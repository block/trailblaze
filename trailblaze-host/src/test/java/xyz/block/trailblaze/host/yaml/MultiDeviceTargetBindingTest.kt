package xyz.block.trailblaze.host.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlin.reflect.KClass
import org.junit.Test
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.toSessionToolRepo
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.resolveToolScopeForDriver
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.yaml.createTrailblazeYamlFromAllTools

/**
 * Session-wide consequences of a configuration whose devices declare different `target:`s: which
 * targets are bound, which custom tools must decode, and how many scripted-tool runtimes launch.
 */
class MultiDeviceTargetBindingTest {

  private val driverType = TrailblazeDriverType.DEFAULT_ANDROID

  /**
   * The regression this exists to prevent: a step recorded on the companion device names a tool
   * only the COMPANION's target declares. An unrecognized tool name doesn't fail the decode — it
   * decodes to an opaque [OtherTrailblazeTool] that no dispatch path can execute — so the decode
   * surface has to be the union of every bound target's tools, not the start device's alone.
   */
  @Test
  fun `recorded steps decode against every bound target's custom tools, not just the start device's`() {
    val boundTargets = MultiDeviceTargetBinding.boundTargets(
      startDeviceTarget = fakeTarget("seller", InputTextTrailblazeTool::class),
      companionTargets = listOf(fakeTarget("buyer", RememberTextTrailblazeTool::class)),
    )
    val companionRecordedStep = """
      - rememberText:
          prompt: the order total
          variable: total
    """.trimIndent()

    val union = MultiDeviceTargetBinding.customToolClasses(boundTargets, driverType)
    val decoded = createTrailblazeYamlFromAllTools(union).decodeTools(companionRecordedStep)
    assertThat(decoded.map { it.trailblazeTool })
      .containsExactly(RememberTextTrailblazeTool(prompt = "the order total", variable = "total"))

    // Start-device tools only: the silent downgrade the union prevents.
    val startOnly = MultiDeviceTargetBinding.customToolClasses(boundTargets.take(1), driverType)
    val downgraded = createTrailblazeYamlFromAllTools(startOnly).decodeTools(companionRecordedStep)
    assertThat(downgraded.map { it.trailblazeTool::class }).containsExactly(OtherTrailblazeTool::class)
  }

  @Test
  fun `a step recorded on a companion of another kind decodes against that kind's tools`() {
    // An iOS launch device beside an Android companion: the Android step names a tool the target
    // declares for the Android driver only.
    val target = object : TrailblazeHostAppTarget(id = "pos", displayName = "pos") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
        listOf("com.example.pos")

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> =
        if (driverType == TrailblazeDriverType.DEFAULT_ANDROID) setOf(RememberTextTrailblazeTool::class) else setOf(InputTextTrailblazeTool::class)
    }
    val companionRecordedStep = """
      - rememberText:
          prompt: the network label
          variable: label
    """.trimIndent()
    val launchTools = MultiDeviceTargetBinding.customToolClasses(listOf(target), TrailblazeDriverType.IOS_HOST)

    val withCompanion = launchTools + MultiDeviceTargetBinding
      .companionDriverToolboxes(listOf(target), setOf(TrailblazeDriverType.DEFAULT_ANDROID))
      .flatMap { it.toolClasses }
    assertThat(createTrailblazeYamlFromAllTools(withCompanion).decodeTools(companionRecordedStep).map { it.trailblazeTool })
      .containsExactly(RememberTextTrailblazeTool(prompt = "the network label", variable = "label"))
    assertThat(createTrailblazeYamlFromAllTools(launchTools).decodeTools(companionRecordedStep).map { it.trailblazeTool::class })
      .containsExactly(OtherTrailblazeTool::class)
    assertThat(
      MultiDeviceTargetBinding.companionDriverToolboxes(listOf(target), setOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE)),
    ).isEmpty()
  }

  @Test
  fun `a browser launch advertises its phone companion's catalog scripted tools, minus exclusions`() {
    // `openUrl` is in the iOS catalog, not the web one, so only the companion's surface can carry it.
    // Read from the LLM's tool list once the companion's driver is widened and `openUrl` registered.
    fun advertised(iosPlatform: String): List<String> {
      val target = AppTargetYamlLoader.loadFromYaml(
        "id: shop\ndisplay_name: Shop\nplatforms:\n  ios:\n    app_ids: [com.example.shop]\n$iosPlatform",
        toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
      )
      val companion = MultiDeviceTargetBinding.companionDriverToolboxes(listOf(target), setOf(TrailblazeDriverType.IOS_HOST))
      val repo = target.toSessionToolRepo(TrailblazeDriverType.PLAYWRIGHT_NATIVE, additional = companion.single())
      repo.widenCatalogToDriver(TrailblazeDriverType.IOS_HOST)
      repo.addDynamicTools(listOf(stubRegistration("openUrl")))
      return repo.getCurrentToolDescriptors().map { it.name }
    }

    assertThat(advertised(iosPlatform = "")).contains("openUrl")
    assertThat(advertised(iosPlatform = "    excluded_tools: [openUrl]\n")).doesNotContain("openUrl")
  }

  @Test
  fun `a companion's always-shown tools stay shown, for each of the session's drivers`() {
    val web = TrailblazeDriverType.PLAYWRIGHT_NATIVE
    val bound = MultiDeviceTargetBinding.boundTargets(
      startDeviceTarget = fakeTarget("seller", alwaysShown = mapOf(driverType to setOf("seller_swipe"))),
      companionTargets = listOf(fakeTarget("buyer", alwaysShown = mapOf(driverType to setOf("buyer_swipe"), web to setOf("buyer_scroll")))),
    )

    assertThat(MultiDeviceTargetBinding.alwaysShownTools(bound, listOf(driverType)))
      .containsOnly("seller_swipe", "buyer_swipe")
    assertThat(MultiDeviceTargetBinding.alwaysShownTools(bound, listOf(driverType, web)))
      .containsOnly("seller_swipe", "buyer_swipe", "buyer_scroll")
  }

  @Test
  fun `bound targets keep the start device first`() {
    val start = fakeTarget("seller")
    val companion = fakeTarget("buyer")

    val bound = MultiDeviceTargetBinding.boundTargets(start, listOf(companion))

    assertThat(bound.map { it.id }).containsExactly("seller", "buyer")
  }

  /**
   * Devices that inherit the session target are the common case — a configuration that pairs two
   * displays of the same app. They must bind ONE target, not one per device, or every downstream
   * per-target surface is duplicated.
   */
  @Test
  fun `devices sharing one target bind it once`() {
    val shared = fakeTarget("seller")

    val bound = MultiDeviceTargetBinding.boundTargets(shared, listOf(shared, shared))

    assertThat(bound.map { it.id }).containsExactly("seller")
  }

  @Test
  fun `a session with no target at all binds nothing`() {
    assertThat(MultiDeviceTargetBinding.boundTargets(null, listOf(null))).isEmpty()
  }

  /**
   * Scripted-tool runtimes are per target, and a runtime carries the device context of a device
   * that runs it. Two targets means two launches, each with ITS device's context — one launch
   * would leave the companion's scripted tools resolving the launch device's app.
   */
  @Test
  fun `each distinct target launches its own scripted-tool runtime with that device's context`() {
    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = fakeTarget("seller"),
      startDeviceDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      startDeviceContext = "start-device",
      companions = listOf(Triple(fakeTarget("buyer"), TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, "companion-device")),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly("seller" to "start-device", "buyer" to "companion-device")
  }

  /**
   * A companion that inherits the session target on the start device's driver shares the start
   * device's runtime: a second launch would register nothing the first did not.
   */
  @Test
  fun `a companion inheriting the session target on the same driver adds no launch`() {
    val shared = fakeTarget("seller")

    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = shared,
      startDeviceDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      startDeviceContext = "start-device",
      companions = listOf(Triple(shared, TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, "companion-device")),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly("seller" to "start-device")
  }

  /**
   * A target's inline scripted tools register per driver, so a launch for the start device's
   * driver leaves out the target's tools for a companion's other platform, e.g. its iOS sign-in.
   */
  @Test
  fun `a companion sharing the session target on another driver launches it with its own context`() {
    val shared = fakeTarget("seller")

    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = shared,
      startDeviceDriver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      startDeviceContext = "browser",
      companions = listOf(Triple(shared, TrailblazeDriverType.IOS_HOST, "iphone")),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly("seller" to "browser", "seller" to "iphone")
  }

  /**
   * A target's `excluded_tools:` are judged on the drivers of the devices that run it. A browser
   * companion inheriting the session target runs the session target, not a second target bound to
   * an Android companion, so its driver must not unhide a tool that second target excludes.
   */
  @Test
  fun `each target is run only by its own devices, and an untargeted companion runs the session's`() {
    val seller = fakeTarget("seller")
    val buyer = fakeTarget("buyer")
    val companions = listOf(buyer to "buyer-device", null to "browser")

    fun devicesRunning(target: TrailblazeHostAppTarget) = MultiDeviceTargetBinding.devicesRunning(
      target = target,
      startDeviceTarget = seller,
      startDeviceContext = "start-device",
      companions = companions,
    )

    assertThat(devicesRunning(seller)).containsExactly("start-device", "browser")
    assertThat(devicesRunning(buyer)).containsExactly("buyer-device")
  }

  /**
   * The launch plan and `excluded_tools:` judgement read one companion list, so for each bound
   * target the drivers its runtimes launch on are the drivers of the devices that run it.
   */
  @Test
  fun `each target launches on exactly the drivers of the devices that run it`() {
    val seller = fakeTarget("seller")
    val buyer = fakeTarget("buyer")
    val web = TrailblazeDriverType.PLAYWRIGHT_NATIVE
    val ios = TrailblazeDriverType.IOS_HOST
    val android = TrailblazeDriverType.DEFAULT_ANDROID
    val companions = listOf(Triple(seller, ios, ios), Triple(buyer, android, android), Triple(buyer, android, android))

    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(seller, web, web, companions)

    listOf(seller, buyer).forEach { target ->
      val running = MultiDeviceTargetBinding.devicesRunning(
        target = target,
        startDeviceTarget = seller,
        startDeviceContext = web,
        companions = companions.map { (companionTarget, _, driver) -> companionTarget to driver },
      )
      assertThat(plan.filter { (planTarget, _) -> planTarget?.id == target.id }.map { it.second })
        .containsExactly(*running.distinct().toTypedArray())
    }
  }

  /**
   * A session with no target still launches one runtime for the start device — that is the
   * single-device path, where scripted tools come from the workspace rather than a target.
   */
  @Test
  fun `a session with no target still launches the start device's runtime`() {
    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = null,
      startDeviceDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      startDeviceContext = "start-device",
      companions = emptyList<Triple<TrailblazeHostAppTarget?, TrailblazeDriverType, String>>(),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly(null to "start-device")
  }

  /**
   * A target gets most of its tools from the `tool_sets:` its trailmap declares, and the session
   * repo's base catalog is scoped to the START device's target. Contributing only the companion's
   * direct custom tools leaves every tool from a toolset the start device doesn't declare out of
   * the repo — the recorded step decodes and then dies at dispatch as an unknown tool.
   */
  @Test
  fun `additions carry a companion's whole resolved scope, not just its own custom tools`() {
    val start = fakeTarget("seller", toolSetIds = listOf("navigation"))
    val companion = fakeTarget("buyer", toolSetIds = listOf("navigation", "memory"))
    val boundTargets = MultiDeviceTargetBinding.boundTargets(start, listOf(companion))

    val additions =
      MultiDeviceTargetBinding.companionToolAdditions(boundTargets, start, driverType)

    val memoryOnly = companion.resolveToolScopeForDriver(driverType).toolClasses -
      start.resolveToolScopeForDriver(driverType).toolClasses
    assertThat(memoryOnly).isNotEmpty()
    assertThat(additions.toolClasses).containsAtLeast(*memoryOnly.toTypedArray())
  }

  /**
   * [MultiDeviceTargetBinding.boundTargets] puts the first non-null COMPANION at index 0 when the
   * session has no start-device target (no `config.target:`, every override on a companion). A
   * position-based drop would discard that companion's tools, and the null-receiver base
   * contributes nothing to replace them.
   */
  @Test
  fun `a session with no start-device target still contributes its companions' tools`() {
    val companion = fakeTarget("buyer", toolSetIds = listOf("memory"))
    val boundTargets = MultiDeviceTargetBinding.boundTargets(null, listOf(companion))

    val additions =
      MultiDeviceTargetBinding.companionToolAdditions(boundTargets, null, driverType)

    assertThat(additions.toolClasses)
      .containsAtLeast(*companion.resolveToolScopeForDriver(driverType).toolClasses.toTypedArray())
  }

  /** A companion inheriting the session target is the same target — the base already carries it. */
  @Test
  fun `a companion inheriting the session target contributes no additions`() {
    val shared = fakeTarget("seller", toolSetIds = listOf("navigation"))
    val boundTargets = MultiDeviceTargetBinding.boundTargets(shared, listOf(shared))

    val additions =
      MultiDeviceTargetBinding.companionToolAdditions(boundTargets, shared, driverType)

    assertThat(additions.toolClasses).isEmpty()
    assertThat(additions.scriptedToolNames).isEmpty()
  }

  /**
   * The defect per-device targets exist to fix. A companion used to be handed the LAUNCH device's
   * whole [xyz.block.trailblaze.model.ResolvedTarget] — device id included — so every companion
   * reported the launch device's target and probed the launch device's installed packages, whether
   * or not the configuration declared different apps.
   */
  @Test
  fun `each device's resolved target carries that device's own id, never the launch device's`() {
    val startDevice = androidDevice("emulator-5560")
    val companionDevice = androidDevice("emulator-5562")

    val start = MultiDeviceTargetBinding.agentResolvedTarget(fakeTarget("seller"), startDevice)
    val companion =
      MultiDeviceTargetBinding.agentResolvedTarget(fakeTarget("buyer"), companionDevice)

    assertThat(start?.id).isEqualTo("seller")
    assertThat(start?.deviceId).isEqualTo(startDevice)
    assertThat(companion?.id).isEqualTo("buyer")
    assertThat(companion?.deviceId).isEqualTo(companionDevice)
  }

  /**
   * The same-target case is where the old defect was invisible and still wrong: both devices run
   * one app, so the target id matched, but the companion probed the LAUNCH device for its app id.
   * A shared target must still produce a per-device resolution.
   */
  @Test
  fun `a companion inheriting the session target still resolves against its own device`() {
    val shared = fakeTarget("seller")
    val companionDevice = androidDevice("emulator-5562")

    val start = MultiDeviceTargetBinding.agentResolvedTarget(shared, androidDevice("emulator-5560"))
    val companion = MultiDeviceTargetBinding.agentResolvedTarget(shared, companionDevice)

    assertThat(companion?.deviceId).isEqualTo(companionDevice)
    assertThat(companion?.deviceId).isNotEqualTo(start?.deviceId)
  }

  /** A device with no target at all — `ctx.target` is legitimately undefined there. */
  @Test
  fun `a device with no target resolves to nothing`() {
    assertThat(MultiDeviceTargetBinding.agentResolvedTarget(null, androidDevice("emulator-5560")))
      .isNull()
  }

  /**
   * `switchDevice` is advertised because the SESSION bound a cast, not because a target asked for
   * it — no app's trailmap can know whether it was cast into a pair.
   */
  @Test
  fun `a multi-device session adds the handover tool and a single-device session adds nothing`() {
    val multiDevice = MultiDeviceTargetBinding.handoverToolSurface(isMultiDeviceSession = true)
    // Contains, not "contains only": the exact set is the catalog entry's, pinned by the test
    // below. Growing `multi_device.yaml` should reach a session, not fail an assertion here.
    assertThat(multiDevice.toolClasses).contains(SwitchDeviceTrailblazeTool::class)

    val singleDevice = MultiDeviceTargetBinding.handoverToolSurface(isMultiDeviceSession = false)
    assertThat(singleDevice.toolClasses).isEmpty()
    assertThat(singleDevice.scriptedToolNames).isEmpty()
  }

  /**
   * The surface reads the `multi_device` catalog entry rather than naming the tool class directly,
   * so the YAML toolset stays the source of truth for what the toolset contains — adding a second
   * tool to `multi_device.yaml` reaches a session without touching this code.
   */
  @Test
  fun `the handover surface is read from the multi_device catalog entry`() {
    val entryTools = TrailblazeToolSetCatalog.entryToolClasses(
      SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID,
    )
    assertThat(entryTools).isNotEmpty()

    assertThat(MultiDeviceTargetBinding.handoverToolSurface(isMultiDeviceSession = true).toolClasses)
      .isEqualTo(entryTools)
  }

  /**
   * Both name kinds come from the entry, not just the class-backed one. `multi_device.yaml`
   * declares only `switchDevice` today, so this drives a catalog that also carries a scripted
   * tool — against the real catalog the scripted assertion would hold on an empty set whether
   * the surface read it or not.
   */
  @Test
  fun `every kind of tool the handover toolset declares reaches the surface`() {
    val entry = ToolSetCatalogEntry(
      id = SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID,
      description = "handover toolset carrying one tool of each kind",
      toolClasses = setOf(SwitchDeviceTrailblazeTool::class),
      scriptedToolNames = setOf(ToolName("multi_device_scripted_tool")),
    )

    val surface = MultiDeviceTargetBinding.handoverToolSurface(
      isMultiDeviceSession = true,
      catalog = listOf(entry),
    )

    assertThat(surface.toolClasses).containsOnly(SwitchDeviceTrailblazeTool::class)
    assertThat(surface.scriptedToolNames).containsOnly(ToolName("multi_device_scripted_tool"))
  }

  private fun androidDevice(instanceId: String) = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun fakeTarget(
    id: String,
    vararg customTools: KClass<out TrailblazeTool>,
    toolSetIds: List<String> = emptyList(),
    alwaysShown: Map<TrailblazeDriverType, Set<String>> = emptyMap(),
  ): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(id = id, displayName = id) {
    override fun getAlwaysShownToolNamesForDriver(driverType: TrailblazeDriverType): Set<String> =
      alwaysShown[driverType].orEmpty()

    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.$id")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = customTools.toSet()

    // Declaring none means UNCONFIGURED, which resolves to the whole driver catalog — the two
    // targets would then have identical scopes and no test here could tell them apart.
    override fun getDeclaredToolSetIdsForDriver(driverType: TrailblazeDriverType): List<String> =
      toolSetIds
  }

  private fun stubRegistration(toolName: String): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor = TrailblazeToolDescriptor(name = toolName)

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("not built in this test")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = error("not dispatched in this test")
    }
}

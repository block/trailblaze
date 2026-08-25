package xyz.block.trailblaze.host.yaml

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlin.reflect.KClass
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.resolveToolScopeForDriver
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
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
      startDeviceContext = "start-device",
      companions = listOf(fakeTarget("buyer") to "companion-device"),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly("seller" to "start-device", "buyer" to "companion-device")
  }

  /**
   * A companion that inherits the session target shares the start device's runtime. Launching a
   * second runtime for the same target would register the same tool names twice.
   */
  @Test
  fun `a companion inheriting the session target adds no launch`() {
    val shared = fakeTarget("seller")

    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = shared,
      startDeviceContext = "start-device",
      companions = listOf(shared to "companion-device"),
    )

    assertThat(plan.map { (target, context) -> target?.id to context })
      .containsExactly("seller" to "start-device")
  }

  /**
   * A session with no target still launches one runtime for the start device — that is the
   * single-device path, where scripted tools come from the workspace rather than a target.
   */
  @Test
  fun `a session with no target still launches the start device's runtime`() {
    val plan = MultiDeviceTargetBinding.scriptedToolLaunchPlan(
      startDeviceTarget = null,
      startDeviceContext = "start-device",
      companions = emptyList(),
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
    assertThat(additions.yamlToolNames).isEmpty()
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

  private fun androidDevice(instanceId: String) = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun fakeTarget(
    id: String,
    vararg customTools: KClass<out TrailblazeTool>,
    toolSetIds: List<String> = emptyList(),
  ): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(id = id, displayName = id) {
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
}

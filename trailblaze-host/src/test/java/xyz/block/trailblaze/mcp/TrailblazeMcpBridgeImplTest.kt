package xyz.block.trailblaze.mcp

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertWaypointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.RunCommandTrailblazeTool

/**
 * Pins the pure helpers `executeTrailblazeTool` is built out of — where a tool is routed, how a
 * composed YAML tool expands, and what the CLI prints for a completed tool.
 *
 * Both routing helpers are load-bearing for the same failure mode: a tool that can only run on
 * the daemon JVM being shipped to the device, which has no registration for it and answers
 * "Unknown tool". `resolveToolDispatchRoute` decides host vs device;
 * `expandDelegatingToolHostSide` flattens a `requires_host = true` composition so each child
 * routes on its own.
 *
 * The recursive per-child dispatch in `expandDelegatingToolAndDispatch` is covered end-to-end
 * via the OSS smoke (`trailblaze tool wikipedia_back_safe` against the wikipedia reproducer
 * trailmap). This file pins the decisions those paths are made of, plus the assembly of the
 * host-local execution context out of them.
 *
 * ## What a green run here does NOT cover
 *
 * `executeHostLocalToolOnDaemon` still gathers the inputs (probing the connected driver, the
 * logs repo, the per-device target override) and sequences the dispatch around them, and that
 * gathering needs a live daemon. Two behaviors live there and are unpinned:
 *  - the cached screen state being invalidated before `tool.execute`, so a tool that moves the
 *    UI through nested calls can't leave a stale snapshot behind on a throw;
 *  - the logger falling back to a no-op when there is no logs repo, which silently drops the
 *    tool's own log output rather than failing the dispatch.
 */
class TrailblazeMcpBridgeImplTest {

  @Test
  fun `androidDisconnectStatus reports only a missing Android serial`() {
    val android = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val ios = TrailblazeDeviceId(
      instanceId = "SIM-UUID",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
    )

    assertEquals(null, TrailblazeMcpBridgeImpl.androidDisconnectStatus(android, listOf(android)))
    assertTrue(
      TrailblazeMcpBridgeImpl.androidDisconnectStatus(android, emptyList())
        ?.contains("emulator-5554") == true,
    )
    assertEquals(null, TrailblazeMcpBridgeImpl.androidDisconnectStatus(ios, emptyList()))
  }

  @Test
  fun `expandDelegatingToolHostSide flattens YAML composed tool into executable primitives`() {
    // A typical workspace pure-YAML composed tool: `requires_host: true` (added by
    // `AppTargetDiscovery.registerWorkspaceYamlTools` when null), `tools:` body that
    // wraps a maestro back press.
    val config = parse(
      """
      id: test_back_safe
      description: Wraps the maestro back primitive for the test.
      requires_host: true
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    val expanded: List<ExecutableTrailblazeTool> =
      TrailblazeMcpBridgeImpl.expandDelegatingToolHostSide(
        tool = tool,
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-emu",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        traceId = null,
      )

    // Crucial regression pins:
    // 1. Expansion produced exactly the children the YAML body declares (one maestro back).
    assertEquals(
      1,
      expanded.size,
      "Expansion should produce one primitive per `tools:` body entry; got ${expanded.size}",
    )
    // 2. The child is an `ExecutableTrailblazeTool` (per the return type), not a wrapper.
    val child = assertNotNull(expanded.firstOrNull(), "Expansion must produce a non-null child")
    // 3. CRITICAL: the child is NOT itself a DelegatingTrailblazeTool — if it were, the
    //    recursive dispatch in `expandDelegatingToolAndDispatch` could re-enter the
    //    host-expansion branch on the child, producing nested-recursion. The framework's
    //    `YamlDefinedTrailblazeTool.toExecutableTrailblazeTools` enforces this via a cast
    //    + `?: error(...)`, but we re-assert it here so a future refactor of the framework
    //    can't silently break the no-nested-expansion invariant the bridge relies on.
    assertTrue(
      child !is DelegatingTrailblazeTool,
      "Expanded child must be an executable primitive, not another delegating tool — " +
        "host-expansion's recursive dispatch assumes non-delegating children. Got: ${child::class.simpleName}",
    )
  }

  @Test
  fun `expandDelegatingToolHostSide handles multi-child compositions in order`() {
    // Pin that expansion preserves YAML declaration order — the recursive dispatcher
    // calls `executeTrailblazeTool` on each child sequentially, so order is observable.
    val config = parse(
      """
      id: test_multi
      description: Multiple primitives in declaration order.
      requires_host: true
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
        - mobile_maestro:
            commands:
              - back: {}
        - mobile_maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    val expanded = TrailblazeMcpBridgeImpl.expandDelegatingToolHostSide(
      tool = tool,
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-emu",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      traceId = null,
    )

    assertEquals(
      3,
      expanded.size,
      "Three `tools:` entries should produce three expanded children; got ${expanded.size}",
    )
    expanded.forEachIndexed { idx, child ->
      assertTrue(
        child !is DelegatingTrailblazeTool,
        "Child at index $idx must be an executable primitive (not a delegating wrapper); " +
          "got ${child::class.simpleName}",
      )
    }
  }

  // -- renderExecutionResult: what `trailblaze tool` prints for the HOST/Maestro blocking path --
  //
  // This is the pure helper the HOST-blocking branch of `executeTrailblazeTool` now routes its
  // completion result through, so a read tool run via `trailblaze tool` shows its real return
  // value instead of a generic "Executed …" acknowledgement. Pins the observable contract:
  // structuredContent wins over message, message wins over the fallback, and Failed / Cancelled
  // throw so the CLI reports a non-zero exit.

  private val fallback = "Executed FooTool on device test-emu"

  @Test
  fun `renderExecutionResult prefers structured content over message and fallback`() {
    val structured = buildJsonObject { put("count", JsonPrimitive(3)) }
    val rendered = TrailblazeMcpBridgeImpl.renderExecutionResult(
      result = TrailExecutionResult.Success(
        toolMessage = "human readable message",
        toolStructuredContent = structured,
      ),
      fallback = fallback,
    )
    // The typed return value the caller/device receives is the structured content serialized
    // verbatim — the message and fallback are fully ignored when structured content is present.
    assertEquals(
      TrailblazeJsonInstance.encodeToString(JsonElement.serializer(), structured),
      rendered,
    )
  }

  @Test
  fun `renderExecutionResult surfaces the tool message when there is no structured content`() {
    val rendered = TrailblazeMcpBridgeImpl.renderExecutionResult(
      result = TrailExecutionResult.Success(toolMessage = "com.example.app is not installed"),
      fallback = fallback,
    )
    assertEquals("com.example.app is not installed", rendered)
  }

  @Test
  fun `renderExecutionResult falls back for an action tool with no payload`() {
    // A tap/swipe-style Success carries neither message nor structured content — the caller
    // should still see the generic acknowledgement rather than an empty string.
    assertEquals(fallback, TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Success(), fallback))
    // A blank message is treated the same as none.
    assertEquals(
      fallback,
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Success(toolMessage = "   "), fallback),
    )
  }

  @Test
  fun `renderExecutionResult throws on failure and cancellation`() {
    assertFailsWith<IllegalStateException> {
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Failed("boom"), fallback)
    }
    assertFailsWith<IllegalStateException> {
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Cancelled, fallback)
    }
  }

  // ── Host-local vs device routing ────────────────────────────────────────────────────────
  //
  // `trailblaze tool <name>` reaches the device through `executeTrailblazeTool`, whose head is
  // this pure decision. A host-local tool's runtime (in-process QuickJS engine, subprocess MCP
  // client, host-side registry) exists only on the daemon JVM, so a HOST_LOCAL verdict is the
  // only thing keeping it off the device's RPC path — where it fails with "Unknown tool" even
  // though the identical tool dispatches fine inside a trail run. Every case below uses a real
  // production tool so the assertion is about the shipped surface, not a fixture's shape.

  @Test
  fun `a HostLocalExecutableTrailblazeTool routes to the host`() {
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.HOST_LOCAL,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(ExecTrailblazeTool(argv = listOf("printf", "hi"))),
    )
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.HOST_LOCAL,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(RunCommandTrailblazeTool(command = "printf hi")),
    )
  }

  @Test
  fun `an executable tool annotated requiresHost routes to the host`() {
    // `assertWaypoint` carries no HostLocal marker — the class-level `requiresHost = true` is
    // the only signal, and it has to be honored on its own (the waypoint registry it reads is
    // host-side).
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.HOST_LOCAL,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(
        AssertWaypointTrailblazeTool(waypoint = "example/android/home"),
      ),
    )
  }

  @Test
  fun `an ordinary device tool still routes to the device`() {
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.DEVICE,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(InputTextTrailblazeTool(text = "hello")),
    )
  }

  @Test
  fun `an unresolved tool placeholder routes to the device`() {
    // Not executable and not delegating — nothing to run on the host, so the device path (and
    // its "unknown tool" diagnostics) stays the right answer.
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.DEVICE,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(OtherTrailblazeTool(toolName = "notATool")),
    )
  }

  @Test
  fun `a composed YAML tool expands host-side only when it requires the host`() {
    val body = """
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
    """.trimIndent()

    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.HOST_EXPAND,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(
        YamlDefinedTrailblazeTool(
          config = parse("id: host_back\ndescription: Host-composed back.\nrequires_host: true\n$body"),
          params = emptyMap(),
        ),
      ),
    )
    // Without the flag the whole composition ships to the device, exactly as before.
    assertEquals(
      TrailblazeMcpBridgeImpl.ToolDispatchRoute.DEVICE,
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(
        YamlDefinedTrailblazeTool(
          config = parse("id: device_back\ndescription: Device-composed back.\n$body"),
          params = emptyMap(),
        ),
      ),
    )
  }

  // ── Host-local dispatch context ─────────────────────────────────────────────────────────
  //
  // Routing a tool to the host is only half the job — the context it runs against has to carry
  // the same signals the trail-run path gives it, or the reroute trades an "Unknown tool" for a
  // subtler wrong answer. Each decision below is pure so it pins without a device.

  private val androidDevice = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  @Test
  fun `a connected host driver serves screen state when there is one`() {
    // Wins even on an on-device driver: if a host driver is connected it is the cheaper and more
    // faithful source, and the RPC path is the fallback rather than the preference.
    assertEquals(
      TrailblazeMcpBridgeImpl.HostLocalScreenStateSource.DIRECT_DRIVER,
      TrailblazeMcpBridgeImpl.resolveHostLocalScreenStateSource(
        hasDirectProvider = true,
        isOnDeviceInstrumentation = true,
      ),
    )
  }

  @Test
  fun `on-device drivers fall back to the RPC screen state instead of having none`() {
    // The load-bearing case. Device setup skips createPersistentDevice for ACCESSIBILITY /
    // INSTRUMENTATION, so there is no host driver to capture with. Returning NONE here is what
    // made `trailblaze tool assertWaypoint` fail with "requires a live screen-state provider".
    assertEquals(
      TrailblazeMcpBridgeImpl.HostLocalScreenStateSource.ON_DEVICE_RPC,
      TrailblazeMcpBridgeImpl.resolveHostLocalScreenStateSource(
        hasDirectProvider = false,
        isOnDeviceInstrumentation = true,
      ),
    )
  }

  @Test
  fun `no driver and no on-device agent means no screen state`() {
    // NONE has to stay reachable: tools branch on a null provider to mean "no state available",
    // so manufacturing a provider that always throws would be worse than admitting there is none.
    assertEquals(
      TrailblazeMcpBridgeImpl.HostLocalScreenStateSource.NONE,
      TrailblazeMcpBridgeImpl.resolveHostLocalScreenStateSource(
        hasDirectProvider = false,
        isOnDeviceInstrumentation = false,
      ),
    )
  }

  @Test
  fun `device info prefers the connected driver's real size`() {
    val info = TrailblazeMcpBridgeImpl.hostLocalDeviceInfo(
      deviceId = androidDevice,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      driverDimensions = 1440 to 3120,
    )
    assertEquals(1440, info.widthPixels)
    assertEquals(3120, info.heightPixels)
  }

  @Test
  fun `device info falls back to the standard sizing when no driver reported one`() {
    // On-device drivers have no host driver to probe, so this fallback is the normal path there,
    // not an edge case.
    val info = TrailblazeMcpBridgeImpl.hostLocalDeviceInfo(
      deviceId = androidDevice,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      driverDimensions = null,
    )
    assertEquals(1080, info.widthPixels)
    assertEquals(2400, info.heightPixels)
  }

  @Test
  fun `a target that declares no app ids never probes the device`() {
    // The probe is a `pm list packages` / `simctl listapps` round trip per dispatch. For a web
    // target or a pure API pack it can only ever return null, so paying for it is pure waste.
    var probed = false
    val appId = TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
      declaredAppIds = null,
      probeInstalledAppIds = { probed = true; emptySet() },
      pickInstalledAppId = { error("must not be reached") },
    )
    assertEquals(null, appId)
    assertFalse(probed, "A target declaring no app ids must not trigger the installed-apps probe")

    // An empty declared list is the same case as null.
    var probedForEmpty = false
    TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
      declaredAppIds = emptyList(),
      probeInstalledAppIds = { probedForEmpty = true; emptySet() },
      pickInstalledAppId = { error("must not be reached") },
    )
    assertFalse(probedForEmpty, "An empty declared list must not trigger the probe either")
  }

  @Test
  fun `app id resolution returns the installed candidate`() {
    val appId = TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
      declaredAppIds = listOf("com.example.primary", "com.example.fallback"),
      probeInstalledAppIds = { setOf("com.example.fallback", "com.other.app") },
      pickInstalledAppId = { installed -> installed.firstOrNull { it == "com.example.fallback" } },
    )
    assertEquals("com.example.fallback", appId)
  }

  @Test
  fun `a failing device probe yields no app id rather than throwing`() {
    // Non-throwing by contract: a scripted tool falls back to `ctx.target.appIds[0]` and lets the
    // launch fail downstream with a clearer message. Throwing here would kill the whole dispatch
    // over a best-effort enrichment.
    assertEquals(
      null,
      TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
        declaredAppIds = listOf("com.example.primary"),
        probeInstalledAppIds = { throw IllegalStateException("adb offline") },
        pickInstalledAppId = { error("must not be reached") },
      ),
    )
    // Nothing installed is a null, not a throw.
    assertEquals(
      null,
      TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
        declaredAppIds = listOf("com.example.primary"),
        probeInstalledAppIds = { setOf("com.unrelated") },
        pickInstalledAppId = { null },
      ),
    )
  }

  @Test
  fun `cancellation during the app id probe aborts rather than resolving to no app id`() {
    // The one exception the non-throwing contract above must NOT absorb. This runs while the
    // dispatch context is still being built, so turning an aborted run into "no app id" would let
    // the dispatch continue against a torn-down session.
    assertFailsWith<CancellationException> {
      TrailblazeMcpBridgeImpl.resolveHostLocalAppId(
        declaredAppIds = listOf("com.example.primary"),
        probeInstalledAppIds = { throw CancellationException("session torn down") },
        pickInstalledAppId = { error("must not be reached") },
      )
    }
  }

  @Test
  fun `nested tool calls inherit the parent dispatch's trace id`() {
    // One logical tool call must land under one trace. Forwarding the caller's original nullable
    // id instead would let each nested dispatch mint its own and scatter the call tree.
    val parentTraceId = TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP)
    val forwarded = mutableListOf<TraceId?>()
    val executor = TrailblazeMcpBridgeImpl.hostLocalNestedToolExecutor(parentTraceId) { _, traceId ->
      forwarded += traceId
      "ok"
    }

    val result = runBlocking { executor(InputTextTrailblazeTool(text = "hello")) }

    assertEquals(listOf<TraceId?>(parentTraceId), forwarded)
    assertEquals("ok", (result as TrailblazeToolResult.Success).message)
  }

  @Test
  fun `a nested tool failure surfaces to the calling tool instead of unwinding the dispatch`() {
    val executor = TrailblazeMcpBridgeImpl.hostLocalNestedToolExecutor(
      TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP),
    ) { _, _ -> throw IllegalStateException("device went away") }

    val result = runBlocking { executor(InputTextTrailblazeTool(text = "hello")) }

    // Typed error, not a thrown exception — the composing tool gets to decide what to do.
    val error = assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(
      error.errorMessage.contains("device went away"),
      "The nested failure's cause must survive into the error message; got: ${error.errorMessage}",
    )
  }

  @Test
  fun `cancellation propagates through nested dispatch rather than becoming a tool error`() {
    // Swallowing cancellation would break session teardown — an aborted run would keep
    // dispatching nested calls as though nothing happened.
    val executor = TrailblazeMcpBridgeImpl.hostLocalNestedToolExecutor(
      TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP),
    ) { _, _ -> throw CancellationException("run aborted") }

    assertFailsWith<CancellationException> {
      runBlocking { executor(InputTextTrailblazeTool(text = "hello")) }
    }
  }

  // ── Host-local dispatch context: wiring ─────────────────────────────────────────────────
  //
  // The decisions above are only half of it. A helper that returns the right answer into the
  // wrong context field is exactly as broken as one that returns the wrong answer, and the
  // original bug lived in wiring rather than in any single decision. These pin the assembled
  // context, so dropping an input or crossing two of them fails here.

  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private object FakeAppTarget : TrailblazeHostAppTarget(id = "fake-target", displayName = "Fake Target") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.declared")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private fun buildContext(
    deviceInfo: TrailblazeDeviceInfo = TrailblazeMcpBridgeImpl.hostLocalDeviceInfo(
      deviceId = androidDevice,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      driverDimensions = null,
    ),
    session: TrailblazeSession = TrailblazeSession(
      sessionId = SessionId("host-local-session"),
      startTime = Clock.System.now(),
    ),
    traceId: TraceId = TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP),
    screenStateProvider: (() -> ScreenState)? = null,
    trailblazeLogger: TrailblazeLogger = TrailblazeLogger.createNoOp(),
    resolvedTarget: ResolvedTarget? = ResolvedTarget(target = FakeAppTarget, deviceId = androidDevice),
    appId: String? = "com.example.installed",
    toolRepo: TrailblazeToolRepo = TrailblazeToolRepo.withDynamicToolSets(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    ),
    sessionDirProvider: ((SessionId) -> File)? = null,
    dispatchNested: suspend (TrailblazeTool, TraceId?) -> String = { _, _ -> "ok" },
  ) = TrailblazeMcpBridgeImpl.buildHostLocalToolContext(
    deviceInfo = deviceInfo,
    session = session,
    traceId = traceId,
    screenStateProvider = screenStateProvider,
    trailblazeLogger = trailblazeLogger,
    resolvedTarget = resolvedTarget,
    appId = appId,
    toolRepo = toolRepo,
    sessionDirProvider = sessionDirProvider,
    dispatchNested = dispatchNested,
  )

  @Test
  fun `the dispatch context carries every resolved input into its own slot`() {
    // Distinct values per slot on purpose: a context that crosses two inputs, or drops one to a
    // hardcoded null, reads differently here.
    val deviceInfo = TrailblazeMcpBridgeImpl.hostLocalDeviceInfo(
      deviceId = androidDevice,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      driverDimensions = 1440 to 3120,
    )
    val session = TrailblazeSession(
      sessionId = SessionId("host-local-session"),
      startTime = Clock.System.now(),
    )
    val traceId = TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP)
    val logger = TrailblazeLogger.createNoOp()
    val target = ResolvedTarget(target = FakeAppTarget, deviceId = androidDevice)
    val toolRepo = TrailblazeToolRepo.withDynamicToolSets(
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    val sessionDirProvider: (SessionId) -> File = { File("/tmp/${it.value}") }

    val ctx = buildContext(
      deviceInfo = deviceInfo,
      session = session,
      traceId = traceId,
      trailblazeLogger = logger,
      resolvedTarget = target,
      appId = "com.example.installed",
      toolRepo = toolRepo,
      sessionDirProvider = sessionDirProvider,
    )

    assertSame(deviceInfo, ctx.trailblazeDeviceInfo)
    assertSame(session, ctx.sessionProvider.invoke())
    assertEquals(traceId, ctx.traceId)
    assertSame(logger, ctx.trailblazeLogger)
    assertSame(target, ctx.resolvedTarget)
    // The device-resolved id, not the target's declared candidate — those differ here so a
    // context that re-derived it from `resolvedTarget` would read "com.example.declared".
    assertEquals("com.example.installed", ctx.appId)
    assertSame(toolRepo, ctx.toolRepo)
    assertSame(sessionDirProvider, ctx.sessionDirProvider)
  }

  @Test
  fun `the dispatch context leaves screen state to be captured on read`() {
    // `screenState` starts null so a tool that never reads it never pays for a capture, and a
    // tool that does read it sees the CURRENT screen rather than a snapshot taken at build time.
    var captures = 0
    val captured = FakeScreenState()
    val ctx = buildContext(screenStateProvider = { captures++; captured })

    assertEquals(0, captures, "Building the context must not capture the screen")
    assertSame(captured, ctx.screenState)
    assertEquals(1, captures)
  }

  @Test
  fun `a context built without a screen source reads no screen state`() {
    // The NONE arm of the source decision has to survive into the context: verification tools
    // branch on a null provider to mean "no state available".
    assertEquals(null, buildContext(screenStateProvider = null).screenState)
  }

  @Test
  fun `nested calls made through the dispatch context inherit its trace id`() {
    // The executor is assembled inside the builder rather than by the caller, so the parent trace
    // id cannot be forgotten at the call site. This pins that it is the CONTEXT's id being
    // forwarded, not a freshly minted one.
    val traceId = TraceId.generate(origin = TraceId.Companion.TraceOrigin.MCP)
    val forwarded = mutableListOf<TraceId?>()
    val ctx = buildContext(traceId = traceId) { _, nestedTraceId ->
      forwarded += nestedTraceId
      "nested ok"
    }

    val executor = assertNotNull(ctx.nestedToolExecutor, "Host-local tools dispatch nested calls through this")
    val result = runBlocking { executor(InputTextTrailblazeTool(text = "hello")) }

    assertEquals(listOf<TraceId?>(traceId), forwarded)
    assertEquals(ctx.traceId, forwarded.single())
    assertEquals("nested ok", (result as TrailblazeToolResult.Success).message)
  }

  @Test
  fun `each dispatch gets its own memory`() {
    // A one-shot dispatch has no run to inherit from. Sharing memory across dispatches would leak
    // one `trailblaze tool` invocation's remembered values into the next.
    val first = buildContext()
    val second = buildContext()

    assertNotSame(first.memory, second.memory)
  }

  private fun parse(yaml: String): ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      .also { it.validate() }
}

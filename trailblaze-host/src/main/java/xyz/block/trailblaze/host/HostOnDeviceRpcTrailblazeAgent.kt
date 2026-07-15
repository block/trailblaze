package xyz.block.trailblaze.host

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.requiresHostInstance
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import kotlin.reflect.KClass

/**
 * Host-side [MaestroTrailblazeAgent] that delegates individual tool executions to an on-device
 * driver (accessibility or instrumentation) via RPC, while keeping the
 * [xyz.block.trailblaze.agent.TrailblazeRunner] agent loop (LLM calls, tool selection)
 * running on the host.
 *
 * Each tool call is serialized as single-step trail YAML and sent to the device as a
 * [RunYamlRequest] with [AgentImplementation.TRAILBLAZE_RUNNER] and
 * [RunYamlRequest.awaitCompletion] = `true`. The device executes the tool via whichever
 * driver is specified in the request's `driverType` and the terminal state comes back
 * inline on the response — no status polling.
 *
 * This mirrors the [HostAccessibilityRpcClient] pattern used by Multi-Agent V3, but integrated
 * with the [MaestroTrailblazeAgent] interface so it works with the legacy TrailblazeRunner.
 */
class HostOnDeviceRpcTrailblazeAgent(
  private val rpcClient: OnDeviceRpcClient,
  private val runYamlRequestTemplate: RunYamlRequest,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  /**
   * Whether mid-trail re-warm probes must confirm the accessibility service is bound. Should
   * be true when this agent drives `ANDROID_ONDEVICE_ACCESSIBILITY` so a post-blip UiAutomator
   * fallback can't silently take over; false for instrumentation-driver flows.
   */
  private val requireAndroidAccessibilityServiceOnRewarm: Boolean = false,
  /**
   * Budget for mid-trail re-warm probes after a transient RPC failure. Bounds the wall-clock
   * cost of the recovery path so a permanently-broken connection fails fast rather than
   * stalling each tool call for the full default budget. Tests override to ~50ms so the
   * circuit-breaker state machine can be exercised without paying real-clock retry delays.
   */
  private val reWarmTimeoutMs: Long = 10_000L,
  /**
   * Poll interval inside [OnDeviceRpcClient.waitForReady] during re-warm. Tests override to
   * ~1ms so the loop body runs quickly when the test wants to force the timeout to fire;
   * production stays at the readiness-probe default. Matches `OnDeviceRpcClient.waitForReady`'s
   * default — if you change one, audit the other.
   */
  private val reWarmPollIntervalMs: Long = 500L,
  /**
   * Session tool repo — threaded to [MaestroTrailblazeAgent] (and then [BaseTrailblazeAgent])
   * so an [OtherTrailblazeTool] naming a subprocess MCP tool in a trail YAML resolves to its
   * registered [SubprocessTrailblazeTool][xyz.block.trailblaze.scripting.subprocess.SubprocessTrailblazeTool]
   * instead of hitting "Unsupported tool type for RPC execution".
   */
  trailblazeToolRepo: TrailblazeToolRepo? = null,
  /**
   * Pre-resolved session target — threaded into every [TrailblazeToolExecutionContext] this
   * agent builds so in-process scripted-tool handlers can read `ctx.target.{id, appIds,
   * appId}` without per-call device probes. Mirrors the V3 wiring in
   * `HostAccessibilityRpcClient`: the host-side caller resolves the target once at session
   * start and passes it in here. Defaults to null to preserve back-compat with callers that
   * don't yet wire it (their in-process scripted tools will see `ctx.target` as undefined,
   * which is the pre-#2904 behaviour).
   */
  resolvedTarget: xyz.block.trailblaze.model.ResolvedTarget? = null,
  /**
   * Device-filtered app id for [resolvedTarget]. Null when no declared candidate is installed
   * (scripted tools should fall back to `ctx.target?.appIds[0]` and let the launch fail
   * downstream with a clearer message). Threaded identically to [resolvedTarget].
   */
  appId: String? = null,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  trailblazeToolRepo = trailblazeToolRepo,
  resolvedTarget = resolvedTarget,
  appId = appId,
) {

  override val usesAccessibilityDriver: Boolean = true

  private val trailblazeYaml = createTrailblazeYaml(customToolClasses)

  /** Last observed RPC failure from [captureScreenState], surfaced by [screenStateProvider]. */
  @Volatile private var lastCaptureFailure: String? = null

  /** Consecutive wedge-signature [captureScreenState] failures; trips circuit breaker
   *  at [MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES] to fail fast on dead `system_server`. */
  private val consecutiveDeviceWedgeFailures = AtomicInteger(0)

  /** RPC-backed screen state provider for the host-side TrailblazeRunner. */
  val screenStateProvider: () -> ScreenState = {
    runBlocking { captureScreenState() }
      ?: error(
        "Failed to capture screen state from device via RPC" +
          (lastCaptureFailure?.let { ": $it" } ?: ""),
      )
  }

  /**
   * Captures current screen state via RPC. The [OnDeviceRpcClient.waitForReady] handshake at
   * trail start proves `GetScreenState` works; a failure here means the connection transitioned
   * from warm to cold mid-session (app/service restart, transient network blip). In that case
   * we re-run the readiness probe once and retry the capture — no blanket retry loop.
   */
  /**
   * @param includeScreenshot whether the on-device handler should bundle the screenshot
   *   bytes in the response. Migration capture (the host's per-tool snapshot hook) needs
   *   only the trees and can pass `false` to skip the Bitmap pull / WEBP encode / base64
   *   round-trip — saves ~200-500ms per capture on real devices.
   */
  suspend fun captureScreenState(includeScreenshot: Boolean = true): ScreenState? {
    // Forward the host's effective screenshot scaling config (driven by
    // `trailblaze config screenshot-*` and the desktop Settings panel) so the on-device
    // agent scales/encodes screenshots the way the user asked. Skipped when the request
    // doesn't include a screenshot — no point spending the bytes.
    val request = GetScreenStateRequest(includeScreenshot = includeScreenshot).let {
      if (includeScreenshot) it.withScreenshotScalingConfig(EffectiveScreenshotScalingConfig.effective)
      else it
    }
    when (val first = rpcClient.rpcCall(request)) {
      is RpcResult.Success -> {
        consecutiveDeviceWedgeFailures.set(0)
        lastCaptureFailure = null
        return RpcScreenStateAdapter.from(first.data)
      }
      is RpcResult.Failure -> {
        val detail = first.message + (first.details?.let { " | $it" } ?: "")
        lastCaptureFailure = detail
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState ${first.errorType}: ${first.message}" +
            (first.details?.let { "\n  Details: $it" } ?: "") +
            "\n  Re-warming connection and retrying once.",
        )
      }
    }
    try {
      rpcClient.waitForReady(
        timeoutMs = reWarmTimeoutMs,
        pollIntervalMs = reWarmPollIntervalMs,
        requireAndroidAccessibilityService = requireAndroidAccessibilityServiceOnRewarm,
      )
    } catch (e: Exception) {
      val detail = "re-warm failed: ${e.message} | runner_pid=${rpcClient.probeRunnerPid()}"
      lastCaptureFailure = "$lastCaptureFailure | $detail"
      Console.log("[HostOnDeviceRpcAgent] $detail")
      tripCircuitBreakerIfDeviceWedged()
      return null
    }
    return when (val retry = rpcClient.rpcCall(request)) {
      is RpcResult.Success -> {
        consecutiveDeviceWedgeFailures.set(0)
        lastCaptureFailure = null
        RpcScreenStateAdapter.from(retry.data)
      }
      is RpcResult.Failure -> {
        val detail = retry.message + (retry.details?.let { " | $it" } ?: "")
        lastCaptureFailure = "$lastCaptureFailure | retry: $detail"
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState retry after re-warm still failed " +
            "${retry.errorType}: ${retry.message}" +
            (retry.details?.let { "\n  Details: $it" } ?: ""),
        )
        tripCircuitBreakerIfDeviceWedged()
        null
      }
    }
  }

  /** Circuit breaker for a wedged `system_server`: cache-clear-and-reconnect can't recover
   *  a dead remote, so fail fast after N consecutive wedge-signature failures. Also arms the
   *  on-device server relaunch before throwing — see the comment at the throw site. */
  private fun tripCircuitBreakerIfDeviceWedged() {
    val detail = lastCaptureFailure ?: return
    val matchesWedge = DEVICE_WEDGE_SIGNATURES.any { detail.contains(it) }
    if (!matchesWedge) {
      consecutiveDeviceWedgeFailures.set(0)
      return
    }
    val count = consecutiveDeviceWedgeFailures.incrementAndGet()
    if (count >= MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES) {
      // A server relaunch is the right escalation for the DEVICE_WEDGE_SIGNATURES class too:
      // the exception below lacks the two-phrase non-recoverable signature, so neither the
      // rpcCall string match nor the session-log scan can arm the relaunch from it — without
      // this arm, the next trail inherits the wedge (the same poison-the-build mode the typed
      // arming closes). If a relaunch can't clear it (system_server truly dead), the armed
      // flag makes the next connect fail loudly instead.
      rpcClient.armNonRecoverableWedge()
      throw TrailblazeException(
        "Device unhealthy: $count consecutive GetScreenState " +
          "failures matched a UiAutomation-wedge signature (system_server appears dead). " +
          "Aborting trail — restart the emulator / device to recover. " +
          "Last failure: $detail",
      )
    }
  }

  companion object {
    private const val MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES = 3

    /** Substrings identifying a wedged-device failure vs a recoverable transient.
     *  Both `connect` and `disconnect` throw `RuntimeException` from the platform's
     *  `UiAutomation` lifecycle when the inner remote object is gone (`id=-1`); the
     *  on-device recovery shim should normally heal these, but if 3 in a row escape,
     *  trip the breaker and recycle the emulator rather than fail a trail after
     *  ~30s of futile re-warms against a dead `system_server`. */
    private val DEVICE_WEDGE_SIGNATURES = listOf(
      "Error while connecting UiAutomation",
      "Error while disconnecting UiAutomation",
      "DeadObjectException",
    )
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    // Resolve `OtherTrailblazeTool` placeholders through the session's tool repo BEFORE the
    // `when` below. Static YAML deserialization (`TrailblazeToolJsonSerializer.deserialize`)
    // always produces `OtherTrailblazeTool` for tool names the static catalog doesn't know —
    // and "doesn't know" includes every dynamically-registered scripted tool registered via
    // `LazyYamlScriptedToolRegistration.addDynamicTools` (the #2749 inline scripted tools
    // declared on the trailmap's `target.tools:` list).
    //
    // Without this resolution, an OtherTrailblazeTool whose name maps to a registered
    // scripted tool fell into the `else` branch below and surfaced as
    // `"Unsupported tool type for RPC execution: OtherTrailblazeTool"`, even though the
    // session's `toolRepo` knew exactly how to dispatch it. Mirrors the resolution pattern
    // [HostAccessibilityRpcClient.execute] and `.executePreAction` use on the V3 path —
    // resolution is the contract every host-driver dispatcher must honor (and the docstring
    // on `MaestroTrailblazeAgent.trailblazeToolRepo` already promises this).
    //
    // [BaseTrailblazeAgent.runTrailblazeTools] also resolves at its loop boundary via
    // `resolveDynamicTool`, but `executeTool` is reachable independently — e.g. from the
    // `runTrailblazeTools` `else` branch when the resolution there returns the original
    // (toolRepo returned the tool but it didn't match `HostLocalExecutableTrailblazeTool`),
    // and from any future caller that dispatches a tool without going through the batch
    // loop. Resolving here defends in depth.
    // Two senses of "resolved" appear in this method — disambiguated by name so a reader
    // (or grep) can tell them apart:
    //  - `repoResolvedTool` (this binding) = `OtherTrailblazeTool` → concrete tool, via the
    //    session's `trailblazeToolRepo.toolCallToTrailblazeTool(...)` (dynamic-tool dispatch).
    //  - `memoryResolvedTool` / `memoryResolvedExpanded` (below) = string-interpolated args via
    //    `interpolateMemoryInTool(...)` — applied ONLY on the host-local branches. RPC-routed
    //    tools are deliberately sent AS AUTHORED: the request carries a memory snapshot, the
    //    device seeds its per-request AgentMemory from it, and the device's own dispatch loop
    //    is the memory boundary there — so the device's tool log gets the raw + resolved pair
    //    and recordings regenerated from it keep their `{{var}}` tokens.
    val repoResolvedTool: TrailblazeTool = if (tool is OtherTrailblazeTool) {
      val repo = trailblazeToolRepo
      if (repo == null) {
        tool
      } else {
        try {
          repo.toolCallToTrailblazeTool(tool.toolName, tool.raw.toString())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Unknown name in this repo (`IllegalStateException` from the `error(...)` branch
          // of `toolCallToTrailblazeTool`) or serializer mismatch (`SerializationException`).
          // Fall through with the original — the `else` branch below produces the
          // unsupported-tool error with the actual toolName visible for diagnostics.
          //
          // **Use `Console.info` (not `Console.log`)** so this diagnostic survives the
          // default CLI quiet mode (`CliInfrastructure.enableQuietMode()`). Without this,
          // an operator triaging a CI-mode "Unsupported tool type" failure would lose the
          // underlying repo-lookup exception — exactly the breadcrumb needed to
          // distinguish "tool genuinely not registered" from "registered but arg-schema
          // mismatch surfaced as SerializationException". See lead-dev review #1.
          Console.info(
            "[HostOnDeviceRpcTrailblazeAgent] Could not resolve '${tool.toolName}' via repo: " +
              "${e::class.simpleName}: ${e.message}",
          )
          tool
        }
      }
    } else {
      tool
    }
    return when (repoResolvedTool) {
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(repoResolvedTool)
        if (repoResolvedTool is HostLocalExecutableTrailblazeTool || repoResolvedTool.requiresHostInstance()) {
          // Host-local tools (subprocess MCP, QuickJS scripted, cbot / dip-slot ADB tools,
          // YAML-defined `requires_host: true`) run on the host JVM — so the host IS their
          // memory boundary: resolve here, dispatch resolved, log raw + resolved.
          val memoryResolvedTool = interpolateMemoryInTool(repoResolvedTool, memory)
          executeHostLocalWithLogging(
            memoryResolvedTool,
            context,
            rawTool = repoResolvedTool.takeIf { it !== memoryResolvedTool },
          )
        } else {
          // RPC-routed: send AS AUTHORED — the device's dispatch loop resolves against the
          // seeded memory snapshot (see the "two senses of resolved" note above).
          executeToolViaRpc(repoResolvedTool, context.traceId)
        }
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(repoResolvedTool, context, toolsExecuted) { expandedTool ->
          // Same host-local vs RPC fork per expanded subtool. Expanded subtools inherit any
          // `OtherTrailblazeTool` → repo resolution from the parent delegating tool — the
          // base's `runTrailblazeTools` loop resolved the parent before dispatching here, so
          // we don't re-run repo resolution on each expanded subtool.
          if (expandedTool is HostLocalExecutableTrailblazeTool || expandedTool.requiresHostInstance()) {
            // Host-local subtools must not be RPCed to the device — they read/write
            // host-side files and credentials that have no meaning on the device JVM.
            // Memory boundary is here (host) for them.
            val memoryResolvedExpanded = interpolateMemoryInTool(expandedTool, memory)
            executeHostLocalWithLogging(
              memoryResolvedExpanded,
              context,
              rawTool = expandedTool.takeIf { it !== memoryResolvedExpanded },
            )
          } else {
            executeToolViaRpc(expandedTool, context.traceId)
          }
        }
      }
      else -> TrailblazeToolResult.Error.FatalError(
        // Diagnostic detail: when the unresolved tool was an `OtherTrailblazeTool`, surface
        // its `toolName` so a triager doesn't see a meaningless `OtherTrailblazeTool` token
        // and has to dig through the trail YAML to find which tool call failed.
        errorMessage = buildString {
          append("Unsupported tool type for RPC execution: ${repoResolvedTool::class.simpleName}")
          if (repoResolvedTool is OtherTrailblazeTool) {
            append(" (toolName='${repoResolvedTool.toolName}' — not registered in this session's ")
            append("tool repo as a class-backed, YAML-defined, or dynamic scripted tool)")
          }
        },
      )
    }
  }

  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    if (commands.isEmpty()) return TrailblazeToolResult.Success()
    // Forward maestro commands to the device via RPC by wrapping them in a MaestroTrailblazeTool.
    // This can be reached when recorded tools contain raw maestro commands or when the
    // nodeSelector tap path falls back to the maestro command path.
    Console.log(
      "[HostOnDeviceRpcAgent] Forwarding ${commands.size} maestro command(s) to device via RPC",
    )
    val maestroTool = MaestroTrailblazeTool(
      yaml = MaestroYamlSerializer.toYaml(commands, includeConfiguration = false),
    )
    // Sent as-is: the commands were built by a tool that already passed a memory boundary, and
    // any residual token resolves on the device (its dispatch loop interpolates the yaml string
    // against the seeded memory snapshot).
    return executeToolViaRpc(maestroTool, traceId)
  }

  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    // Accessibility-shaped selectors are recorded under the accessibility driver and must
    // dispatch via the on-device [AccessibilityTrailblazeAgent.executeNodeSelectorTap]
    // path — resolving against the live accessibility tree and dispatching a coordinate
    // gesture. Falling through to Maestro (the historical behavior here) loses the
    // selector's accessibility semantics and resolves against UiAutomator instead, which
    // is the bug we hit on Compose surfaces where a clickable wrapper sits over a
    // non-clickable text child (Sign in: clickable View → TextView "Sign in" with no
    // ACTION_CLICK). UiAutomator-shaped resolution silently picks the text child and
    // taps a node that doesn't fire the click handler.
    //
    // Send the recording's TapOnByElementSelector tool over RPC. On-device, when the
    // active agent is [AccessibilityTrailblazeAgent], the tool's [execute] override
    // re-enters [executeNodeSelectorTap] there and resolves the selector against the
    // live accessibility tree. No infinite loop — the host and device sides are
    // distinct agent instances.
    if (nodeSelector.androidAccessibility != null) {
      return executeToolViaRpc(
        tool = TapOnByElementSelector(
          nodeSelector = nodeSelector,
          longPress = longPress,
        ),
        traceId = traceId,
      )
    }
    // Non-accessibility-shaped selectors (Maestro-shaped, etc.) — let the caller fall
    // through to [executeMaestroCommands] via [super.execute]. Same behavior as before
    // for the instrumentation/Maestro flows.
    return null
  }

  /**
   * Dispatch a host-local tool (subprocess MCP, `requires_host: true`, etc.) and emit a
   * session-log entry for it (#2924). The base class's short-circuit covers the top-level
   * dispatch path uniformly, but this agent's [executeTool] also reaches host-local tools
   * via the `requiresHostInstance` annotation branch and the [DelegatingTrailblazeTool]
   * sub-tool expansion — both need their own log emit so every dispatch is visible to
   * recording and reports regardless of how it routed through the dispatcher.
   */
  private fun executeHostLocalWithLogging(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
    /** The authored form when the host-side memory boundary rewrote [tool] — see [logToolExecution]. */
    rawTool: TrailblazeTool? = null,
  ): TrailblazeToolResult {
    val timeBeforeExecution = Clock.System.now()
    // Same exception-bypass guard as the base agent's host-local branch (#2924 review):
    // a thrown exception would otherwise skip `logToolExecution` and leave the dispatch
    // invisible. Convert non-cancellation throws into a `TrailblazeToolResult.Error` and
    // log it; re-throw `CancellationException` so coroutine cancellation still unwinds.
    val result: TrailblazeToolResult = try {
      runBlocking { tool.execute(context) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, tool)
    }
    logToolExecution(
      tool = tool,
      timeBeforeExecution = timeBeforeExecution,
      context = context,
      result = result,
      // Same rationale as the `BaseTrailblazeAgent` host-local branch — flag for badging /
      // filtering so a developer reading the session log can tell at a glance whether the
      // dispatch ran on the host JVM (subprocess MCP, `requires_host`, host-preferred
      // composition primitive) or got routed to the device over RPC.
      dispatchedHostSide = true,
      rawTool = rawTool,
    )
    return result
  }

  private fun executeToolViaRpc(tool: TrailblazeTool, traceId: TraceId?): TrailblazeToolResult {
    val timeBeforeExecution = Clock.System.now()
    // How many `TrailblazeToolLog`s the on-device dispatch emitted for this tool. Set from the
    // RPC response on the success path; stays 0 on any failure path (no device log to defer to,
    // so the host emit below stays the only record). Drives the host-side double-log skip below.
    // Atomic because it's written inside the `runBlocking` block and read after it returns: the
    // continuation happens to resume on the caller thread today, but a future `withContext` in
    // the block must not be able to silently break the cross-thread visibility of the count.
    val onDeviceToolLogCount = AtomicInteger(0)
    val result: TrailblazeToolResult = runBlocking {
      try {
        // The tool is encoded AS AUTHORED (memory tokens intact) — the device's dispatch loop
        // resolves them against the memory snapshot below, so the device-side tool log carries
        // the raw + resolved pair and recordings keep their tokens.
        val toolItems = listOf(TrailYamlItem.ToolTrailItem(listOf(fromTrailblazeTool(tool))))
        val yaml = trailblazeYaml.encodeToString(toolItems)

        // Reuse the host's top-level session ID so every per-tool RunYamlRequest writes
        // into the same on-device session directory. When pulled back to the host via
        // `adb pull`, those logs merge into the same host-side session directory instead
        // of scattering into one `session_<millis>/` directory per tool call. Session
        // start/end logs are still suppressed — the host owns the session lifecycle.
        val request = runYamlRequestTemplate.copy(
          yaml = yaml,
          agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
          traceId = traceId,
          // Per-tool RPCs block the HTTP response on on-device completion. Explicit for
          // clarity even though the request default is also true.
          awaitCompletion = true,
          config = runYamlRequestTemplate.config.copy(
            overrideSessionId = sessionProvider.invoke().sessionId,
            sendSessionStartLog = false,
            sendSessionEndLog = false,
          ),
          // Snapshot host memory so the device's dispatch boundary can resolve {{var}}/${var}
          // tokens in the (raw) tool args, and on-device tools can read keys directly. The
          // sensitive-key set rides along so the device's per-request AgentMemory keeps those
          // values redacted in its own logs (they'd otherwise arrive unmarked and log cleartext).
          memorySnapshot = memory.variables.toMap(),
          sensitiveMemoryKeys = memory.sensitiveKeys.toList(),
          // Args are immutable per run, so unlike memory they only travel host → device (no
          // round-trip back). The device rehydrates them verbatim to resolve {{args.x}} tokens.
          argsSnapshot = TrailArgBinder.encodeProvided(memory.args),
          sensitiveArgNames = memory.sensitiveArgNames.toList(),
        )

        val name = tool::class.simpleName ?: "unknown"
        when (val first: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
          is RpcResult.Success -> {
            // Apply the on-device agent's post-execution memory back into the host's shared
            // instance so writes from on-device tools (including TS handlers) are visible to
            // subsequent host-side or RPC dispatches.
            applyMemorySnapshot(first.data.memorySnapshot, first.data.memoryDeletions, first.data.sensitiveMemoryKeys)
            onDeviceToolLogCount.set(first.data.onDeviceToolLogCount)
            toToolResult(name, first, timeBeforeExecution)
          }
          is RpcResult.Failure -> {
            val firstDetail = first.message + (first.details?.let { " | $it" } ?: "")
            Console.log(
              "[HostOnDeviceRpcAgent] RPC failed for '$name' (${first.errorType}): " +
                "${first.message}" +
                (first.details?.let { "\n  Details: $it" } ?: "") +
                "\n  Re-warming connection and retrying once.",
            )
            try {
              rpcClient.waitForReady(
                timeoutMs = reWarmTimeoutMs,
                pollIntervalMs = reWarmPollIntervalMs,
                requireAndroidAccessibilityService = requireAndroidAccessibilityServiceOnRewarm,
              )
            } catch (e: Exception) {
              val detail = "re-warm failed: ${e.message} | runner_pid=${rpcClient.probeRunnerPid()}"
              Console.log("[HostOnDeviceRpcAgent] $detail")
              return@runBlocking rpcFailureToolResult(
                errorMessage = "RPC call failed for '$name': $firstDetail | $detail",
                firstDetail,
                detail,
              )
            }
            when (val retry: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
              is RpcResult.Success -> {
                applyMemorySnapshot(retry.data.memorySnapshot, retry.data.memoryDeletions, retry.data.sensitiveMemoryKeys)
                onDeviceToolLogCount.set(retry.data.onDeviceToolLogCount)
                toToolResult(name, retry, timeBeforeExecution)
              }
              is RpcResult.Failure -> {
                val retryDetail = retry.message + (retry.details?.let { " | $it" } ?: "")
                Console.log(
                  "[HostOnDeviceRpcAgent] RPC retry for '$name' after re-warm still failed " +
                    "(${retry.errorType}): ${retry.message}" +
                    (retry.details?.let { "\n  Details: $it" } ?: ""),
                )
                rpcFailureToolResult(
                  errorMessage = "RPC call failed for '$name': $firstDetail | retry: $retryDetail",
                  firstDetail,
                  retryDetail,
                )
              }
            }
          }
        }
      } catch (e: Exception) {
        // Propagate cancellation so structured-concurrency teardown (agent abort, driver
        // disconnect, session shutdown) isn't silently converted into a tool error.
        if (e is kotlinx.coroutines.CancellationException) throw e
        Console.log(
          "[HostOnDeviceRpcAgent] Exception executing '${tool::class.simpleName}': " +
            "${e::class.simpleName}: ${e.message}",
        )
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Tool execution failed: ${e::class.simpleName}: ${e.message}",
        )
      }
    }

    // Emit a host-side TrailblazeToolLog ONLY when the on-device dispatch did not already log
    // the tool itself. The device runs the tool through
    // `BaseTrailblazeAgent.runTrailblazeTools` and, in the common path, emits its own
    // `TrailblazeToolLog` (pulled back to the host via `adb pull` and merged into this same
    // session). When that happens, a second host-side emit produces a duplicate: the one
    // execution renders twice in the session report — an outer "host" span and a nested
    // "on-device" span (#3818). So we defer to the device's log whenever it reported one
    // (`onDeviceToolLogCount > 0`).
    //
    // The host emit is still required for the catch-all case where the on-device dispatch
    // emits NO `TrailblazeToolLog` — e.g. a tool whose `execute` short-circuits straight to
    // `agent.executeNodeSelectorTap` (accessibility nodeSelectors), which only produces
    // driver-action logs and routes around the device's tool-log emit site. Without the host
    // emit there, `TrailblazeRecordingGenerator` would see zero `TrailblazeToolLog` files in
    // the host's session dir (only the unrecordable `DelegatingTrailblazeToolLog`) and emit
    // empty recordings. `dispatchedHostSide = false` is the correct semantic — the actual
    // dispatch ran on device.
    if (traceId != null && onDeviceToolLogCount.get() == 0) {
      logToolExecution(
        tool = tool,
        timeBeforeExecution = timeBeforeExecution,
        traceId = traceId,
        result = result,
        dispatchedHostSide = false,
      )
    } else if (traceId != null) {
      // Breadcrumb for the #3818 skip path: when a tool appears once (or not at all) in a
      // report, this line tells a triager the host *deliberately* deferred to the device's
      // own tool log rather than dropping its own. Mirrors the diagnostic-log density of the
      // rest of this dispatcher (every RPC state transition logs).
      Console.log(
        "[HostOnDeviceRpcAgent] Skipping host-side tool log for " +
          "'${tool::class.simpleName}' — on-device dispatch already emitted " +
          "${onDeviceToolLogCount.get()} tool log(s) (#3818).",
      )
    }
    return result
  }

  private fun toToolResult(
    name: String,
    rpcResult: RpcResult.Success<RunYamlResponse>,
    timeBeforeExecution: kotlinx.datetime.Instant,
  ): TrailblazeToolResult {
    val durationMs =
      Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()
    return when (rpcResult.data.success) {
      true -> {
        Console.log("[HostOnDeviceRpcAgent] '$name' completed in ${durationMs}ms")
        // Mirror the on-device tool's `Success.message` / `structuredContent` back through the
        // host-side result so scripted-tool authors composing this tool via `client.callTool(...)`
        // observe the same payload regardless of whether dispatch routed host-side or via RPC.
        // Before the response envelope grew these fields, the message/structuredContent were
        // silently discarded here — that gap is what `prefersHostSideForCallback` used to paper
        // over for `android_adbShell` / `android_sendBroadcast` / `mobile_listInstalledApps`.
        TrailblazeToolResult.Success(
          message = rpcResult.data.toolMessage,
          structuredContent = rpcResult.data.toolStructuredContent,
        )
      }
      false -> {
        val errorMessage =
          rpcResult.data.errorMessage ?: "Tool '$name' execution failed on-device"
        if (rpcClient.noteIfNonRecoverableWedge(rpcResult.data)) {
          // The on-device handler tagged this failure as the non-recoverable UiAutomation
          // wedge (its in-process reconnect retry already failed). The typed arm above
          // schedules a server relaunch before the NEXT session — the wedge arrives as an
          // HTTP-200 `success=false` response the string-matching breaker in
          // `OnDeviceRpcClient.rpcCall` never sees. FatalError (not ExceptionThrown) aborts
          // THIS trail: every subsequent dispatch is guaranteed to fail identically, so
          // feeding the error back to the LLM (or to self-heal) just burns the call budget
          // against a dead server. Parity with the V3 `HostAccessibilityRpcClient` path
          // (same arm + `recoverable = false`).
          //
          // Console.info (this file's CI-triage convention): when the device's errorMessage
          // lacks the wedge phrases — the exact case the typed field exists for — no other
          // operator-visible line attributes the next trail's force-restart to this dispatch,
          // and Console.log is suppressed in the CLI's default quiet mode.
          Console.info(
            "[HostOnDeviceRpcAgent] '$name' failed with the non-recoverable UiAutomation " +
              "wedge — arming on-device server relaunch and aborting the trail.",
          )
          TrailblazeToolResult.Error.FatalError(errorMessage = errorMessage)
        } else {
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = errorMessage)
        }
      }
      null -> TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "On-device server returned null success inline for '$name' — " +
          "contract violation for awaitCompletion=true (expected true/false, got null)",
      )
    }
  }

  /**
   * Terminal result for an RPC-level [RpcResult.Failure]. When any of [details] carries the
   * non-recoverable UiAutomation wedge signature, the relaunch breaker has already been armed by
   * the string match at the [OnDeviceRpcClient.rpcCall] chokepoint — return
   * [TrailblazeToolResult.Error.FatalError] so this trail aborts too, keeping arm-and-abort
   * consistent with the typed HTTP-200 path in [toToolResult]. Any other failure stays
   * LLM-retryable as a plain ExceptionThrown.
   */
  private fun rpcFailureToolResult(
    errorMessage: String,
    vararg details: String?,
  ): TrailblazeToolResult.Error = if (
    details.any { UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(it) }
  ) {
    TrailblazeToolResult.Error.FatalError(errorMessage = errorMessage)
  } else {
    TrailblazeToolResult.Error.ExceptionThrown(errorMessage = errorMessage)
  }

  /** Merges the device's post-execution snapshot into this agent's [memory] then applies explicit deletes. */
  private fun applyMemorySnapshot(
    deviceSnapshot: Map<String, String>,
    deletions: List<String>,
    sensitiveKeys: List<String>,
  ) {
    // Merge (device wins on conflict) rather than replace. An on-device tool's per-request
    // AgentMemory is seeded from the host push at request start, so its returned snapshot SHOULD
    // carry back every host key — but some dispatch paths (e.g. a scripted tool whose request
    // resets the on-device session) return a snapshot that drops host-set keys, and a plain
    // clear()+putAll() then WIPED them, losing a value a later ${var} step needed (the "typed ''"
    // empty-input false green). Preserving host-only keys keeps cross-step remembers alive;
    // device-written keys still overwrite on conflict. [deletions] then removes keys an on-device
    // tool EXPLICITLY deleted — merge alone can't distinguish those from a merely-omitted key.
    // [sensitiveKeys] re-marks device-side `rememberSensitive` keys so host-side logging keeps
    // redacting them after the merge (the snapshot map itself carries no sensitivity marking).
    // Mark BEFORE storing the values (mirroring `AgentMemory.storeSensitive`): a reader that
    // snapshots `variables` and filters against `sensitiveKeys` must never observe a returned
    // secret in the window before its marker lands.
    sensitiveKeys.forEach { memory.markSensitive(it) }
    memory.variables.putAll(deviceSnapshot)
    deletions.forEach { memory.variables.remove(it) }
  }
}

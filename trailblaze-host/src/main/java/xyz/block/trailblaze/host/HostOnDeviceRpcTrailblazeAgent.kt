package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.requiresHost
import xyz.block.trailblaze.util.Console
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
   * Session tool repo — threaded to [MaestroTrailblazeAgent] (and then [BaseTrailblazeAgent])
   * so an [OtherTrailblazeTool] naming a subprocess MCP tool in a trail YAML resolves to its
   * registered [SubprocessTrailblazeTool][xyz.block.trailblaze.scripting.subprocess.SubprocessTrailblazeTool]
   * instead of hitting "Unsupported tool type for RPC execution".
   */
  trailblazeToolRepo: TrailblazeToolRepo? = null,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  trailblazeToolRepo = trailblazeToolRepo,
) {

  override val usesAccessibilityDriver: Boolean = true

  private val trailblazeYaml = createTrailblazeYaml(customToolClasses)

  /** Last observed RPC failure from [captureScreenState], surfaced by [screenStateProvider]. */
  @Volatile private var lastCaptureFailure: String? = null

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
  suspend fun captureScreenState(): ScreenState? {
    when (val first = rpcClient.rpcCall(GetScreenStateRequest())) {
      is RpcResult.Success -> return RpcScreenStateAdapter(first.data)
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
        timeoutMs = 10_000L,
        requireAndroidAccessibilityService = requireAndroidAccessibilityServiceOnRewarm,
      )
    } catch (e: Exception) {
      val detail = "re-warm failed: ${e.message}"
      lastCaptureFailure = "$lastCaptureFailure | $detail"
      Console.log("[HostOnDeviceRpcAgent] $detail")
      return null
    }
    return when (val retry = rpcClient.rpcCall(GetScreenStateRequest())) {
      is RpcResult.Success -> {
        lastCaptureFailure = null
        RpcScreenStateAdapter(retry.data)
      }
      is RpcResult.Failure -> {
        val detail = retry.message + (retry.details?.let { " | $it" } ?: "")
        lastCaptureFailure = "$lastCaptureFailure | retry: $detail"
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState retry after re-warm still failed " +
            "${retry.errorType}: ${retry.message}" +
            (retry.details?.let { "\n  Details: $it" } ?: ""),
        )
        null
      }
    }
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    return when (tool) {
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(tool)
        if (tool::class.requiresHost()) {
          // Host-only tools (cbot, dip-slot) run locally — they need ADB/USB on the Mac.
          runBlocking { tool.execute(context) }
        } else {
          executeToolViaRpc(tool)
        }
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(tool, context, toolsExecuted) { expandedTool ->
          executeToolViaRpc(expandedTool)
        }
      }
      else -> TrailblazeToolResult.Error.FatalError(
        errorMessage = "Unsupported tool type for RPC execution: ${tool::class.simpleName}",
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
    return executeToolViaRpc(maestroTool)
  }

  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    // Return null so the caller falls through to executeMaestroCommands() which
    // now properly forwards commands to the device via RPC. The on-device agent
    // will handle nodeSelector resolution with its own view hierarchy.
    return null
  }

  private fun executeToolViaRpc(tool: TrailblazeTool): TrailblazeToolResult {
    val timeBeforeExecution = Clock.System.now()
    return runBlocking {
      try {
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
          // Per-tool RPCs block the HTTP response on on-device completion. Explicit for
          // clarity even though the request default is also true.
          awaitCompletion = true,
          config = runYamlRequestTemplate.config.copy(
            overrideSessionId = sessionProvider.invoke().sessionId,
            sendSessionStartLog = false,
            sendSessionEndLog = false,
          ),
        )

        when (val rpcResult = rpcClient.rpcCall(request)) {
          is RpcResult.Failure -> {
            val name = tool::class.simpleName ?: "unknown"
            val details = rpcResult.details?.let { "\n  Details: $it" } ?: ""
            Console.log(
              "[HostOnDeviceRpcAgent] RPC failed for '$name' (${rpcResult.errorType}): " +
                "${rpcResult.message}$details",
            )
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "RPC call failed for '$name': ${rpcResult.message}" +
                (rpcResult.details?.let { " | $it" } ?: ""),
            )
          }
          is RpcResult.Success -> {
            val name = tool::class.simpleName ?: "unknown"
            val durationMs =
              Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()
            when (rpcResult.data.success) {
              true -> {
                Console.log("[HostOnDeviceRpcAgent] '$name' completed in ${durationMs}ms")
                TrailblazeToolResult.Success()
              }
              false -> TrailblazeToolResult.Error.ExceptionThrown(
                errorMessage = rpcResult.data.errorMessage
                  ?: "Tool '$name' execution failed on-device",
              )
              null -> TrailblazeToolResult.Error.ExceptionThrown(
                errorMessage = "On-device server returned null success inline for '$name' — " +
                  "contract violation for awaitCompletion=true (expected true/false, got null)",
              )
            }
          }
        }
      } catch (e: Exception) {
        Console.log(
          "[HostOnDeviceRpcAgent] Exception executing '${tool::class.simpleName}': " +
            "${e::class.simpleName}: ${e.message}",
        )
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Tool execution failed: ${e::class.simpleName}: ${e.message}",
        )
      }
    }
  }

}

package xyz.block.trailblaze.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.utils.Ext.asJsonObjects
import xyz.block.trailblaze.agent.ExecutionState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
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
 * [RunYamlRequest] with [AgentImplementation.TRAILBLAZE_RUNNER]. The device executes the tool
 * via whichever driver is specified in the request's `driverType` and the host polls for
 * completion.
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
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
) {

  override val usesAccessibilityDriver: Boolean = true

  private val trailblazeYaml = createTrailblazeYaml(customToolClasses)

  /** RPC-backed screen state provider for the host-side TrailblazeRunner. */
  val screenStateProvider: () -> ScreenState = {
    runBlocking { captureScreenState() }
      ?: error("Failed to capture screen state from device via RPC")
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
    val maestroTool = MaestroTrailblazeTool(commands = commands.asJsonObjects())
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

  suspend fun captureScreenState(
    maxRetries: Int = 5,
    initialDelayMs: Long = 500L,
  ): ScreenState? {
    var lastError: String? = null
    repeat(maxRetries) { attempt ->
      try {
        when (val result = rpcClient.rpcCall(GetScreenStateRequest())) {
          is RpcResult.Success -> return RpcScreenStateAdapter(result.data)
          is RpcResult.Failure -> {
            lastError = result.message + (result.details?.let { " | $it" } ?: "")
            val delayMs = initialDelayMs * (attempt + 1)
            Console.log(
              "[HostOnDeviceRpcAgent] GetScreenState ${result.errorType} (attempt ${attempt + 1}/$maxRetries): " +
                "${result.message}${result.details?.let { "\n  Details: $it" } ?: ""}, retrying in ${delayMs}ms...",
            )
            delay(delayMs)
          }
        }
      } catch (e: Exception) {
        lastError = e.message
        val delayMs = initialDelayMs * (attempt + 1)
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState exception (attempt ${attempt + 1}/$maxRetries): " +
            "${e.message}, retrying in ${delayMs}ms..."
        )
        delay(delayMs)
      }
    }
    Console.log("[HostOnDeviceRpcAgent] GetScreenState failed after $maxRetries attempts: $lastError")
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
            Console.log(
              "[HostOnDeviceRpcAgent] '$name' dispatched, " +
                "awaiting session ${rpcResult.data.sessionId.value}",
            )
            val success = awaitToolCompletion(rpcResult.data.sessionId)
            val durationMs = Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()
            if (success) {
              Console.log("[HostOnDeviceRpcAgent] '$name' completed in ${durationMs}ms")
              TrailblazeToolResult.Success()
            } else {
              TrailblazeToolResult.Error.ExceptionThrown(
                errorMessage = "Tool '$name' execution failed or timed out on-device",
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

  private suspend fun awaitToolCompletion(
    sessionId: SessionId,
    maxWaitMs: Long = 120_000L,
    pollIntervalMs: Long = 500L,
  ): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      val statusResult = rpcClient.rpcCall(GetExecutionStatusRequest(sessionId.value))
      when (statusResult) {
        is RpcResult.Success -> {
          if (!statusResult.data.found) {
            delay(pollIntervalMs)
            continue
          }
          when (statusResult.data.status?.state) {
            ExecutionState.COMPLETED -> return true
            ExecutionState.FAILED, ExecutionState.CANCELLED -> return false
            else -> delay(pollIntervalMs)
          }
        }
        is RpcResult.Failure -> delay(pollIntervalMs)
      }
    }
    Console.log(
      "[HostOnDeviceRpcAgent] Timed out waiting for tool completion (session ${sessionId.value})",
    )
    return false
  }
}

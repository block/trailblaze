package xyz.block.trailblaze.host

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.ExecutionState
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool

/**
 * Host-side [UiActionExecutor] that forwards individual tool calls to the on-device
 * accessibility server via RPC, then polls until execution completes.
 *
 * Used by [xyz.block.trailblaze.agent.MultiAgentV3Runner] running on the host to drive
 * the on-device accessibility driver one tool call at a time, without sending the entire
 * trail YAML to the device.
 *
 * Each [execute] call:
 * 1. Converts (toolName, args) → [xyz.block.trailblaze.toolcalls.TrailblazeTool] → single-step YAML
 * 2. Sends a [RunYamlRequest] (with [AgentImplementation.TRAILBLAZE_RUNNER]) to the on-device server
 * 3. Polls [GetExecutionStatusRequest] until the on-device job reaches a terminal state
 *
 * Screen state is captured via [GetScreenStateRequest] without executing any tool.
 */
class HostAccessibilityRpcClient(
  private val rpcClient: OnDeviceRpcClient,
  private val toolRepo: TrailblazeToolRepo,
  private val runYamlRequestTemplate: RunYamlRequest,
) : UiActionExecutor, AutoCloseable {

  private val trailblazeYaml = createTrailblazeYaml()

  override suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult {
    val startTime = System.currentTimeMillis()
    return try {
      // Deserialize (toolName, args) → TrailblazeTool, then encode as single-step trail YAML
      val tool = toolRepo.toolCallToTrailblazeTool(toolName, args.toString())
      val toolItems = listOf(TrailYamlItem.ToolTrailItem(listOf(fromTrailblazeTool(tool))))
      val yaml = trailblazeYaml.encodeToString(toolItems)

      // Generate a unique session ID for this individual tool call so the on-device
      // progress manager can track it independently. Session start/end logs are suppressed
      // because the host manages the top-level session.
      val toolCallSessionId = SessionId.generate()
      val singleToolRequest = runYamlRequestTemplate.copy(
        yaml = yaml,
        agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
        config = runYamlRequestTemplate.config.copy(
          overrideSessionId = toolCallSessionId,
          sendSessionStartLog = false,
          sendSessionEndLog = false,
        ),
      )

      // The on-device server returns immediately with a session ID; execution is async
      when (val rpcResult = rpcClient.rpcCall(singleToolRequest)) {
        is RpcResult.Failure -> {
          Console.log("[HostAccessibilityRpcClient] RPC call failed for '$toolName': ${rpcResult.message}")
          ExecutionResult.Failure(
            error = "RPC call failed: ${rpcResult.message}",
            recoverable = true,
          )
        }
        is RpcResult.Success -> {
          Console.log(
            "[HostAccessibilityRpcClient] '$toolName' dispatched, " +
              "awaiting session ${rpcResult.data.sessionId.value}",
          )
          val success = awaitToolCompletion(rpcResult.data.sessionId)
          val durationMs = System.currentTimeMillis() - startTime
          if (success) {
            ExecutionResult.Success(
              screenSummaryAfter = "Tool '$toolName' executed via accessibility driver",
              durationMs = durationMs,
            )
          } else {
            ExecutionResult.Failure(
              error = "Tool '$toolName' execution failed or timed out on-device",
              recoverable = true,
            )
          }
        }
      }
    } catch (e: Exception) {
      Console.log("[HostAccessibilityRpcClient] Exception executing '$toolName': ${e.message}")
      ExecutionResult.Failure(error = "Tool execution failed: ${e.message}", recoverable = true)
    }
  }

  override suspend fun captureScreenState(): ScreenState? {
    return try {
      when (val result = rpcClient.rpcCall(GetScreenStateRequest())) {
        is RpcResult.Success -> RpcScreenStateAdapter(result.data)
        is RpcResult.Failure -> {
          Console.log("[HostAccessibilityRpcClient] GetScreenState failed: ${result.message}")
          null
        }
      }
    } catch (e: Exception) {
      Console.log("[HostAccessibilityRpcClient] Exception capturing screen state: ${e.message}")
      null
    }
  }

  /**
   * Polls [GetExecutionStatusRequest] until the on-device job reaches a terminal state
   * ([ExecutionState.COMPLETED], [ExecutionState.FAILED], or [ExecutionState.CANCELLED]).
   *
   * Returns true on success, false on failure or timeout.
   */
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
            // Not yet registered by the on-device progress manager — retry shortly
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
      "[HostAccessibilityRpcClient] Timed out waiting for tool completion " +
        "(session ${sessionId.value})",
    )
    return false
  }

  override fun close() {
    rpcClient.close()
  }
}

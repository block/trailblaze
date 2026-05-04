package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.requiresHostInstance
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool

/**
 * Host-side [UiActionExecutor] that forwards individual tool calls to the on-device
 * accessibility server via RPC and awaits completion inline on the RPC response.
 *
 * Used by [xyz.block.trailblaze.agent.MultiAgentV3Runner] running on the host to drive
 * the on-device accessibility driver one tool call at a time, without sending the entire
 * trail YAML to the device.
 *
 * Each [execute] call:
 * 1. Converts (toolName, args) → [xyz.block.trailblaze.toolcalls.TrailblazeTool] → single-step YAML
 * 2. Sends a [RunYamlRequest] (with [AgentImplementation.TRAILBLAZE_RUNNER] and
 *    [RunYamlRequest.awaitCompletion] = `true`) to the on-device server
 * 3. Reads the terminal state directly from [xyz.block.trailblaze.llm.RunYamlResponse.success]
 *
 * Screen state is captured via [GetScreenStateRequest] without executing any tool.
 */
class HostAccessibilityRpcClient(
  private val rpcClient: OnDeviceRpcClient,
  private val toolRepo: TrailblazeToolRepo,
  private val runYamlRequestTemplate: RunYamlRequest,
  /** Provides the host's top-level session so every per-tool RPC shares one on-device session dir. */
  private val sessionProvider: TrailblazeSessionProvider,
  /** Context provider for executing host-only tools (cbot, dip-slot) locally. */
  private val toolExecutionContextProvider: ((TraceId?) -> TrailblazeToolExecutionContext)? = null,
  /**
   * Host-side memory shared with [toolExecutionContextProvider]'s contexts. Required (no default)
   * so a caller cannot silently get an isolated empty memory and lose interpolation —
   * see [interpolateMemoryInTool]. [AgentMemory] is concurrent-safe.
   */
  private val memory: AgentMemory,
) : UiActionExecutor, AutoCloseable {

  private val trailblazeYaml = createTrailblazeYaml()

  override suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult {
    val startTime = System.currentTimeMillis()
    // Serialize args once — this runs on the hot path (every recorded tool call of every
    // step of every trail), so we avoid the double re-serialization the earlier version did.
    val argsString = args.toString()
    return try {
      // Deserialize (toolName, args) → TrailblazeTool, then encode as single-step trail YAML.
      // Fall back to a raw OtherTrailblazeTool wrapper ONLY when the tool name isn't
      // registered in this repo — YAML-defined or device-specific tools that trail recordings
      // use but that aren't in any toolset catalog entry, because they're never surfaced to
      // the LLM. The on-device runner resolves these through its own repo.
      val tool = try {
        toolRepo.toolCallToTrailblazeTool(toolName, argsString)
      } catch (lookupFailure: Exception) {
        // Preserve cooperative cancellation — `execute` is a suspend function and this
        // whole block is inside a broad `try { … } catch (Exception)`, so a
        // `CancellationException` here could otherwise be swallowed and prevent shutdown.
        if (lookupFailure is CancellationException) throw lookupFailure
        val isUnknownToolName =
          lookupFailure.message?.contains("Could not find Trailblaze tool for name:") == true
        if (!isUnknownToolName) throw lookupFailure
        // Wrap the args verbatim as an OtherTrailblazeTool — the args object already carries
        // the flat tool params, no decode needed. The on-device runner resolves the wrapped
        // call through its own repo.
        Console.log(
          "[HostAccessibilityRpcClient] '$toolName' resolved as OtherTrailblazeTool fallback " +
            "— not in this repo's toolset catalog",
        )
        OtherTrailblazeTool(toolName, args)
      }

      // Resolve memory tokens once, up front, so both branches (host-only and RPC) see the
      // same resolved args. Host-only tools like RunCommandTrailblazeTool don't all
      // self-interpolate; RPC tools must be resolved here because the device's per-request
      // memory is empty. See [interpolateMemoryInTool].
      val resolvedTool = interpolateMemoryInTool(tool, memory)

      // Host-only tools (cbot, dip-slot) must execute locally — they need ADB/USB on the Mac.
      // Use the instance-level helper so YAML-defined tools that set `requires_host: true`
      // are honored too, not just class-annotated ones.
      if (resolvedTool is ExecutableTrailblazeTool && resolvedTool.requiresHostInstance()) {
        val context = toolExecutionContextProvider?.invoke(traceId)
          ?: return ExecutionResult.Failure(
            error = "Host-only tool '$toolName' requires a tool execution context",
            recoverable = false,
          )
        val result = resolvedTool.execute(context)
        val durationMs = System.currentTimeMillis() - startTime
        return when (result) {
          is xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success ->
            ExecutionResult.Success(
              screenSummaryAfter = "Host-only tool '$toolName' executed locally",
              durationMs = durationMs,
            )
          else ->
            ExecutionResult.Failure(error = "Host-only tool '$toolName' failed: $result", recoverable = true)
        }
      }
      val toolItems = listOf(TrailYamlItem.ToolTrailItem(listOf(fromTrailblazeTool(resolvedTool))))
      val yaml = trailblazeYaml.encodeToString(toolItems)

      // Reuse the host's top-level session ID so every per-tool RunYamlRequest writes
      // into the same on-device session directory. When pulled back to the host via
      // `adb pull`, those logs merge into the same host-side session directory instead
      // of scattering into one `session_<millis>/` directory per tool call. Session
      // start/end logs are still suppressed — the host owns the session lifecycle.
      val singleToolRequest = runYamlRequestTemplate.copy(
        yaml = yaml,
        agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
        traceId = traceId,
        // Per-tool RPCs are bounded in time; block the HTTP response on on-device completion
        // so we don't need a separate GetExecutionStatusRequest poll. Explicit for clarity even
        // though the request default is also true.
        awaitCompletion = true,
        config = runYamlRequestTemplate.config.copy(
          overrideSessionId = sessionProvider.invoke().sessionId,
          sendSessionStartLog = false,
          sendSessionEndLog = false,
        ),
        // Snapshot host memory so on-device tools can read keys directly (the boundary
        // pre-resolution above only handles {{var}}/${var} interpolation in tool args).
        memorySnapshot = memory.variables.toMap(),
      )

      when (val rpcResult = rpcClient.rpcCall(singleToolRequest)) {
        is RpcResult.Failure -> {
          Console.log("[HostAccessibilityRpcClient] RPC call failed for '$toolName': ${rpcResult.message}")
          ExecutionResult.Failure(
            error = "RPC call failed: ${rpcResult.message}",
            recoverable = true,
          )
        }
        is RpcResult.Success -> {
          val durationMs = System.currentTimeMillis() - startTime
          // Apply the on-device agent's post-execution memory back into the host's shared
          // instance so writes from on-device tools (including TS handlers) are visible to
          // subsequent host-side or RPC dispatches. Sequential trail execution means the
          // host can't have written between dispatch and now, so a full replace is safe.
          applyMemorySnapshot(rpcResult.data.memorySnapshot)
          // `success == true` means the on-device handler ran the tool and its post-action
          // settle completed. `false` carries the on-device errorMessage; `null` should not
          // occur on this path because we set `awaitCompletion = true`, but we treat it
          // defensively as a failure so a mis-wired server can't silently look like success.
          when (rpcResult.data.success) {
            true -> ExecutionResult.Success(
              screenSummaryAfter = "Tool '$toolName' executed via accessibility driver",
              durationMs = durationMs,
            )
            false -> ExecutionResult.Failure(
              error = rpcResult.data.errorMessage
                ?: "Tool '$toolName' execution failed on-device",
              recoverable = true,
            )
            null -> ExecutionResult.Failure(
              error = "On-device server returned null success inline — contract violation " +
                "for awaitCompletion=true (expected true/false, got null)",
              recoverable = false,
            )
          }
        }
      }
    } catch (e: Exception) {
      // Rethrow cancellation so coroutine cancellation propagates cleanly — otherwise the
      // outer scope can't cancel this suspend function and timeouts/shutdown may hang.
      if (e is CancellationException) throw e
      Console.log("[HostAccessibilityRpcClient] Exception executing '$toolName': ${e.message}")
      ExecutionResult.Failure(error = "Tool execution failed: ${e.message}", recoverable = true)
    }
  }

  /**
   * Executes a pre-action tool (e.g. launchApp) from a trail's `tools:` section via RPC and
   * returns whether it succeeded on-device. Forces [RunYamlRequest.awaitCompletion]
   * regardless of what the caller's template had — pre-actions MUST finish before the main
   * trail starts, so a caller that constructed the template in async-kickoff mode must not
   * accidentally turn `launchApp` into a race.
   *
   * Returns `false` on RPC failure, terminal on-device failure, or any server contract
   * violation. The caller is expected to short-circuit the trail if this returns `false` —
   * a failed `launchApp` means the main trail would otherwise run against the wrong app
   * state, producing a confusing "mid-trail tap failed" failure instead of a clean
   * "couldn't launch the app under test" one.
   */
  suspend fun executePreAction(request: RunYamlRequest): Boolean {
    val syncRequest = if (request.awaitCompletion) request else request.copy(awaitCompletion = true)
    return when (val rpcResult = rpcClient.rpcCall(syncRequest)) {
      is RpcResult.Failure -> {
        Console.log("[HostAccessibilityRpcClient] Pre-action RPC failed: ${rpcResult.message}")
        false
      }
      is RpcResult.Success -> when (rpcResult.data.success) {
        true -> true
        false -> {
          Console.log(
            "[HostAccessibilityRpcClient] Pre-action failed on-device: " +
              (rpcResult.data.errorMessage ?: "no error message"),
          )
          false
        }
        null -> {
          Console.log(
            "[HostAccessibilityRpcClient] Pre-action returned null success inline — contract " +
              "violation for awaitCompletion=true (expected true/false, got null)",
          )
          false
        }
      }
    }
  }

  /**
   * Captures current screen state via RPC. The [OnDeviceRpcClient.waitForReady] handshake at
   * trail start proves `GetScreenState` works; a failure here means the connection transitioned
   * from warm to cold mid-session (app/service restart, transient network blip). In that case
   * we re-run the readiness probe once and retry the capture — no blanket retry loop.
   */
  override suspend fun captureScreenState(): ScreenState? {
    when (val first = rpcClient.rpcCall(GetScreenStateRequest())) {
      is RpcResult.Success -> return RpcScreenStateAdapter(first.data)
      is RpcResult.Failure -> Console.log(
        "[HostAccessibilityRpcClient] GetScreenState ${first.errorType}: ${first.message}" +
          (first.details?.let { "\n  Details: $it" } ?: "") +
          "\n  Re-warming connection and retrying once.",
      )
    }
    try {
      // This client always drives the accessibility driver (V3 + on-host path), so the re-warm
      // must confirm the service is still bound — a UiAutomator fallback here would silently
      // break accessibility-specific tool semantics.
      rpcClient.waitForReady(timeoutMs = 10_000L, requireAndroidAccessibilityService = true)
    } catch (e: Exception) {
      Console.log("[HostAccessibilityRpcClient] Re-warm failed: ${e.message}")
      return null
    }
    return when (val retry = rpcClient.rpcCall(GetScreenStateRequest())) {
      is RpcResult.Success -> RpcScreenStateAdapter(retry.data)
      is RpcResult.Failure -> {
        Console.log(
          "[HostAccessibilityRpcClient] GetScreenState retry after re-warm still failed " +
            "${retry.errorType}: ${retry.message}" +
            (retry.details?.let { "\n  Details: $it" } ?: ""),
        )
        null
      }
    }
  }

  override fun close() {
    rpcClient.close()
  }

  /** Replaces the host's [memory] with the device's post-execution snapshot. */
  private fun applyMemorySnapshot(deviceSnapshot: Map<String, String>) {
    memory.variables.clear()
    memory.variables.putAll(deviceSnapshot)
  }
}

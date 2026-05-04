package xyz.block.trailblaze.compose.driver.rpc

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.driver.ComposeScreenState
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * RPC handler that executes a batch of Compose tools.
 *
 * Tools are executed sequentially. Execution stops on the first error, returning partial results.
 * Only [ComposeExecutableTool] types are supported.
 */
class ExecuteToolsHandler(
  private val target: ComposeTestTarget,
  private val mutex: Mutex,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
  /**
   * Repo used to resolve [OtherTrailblazeTool] payloads back to concrete Compose tools. Tools
   * arrive over RPC as `{toolName, raw}` (the `TrailblazeToolJsonSerializer` shape registered
   * in `TrailblazeJson`) — we route by `toolName` against the repo's catalog entries and
   * decode the flat args via the matching class serializer.
   *
   * Default value builds a repo with the full Compose toolset enabled. Tests and embedders
   * can pass a custom repo for controlled coverage; callers that wire a richer driver context
   * (e.g. host-side bridges) inject their own configured repo instead of letting the handler
   * own catalog discovery.
   */
  private val toolRepo: TrailblazeToolRepo = defaultComposeToolRepo(),
) : RpcHandler<ExecuteToolsRequest, ExecuteToolsResponse> {

  /** Shared memory across all RPC calls so variable interpolation works. */
  private val memory = AgentMemory()

  override suspend fun handle(
    request: ExecuteToolsRequest
  ): RpcResult<ExecuteToolsResponse> {
    return mutex.withLock {
      val results = mutableListOf<TrailblazeToolResult>()
      val context = createToolExecutionContext()

      for (tool in request.tools) {
        // Resolve the wire-shape OtherTrailblazeTool back to its concrete class. A failure
        // here (unknown toolName, args don't fit the schema) breaks the same way executor
        // failures do — capture as an ExceptionThrown result and stop. Letting the throw
        // bubble out of the loop would skip the partial-results contract and turn the whole
        // batch into an opaque RPC failure for the client.
        val resolved = try {
          resolveTool(tool)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          val toolName = (tool as? OtherTrailblazeTool)?.toolName ?: tool::class.simpleName
          results.add(
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "Failed to resolve '$toolName': ${e.message}",
              command = tool,
            ),
          )
          break
        }
        if (resolved !is ComposeExecutableTool) {
          results.add(
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage =
                "Unsupported tool type: ${resolved::class.simpleName}. " +
                  "Only ComposeExecutableTool types are supported over RPC.",
            )
          )
          break
        }

        try {
          val result = resolved.executeWithCompose(target, context)
          results.add(result)
          if (!result.isSuccess()) break
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          results.add(TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e))
          break
        }
      }

      RpcResult.Success(ExecuteToolsResponse(results = results))
    }
  }

  /**
   * Tools arrive over the wire as [OtherTrailblazeTool] (the `TrailblazeToolJsonSerializer`
   * always decodes the abstract type as the wrapped shape). Look the concrete class up by name
   * against [toolRepo] and decode the flat args; non-wrapped instances pass through unchanged
   * for tests that hand-construct concrete tools.
   */
  private fun resolveTool(tool: TrailblazeTool): TrailblazeTool {
    if (tool !is OtherTrailblazeTool) return tool
    return toolRepo.toolCallToTrailblazeTool(tool.toolName, tool.raw.toString())
  }

  private fun createToolExecutionContext(): TrailblazeToolExecutionContext {
    val screenState =
      ComposeScreenState(
        target = target,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
    val freshScreenStateProvider: () -> ComposeScreenState = {
      ComposeScreenState(
        target = target,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
    }
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      screenStateProvider = freshScreenStateProvider,
      traceId = null,
      trailblazeDeviceInfo =
        TrailblazeDeviceInfo(
          trailblazeDeviceId =
            TrailblazeDeviceId(
              instanceId = "compose-rpc",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
            ),
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          widthPixels = viewportWidth,
          heightPixels = viewportHeight,
        ),
      sessionProvider = {
        TrailblazeSession(
          sessionId = SessionId("rpc-session"),
          startTime = Clock.System.now(),
        )
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = memory,
    )
  }

  companion object {
    /**
     * Default [TrailblazeToolRepo] used when callers don't inject one — opens the full Compose
     * toolset (`compose_core`, `compose_verification`, `memory`) so every
     * [ComposeExecutableTool] the agent might advertise is reachable. Embedders that need a
     * narrower or richer surface (e.g. with custom dynamic tool registrations) can construct
     * their own repo and pass it via the constructor.
     */
    fun defaultComposeToolRepo(): TrailblazeToolRepo =
      TrailblazeToolRepo
        .withDynamicToolSets(driverType = TrailblazeDriverType.COMPOSE)
        .also { it.setActiveToolSets(ComposeToolSetIds.ALL) }
  }
}

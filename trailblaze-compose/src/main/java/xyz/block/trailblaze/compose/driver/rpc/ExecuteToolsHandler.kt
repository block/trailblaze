package xyz.block.trailblaze.compose.driver.rpc

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.driver.ComposeScreenState
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * RPC handler that executes a batch of Compose tools.
 *
 * Tools are executed sequentially. Execution stops on the first error, returning partial results.
 * Only [ComposeExecutableTool] types are supported.
 */
@OptIn(ExperimentalTestApi::class)
class ExecuteToolsHandler(
  private val composeUiTest: ComposeUiTest,
  private val mutex: Mutex,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
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
        if (tool !is ComposeExecutableTool) {
          results.add(
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage =
                "Unsupported tool type: ${tool::class.simpleName}. " +
                  "Only ComposeExecutableTool types are supported over RPC.",
            )
          )
          break
        }

        try {
          val result = tool.executeWithCompose(composeUiTest, context)
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

  private fun createToolExecutionContext(): TrailblazeToolExecutionContext {
    val screenState =
      ComposeScreenState(
        composeUiTest = composeUiTest,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
    val freshScreenStateProvider: () -> ComposeScreenState = {
      ComposeScreenState(
        composeUiTest = composeUiTest,
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
}

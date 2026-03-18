package xyz.block.trailblaze.compose.driver.rpc

import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.compose.driver.ComposeScreenState
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * RPC handler that captures the current Compose screen state.
 *
 * Returns a screenshot (Base64-encoded PNG), the view hierarchy as serialized JSON, and the
 * semantics tree text snapshot.
 */
class GetScreenStateHandler(
  private val target: ComposeTestTarget,
  private val mutex: Mutex,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
) : RpcHandler<GetScreenStateRequest, GetScreenStateResponse> {

  @OptIn(ExperimentalEncodingApi::class)
  override suspend fun handle(
    request: GetScreenStateRequest
  ): RpcResult<GetScreenStateResponse> {
    return mutex.withLock {
      try {
        val screenState =
          ComposeScreenState(
            target = target,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            requestedDetails = request.requestedDetails,
          )

        val screenshotBase64 =
          screenState.screenshotBytes?.let { Base64.encode(it) }

        val serializableMapping =
          screenState.elementIdMapping.mapValues { (_, ref) ->
            SerializableComposeElementRef(
              descriptor = ref.descriptor,
              nthIndex = ref.nthIndex,
              testTag = ref.testTag,
            )
          }

        RpcResult.Success(
          GetScreenStateResponse(
            screenshotBase64 = screenshotBase64,
            viewHierarchy = screenState.viewHierarchy,
            semanticsTreeText = screenState.semanticsTreeText,
            width = screenState.deviceWidth,
            height = screenState.deviceHeight,
            elementIdMapping = serializableMapping,
            trailblazeNodeTree = screenState.trailblazeNodeTree,
          )
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        RpcResult.Failure(
          errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
          message = "Failed to capture screen state",
          details = e.message,
        )
      }
    }
  }
}

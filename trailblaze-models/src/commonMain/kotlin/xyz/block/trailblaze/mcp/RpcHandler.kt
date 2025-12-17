package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Handler interface for processing RPC requests.
 *
 * Each request type should have its own handler implementation.
 * Handlers can be objects (for stateless handlers) or classes (for stateful handlers).
 *
 * The response type is automatically inferred from the request type.
 *
 * Example:
 * ```kotlin
 * // Request declares its response type
 * @Serializable
 * object MyRequest : RpcRequest<MyResponse>
 *
 * // Handler uses that response type
 * object MyRequestHandler : RpcHandler<MyRequest, MyResponse> {
 *   override suspend fun handle(request: MyRequest): RpcResult<MyResponse> {
 *     // Process the request
 *     return RpcResult.Success(MyResponse(...))
 *   }
 * }
 * ```
 */
interface RpcHandler<TRequest : RpcRequest<TResponse>, TResponse : Any> {
  /**
   * Handle the RPC request and return a result.
   *
   * @param request The request to process
   * @return RpcResult containing either the response or error information
   */
  suspend fun handle(request: TRequest): RpcResult<TResponse>
}

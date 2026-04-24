package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlin.reflect.KClass

/**
 * Marker interface for all RPC request types.
 * Any class implementing this interface can be used with the generic rpcCall function.
 * 
 * @param TResponse The response type this request returns
 * 
 * Example:
 * ```kotlin
 * @Serializable
 * object DeviceStatusRequest : RpcRequest<DeviceStatusResponse>
 * 
 * // Now the compiler knows what type rpcCall returns!
 * val result = client.rpcCall(DeviceStatusRequest) // Returns RpcResult<DeviceStatusResponse>
 * ```
 */
interface RpcRequest<TResponse : Any> {
  /**
   * Optional per-request timeout override (request + socket) in milliseconds. `null` means the
   * RPC uses the default HttpClient configuration.
   *
   * Scopes the long timeout to requests that actually need it (e.g.
   * [xyz.block.trailblaze.llm.RunYamlRequest] with `awaitCompletion = true` runs until the
   * on-device trail finishes — minutes — so it overrides to a long budget). Short RPCs like
   * `GetScreenStateRequest` stay on the short default so a hung server is detected quickly
   * instead of hanging for 20 minutes.
   */
  val requestTimeoutMs: Long? get() = null

  companion object {
    /**
     * Utilities for computing RPC paths from request types.
     */

    /**
     * Converts a class name to a kebab-case RPC path.
     * Example: "DeviceStatusRequest" -> "/device-status-request"
     */
    fun KClass<*>.toRpcPath(): String = "/rpc/${this.simpleName}"
  }
}

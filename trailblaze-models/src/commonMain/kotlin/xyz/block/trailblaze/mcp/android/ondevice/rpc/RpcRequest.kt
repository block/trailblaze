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

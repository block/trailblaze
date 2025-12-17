package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable

/**
 * Result wrapper for RPC calls that can either succeed or fail.
 */
@Serializable
sealed interface RpcResult<out T> {
  /**
   * Successful RPC call with the response data.
   */
  @Serializable
  data class Success<T>(val data: T) : RpcResult<T>

  /**
   * Failed RPC call with error information.
   */
  @Serializable
  data class Failure(
    val errorType: ErrorType,
    val message: String,
    val details: String? = null,
    val method: String? = null,
    val url: String? = null
  ) : RpcResult<Nothing>

  /**
   * Types of errors that can occur during RPC calls.
   */
  @Serializable
  enum class ErrorType {
    /** Network connection error */
    NETWORK_ERROR,

    /** HTTP error (4xx, 5xx status codes) */
    HTTP_ERROR,

    /** JSON serialization/deserialization error */
    SERIALIZATION_ERROR,

    /** Unexpected error */
    UNKNOWN_ERROR
  }
}

/**
 * Extension to get the data or null if failed.
 */
fun <T> RpcResult<T>.getOrNull(): T? = when (this) {
  is RpcResult.Success -> data
  is RpcResult.Failure -> null
}

/**
 * Extension to get the data or throw if failed.
 */
fun <T> RpcResult<T>.getOrThrow(): T = when (this) {
  is RpcResult.Success -> data
  is RpcResult.Failure -> throw RpcException(errorType, message, details, method, url)
}

/**
 * Extension to transform the success value.
 */
inline fun <T, R> RpcResult<T>.map(transform: (T) -> R): RpcResult<R> = when (this) {
  is RpcResult.Success -> RpcResult.Success(transform(data))
  is RpcResult.Failure -> this
}

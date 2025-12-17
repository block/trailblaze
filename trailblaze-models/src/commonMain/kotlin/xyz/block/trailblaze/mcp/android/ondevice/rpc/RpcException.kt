package xyz.block.trailblaze.mcp.android.ondevice.rpc

/**
 * Exception thrown when unwrapping a failed RpcResult.
 */
class RpcException(
  val errorType: RpcResult.ErrorType,
  override val message: String,
  val details: String? = null,
  val method: String? = null,
  val url: String? = null
) : Exception(
  buildString {
    append("RPC Error [$errorType]")
    method?.let { append(" [$it]") }
    append(": $message")
    details?.let { append(" - $it") }
    url?.let { append(" (URL: $it)") }
  }
)
package xyz.block.trailblaze.mcp.models

import kotlinx.serialization.Serializable

/**
 * Standardized error response schema for RPC failures.
 * Used to ensure consistent error formatting across all RPC endpoints.
 *
 * @property errorType The category of error that occurred
 * @property message A human-readable error message
 * @property details Optional additional details about the error
 */
@Serializable
data class RpcErrorResponse(
  val errorType: String,
  val message: String,
  val details: String? = null
)

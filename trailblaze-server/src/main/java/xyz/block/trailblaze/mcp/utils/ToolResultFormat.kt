package xyz.block.trailblaze.mcp.utils

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Standardized tool result format for MCP responses.
 *
 * Goals:
 * - Consistent structure across all tools
 * - Minimal token usage
 * - Actionable information for the LLM
 *
 * Format:
 * ```
 * [SUCCESS] Tapped on 'Submit' button at (320, 540)
 * Screen updated. Next: Check for confirmation dialog.
 * ```
 */
data class ToolResultSummary(
  val success: Boolean,
  val action: String,
  val details: String? = null,
  val nextHint: String? = null,
) {
  /**
   * Formats the result as a concise, LLM-friendly string.
   */
  fun format(): String = buildString {
    // Status prefix
    append(if (success) "[OK] " else "[FAILED] ")

    // Main action description
    append(action)

    // Optional details on same line if short
    if (details != null) {
      if (details.length < 50) {
        append(". $details")
      } else {
        appendLine()
        append(details)
      }
    }

    // Optional hint for next action
    if (nextHint != null) {
      appendLine()
      append("Hint: $nextHint")
    }
  }

  /**
   * Converts to MCP CallToolResult.
   */
  fun toCallToolResult(): CallToolResult = CallToolResult(
    content = mutableListOf(TextContent(format())),
    isError = !success,
  )

  companion object {
    /**
     * Creates a success result.
     */
    fun success(
      action: String,
      details: String? = null,
      nextHint: String? = null,
    ) = ToolResultSummary(
      success = true,
      action = action,
      details = details,
      nextHint = nextHint,
    )

    /**
     * Creates a failure result.
     */
    fun failure(
      action: String,
      reason: String,
      nextHint: String? = null,
    ) = ToolResultSummary(
      success = false,
      action = action,
      details = reason,
      nextHint = nextHint,
    )

    /**
     * Creates a result for a tap action.
     */
    fun tap(
      target: String,
      x: Int,
      y: Int,
      success: Boolean = true,
    ) = ToolResultSummary(
      success = success,
      action = "Tapped on $target at ($x, $y)",
      nextHint = if (success) "Check screen for expected changes" else "Verify element is visible and try again",
    )

    /**
     * Creates a result for a swipe action.
     */
    fun swipe(
      direction: String,
      success: Boolean = true,
    ) = ToolResultSummary(
      success = success,
      action = "Swiped $direction",
      nextHint = if (success) "Check if content scrolled as expected" else null,
    )

    /**
     * Creates a result for text input.
     */
    fun textInput(
      text: String,
      success: Boolean = true,
    ) = ToolResultSummary(
      success = success,
      action = "Entered text: \"${text.take(30)}${if (text.length > 30) "..." else ""}\"",
      nextHint = if (success) "Verify text appears in field" else null,
    )

    /**
     * Creates a result for navigation.
     */
    fun navigation(
      action: String,
      destination: String? = null,
      success: Boolean = true,
    ) = ToolResultSummary(
      success = success,
      action = action,
      details = destination?.let { "Now on: $it" },
    )

    /**
     * Creates a result for assertion/verification.
     */
    fun assertion(
      condition: String,
      passed: Boolean,
    ) = ToolResultSummary(
      success = passed,
      action = if (passed) "Verified: $condition" else "Assertion failed: $condition",
    )

    /**
     * Creates a result for configuration changes.
     */
    fun configChange(
      setting: String,
      newValue: String,
    ) = ToolResultSummary(
      success = true,
      action = "Set $setting to $newValue",
    )

    /**
     * Wraps an existing string result in the standard format.
     * Use this for backward compatibility with existing tool implementations.
     */
    fun fromLegacy(
      result: String,
      success: Boolean = true,
    ): ToolResultSummary {
      // Try to detect if it's an error message
      val isError = result.startsWith("Error:") ||
        result.startsWith("Failed:") ||
        result.contains("not found", ignoreCase = true) ||
        result.contains("unable to", ignoreCase = true)

      return ToolResultSummary(
        success = success && !isError,
        action = result.lines().firstOrNull()?.take(100) ?: result.take(100),
        details = if (result.lines().size > 1) result.lines().drop(1).joinToString("\n") else null,
      )
    }
  }
}

/**
 * Extension to convert a string result to standardized format.
 */
fun String.toToolResult(success: Boolean = true): ToolResultSummary =
  ToolResultSummary.fromLegacy(this, success)

/**
 * Helper object for building tool results with common patterns.
 */
object ToolResults {
  fun ok(message: String) = ToolResultSummary.success(message)
  fun fail(message: String, reason: String) = ToolResultSummary.failure(message, reason)

  /**
   * For device not connected errors.
   */
  fun noDevice() = ToolResultSummary.failure(
    action = "No device connected",
    reason = "Connect a device first using connectToDevice()",
    nextHint = "Call listConnectedDevices() to see available devices",
  )

  /**
   * For element not found errors.
   */
  fun elementNotFound(selector: String) = ToolResultSummary.failure(
    action = "Element not found: $selector",
    reason = "The specified element is not visible on screen",
    nextHint = "Try scrolling or navigating to the correct screen",
  )

  /**
   * For timeout errors.
   */
  fun timeout(operation: String) = ToolResultSummary.failure(
    action = "Timeout: $operation",
    reason = "Operation took too long to complete",
    nextHint = "Check if the app is responsive",
  )
}

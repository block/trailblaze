package xyz.block.trailblaze.android.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.allDriverMatches

/**
 * Activates the element matching [nodeSelector] with `AccessibilityNodeInfo.ACTION_CLICK`, the way
 * TalkBack would, instead of letting the Android accessibility driver choose between that and a
 * coordinate gesture.
 *
 * `tapOnElementBySelector` is the tap to use. Its driver sends a textless container to a gesture,
 * because some apps' containers advertise ACTION_CLICK and ignore it. A few rows are that shape
 * and answer ACTION_CLICK while absorbing the gesture, and no field on the node tells them apart:
 * - DocumentsUI's file-picker row (`item_root`), a textless LinearLayout in another app's window.
 * - A Compose `selectable` option row on API 29, which cannot publish the selection state that
 *   would otherwise route it to ACTION_CLICK (`stateDescription` only exists from API 30).
 *
 * Use this only for a step measured to need it. Every other dispatch precondition still holds: a
 * node that is disabled, editable, hidden or does not advertise ACTION_CLICK still gets a gesture.
 * It needs the Android accessibility driver; elsewhere it fails rather than tapping some other way.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_accessibilityClick",
  surfaceToLlm = false,
)
@LLMDescription(
  "Activates an Android element with the accessibility click action, as TalkBack would. Only for " +
    "a row measured to ignore a normal tap; use tapOnElementBySelector otherwise.",
)
data class AndroidAccessibilityClickTrailblazeTool(
  val reason: String? = null,
  /** The element to activate, as recorded under the Android accessibility driver. */
  val nodeSelector: TrailblazeNodeSelector,
  /** How long to wait for the element to appear; `null` takes the driver's default. */
  val timeoutMs: Long? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val platform = toolExecutionContext.trailblazeDeviceInfo.platform
    if (platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_accessibilityClick is Android-only (got platform: $platform). " +
          "Use tapOnElementBySelector.",
        command = this,
      )
    }
    val agent = toolExecutionContext.maestroTrailblazeAgent
    // Look through the whole tree: a row picked out by `containsChild:` or `childOf:` has its
    // androidAccessibility match nested, not at the root.
    val hasAccessibilityMatch = nodeSelector.allDriverMatches().any { it is DriverNodeMatch.AndroidAccessibility }
    if (!hasAccessibilityMatch || agent == null || !agent.usesAccessibilityDriver) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_accessibilityClick needs the Android accessibility driver and a " +
          "selector with an androidAccessibility match.",
        command = this,
      )
    }
    return agent.executeNodeSelectorTap(
      nodeSelector = nodeSelector,
      longPress = false,
      tapRoute = TapRouteOverride.ACTION_CLICK,
      timeoutMs = timeoutMs,
      traceId = toolExecutionContext.traceId,
    ) ?: TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "android_accessibilityClick: the driver did not resolve the selector.",
      command = this,
    )
  }
}

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
 * Touches the centre of the element matching [nodeSelector] with a coordinate gesture, a finger on
 * the glass, instead of letting the Android accessibility driver choose between that and
 * `AccessibilityNodeInfo.ACTION_CLICK`.
 *
 * The mirror image of [AndroidAccessibilityClickTrailblazeTool]. `tapOnElementBySelector` sends a
 * node that carries its own label and advertises ACTION_CLICK to ACTION_CLICK, which is right for
 * nearly every button. A few such nodes answer the action with something other than what a touch
 * does, and no field on the node tells them apart from the buttons:
 * - A read-only quantity field (a non-editable `EditText` showing its value) whose ACTION_CLICK
 *   only focuses it, while a touch opens the numpad that edits it. A sibling price field of the
 *   same shape opens its numpad from either.
 *
 * Use this only for a step measured to need it. A gesture needs nothing from the node beyond its
 * bounds, so unlike [AndroidAccessibilityClickTrailblazeTool] there is no precondition that can
 * send it the other way. It needs the Android accessibility driver; elsewhere it fails rather than
 * tapping some other way.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_gestureTap",
  surfaceToLlm = false,
)
@LLMDescription(
  "Touches an Android element's centre with a real tap gesture. Only for an element measured to " +
    "ignore the accessibility click a normal tap sends it; use tapOnElementBySelector otherwise.",
)
data class AndroidGestureTapTrailblazeTool(
  val reason: String? = null,
  /** The element to touch, as recorded under the Android accessibility driver. */
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
        errorMessage = "android_gestureTap is Android-only (got platform: $platform). " +
          "Use tapOnElementBySelector.",
        command = this,
      )
    }
    val agent = toolExecutionContext.maestroTrailblazeAgent
    // Look through the whole tree: an element picked out by `containsChild:` or `childOf:` has its
    // androidAccessibility match nested, not at the root.
    val hasAccessibilityMatch = nodeSelector.allDriverMatches().any { it is DriverNodeMatch.AndroidAccessibility }
    if (!hasAccessibilityMatch || agent == null || !agent.usesAccessibilityDriver) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_gestureTap needs the Android accessibility driver and a " +
          "selector with an androidAccessibility match.",
        command = this,
      )
    }
    return agent.executeNodeSelectorTap(
      nodeSelector = nodeSelector,
      longPress = false,
      tapRoute = TapRouteOverride.GESTURE,
      timeoutMs = timeoutMs,
      traceId = toolExecutionContext.traceId,
    ) ?: TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "android_gestureTap: the driver did not resolve the selector.",
      command = this,
    )
  }
}

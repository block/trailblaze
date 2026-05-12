package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.isBlank

/**
 * Shared lowering for selector-based tools that project into Maestro Orchestra commands
 * ([TapOnByElementSelector], [AssertVisibleBySelectorTrailblazeTool], and any future
 * selector-based tool that needs the same fallback).
 *
 * Returns the [TrailblazeElementSelector] to feed into Maestro:
 *  - If [selector] is non-null, returns it directly (legacy projection, canonical path).
 *  - If [selector] is null and [nodeSelector] is non-null, lowers the nodeSelector via the
 *    inverse conversion ([TrailblazeNodeSelector.toTrailblazeElementSelector]) so the same
 *    Maestro orchestra resolves it.
 *  - If both are null, returns null — callers handle this case themselves (Tap emits an
 *    empty command list; Assert throws as a malformed recording).
 *
 * Throws [IllegalStateException] when [selector] is null and the lowered result is blank
 * (no matchable predicates anywhere in its structural tree per
 * [TrailblazeElementSelector.isBlank]). A blank lowering would silently match an arbitrary
 * element rather than failing — surface that loudly here so authoring mistakes are caught
 * at execution time with a clear diagnostic instead of a mis-targeted test.
 */
internal fun lowerToMaestroSelector(
  selector: TrailblazeElementSelector?,
  nodeSelector: TrailblazeNodeSelector?,
): TrailblazeElementSelector? {
  if (selector != null) return selector
  val lowered = nodeSelector?.toTrailblazeElementSelector() ?: return null
  if (lowered.isBlank()) {
    error(
      "Cannot lower nodeSelector to Maestro orchestra: the inverse conversion " +
        "produced a selector with no matchable predicates. The nodeSelector's only " +
        "predicates are driver-only fields (e.g. classNameRegex, accessibilityTextRegex, " +
        "hintTextRegex, contentDescriptionRegex) that don't map to the Maestro-shaped " +
        "TrailblazeElementSelector fields. The same check walks structural relations " +
        "(containsChild, childOf, etc.) — a wrapper around a blank inner selector also " +
        "fails this guard. " +
        "\n\nTo make this recording Maestro-compatible, add a textRegex or resourceIdRegex " +
        "(which DO map) somewhere in the nodeSelector's match chain, or run this trail " +
        "under an accessibility-native driver that resolves nodeSelectors against the live " +
        "tree instead of going through Maestro orchestra. " +
        "\n\nnodeSelector=${nodeSelector?.driverMatch?.description() ?: nodeSelector?.description() ?: "?"}",
    )
  }
  return lowered
}

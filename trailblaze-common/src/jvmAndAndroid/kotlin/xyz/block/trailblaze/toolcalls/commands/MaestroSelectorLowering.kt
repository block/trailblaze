package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.isBlank
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console

/**
 * Shared lowering for selector-based tools that project into Maestro Orchestra commands
 * ([TapOnByElementSelector], [AssertVisibleBySelectorTrailblazeTool], and any future
 * selector-based tool that needs the same fallback).
 *
 * Returns the [TrailblazeElementSelector] to feed into Maestro by lowering [nodeSelector] via
 * the inverse conversion ([TrailblazeNodeSelector.toTrailblazeElementSelector]). Returns null
 * when [nodeSelector] is null — callers handle this case themselves (Tap emits an empty
 * command list; Assert throws as a malformed recording).
 *
 * Throws [IllegalStateException] when the lowered result is blank (no matchable predicates
 * anywhere in its structural tree per [TrailblazeElementSelector.isBlank]). A blank lowering
 * would silently match an arbitrary element rather than failing — surface that loudly here so
 * authoring mistakes are caught at execution time with a clear diagnostic instead of a
 * mis-targeted test.
 */
internal fun lowerToMaestroSelector(
  nodeSelector: TrailblazeNodeSelector?,
): TrailblazeElementSelector? {
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

/**
 * Picks the [TrailblazeNodeSelector] to record for a tool whose replay may dispatch through
 * [lowerToMaestroSelector] ([TapTrailblazeTool] / [AssertVisibleTrailblazeTool], non-accessibility
 * path).
 *
 * On ANDROID this path always dispatches via Maestro lowering (the instrumentation agent has no
 * native nodeSelector tap/assert), and only the TapSelectorV2-derived [legacyAsNodeSelector] is
 * uniqueness-verified against the UiAutomator hierarchy Maestro resolves against. The modern
 * generator describes the TrailblazeNode hitTest winner, which climbs to the nearest interactive
 * ancestor (e.g. a scrollable RecyclerView) — so [modernNodeSelector] can lower to a non-blank but
 * mis-targeted Maestro selector: a tap hits an arbitrary row at the container's center, an assert
 * passes vacuously (Square AI instrumentation regression after #4538). ANDROID therefore always
 * records [legacyAsNodeSelector]. (ANDROID here is a safe proxy for "Maestro dispatch": the
 * accessibility driver's recording path returns earlier in both tools and never reaches this
 * decision.)
 *
 * Other platforms keep [modernNodeSelector] when it lowers to a non-blank Maestro selector —
 * their dispatch resolves the nodeSelector natively (e.g. web/Playwright), and the legacy
 * conversion drops their driver match entirely. When it doesn't lower cleanly (e.g. driver-only
 * classNameRegex), fall back to [legacyAsNodeSelector] so the Maestro fallback stays functional.
 */
internal fun recordedNodeSelectorForMaestroPath(
  platform: TrailblazeDevicePlatform,
  modernNodeSelector: TrailblazeNodeSelector?,
  legacyAsNodeSelector: TrailblazeNodeSelector,
): TrailblazeNodeSelector {
  if (platform == TrailblazeDevicePlatform.ANDROID) {
    if (modernNodeSelector != null) {
      Console.log(
        "[record-selector] ANDROID Maestro path: recording TapSelectorV2 selector " +
          "(${legacyAsNodeSelector.description()}); discarding modern nodeSelector " +
          "(${modernNodeSelector.description()})",
      )
    }
    return legacyAsNodeSelector
  }
  val modernLowersToMaestro =
    modernNodeSelector?.toTrailblazeElementSelector()?.let { !it.isBlank() } ?: false
  return if (modernLowersToMaestro) modernNodeSelector!! else legacyAsNodeSelector
}

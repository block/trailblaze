package xyz.block.trailblaze.android.accessibility

/**
 * Wait window applied to an accessibility assertion whose caller passes no explicit `timeoutMs`.
 *
 * Shared by every path that can produce one, because an assertion's window must not depend on how
 * it reached the driver: [AccessibilityTrailblazeAgent] dispatches node-selector assertions
 * directly, while [MaestroCommandConverter] lowers Maestro-shaped ones (legacy
 * `assertVisibleWithText`, accessibility-text and resource-id assertions, and the not-visible
 * fallback). Those two used to carry independent defaults, so the same unpinned assertion got a
 * different window depending on its route.
 *
 * 17s is not a Trailblaze judgement about how long a screen should take. It is upstream Maestro's
 * own `lookupTimeoutMs` default, inherited when `Orchestra.kt` was vendored into
 * `android/maestro/orchestra/` from Maestro v2.6.1. The instrumentation driver runs on that vendored
 * default, so matching it here is what keeps a trail's assertion windows unchanged when it flips
 * drivers — the parity is the point, not the number. Upstream also splits out
 * `optionalLookupTimeoutMs = 7000L` for lookups allowed to miss; this driver has no equivalent.
 *
 * The window is a ceiling on failure, not a delay: an assertion polls the accessibility tree and
 * returns as soon as it resolves, so a longer default costs a passing assertion nothing. It only
 * makes a failure take longer to report.
 *
 * That asymmetry inverts for a probe whose expected outcome is the failing one — a
 * `block_runIf`/`block_dismissIfPresent` condition polls to exhaustion every time the dialog isn't
 * there, and pays the whole window. Such conditions should pin a short `timeoutMs` rather than
 * inherit this default.
 */
internal const val DEFAULT_ACCESSIBILITY_ASSERT_TIMEOUT_MS = 17_000L

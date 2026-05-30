package xyz.block.trailblaze.toolcalls

/**
 * Marker interface for tools that are guaranteed not to mutate device state.
 *
 * Implemented by query-shaped tools (today: `FindMatchesTrailblazeTool`) so the
 * dispatch loop in `BaseTrailblazeAgent.runTrailblazeTools` can skip the
 * post-tool snapshot-cache invalidation step — the captured view hierarchy is
 * still valid for a follow-up query in the same batch.
 *
 * ## Why the marker, not the [TrailblazeToolClass] annotation flags
 *
 * `isVerification = true` is the existing "read-only" signal, but it implies
 * "successful execution IS the assertion verdict" — a semantic that doesn't fit
 * a query like `findMatches` (the assertion is in the caller's `matches.length`
 * branch, not in the tool's success/failure return). `isRecordable = false`
 * doesn't reliably partition mutating from non-mutating either — `TapTrailblazeTool`
 * has `isRecordable = false` because it delegates to a more precise recorded
 * form, but it absolutely mutates the device.
 *
 * The safest gate is therefore "always invalidate unless the tool opted in to
 * read-only via [ReadOnlyTrailblazeTool] or [TrailblazeToolClass.isVerification]."
 * Tools that read from the device WITHOUT mutating it (queries, inspections,
 * capture tools that only hand bytes back) should adopt this marker. Tools
 * that mutate state — even subtly, e.g. focus changes, app relaunches, network
 * traffic — must NOT implement this interface, even if their annotation has
 * `isRecordable = false` for a different reason (delegation, intermediate
 * shape, etc.).
 */
interface ReadOnlyTrailblazeTool : TrailblazeTool

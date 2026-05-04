package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Type-safe wrapper for session identifiers.
 * Using an inline value class provides compile-time type safety with zero runtime overhead.
 *
 * ## Construction
 * - Use the primary constructor [SessionId] to wrap an already-sanitized value
 *   (lookups, deserialization, parsing an existing ID read back from MCP, disk,
 *   or another process).
 * - Use [Companion.sanitized] to produce a filesystem-safe ID from raw input
 *   (user-provided names, test seeds, anything that may contain non-alphanumeric
 *   characters or uppercase letters).
 *
 * The two entry points are deliberately separate: sanitizing a lookup value
 * would mutate the query and miss existing records, while wrapping raw input
 * without sanitization produces IDs that cannot be safely used as directory
 * or filename components.
 *
 * ## When NOT to sanitize
 * Do not reach for [Companion.sanitized] as a "safe default" on values you read
 * back from another source. Those values are already in canonical form, and
 * re-sanitizing a lookup value is a no-op in the happy path but masks typos in
 * ID queries instead of letting them surface as misses. Rule of thumb: only
 * sanitize at the point where raw input first enters the system.
 *
 * ## Related
 * [xyz.block.trailblaze.util.toSnakeCaseIdentifier] is a similarly-shaped
 * helper used for general-purpose snake_case identifier generation (collapses
 * consecutive underscores, strips leading digits, strips path/extension). It
 * is NOT the canonical session-ID sanitizer — use [Companion.sanitized] for
 * session IDs so idempotence and long-suffix preservation are guaranteed.
 */
@Serializable
@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "SessionId cannot be blank" }
  }

  override fun toString(): String = value

  companion object {
    private val NON_ALPHANUMERIC = Regex("[^a-zA-Z0-9]")

    /**
     * Produces a filesystem-safe [SessionId] from arbitrary input.
     *
     * - Replaces non-alphanumeric characters with underscores
     * - Converts to lowercase
     *
     * This is the single canonical sanitizer for session IDs. It is idempotent:
     * `sanitized(sanitized(x).value) == sanitized(x)`, which lets a host-generated
     * ID round-trip unchanged through an on-device handler that re-sanitizes any
     * override — otherwise host and device would write to two different session
     * directories.
     *
     * Does **not** truncate: the full input (including long TestRail
     * `__suite__section__case` suffixes) is preserved so downstream tooling can
     * map session IDs back to test identifiers.
     */
    fun sanitized(raw: String): SessionId = SessionId(
      raw.replace(NON_ALPHANUMERIC, "_").lowercase(),
    )

    /**
     * Produces a unique pinned [SessionId] for one trail invocation.
     *
     * Used by the CLI / daemon-handler hot path to scope the post-completion
     * status check to *this* trail's session. Without a pinned ID, the global
     * "every new session in the logs repo since the run started" enumeration
     * cross-attributes failures: when N trails run in parallel against one
     * daemon and any sibling ends with `TimeoutReached` / `MaxCallsLimit`, the
     * still-running CLI invocations all report failed even when their own
     * trail succeeded (a benchmark reproduction observed this on every model
     * that didn't pass 3/3).
     *
     * The 8-char UUID suffix is sufficient: collisions across trails sharing
     * the same `testName` are bounded by the per-step parallelism cap (≤ ~10),
     * and the [sanitized] step lowercases / normalizes the result so the
     * pinned id round-trips cleanly through any layer that re-sanitizes.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun pinnedFor(testName: String): SessionId =
      sanitized("${testName}_${Uuid.random().toString().take(8)}")
  }
}

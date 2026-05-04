package xyz.block.trailblaze.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure rendering helpers for `trailblaze toolbox` output.
 *
 * Extracted out of [ToolboxCommand] so the formatting logic can be unit-tested without
 * spinning a daemon. Each function returns a list of lines (or a single string) — the
 * caller is responsible for emitting them via [xyz.block.trailblaze.util.Console]. This
 * separation matters specifically for:
 *
 *  - Per-tool description "peek" with ellipsis logic ([compactToolPeekLine]). Truncation
 *    rules are subtle (first-line vs. char-cap, distinguish "everything fit" from
 *    "first line fit but more existed"), and a regression to the silent-drop behavior is
 *    invisible until a user notices that `--name <tool>` shows content the listing didn't.
 *  - Target-list block ([renderTargetListBlock]). Platform ids must stay lowercase so
 *    they're copy-pasteable into `--device`; column padding must adapt to the longest
 *    target id. Both are easy to break with a "small" tweak.
 *
 * Tests sit in `ToolboxFormatterTest`. The targeted assertions there pin behavior
 * (ellipsis fires on truncation, lowercase platforms, etc.); the snapshot file is a
 * non-contract eyeball baseline that surfaces unintended formatting drift in PRs.
 *
 * ## Public API typing
 *
 * [renderTargetListBlock] accepts a typed [List]<[TargetSummary]> rather than a raw
 * [JsonArray] so the formatter doesn't leak `kotlinx.serialization` types as part of its
 * public surface. JSON parsing lives at the call site (see [parseTargetSummariesJson])
 * where it can short-circuit malformed daemon responses without crashing the formatter.
 */
internal object ToolboxFormatter {

  /**
   * Max characters of a tool description shown in the compact listing. Roughly fits in
   * a 120-col terminal alongside the longest tool names we ship today
   * (`playwrightSample_web_openFixtureAndVerifyText` is 44 chars). Bump if our names
   * grow longer or the typical description's first sentence routinely gets clipped.
   */
  const val COMPACT_DESC_MAX_CHARS = 100

  /**
   * Renders one tool's compact listing line: `- name: peek` with ellipsis when the peek
   * isn't the whole description. Returns just the line content (no leading indent —
   * the caller decides indentation).
   *
   * Ellipsis appended whenever the displayed text differs from the trimmed full
   * description, so users have an unambiguous "use --name for more" cue. Without that
   * distinction, a description whose first line happens to fit in
   * [COMPACT_DESC_MAX_CHARS] would silently drop subsequent lines and look complete.
   *
   * Returns just `- name` (no colon) when the description is empty.
   */
  fun compactToolPeekLine(toolName: String, toolDesc: String): String {
    val trimmed = toolDesc.trim()
    val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
    val capped = if (firstLine.length > COMPACT_DESC_MAX_CHARS) {
      firstLine.take(COMPACT_DESC_MAX_CHARS - 1).trimEnd()
    } else {
      firstLine
    }
    val needsEllipsis = capped != trimmed
    val peek = if (needsEllipsis) "$capped…" else capped
    return if (peek.isEmpty()) "- $toolName" else "- $toolName: $peek"
  }

  /**
   * Renders the bottom-of-toolbox "Targets:" block — header, one row per target with
   * column-aligned platforms in lowercase, blank line, then the two-line "switch
   * targets" hint. Returns an empty list when there are no other targets.
   *
   * The formatter is type-safe: callers pass a typed list of [TargetSummary] rather than
   * a raw JSON array so a malformed daemon response (null platform element, nested
   * object) doesn't crash the formatter. Use [parseTargetSummariesJson] at the call
   * site to translate a JSON array into this list with explicit error handling.
   */
  fun renderTargetListBlock(otherTargets: List<TargetSummary>): List<String> {
    if (otherTargets.isEmpty()) return emptyList()
    val nameWidth = otherTargets.maxOf { it.name.length }
    val out = mutableListOf<String>()
    out += ""
    out += "Targets:"
    for (row in otherTargets) {
      val paddedName = row.name.padEnd(nameWidth)
      out += if (!row.platforms.isNullOrEmpty()) {
        "  $paddedName  (${row.platforms.joinToString(", ")})"
      } else {
        "  $paddedName"
      }
    }
    out += ""
    out += "  Use --target <name> to see target-specific tools."
    out += "  Target is set per session. End session to switch."
    return out
  }

  /**
   * Public surface row for [renderTargetListBlock]. `name` is the target id (already
   * lowercase); `platforms` is the lowercase platform-id list (`["android", "ios"]`)
   * or null if the target has no platforms declared.
   */
  data class TargetSummary(val name: String, val platforms: List<String>?)

  /**
   * Parses a daemon-emitted JSON array of `{"name": "...", "platforms": ["..."]}` rows
   * into [TargetSummary] instances. Defensive against malformed entries: a non-string
   * platform element is dropped from that row's platform list rather than crashing the
   * whole render. Missing `name` falls back to `"?"` so the row is still visible (and
   * obviously broken) instead of being silently dropped.
   */
  fun parseTargetSummariesJson(otherTargets: JsonArray): List<TargetSummary> =
    otherTargets.map { other ->
      val obj = other.jsonObject
      val name = (obj["name"] as? JsonPrimitive)
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.content
        ?: "?"
      val platforms = (obj["platforms"] as? JsonArray)?.mapNotNull { element ->
        // JsonNull extends JsonPrimitive in kotlinx.serialization, so a bare
        // `as? JsonPrimitive` lets nulls through with a literal `"null"` string.
        // Filter explicitly so a stray null in the daemon response disappears
        // instead of leaking the word "null" into the rendered target row.
        (element as? JsonPrimitive)
          ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
          ?.content
      }
      TargetSummary(name, platforms)
    }
}

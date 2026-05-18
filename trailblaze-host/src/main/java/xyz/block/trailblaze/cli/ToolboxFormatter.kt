package xyz.block.trailblaze.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
   * Renders a role-grouped section — one of the headline blocks at the top of
   * `toolbox`'s default index view, or the entire body of `toolbox <role>` filtered
   * output. Pure: takes the role's tool ids + a name → description lookup and emits
   * a list of lines.
   *
   * Shape:
   * ```
   * <header>
   *   - <toolName>: <description-peek>     (peek built via compactToolPeekLine)
   *   - <toolName2>: <description-peek>
   * ```
   * No leading or trailing blank line — the caller controls spacing relative to other
   * sections. Empty `toolNames` returns an empty list (sections silently elide
   * themselves when there's nothing to show).
   *
   * Tools without a description in [descriptionsByName] render as bare `- toolName`
   * — same fallback as the compact toolset listing.
   */
  fun renderRoleSection(
    header: String,
    toolNames: List<String>,
    descriptionsByName: Map<String, String>,
  ): List<String> {
    if (toolNames.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    out += header
    for (n in toolNames) {
      val desc = descriptionsByName[n] ?: ""
      out += "  ${compactToolPeekLine(n, desc)}"
    }
    return out
  }

  /**
   * Renders the empty-state message for `toolbox <role>` when the daemon's role list
   * is empty for the current target/platform. Distinct from [renderRoleSection]
   * returning `emptyList()` — the headline path silently elides, but the filtered-view
   * path actively informs the user no such tools exist and points at the `waypoints`
   * skill to author one.
   *
   * Returns a non-empty list of lines suitable for emission via `Console.info`. Caller
   * supplies `target` / `platform` already-resolved (or `null` if unknown).
   */
  fun renderRoleEmptyMessage(
    role: String,
    target: String?,
    platform: String?,
    suffix: String,
  ): List<String> {
    val tgt = target ?: "this target"
    val plat = platform ?: "this platform"
    return listOf(
      "No $role tools available for $tgt on $plat.",
      "",
      "If you need one, use the `waypoints` skill to author a new $suffix in the relevant pack.",
    )
  }

  /**
   * Walks one or more daemon-response toolset JSON arrays and collects every tool's
   * name → description from `toolDetails` (or descriptor-shaped `tools`) entries present.
   *
   * Accepts a vararg of `JsonArray?` so callers can mix index-mode shapes
   * (`platformToolsets`, `targetToolsets`) with target-mode shapes (`toolGroups`,
   * `toolsByPlatform`) in one call — the role-filtered CLI view needs all of them because
   * a non-default `--target` request gets a target-mode envelope rather than index-mode.
   * Returns an empty map for the compact `tools: [name, ...]` (string-array) form — no
   * descriptions carried.
   *
   * First write wins on collision — the toolset listed first is the authoritative
   * description for the role view. Pass platform / index sources before target sources
   * to preserve that precedence.
   */
  fun collectToolDescriptions(
    vararg toolsetSources: JsonArray?,
  ): Map<String, String> {
    val byName = mutableMapOf<String, String>()
    for (sources in toolsetSources) {
      sources ?: continue
      for (ts in sources) {
        val tsObj = ts.jsonObject
        // Two carrier shapes:
        //   - Index/target-mode toolsets: `{name, toolDetails: [{name, description, ...}, ...]}`
        //   - Target-mode flat-by-platform: `{platform, tools: [{name, description, ...}, ...]}`
        // Both carry full descriptors at the same shape, so we accept either key for the inner
        // array. (Index-mode compact `tools: [name, ...]` is a string array — `jsonArray.get(...)
        // .jsonObject` will skip it via the catch below.)
        val details = tsObj["toolDetails"]?.jsonArray ?: tsObj["tools"]?.jsonArray ?: continue
        for (t in details) {
          val obj = (t as? JsonObject) ?: continue
          val n = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
          val d = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
          byName.putIfAbsent(n, d)
        }
      }
    }
    return byName
  }

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

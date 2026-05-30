package xyz.block.trailblaze.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
   * Renders the single-line toolbox banner that prepends EVERY `toolbox` invocation —
   * the resolved-context line a downstream LLM (Claude / Codex / etc.) reads first to know
   * "this output describes (target X, platform Y)". Distinct from
   * [renderResolvedTargetHeader]: the banner is unconditional and states WHAT was resolved;
   * the resolved-target header is the OOBE discovery affordance that also tells the user
   * HOW it was resolved and how to switch.
   *
   * Shape:
   *  - `# Trailblaze toolbox` — when neither target nor platform are known (e.g.
   *    `toolbox --name <tool>` invoked without a connected device or pinned target).
   *  - `# Trailblaze toolbox — <target>` — target known, platform unknown.
   *  - `# Trailblaze toolbox — <target> (<platform>)` — both known.
   *  - `… — tool: <toolName>` suffix appended when [toolName] is set, marking this output
   *    as a single-tool drill-down rather than a target-wide listing.
   */
  fun renderToolboxBanner(target: String?, platform: String?, toolName: String? = null): String {
    // Funnel blank-string inputs through the same path as null — picocli won't normally
    // emit empty `--name`, and the CLI already pre-blanks `resolvedDevice` upstream, but
    // belt-and-braces here so the banner never renders a dangling `(<empty>)` or
    // `— tool: ` suffix when a future caller passes empty strings through.
    val normalizedTarget = target?.takeIf { it.isNotBlank() }
    val normalizedPlatform = platform?.takeIf { it.isNotBlank() }
    val normalizedTool = toolName?.takeIf { it.isNotBlank() }
    val core = when {
      normalizedTarget == null && normalizedPlatform == null -> "# Trailblaze toolbox"
      normalizedTarget == null -> "# Trailblaze toolbox — ($normalizedPlatform)"
      normalizedPlatform == null -> "# Trailblaze toolbox — $normalizedTarget"
      else -> "# Trailblaze toolbox — $normalizedTarget ($normalizedPlatform)"
    }
    return if (normalizedTool != null) "$core — tool: $normalizedTool" else core
  }

  /**
   * Renders the `## System prompt` section that surfaces the resolved target's curated
   * LLM-facing prose to a CLI-side agent. Returned as a list of lines including a trailing
   * blank line so the next section ([renderToolsHeader] or the existing catalog) breathes.
   *
   * Empty / blank content returns an empty list — callers can omit the section entirely
   * without a guard. The trailing `## Tools` divider is rendered separately via
   * [renderToolsHeader] so callers can choose whether to emit it (skip it when the system
   * prompt section is absent — there's no need for a `## Tools` header without a `##
   * System prompt` above to disambiguate from).
   */
  fun renderSystemPromptSection(content: String?): List<String> {
    if (content.isNullOrBlank()) return emptyList()
    val out = mutableListOf<String>()
    out += "## System prompt"
    out += ""
    out += content.trimEnd()
    out += ""
    return out
  }

  /**
   * Renders the `## Tools` header line plus a trailing blank. Emitted before the existing
   * catalog rendering when a `## System prompt` section preceded it, so the two sections
   * are visually delimited for downstream LLM consumers.
   */
  fun renderToolsHeader(): List<String> = listOf("## Tools", "")

  /**
   * Renders the full system-prompt block — prose section *plus* the `## Tools` divider —
   * or an empty list when [content] is null / blank.
   *
   * Centralises the "emit `## Tools` only when system prompt was emitted" policy so callers
   * (today: [ToolboxCommand.formatToolsResult]; potentially future ones) don't each re-derive
   * the guard. The pieces remain available individually via [renderSystemPromptSection] and
   * [renderToolsHeader] for callers that need just one half (e.g., a future help screen that
   * surfaces only the prompt).
   */
  fun renderSystemPromptBlock(content: String?): List<String> {
    val prompt = renderSystemPromptSection(content)
    if (prompt.isEmpty()) return emptyList()
    return prompt + renderToolsHeader()
  }

  /**
   * One-shot dispatcher used by [ToolboxCommand.formatToolsResult] to decide whether the
   * `## System prompt` + `## Tools` block fires for a given daemon response.
   *
   * Encodes the cross-mode policy that previously lived as a sequence of statements in the
   * command class: the block is emitted for every target-wide listing (index / target /
   * search / role-filter) and suppressed for tool-specific drill-downs (`--name <tool>`).
   *
   * @param response the daemon's JSON response object (already parsed by the caller)
   * @param isNameMode true when `--name <tool>` is in play (suppresses the block)
   * @return the lines to emit, or an empty list when the block should not fire — either
   *   because the caller is in `--name` mode, the response has no `systemPrompt` field, or
   *   the prompt content is blank
   */
  fun systemPromptBlockForResponse(
    response: JsonObject,
    isNameMode: Boolean,
  ): List<String> {
    if (isNameMode) return emptyList()
    val content = response["systemPrompt"]?.jsonPrimitive?.contentOrNull
    return renderSystemPromptBlock(content)
  }

  /**
   * Renders the three-line "resolved target" header that prepends `toolbox`'s output
   * when the user didn't pass `--target`. Tells them which target was picked, where
   * it came from, what alternatives exist, and how to switch — so the first
   * `toolbox --device <p>` invocation discovers the flag without requiring the user
   * to know the word "target" exists.
   *
   * Returned as a list of lines so the unit test can assert against the line shape
   * without redirecting Console. The caller emits each line via `Console.info`.
   *
   * The pure-rendering contract: caller passes a fully-resolved [sourceLabel] phrase
   * (e.g. "from workspace config", "built-in default"). The formatter stays decoupled
   * from the CLI-resolution enum that drives those phrases.
   *
   * Empty `availableTargets` collapses the second line to just the resolved target —
   * a workspace with no discoverable targets shouldn't render "Available targets: "
   * with a dangling colon.
   */
  fun renderResolvedTargetHeader(
    resolved: String,
    sourceLabel: String,
    availableTargets: List<String>,
  ): List<String> {
    val availableList = if (availableTargets.isEmpty()) resolved else availableTargets.joinToString(", ")
    return listOf(
      "Using target: $resolved (no --target specified; $sourceLabel)",
      "Available targets: $availableList",
      "To switch: --target <name>",
      "",
    )
  }

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
      "If you need one, use the `waypoints` skill to author a new $suffix in the relevant trailmap.",
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
   * Result of rendering a single tool's help (the `toolbox --name <tool>` shape).
   *
   * [Lines] carries the rendered output ready for `Console.info` emission.
   * [Error] is the daemon-reported "tool not found" or other error message — caller
   * decides whether to print via `Console.error` and which exit code to return.
   *
   * **Why this diverges from the `List<String>` convention elsewhere in this file**:
   * the other renderers (`renderTargetListBlock`, `renderRoleSection`,
   * `renderRoleEmptyMessage`) use empty list as their "no output" signal because empty
   * is a benign rendering state. Per-tool help has a distinct *error* shape — the
   * daemon's "tool not found" reply must be routed to `Console.error` (stderr) and
   * paired with a non-zero exit code, not collapsed to "render nothing". The sealed
   * split makes that error vs. success contract explicit at the call sites
   * ([ToolboxCommand.formatNameResult] and [ToolHelpRenderer.renderHelp]) so neither
   * can drop the error path on accident.
   */
  sealed interface ToolNameRender {
    data class Lines(val lines: List<String>) : ToolNameRender
    data class Error(val message: String) : ToolNameRender
  }

  /**
   * Renders the per-tool help block — name, description, parameters, optional
   * category/target footers — from the `toolbox` daemon response JSON. Pure: returns
   * lines; the caller emits them.
   *
   * Shared between `toolbox --name <tool>` (the explicit catalog path) and
   * `tool <name> --help` (the discoverable path) so both surfaces stay in lock-step.
   */
  fun renderToolNameLines(json: JsonObject): ToolNameRender {
    val tool = json["tool"] as? JsonObject
      ?: return ToolNameRender.Error(
        json["error"]?.jsonPrimitive?.contentOrNull ?: "Tool not found",
      )

    val out = mutableListOf<String>()
    val toolName = tool["name"]?.jsonPrimitive?.contentOrNull ?: "?"
    val toolDesc = tool["description"]?.jsonPrimitive?.contentOrNull ?: ""
    out += toolName
    out += "  $toolDesc"
    out += ""
    out += renderParameterLines(tool, "  ")

    // Defensive joins: a malformed daemon payload (null entry, nested object, accidental
    // array-of-arrays) must not crash help rendering — sibling parsers in this file
    // (`parseTargetSummariesJson`) already use the same `mapNotNull { (it as? JsonPrimitive)
    // ... }` shape. Drop bad rows silently and render whatever remains.
    val categories = (json["foundInCategories"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
    if (!categories.isNullOrEmpty()) {
      out += "  Categories: ${categories.joinToString(", ")}"
    }
    val targets = (json["foundInTargets"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
    if (!targets.isNullOrEmpty()) {
      out += "  Targets: ${targets.joinToString(", ")}"
    }
    return ToolNameRender.Lines(out)
  }

  /**
   * Renders parameter rows for a single tool — required first, then optional, each
   * prefixed with [indent]. Empty list when the tool has no parameters.
   */
  fun renderParameterLines(toolObj: JsonObject, indent: String): List<String> {
    val out = mutableListOf<String>()
    val required = toolObj["requiredParameters"] as? JsonArray
    if (required != null) {
      for (param in required) {
        val pObj = (param as? JsonObject) ?: continue
        val pName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
        val pType = pObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
        val pDesc = pObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        out += "${indent}$pName ($pType, required): $pDesc"
      }
    }
    val optional = toolObj["optionalParameters"] as? JsonArray
    if (optional != null) {
      for (param in optional) {
        val pObj = (param as? JsonObject) ?: continue
        val pName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
        val pType = pObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
        val pDesc = pObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        out += "${indent}$pName ($pType, optional): $pDesc"
      }
    }
    return out
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

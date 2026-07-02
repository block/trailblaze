package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import xyz.block.trailblaze.util.Console

// ---------------------------------------------------------------------------
// Agent-optimized output formatters (shared across CLI verbs that dispatch to
// the agent-loop MCP tool: `step`, `tool`, `snapshot`)
// ---------------------------------------------------------------------------

/**
 * Format the MCP `step` tool's result with structured sections (### Page, ### Screen).
 * Agent-optimized: AI coding agents parse this output format.
 *
 * The function name's `Blaze` prefix is **lineage** — this helper predates the
 * `blaze → step` rename and is shared today by three verbs (`step`, `tool`,
 * `snapshot`), all of which dispatch to the same MCP tool (now named `step`).
 * Renaming the helper to `formatStepResult*` would imply it's step-verb-specific,
 * which is wrong. A future rename to a verb-agnostic name (`formatActionResult*`,
 * `formatAgentToolResult*`) is the better cleanup; tracked as deferred housekeeping.
 */
internal fun formatBlazeResultAgent(result: CliMcpClient.ToolResult) {
  if (result.isError) {
    Console.info("### Error")
    Console.error(result.content)
    return
  }

  // Try JSON format first
  try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.info("### Error")
      Console.error(error)
      return
    }
    val screenSummary = json["screenSummary"]?.jsonPrimitive?.content
    if (screenSummary != null) {
      formatScreenSummaryAgent(screenSummary)
      return
    }
  } catch (_: Exception) {
    // Not JSON — parse as daemon's markdown text format
  }

  // Parse plain text / markdown format from daemon
  val text = result.content

  // Extract screenshot path (printed last so it's the final line the user sees)
  val screenshotMarker = "**Screenshot:** "
  val screenshotIdx = text.indexOf(screenshotMarker)
  val screenshotPath = if (screenshotIdx >= 0) {
    val screenshotEnd = text.indexOf('\n', screenshotIdx).let { if (it < 0) text.length else it }
    text.substring(screenshotIdx + screenshotMarker.length, screenshotEnd).trim()
  } else null

  // Surface the daemon's status header (`**✓ Executed** — Tapped Checkout`, `**✅ Done** — …`,
  // StepResult.toMarkdown) as a `→ Executed — Tapped Checkout` breadcrumb above the ### Screen
  // block, so a step isn't silent about what it did.
  val screenMarker = "**Screen:** "
  val screenIdx = text.indexOf(screenMarker)
  val headerMatch = matchStatusHeader(if (screenIdx >= 0) text.substring(0, screenIdx) else text)

  val bodyStart = headerMatch?.endIdx ?: 0
  val bodyEnd = listOf(screenIdx, screenshotIdx).filter { it >= 0 }.minOrNull() ?: text.length
  val bodyAfterHeader =
    if (headerMatch != null && bodyStart <= bodyEnd) text.substring(bodyStart, bodyEnd) else ""

  // A multi-line message (e.g. the JSON a read tool returns via `trailblaze tool`) detaches
  // onto its own lines under a bare verb breadcrumb so its shape survives; a single-line
  // message stays inline and `**Suggestion:**` / `**Hint:**` bodies fall to the middle slice.
  val layout = headerMatch?.let { layoutStatusBody(it, bodyAfterHeader) }
  if (layout != null) {
    Console.info(layout.breadcrumb)
  }

  if (layout?.detachedBody != null) {
    Console.info(layout.detachedBody)
  } else if (bodyStart < bodyEnd) {
    // `**Suggestion:** …` / `**Hint:** …` recovery lines between the header and the screen —
    // trimmed so a lone `\n\n` separator doesn't print as a blank line.
    val middle = text.substring(bodyStart, bodyEnd).trim()
    if (middle.isNotEmpty() && (headerMatch != null || screenIdx < 0)) {
      Console.info(middle)
    }
  }

  if (screenIdx >= 0) {
    val screenEnd = if (screenshotIdx >= 0 && screenshotIdx > screenIdx) screenshotIdx else text.length
    val screenText = text.substring(screenIdx + screenMarker.length, screenEnd).trim()
    formatScreenSummaryAgent(screenText)
  } else if (screenshotPath == null && headerMatch == null) {
    Console.info(text)
  }

  if (screenshotPath != null) {
    Console.info("Screenshot: $screenshotPath")
  }
}

/**
 * Outcome of parsing the leading `**<emoji> Verb** — message` status block
 * out of a daemon markdown blob. [formatted] is the rendered breadcrumb
 * (`→ Verb — message`); [endIdx] is the offset in the *original* input
 * (before the leading-whitespace trim) one past the last consumed character,
 * so callers can resume from there to surface any `**Suggestion:**` /
 * `**Hint:**` lines that follow. [verb] is the cleaned verb alone ("Done",
 * "Executed", …) and [message] the inline first-line message (null when the
 * header carried none) — [layoutStatusBody] uses both to decide whether a
 * multi-line message should detach onto its own lines.
 */
internal data class StatusHeaderMatch(
  val formatted: String,
  val endIdx: Int,
  val verb: String,
  val message: String?,
)

/**
 * How a status header + its trailing body render. A single-line message rides inline on
 * [breadcrumb] (`→ Verb — msg`); a multi-line message — e.g. the JSON a read tool returns
 * via `trailblaze tool` — yields a bare `→ Verb` [breadcrumb] plus [detachedBody] printed
 * beneath, so the payload keeps its indentation instead of the first line colliding with
 * the breadcrumb.
 */
internal data class StatusBodyLayout(val breadcrumb: String, val detachedBody: String?)

/**
 * Decides the [StatusBodyLayout] for [match] given [bodyAfterHeader] — the text between the
 * header and the `**Screen:**` / `**Screenshot:**` markers. The message detaches onto its
 * own lines only when it continues past the first line with non-marker content (a JSON/text
 * payload); `**Suggestion:**` / `**Hint:**` bodies stay for the caller's middle slice.
 */
internal fun layoutStatusBody(match: StatusHeaderMatch, bodyAfterHeader: String): StatusBodyLayout {
  val continuesAsMessage = match.message != null &&
    bodyAfterHeader.isNotBlank() &&
    !bodyAfterHeader.trimStart().startsWith("**")
  return if (continuesAsMessage) {
    StatusBodyLayout(
      breadcrumb = "→ ${match.verb}",
      detachedBody = (match.message + bodyAfterHeader).trimEnd(),
    )
  } else {
    StatusBodyLayout(breadcrumb = match.formatted, detachedBody = null)
  }
}

/**
 * Convenience wrapper for callers that only need the formatted breadcrumb.
 * Returns null when [prefix] doesn't lead with a status header. Used by
 * tests and any consumer that doesn't care about resuming the parse.
 */
internal fun extractStatusHeader(prefix: String): String? =
  matchStatusHeader(prefix)?.formatted

/**
 * Lift the leading `**<emoji> Verb** — message` status line out of a daemon
 * markdown blob. Returns a [StatusHeaderMatch] with the rendered breadcrumb
 * and the end offset in the original [prefix], or null if [prefix] doesn't
 * lead with a status header.
 */
internal fun matchStatusHeader(prefix: String): StatusHeaderMatch? {
  // Skip a leading-whitespace run, then require a `**` opener. The end-index
  // we return is in the ORIGINAL prefix, so callers can resume from there
  // even though our regex matched against the trimmed view.
  val leadingWs = prefix.takeWhile { it.isWhitespace() }.length
  val trimmed = prefix.substring(leadingWs)
  if (!trimmed.startsWith("**")) return null

  // No explicit emoji character class — the daemon uses ✓ / → / ⚠️ / ❌ / ✅
  // today, but a future verb could ship a new emoji and we don't want to
  // chase the regex every time. Match anything inside `**…**` and strip
  // leading decoration after the fact. Crucially this avoids the U+FE0F
  // variation-selector trap: `⚠️` is two codepoints (U+26A0 + U+FE0F) and
  // a single-codepoint character class would consume only U+26A0, leaving
  // U+FE0F to leak into the captured verb.
  val match = Regex("""^\*\*([^*]+?)\*\*(?:[ \t]*—[ \t]*([^\n]+))?""")
    .find(trimmed)
    ?: return null

  val rawVerb = match.groupValues[1]
  val verb = rawVerb.trim().dropWhile { c ->
    c.isWhitespace() ||
      c.category in DECORATION_CATEGORIES ||
      c.code == VARIATION_SELECTOR_16
  }.trim()
  val message = match.groupValues[2].trim().ifEmpty { null }
  val formatted = if (message != null) "→ $verb — $message" else "→ $verb"
  return StatusHeaderMatch(
    formatted = formatted,
    endIdx = leadingWs + match.range.last + 1,
    verb = verb,
    message = message,
  )
}

private val DECORATION_CATEGORIES = setOf(
  CharCategory.OTHER_SYMBOL,
  CharCategory.MATH_SYMBOL,
  CharCategory.MODIFIER_SYMBOL,
  CharCategory.NON_SPACING_MARK,
)

private const val VARIATION_SELECTOR_16 = 0xFE0F

/**
 * Format ask tool result with ### Answer section.
 */
internal fun formatAskResultAgent(result: CliMcpClient.ToolResult) {
  if (result.isError) {
    Console.info("### Error")
    Console.error(result.content)
    return
  }
  try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.info("### Error")
      Console.error(error)
      return
    }
    val answer = json["answer"]?.jsonPrimitive?.content
    val screenSummary = json["screenSummary"]?.jsonPrimitive?.content
    if (answer != null) {
      Console.info("### Answer")
      Console.info(answer)
    }
    // Suppress the ### Screen / ### Page block when its content duplicates the
    // Answer. For "what's on screen?" style asks the LLM fills both fields with
    // the same prose and the user reads two nearly-identical paragraphs.
    // Mirrors the dedup in StepToolSet.StepResult.toMarkdown.
    if (screenSummary != null && !screenSummaryDuplicatesAnswer(answer, screenSummary)) {
      formatScreenSummaryAgent(screenSummary)
    }
  } catch (_: Exception) {
    Console.info(result.content)
  }
}

/**
 * Format verify result with ### Passed / ### Failed.
 * Returns exit code: [TrailblazeExitCode.SUCCESS] if the assertion passed,
 * [TrailblazeExitCode.ASSERTION_FAILED] if the assertion's verdict was false,
 * [TrailblazeExitCode.INFRA_FAILED] if the daemon/tool returned a transport-level
 * error before any verdict could be computed.
 */
internal fun formatVerifyResultAgent(result: CliMcpClient.ToolResult): Int {
  if (result.isError) {
    Console.info("### Error")
    Console.error(result.content)
    return TrailblazeExitCode.INFRA_FAILED.code
  }
  try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.info("### Error")
      Console.error(error)
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val passed = json["passed"]?.jsonPrimitive?.content?.toBoolean() ?: false
    val resultText = json["result"]?.jsonPrimitive?.content ?: ""
    if (passed) {
      Console.info("### Passed")
      Console.info(resultText)
      return TrailblazeExitCode.SUCCESS.code
    } else {
      Console.info("### Failed")
      Console.info(resultText)
      return TrailblazeExitCode.ASSERTION_FAILED.code
    }
  } catch (_: Exception) {
    // Not JSON — parse markdown format from daemon (e.g., "**✅ PASSED** — reason").
    // Print the verdict ABOVE the screen summary so an interactive user sees the
    // pass/fail at a glance instead of inferring it from $?. formatBlazeResultAgent
    // intentionally only prints the screen — without the header, the markdown path
    // is silent on the verdict even though the exit code is correct (PR #3620).
    val text = result.content
    val passed = parseVerifyPassedFromMarkdown(text)
    Console.info(if (passed) "### Passed" else "### Failed")
    formatBlazeResultAgent(result)
    return if (passed) TrailblazeExitCode.SUCCESS.code else TrailblazeExitCode.ASSERTION_FAILED.code
  }
}

/**
 * Parse pass/fail status from a markdown-formatted verify result.
 * Looks for "✅ PASSED" or "❌ FAILED" markers in the text.
 */
internal fun parseVerifyPassedFromMarkdown(text: String): Boolean {
  return "✅ PASSED" in text && "❌ FAILED" !in text
}

/**
 * Return true when an ask response's `screenSummary` would just restate the
 * `answer` field — the typical "what's on screen?" case where the LLM puts
 * identical prose in both. Comparison is whitespace-collapsed equality only:
 *
 * - Substring containment was considered and rejected — "Search" suppressing
 *   "Search results found" is the wrong call. Dedup should only fire when
 *   the two fields are *effectively the same string*, not when one is a
 *   prefix/suffix that omits potentially-important detail.
 * - Blank-answer short-circuit guards against `"".contains("")` returning
 *   true for any screen, which would silently suppress the whole screen
 *   block whenever the LLM returned an empty answer.
 */
internal fun screenSummaryDuplicatesAnswer(answer: String?, screenSummary: String?): Boolean {
  if (answer.isNullOrBlank() || screenSummary.isNullOrBlank()) return false
  val a = answer.trim().replace(Regex("\\s+"), " ")
  val s = screenSummary.trim().replace(Regex("\\s+"), " ")
  return a == s
}

/**
 * Format session result (sessionId + message).
 */
internal fun formatSessionResultAgent(result: CliMcpClient.ToolResult) {
  if (result.isError) {
    Console.error(result.content)
    return
  }
  try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.error(error)
      return
    }
    val message = json["message"]?.jsonPrimitive?.content
    val sessionId = json["sessionId"]?.jsonPrimitive?.content
    if (sessionId != null) Console.info("Session: $sessionId")
    if (message != null) Console.info(message)
  } catch (_: Exception) {
    Console.info(result.content)
  }
}

/**
 * Parse screen summary into structured sections (### Page, ### Screen).
 *
 * Input format from daemon:
 *   URL: https://...
 *   Title: ...
 *   Scroll: ...
 *   Focused: ...  (optional)
 *   [searchbox] Search Wikipedia | [button] Search | ...
 *
 * Output:
 *   ### Page
 *   - Page URL: https://...
 *   - Page title: ...
 *   ### Screen
 *   [searchbox] Search Wikipedia | [button] Search | ...
 */
internal fun formatScreenSummaryAgent(summary: String) {
  val lines = summary.lines()
  var url: String? = null
  var title: String? = null
  var focused: String? = null
  val screenElements = mutableListOf<String>()

  for (line in lines) {
    when {
      line.startsWith("URL: ") -> url = line.removePrefix("URL: ").trim()
      line.startsWith("Title: ") -> title = line.removePrefix("Title: ").trim()
      line.startsWith("Focused: ") -> focused = line.removePrefix("Focused: ").trim()
      line.startsWith("Scroll: ") -> {} // skip scroll info
      line.isNotBlank() -> screenElements.add(line)
    }
  }

  if (url != null || title != null) {
    Console.info("### Page")
    if (url != null) Console.info("- Page URL: $url")
    if (title != null) Console.info("- Page title: $title")
    if (focused != null) Console.info("- Focused: $focused")
  }

  if (screenElements.isNotEmpty()) {
    Console.info("### Screen")
    Console.info(screenElements.joinToString("\n"))
  }
}

/**
 * Extract a human-readable error message from a daemon response.
 *
 * Daemon errors are often JSON like `{"error": "message"}`. This helper unwraps the
 * JSON and returns just the message string, falling back to the raw content if it's
 * not parseable JSON.
 */
internal fun extractErrorMessage(content: String): String {
  return try {
    val json = Json.parseToJsonElement(content).jsonObject
    json["error"]?.jsonPrimitive?.content ?: content
  } catch (_: Exception) {
    content
  }
}

/**
 * Extract an application-level error from a JSON response, or null if none found.
 *
 * Unlike [extractErrorMessage] which always returns a string, this returns null when
 * there is no error — useful for `if (error != null)` patterns.
 */
internal fun extractJsonError(content: String): String? {
  return try {
    Json.parseToJsonElement(content).jsonObject["error"]?.jsonPrimitive?.content
  } catch (_: Exception) {
    null
  }
}

/**
 * Substring markers that classify a tool-result body as user-input misuse
 * (MISUSE = 3 per [TrailblazeExitCode]) rather than infrastructure failure
 * (INFRA_FAILED = 2). Single source of truth for both [blazeExitCode] and
 * [ToolCommand]'s rejection intercept — duplicating the strings risks a
 * silent two-layer drift if a new marker is added in one site but not the
 * other.
 *
 * - `"Unknown tool"` matches both the singular `Unknown tool: foo` (emitted
 *   by `AgentUiActionExecutor`) and the plural `Unknown tools: a, b, c`
 *   (emitted by `StepToolSet` when a batch contains multiple unknown names).
 * - `"not valid for the current device/target"` matches both `Tool not valid …`
 *   (singular) and `Tools not valid …` (plural) — `StepToolSet` pluralizes
 *   the noun based on count.
 */
internal val MISUSE_MARKERS = listOf("Unknown tool", "not valid for the current device/target")

/**
 * True when a daemon result is a *user-input misuse* — an **error** whose message carries a
 * [MISUSE_MARKERS] phrase (`Unknown tool`, `not valid for the current device/target`).
 *
 * The error gate matters now that a successful `trailblaze tool <read-tool>` returns the tool's
 * own payload: a marker phrase appearing inside a SUCCESS payload (e.g. shell output that happens
 * to mention "Unknown tool") must not be misreported as EXIT=3. Daemon rejections always render as
 * `**❌ Error** — …` (StepResult error) or a JSON `{"error": …}`, so we only treat the markers as
 * misuse when one of those error signals is present.
 */
internal fun isMisuseResult(content: String): Boolean {
  val isError = "❌ Error" in content || "❌ FAILED" in content || extractJsonError(content) != null
  return isError && MISUSE_MARKERS.any { it in content }
}

/**
 * Determine exit code from a `step` MCP tool result.
 *
 * Name kept as `blazeExitCode` for lineage — shared by every verb that
 * dispatches to the agent-loop tool. See [formatBlazeResultAgent] for the
 * full deferral note.
 */
internal fun blazeExitCode(result: CliMcpClient.ToolResult): Int {
  if (result.isError) return TrailblazeExitCode.INFRA_FAILED.code
  return try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (!error.isNullOrBlank()) TrailblazeExitCode.INFRA_FAILED.code else TrailblazeExitCode.SUCCESS.code
  } catch (_: Exception) {
    // Not JSON — check markdown for error markers. [isMisuseResult] gates the MISUSE
    // markers on an error status so a SUCCESS payload that merely contains a marker
    // phrase (a read/shell tool now returns its real output here) isn't misreported as
    // a typo. A generic error with no marker is INFRA; anything else is SUCCESS.
    val text = result.content
    if (isMisuseResult(text)) {
      TrailblazeExitCode.MISUSE.code
    } else if ("❌ Error" in text || "❌ FAILED" in text) {
      TrailblazeExitCode.INFRA_FAILED.code
    } else {
      TrailblazeExitCode.SUCCESS.code
    }
  }
}

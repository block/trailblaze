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

  val screenMarker = "**Screen:** "
  val screenIdx = text.indexOf(screenMarker)
  if (screenIdx >= 0) {
    val screenText = text.substring(screenIdx + screenMarker.length).trim()
    formatScreenSummaryAgent(screenText)
  } else if (screenshotPath == null) {
    Console.info(text)
  }

  if (screenshotPath != null) {
    Console.info("Screenshot: $screenshotPath")
  }
}

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
    if (screenSummary != null) {
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
    // Not JSON — parse markdown format from daemon (e.g., "**✅ PASSED** — reason")
    val text = result.content
    val passed = parseVerifyPassedFromMarkdown(text)
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
    // Not JSON — check markdown for error markers
    val text = result.content
    // The daemon's `StepResult.toMarkdown()` always prefixes these with
    // `**❌ Error**`, but `ToolCommand` intercepts the rejection path before
    // this fallback — checking the substring here is defense in depth for any
    // future caller of `blazeExitCode` (or a daemon-side format drift) so we
    // don't silently return SUCCESS on a typo. Order matters: check the
    // [MISUSE_MARKERS] BEFORE the generic error markers, otherwise the broad
    // "❌ Error" / "❌ FAILED" catch would mask the more-specific
    // input-mistake verdict.
    if (MISUSE_MARKERS.any { it in text }) {
      TrailblazeExitCode.MISUSE.code
    } else if ("❌ Error" in text || "❌ FAILED" in text) {
      TrailblazeExitCode.INFRA_FAILED.code
    } else {
      TrailblazeExitCode.SUCCESS.code
    }
  }
}

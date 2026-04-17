package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import xyz.block.trailblaze.util.Console

// ---------------------------------------------------------------------------
// Agent-optimized output formatters (used by ./blaze commands)
// ---------------------------------------------------------------------------

/**
 * Format blaze tool result with structured sections (### Page, ### Screen).
 * Agent-optimized: AI coding agents parse this output format.
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
 * Returns exit code: OK if passed, SOFTWARE if failed.
 */
internal fun formatVerifyResultAgent(result: CliMcpClient.ToolResult): Int {
  if (result.isError) {
    Console.info("### Error")
    Console.error(result.content)
    return CommandLine.ExitCode.SOFTWARE
  }
  try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (error != null) {
      Console.info("### Error")
      Console.error(error)
      return CommandLine.ExitCode.SOFTWARE
    }
    val passed = json["passed"]?.jsonPrimitive?.content?.toBoolean() ?: false
    val resultText = json["result"]?.jsonPrimitive?.content ?: ""
    if (passed) {
      Console.info("### Passed")
      Console.info(resultText)
      return CommandLine.ExitCode.OK
    } else {
      Console.info("### Failed")
      Console.info(resultText)
      return CommandLine.ExitCode.SOFTWARE
    }
  } catch (_: Exception) {
    // Not JSON — parse markdown format from daemon (e.g., "**✅ PASSED** — reason")
    val text = result.content
    val passed = parseVerifyPassedFromMarkdown(text)
    formatBlazeResultAgent(result)
    return if (passed) CommandLine.ExitCode.OK else CommandLine.ExitCode.SOFTWARE
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
 * Determine exit code from a blaze tool result.
 */
internal fun blazeExitCode(result: CliMcpClient.ToolResult): Int {
  if (result.isError) return CommandLine.ExitCode.SOFTWARE
  return try {
    val json = Json.parseToJsonElement(result.content).jsonObject
    val error = json["error"]?.jsonPrimitive?.content
    if (!error.isNullOrBlank()) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK
  } catch (_: Exception) {
    // Not JSON — check markdown for error markers
    val text = result.content
    if ("❌ Error" in text || "❌ FAILED" in text) {
      CommandLine.ExitCode.SOFTWARE
    } else {
      CommandLine.ExitCode.OK
    }
  }
}

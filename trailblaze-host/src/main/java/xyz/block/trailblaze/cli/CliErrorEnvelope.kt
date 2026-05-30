package xyz.block.trailblaze.cli

import xyz.block.trailblaze.util.Console

/**
 * Structured human-readable error envelope shared by every non-AI CLI command.
 *
 * Emits the standard three-line block to stderr:
 *
 * ```
 * ✗ <verb> failed[ on <target>]
 *   reason: <reason>
 *   hint: <hint>           (omitted when null)
 * ```
 *
 * Goals:
 * - Replace raw stack traces and bare `Console.error(e.toString())` patterns with a
 *   single shape every command produces.
 * - Keep the body terse — `reason` is one line, `hint` is one line of actionable
 *   recovery (`is the daemon running? try \`trailblaze app start\``,
 *   `device not found; check \`trailblaze device list\``).
 * - Pair with [TrailblazeExitCode] for the machine signal: the envelope tells the
 *   human what happened; the exit code tells the script what to do.
 */
internal fun reportCliError(
  verb: String,
  target: String? = null,
  reason: String,
  hint: String? = null,
) {
  val header = if (target.isNullOrBlank()) "✗ $verb failed" else "✗ $verb failed on $target"
  Console.error(header)
  Console.error("  reason: $reason")
  if (!hint.isNullOrBlank()) Console.error("  hint: $hint")
}

/**
 * Translate a thrown exception into a one-line `reason:` string for [reportCliError].
 *
 * Maps the common network/IO exception classes that previously leaked their
 * stack trace (notably [java.net.SocketTimeoutException],
 * [java.net.ConnectException], [java.io.IOException]) onto a phrasing a normal
 * user can act on, instead of `java.net.SocketTimeoutException: connect timed out`.
 * Falls back to `<simpleName>: <message>` for unfamiliar exceptions so the user
 * still sees *something* rather than an opaque "internal error."
 *
 * Any whitespace run inside the message is collapsed to a single space before
 * embedding — multi-line exceptions (YAML parse errors, indented Java stack
 * frames inside a wrapped cause) would otherwise break the envelope's
 * one-line-per-field contract and produce output a downstream `grep` couldn't
 * line-correlate.
 */
internal fun describeThrowableForUser(e: Throwable): String {
  val message = e.message?.sanitizeForOneLine()?.takeIf { it.isNotBlank() }
  return when (e) {
    is java.net.SocketTimeoutException ->
      "network request timed out${message?.let { " ($it)" } ?: ""}"
    is java.net.ConnectException ->
      "could not connect${message?.let { " ($it)" } ?: ""}"
    is java.io.IOException ->
      message ?: "I/O error (${e::class.simpleName})"
    else ->
      message ?: e::class.simpleName ?: "unknown error"
  }
}

private val WHITESPACE_RUN = Regex("\\s+")

private fun String.sanitizeForOneLine(): String = trim().replace(WHITESPACE_RUN, " ")

// DOT_MATCHES_ALL so a multi-line body (e.g. an HTML error page or indented
// JSON returned by the daemon) is captured in its entirety rather than only
// the first line — sanitizeForOneLine below collapses the whitespace.
private val HTTP_ERROR_REGEX = Regex("""HTTP (\d{3})(?::\s*(.*))?""", RegexOption.DOT_MATCHES_ALL)

/**
 * Translate a transport-level error payload (the `HTTP {code}: {body}` strings
 * `CliMcpClient.sendRequest` produces when the daemon returns 4xx/5xx) into a
 * human-readable reason suitable for the `reason:` line of [reportCliError].
 *
 * The motivating regression was iOS connects under contention surfacing
 * `Error connecting to device: HTTP 404` with no further detail — the FTUX
 * validator's exact complaint was "exit 2 but nothing actionable in the
 * output." This mapper gives the user something to try (`trailblaze device`,
 * `trailblaze app --stop && trailblaze app`) instead of an HTTP code.
 *
 * Inputs that don't look like a transport error pass through unchanged so
 * non-HTTP failures (which already have descriptive content) aren't
 * homogenized into a generic phrase. Body text is collapsed to a single line
 * via [sanitizeForOneLine] before being embedded so a multi-line HTML error
 * page (or indented JSON) can't break the envelope's one-line-per-field
 * invariant. Lives in this file so it sits next to [describeThrowableForUser]
 * and `reportCliError` — the two existing helpers that shape strings for the
 * structured CLI error envelope.
 */
internal fun decodeTransportError(content: String): String {
  val httpMatch = HTTP_ERROR_REGEX.find(content) ?: return content
  val code = httpMatch.groupValues[1].toInt()
  val body = httpMatch.groupValues[2].sanitizeForOneLine()
  val bodySuffix = if (body.isNotEmpty()) " (body: $body)" else ""
  return when (code) {
    // 404 on a device-connect path is ambiguous in the current daemon: it can
    // mean "MCP session has been evicted (stale session id)", "device list
    // changed between LIST and connect", or in some contention races the
    // daemon's claim path returns 404 where 409/423 would be semantically
    // correct. The hint covers all three without committing to a wrong cause.
    404 -> "Daemon returned 404 -- this is ambiguous in the current daemon: " +
      "the device list may have shifted under you, the MCP session may be stale, " +
      "or another caller is racing this connect. Try `trailblaze device` to " +
      "refresh the list, retry once, or `trailblaze app --stop && trailblaze app` " +
      "to recycle the daemon." +
      bodySuffix
    409, 423 -> "Daemon reports the device is held by another caller " +
      "(HTTP $code)$bodySuffix. Wait for the holder to finish or stop it via " +
      "`trailblaze app --stop`."
    in 500..599 -> "Daemon internal error (HTTP $code)$bodySuffix. Check the " +
      "daemon log at `~/.trailblaze/daemon.log`."
    else -> "Daemon returned HTTP $code$bodySuffix."
  }
}

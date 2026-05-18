package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.util.toMap
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Endpoint that serves the home page of the Trailblaze logs server.
 *
 * Renders a server-side list of available sessions with quick summary info
 * (status, title, when, duration). Each row links to `/report?session=<id>`,
 * which generates a single-session WASM bundle that auto-advances to the
 * session detail page — much smaller than the all-sessions report.
 */
object HomeEndpoint {

  private fun defaultHtml(logsRepo: LogsRepo): String {
    val sessions = logsRepo.getSessionIds()
      .mapNotNull { logsRepo.getSessionInfo(it) }
      .sortedByDescending { it.timestamp }

    val rows = if (sessions.isEmpty()) {
      """<tr><td colspan="4" class="empty">No sessions yet. Run a trail to populate this list.</td></tr>"""
    } else {
      sessions.joinToString("\n") { sessionRow(it) }
    }

    return """
    <!DOCTYPE html>
    <html>
      <head>
        <title>Trailblaze Server</title>
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 960px; margin: 40px auto; padding: 0 20px; color: #222; }
          h1 { font-size: 24px; margin-bottom: 4px; }
          .subtitle { color: #666; font-size: 14px; margin-top: 0; margin-bottom: 24px; }
          .actions { margin-bottom: 24px; font-size: 14px; }
          .actions a { color: #0066cc; text-decoration: none; margin-right: 16px; }
          .actions a:hover { text-decoration: underline; }
          table { width: 100%; border-collapse: collapse; font-size: 14px; }
          th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #eee; vertical-align: middle; }
          th { font-weight: 600; color: #666; background: #fafafa; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
          tr:hover td { background: #fafbff; }
          td.empty { color: #888; text-align: center; padding: 40px 0; }
          .status { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 12px; font-weight: 500; white-space: nowrap; }
          .status-pass { background: #e6f6ec; color: #1b6b35; }
          .status-fail { background: #fdecea; color: #a8221b; }
          .status-prog { background: #eef2ff; color: #2945a1; }
          .status-other { background: #f1f1f1; color: #555; }
          .title-link { color: #0066cc; text-decoration: none; font-weight: 500; }
          .title-link:hover { text-decoration: underline; }
          .meta { color: #888; font-size: 12px; }
          .nowrap { white-space: nowrap; }
        </style>
      </head>
      <body>
        <h1>Trailblaze Server</h1>
        <p class="subtitle">${sessions.size} session(s)</p>
        <div class="actions">
          <a href="/report">View all sessions in one report</a>
          <a href="/devices">Devices</a>
          <a href="/ping">Health check</a>
        </div>
        <table>
          <thead>
            <tr>
              <th>Status</th>
              <th>Session</th>
              <th class="nowrap">When</th>
              <th class="nowrap">Duration</th>
            </tr>
          </thead>
          <tbody>
            $rows
          </tbody>
        </table>
      </body>
    </html>
    """
  }

  private fun sessionRow(info: SessionInfo): String {
    val sessionId = info.sessionId.value
    val (statusLabel, statusClass) = statusLabelAndClass(info.latestStatus)
    val title = htmlEscape(info.displayName)
    val when_ = htmlEscape(formatTimestamp(info))
    val duration = formatDuration(info.durationMs)
    val href = "/report?session=${urlEncode(sessionId)}"
    val sessionIdEscaped = htmlEscape(sessionId)
    return """
            <tr>
              <td><span class="status $statusClass">$statusLabel</span></td>
              <td>
                <a class="title-link" href="$href">$title</a>
                <div class="meta">$sessionIdEscaped</div>
              </td>
              <td class="meta nowrap">$when_</td>
              <td class="meta nowrap">$duration</td>
            </tr>
    """.trimEnd()
  }

  private fun statusLabelAndClass(status: SessionStatus): Pair<String, String> = when (status) {
    is SessionStatus.Ended.Succeeded -> "Passed" to "status-pass"
    is SessionStatus.Ended.SucceededWithSelfHeal -> "Passed (self-heal)" to "status-pass"
    is SessionStatus.Ended.Failed -> "Failed" to "status-fail"
    is SessionStatus.Ended.FailedWithSelfHeal -> "Failed (self-heal)" to "status-fail"
    is SessionStatus.Ended.Cancelled -> "Cancelled" to "status-other"
    is SessionStatus.Ended.TimeoutReached -> "Timed out" to "status-fail"
    is SessionStatus.Ended.MaxCallsLimitReached -> "Max calls" to "status-fail"
    is SessionStatus.Started -> "Running" to "status-prog"
    is SessionStatus.Unknown -> "Unknown" to "status-other"
  }

  private fun formatTimestamp(info: SessionInfo): String {
    val local = info.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}-$month-$day $hour:$minute"
  }

  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
  }

  private fun htmlEscape(input: String): String = buildString(input.length) {
    for (ch in input) when (ch) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> append("&quot;")
      '\'' -> append("&#39;")
      else -> append(ch)
    }
  }

  private fun urlEncode(input: String): String =
    java.net.URLEncoder.encode(input, Charsets.UTF_8)

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  ) = with(routing) {
    get("/") {
      val callbackHandlerResult = homeCallbackHandler?.invoke(call.request.queryParameters.toMap())
      val defaultPage = defaultHtml(logsRepo)
      val htmlResult = callbackHandlerResult?.getOrNull() ?: defaultPage
      call.respondText(text = htmlResult, contentType = ContentType.Text.Html)
    }
  }
}

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

  private fun defaultHtml(logsRepo: LogsRepo, trailRunnerPath: String?): String {
    val sessions = logsRepo.getSessionIds()
      .mapNotNull { logsRepo.getSessionInfo(it) }
      .sortedByDescending { it.timestamp }

    val rows = if (sessions.isEmpty()) {
      """<tr><td colspan="4" class="empty">No sessions yet. Run a trail to populate this list.</td></tr>"""
    } else {
      sessions.joinToString("\n") { sessionRow(it) }
    }
    val hasTrailRunner = trailRunnerPath != null
    val heroClass = if (hasTrailRunner) "hero" else "hero report-only"
    val heroDescription = if (hasTrailRunner) {
      "Create, record, and run natural-language UI tests in Trail Runner, or inspect the sessions produced by this daemon."
    } else {
      "Review reports, storyboards, connected devices, and the sessions produced by this daemon."
    }
    val trailRunnerButton = trailRunnerPath?.let { path ->
      """<a class="button primary" href="${htmlEscape(path)}">Open Trail Runner <span aria-hidden="true">&rarr;</span></a>"""
    }.orEmpty()
    val reportButtonClass = if (hasTrailRunner) "button" else "button primary"
    val trailRunnerWorkflow = if (hasTrailRunner) {
      """
              <div class="workflow" aria-hidden="true">
                <div class="flow-step">
                  <div class="flow-icon">1</div>
                  <div class="flow-copy"><strong>Describe the flow</strong><span>Turn an objective into clear steps</span></div>
                </div>
                <div class="flow-step">
                  <div class="flow-icon">2</div>
                  <div class="flow-copy"><strong>Record on a device</strong><span>Capture the tools that perform each step</span></div>
                </div>
                <div class="flow-step">
                  <div class="flow-icon">3</div>
                  <div class="flow-copy"><strong>Replay deterministically</strong><span>Run the saved trail whenever you need it</span></div>
                </div>
              </div>
      """.trimIndent()
    } else {
      ""
    }

    return """
    <!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <meta name="color-scheme" content="dark light" />
        <title>Trailblaze Daemon</title>
        <script>
          (function () {
            try {
              var mode = JSON.parse(localStorage.getItem('tb-theme')) || 'system';
              var prefersLight = window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches;
              var dark = mode === 'dark' || (mode === 'system' && !prefersLight);
              document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
            } catch (e) {
              document.documentElement.setAttribute('data-theme', 'dark');
            }
          })();
        </script>
        <style>
          :root {
            --text-standard: #fff;
            --text-subtle: #878787;
            --text-subtle-variant: #a2a2a2;
            --bg-window: #0f0f0f;
            --bg-sheet: #1a1a1a;
            --bg-subtle: #0f0f0f;
            --bg-standard: #181818;
            --bg-prominent: #232323;
            --bg-extra-prominent: #333;
            --tb-hairline: rgba(255, 255, 255, .07);
            --tb-hairline-strong: rgba(255, 255, 255, .12);
            --tb-primary-green: #00e013;
            --tb-running: #5e9bff;
            --tb-ai: #b58cff;
            --tb-fail: #f84752;
            --tb-warning: #ff9d2e;
            --font-display: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
            --font-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
          }

          html[data-theme="light"] {
            --text-standard: #14161a;
            --text-subtle: #525964;
            --text-subtle-variant: #434a55;
            --bg-window: #edeff3;
            --bg-sheet: #fff;
            --bg-subtle: #f1f4f8;
            --bg-standard: #fff;
            --bg-prominent: #e7eaf0;
            --bg-extra-prominent: #dce1e9;
            --tb-hairline: rgba(15, 23, 42, .10);
            --tb-hairline-strong: rgba(15, 23, 42, .16);
          }

          * { box-sizing: border-box; }
          html { min-height: 100%; background: var(--bg-window); }
          body {
            min-height: 100vh;
            margin: 0;
            background: var(--bg-window);
            color: var(--text-standard);
            font-family: var(--font-display);
            -webkit-font-smoothing: antialiased;
          }
          a { color: inherit; }
          a:focus-visible {
            outline: 2px solid var(--tb-primary-green);
            outline-offset: 3px;
            border-radius: 8px;
          }
          .page { width: min(1120px, calc(100% - 40px)); margin: 0 auto; padding: 22px 0 56px; }
          .topbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            min-height: 42px;
            margin-bottom: 18px;
            padding: 0 4px;
          }
          .brand { display: flex; align-items: center; gap: 11px; font-size: 15px; font-weight: 700; }
          .brand-mark {
            position: relative;
            width: 24px;
            height: 24px;
            border: 1px solid var(--tb-hairline-strong);
            border-radius: 8px;
            background: var(--bg-prominent);
          }
          .brand-mark::before,
          .brand-mark::after {
            position: absolute;
            content: "";
            border-radius: 99px;
            background: var(--tb-primary-green);
          }
          .brand-mark::before { width: 4px; height: 4px; top: 6px; left: 6px; box-shadow: 8px 8px 0 var(--tb-primary-green); }
          .brand-mark::after { width: 11px; height: 2px; top: 11px; left: 7px; transform: rotate(45deg); }
          .daemon-status {
            display: inline-flex;
            align-items: center;
            gap: 7px;
            color: var(--text-subtle-variant);
            font-size: 12px;
            font-weight: 600;
            text-decoration: none;
          }
          .daemon-status::before {
            width: 7px;
            height: 7px;
            content: "";
            border-radius: 99px;
            background: var(--tb-primary-green);
            box-shadow: 0 0 0 4px rgba(0, 224, 19, .10);
          }
          .daemon-status:hover { color: var(--text-standard); }
          .sheet {
            overflow: hidden;
            border: 1px solid var(--tb-hairline);
            border-radius: 18px;
            background: var(--bg-sheet);
            box-shadow: 0 18px 50px rgba(0, 0, 0, .16);
          }
          .hero {
            display: grid;
            grid-template-columns: minmax(0, 1.35fr) minmax(300px, .65fr);
            gap: 44px;
            align-items: center;
            min-height: 350px;
            padding: 50px 54px;
            border-bottom: 1px solid var(--tb-hairline);
          }
          .hero.report-only { grid-template-columns: minmax(0, 720px); }
          .eyebrow {
            margin: 0 0 14px;
            color: var(--text-subtle);
            font-size: 10.5px;
            font-weight: 700;
            letter-spacing: .1em;
            text-transform: uppercase;
          }
          h1 {
            max-width: 650px;
            margin: 0;
            font-size: clamp(36px, 5vw, 58px);
            font-weight: 500;
            letter-spacing: -.04em;
            line-height: .98;
          }
          h1 span { color: var(--tb-primary-green); }
          .lede {
            max-width: 600px;
            margin: 20px 0 28px;
            color: var(--text-subtle-variant);
            font-size: 16px;
            line-height: 1.55;
          }
          .hero-actions { display: flex; flex-wrap: wrap; gap: 10px; }
          .button {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 9px;
            min-height: 44px;
            padding: 0 19px;
            border: 1px solid var(--tb-hairline-strong);
            border-radius: 999px;
            background: var(--bg-prominent);
            color: var(--text-standard);
            font-size: 13px;
            font-weight: 700;
            text-decoration: none;
            transition: background .15s, transform .15s;
          }
          .button:hover { background: var(--bg-extra-prominent); transform: translateY(-1px); }
          .button.primary { border-color: transparent; background: var(--tb-primary-green); color: #00220a; }
          .button.primary:hover { background: #00c711; }
          .workflow {
            position: relative;
            display: flex;
            flex-direction: column;
            gap: 10px;
            padding: 22px;
            border: 1px solid var(--tb-hairline);
            border-radius: 16px;
            background: var(--bg-subtle);
          }
          .workflow::before {
            position: absolute;
            top: 46px;
            bottom: 46px;
            left: 39px;
            width: 1px;
            content: "";
            background: var(--tb-hairline-strong);
          }
          .flow-step { position: relative; display: flex; align-items: center; gap: 12px; min-height: 48px; }
          .flow-icon {
            z-index: 1;
            display: grid;
            width: 34px;
            height: 34px;
            flex: 0 0 34px;
            place-items: center;
            border: 1px solid var(--tb-hairline-strong);
            border-radius: 10px;
            background: var(--bg-standard);
            font-size: 14px;
            font-weight: 700;
          }
          .flow-step:nth-child(1) .flow-icon { color: var(--tb-ai); }
          .flow-step:nth-child(2) .flow-icon { color: var(--tb-running); }
          .flow-step:nth-child(3) .flow-icon { color: var(--tb-primary-green); }
          .flow-copy strong { display: block; font-size: 13px; }
          .flow-copy span { display: block; margin-top: 2px; color: var(--text-subtle); font-size: 11.5px; }
          .utilities {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 12px;
            padding: 18px;
            border-bottom: 1px solid var(--tb-hairline);
          }
          .utility {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 16px;
            min-height: 76px;
            padding: 15px 17px;
            border: 1px solid var(--tb-hairline);
            border-radius: 12px;
            background: var(--bg-subtle);
            text-decoration: none;
            transition: border-color .15s, background .15s;
          }
          .utility:hover { border-color: var(--tb-hairline-strong); background: var(--bg-standard); }
          .utility-copy strong { display: block; color: var(--text-standard); font-size: 13px; }
          .utility-copy span { display: block; margin-top: 4px; color: var(--text-subtle); font-size: 11.5px; }
          .utility-arrow { color: var(--text-subtle); font-size: 17px; }
          .sessions { padding: 30px 36px 36px; }
          .section-head { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 15px; }
          h2 { margin: 0; font-size: 20px; font-weight: 600; letter-spacing: -.02em; }
          .session-count { color: var(--text-subtle); font-size: 12px; }
          .table-shell { overflow: hidden; border: 1px solid var(--tb-hairline); border-radius: 12px; }
          table { width: 100%; border-collapse: collapse; font-size: 13px; }
          th,
          td { padding: 13px 15px; border-bottom: 1px solid var(--tb-hairline); text-align: left; vertical-align: middle; }
          th {
            background: var(--bg-subtle);
            color: var(--text-subtle);
            font-size: 10px;
            font-weight: 700;
            letter-spacing: .08em;
            text-transform: uppercase;
          }
          tbody tr:last-child td { border-bottom: 0; }
          tbody tr { transition: background .12s; }
          tbody tr:hover { background: var(--bg-subtle); }
          td.empty { padding: 48px 20px; color: var(--text-subtle); text-align: center; }
          .status {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 4px 9px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: 700;
            white-space: nowrap;
          }
          .status::before { width: 6px; height: 6px; content: ""; border-radius: 99px; background: currentColor; }
          .status-pass { background: rgba(0, 224, 19, .10); color: var(--tb-primary-green); }
          .status-fail { background: rgba(248, 71, 82, .12); color: var(--tb-fail); }
          .status-prog { background: rgba(94, 155, 255, .13); color: var(--tb-running); }
          .status-other { background: var(--bg-prominent); color: var(--text-subtle-variant); }
          html[data-theme="light"] .status-pass { color: #08660f; }
          html[data-theme="light"] .status-fail { color: #c81e2b; }
          html[data-theme="light"] .status-prog { color: #2358b8; }
          .title-link { color: var(--text-standard); font-size: 13.5px; font-weight: 600; text-decoration: none; }
          .title-link:hover { text-decoration: underline; text-underline-offset: 3px; }
          .meta { color: var(--text-subtle); font-size: 11.5px; }
          .session-id { margin-top: 3px; font-family: var(--font-mono); font-size: 10.5px; }
          .nowrap { white-space: nowrap; }
          .extras { display: flex; gap: 12px; margin-top: 7px; font-size: 11.5px; }
          .extras a { color: var(--tb-running); font-weight: 600; text-decoration: none; }
          .extras a:hover { text-decoration: underline; text-underline-offset: 3px; }

          @media (max-width: 800px) {
            .hero { grid-template-columns: 1fr; min-height: 0; padding: 42px 34px; }
            .workflow { display: none; }
            .utilities { grid-template-columns: 1fr; }
          }
          @media (max-width: 620px) {
            .page { width: min(calc(100% - 20px), 1120px); padding-top: 10px; }
            .topbar { margin-bottom: 10px; }
            .sheet { border-radius: 14px; }
            .hero { padding: 34px 22px; }
            h1 { font-size: 38px; }
            .button { width: 100%; }
            .sessions { padding: 26px 18px; }
            .table-shell { overflow-x: auto; }
            table { min-width: 650px; }
          }
          @media (prefers-reduced-motion: reduce) {
            *, *::before, *::after { scroll-behavior: auto !important; transition: none !important; }
          }
        </style>
      </head>
      <body>
        <main class="page">
          <header class="topbar">
            <div class="brand"><span class="brand-mark" aria-hidden="true"></span>Trailblaze</div>
            <a class="daemon-status" href="/ping">Daemon running</a>
          </header>
          <div class="sheet">
            <section class="$heroClass">
              <div>
                <p class="eyebrow">Trailblaze daemon</p>
                <h1>Your testing workspace is <span>ready.</span></h1>
                <p class="lede">$heroDescription</p>
                <div class="hero-actions">
                  $trailRunnerButton
                  <a class="$reportButtonClass" href="/report">View all sessions</a>
                </div>
              </div>
              $trailRunnerWorkflow
            </section>

            <nav class="utilities" aria-label="Daemon utilities">
              <a class="utility" href="/report">
                <span class="utility-copy"><strong>All-session report</strong><span>Explore every captured run</span></span>
                <span class="utility-arrow" aria-hidden="true">&rarr;</span>
              </a>
              <a class="utility" href="/devices">
                <span class="utility-copy"><strong>Connected devices</strong><span>Inspect the available test surfaces</span></span>
                <span class="utility-arrow" aria-hidden="true">&rarr;</span>
              </a>
              <a class="utility" href="/ping">
                <span class="utility-copy"><strong>Health check</strong><span>Verify that the daemon is responding</span></span>
                <span class="utility-arrow" aria-hidden="true">&rarr;</span>
              </a>
            </nav>

            <section class="sessions" aria-labelledby="sessions-title">
              <div class="section-head">
                <div>
                  <p class="eyebrow">Run history</p>
                  <h2 id="sessions-title">Recent sessions</h2>
                </div>
                <span class="session-count">${sessions.size} total</span>
              </div>
              <div class="table-shell">
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
              </div>
            </section>
          </div>
        </main>
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
    // The storyboard HTML is generated on demand by StoryboardEndpoint, so this link is
    // always live — no dependency on a pre-generated `.storyboard.html` in the logs dir.
    val storyboardHref = "/storyboard?session=${urlEncode(sessionId)}"
    val sessionIdEscaped = htmlEscape(sessionId)
    return """
            <tr>
              <td><span class="status $statusClass">$statusLabel</span></td>
              <td>
                <a class="title-link" href="$href">$title</a>
                <div class="meta session-id">$sessionIdEscaped</div>
                <div class="extras">
                  <a href="$href">Report</a>
                  <a href="$storyboardHref">Storyboard</a>
                </div>
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
    trailRunnerPath: String? = null,
  ) = with(routing) {
    get("/") {
      val callbackHandlerResult = homeCallbackHandler?.invoke(call.request.queryParameters.toMap())
      val defaultPage = defaultHtml(logsRepo, trailRunnerPath)
      val htmlResult = callbackHandlerResult?.getOrNull() ?: defaultPage
      call.respondText(text = htmlResult, contentType = ContentType.Text.Html)
    }
  }
}

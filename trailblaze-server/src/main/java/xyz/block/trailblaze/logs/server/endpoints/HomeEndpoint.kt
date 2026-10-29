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
 * A status console for the daemon: run outcome counts, the connected devices, and a filterable
 * list of sessions with quick summary info (status, title, when, duration). Each row links to
 * `/report?session=<id>`, which generates that one session's interactive report and opens
 * straight on its detail view. That page is much smaller than the all-sessions report.
 */
object HomeEndpoint {

  private fun defaultHtml(logsRepo: LogsRepo, trailRunnerPath: String?): String {
    val sessions = logsRepo.getSessionIds()
      .mapNotNull { logsRepo.getSessionInfo(it) }
      .sortedByDescending { it.timestamp }
    val kindCounts = sessions.groupingBy { statusLabelAndKind(it.latestStatus).second }.eachCount()
    fun count(kind: String) = kindCounts[kind] ?: 0

    val rows = if (sessions.isEmpty()) {
      """<tr class="empty-row"><td colspan="5" class="empty">No sessions yet. Run a trail to populate this list.</td></tr>"""
    } else {
      sessions.joinToString("\n") { sessionRow(it) } +
        """<tr class="empty-row filtered-empty" hidden><td colspan="5" class="empty">No sessions match this filter.</td></tr>"""
    }
    // `/report` covers the most recent DEFAULT_SESSION_LIMIT sessions; say so rather than
    // implying it covers everything, and offer the explicit opt-in when it doesn't.
    val cappedReport = sessions.size > GenerateReportEndpoint.DEFAULT_SESSION_LIMIT
    val reportTitle = if (cappedReport) {
      "The ${GenerateReportEndpoint.DEFAULT_SESSION_LIMIT} most recent of ${sessions.size} runs, in one shareable HTML page"
    } else {
      "Every captured run in one shareable HTML page"
    }
    val allSessionsLink = if (cappedReport) {
      """<a class="text-link" href="${GenerateReportEndpoint.ALL_RUNS_REPORT_URL}" title="Every run in one page, slower and much larger">Report of all ${sessions.size}</a>"""
    } else {
      ""
    }
    val trailRunnerButton = trailRunnerPath?.let { path ->
      """<a class="button primary" href="${htmlEscape(path)}">Open Trailblaze App <span aria-hidden="true">&rarr;</span></a>"""
    }.orEmpty()
    val reportButtonClass = if (trailRunnerPath != null) "button" else "button primary"
    val lastRun = sessions.firstOrNull()?.let { timeElement(it) } ?: "&mdash;"
    val filters = listOf(
      Triple("all", "All", sessions.size),
      Triple("prog", "Running", count("prog")),
      Triple("fail", "Failed", count("fail")),
      Triple("pass", "Passed", count("pass")),
      Triple("other", "Other", count("other")),
    ).filter { (key, _, n) -> key == "all" || n > 0 }
      .joinToString("") { (key, label, n) ->
        val pressed = if (key == "all") "true" else "false"
        """<button type="button" class="filter" data-filter="$key" aria-pressed="$pressed">$label <span class="filter-count">$n</span></button>"""
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
            color-scheme: light;
            --radius: 10px;
            --background: oklch(0.985 0.002 247.84);
            --foreground: oklch(0.145 0.014 258.34);
            --card: oklch(1 0 0);
            --primary: oklch(0.205 0.015 258.34);
            --primary-foreground: oklch(0.985 0.002 247.84);
            --muted: oklch(0.96 0.004 264.54);
            --muted-foreground: oklch(0.48 0.021 264.36);
            --accent: oklch(0.94 0.006 264.53);
            --destructive: oklch(0.577 0.245 27.325);
            --border: oklch(0.90 0.007 264.53);
            --input: oklch(0.90 0.007 264.53);
            --ring: oklch(0.62 0.15 151.71);
            --green: oklch(0.53 0.16 149.21);
            --blue: oklch(0.55 0.17 258.34);
            --shadow-xs: 0 1px 2px oklch(0 0 0 / 5%);
            --shadow-sm: 0 1px 3px oklch(0 0 0 / 8%);
            --font-sans: "Inter Variable", Inter, ui-sans-serif, system-ui, sans-serif;
            --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
          }

          :root[data-theme="dark"] {
            color-scheme: dark;
            --background: oklch(0.135 0.012 258.34);
            --foreground: oklch(0.97 0.003 264.54);
            --card: oklch(0.175 0.013 258.34);
            --primary: oklch(0.92 0.006 264.53);
            --primary-foreground: oklch(0.205 0.015 258.34);
            --muted: oklch(0.235 0.015 258.34);
            --muted-foreground: oklch(0.68 0.015 264.37);
            --accent: oklch(0.265 0.017 258.34);
            --destructive: oklch(0.704 0.191 22.216);
            --border: oklch(1 0 0 / 12%);
            --input: oklch(1 0 0 / 15%);
            --ring: oklch(0.72 0.15 150.10);
            --green: oklch(0.72 0.15 150.10);
            --blue: oklch(0.70 0.14 258.34);
          }

          * { box-sizing: border-box; }
          html { min-height: 100%; background: var(--background); scrollbar-gutter: stable; }
          body {
            min-height: 100vh;
            margin: 0;
            background: var(--background);
            color: var(--foreground);
            font-family: var(--font-sans);
            font-size: 14px;
            line-height: 1.45;
            -webkit-font-smoothing: antialiased;
          }
          a { color: inherit; }
          a:focus-visible, button:focus-visible {
            outline: 2px solid color-mix(in oklab, var(--ring) 60%, transparent);
            outline-offset: 2px;
          }
          [hidden] { display: none !important; }
          .page { width: min(1120px, calc(100% - 40px)); margin: 0 auto; padding: 20px 0 56px; }

          .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 40px; margin-bottom: 20px; }
          .topbar-left { display: flex; align-items: center; gap: 16px; min-width: 0; }
          .brand { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 700; }
          .brand-mark {
            position: relative;
            width: 24px;
            height: 24px;
            flex: 0 0 24px;
            border-radius: 6px;
            background: var(--primary);
          }
          .brand-mark::before,
          .brand-mark::after {
            position: absolute;
            content: "";
            border-radius: 99px;
            background: var(--primary-foreground);
          }
          .brand-mark::before { width: 4px; height: 4px; top: 6px; left: 6px; box-shadow: 8px 8px 0 var(--primary-foreground); }
          .brand-mark::after { width: 11px; height: 2px; top: 11px; left: 6.5px; transform: rotate(45deg); }
          .daemon-status {
            display: inline-flex;
            align-items: center;
            gap: 7px;
            padding-left: 16px;
            border-left: 1px solid var(--input);
            color: var(--muted-foreground);
            font-size: 12px;
            font-weight: 600;
            text-decoration: none;
            white-space: nowrap;
          }
          .daemon-status::before {
            width: 7px;
            height: 7px;
            content: "";
            border-radius: 99px;
            background: var(--green);
            box-shadow: 0 0 0 3px color-mix(in oklab, var(--green) 18%, transparent);
          }
          .daemon-status:hover { color: var(--foreground); }
          .actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
          .button {
            display: inline-flex;
            align-items: center;
            gap: 7px;
            height: 32px;
            padding: 0 13px;
            border: 1px solid var(--input);
            border-radius: calc(var(--radius) - 2px);
            background: var(--background);
            box-shadow: var(--shadow-xs);
            color: var(--foreground);
            font-size: 13px;
            font-weight: 500;
            text-decoration: none;
            white-space: nowrap;
            transition: background .12s, border-color .12s;
          }
          .button:hover { background: var(--accent); }
          .button.primary { border-color: transparent; background: var(--primary); color: var(--primary-foreground); }
          .button.primary:hover { background: color-mix(in oklab, var(--primary) 90%, transparent); }

          .sheet {
            overflow: hidden;
            border: 1px solid var(--border);
            border-radius: calc(var(--radius) + 4px);
            background: var(--card);
            box-shadow: var(--shadow-sm);
          }
          .sheet + .sheet { margin-top: 14px; }

          .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
          .stat { padding: 16px 20px 18px; }
          .stat + .stat { border-left: 1px solid var(--border); }
          .stat-label { color: var(--muted-foreground); font-size: 12px; font-weight: 600; }
          .stat-value {
            margin-top: 6px;
            font-size: 26px;
            font-weight: 600;
            letter-spacing: -.02em;
            font-variant-numeric: tabular-nums;
            line-height: 1.1;
          }
          .stat-value.is-zero { color: var(--muted-foreground); }
          .stat-value.running:not(.is-zero) { color: var(--blue); }
          .stat-value.failed:not(.is-zero) { color: var(--destructive); }
          .stat-value.passed:not(.is-zero) { color: var(--green); }
          .stat-value.when { font-size: 20px; padding-top: 4px; }

          .devices { display: flex; align-items: center; gap: 14px; padding: 12px 20px; border-top: 1px solid var(--border); }
          .devices-label { flex: 0 0 auto; color: var(--muted-foreground); font-size: 12px; font-weight: 600; }
          .device-list { display: flex; flex: 1 1 auto; flex-wrap: wrap; gap: 6px; min-width: 0; }
          .device {
            display: inline-flex;
            align-items: center;
            gap: 7px;
            max-width: 320px;
            height: 26px;
            padding: 0 9px 0 4px;
            border: 1px solid var(--border);
            border-radius: 7px;
            background: var(--muted);
            font-size: 12px;
            font-weight: 500;
          }
          .device-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
          .platform {
            flex: 0 0 auto;
            padding: 2px 5px;
            border-radius: 4px;
            background: var(--accent);
            color: var(--muted-foreground);
            font-size: 10px;
            font-weight: 700;
          }
          .devices-none { color: var(--muted-foreground); font-size: 12px; }
          .text-link { flex: 0 0 auto; color: var(--foreground); font-size: 13px; font-weight: 500; text-decoration: none; white-space: nowrap; }
          .text-link:hover { text-decoration: underline; text-underline-offset: 3px; }

          .section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 20px; border-bottom: 1px solid var(--border); }
          .section-head-left { display: flex; align-items: center; gap: 16px; min-width: 0; flex-wrap: wrap; }
          h2 { margin: 0; font-size: 14px; font-weight: 650; }
          .filters { display: flex; flex-wrap: wrap; gap: 4px; }
          .filter {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            height: 26px;
            padding: 0 9px;
            border: 1px solid transparent;
            border-radius: 7px;
            background: transparent;
            color: var(--muted-foreground);
            font: inherit;
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
          }
          .filter:hover { background: var(--muted); color: var(--foreground); }
          .filter[aria-pressed="true"] { border-color: var(--input); background: var(--accent); color: var(--foreground); }
          .filter-count { color: var(--muted-foreground); font-variant-numeric: tabular-nums; }

          table { width: 100%; border-collapse: collapse; table-layout: fixed; }
          col.c-status { width: 132px; }
          col.c-when { width: 112px; }
          col.c-duration { width: 84px; }
          col.c-actions { width: 156px; }
          th, td { padding: 10px 20px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: middle; }
          td + td, th + th { padding-left: 0; }
          th {
            padding-top: 9px;
            padding-bottom: 9px;
            background: var(--muted);
            color: var(--muted-foreground);
            font-size: 11px;
            font-weight: 600;
          }
          tbody td { height: 56px; }
          tbody tr:last-child td { border-bottom: 0; }
          tbody tr:not(.empty-row):hover { background: var(--muted); }
          td.empty { padding: 44px 20px; color: var(--muted-foreground); text-align: center; }
          table[data-filter="prog"] tr[data-kind]:not([data-kind="prog"]),
          table[data-filter="fail"] tr[data-kind]:not([data-kind="fail"]),
          table[data-filter="pass"] tr[data-kind]:not([data-kind="pass"]),
          table[data-filter="other"] tr[data-kind]:not([data-kind="other"]) { display: none; }

          .status { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; font-weight: 600; white-space: nowrap; }
          .status::before { width: 7px; height: 7px; flex: 0 0 7px; content: ""; border-radius: 99px; background: currentColor; }
          .status-pass { color: var(--green); }
          .status-fail { color: var(--destructive); }
          .status-prog { color: var(--blue); }
          .status-prog::before { animation: pulse 1.6s ease-in-out infinite; }
          .status-other { color: var(--muted-foreground); }
          @keyframes pulse { 50% { opacity: .35; } }

          .title-link {
            display: block;
            overflow: hidden;
            color: var(--foreground);
            font-size: 13px;
            font-weight: 600;
            text-decoration: none;
            text-overflow: ellipsis;
            white-space: nowrap;
          }
          .title-link.is-id { font-family: var(--font-mono); font-size: 12px; font-weight: 500; }
          .title-link:hover { text-decoration: underline; text-underline-offset: 3px; }
          .session-id {
            overflow: hidden;
            margin-top: 2px;
            color: var(--muted-foreground);
            font-family: var(--font-mono);
            font-size: 11px;
            text-overflow: ellipsis;
            white-space: nowrap;
          }
          .meta { color: var(--muted-foreground); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
          .row-actions { display: flex; justify-content: flex-end; gap: 4px; }
          .row-actions a {
            padding: 4px 8px;
            border-radius: 6px;
            color: var(--muted-foreground);
            font-size: 12px;
            font-weight: 600;
            text-decoration: none;
          }
          tbody tr:hover .row-actions a { color: var(--muted-foreground); }
          .row-actions a:hover { background: var(--accent); color: var(--foreground); }
          th.right { text-align: right; padding-right: 28px; }

          @media (max-width: 860px) {
            .topbar { flex-wrap: wrap; }
            .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
            .stat:nth-child(3) { border-left: 0; }
            .stat:nth-child(n + 3) { border-top: 1px solid var(--border); }
            .table-scroll { overflow-x: auto; }
            table { min-width: 720px; }
          }
          @media (max-width: 620px) {
            .page { width: calc(100% - 20px); padding-top: 12px; }
            .actions { justify-content: flex-start; }
            .devices { align-items: flex-start; flex-direction: column; gap: 8px; }
          }
          @media (prefers-reduced-motion: reduce) {
            *, *::before, *::after { animation: none !important; transition: none !important; }
          }
        </style>
      </head>
      <body>
        <main class="page">
          <header class="topbar">
            <div class="topbar-left">
              <div class="brand"><span class="brand-mark" aria-hidden="true"></span>Trailblaze</div>
              <a class="daemon-status" href="/ping" title="Health check">Daemon running</a>
            </div>
            <nav class="actions" aria-label="Daemon utilities">
              <a class="button" href="/devices">Devices</a>
              <a class="$reportButtonClass" href="${GenerateReportEndpoint.ALL_RUNS_REPORT_PATH}" title="$reportTitle">Interactive report</a>
              $trailRunnerButton
            </nav>
          </header>

          <section class="sheet" aria-label="Daemon status">
            <div class="stats">
              ${statCell("Running", count("prog"), "running")}
              ${statCell("Failed", count("fail"), "failed")}
              ${statCell("Passed", count("pass"), "passed")}
              <div class="stat"><div class="stat-label">Last run</div><div class="stat-value when">$lastRun</div></div>
            </div>
            <div class="devices" id="devices" hidden>
              <span class="devices-label" id="devices-label">Devices</span>
              <div class="device-list" id="device-list"></div>
              <a class="text-link" href="/devices">Control a device <span aria-hidden="true">&rarr;</span></a>
            </div>
          </section>

          <section class="sheet" aria-labelledby="sessions-title">
            <div class="section-head">
              <div class="section-head-left">
                <h2 id="sessions-title">Sessions</h2>
                <div class="filters" role="group" aria-label="Filter sessions by status">$filters</div>
              </div>
              $allSessionsLink
            </div>
            <div class="table-scroll">
              <table id="sessions" data-filter="all">
                <colgroup><col class="c-status" /><col /><col class="c-when" /><col class="c-duration" /><col class="c-actions" /></colgroup>
                <thead>
                  <tr>
                    <th>Status</th>
                    <th>Session</th>
                    <th>Started</th>
                    <th>Duration</th>
                    <th class="right">Open</th>
                  </tr>
                </thead>
                <tbody>
                  $rows
                </tbody>
              </table>
            </div>
          </section>
        </main>
        <script>
          (function () {
            var table = document.getElementById('sessions');
            var buttons = document.querySelectorAll('.filter');
            var filteredEmpty = table.querySelector('.filtered-empty');
            buttons.forEach(function (button) {
              button.addEventListener('click', function () {
                var key = button.getAttribute('data-filter');
                table.setAttribute('data-filter', key);
                buttons.forEach(function (b) { b.setAttribute('aria-pressed', String(b === button)); });
                if (filteredEmpty) {
                  var any = key === 'all' || table.querySelector('tr[data-kind="' + key + '"]');
                  filteredEmpty.hidden = !!any;
                }
              });
            });

            // The console is often left open in a tab, so relative times are re-derived every minute.
            function relabelTimes() {
              var now = Date.now();
              document.querySelectorAll('time[datetime]').forEach(function (el) {
                var then = Date.parse(el.getAttribute('datetime'));
                if (isNaN(then)) return;
                var minutes = Math.round((now - then) / 60000);
                var text;
                if (minutes < 1) text = 'just now';
                else if (minutes < 60) text = minutes + 'm ago';
                else if (minutes < 60 * 24) text = Math.round(minutes / 60) + 'h ago';
                else if (minutes < 60 * 24 * 7) text = Math.round(minutes / 1440) + 'd ago';
                else text = new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
                if (!el.title) el.title = el.textContent;
                el.textContent = text;
              });
            }
            relabelTimes();
            setInterval(relabelTimes, 60000);

            var platformLabels = { IOS: 'iOS', ANDROID: 'Android', WEB: 'Web' };
            fetch('/rpc/GetConnectedDevicesRequest', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: '{}',
            })
              .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
              .then(function (json) {
                var devices = (json && json.devices) || [];
                var list = document.getElementById('device-list');
                document.getElementById('devices-label').textContent =
                  devices.length === 1 ? '1 device' : devices.length + ' devices';
                if (devices.length === 0) {
                  var none = document.createElement('span');
                  none.className = 'devices-none';
                  none.textContent = 'None connected';
                  list.appendChild(none);
                }
                devices.forEach(function (device) {
                  var chip = document.createElement('span');
                  chip.className = 'device';
                  chip.title = device.instanceId || '';
                  var platform = document.createElement('span');
                  platform.className = 'platform';
                  platform.textContent = platformLabels[device.platform] || device.platform || '?';
                  var name = document.createElement('span');
                  name.className = 'device-name';
                  name.textContent = device.description || device.instanceId;
                  chip.append(platform, name);
                  list.appendChild(chip);
                });
                document.getElementById('devices').hidden = false;
              })
              .catch(function () {});
          })();
        </script>
      </body>
    </html>
    """
  }

  private fun statCell(label: String, value: Int, modifier: String): String {
    val zero = if (value == 0) " is-zero" else ""
    return """<div class="stat"><div class="stat-label">$label</div><div class="stat-value $modifier$zero">$value</div></div>"""
  }

  private fun sessionRow(info: SessionInfo): String {
    val sessionId = info.sessionId.value
    val (statusLabel, kind) = statusLabelAndKind(info.latestStatus)
    val titleIsId = info.displayName == sessionId
    val title = htmlEscape(info.displayName)
    val duration = formatDuration(info.durationMs)
    val href = GenerateReportEndpoint.sessionReportUrl(info.sessionId)
    // The storyboard HTML is generated on demand by StoryboardEndpoint, so this link is
    // always live — no dependency on a pre-generated `.storyboard.html` in the logs dir.
    val storyboardHref = "/storyboard?session=${urlEncode(sessionId)}"
    val sessionIdLine = if (titleIsId) "" else """<div class="session-id">${htmlEscape(sessionId)}</div>"""
    val titleClass = if (titleIsId) "title-link is-id" else "title-link"
    return """
            <tr data-kind="$kind">
              <td><span class="status status-$kind">$statusLabel</span></td>
              <td>
                <a class="$titleClass" href="$href" title="$title">$title</a>
                $sessionIdLine
              </td>
              <td class="meta">${timeElement(info)}</td>
              <td class="meta">$duration</td>
              <td><div class="row-actions"><a href="$href">Report</a><a href="$storyboardHref">Storyboard</a></div></td>
            </tr>
    """.trimEnd()
  }

  /** Absolute local time, which the page's script rewrites to a relative one and keeps as a tooltip. */
  private fun timeElement(info: SessionInfo): String =
    """<time datetime="${info.timestamp}">${htmlEscape(formatTimestamp(info))}</time>"""

  private fun statusLabelAndKind(status: SessionStatus): Pair<String, String> = when (status) {
    is SessionStatus.Ended.Succeeded -> "Passed" to "pass"
    is SessionStatus.Ended.SucceededWithSelfHeal -> "Passed (self-heal)" to "pass"
    is SessionStatus.Ended.Failed -> "Failed" to "fail"
    is SessionStatus.Ended.FailedWithSelfHeal -> "Failed (self-heal)" to "fail"
    is SessionStatus.Ended.Cancelled -> "Cancelled" to "other"
    is SessionStatus.Ended.TimeoutReached -> "Timed out" to "fail"
    is SessionStatus.Ended.MaxCallsLimitReached -> "Max calls" to "fail"
    is SessionStatus.Started -> "Running" to "prog"
    is SessionStatus.Unknown -> "Unknown" to "other"
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

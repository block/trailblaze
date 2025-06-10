<!DOCTYPE html>
<html lang="en">

<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🧭 Trailblaze Report</title>
    <script type="text/javascript">
        window._trailblazeSessions = ${summaryJson};
    </script>
    <#include "*/trailblaze_report_css.ftl">
</head>

<body>
<div class="container">
    <div class="header-flex" id="header-flex">
        <div style="display: flex; flex-direction: column; align-items: flex-start; flex: 1;">
            <div class="header-title">🧭 Trailblaze Report</div>
            <div class="session-id-header" id="session-id-header"></div>
        </div>
        <div class="header-controls">
        </div>
        <div class="header-summaries" id="header-summaries">
            <div class="header-summary">
                <div class="header-summary-title">Session Outcomes</div>
                <div class="header-outcomes">
                        <span class="header-outcome"><span class="header-outcome-emoji">✅</span> <span
                                    id="outcome-complete">-</span></span>
                    <span class="header-outcome"><span class="header-outcome-emoji">❌</span> <span
                                id="outcome-failed">-</span></span>
                    <span class="header-outcome"><span class="header-outcome-emoji">⏰</span> <span
                                id="outcome-maxcalls">-</span></span>
                </div>
            </div>
            <div class="header-divider"></div>
            <div class="header-summary">
                <div class="header-summary-title">LLM Usage</div>
                <div class="header-summary-value" id="llm-usage">-</div>
            </div>
        </div>
    </div>
    <div id="main-table-section">
        <div id="error-container"></div>
        <table class="sessions-table">
            <thead>
            <tr>
                <th onclick="sortTable(0)">Outcome</th>
                <th onclick="sortTable(1)">Start Time</th>
                <th onclick="sortTable(2)">Session</th>
                <th onclick="sortTable(3)">Duration</th>
                <th onclick="sortTable(4)">Cost</th>
                <th onclick="sortTable(5)">Tasks</th>
                <th>Screenshots</th>
                <th>Details</th>
            </tr>
            </thead>
            <tbody id="sessions-body">
            </tbody>
        </table>
    </div>
    <div id="session-details" class="session-details"></div>
</div>

<div id="screenshot-modal" class="modal">
    <div class="modal-content">
        <img id="modal-image" src="" alt="Screenshot">
    </div>
</div>

<div id="code-modal" class="code-modal">
    <div class="code-modal-content">
        <span class="code-modal-close">&times;</span>
        <pre id="code-content"></pre>
        <button class="copy-button" onclick="copyCode()">Copy to Clipboard</button>
    </div>
</div>

<#include "*/trailblaze_report_js.ftl">
</body>

</html>
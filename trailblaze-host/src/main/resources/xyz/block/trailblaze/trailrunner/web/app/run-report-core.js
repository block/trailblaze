// Environment-agnostic core for the interactive run report, used by the in-app "Share" button
// (browser: share-export.jsx) — it already holds the derived trace/llmLogs and supplies screenshots
// fetched from /static, then calls buildRunReportHtml here.
// This file is PLAIN JS (no JSX) and loads as a classic <script> (NOT type=text/babel): the embedded
// viewer is shipped via RUN_REPORT_VIEWER.toString(), and Babel would lower its `for...of` loops into
// module-scope helpers that don't survive serialization, blanking the exported report. It must not
// reference React, the DOM, or any other app global. (The extraction + renderer are kept self-contained
// here, and a CommonJS export is left in place, so a future headless generator could reuse them as-is.)

// ─────────────────────────────────────────────────────────────────────────────
// Log → timeline extraction (moved verbatim from data-extract.jsx; this is now the only copy).
// ─────────────────────────────────────────────────────────────────────────────

function truncate(s, n = 60) {
  if (s == null) return '';
  const str = String(s);
  return str.length > n ? str.slice(0, n - 1) + '…' : str;
}

function logClass(log) {
  const cls = log.class || '';
  const last = cls.split('.').pop();
  return last;
}

function extractTrace(logs) {
  // Trailblaze writes several log records per logical step; the timeline collapses
  // them so each user-meaningful step shows once:
  //  - an objective logs both a Start and a Complete carrying the same promptStep —
  //    the Complete is a bookend, dropped here;
  //  - one agent tool call logs a delegating tool + the resolved primitive (same
  //    traceId) plus the device action it dispatched — folded into one row;
  //  - assertion polling and scroll loops re-log each attempt — folded with an xN count.
  const out = [];
  let group = null; // open tool call, keyed by traceId, that later records fold into
  let asserts = new Map(); // condition -> row, reset whenever the assertion burst breaks
  let objective = null; // text of the active objective, to suppress per-turn echoes of it
  const closeGroup = () => { if (group) { out.push(group); group = null; } };

  for (const log of logs) {
    const cls = logClass(log);
    if (cls === 'ObjectiveCompleteLog') continue;

    const toolName = log.toolName;
    const traceId = typeof log.traceId === 'string' ? log.traceId : null;
    const action = cls === 'MaestroDriverLog' ? log.action : null;
    const promptText = stepText(log.promptStep) || (typeof log.instructions === 'string' ? log.instructions : null);
    const err = typeof log.errorMessage === 'string' ? log.errorMessage : null;
    const screenshotFile = log.screenshotFile || null;
    const viewHierarchy = log.viewHierarchyFiltered || log.trailblazeNodeTree || log.viewHierarchy || null;
    const ts = log.timestamp ? Date.parse(log.timestamp) : null;

    if (toolName) {
      asserts = new Map();
      if (group && traceId && group._trace === traceId) {
        if (!group.screenshotFile && screenshotFile) group.screenshotFile = screenshotFile;
        if (!group.viewHierarchy && viewHierarchy) group.viewHierarchy = viewHierarchy;
        if (group.ok && err) { group.ok = false; group.err = err; }
        group._logs.push(log);
        continue;
      }
      closeGroup();
      const ok = log.successful !== false && !err;
      const detail = toolDetail(log);
      group = { _trace: traceId, _logs: [log], label: toolName, tool: detail.summary, note: detail.note, ms: log.durationMs || 0, ok, err: ok ? null : (err || truncate(log.resultSummary)), screenshotFile, viewHierarchy, ts };
      if (!traceId) closeGroup();
      continue;
    }

    if (action && group) {
      if (!group.screenshotFile && screenshotFile) group.screenshotFile = screenshotFile;
      if (!group.viewHierarchy && viewHierarchy) group.viewHierarchy = viewHierarchy;
      if (group.ts == null) group.ts = ts;
      if (!group.tool) group.tool = describeAction(action);
      group._logs.push(log);
      continue;
    }

    if (action) {
      const actionType = (action.class || '').split('.').pop() || 'Device action';
      if (actionType === 'AssertCondition') {
        const cond = action.conditionDescription || '';
        const open = asserts.get(cond);
        if (open) { open.count++; open.ms += log.durationMs || 0; if (screenshotFile) open.screenshotFile = screenshotFile; if (viewHierarchy) open.viewHierarchy = viewHierarchy; open._logs.push(log); continue; }
        const row = { label: actionType, _logs: [log], tool: describeAction(action), ms: log.durationMs || 0, ok: true, err: null, screenshotFile, viewHierarchy, ts, count: 1 };
        out.push(row); asserts.set(cond, row); continue;
      }
      asserts = new Map();
      const sig = actionType + ':' + describeAction(action);
      const prev = out[out.length - 1];
      if (prev && prev._sig === sig) { prev.count = (prev.count || 1) + 1; prev.ms += log.durationMs || 0; if (screenshotFile) prev.screenshotFile = screenshotFile; if (viewHierarchy) prev.viewHierarchy = viewHierarchy; prev._logs.push(log); continue; }
      out.push({ _sig: sig, _logs: [log], label: actionType, tool: describeAction(action), ms: log.durationMs || 0, ok: true, err: null, screenshotFile, viewHierarchy, ts, count: 1 });
      continue;
    }

    if (promptText) {
      asserts = new Map(); closeGroup();
      const isObjective = cls === 'ObjectiveStartLog';
      if (isObjective) objective = promptText;
      // Each agent turn (TrailblazeLlmRequestLog) re-logs the active objective as its
      // promptStep; that reasoning already rides on the tool row that follows and in the
      // LLM transcript, so a turn that just echoes the objective isn't its own row.
      else if (promptText === objective && !err) continue;
      // `objective` marks the top-level trail steps (ObjectiveStartLog) so the timeline
      // can nest the tool calls / assertions that follow under their step.
      out.push({ label: truncate(promptText, 120), _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: !err, err, screenshotFile, viewHierarchy, ts, objective: isObjective });
      continue;
    }

    if (err) {
      asserts = new Map(); closeGroup();
      out.push({ label: 'Error', _logs: [log], tool: '', ms: 0, ok: false, err, screenshotFile, viewHierarchy, ts });
    }
  }
  closeGroup();

  return out.map((r, idx) => {
    const { _sig, _trace, count, note, ...rest } = r;
    const merged = count > 1 ? (note ? note + ' · ×' + count : '×' + count) : note;
    const children = toolChildren(r);
    const withChildren = children ? { ...rest, children } : rest;
    return merged != null ? { ...withChildren, note: merged, i: idx + 1 } : { ...withChildren, i: idx + 1 };
  });
}

// The sub-tools an outer tool delegated to. A high-level tool the agent calls (e.g.
// `tap` on a ref) is logged as a DelegatingTrailblazeToolLog carrying `executableTools`
// — the concrete executor tool(s) it expanded into (e.g. `tapOnElementBySelector` with a
// resolved selector). They share the outer tool's traceId, so they're already folded into
// this one row; we surface them as expandable children so the "this tool ran those tools"
// hierarchy is visible. Returns null when the row didn't delegate to a distinct inner tool
// (primitives, scripted host-side tools that call backend APIs, raw actions).
function toolChildren(r) {
  const first = r._logs && r._logs[0];
  const exec = first && Array.isArray(first.executableTools) ? first.executableTools : null;
  if (!exec || !exec.length) return null;
  const kids = exec
    .map((e) => ({ label: e.toolName || '', tool: summarizeToolArgs((e && e.raw) || {}, {}) }))
    .filter((c) => c.label && c.label !== r.label);
  return kids.length ? kids : null;
}

function describeAction(action) {
  if (!action) return '';
  const parts = [];
  if (action.x != null && action.y != null) parts.push(`(${action.x}, ${action.y})`);
  if (action.text) parts.push(`"${truncate(action.text, 30)}"`);
  if (action.conditionDescription) parts.push(truncate(action.conditionDescription, 40));
  if (action.appId) parts.push(action.appId);
  if (action.selector) parts.push(truncate(JSON.stringify(action.selector), 40));
  return parts.join(' ');
}

function parseLlmResponse(resp) {
  if (!Array.isArray(resp)) return [];
  const out = [];
  for (const msg of resp) {
    for (const part of (msg.parts || [])) {
      const cls = String(part.class || '');
      if (cls.endsWith('Tool.Call')) {
        let args = part.args;
        let reasoning = null;
        try {
          const parsed = JSON.parse(part.args);
          reasoning = parsed.reasoning || parsed.reason || null;
          if (reasoning) { delete parsed.reasoning; delete parsed.reason; }
          args = JSON.stringify(parsed, null, 2);
        } catch (e) { }
        out.push({ kind: 'tool', tool: part.tool, args, reasoning });
      } else if (part.text || part.content) {
        out.push({ kind: 'text', text: part.text || part.content });
      }
    }
  }
  return out;
}

function extractLlmLogs(logs) {
  const rows = [];
  for (const log of logs) {
    if (log.llmMessages || log.llmResponse) {
      const u = log.llmRequestUsageAndCost;
      const model = (u?.trailblazeLlmModel?.modelId)
        || (log.trailblazeLlmModel?.modelId)
        || log.modelName
        || '?';
      rows.push({
        model,
        inputTokens: u?.inputTokens ?? null,
        outputTokens: u?.outputTokens ?? null,
        cacheReadTokens: u?.cacheReadInputTokens ?? 0,
        promptCost: u?.promptCost ?? null,
        completionCost: u?.completionCost ?? null,
        totalCost: u?.totalCost ?? null,
        messages: log.llmMessages || [],
        response: parseLlmResponse(log.llmResponse),
        durationMs: log.durationMs || 0,
        label: log.llmRequestLabel || 'LLM Request',
        instructions: log.instructions || null,
      });
    }
    if (log.usageAndCost && log.systemPrompt !== undefined) {
      const u = log.usageAndCost;
      const model = (u?.trailblazeLlmModel?.modelId) || log.modelName || '?';
      rows.push({
        model,
        inputTokens: u?.inputTokens ?? null,
        outputTokens: u?.outputTokens ?? null,
        cacheReadTokens: u?.cacheReadInputTokens ?? 0,
        promptCost: u?.promptCost ?? null,
        completionCost: u?.completionCost ?? null,
        totalCost: u?.totalCost ?? null,
        messages: [],
        durationMs: log.durationMs || 0,
        label: 'MCP Sampling',
        instructions: log.userMessage || null,
      });
    }
  }
  return rows;
}

function stepText(promptStep) {
  if (!promptStep || typeof promptStep !== 'object') return null;
  return promptStep.step || promptStep.verify || promptStep.prompt || null;
}
function toolDetail(log) {
  const raw = (log.trailblazeTool && log.trailblazeTool.raw) || {};
  const delegated = (log.executableTools && log.executableTools[0] && log.executableTools[0].raw) || {};
  const reasoning = raw.reasoning || raw.reason || delegated.reasoning || delegated.reason || null;
  return {
    summary: summarizeToolArgs(raw, delegated),
    note: reasoning ? truncate(String(reasoning), 180) : null,
  };
}

function summarizeToolArgs(raw, delegated) {
  const a = { ...delegated, ...raw };
  const sel = a.selector || delegated.selector;
  if (sel && typeof sel === 'object') {
    const s = describeSelector(sel);
    if (s) return s;
  }
  if (a.text != null) return `"${truncate(String(a.text), 40)}"`;
  if (a.value != null && typeof a.value !== 'object') return `"${truncate(String(a.value), 40)}"`;
  if (a.x != null && a.y != null) return `(${a.x}, ${a.y})`;
  if (a.appId) return String(a.appId);
  const skip = { reason: 1, reasoning: 1, ref: 1, selector: 1 };
  const keys = Object.keys(a).filter((k) => !skip[k] && typeof a[k] !== 'object');
  return keys.length ? keys.slice(0, 3).map((k) => `${k}=${truncate(String(a[k]), 24)}`).join(' ') : '';
}

function describeSelector(sel) {
  const order = ['text', 'textRegex', 'idRegex', 'id', 'accessibilityText', 'contentDescription', 'containsChild'];
  const k = order.find((key) => sel[key] != null) || Object.keys(sel)[0];
  if (!k) return '';
  const v = sel[k];
  return `${k}: ${truncate(typeof v === 'object' ? JSON.stringify(v) : String(v), 44)}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Report assembly (moved from share-export.jsx; this is now the only copy).
// ─────────────────────────────────────────────────────────────────────────────

// Strip the heavy, viewer-irrelevant fields off each trace step before embedding: `_logs` (the
// raw log records), `_sig`/`_trace` (extraction bookkeeping), and `viewHierarchy` (can be
// hundreds of KB per step). Children collapse to just their label + arg summary.
function slimTraceForShare(trace) {
  return (trace || []).map((t) => ({
    i: t.i,
    label: t.label,
    tool: t.tool || '',
    note: t.note || null,
    ms: t.ms || 0,
    ok: t.ok !== false,
    err: t.ok === false ? (t.err || null) : null,
    screenshotFile: t.screenshotFile || null,
    objective: !!t.objective,
    count: t.count || null,
    children: (t.children || []).map((c) => ({ label: c.label, tool: c.tool || '' })),
  }));
}

// Keep what makes the LLM view skimmable — the model, token/cost accounting, the step it ran
// under, and the assistant's reasoning + chosen tool. We deliberately DROP `messages` (the
// system prompt + per-turn screen-state dumps): those repeat verbatim across every call and
// would dwarf the screenshots in file size, while the reasoning/decision is the gold.
function slimLlmForShare(llmLogs) {
  return (llmLogs || []).map((r) => ({
    model: r.model,
    inputTokens: r.inputTokens ?? null,
    outputTokens: r.outputTokens ?? null,
    cacheReadTokens: r.cacheReadTokens || 0,
    totalCost: r.totalCost ?? null,
    durationMs: r.durationMs || 0,
    label: r.label || 'LLM Request',
    instructions: r.instructions || null,
    response: (r.response || []).map((p) => p.kind === 'tool'
      ? { kind: 'tool', tool: p.tool, args: p.args || null, reasoning: p.reasoning || null }
      : { kind: 'text', text: p.text || '' }),
  }));
}

// Assemble the full self-contained HTML document. Pure: callers supply the (already-derived)
// `trace`/`llmLogs`, a `{ filename -> dataURI }` screenshot map, and the run `meta`. No fetch, no
// DOM — usable identically in the browser and in bun.
function buildRunReportHtml({ meta, trace, llmLogs, shots }) {
  const payload = {
    meta: { generatedAt: '', ...meta, steps: (trace || []).length || (meta && meta.steps) || 0 },
    trace: slimTraceForShare(trace),
    llm: slimLlmForShare(llmLogs),
    shots: shots || {},
  };
  // Escape `<` so a `</script>` inside any string can't close our data block early.
  const json = JSON.stringify(payload).replace(/</g, '\\u003c');
  const viewer = RUN_REPORT_VIEWER.toString();
  const title = ((payload.meta.title || 'Trailblaze run') + ' · Trailblaze run').replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
  return `<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${title}</title>
<style>${RUN_REPORT_CSS}</style>
</head>
<body>
<div id="app"></div>
<script id="run-data" type="application/json">${json}</script>
<script>(${viewer})();</script>
</body>
</html>`;
}

const RUN_REPORT_CSS = `
:root {
  --bg: #0e0f11; --bg2: #16181c; --bg3: #1c1f24; --line: rgba(255,255,255,.1);
  --line2: rgba(255,255,255,.16); --txt: #e6e7e9; --sub: #9aa0a6; --sub2: #c4c7cc;
  --pass: #2ecc5c; --fail: #f84752; --run: #5e9bff; --purple: #a679f5; --amber: #f0b429; --ai: #b58cff;
}
* { box-sizing: border-box; }
html, body { margin: 0; height: 100%; }
body { background: var(--bg); color: var(--txt); font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
#app { display: flex; flex-direction: column; min-height: 100%; }
header { padding: 22px 28px 0; border-bottom: 1px solid var(--line); }
.title-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h1 { font-size: 20px; margin: 0; font-weight: 700; }
.badge { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; padding: 3px 9px; border-radius: 99px; }
.badge.passed, .badge.success { background: rgba(46,204,92,.16); color: var(--pass); }
.badge.failed, .badge.error { background: rgba(248,71,82,.16); color: var(--fail); }
.badge.running, .badge.cancelled, .badge.unknown { background: rgba(94,155,255,.16); color: var(--run); }
.meta { display: flex; flex-wrap: wrap; gap: 8px 22px; margin-top: 12px; }
.meta .k { font-size: 9.5px; text-transform: uppercase; letter-spacing: .08em; color: var(--sub); }
.meta .v { font-size: 12.5px; font-weight: 500; }
.errbanner { margin-top: 14px; background: rgba(248,71,82,.1); border: 1px solid rgba(248,71,82,.3); color: #ffb3b8; border-radius: 8px; padding: 9px 12px; font-size: 12.5px; }
nav { display: flex; gap: 4px; margin-top: 16px; }
nav button { background: none; border: none; color: var(--sub); font-size: 13px; font-weight: 600; padding: 8px 12px; cursor: pointer; border-bottom: 2px solid transparent; }
nav button.active { color: var(--txt); border-bottom-color: var(--run); }
main { flex: 1; min-height: 0; padding: 18px 28px; }
footer { padding: 14px 28px; border-top: 1px solid var(--line); color: var(--sub); font-size: 11.5px; display: flex; gap: 8px; align-items: center; }
.tl { display: grid; grid-template-columns: 1fr minmax(280px, 38%); gap: 22px; align-items: start; }
@media (max-width: 760px) { .tl { grid-template-columns: 1fr; } }
.eyebrow { font-size: 9.5px; text-transform: uppercase; letter-spacing: .08em; color: var(--sub); margin-bottom: 8px; }
.steps { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: var(--bg2); }
.grphdr { padding: 10px 13px; background: var(--bg3); border-top: 1px solid var(--line2); display: flex; align-items: center; gap: 8px; }
.grphdr:first-child { border-top: none; }
.grphdr .chip { font-size: 9.5px; font-weight: 700; letter-spacing: .06em; color: var(--purple); background: rgba(166,121,245,.14); border-radius: 5px; padding: 2px 7px; }
.grphdr .dot { width: 8px; height: 8px; border-radius: 99px; }
.grphdr .lbl { display: block; font-size: 13px; font-weight: 600; margin-top: 6px; line-height: 1.4; }
.step { display: flex; gap: 9px; padding: 9px 13px; cursor: pointer; border-top: 1px solid var(--line); }
.step.child { padding-left: 22px; }
.step:hover { background: rgba(255,255,255,.03); }
.step.sel { background: rgba(94,155,255,.1); border-left: 2px solid var(--run); padding-left: 11px; }
.step.sel.child { padding-left: 20px; }
.step .num { font-size: 11px; color: var(--sub); width: 20px; text-align: right; flex-shrink: 0; }
.step .ic { width: 9px; height: 9px; border-radius: 99px; flex-shrink: 0; margin-top: 5px; }
.step .lbl { font-size: 13px; font-weight: 500; }
.step .tl-tool { font-size: 11px; color: var(--sub); margin-top: 2px; word-break: break-word; }
.step .note { font-size: 11.5px; color: var(--sub2); margin-top: 3px; line-height: 1.4; }
.step .err { font-size: 11.5px; color: #ffb3b8; margin-top: 3px; }
.kids { margin-top: 6px; border-left: 1px solid var(--line2); padding-left: 10px; }
.kids div { font-size: 11.5px; margin-top: 3px; }
.kids .kt { color: var(--sub); }
.preview { position: sticky; top: 18px; }
.shot { width: 100%; background: #000; border: 1px solid var(--line2); border-radius: 6px; display: block; cursor: zoom-in; }
.noshot { width: 100%; aspect-ratio: 1/2; border: 1px dashed var(--line2); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: var(--sub); font-size: 12px; text-align: center; padding: 20px; }
.pvctl { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.pvctl .count { margin-left: auto; font-size: 11px; color: var(--sub); }
.detail { margin-top: 14px; border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; background: var(--bg2); }
.detail .lbl { font-size: 13.5px; font-weight: 600; }
.detail .tl-tool { font-size: 12px; color: var(--sub); margin-top: 6px; word-break: break-word; }
.detail .err { font-size: 12px; color: #ffb3b8; margin-top: 6px; }
button.btn { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 11px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
button.btn:disabled { opacity: .4; cursor: default; }
button.btn:not(:disabled):hover { border-color: var(--run); }
.llm { display: grid; grid-template-columns: 300px 1fr; gap: 20px; align-items: start; }
@media (max-width: 760px) { .llm { grid-template-columns: 1fr; } }
.card { border: 1px solid var(--line); border-radius: 10px; background: var(--bg2); padding: 10px 13px; }
.totals { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px; }
.totals .n { font-size: 13px; font-weight: 700; }
.totals .t { font-size: 10.5px; color: var(--sub); }
.callrow { padding: 8px 11px; margin-top: 5px; cursor: pointer; border: 1px solid var(--line); border-radius: 8px; background: var(--bg2); }
.callrow.sel { background: var(--bg3); border-color: rgba(46,204,92,.45); }
.callrow .d { font-size: 11.5px; font-weight: 600; }
.callrow .m { font-size: 10.5px; color: var(--sub); margin-top: 3px; }
.resp { border: 1px solid rgba(181,140,255,.3); background: rgba(181,140,255,.07); border-radius: 10px; padding: 11px 13px; margin-top: 10px; }
.resp .h { font-size: 10.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; color: var(--ai); margin-bottom: 8px; }
.resp .reason { font-size: 12.5px; line-height: 1.55; margin-bottom: 6px; }
.resp .tool { font-size: 12px; font-weight: 700; margin: 4px 0; }
pre { margin: 0; font-size: 11px; line-height: 1.5; color: var(--sub2); white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
.rows { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; max-width: 720px; }
.rows .r { display: flex; gap: 12px; padding: 9px 13px; background: var(--bg2); border-top: 1px solid var(--line); font-size: 12.5px; }
.rows .r:first-child { border-top: none; }
.rows .r .k { flex: 0 0 90px; color: var(--sub); font-size: 11.5px; }
.rows .r .v { word-break: break-all; }
.cmd { display: flex; gap: 8px; align-items: flex-start; margin-top: 8px; max-width: 720px; }
.cmd pre { flex: 1; }
.zoom { position: fixed; inset: 0; background: rgba(0,0,0,.85); display: flex; align-items: center; justify-content: center; cursor: zoom-out; z-index: 99; }
.zoom img { max-width: 92vw; max-height: 92vh; border-radius: 10px; border: 1px solid var(--line2); }
.empty { color: var(--sub); font-size: 13px; padding: 30px; text-align: center; }
`;

// The standalone viewer. This function is serialized via .toString() and embedded; it must be
// fully self-contained (no references to outer scope) and read its data from #run-data. Plain
// DOM only — no React, no external scripts — so the exported file runs offline anywhere.
function RUN_REPORT_VIEWER() {
  const D = JSON.parse(document.getElementById('run-data').textContent);
  const root = document.getElementById('app');
  const esc = (s) => String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const st = { tab: 'timeline', step: (D.trace[0] && D.trace[0].i) || 0, llmSel: 0 };
  // Default the timeline to the first failed step, mirroring the live Run details page.
  const firstFail = D.trace.findIndex((t) => !t.ok);
  if (firstFail >= 0) st.step = D.trace[firstFail].i;

  const stepCat = (t) => {
    if (!t.ok) return 'fail';
    const tool = String(t.tool || ''); const lbl = String(t.label || '').toLowerCase();
    if (tool === 'agent step' || tool.indexOf('llm') === 0) return 'llm';
    if (lbl.indexOf('assert') === 0 || lbl.indexOf('verify') === 0 || tool.toLowerCase().indexOf('assert') >= 0) return 'assert';
    return 'tool';
  };
  const catColor = { fail: 'var(--fail)', llm: 'var(--run)', assert: 'var(--amber)', tool: 'var(--pass)' };

  const idxOf = (i) => Math.max(0, D.trace.findIndex((t) => t.i === i));
  const shotForStep = (i) => {
    let file = null;
    for (let k = idxOf(i); k >= 0; k--) { if (D.trace[k] && D.trace[k].screenshotFile) { file = D.trace[k].screenshotFile; break; } }
    return file ? D.shots[file] : null;
  };

  // Group flat trace under objective rows -> { header, num, items } (same shape as the app's StepStack).
  const groups = (() => {
    const gs = []; let cur = null; let n = 0;
    for (const t of D.trace) {
      if (t.objective) { cur = { header: t, num: ++n, items: [] }; gs.push(cur); }
      else { if (!cur) { cur = { header: null, num: 0, items: [] }; gs.push(cur); } cur.items.push(t); }
    }
    return gs;
  })();
  const hasSteps = groups.some((g) => g.header);

  const stepRowHtml = (t, child) => {
    const cat = stepCat(t); const sel = t.i === st.step;
    const kids = (t.children || []).length
      ? `<div class="kids">${t.children.map((c) => `<div><span class="mono">${esc(c.label)}</span> <span class="kt mono">${esc(c.tool)}</span></div>`).join('')}</div>` : '';
    const count = t.count ? ` <span class="mono" style="color:var(--sub)">×${t.count}</span>` : '';
    return `<div class="step${sel ? ' sel' : ''}${child ? ' child' : ''}" data-step="${t.i}">
      ${child ? '' : `<span class="num mono">${t.i}</span>`}
      <span class="ic" style="background:${catColor[cat]}"></span>
      <div style="flex:1;min-width:0">
        <div class="lbl">${esc(t.label)}${count}</div>
        ${t.tool ? `<div class="tl-tool mono">${esc(t.tool)}</div>` : ''}
        ${t.note ? `<div class="note">${esc(t.note)}</div>` : ''}
        ${!t.ok && t.err ? `<div class="err">${esc(t.err)}</div>` : ''}
        ${kids}
      </div>
    </div>`;
  };

  const renderTimeline = () => {
    if (!D.trace.length) return `<div class="empty">This run didn't emit any agent-task steps.</div>`;
    let stepsHtml;
    if (!hasSteps) {
      stepsHtml = D.trace.map((t) => stepRowHtml(t, false)).join('');
    } else {
      stepsHtml = groups.map((g) => {
        const failed = (g.header && !g.header.ok) || g.items.some((t) => !t.ok);
        const hdr = g.header ? `<div class="grphdr">
            <span class="chip">STEP ${g.num}</span>
            <span class="dot" style="background:${failed ? 'var(--fail)' : 'var(--pass)'}"></span>
            ${g.items.length ? `<span style="font-size:11px;color:var(--sub)">${g.items.length} action${g.items.length === 1 ? '' : 's'}</span>` : ''}
            <span class="lbl" style="width:100%">${esc(g.header.label)}</span>
          </div>` : '';
        return hdr + g.items.map((t) => stepRowHtml(t, hasSteps)).join('');
      }).join('');
    }
    const cur = D.trace.find((t) => t.i === st.step) || D.trace[0];
    const shot = shotForStep(st.step);
    const pos = idxOf(st.step);
    const detail = `<div class="detail">
        <div class="lbl">${esc(cur.label)}</div>
        ${cur.tool ? `<div class="tl-tool mono">${esc(cur.tool)}</div>` : ''}
        ${cur.note ? `<div class="tl-tool">${esc(cur.note)}</div>` : ''}
        ${!cur.ok && cur.err ? `<div class="err">${esc(cur.err)}</div>` : ''}
      </div>`;
    return `<div class="tl">
      <div><div class="eyebrow">${D.trace.length} step${D.trace.length === 1 ? '' : 's'}</div><div class="steps">${stepsHtml}</div></div>
      <div class="preview">
        ${shot ? `<img class="shot" id="shot" src="${shot}" alt="Step ${pos + 1}" />` : `<div class="noshot">No screenshot captured before this step.</div>`}
        <div class="pvctl">
          <button class="btn" id="prev"${pos <= 0 ? ' disabled' : ''}>&larr; Prev</button>
          <button class="btn" id="next"${pos >= D.trace.length - 1 ? ' disabled' : ''}>Next &rarr;</button>
          <span class="count mono">Step ${pos + 1} / ${D.trace.length}</span>
        </div>
        ${detail}
      </div>
    </div>`;
  };

  const fmtN = (n) => n == null ? '—' : n.toLocaleString();
  const fmtCost = (c) => c == null ? '—' : c < 0.000001 ? '<$0.000001' : '$' + c.toFixed(6);
  const decisionOf = (r) => { const t = (r.response || []).find((p) => p.kind === 'tool'); return t ? t.tool : ((r.response || []).find((p) => p.kind === 'text') ? 'text reply' : r.label); };

  const renderLlm = () => {
    if (!D.llm.length) return `<div class="empty">This run has no LLM request logs.</div>`;
    const totals = D.llm.reduce((a, r) => ({ i: a.i + (r.inputTokens || 0), o: a.o + (r.outputTokens || 0), c: a.c + (r.totalCost || 0) }), { i: 0, o: 0, c: 0 });
    const list = D.llm.map((r, i) => `<div class="callrow${i === st.llmSel ? ' sel' : ''}" data-llm="${i}">
        <div class="d mono">${i + 1}. ${esc(decisionOf(r))}</div>
        <div class="m">in ${fmtN(r.inputTokens)} · out ${fmtN(r.outputTokens)}${r.durationMs ? ' · ' + (r.durationMs / 1000).toFixed(1) + 's' : ''}</div>
      </div>`).join('');
    const r = D.llm[st.llmSel] || D.llm[0];
    const respParts = (r.response || []).length ? (r.response || []).map((p) => p.kind === 'tool'
      ? `${p.reasoning ? `<div class="reason">${esc(p.reasoning)}</div>` : ''}<div class="tool mono">⚙ ${esc(p.tool)}</div>${p.args && p.args !== '{}' ? `<pre>${esc(p.args)}</pre>` : ''}`
      : `<div class="reason">${esc(p.text)}</div>`).join('') : '<div style="color:var(--sub);font-size:12px">No response captured for this call.</div>';
    const detail = `<div class="card" style="display:flex;gap:16px;flex-wrap:wrap;align-items:baseline">
        <span style="font-weight:700">Call ${st.llmSel + 1} <span style="color:var(--sub);font-weight:500">of ${D.llm.length}</span></span>
        <span class="mono" style="color:var(--sub);font-size:11.5px">${esc(r.model)}</span>
        <span style="color:var(--sub);font-size:11.5px">in ${fmtN(r.inputTokens)}${r.cacheReadTokens ? ' (' + fmtN(r.cacheReadTokens) + ' cached)' : ''} · out ${fmtN(r.outputTokens)}</span>
        ${r.totalCost != null ? `<span style="color:var(--sub);font-size:11.5px">${fmtCost(r.totalCost)}</span>` : ''}
      </div>
      ${r.instructions ? `<div style="margin:10px 2px 0;font-size:13px;font-weight:600">${esc(r.instructions)}</div>` : ''}
      <div class="resp"><div class="h">Assistant response</div>${respParts}</div>`;
    return `<div class="llm">
      <div><div class="card"><div style="font-size:12px;font-weight:600;color:var(--sub)">Session totals · ${D.llm.length} calls</div>
        <div class="totals"><div><div class="n mono">${fmtN(totals.i)}</div><div class="t">input tokens</div></div>
        <div><div class="n mono">${fmtN(totals.o)}</div><div class="t">output tokens</div></div>
        <div><div class="n mono">${fmtCost(totals.c)}</div><div class="t">total cost</div></div></div></div>
        <div style="margin-top:12px">${list}</div></div>
      <div>${detail}</div>
    </div>`;
  };

  const renderInfo = () => {
    const m = D.meta;
    const rows = [['Target', m.target], ['Device', m.device], ['Platform', m.platform], ['Trail', m.trailId], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt], ['Duration', m.duration]]
      .filter(([, v]) => v).map(([k, v]) => `<div class="r"><span class="k">${k}</span><span class="v">${esc(v)}</span></div>`).join('');
    return `<div>
      ${m.cmd ? `<div class="eyebrow">Rerun this in the CLI</div><div class="cmd"><pre class="mono" id="cmd">${esc(m.cmd)}</pre><button class="btn" id="copycmd">Copy</button></div>` : ''}
      <div class="eyebrow" style="margin-top:22px">Run details</div><div class="rows">${rows}</div>
    </div>`;
  };

  const render = () => {
    const m = D.meta;
    const tabs = [['timeline', 'Timeline'], ...(D.llm.length ? [['llm', `LLM (${D.llm.length})`]] : []), ['info', 'Info']];
    const body = st.tab === 'timeline' ? renderTimeline() : st.tab === 'llm' ? renderLlm() : renderInfo();
    const metaItems = [['Target', m.target], ['Device', m.device], ['Duration', m.duration], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt]]
      .filter(([, v]) => v).map(([k, v]) => `<div><div class="k">${k}</div><div class="v">${esc(v)}</div></div>`).join('');
    root.innerHTML = `
      <header>
        <div class="title-row"><h1>${esc(m.title)}</h1><span class="badge ${esc(m.status)}">${esc(m.status)}</span></div>
        <div class="meta">${metaItems}</div>
        ${m.error ? `<div class="errbanner">${esc(m.error)}</div>` : ''}
        <nav>${tabs.map(([id, l]) => `<button class="${st.tab === id ? 'active' : ''}" data-tab="${id}">${l}</button>`).join('')}</nav>
      </header>
      <main>${body}</main>
      <footer><span>Exported from Trailblaze · ${esc(m.generatedAt)}</span></footer>`;
    wire();
  };

  let zoomEl = null;
  const wire = () => {
    root.querySelectorAll('[data-tab]').forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; render(); });
    root.querySelectorAll('[data-step]').forEach((el) => el.onclick = () => { st.step = +el.dataset.step; render(); });
    root.querySelectorAll('[data-llm]').forEach((el) => el.onclick = () => { st.llmSel = +el.dataset.llm; render(); });
    const prev = document.getElementById('prev'); const next = document.getElementById('next');
    if (prev) prev.onclick = () => { const p = idxOf(st.step); if (p > 0) { st.step = D.trace[p - 1].i; render(); } };
    if (next) next.onclick = () => { const p = idxOf(st.step); if (p < D.trace.length - 1) { st.step = D.trace[p + 1].i; render(); } };
    const shot = document.getElementById('shot');
    if (shot) shot.onclick = () => {
      // Build the zoom overlay via DOM APIs (not innerHTML) — the image src is a data: URI but we
      // never reinterpret any value as HTML here.
      zoomEl = document.createElement('div'); zoomEl.className = 'zoom';
      const big = document.createElement('img'); big.src = shot.src; big.alt = 'screenshot';
      zoomEl.appendChild(big);
      zoomEl.onclick = () => { zoomEl.remove(); zoomEl = null; };
      document.body.appendChild(zoomEl);
    };
    const copycmd = document.getElementById('copycmd');
    if (copycmd) copycmd.onclick = () => { try { navigator.clipboard.writeText(D.meta.cmd); copycmd.textContent = 'Copied'; setTimeout(() => { copycmd.textContent = 'Copy'; }, 1500); } catch (e) {} };
  };

  document.addEventListener('keydown', (e) => {
    if (zoomEl && e.key === 'Escape') { zoomEl.remove(); zoomEl = null; return; }
    if (st.tab !== 'timeline' || !D.trace.length) return;
    if (e.key === 'ArrowLeft') { const p = idxOf(st.step); if (p > 0) { st.step = D.trace[p - 1].i; render(); } }
    if (e.key === 'ArrowRight') { const p = idxOf(st.step); if (p < D.trace.length - 1) { st.step = D.trace[p + 1].i; render(); } }
  });

  render();
}

// Export to the browser global scope (classic script) and to bun/node (require).
const RUN_REPORT_EXPORTS = {
  truncate, logClass, extractTrace, toolChildren, describeAction, parseLlmResponse, extractLlmLogs,
  stepText, toolDetail, summarizeToolArgs, describeSelector,
  slimTraceForShare, slimLlmForShare, buildRunReportHtml, RUN_REPORT_CSS, RUN_REPORT_VIEWER,
};
if (typeof window !== 'undefined') Object.assign(window, RUN_REPORT_EXPORTS);
if (typeof module !== 'undefined' && module.exports) module.exports = RUN_REPORT_EXPORTS;

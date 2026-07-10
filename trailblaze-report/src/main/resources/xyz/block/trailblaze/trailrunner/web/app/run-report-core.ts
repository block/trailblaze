// Environment-agnostic core for the interactive run report, used by the in-app "Share" button
// (browser: share-export.jsx) — it already holds the derived trace/llmLogs and supplies screenshots
// fetched from /static, then calls buildRunReportHtml here.
//
// TypeScript SOURCE for a plain-JS artifact: the Gradle task `transpileRunReportCore`
// (:trailblaze-host) emits `run-report-core.js` from this file via `bun build --no-bundle`
// (type-strip only, no syntax lowering) into the module's generated JAR resources — this .ts file
// itself is NOT packaged. Consumers load the emitted .js: the Trail Runner web app as a classic
// <script> (NOT type=text/babel), and the bun driver via `require()`. Constraints that shape it:
//  - It must stay a SCRIPT (no import/export statements): the embedded viewer is shipped via
//    RUN_REPORT_VIEWER.toString(), and module lowering would break both the classic-script load and
//    the serialized function (Babel lowers `for...of` into module-scope helpers that don't survive
//    serialization, blanking the exported report). Shared types come from the ambient
//    run-report-types.d.ts instead of imports.
//  - It must not reference React or any other app global. (The extraction + renderer are
//    self-contained; the guarded CommonJS export serves bun/node consumers.)

// ─────────────────────────────────────────────────────────────────────────────
// Log → timeline extraction (moved verbatim from data-extract.jsx; this is now the only copy).
// ─────────────────────────────────────────────────────────────────────────────

function truncate(s: unknown, n = 60): string {
  if (s == null) return '';
  const str = String(s);
  return str.length > n ? str.slice(0, n - 1) + '…' : str;
}

function logClass(log: TrailblazeLogRecord): string {
  const cls = log.class || '';
  const last = cls.split('.').pop();
  return last || '';
}

function extractTrace(logs: TrailblazeLogRecord[]): RawTraceRow[] {
  // Trailblaze writes several log records per logical step; the timeline collapses
  // them so each user-meaningful step shows once:
  //  - an objective logs both a Start and a Complete carrying the same promptStep —
  //    the Complete is a bookend, dropped here;
  //  - one agent tool call logs a delegating tool + the resolved primitive (same
  //    traceId) plus the device action it dispatched — folded into one row;
  //  - assertion polling and scroll loops re-log each attempt — folded with an xN count.
  const out: any[] = [];
  let group: any = null; // open tool call, keyed by traceId, that later records fold into
  let asserts = new Map<string, any>(); // condition -> row, reset whenever the assertion burst breaks
  let objective: string | null = null; // text of the active objective, to suppress per-turn echoes of it
  let objRow: any = null; // the open objective row, so a failing ObjectiveCompleteLog can mark it failed
  const closeGroup = () => { if (group) { out.push(group); group = null; } };

  for (const log of logs) {
    const cls = logClass(log);
    // An objective logs a Complete bookend carrying an AgentTaskStatus. On a Failure result, mark
    // the matching objective row failed (it shows red, becomes the default-selected step, and turns
    // its group header red) — this is how MCP-sampling agents record a failed step (no driver-action
    // AssertCondition). The Complete itself is otherwise a bookend and dropped.
    if (cls === 'ObjectiveCompleteLog') {
      const res = log.objectiveResult;
      const failed = res && String(res.class || '').indexOf('Failure') >= 0;
      if (failed && objRow) {
        objRow.ok = false;
        objRow.err = truncate(res.llmExplanation || log.errorMessage || 'Objective failed', 180);
      }
      continue;
    }

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
      if (!group.mark) { const mk = actionMark(action, log); if (mk) group.mark = mk; }
      group._logs.push(log);
      continue;
    }

    if (action) {
      const actionType = (action.class || '').split('.').pop() || 'Device action';
      if (actionType === 'AssertCondition') {
        const cond = action.conditionDescription || '';
        // A failed assertion (`succeeded: false`) marks the step failed so it shows red, is the
        // timeline's default-selected step, and bubbles its group header to failed — matching the
        // legacy report. For a polled assertion the latest attempt's outcome wins.
        const aok = action.succeeded !== false;
        const aerr = aok ? null : (err || `Assertion failed: ${cond}`);
        const open = asserts.get(cond);
        if (open) { open.count++; open.ms += log.durationMs || 0; open.ok = aok; open.err = aerr; if (screenshotFile) open.screenshotFile = screenshotFile; if (viewHierarchy) open.viewHierarchy = viewHierarchy; open._logs.push(log); continue; }
        const row = { label: actionType, _logs: [log], tool: describeAction(action), ms: log.durationMs || 0, ok: aok, err: aerr, screenshotFile, viewHierarchy, ts, count: 1, mark: actionMark(action, log) };
        out.push(row); asserts.set(cond, row); continue;
      }
      asserts = new Map();
      const sig = actionType + ':' + describeAction(action);
      const prev = out[out.length - 1];
      if (prev && prev._sig === sig) { prev.count = (prev.count || 1) + 1; prev.ms += log.durationMs || 0; if (screenshotFile) prev.screenshotFile = screenshotFile; if (viewHierarchy) prev.viewHierarchy = viewHierarchy; prev._logs.push(log); continue; }
      out.push({ _sig: sig, _logs: [log], label: actionType, tool: describeAction(action), ms: log.durationMs || 0, ok: true, err: null, screenshotFile, viewHierarchy, ts, count: 1, mark: actionMark(action, log) });
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
      const prow = { label: truncate(promptText, 120), _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: !err, err, screenshotFile, viewHierarchy, ts, objective: isObjective };
      out.push(prow);
      if (isObjective) objRow = prow;
      continue;
    }

    // Terminal / failure snapshots (captureFinalScreenshot / captureFailureScreenshot) log a
    // TrailblazeSnapshotLog carrying a screenshotFile + displayName but no tool/action/prompt.
    // Without an explicit row they fall through every branch above and are dropped — so the state
    // after the final action (the tap's result) never shows in the timeline. Render it as its own
    // trailing cell so the run's end state is visible.
    if (cls === 'TrailblazeSnapshotLog' && screenshotFile) {
      asserts = new Map(); closeGroup();
      const label = log.displayName === 'final_screenshot' ? 'Final state'
        : log.displayName === 'failure_screenshot' ? 'Failure state'
        : (log.displayName || 'Snapshot');
      out.push({ label, _logs: [log], tool: '', ms: log.durationMs || 0, ok: log.displayName !== 'failure_screenshot', err: null, screenshotFile, viewHierarchy, ts });
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
// (primitives, scripted host-side tools that call backend APIs directly, raw actions).
function toolChildren(r: any): TraceChild[] | null {
  const first = r._logs && r._logs[0];
  const exec = first && Array.isArray(first.executableTools) ? first.executableTools : null;
  if (!exec || !exec.length) return null;
  const kids = exec
    .map((e) => ({ label: e.toolName || '', tool: summarizeToolArgs((e && e.raw) || {}, {}) }))
    .filter((c) => c.label && c.label !== r.label);
  return kids.length ? kids : null;
}

// The report-time annotation for a device action: a tap/long-press point, a swipe vector, or an
// assertion marker — in device-pixel coordinates (dw×dh = the screenshot's natural size). The viewer
// overlays it on the step's screenshot scaled by ratio. Returns null when there are no coordinates
// (e.g. a keypress) or no device dimensions to scale against. Set-of-mark numbered boxes are NOT
// here — those are baked into the screenshot pixels at capture time.
function actionMark(action: any, log: TrailblazeLogRecord): ActionMark | null {
  if (!action) return null;
  const dw = log.deviceWidth || null;
  const dh = log.deviceHeight || null;
  if (!dw || !dh) return null;
  const kind = (action.class || '').split('.').pop() || '';
  if (kind === 'Swipe') {
    if (action.startX == null || action.startY == null || action.endX == null || action.endY == null) return null;
    return { kind: 'swipe', x1: action.startX, y1: action.startY, x2: action.endX, y2: action.endY, dw, dh };
  }
  if (action.x == null || action.y == null) return null;
  if (kind === 'AssertCondition') return { kind: 'assert', x: action.x, y: action.y, dw, dh, ok: action.succeeded !== false };
  return { kind: 'tap', x: action.x, y: action.y, dw, dh };
}

function describeAction(action: any): string {
  if (!action) return '';
  const parts = [];
  if (action.x != null && action.y != null) parts.push(`(${action.x}, ${action.y})`);
  if (action.text) parts.push(`"${truncate(action.text, 30)}"`);
  if (action.conditionDescription) parts.push(truncate(action.conditionDescription, 40));
  if (action.appId) parts.push(action.appId);
  if (action.selector) parts.push(truncate(JSON.stringify(action.selector), 40));
  return parts.join(' ');
}

function parseLlmResponse(resp: unknown): LlmResponsePart[] {
  if (!Array.isArray(resp)) return [];
  const out: LlmResponsePart[] = [];
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

function extractLlmLogs(logs: TrailblazeLogRecord[]): RawLlmRow[] {
  const rows: RawLlmRow[] = [];
  // Total cost the usage object reports. The logs carry promptCost + completionCost (per-request),
  // not a precomputed totalCost; sum them so the viewer's cost totals match computeUsageSummary
  // ($promptCost + $completionCost per call) instead of showing ~$0.
  const costOf = (u) => {
    if (!u) return null;
    if (u.totalCost != null) return u.totalCost;
    if (u.promptCost != null || u.completionCost != null) return (u.promptCost || 0) + (u.completionCost || 0);
    return null;
  };
  // The SAME LLM call is logged twice in the MCP-sampling agent path: once as a TrailblazeLlmRequestLog
  // (llmMessages/llmResponse) and once as an McpSamplingLog (usageAndCost + systemPrompt), sharing a
  // traceId. Count the request log (matching computeUsageSummary) and skip the paired sampling entry so
  // call counts, tokens, and cost aren't doubled. Precompute the request traceIds so the dedup is
  // order-independent; a sampling log with no paired request (pure-MCP session) is still counted.
  const requestTraceIds = new Set(
    logs.filter((l) => (l.llmMessages || l.llmResponse) && l.traceId).map((l) => l.traceId),
  );
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
        totalCost: costOf(u),
        messages: log.llmMessages || [],
        response: parseLlmResponse(log.llmResponse),
        durationMs: log.durationMs || 0,
        label: log.llmRequestLabel || 'LLM Request',
        instructions: log.instructions || null,
      });
    }
    if (log.usageAndCost && log.systemPrompt !== undefined) {
      if (log.traceId && requestTraceIds.has(log.traceId)) continue; // paired duplicate of a request log
      const u = log.usageAndCost;
      const model = (u?.trailblazeLlmModel?.modelId) || log.modelName || '?';
      rows.push({
        model,
        inputTokens: u?.inputTokens ?? null,
        outputTokens: u?.outputTokens ?? null,
        cacheReadTokens: u?.cacheReadInputTokens ?? 0,
        promptCost: u?.promptCost ?? null,
        completionCost: u?.completionCost ?? null,
        totalCost: costOf(u),
        messages: [],
        durationMs: log.durationMs || 0,
        label: 'MCP Sampling',
        instructions: log.userMessage || null,
      });
    }
  }
  return rows;
}

function stepText(promptStep: any): string | null {
  if (!promptStep || typeof promptStep !== 'object') return null;
  return promptStep.step || promptStep.verify || promptStep.prompt || null;
}
function toolDetail(log: TrailblazeLogRecord): { summary: string; note: string | null } {
  const raw = (log.trailblazeTool && log.trailblazeTool.raw) || {};
  const delegated = (log.executableTools && log.executableTools[0] && log.executableTools[0].raw) || {};
  const reasoning = raw.reasoning || raw.reason || delegated.reasoning || delegated.reason || null;
  return {
    summary: summarizeToolArgs(raw, delegated),
    note: reasoning ? truncate(String(reasoning), 180) : null,
  };
}

function summarizeToolArgs(raw: any, delegated: any): string {
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

function describeSelector(sel: any): string {
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
function slimTraceForShare(trace: RawTraceRow[] | null | undefined): TraceStep[] {
  return (trace || []).map((t) => ({
    i: t.i,
    label: t.label,
    tool: t.tool || '',
    note: t.note || null,
    ms: t.ms || 0,
    ts: t.ts || null,
    ok: t.ok !== false,
    err: t.ok === false ? (t.err || null) : null,
    screenshotFile: t.screenshotFile || null,
    objective: !!t.objective,
    count: t.count || null,
    mark: t.mark || null,
    children: (t.children || []).map((c) => ({ label: c.label, tool: c.tool || '' })),
  }));
}

// Keep what makes the LLM view skimmable — the model, token/cost accounting, the step it ran
// under, and the assistant's reasoning + chosen tool. We deliberately DROP `messages` (the
// system prompt + per-turn screen-state dumps): those repeat verbatim across every call and
// would dwarf the screenshots in file size, while the reasoning/decision is the gold.
function slimLlmForShare(llmLogs: RawLlmRow[] | null | undefined): LlmCall[] {
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

// Assemble the full self-contained HTML document for ONE run. Thin wrapper over
// buildMultiReportHtml so the in-app Share button (browser) and the single-run case keep the same
// { meta, trace, llmLogs, shots } signature. An optional recording YAML rides on meta.recordingYaml
// to surface the Recording tab. Pure: no fetch, no DOM — usable identically in the browser and bun.
function buildRunReportHtml({ meta, trace, llmLogs, shots }: { meta: RunMeta; trace: RawTraceRow[]; llmLogs: RawLlmRow[]; shots: Record<string, string> }): string {
  // Recording YAML rides in on meta.recordingYaml; lift it into the dedicated session field and drop
  // it from meta so the (potentially large) string isn't embedded twice in the payload.
  const { recordingYaml = null, ...metaRest } = meta || {};
  return buildMultiReportHtml({
    generatedAt: metaRest.generatedAt || '',
    sessions: [{ meta: metaRest, trace, llmLogs, shots, recordingYaml }],
  });
}

// Assemble the full self-contained HTML document for ONE OR MORE runs. Each session carries its own
// derived trace/llmLogs, screenshot map, and (optional) recording YAML. A single session opens
// straight on that run's detail (mirroring the old WASM report's single-session auto-advance); with
// several it opens on a pass/fail session index that drills into each run. Pure: callers supply
// already-derived data; no fetch, no DOM — identical in the browser and in bun.
function buildMultiReportHtml({ generatedAt, sessions }: { generatedAt?: string; sessions: SessionInput[] }): string {
  const list: SessionPayload[] = (sessions || []).map((s) => {
    const trace = s.trace || [];
    return {
      meta: { generatedAt: generatedAt || '', ...(s.meta || {}), steps: trace.length || (s.meta && s.meta.steps) || 0 },
      trace: slimTraceForShare(trace),
      llm: slimLlmForShare(s.llmLogs),
      shots: s.shots || {},
      recordingYaml: s.recordingYaml || null,
      deviceLog: s.deviceLog || null,
      network: s.network || null,
      events: s.events || null,
      video: s.video || null,
    };
  });
  const payload = { generatedAt: generatedAt || '', sessions: list };
  // Embed the payload as a JS object literal assigned to a global, NOT as text parsed back out of a
  // DOM node. Reading our own trusted data from a JS variable (vs. element.textContent) keeps the
  // viewer's innerHTML off the "DOM-text reinterpreted as HTML" path; every user-supplied field is
  // still escaped at render time. Escape `<` so a `</script>` inside any string can't close the
  // script block early.
  const json = JSON.stringify(payload).replace(/</g, '\\u003c');
  const viewer = RUN_REPORT_VIEWER.toString();
  const heading = list.length === 1 ? (list[0].meta.title || 'Trailblaze run') : `${list.length} Trailblaze runs`;
  const title = (heading + ' · Trailblaze run').replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
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
<script>window.__TB_RUN_DATA__ = ${json};</script>
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
.shotwrap { width: fit-content; max-width: 100%; margin: 0 auto; }
.shot { max-width: 100%; max-height: calc(100vh - 300px); background: #000; border: 1px solid var(--line2); border-radius: 6px; display: block; cursor: zoom-in; }
.noshot { width: 100%; aspect-ratio: 1/2; max-height: calc(100vh - 300px); border: 1px dashed var(--line2); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: var(--sub); font-size: 12px; text-align: center; padding: 20px; }
.pvctl { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.pvctl .count { margin-left: auto; font-size: 11px; color: var(--sub); }
.detail { margin-top: 14px; border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; background: var(--bg2); }
.detail .lbl { font-size: 13.5px; font-weight: 600; }
.detail .tl-tool { font-size: 12px; color: var(--sub); margin-top: 6px; word-break: break-word; }
.detail .err { font-size: 12px; color: #ffb3b8; margin-top: 6px; }
button.btn { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 11px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
button.btn:disabled { opacity: .4; cursor: default; }
button.btn:not(:disabled):hover { border-color: var(--run); }
button.btn.play { border-color: var(--run); color: var(--run); min-width: 84px; }
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
.idxsum { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 16px; }
.idxsum .n { font-size: 19px; font-weight: 700; }
.idxsum .t { font-size: 10px; color: var(--sub); text-transform: uppercase; letter-spacing: .07em; margin-top: 2px; }
.idx { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: var(--bg2); max-width: 880px; }
.idxrow { display: flex; align-items: center; gap: 12px; padding: 13px 15px; border-top: 1px solid var(--line); cursor: pointer; }
.idxrow:first-child { border-top: none; }
.idxrow:hover { background: rgba(255,255,255,.03); }
.idxrow .dot { width: 9px; height: 9px; border-radius: 99px; flex-shrink: 0; }
.idxrow .nm { font-size: 13.5px; font-weight: 600; flex: 1; min-width: 0; word-break: break-word; }
.idxrow .mt { font-size: 11px; color: var(--sub); white-space: nowrap; }
.idxrow .arr { color: var(--sub); font-size: 14px; }
.back { background: none; border: none; color: var(--sub); font-size: 12px; font-weight: 600; cursor: pointer; padding: 0; margin: 0 0 12px; }
.back:hover { color: var(--txt); }
.yaml { max-height: none; max-width: 880px; }
.shotwrap { position: relative; display: block; }
.mark { position: absolute; pointer-events: none; }
.mark.tap { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--fail); border-radius: 99px; background: rgba(248,71,82,.25); box-shadow: 0 0 0 1px rgba(0,0,0,.5); }
.mark.assertok { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--pass); border-radius: 99px; background: rgba(46,204,92,.22); }
.markborder { position: absolute; inset: 0; border: 3px solid var(--fail); border-radius: 6px; pointer-events: none; }
svg.swipe { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.gal { display: flex; flex-wrap: wrap; gap: 12px; }
.galcell { width: 150px; cursor: zoom-in; }
.galcell img { width: 100%; border: 1px solid var(--line2); border-radius: 6px; display: block; background: #000; }
.galcell .cap { font-size: 11px; color: var(--sub); margin-top: 4px; line-height: 1.35; word-break: break-word; }
.logpane { border: 1px solid var(--line); border-radius: 8px; background: var(--bg); max-height: 72vh; overflow: auto; margin-top: 8px; }
.logpane .ln { display: flex; gap: 10px; padding: 1px 11px; font: 11.5px/1.6 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; white-space: pre-wrap; word-break: break-word; border-top: 1px solid rgba(255,255,255,.04); }
.logpane .ln:first-child { border-top: none; }
.logpane .ln.e { color: #ffb3b8; } .logpane .ln.w { color: var(--amber); }
.logpane.net .ln span:first-child { font-weight: 700; min-width: 46px; }
.logpane.net .m { color: var(--sub); min-width: 96px; }
.evchips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.evchip { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 999px; padding: 4px 10px; font-size: 11.5px; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
.evchip:hover { border-color: var(--run); }
.evchip.on { border-color: var(--run); background: var(--bg2); }
.evchip .c { color: var(--sub); font: 10.5px ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.logpane.ev .m { color: var(--sub); min-width: 62px; }
.video { max-width: 640px; }
.vframe { height: min(72vh, 900px); max-width: 100%; aspect-ratio: 1/2; background-repeat: no-repeat; background-color: #000; border: 1px solid var(--line2); border-radius: 6px; margin-top: 10px; }
.vctl { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.vctl input[type=range] { flex: 1; accent-color: var(--run); }
.tlrail { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 12px; }
.tlrail .tldot { width: 9px; height: 9px; border-radius: 99px; cursor: pointer; opacity: .55; flex-shrink: 0; }
.tlrail .tldot:hover { opacity: 1; }
.tlrail .tldot.cur { opacity: 1; outline: 2px solid var(--txt); outline-offset: 1px; }
.step .ts { margin-left: auto; flex-shrink: 0; color: var(--sub); font-size: 10.5px; text-align: right; }
.step .ts .dur { display: block; color: var(--sub); opacity: .8; }
.lfilter { display: flex; align-items: center; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
.lfilter input { background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 10px; font-size: 12.5px; min-width: 220px; }
.lfilter input:focus { outline: none; border-color: var(--run); }
.lfilter .count { font-size: 11px; color: var(--sub); margin-left: auto; }
.badge.selfheal { background: rgba(166,121,245,.16); color: var(--purple); }
.zoom .zoomwrap { position: relative; }
.zoom .zoomwrap img { display: block; }
`;

// The standalone viewer. This function is serialized via .toString() and embedded; it must be
// fully self-contained (no references to outer scope) and read its data from #run-data. Plain
// DOM only — no React, no external scripts — so the exported file runs offline anywhere.
function RUN_REPORT_VIEWER(): void {
  const RAW: Partial<ReportPayload> = window.__TB_RUN_DATA__ || {};
  const root = document.getElementById('app') as HTMLElement;
  const esc = (s: unknown) => String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));

  // Normalize to a sessions[] array. New reports embed { generatedAt, sessions:[...] }; tolerate the
  // older single-run shape ({ meta, trace, llm, shots }) so previously-exported files still open.
  const SESSIONS: SessionPayload[] = (RAW.sessions && RAW.sessions.length)
    ? RAW.sessions
    : [{ meta: RAW.meta || {}, trace: RAW.trace || [], llm: RAW.llm || [], shots: RAW.shots || {}, recordingYaml: (RAW.meta && RAW.meta.recordingYaml) || null }];
  const MULTI = SESSIONS.length > 1;
  const generatedAt = RAW.generatedAt || (SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta.generatedAt) || '';

  // `D` is the session currently in view; every renderer reads D.trace / D.llm / D.shots / D.meta /
  // D.recordingYaml, so the single-run renderers below are unchanged across a session switch.
  let D: SessionPayload = SESSIONS[0];
  const st = { view: MULTI ? 'index' : 'detail', session: 0, tab: 'timeline', step: 0, llmSel: 0, evStream: 0, playing: false, vSpeed: 1 };
  const TIMELINE_PLAY_MS = 900; // per-step dwell when auto-playing the screenshot timeline
  // Timeline screenshot-playback timer. Declared up here (before openSession, which stops it) so the
  // init-time openSession() call for a single-session report doesn't hit a temporal-dead-zone ref.
  let timelineTimer = null;
  const stopTimeline = () => { if (timelineTimer) { clearInterval(timelineTimer); timelineTimer = null; } st.playing = false; };

  // Open a session's detail. A FAILED run defaults the timeline to its first failed step (mirrors
  // the live page); a passed run starts at step 1 — it may still contain failed rows (an assertion
  // poll that failed before the agent recovered), and jumping to one would make a green run open on
  // a red screen.
  const openSession = (i) => {
    stopTimeline(); st.session = i; D = SESSIONS[i]; st.view = 'detail'; st.tab = 'timeline'; st.llmSel = 0; st.evStream = 0;
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    const firstFail = runFailed ? D.trace.findIndex((t) => !t.ok) : -1;
    st.step = firstFail >= 0 ? D.trace[firstFail].i : ((D.trace[0] && D.trace[0].i) || 0);
  };
  if (!MULTI) openSession(0);

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
    const at = idxOf(i);
    // Resolve a row to its inlined screenshot — but only if the image is actually present in
    // D.shots. A screenshotFile whose inline failed (the Share path skips failed fetches;
    // run-report-cli skips files dataUri() can't read) must NOT short-circuit the fallbacks and
    // leave the pane empty.
    const shot = (r) => (r && r.screenshotFile && D.shots[r.screenshotFile]) ? D.shots[r.screenshotFile] : null;
    // 1. The row's own frame — the screen it acted on (action/tool rows carry their pre-action frame).
    let s = shot(D.trace[at]);
    if (s) return s;
    // 2. Screenshot-less rows (step/objective headers, agent-reasoning turns) show the NEXT frame —
    // the screen this step is about to act on. Bounded to THIS step: stop at the next objective
    // header so a frameless middle step never previews a future step's screen.
    for (let k = at + 1; k < D.trace.length && !D.trace[k].objective; k++) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    // 3. Nothing usable ahead in this step: fall back to the nearest earlier frame so the pane is
    // never empty.
    for (let k = at - 1; k >= 0; k--) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    return null;
  };

  // The report-time action overlay on a step's screenshot: a tap/long-press dot, a swipe arrow, an
  // assertion ok-dot, or a failed-assertion red border. Positioned by device-pixel ratio over an
  // <img> that's width:100% and preserves the screenshot's aspect, so percentages map directly.
  const markHtml = (t) => {
    const mk = t.mark;
    if (!mk) return '';
    if (mk.kind === 'swipe') {
      return `<svg class="swipe" viewBox="0 0 ${mk.dw} ${mk.dh}" preserveAspectRatio="none">
        <defs><marker id="ah${t.i}" markerWidth="5" markerHeight="5" refX="2.5" refY="2.5" orient="auto"><path d="M0,0 L5,2.5 L0,5 Z" fill="#5e9bff"/></marker></defs>
        <line x1="${mk.x1}" y1="${mk.y1}" x2="${mk.x2}" y2="${mk.y2}" stroke="#5e9bff" stroke-width="6" marker-end="url(#ah${t.i})" /></svg>`;
    }
    // A failed assertion gets the red full-screen border (matches the old report's
    // ScreenshotAnnotation), keyed off the action's own `succeeded` flag.
    if (mk.kind === 'assert' && mk.ok === false) return `<div class="markborder"></div>`;
    const left = (mk.x / mk.dw) * 100;
    const top = (mk.y / mk.dh) * 100;
    const cls = mk.kind === 'assert' ? 'assertok' : 'tap';
    return `<div class="mark ${cls}" style="left:${left}%;top:${top}%"></div>`;
  };

  // Group flat trace under objective rows -> { header, num, items } (same shape as the app's StepStack).
  const groupTrace = () => {
    const gs = []; let cur = null; let n = 0;
    for (const t of D.trace) {
      if (t.objective) { cur = { header: t, num: ++n, items: [] }; gs.push(cur); }
      else { if (!cur) { cur = { header: null, num: 0, items: [] }; gs.push(cur); } cur.items.push(t); }
    }
    return gs;
  };

  // First wall-clock timestamp in the trace — the run-clock zero every row's elapsed offset is
  // measured from (parity with the legacy report's elapsed-from-session-start gutter).
  const traceT0 = () => { for (const t of D.trace) { if (t.ts != null) return t.ts; } return null; };
  const fmtDur = (ms) => !ms ? '' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;

  const stepRowHtml = (t, child) => {
    const cat = stepCat(t); const sel = t.i === st.step;
    const kids = (t.children || []).length
      ? `<div class="kids">${t.children.map((c) => `<div><span class="mono">${esc(c.label)}</span> <span class="kt mono">${esc(c.tool)}</span></div>`).join('')}</div>` : '';
    const count = t.count ? ` <span class="mono" style="color:var(--sub)">×${t.count}</span>` : '';
    const t0 = traceT0();
    const rel = (t.ts != null && t0 != null) ? `+${((t.ts - t0) / 1000).toFixed(1)}s` : '';
    const dur = fmtDur(t.ms);
    const time = (rel || dur) ? `<span class="ts mono">${rel}${dur ? `<span class="dur">${dur}</span>` : ''}</span>` : '';
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
      ${time}
    </div>`;
  };

  const renderTimeline = () => {
    if (!D.trace.length) return `<div class="empty">This run didn't emit any agent-task steps.</div>`;
    const groups = groupTrace();
    const hasSteps = groups.some((g) => g.header);
    let stepsHtml;
    if (!hasSteps) {
      stepsHtml = D.trace.map((t) => stepRowHtml(t, false)).join('');
    } else {
      stepsHtml = groups.map((g) => {
        // The header dot reports the OBJECTIVE's outcome (from its Complete bookend), not the worst
        // row inside it: an assertion poll can fail mid-step and the agent still recover, and the
        // legacy report shows that step green. Headerless groups fall back to their rows.
        const failed = g.header ? !g.header.ok : g.items.some((t) => !t.ok);
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
    const t0 = traceT0();
    const curTime = [
      (cur.ts != null && t0 != null) ? `at +${((cur.ts - t0) / 1000).toFixed(1)}s` : '',
      fmtDur(cur.ms),
    ].filter(Boolean).join(' · ');
    const detail = `<div class="detail">
        <div class="lbl">${esc(cur.label)}</div>
        ${cur.tool ? `<div class="tl-tool mono">${esc(cur.tool)}</div>` : ''}
        ${cur.note ? `<div class="tl-tool">${esc(cur.note)}</div>` : ''}
        ${curTime ? `<div class="tl-tool mono">${curTime}</div>` : ''}
        ${!cur.ok && cur.err ? `<div class="err">${esc(cur.err)}</div>` : ''}
      </div>`;
    // One dot per step under the preview — the run at a glance (color = row category), click to jump.
    const rail = `<div class="tlrail">${D.trace.map((t) =>
      `<span class="tldot${t.i === st.step ? ' cur' : ''}" data-step="${t.i}" title="${t.i}. ${esc(t.label)}" style="background:${catColor[stepCat(t)]}"></span>`).join('')}</div>`;
    return `<div class="tl">
      <div><div class="eyebrow">${D.trace.length} step${D.trace.length === 1 ? '' : 's'}</div><div class="steps">${stepsHtml}</div></div>
      <div class="preview">
        ${shot ? `<div class="shotwrap"><img class="shot" id="shot" src="${shot}" alt="Step ${pos + 1}" />${cur.screenshotFile ? markHtml(cur) : ''}</div>` : `<div class="noshot">No screenshot captured before this step.</div>`}
        ${rail}
        <div class="pvctl">
          <button class="btn" id="prev"${pos <= 0 ? ' disabled' : ''}>&larr; Prev</button>
          <button class="btn play" id="tlplay">${st.playing ? '⏸ Pause' : '▶ Play'}</button>
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
    const totals = D.llm.reduce((a, r) => ({ i: a.i + (r.inputTokens || 0), o: a.o + (r.outputTokens || 0), c: a.c + (r.totalCost || 0), k: a.k + (r.cacheReadTokens || 0), d: a.d + (r.durationMs || 0) }), { i: 0, o: 0, c: 0, k: 0, d: 0 });
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
        <div><div class="n mono">${fmtCost(totals.c)}</div><div class="t">total cost</div></div>
        ${totals.k ? `<div><div class="n mono">${fmtN(totals.k)} <span style="font-weight:500;color:var(--sub)">(${Math.round((totals.k / (totals.i || 1)) * 100)}%)</span></div><div class="t">cached input</div></div>` : ''}
        ${totals.d ? `<div><div class="n mono">${(totals.d / D.llm.length / 1000).toFixed(1)}s</div><div class="t">avg response</div></div>` : ''}</div></div>
        <div style="margin-top:12px">${list}</div></div>
      <div>${detail}</div>
    </div>`;
  };

  // Screenshot gallery: every step that has a screenshot, as a clickable thumbnail with its label.
  const renderGrid = () => {
    const cells = D.trace.filter((t) => t.screenshotFile && D.shots[t.screenshotFile]).map((t) =>
      `<div class="galcell" data-shot="${esc(t.screenshotFile)}">
        <img src="${D.shots[t.screenshotFile]}" alt="${esc(t.label)}" />
        <div class="cap">${t.i}. ${esc(t.label)}</div>
      </div>`).join('');
    return cells ? `<div class="gal">${cells}</div>` : `<div class="empty">No screenshots captured for this run.</div>`;
  };

  // Severity class for a logcat line. Reads the logcat level token (`E/Tag…` brief form or a
  // standalone `E` column in threadtime form), falling back to crash keywords. Heuristic, but
  // tighter than a bare letter match — used only for row coloring.
  const logLevelClass = (l) => {
    const m = l.match(/(?:^|\s)([VDIWEF])[\/\s]/);
    const lvl = m ? m[1] : '';
    if (lvl === 'E' || lvl === 'F' || /\b(FATAL|ANR)\b|Exception/.test(l)) return 'e';
    if (lvl === 'W' || /\bWARN\b/.test(l)) return 'w';
    return '';
  };

  const renderDevice = () => {
    if (!D.deviceLog) return `<div class="empty">No device log captured.</div>`;
    const lines = D.deviceLog.split('\n');
    const html = lines.map((l) => `<div class="ln ${logLevelClass(l)}">${esc(l)}</div>`).join('');
    return `<div class="eyebrow">Device log · ${lines.length} lines</div>
      <div class="lfilter" id="dlbar"><input id="dlq" type="search" placeholder="Filter log lines…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="w">Warn+</button>
        <button class="evchip" data-lvl="e">Errors</button>
        <span class="count" id="dlcount"></span></div>
      <div class="logpane" id="dlpane">${html}</div>`;
  };

  const renderNetwork = () => {
    if (!D.network || !D.network.length) return `<div class="empty">No network activity captured.</div>`;
    const rows = D.network.map((e) => {
      const fail = e.phase === 'FAILED' || (e.statusCode != null && e.statusCode >= 400);
      const status = e.phase === 'FAILED' ? 'FAILED' : (e.statusCode != null ? String(e.statusCode) : (e.phase === 'REQUEST_START' ? '→' : ''));
      const dur = e.durationMs != null ? ` ${e.durationMs}ms` : '';
      return `<div class="ln ${fail ? 'e' : ''}"><span>${esc(e.method)}</span><span class="m">${esc(status)}${esc(dur)}</span><span>${esc(e.urlPath)}</span></div>`;
    }).join('');
    return `<div class="eyebrow">Network · ${D.network.length} events</div>
      <div class="lfilter" id="nlbar"><input id="nlq" type="search" placeholder="Filter by method, path, status…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="e">Failed</button>
        <span class="count" id="nlcount"></span></div>
      <div class="logpane net" id="nlpane">${rows}</div>`;
  };

  // Shared text + severity filtering for the device/network log panes. Filters rows in place
  // (display:none) rather than re-rendering, so typing keeps input focus and stays fast on
  // thousands of lines. Severity rides on the row's coloring class: 'e' = error, 'w' = warning.
  const wireLogFilter = (paneId: string, inputId: string, barId: string, countId: string) => {
    const pane = document.getElementById(paneId);
    if (!pane) return;
    const input = document.getElementById(inputId) as HTMLInputElement | null;
    const countEl = document.getElementById(countId);
    const chips = Array.from(root.querySelectorAll<HTMLElement>(`#${barId} [data-lvl]`));
    let lvl = '';
    const apply = () => {
      const q = ((input && input.value) || '').toLowerCase();
      let shown = 0;
      for (const r of Array.from(pane.children) as HTMLElement[]) {
        const okLvl = !lvl || (lvl === 'e' ? r.classList.contains('e') : (r.classList.contains('e') || r.classList.contains('w')));
        const okQ = !q || r.textContent.toLowerCase().indexOf(q) >= 0;
        const on = okLvl && okQ;
        r.style.display = on ? '' : 'none';
        if (on) shown++;
      }
      if (countEl) countEl.textContent = `${shown} shown`;
    };
    if (input) input.oninput = apply;
    chips.forEach((c) => c.onclick = () => { lvl = c.dataset.lvl; chips.forEach((x) => x.classList.toggle('on', x === c)); apply(); });
    apply();
  };

  // Generic session-events tab: one selectable stream per `events/<name>.<style>.ndjson` producer,
  // each event shown at its offset from the stream's first event so a producer's activity reads on
  // the run clock. Mirrors the producer-agnostic JVM SessionEventsReader; the renderer knows nothing
  // about any specific producer.
  const renderEvents = () => {
    const streams = D.events;
    if (!streams || !streams.length) return `<div class="empty">No events captured.</div>`;
    const sel = Math.min(Math.max(st.evStream || 0, 0), streams.length - 1);
    const chips = streams.map((s, i) =>
      `<button class="evchip ${i === sel ? 'on' : ''}" data-evstream="${i}">${esc(s.name)}<span class="c">${s.total}</span></button>`).join('');
    const cur = streams[sel];
    const t0 = cur.events.length && cur.events[0].t != null ? cur.events[0].t : null;
    const rows = cur.events.map((e) => {
      const rel = (e.t != null && t0 != null) ? `+${((e.t - t0) / 1000).toFixed(2)}s` : '';
      return `<div class="ln"><span class="m">${esc(rel)}</span><span>${esc(e.d)}</span></div>`;
    }).join('');
    const totalEvents = streams.reduce((a, s) => a + (s.total || 0), 0);
    const trunc = cur.truncated ? ` · showing last ${cur.events.length} of ${cur.total}` : '';
    return `<div class="eyebrow">Events · ${streams.length} stream${streams.length === 1 ? '' : 's'} · ${totalEvents} total${trunc}</div>
      <div class="evchips">${chips}</div>
      <div class="logpane ev">${rows}</div>`;
  };

  // Video playback over the embedded sprite sheet — pure CSS background-position scrubbing, no decode
  // step. Frame layout + range are precomputed (D.video); wireVideo() drives play/seek.
  const renderVideo = () => {
    const v = D.video;
    if (!v) return `<div class="empty">No video frames captured for this run.</div>`;
    const total = v.endFrame - v.startFrame + 1;
    // Controls ABOVE the frame (the frame is device-tall; controls below it would sit under the
    // fold), frame height-capped to the viewport — matching the legacy player's always-visible
    // transport with elapsed/total time and a playback-speed toggle.
    return `<div class="video">
      <div class="eyebrow">Video · ${total} frame${total === 1 ? '' : 's'} @ ${v.fps}fps</div>
      <div class="vctl">
        <button class="btn play" id="vplay">▶ Play</button>
        <input type="range" id="vseek" min="0" max="${total - 1}" value="0" />
        <span class="count mono" id="vpos">0.0s / ${(total / v.fps).toFixed(1)}s</span>
        <button class="btn" id="vspeed" title="Playback speed">${st.vSpeed}×</button>
      </div>
      <div class="vframe" id="vframe" style="background-image:url('${v.sprite}')"></div>
    </div>`;
  };

  const renderInfo = () => {
    const m = D.meta;
    const rows = [['Target', m.target], ['App', m.appId], ['App version', m.appVersion], ['Device', m.device], ['Platform', m.platform], ['Trail', m.trailId], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt], ['Duration', m.duration]]
      .filter(([, v]) => v).map(([k, v]) => `<div class="r"><span class="k">${k}</span><span class="v">${esc(v)}</span></div>`).join('');
    return `<div>
      ${m.cmd ? `<div class="eyebrow">Rerun this in the CLI</div><div class="cmd"><pre class="mono" id="cmd">${esc(m.cmd)}</pre><button class="btn" id="copycmd">Copy</button></div>` : ''}
      <div class="eyebrow" style="margin-top:22px">Run details</div><div class="rows">${rows}</div>
    </div>`;
  };

  const renderRecording = () => {
    if (!D.recordingYaml) return `<div class="empty">No recorded trail (.trail.yaml) for this run.</div>`;
    return `<div class="eyebrow">Recorded trail (.trail.yaml)</div>
      <div class="cmd"><pre class="mono yaml" id="yaml">${esc(D.recordingYaml)}</pre><button class="btn" id="copyyaml">Copy</button></div>`;
  };

  const isPass = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'passed' || v === 'success'; };
  const isFail = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'failed' || v === 'error'; };

  // The multi-session landing page: a pass/fail tally and one row per run that drills into its detail.
  // Cancelled/running/unknown runs are neither passed nor failed — they go in an "other" bucket
  // (shown only when non-empty) so the headline tally agrees with the per-row status dots.
  const renderIndex = () => {
    const pass = SESSIONS.filter(isPass).length;
    const fail = SESSIONS.filter(isFail).length;
    const other = SESSIONS.length - pass - fail;
    const rows = SESSIONS.map((s, i) => {
      const status = String((s.meta && s.meta.status) || 'unknown').toLowerCase();
      const dot = isPass(s) ? 'var(--pass)' : isFail(s) ? 'var(--fail)' : 'var(--run)';
      const sub = [s.meta.platform, s.meta.device, s.meta.steps ? s.meta.steps + ' steps' : null, s.meta.duration].filter(Boolean).map(esc).join(' · ');
      return `<div class="idxrow" data-session="${i}">
          <span class="dot" style="background:${dot}"></span>
          <div class="nm">${esc(s.meta.title || ('Run ' + (i + 1)))}${sub ? `<div class="mt" style="font-weight:400;margin-top:3px">${sub}</div>` : ''}</div>
          <span class="badge ${esc(status)}">${esc(status)}</span>${s.meta.selfHeal ? `<span class="badge selfheal">self-heal</span>` : ''}
          <span class="arr">→</span>
        </div>`;
    }).join('');
    return `<div class="idxsum">
        <div><div class="n">${SESSIONS.length}</div><div class="t">runs</div></div>
        <div><div class="n" style="color:var(--pass)">${pass}</div><div class="t">passed</div></div>
        <div><div class="n" style="color:var(--fail)">${fail}</div><div class="t">failed</div></div>
        ${other ? `<div><div class="n" style="color:var(--run)">${other}</div><div class="t">other</div></div>` : ''}
      </div>
      <div class="idx">${rows}</div>`;
  };

  const render = () => {
    if (st.view === 'index') {
      root.innerHTML = `
        <header><div class="title-row"><h1>${SESSIONS.length} Trailblaze runs</h1></div></header>
        <main>${renderIndex()}</main>
        <footer><span>Exported from Trailblaze · ${esc(generatedAt)}</span></footer>`;
      wire();
      return;
    }
    const m = D.meta;
    const hasShots = D.trace.some((t) => t.screenshotFile && D.shots[t.screenshotFile]);
    const tabs = [
      ['timeline', 'Timeline'],
      ...(hasShots ? [['grid', 'Grid']] : []),
      ...(D.video ? [['video', 'Video']] : []),
      ...(D.llm.length ? [['llm', `LLM (${D.llm.length})`]] : []),
      ...(D.recordingYaml ? [['recording', 'Recording']] : []),
      ...(D.deviceLog ? [['device', 'Device logs']] : []),
      ...(D.network && D.network.length ? [['network', 'Network']] : []),
      ...(D.events && D.events.length ? [['events', 'Events']] : []),
      ['info', 'Info'],
    ];
    const body = st.tab === 'timeline' ? renderTimeline()
      : st.tab === 'grid' ? renderGrid()
      : st.tab === 'video' ? renderVideo()
      : st.tab === 'llm' ? renderLlm()
      : st.tab === 'recording' ? renderRecording()
      : st.tab === 'device' ? renderDevice()
      : st.tab === 'network' ? renderNetwork()
      : st.tab === 'events' ? renderEvents()
      : renderInfo();
    const metaItems = [['Target', m.target], ['App version', m.appVersion], ['Device', m.device], ['Duration', m.duration], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt]]
      .filter(([, v]) => v).map(([k, v]) => `<div><div class="k">${k}</div><div class="v">${esc(v)}</div></div>`).join('');
    root.innerHTML = `
      <header>
        ${MULTI ? `<button class="back" data-back>← All runs</button>` : ''}
        <div class="title-row"><h1>${esc(m.title)}</h1><span class="badge ${esc(m.status)}">${esc(m.status)}</span>${m.selfHeal ? `<span class="badge selfheal">self-heal</span>` : ''}</div>
        <div class="meta">${metaItems}</div>
        ${m.error ? `<div class="errbanner">${esc(m.error)}</div>` : ''}
        <nav>${tabs.map(([id, l]) => `<button class="${st.tab === id ? 'active' : ''}" data-tab="${id}">${l}</button>`).join('')}</nav>
      </header>
      <main>${body}</main>
      <footer><span>Exported from Trailblaze · ${esc(generatedAt)}</span></footer>`;
    wire();
  };

  let zoomEl = null;
  let videoTimer = null;
  const stopVideo = () => { if (videoTimer) { clearInterval(videoTimer); videoTimer = null; } };
  // Timeline screenshot playback (stopTimeline/timelineTimer declared near st): "▶ Play" walks
  // st.step forward one trace step at a time so the per-step screenshots scrub like a slideshow
  // (parity with the in-app timeline / the legacy WASM report). Each tick re-renders; the timer lives
  // outside the render/wire lifecycle so a tick's own re-render doesn't cancel it (only explicit stop
  // / navigation does).
  // Build the zoom overlay via DOM APIs (not innerHTML) — the image src is a data: URI but we never
  // reinterpret any value as HTML here.
  // `markup` is the step's action-mark overlay (markHtml) so the zoomed view keeps the tap dot /
  // swipe arrow — it's built from numeric coordinates only, never from user strings, so inserting
  // it as HTML is safe.
  const openZoom = (src: string, markup?: string) => {
    zoomEl = document.createElement('div'); zoomEl.className = 'zoom';
    const wrap = document.createElement('div'); wrap.className = 'zoomwrap';
    const big = document.createElement('img'); big.src = src; big.alt = 'screenshot';
    wrap.appendChild(big);
    if (markup) wrap.insertAdjacentHTML('beforeend', markup);
    zoomEl.appendChild(wrap);
    zoomEl.onclick = () => { zoomEl.remove(); zoomEl = null; };
    document.body.appendChild(zoomEl);
  };
  const wire = () => {
    stopVideo(); // a re-render replaces the video element; drop any running playback timer.
    if (st.tab !== 'timeline') stopTimeline(); // playback only lives on the timeline tab
    root.querySelectorAll<HTMLElement>('[data-session]').forEach((el) => el.onclick = () => { openSession(+el.dataset.session); render(); });
    const backBtn = root.querySelector<HTMLElement>('[data-back]'); if (backBtn) backBtn.onclick = () => { stopTimeline(); st.view = 'index'; render(); };
    root.querySelectorAll<HTMLElement>('[data-tab]').forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; render(); });
    root.querySelectorAll<HTMLElement>('[data-step]').forEach((el) => el.onclick = () => { stopTimeline(); st.step = +el.dataset.step; render(); });
    root.querySelectorAll<HTMLElement>('[data-llm]').forEach((el) => el.onclick = () => { st.llmSel = +el.dataset.llm; render(); });
    root.querySelectorAll<HTMLElement>('[data-evstream]').forEach((el) => el.onclick = () => { st.evStream = +el.dataset.evstream; render(); });
    root.querySelectorAll<HTMLElement>('[data-shot]').forEach((el) => el.onclick = () => { const s = D.shots[el.dataset.shot]; if (s) openZoom(s); });
    const prev = document.getElementById('prev'); const next = document.getElementById('next');
    if (prev) prev.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p > 0) { st.step = D.trace[p - 1].i; render(); } };
    if (next) next.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) { st.step = D.trace[p + 1].i; render(); } };
    const tlplay = document.getElementById('tlplay');
    if (tlplay) tlplay.onclick = () => {
      if (timelineTimer) { stopTimeline(); render(); return; }
      if (idxOf(st.step) >= D.trace.length - 1) st.step = D.trace[0].i; // restart from the top if parked at the end
      st.playing = true;
      timelineTimer = setInterval(() => {
        const p = idxOf(st.step);
        if (p >= D.trace.length - 1) { stopTimeline(); render(); return; }
        st.step = D.trace[p + 1].i; render();
      }, TIMELINE_PLAY_MS);
      render();
    };
    // While playing, keep the advancing step in view in the step list.
    if (st.playing) { const selEl = root.querySelector('.step.sel'); if (selEl && selEl.scrollIntoView) selEl.scrollIntoView({ block: 'nearest' }); }
    // Zoom from the data model (the step's screenshot data URI), not by reading the rendered
    // <img>'s src back out of the DOM. The zoomed view keeps the step's tap/swipe mark.
    const shotSrc = shotForStep(st.step);
    const shot = document.getElementById('shot');
    if (shot && shotSrc) shot.onclick = () => {
      const cur = D.trace.find((t) => t.i === st.step);
      openZoom(shotSrc, cur && cur.screenshotFile ? markHtml(cur) : '');
    };
    if (st.tab === 'video') wireVideo();
    if (st.tab === 'device') wireLogFilter('dlpane', 'dlq', 'dlbar', 'dlcount');
    if (st.tab === 'network') wireLogFilter('nlpane', 'nlq', 'nlbar', 'nlcount');
    const copycmd = document.getElementById('copycmd');
    if (copycmd) copycmd.onclick = () => { try { navigator.clipboard.writeText(D.meta.cmd); copycmd.textContent = 'Copied'; setTimeout(() => { copycmd.textContent = 'Copy'; }, 1500); } catch (e) {} };
    const copyyaml = document.getElementById('copyyaml');
    if (copyyaml) copyyaml.onclick = () => { try { navigator.clipboard.writeText(D.recordingYaml); copyyaml.textContent = 'Copied'; setTimeout(() => { copyyaml.textContent = 'Copy'; }, 1500); } catch (e) {} };
  };

  // Drive the video sprite scrubber: map the logical-frame index to a grid cell and show it via CSS
  // background-position (no per-frame image fetch). Per-frame width comes from the sprite's natural
  // width / columns, read once on load to set the frame box aspect ratio.
  const wireVideo = () => {
    const v = D.video;
    const box = document.getElementById('vframe');
    if (!v || !box) return;
    const total = v.endFrame - v.startFrame + 1;
    const seek = document.getElementById('vseek') as HTMLInputElement | null;
    const posEl = document.getElementById('vpos');
    const playBtn = document.getElementById('vplay');
    const speedBtn = document.getElementById('vspeed');
    const show = (k) => {
      const kk = Math.max(0, Math.min(total - 1, k));
      const logical = v.startFrame + kk;
      const physical = (v.frameMap[logical] != null) ? v.frameMap[logical] : logical;
      const col = Math.floor(physical / v.rows);
      const row = physical % v.rows;
      box.style.backgroundSize = `${v.columns * 100}% ${v.rows * 100}%`;
      box.style.backgroundPosition = `${v.columns > 1 ? (col / (v.columns - 1)) * 100 : 0}% ${v.rows > 1 ? (row / (v.rows - 1)) * 100 : 0}%`;
      if (posEl) posEl.textContent = `${(kk / v.fps).toFixed(1)}s / ${(total / v.fps).toFixed(1)}s`;
      if (seek && +seek.value !== kk) seek.value = String(kk);
    };
    const img = new Image();
    img.onload = () => { const fw = img.naturalWidth / v.columns; if (fw > 0 && v.frameHeight > 0) box.style.aspectRatio = `${fw} / ${v.frameHeight}`; show(seek ? +seek.value : 0); };
    img.src = v.sprite;
    show(seek ? +seek.value : 0);
    const startPlayback = () => {
      stopVideo();
      videoTimer = setInterval(() => {
        const k = seek ? +seek.value : 0;
        show(k + 1 >= total ? 0 : k + 1);
      }, 1000 / (v.fps * st.vSpeed));
    };
    if (seek) seek.oninput = () => { stopVideo(); if (playBtn) playBtn.textContent = '▶ Play'; show(+seek.value); };
    if (playBtn) playBtn.onclick = () => {
      if (videoTimer) { stopVideo(); playBtn.textContent = '▶ Play'; return; }
      playBtn.textContent = '⏸ Pause';
      startPlayback();
    };
    // Playback-speed toggle (0.5× → 1× → 2× → 4×), multiplying the frame clock — parity with the
    // legacy player's speed control. Takes effect immediately when already playing.
    if (speedBtn) speedBtn.onclick = () => {
      const speeds = [0.5, 1, 2, 4];
      st.vSpeed = speeds[(speeds.indexOf(st.vSpeed) + 1) % speeds.length];
      speedBtn.textContent = `${st.vSpeed}×`;
      if (videoTimer) startPlayback();
    };
  };

  document.addEventListener('keydown', (e) => {
    if (zoomEl && e.key === 'Escape') { zoomEl.remove(); zoomEl = null; return; }
    // Space toggles playback on the video tab too (parity with the legacy player's spacebar).
    if (st.view === 'detail' && st.tab === 'video' && e.key === ' ') { e.preventDefault(); const b = document.getElementById('vplay'); if (b) b.click(); return; }
    if (st.view !== 'detail' || st.tab !== 'timeline' || !D.trace.length) return;
    if (e.key === 'ArrowLeft') { stopTimeline(); const p = idxOf(st.step); if (p > 0) { st.step = D.trace[p - 1].i; render(); } }
    if (e.key === 'ArrowRight') { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) { st.step = D.trace[p + 1].i; render(); } }
    if (e.key === ' ') { e.preventDefault(); const b = document.getElementById('tlplay'); if (b) b.click(); } // space toggles play/pause
  });

  render();
}

// Export to the browser global scope (classic script) and to bun/node (require).
const RUN_REPORT_EXPORTS = {
  truncate, logClass, extractTrace, toolChildren, describeAction, parseLlmResponse, extractLlmLogs,
  stepText, toolDetail, summarizeToolArgs, describeSelector,
  slimTraceForShare, slimLlmForShare, buildRunReportHtml, buildMultiReportHtml, RUN_REPORT_CSS, RUN_REPORT_VIEWER,
};
if (typeof window !== 'undefined') Object.assign(window, RUN_REPORT_EXPORTS);
if (typeof module !== 'undefined' && module.exports) module.exports = RUN_REPORT_EXPORTS;

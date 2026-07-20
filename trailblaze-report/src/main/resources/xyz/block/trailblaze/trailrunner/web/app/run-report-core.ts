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

// Return the immutable trail source captured when the session started. Reading this from the raw
// log keeps browser exports aligned with headless reports even if the trail is edited after the run.
function originalYamlFromLogs(logs: TrailblazeLogRecord[] | null | undefined): string | null {
  for (const log of logs || []) {
    const rawYaml = log && log.sessionStatus && log.sessionStatus.rawYaml;
    if (typeof rawYaml === 'string' && rawYaml.trim()) return rawYaml;
  }
  return null;
}

// Preserve one exact top-level YAML block (including its key) without parsing/re-emitting the
// document. The report uses this for dedicated Config views, where showing the authored bytes is
// more useful than a normalized object and works for both unified (`config:`) and v1 (`- config:`)
// trail shapes.
function yamlRootSection(yaml: string | null | undefined, key: string): string | null {
  if (!yaml || !yaml.trim()) return null;
  const lines = yaml.replace(/\r\n/g, '\n').split('\n');
  const wanted = new RegExp(`^(?:-\\s+)?${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:`);
  const root = /^(?:-\s+)?[A-Za-z_][\w-]*\s*:/;
  const start = lines.findIndex((line) => wanted.test(line));
  if (start < 0) return null;
  let end = start + 1;
  while (end < lines.length && !root.test(lines[end])) end++;
  return lines.slice(start, end).join('\n').trimEnd();
}

function localRunAgentPrompt(meta: RunMeta | null | undefined): string | null {
  if (!meta || !meta.cmd) return null;
  const context = [
    meta.title ? `Test: ${meta.title}` : null,
    meta.trailId ? `Trail: ${meta.trailId}` : null,
    meta.target ? `Target: ${meta.target}` : null,
    meta.platform ? `Platform: ${meta.platform}` : null,
  ].filter(Boolean).join('\n');
  const trail = meta.trailId ? `the ${meta.trailId} trail` : 'the same trail';
  return `Run this Trailblaze test locally and report the result.\n\n${context ? `${context}\n\n` : ''}From the repository root, use either:\n- Trailblaze CLI: \`${meta.cmd}\`\n- Trail Runner: run \`./trailblaze app\`, select ${trail}, and run it.\n\nUse the same target and platform as the original run. If local setup blocks execution, diagnose it, fix it when safe, and retry the test.`;
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
    // Self-heal is an outcome of an authored objective, not another timeline action. Preserve the
    // marker on that objective so the report can distinguish the one repaired step from incidental
    // failed polling rows elsewhere in an otherwise-passing run.
    if (cls === 'SelfHealInvokedLog') {
      closeGroup(); asserts = new Map();
      const prompt = stepText(log.promptStep);
      const healed = [...out].reverse().find((row) => row.objective && (!prompt || row.label === truncate(prompt, 120))) || objRow;
      if (healed) {
        const result = log.recordingResult || {};
        const failedTool = result.failedTool || {};
        const failure = result.failureResult || {};
        const failureType = String(failure.class || '').split('.').pop();
        const message = failure.errorMessage || failure.message || 'Recorded actions could not complete this step.';
        const stack = failure.stackTrace || failure.stackTraceString || '';
        healed.selfHeal = true;
        healed.selfHealTool = failedTool.toolName || failedTool.name || summarizeToolArgs(failedTool.raw || failedTool, {});
        healed.selfHealError = `${failureType ? `${failureType}: ` : ''}${message}${stack ? `\n${stack}` : ''}`;
        const source = [...out].reverse().find((row) => !row.objective && (!healed.selfHealTool || row.label === healed.selfHealTool))
          || [...out].reverse().find((row) => !row.objective && !row.ok);
        if (source) source.selfHealSource = true;
      }
      continue;
    }
    // An objective logs a Complete bookend carrying an AgentTaskStatus. On a Failure result, mark
    // the matching objective row failed (it shows red, becomes the default-selected step, and turns
    // its group header red) — this is how MCP-sampling agents record a failed step (no driver-action
    // AssertCondition). The Complete itself is otherwise a bookend and dropped.
    if (cls === 'ObjectiveCompleteLog') {
      const res = log.objectiveResult;
      const failed = res && String(res.class || '').indexOf('Failure') >= 0;
      if (failed && objRow) {
        objRow.ok = false;
        // Preserve the complete failure here. The interactive report parses the exception type,
        // message, and stack trace into separate fields; truncating at extraction time made that
        // impossible and discarded the most actionable frames.
        objRow.err = String(res.llmExplanation || log.errorMessage || 'Objective failed');
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
      // can nest the tool calls / assertions that follow under their step. `trailhead` marks the
      // objective lowered from the trail's `trailhead:` (its step 0) — the DirectionStep.isTrailhead
      // flag rides through the ObjectiveStartLog's promptStep.
      const prow = { label: truncate(promptText, 120), _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: !err, err, screenshotFile, viewHierarchy, ts, objective: isObjective, trailhead: isObjective && log.promptStep?.isTrailhead === true };
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
    trailhead: !!t.trailhead,
    selfHeal: !!t.selfHeal,
    selfHealTool: t.selfHealTool || null,
    selfHealError: t.selfHealError || null,
    selfHealSource: !!t.selfHealSource,
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
// buildMultiReportHtml so the in-app Share button (browser) and the single-run case share one data
// contract. Optional generic event streams and the authored/recorded YAML ride alongside the trace,
// LLM calls, and screenshots. Pure: no fetch, no DOM — usable identically in the browser and bun.
function buildRunReportHtml({ meta, trace, llmLogs, shots, events = null }: { meta: RunMeta; trace: RawTraceRow[]; llmLogs: RawLlmRow[]; shots: Record<string, string>; events?: EventStream[] | null }): string {
  // Recording YAML rides in on meta.recordingYaml; lift it into the dedicated session field and drop
  // it from meta so the (potentially large) string isn't embedded twice in the payload.
  const { recordingYaml = null, originalYaml = null, ...metaRest } = meta || {};
  return buildMultiReportHtml({
    generatedAt: metaRest.generatedAt || '',
    sessions: [{ meta: metaRest, trace, llmLogs, shots, recordingYaml, originalYaml, events }],
  });
}

// Assemble the full self-contained HTML document for ONE OR MORE runs. Each session carries its own
// derived trace/llmLogs, screenshot map, and (optional) recording YAML. A single session opens
// straight on that run's detail (mirroring the old WASM report's single-session auto-advance); with
// several it opens on a pass/fail session index that drills into each run. Pure: callers supply
// already-derived data; no fetch, no DOM — identical in the browser and in bun.
// Module-level helpers RUN_REPORT_VIEWER calls. They must be plain top-level `function`
// declarations (their .toString() is embedded into the standalone export's script, where the
// declaration name is what the serialized viewer resolves).
const VIEWER_HELPERS = [yamlRootSection, localRunAgentPrompt];

function buildMultiReportHtml({ generatedAt, sessions }: { generatedAt?: string; sessions: SessionInput[] }): string {
  const list: SessionPayload[] = (sessions || []).map((s) => {
    const trace = s.trace || [];
    return {
      meta: { generatedAt: generatedAt || '', ...(s.meta || {}), steps: trace.length || (s.meta && s.meta.steps) || 0 },
      trace: slimTraceForShare(trace),
      llm: slimLlmForShare(s.llmLogs),
      shots: s.shots || {},
      recordingYaml: s.recordingYaml || null,
      originalYaml: s.originalYaml || null,
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
  // The viewer ships as serialized source, so it cannot close over module scope: every module-level
  // helper it calls must have its source embedded alongside it (they resolve as script-scope
  // declarations in the export, and as ordinary module scope when RUN_REPORT_VIEWER runs in-app).
  const helpers = VIEWER_HELPERS.map((fn) => fn.toString()).join('\n');
  const viewer = RUN_REPORT_VIEWER.toString();
  const heading = list.length === 1 ? (list[0].meta.title || 'Trailblaze run') : 'Trailblaze Report';
  const title = (list.length === 1 ? heading + ' · Trailblaze run' : heading).replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${title}</title>
<script>(()=>{let theme='dark';try{const saved=localStorage.getItem('trailblaze-report-theme');theme=saved==='light'||saved==='dark'?saved:(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark')}catch(e){theme=typeof matchMedia==='function'&&matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'}document.documentElement.dataset.theme=theme})()</script>
<style>${RUN_REPORT_CSS}</style>
</head>
<body>
<div id="app"></div>
<script id="tb-run-data">window.__TB_RUN_DATA__ = ${json};</script>
<script>${helpers}
(${viewer})();</script>
</body>
</html>`;
}

const RUN_REPORT_CSS = `
:root {
  color-scheme: light;
  --neutral-1: #f7f6f2; --neutral-2: #ffffff; --neutral-3: #f2f1eb; --neutral-4: #ebe9e1;
  --neutral-5: #dfddd4; --neutral-6: #cfccc2; --neutral-7: #bbb7ab; --neutral-8: #99958b;
  --neutral-9: #76736b; --neutral-10: #5f5d57; --neutral-11: #3a3a36; --neutral-12: #111111;
  --accent-1: #f7faff; --accent-2: #f0f6ff; --accent-3: #e5efff; --accent-4: #d4e5ff;
  --accent-5: #bdd7ff; --accent-6: #9fc4fb; --accent-7: #79abf5; --accent-8: #4b8bea;
  --accent-9: #1f6feb; --accent-10: #1b63d2; --accent-11: #1857b6; --accent-12: #0d2f66;
  --cyan-3: #e6f7ff; --cyan-9: #0aa7d9; --cyan-11: #087ca3;
  --violet-3: #f4efff; --violet-9: #8250df; --violet-11: #6f42c1;
  --error-3: #fff0f0; --error-9: #cf222e; --error-11: #cf222e;
  --success-3: #eef8f1; --success-9: #1a7f37; --success-11: #1a7f37;
  --warning-3: #fff7df; --warning-9: #d8a018; --warning-11: #9a6700;
  --info-3: #eaf3ff; --info-9: #1f6feb; --info-11: #1857b6;
  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --line: var(--neutral-5); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: var(--success-11); --fail: var(--error-11); --run: var(--accent-11); --purple: var(--violet-11); --amber: var(--warning-11); --ai: var(--violet-9);
  --event: var(--cyan-11); --focus: var(--accent-9); --player-line: var(--neutral-6);
  --danger-surface: var(--error-3); --danger-border: var(--error-9); --danger-text: var(--error-11);
  --warning-surface: var(--warning-3); --warning-border: var(--warning-9); --warning-text: var(--warning-11);
  --success-surface: var(--success-3); --success-border: var(--success-9); --success-text: var(--success-11);
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-2); --code-text: var(--neutral-12);
  --r-sm: 6px; --r-md: 10px; --r-lg: 14px;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px; --space-5: 24px; --space-6: 32px;
  --type-micro: 9px; --type-caption: 11px; --type-small: 12px; --type-body: 14px; --type-title: 24px;
  --page-x: var(--space-6); --page-y: var(--space-5); --content-wide: 1120px; --content-reading: 720px; --control-height: 32px;
  --shadow-raised: 0 16px 40px color-mix(in srgb,var(--accent-12) 12%,transparent), 0 2px 8px color-mix(in srgb,var(--accent-12) 9%,transparent);
}
[data-theme="dark"] {
  color-scheme: dark;
  --neutral-1: #121313; --neutral-2: #19191a; --neutral-3: #212224; --neutral-4: #282a2d;
  --neutral-5: #303236; --neutral-6: #393c41; --neutral-7: #454950; --neutral-8: #5b6169;
  --neutral-9: #7a818b; --neutral-10: #8c939d; --neutral-11: #b3b8be; --neutral-12: #e5e8ec;
  --accent-1: #111315; --accent-2: #171a1e; --accent-3: #1c232d; --accent-4: #202b3a;
  --accent-5: #243348; --accent-6: #293c59; --accent-7: #324a6d; --accent-8: #43618e;
  --accent-9: #5a81bb; --accent-10: #6e94cb; --accent-11: #a0b9de; --accent-12: #dae9ff;
  --cyan-3: #1b2428; --cyan-9: #86bdd6; --cyan-11: #9dbdcc;
  --violet-3: #21202f; --violet-9: #6457ac; --violet-11: #b4b0e8;
  --error-3: #2d1d1c; --error-9: #c56c65; --error-11: #e0a7a1;
  --success-3: #1a261a; --success-9: #84cc86; --success-11: #9bc49b;
  --warning-3: #262219; --warning-9: #ceb47e; --warning-11: #c5b696;
  --info-3: #1b2329; --info-9: #7aabce; --info-11: #9fbcd1;
  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --line: var(--neutral-4); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: #39d16d; --fail: #ff626d; --run: #6aa6ff; --purple: #b08cff; --amber: #f2b84b; --ai: #c29aff;
  --event: #5ed3ff; --focus: #91bdff; --player-line: var(--neutral-6);
  --danger-surface: var(--error-3); --danger-border: #ff626d; --danger-text: #ff969d;
  --warning-surface: var(--warning-3); --warning-border: #f2b84b; --warning-text: #ffd27a;
  --success-surface: var(--success-3); --success-border: #39d16d; --success-text: #76e99a;
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-1); --code-text: var(--neutral-12);
  --shadow-raised: 0 18px 48px color-mix(in srgb,var(--accent-1) 76%,transparent), 0 2px 8px color-mix(in srgb,var(--accent-1) 82%,transparent);
}
* { box-sizing: border-box; scrollbar-width: thin; scrollbar-color: rgba(144,152,164,.32) transparent; }
*::-webkit-scrollbar { width: 8px; height: 8px; }
*::-webkit-scrollbar-track { background: transparent; }
*::-webkit-scrollbar-thumb { min-height: 36px; border: 2px solid transparent; border-radius: 99px; background: rgba(144,152,164,.32); background-clip: padding-box; }
*::-webkit-scrollbar-thumb:hover { background-color: rgba(144,152,164,.52); }
*::-webkit-scrollbar-corner { background: transparent; }
html, body { margin: 0; height: 100%; overflow: hidden; }
body { background: var(--bg); color: var(--txt); font: var(--type-body)/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; text-rendering: optimizeLegibility; transition: background-color 140ms ease-out,color 140ms ease-out; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
#app { display: flex; flex-direction: column; height: 100%; height: 100dvh; min-height: 0; overflow: hidden; }
@keyframes reportPageForward { from { opacity: .35; transform: translateX(18px); } to { opacity: 1; transform: translateX(0); } }
@keyframes reportPageBack { from { opacity: .35; transform: translateX(-18px); } to { opacity: 1; transform: translateX(0); } }
#app.page-enter-forward { animation: reportPageForward 220ms cubic-bezier(.16,1,.3,1) both; }
#app.page-enter-back { animation: reportPageBack 220ms cubic-bezier(.16,1,.3,1) both; }
header { flex-shrink: 0; padding: var(--page-y) var(--page-x) 0; border-bottom: 1px solid var(--line); background: var(--header); }
.title-row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; max-width: var(--content-wide); }
h1 { font-size: var(--type-title); line-height: 1.2; letter-spacing: -.018em; margin: 0; font-weight: 720; }
.badge { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; padding: 3px 9px; border-radius: 99px; }
.badge.passed, .badge.success { background: var(--success-surface); color: var(--success-text); }
.badge.failed, .badge.error { background: var(--danger-surface); color: var(--danger-text); }
.badge.running, .badge.cancelled, .badge.unknown { background: var(--accent-surface); color: var(--run); }
.meta { display: flex; flex-wrap: wrap; gap: var(--space-3) var(--space-5); margin-top: var(--space-4); }
.meta .k { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .1em; color: var(--sub); }
.meta .v { font-size: var(--type-small); font-weight: 600; margin-top: 1px; }
nav { display: flex; gap: var(--space-1); margin-top: 20px; margin-left: calc(-1 * var(--space-3)); overflow-x: auto; scrollbar-width: thin; }
nav button { background: none; border: none; color: var(--sub); font-size: 13px; font-weight: 650; padding: 10px 12px; cursor: pointer; border-bottom: 2px solid transparent; white-space: nowrap; transition: color 120ms ease-out, background-color 120ms ease-out, border-color 120ms ease-out; }
nav button:hover { color: var(--txt); background: var(--bg3); border-radius: var(--r-sm) var(--r-sm) 0 0; }
nav button.active { color: var(--txt); border-bottom-color: var(--run); border-radius: var(--r-sm) var(--r-sm) 0 0; background: var(--accent-surface); }
main { flex: 1; min-height: 0; overflow: auto; padding: var(--page-y) var(--page-x) var(--space-6); }
footer { flex-shrink: 0; padding: var(--space-3) var(--page-x); border-top: 1px solid var(--line); color: var(--sub); font-size: var(--type-caption); display: flex; gap: var(--space-2); align-items: center; }
.indexfooter, .detailfooter { min-height: 59px; box-sizing: border-box; justify-content: space-between; }
.detailfooter { min-width: 0; gap: var(--space-4); }
.detailfootermeta { min-width: 0; flex: 1; display: flex; align-items: center; gap: var(--space-4); overflow-x: auto; scrollbar-width: none; }
.detailfootermeta::-webkit-scrollbar { display: none; }
.detailfooteritem { display: grid; gap: 1px; white-space: nowrap; }
.detailfooteritem.runon { margin-left: auto; text-align: right; }
.detailfooteritem .k { color: var(--sub); font-size: var(--type-micro); font-weight: 650; letter-spacing: .09em; line-height: 1.2; text-transform: uppercase; }
.detailfooteritem .v { color: var(--sub2); font-size: var(--type-caption); font-weight: 600; line-height: 1.25; }
.indexshell { width: 100%; max-width: var(--content-wide); margin-inline: auto; }
.indexfootercontent { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.indexmetrics { display: flex; align-items: center; gap: var(--space-5); margin-left: auto; }
.indexrundate { text-align: right; }
[data-theme="light"] nav button:hover, [data-theme="light"] .idxrow:hover, [data-theme="light"] .step:hover, [data-theme="light"] .grphdr:hover { background: var(--neutral-3); }
[data-theme="light"] nav button.active { background: var(--accent-surface); }
[data-theme="light"] .idxattempts { background: var(--neutral-2); }
[data-theme="light"] .idxattemptrow:hover { background: var(--neutral-3); }
[data-theme="light"] .quietlink, [data-theme="light"] .yamllink { background: var(--neutral-2); }
[data-theme="light"] .exportmenuitem:hover, [data-theme="light"] .idxsortoption:hover { background: var(--neutral-3); }
.tl { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.eyebrow { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); margin-bottom: var(--space-2); }
.viewpage { width: 100%; max-width: var(--content-wide); }
.viewhead { display: flex; align-items: baseline; gap: var(--space-2); min-height: 24px; margin: 0 0 var(--space-3); }
.viewtitle { margin: 0; color: var(--txt); font-size: var(--type-small); font-weight: 720; line-height: 1.35; }
.viewmeta { color: var(--sub); font-size: var(--type-micro); font-weight: 550; letter-spacing: .075em; text-transform: uppercase; }
.viewbody { min-width: 0; }
.timelinephases { display: grid; gap: 18px; }
.tlphasehead { position: sticky; top: -1px; z-index: 6; width: 100%; min-height: 40px; display: flex; align-items: center; gap: 9px; margin: 0 0 7px; padding: 0 8px; border-bottom: 1px solid var(--line); background: color-mix(in srgb,var(--bg) 94%,transparent); backdrop-filter: blur(10px); }
.phasecontrol { min-width: 0; min-height: 40px; flex: 1; display: flex; align-items: center; gap: 9px; padding: 7px 0; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.phasecontrol:hover { color: var(--txt); }
.phasecontrol:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.tlphasehead .name { color: var(--txt); font-size: 12px; font-weight: 750; letter-spacing: .055em; text-transform: uppercase; }
.tlphasehead .desc { color: var(--sub); font-size: 10.5px; }
.tlphasehead .phasechev { width: 8px; height: 8px; margin-left: auto; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.phasecontrol[aria-expanded="false"] .phasechev { transform: rotate(-45deg); }
.tlphase.trailhead .tlphasehead .name { color: var(--purple); }
.tlphasebody[hidden], .stepgroupbody[hidden] { display: none; }
.timelinecontrols { display: flex; justify-content: flex-start; margin: 0 0 10px; }
.selfhealpanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--warning-border) 60%,var(--line2)); border-radius: var(--r-lg); background: var(--warning-surface); }
.selfhealhead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--warning-border) 46%,var(--line)); }
.selfhealicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--warning-border); color: var(--neutral-12); font-size: 13px; font-weight: 850; }
.selfhealtitle { color: var(--warning-text); font-size: 13px; font-weight: 720; }
.selfhealcontext { margin-left: auto; color: var(--sub2); font-size: 10.5px; }
.selfhealbody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.selfhealfield { min-width: 0; padding: 10px 13px 11px; }
.selfhealfield + .selfhealfield { border-left: 1px solid color-mix(in srgb,var(--warning-border) 40%,var(--line)); }
.selfhealfield .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.selfhealtoolname { display: block; margin-top: 4px; color: var(--warning-text); font-size: 12.5px; font-weight: 650; overflow-wrap: anywhere; }
.selfhealmessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.yamllink { margin-top: 8px; width: fit-content; min-height: 28px; display: inline-flex; align-items: center; border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: 10.5px; font-weight: 700; cursor: pointer; }
.yamllink:hover { color: var(--txt); border-color: var(--run); }
.failurepanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--danger-border) 64%,var(--line2)); border-radius: var(--r-lg); background: var(--danger-surface); }
.failurehead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 48%,var(--line)); }
.failureicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--danger-border); color: var(--neutral-12); font-size: 13px; font-weight: 850; }
.failuretitle { color: var(--danger-text); font-size: 13px; font-weight: 720; }
.failurecontext { margin-left: auto; color: var(--sub2); font-size: 10.5px; }
.failuretool { display: grid; grid-template-columns: 112px minmax(0,1fr); gap: 12px; align-items: center; padding: 10px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failuretool .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.failuretoolvalue { min-width: 0; display: flex; align-items: center; gap: 8px; }
.failuretoolname { color: var(--danger-text); font-size: 12.5px; font-weight: 650; }
.failuretoolargs { color: var(--sub2); font-size: 10.5px; }
.failuretool .yamllink { margin: 0 0 0 auto; flex-shrink: 0; }
.failurebody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.failurefield { min-width: 0; padding: 10px 13px 11px; }
.failurefield + .failurefield { border-left: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurefield .k, .failurestack summary { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.failuretype { display: block; margin-top: 4px; color: var(--danger-text); font-size: 11.5px; overflow-wrap: anywhere; }
.failuremessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.failurestack { border-top: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurestack summary { display: flex; align-items: center; gap: 8px; padding: 9px 13px; cursor: pointer; list-style: none; }
.failurestack summary::-webkit-details-marker { display: none; }
.failurestack summary::before { content: '›'; color: var(--sub2); font-size: 17px; line-height: 1; transform: rotate(90deg); transition: transform 120ms ease-out; }
.failurestack:not([open]) summary::before { transform: rotate(0deg); }
.failurestack .frames { margin-left: auto; color: var(--sub); font-size: 10px; font-weight: 500; font-variant-numeric: tabular-nums; letter-spacing: 0; text-transform: none; }
.failurestack pre { max-height: 210px; margin: 0; border: 0; border-top: 1px solid color-mix(in srgb,var(--danger-border) 32%,var(--line)); border-radius: 0; background: var(--code-surface); color: var(--code-text); }
.tlphase.trailhead .steps { border-color: color-mix(in srgb,var(--violet-9) 52%,var(--line2)); }
.steps { border: 1px solid var(--line); border-radius: var(--r-lg); overflow: hidden; background: var(--bg2); box-shadow: inset 0 1px rgba(255,255,255,.025); }
.stepgroup { position: relative; }
.stepgroup.failed { background: var(--danger-surface); }
.stepgroup.failed .grphdr { background: color-mix(in srgb,var(--danger-surface) 80%,var(--bg3)); }
.stepgroup.failed .grphdr .chip { color: var(--danger-text); background: color-mix(in srgb,var(--danger-border) 26%,var(--danger-surface)); }
.stepgroup.failed .step { background-color: transparent; }
.stepgroup.selfhealed { background: var(--warning-surface); }
.stepgroup.selfhealed .grphdr { background: color-mix(in srgb,var(--warning-surface) 80%,var(--bg3)); }
.stepgroup.selfhealed .grphdr .chip { color: var(--warning-text); background: color-mix(in srgb,var(--warning-border) 24%,var(--warning-surface)); }
.stepgroup.selfhealed .step { background-color: var(--bg2); }
.grphdr { width: 100%; padding: 12px 14px 11px; background: var(--bg3); color: inherit; border: 0; border-top: 1px solid var(--line2); display: grid; grid-template-columns: auto auto auto 1fr auto; align-items: center; gap: 8px; font: inherit; text-align: left; cursor: pointer; }
.grphdr:hover { background: color-mix(in srgb,var(--bg3) 84%,white); }
.grphdr.sel { background: var(--accent-surface); }
.grphdr:focus-visible { position: relative; z-index: 1; outline: 2px solid var(--focus); outline-offset: -2px; }
.steps > .grphdr:first-child, .stepgroup:first-child > .grphdr { border-top: none; }
.grphdr .chip, .galchip { font-size: 9.5px; font-weight: 700; letter-spacing: .06em; color: var(--purple); background: var(--violet-surface); border-radius: 5px; padding: 2px 7px; white-space: nowrap; flex-shrink: 0; }
.grphdr.trailhead .chip { color: var(--purple); background: var(--violet-surface); }
.grphdr .dot { width: 8px; height: 8px; border-radius: 99px; }
.grphdr .lbl { grid-column: 1 / -1; display: block; font-size: 14px; font-weight: 650; margin-top: 4px; line-height: 1.4; }
.grphdr .groupchev { grid-column: 5; grid-row: 1; width: 8px; height: 8px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.grphdr[aria-expanded="false"] .groupchev { transform: rotate(-45deg); }
.step { display: flex; gap: 10px; padding: 10px 14px; cursor: pointer; border-top: 1px solid var(--line); transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.step.child { padding-left: 22px; }
.step:hover { background: var(--bg3); }
.step.sel { background: var(--accent-surface); }
.stepgroup.failed .step.sel { background: color-mix(in srgb,var(--danger-border) 18%,var(--danger-surface)); }
.stepgroup.selfhealed .grphdr.sel, .stepgroup.selfhealed .step.selfheal { background: color-mix(in srgb,var(--warning-border) 18%,var(--warning-surface)); }
.stepgroup.selfhealed .step.sel:not(.selfheal) { background: var(--accent-surface); }
.step .num { font-size: 11px; color: var(--sub); width: 20px; text-align: right; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.step .ic { width: 14px; height: 14px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 2px; font-size: 14px; font-weight: 800; line-height: 1; }
.step .ic.dot::before { content: ''; width: 9px; height: 9px; border-radius: 99px; background: var(--icon-color); }
.step .ic.tap { font-size: 13px; }
.step .ic.verify { color: var(--pass); }
.step .ic.failure { color: var(--fail); }
.step .lbl { font-size: 13px; font-weight: 560; }
.step .tl-tool { font-size: 11px; color: var(--sub); margin-top: 2px; word-break: break-word; }
.step .note { font-size: 11.5px; color: var(--sub2); margin-top: 3px; line-height: 1.4; }
.kids { margin-top: 6px; border-left: 1px solid var(--line2); padding-left: 10px; }
.kids div { font-size: 11.5px; margin-top: 3px; }
.kids .kt { color: var(--sub); }
.timeline-list { grid-row: 2; }
.preview { position: static; grid-row: 1; min-width: 0; display: flex; justify-content: center; }
.deviceplayer { width: fit-content; max-width: 100%; display: grid; grid-template-rows: minmax(0,1fr) auto; overflow: hidden; border: 2px solid var(--player-line); border-radius: 22px; background: var(--raised); box-shadow: var(--shadow-raised); }
.deviceplayer.empty { width: min(360px,100%); grid-template-rows: auto auto; }
.shotwrap { width: fit-content; max-width: 100%; margin: 0; }
.shot { max-width: 100%; max-height: calc(100vh - 290px); background: #000; border: 0; display: block; cursor: zoom-in; }
.noshot { width: 100%; aspect-ratio: 1/2; border: 0; display: flex; align-items: center; justify-content: center; color: var(--sub); font-size: 12px; text-align: center; padding: 20px; }
.pvctl { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 0; margin: 0; border-top: 2px solid var(--player-line); overflow: hidden; }
.pvctl button.btn { width: 100%; min-width: 0; min-height: 42px; border: 0; border-left: 2px solid var(--player-line); border-radius: 0; background: transparent; }
.pvctl button.btn:first-child { border-left: 0; }
.pvctl button.btn.play { min-width: 0; border-left-color: var(--player-line); background: rgba(106,166,255,.08); }
.transporticon { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; color: currentColor; }
.transporticon.direction::before { content: ''; width: 12px; height: 12px; box-sizing: border-box; border-bottom: 4px solid currentColor; border-left: 4px solid currentColor; border-radius: 2px; transform: rotate(45deg); }
.pvctl #next .transporticon.direction::before { transform: rotate(225deg); }
.transporticon.playicon { margin-left: 2px; }
.transporticon.pauseicon { gap: 5px; }
.transporticon.pauseicon::before, .transporticon.pauseicon::after { content: ''; width: 5px; height: 20px; border-radius: 2px; background: currentColor; }
button.btn { min-height: 34px; background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: var(--r-sm); padding: 6px 11px; font-size: 12.5px; font-weight: 650; cursor: pointer; transition: color 120ms ease-out, background-color 120ms ease-out, border-color 120ms ease-out, transform 100ms ease-out; }
button.btn:disabled { opacity: .4; cursor: default; }
button.btn:not(:disabled):hover { border-color: var(--run); background: var(--button-hover); }
.pvctl button.btn:not(:disabled):hover { border-left-color: var(--player-line); }
button.btn:not(:disabled):active { transform: translateY(1px); }
button.btn.play { border-color: var(--run); background: var(--accent-surface); color: var(--run); min-width: 84px; }
.llm { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.card { border: 1px solid var(--line); border-radius: 10px; background: var(--bg2); padding: 10px 13px; }
.totals { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px; }
.totals .n { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.totals .t { font-size: 10.5px; color: var(--sub); }
.callrow { padding: 9px 11px; margin-top: 5px; cursor: pointer; border: 1px solid transparent; border-radius: var(--r-md); background: var(--bg2); transition: background-color 120ms ease-out, border-color 120ms ease-out; }
.callrow:hover { background: var(--bg3); border-color: var(--line2); }
.callrow.sel { background: var(--bg3); border-color: rgba(57,209,109,.45); }
.callrow .d { font-size: 11.5px; font-weight: 600; }
.callrow .m { font-size: 10.5px; color: var(--sub); margin-top: 3px; }
.resp { border: 1px solid rgba(181,140,255,.3); background: rgba(181,140,255,.07); border-radius: 10px; padding: 11px 13px; margin-top: 10px; }
.resp .h { font-size: 10.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; color: var(--ai); margin-bottom: 8px; }
.resp .reason { font-size: 12.5px; line-height: 1.55; margin-bottom: 6px; }
.resp .tool { font-size: 12px; font-weight: 700; margin: 4px 0; }
pre { margin: 0; font-size: 11px; line-height: 1.5; color: var(--sub2); white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
.rows { display: grid; max-width: var(--content-reading); overflow: hidden; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); }
.rows .r { display: grid; grid-template-columns: 160px minmax(0,1fr); gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); font-size: var(--type-small); }
.rows .r:first-child { border-top: 0; }
.rows .r .k { color: var(--sub); font-size: var(--type-caption); }
.rows .r .v { overflow-wrap: anywhere; }
.infosection + .infosection { margin-top: var(--space-5); }
.cmd { display: flex; gap: var(--space-2); align-items: flex-start; margin-top: var(--space-2); max-width: var(--content-reading); }
.cmd pre { flex: 1; }
.zoom { position: fixed; inset: 0; background: rgba(2,6,12,.9); display: flex; align-items: center; justify-content: center; cursor: zoom-out; z-index: 99; backdrop-filter: blur(4px); }
.zoom img { max-width: 92vw; max-height: 92vh; border-radius: 10px; border: 1px solid var(--line2); }
.zoomnav { position: fixed; top: 50%; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--line2); border-radius: 12px; background: color-mix(in srgb,var(--raised) 90%,transparent); color: var(--txt); font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 29px; font-weight: 600; line-height: 1; cursor: pointer; transform: translateY(-50%); box-shadow: var(--shadow-raised); }
.zoomnav.prev { left: 24px; }
.zoomnav.next { right: 24px; }
.zoomnav:hover { border-color: var(--run); background: rgba(34,40,50,.96); }
.zoomnav:disabled { opacity: 0; pointer-events: none; }
.zoomcount { position: fixed; bottom: 20px; left: 50%; padding: 5px 9px; border: 1px solid var(--line2); border-radius: 8px; background: color-mix(in srgb,var(--raised) 90%,transparent); color: var(--sub2); font-size: var(--type-caption); font-weight: 600; transform: translateX(-50%); }
.empty { color: var(--sub); font-size: 13px; padding: 30px; text-align: center; }
.indexheader { padding-bottom: var(--page-y); }
.indexheadrow { justify-content: space-between; }
.indexheadactions, .detailactions { display: flex; align-items: center; gap: var(--space-2); }
.themetoggle { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; padding: 0; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--sub); cursor: pointer; }
.themetoggle:hover { color: var(--txt); background: var(--button-hover); }
.themetoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.themeicon { width: 19px; height: 19px; display: block; }
.themeicon.moon { display: none; }
[data-theme="light"] .themeicon.sun { display: none; }
[data-theme="light"] .themeicon.moon { display: block; }
.idxsummary { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
.idxsummary .stat { color: var(--sub2); font-size: 13px; font-weight: 650; white-space: nowrap; }
.idxsummary .stat strong { color: var(--txt); font-size: 16px; }
.idxsummary .stat.pass strong { color: var(--pass); }
.idxsummary .stat.selfheal strong { color: var(--amber); }
.idxsummary .stat.fail strong { color: var(--fail); }
.indexcontext { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-5); max-width: var(--content-wide); margin-top: var(--space-4); }
.indexmeta { margin-top: 0; }
.indexlinks { display: flex; align-items: center; gap: var(--space-2); flex-shrink: 0; }
.idxfilter { display: grid; grid-template-columns: minmax(0,1fr) auto 132px; align-items: center; gap: var(--space-2); width: min(100%,var(--content-wide)); margin-bottom: var(--space-3); }
.idxfilter input { width: 100%; min-width: 0; min-height: var(--control-height); background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 10px; font: inherit; font-size: var(--type-caption); outline: none; }
.idxfilter input:focus-visible, .idxsort summary:focus-visible, .idxhealedfilter:focus-visible { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(77,139,255,.16); outline: none; }
.idxhealedfilter { min-height: var(--control-height); display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line2); border-radius: var(--r-md); padding: 5px 11px; background: var(--bg3); color: var(--sub2); font: inherit; font-size: var(--type-caption); font-weight: 650; white-space: nowrap; cursor: pointer; }
.idxhealedfilter::before { content: ''; width: 7px; height: 7px; border: 2px solid var(--amber); border-radius: 50%; }
.idxhealedfilter:hover { border-color: var(--amber); color: var(--txt); }
.idxhealedfilter[aria-pressed="true"] { border-color: rgba(242,184,75,.55); background: rgba(242,184,75,.13); color: var(--amber); }
.idxsort { position: relative; width: 132px; color: var(--sub2); font-size: var(--type-caption); font-weight: 650; }
.idxsort summary { min-height: var(--control-height); display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); list-style: none; border: 1px solid var(--line2); border-radius: var(--r-md); padding: 5px var(--space-3); background: var(--bg3); cursor: pointer; transition: color 100ms ease-out,background-color 100ms ease-out,border-color 100ms ease-out; }
.idxsort summary::-webkit-details-marker { display: none; }
.idxsort summary:hover, .idxsort[open] summary { border-color: var(--run); background: var(--button-hover); color: var(--txt); }
.idxsortchev { width: 8px; height: 8px; flex-shrink: 0; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); }
.idxsortmenu { position: absolute; z-index: 30; top: calc(100% + 6px); right: 0; width: 164px; display: grid; gap: 3px; padding: 5px; border: 1px solid var(--line2); border-radius: 14px; background: var(--raised); box-shadow: 0 16px 36px rgba(0,0,0,.42); transform-origin: top right; animation: idxsortin 120ms cubic-bezier(.16,1,.3,1); }
.idxsortoption { min-height: 32px; display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; border-radius: 9px; padding: 6px 10px; background: transparent; color: var(--sub2); font: inherit; font-size: 11.5px; font-weight: 600; text-align: left; cursor: pointer; }
.idxsortoption:hover, .idxsortoption:focus-visible { background: var(--button-hover); color: var(--txt); outline: none; }
.idxsortoption[aria-selected="true"] { background: var(--accent-surface); color: var(--run); }
.idxsortoption[aria-selected="true"]::after { content: '✓'; color: var(--run); font-size: 11px; }
@keyframes idxsortin { from { opacity: 0; transform: translateY(-4px) scale(.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
.idxsection + .idxsection { margin-top: var(--space-4); }
.idxsectionhead { display: flex; align-items: center; gap: 8px; margin: 0 0 7px 2px; color: var(--sub2); font-size: var(--type-caption); font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.idxsectionhead::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxsectionhead.failed::before { background: var(--fail); }
.idxsectionhead.selfheal::before { background: var(--amber); }
.idxsectionhead.passed::before { background: var(--pass); }
.idxsectioncount { color: var(--sub); font-weight: 600; letter-spacing: 0; text-transform: none; }
.idx { border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; background: var(--bg2); max-width: var(--content-wide); }
.idxrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 180px 20px; align-items: center; gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.idxrow[hidden] { display: none; }
.idxrow:first-child { border-top: none; }
.idxrow.firstmatch { border-top: none; }
.idxrow:hover { background: var(--bg3); }
.idxrow:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxstatus { width: 12px; height: 12px; display: flex; align-items: center; justify-content: center; }
.idxstatusdot { width: 7px; height: 7px; border-radius: 50%; background: var(--sub); box-shadow: 0 0 0 3px rgba(160,169,184,.08); }
.idxstatusdot.failed { background: var(--fail); box-shadow: 0 0 0 3px rgba(255,91,106,.1); }
.idxstatusdot.selfheal { background: var(--amber); box-shadow: 0 0 0 3px rgba(242,184,75,.11); }
.idxstatusdot.passed { background: var(--pass); box-shadow: 0 0 0 3px rgba(48,211,109,.1); }
.idxmain { min-width: 0; }
.idxrow .nm { font-size: 14px; font-weight: 650; min-width: 0; word-break: break-word; }
.idxfacts { display: grid; grid-template-columns: 104px 60px; gap: 16px; align-items: center; }
.idxfact .k { color: var(--sub); font-size: var(--type-micro); letter-spacing: .08em; text-transform: uppercase; }
.idxfact .v { color: var(--sub2); font-size: var(--type-caption); font-weight: 600; margin-top: 1px; white-space: nowrap; }
.quietlink { min-height: 32px; display: inline-flex; align-items: center; color: var(--sub2); border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 9px; font-size: 11px; font-weight: 650; text-decoration: none; background: var(--bg2); }
.quietlink:hover { color: var(--txt); border-color: var(--run); background: rgba(106,166,255,.07); }
.quietlink:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.idxrow .arr { color: var(--sub); font-size: 14px; align-self: center; }
.idxretry { border-top: 1px solid var(--line); }
.idxretry:first-child { border-top: 0; }
.idxretry > summary { list-style: none; border-top: 0; }
.idxretry > summary::-webkit-details-marker { display: none; }
.idxretryrow { grid-template-columns: auto minmax(220px,1fr) 180px 20px; }
.idxretrydots { display: inline-flex; align-items: center; gap: 5px; padding-inline: 1px; }
.idxretrydots .idxstatusdot { flex-shrink: 0; }
.idxretrychev { width: 8px; height: 8px; justify-self: center; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out,color 120ms ease-out; }
.idxretry[open] .idxretrychev { color: var(--ai); transform: rotate(225deg) translate(-1px,-1px); }
.idxattempts { background: var(--bg2); border-top: 1px solid var(--line); }
.idxattemptrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 180px 20px; align-items: center; gap: var(--space-4); min-height: 58px; padding: 10px var(--space-4) 10px 48px; border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out,box-shadow 120ms ease-out; }
.idxattemptrow:first-child { border-top: 0; }
.idxattemptrow:hover { background: var(--bg3); }
.idxattemptrow[data-outcome="failed"]:hover { background: var(--danger-surface); box-shadow: inset 3px 0 var(--fail); }
.idxattemptrow[data-outcome="selfheal"]:hover { background: rgba(242,184,75,.055); box-shadow: inset 3px 0 var(--amber); }
.idxattemptrow[data-outcome="passed"]:hover { background: rgba(48,211,109,.045); box-shadow: inset 3px 0 var(--pass); }
.idxattemptrow:focus-visible { outline: 1px solid var(--line2); outline-offset: -1px; }
.idxattemptmain { min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.idxattemptlabel { color: var(--txt); font-size: var(--type-caption); font-weight: 650; white-space: nowrap; }
.idxattemptstatus { font-size: var(--type-micro); font-weight: 700; text-transform: capitalize; }
.idxattemptstatus.failed { color: var(--fail); }
.idxattemptstatus.selfheal { color: var(--amber); }
.idxattemptstatus.passed { color: var(--pass); }
.idxattempttime { min-width: 0; overflow: hidden; color: var(--sub); font-size: var(--type-micro); text-overflow: ellipsis; white-space: nowrap; }
.detailheader { padding-top: var(--space-4); }
.detailheader h1 { font-size: 20px; }
.detailheader nav { margin-top: var(--space-3); }
.detailtitle { min-height: 32px; max-width: none; display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 12px; }
.detailedge { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; }
.runidentity { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; min-width: 0; }
.exportmenu { position: relative; }
.exportmenu summary { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid transparent; border-radius: 7px; color: var(--sub); cursor: pointer; list-style: none; }
.exportmenu summary::-webkit-details-marker { display: none; }
.exportdots { display: inline-flex; align-items: center; gap: 3px; }
.exportdot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.exportmenu summary:hover, .exportmenu[open] summary { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.exportmenuitems { position: absolute; z-index: 30; top: calc(100% + 5px); right: 0; width: 196px; padding: 5px; border: 1px solid var(--line2); border-radius: 9px; background: var(--bg2); box-shadow: 0 12px 30px rgba(0,0,0,.38); animation: idxsortin 120ms ease-out both; }
.exportmenuitem { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; border: 0; border-radius: 6px; background: transparent; color: var(--sub2); font: inherit; font-size: 11px; font-weight: 650; text-align: left; cursor: pointer; }
.exportmenuitem:hover { color: var(--txt); background: var(--button-hover); }
.exportmenuitem:disabled { color: var(--sub); cursor: not-allowed; opacity: .48; background: transparent; }
.exportmenuitem .count { color: var(--sub); font-size: 9px; line-height: 1.2; font-variant-numeric: tabular-nums; }
.headeraction { min-width: 72px; display: inline-flex; align-items: center; justify-content: center; }
.back { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; background: transparent; border: 1px solid transparent; border-radius: 7px; color: var(--sub); cursor: pointer; padding: 0; }
.back:hover { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.back:focus-visible { color: var(--txt); outline: 2px solid var(--focus); outline-offset: 2px; }
.backarrow { font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 25px; font-weight: 600; line-height: .9; }
.yaml { max-height: none; max-width: 880px; }
.yamlline { display: block; min-height: 1.5em; }
.yamlmark { display: block; margin: 0 0 0 -10px; padding: 0 0 0 10px; border-left: 3px solid transparent; }
.yamlmark.failed { border-left-color: var(--fail); }
.yamlmark.selfheal { border-left-color: var(--amber); }
.yamlmark.tool.failed { border-left-width: 4px; }
.yamlmark.tool.selfheal { border-left-width: 4px; }
.shotwrap { position: relative; display: block; }
.mark { position: absolute; pointer-events: none; }
.mark.tap { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--fail); border-radius: 99px; background: rgba(248,71,82,.25); box-shadow: 0 0 0 1px rgba(0,0,0,.5); }
.mark.assertok { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--pass); border-radius: 99px; background: rgba(46,204,92,.22); }
.markborder { position: absolute; inset: 0; border: 3px solid var(--fail); border-radius: 6px; pointer-events: none; }
svg.swipe { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.viewpage.lightboxpage { max-width: none; }
.gal { width: 100%; display: grid; grid-template-columns: repeat(auto-fit,minmax(min(190px,100%),1fr)); gap: 16px; align-items: start; }
.lightboxtoolbar { display: flex; justify-content: flex-start; margin: -4px 0 var(--space-4); }
.lightboxtoggle { min-height: 30px; display: inline-flex; align-items: center; gap: 8px; padding: 4px 8px 4px 7px; border: 1px solid var(--line2); border-radius: 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: var(--type-caption); font-weight: 650; cursor: pointer; }
.lightboxtoggle:hover { color: var(--txt); border-color: var(--run); }
.lightboxtoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.lightboxtoggletrack { width: 24px; height: 14px; display: flex; align-items: center; padding: 2px; border-radius: 99px; background: var(--line2); transition: background-color 120ms ease-out; }
.lightboxtogglethumb { width: 10px; height: 10px; border-radius: 99px; background: var(--sub2); transition: transform 120ms ease-out, background-color 120ms ease-out; }
.lightboxtoggle[aria-checked="true"] .lightboxtoggletrack { background: rgba(106,166,255,.48); }
.lightboxtoggle[aria-checked="true"] .lightboxtogglethumb { background: var(--accent-7); transform: translateX(10px); }
.galcell { min-width: 0; border: 0; padding: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.galcell:hover .gallabel, .galcell:hover .galtool { color: var(--txt); }
.galshot { cursor: zoom-in; }
.galcell img { width: 100%; border: 1px solid var(--line2); border-radius: 6px; display: block; background: #000; }
.galcell .cap { display: grid; gap: 5px; margin-top: 7px; line-height: 1.35; word-break: break-word; }
.galchip { width: fit-content; }
.galchip.trailhead { color: var(--purple); background: var(--violet-surface); }
.gallabel { color: var(--sub2); font-size: var(--type-caption); font-weight: 600; }
.galtool { color: var(--sub); font-size: var(--type-caption); }
.logpane { border: 1px solid var(--line); border-radius: 8px; background: var(--bg); max-height: 72vh; overflow: auto; margin-top: 8px; }
.logpane .ln { display: flex; gap: 10px; padding: 1px 11px; font-size: 11.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; border-top: 1px solid var(--line); }
.logpane .ln:first-child { border-top: none; }
.logpane .ln.e { color: var(--danger-text); } .logpane .ln.w { color: var(--warning-text); }
.logpane.net .ln span:first-child { font-weight: 700; min-width: 46px; }
.logpane.net .m { color: var(--sub); min-width: 96px; }
.evchips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.evchip { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 999px; padding: 4px 10px; font-size: 11.5px; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
.evchip:hover { border-color: var(--run); }
.evchip.on { border-color: var(--run); background: var(--bg2); }
.evchip .c { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamselect { position: relative; flex-shrink: 0; }
.streamselect summary { width: 218px; min-height: 32px; display: grid; grid-template-columns: auto minmax(0,1fr) auto auto; align-items: center; gap: 8px; padding: 5px 9px; border: 1px solid var(--line2); border-radius: 9px; background: var(--bg2); color: var(--sub2); cursor: pointer; list-style: none; font-size: 10.5px; font-weight: 650; }
.streamselect summary::-webkit-details-marker { display: none; }
.streamselect summary:hover, .streamselect[open] summary { border-color: color-mix(in srgb,var(--run) 58%,var(--line2)); background: var(--bg3); }
.streamselect summary:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.streamselectoricon, .streamoptiondot { width: 9px; height: 9px; border: 1.5px solid currentColor; border-radius: 99px; background: transparent; flex-shrink: 0; }
.streamselectoricon { color: var(--sub); }
.streamselect .selection { color: var(--sub); font-size: 10px; font-weight: 500; font-variant-numeric: tabular-nums; white-space: nowrap; }
.streamselect .chevron { width: 7px; height: 7px; flex-shrink: 0; border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; color: var(--sub); transform: rotate(45deg); transform-origin: center; transition: transform 120ms ease-out; }
.streamselect[open] .chevron { transform: rotate(225deg); }
.streammenu { position: absolute; z-index: 20; top: calc(100% + 6px); left: 0; width: min(320px, calc(100vw - 48px)); overflow: hidden; border: 1px solid var(--line2); border-radius: 10px; background: var(--bg3); box-shadow: 0 14px 34px rgba(0,0,0,.38); }
.evstreamselect { width: min(360px,100%); margin-bottom: 8px; }
.evstreamselect summary { width: 100%; }
.evstreamselect .streammenu { left: 0; right: auto; width: 100%; max-height: min(360px,60vh); overflow-y: auto; padding: 5px; border-radius: 12px; }
.evstreamlabel { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evstreamcount { margin-left: auto; color: var(--sub); font-size: 10.5px; font-weight: 500; font-variant-numeric: tabular-nums; }
.evstreamoption { min-height: 32px; display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 10px; width: 100%; border: 0; border-radius: 8px; padding: 6px 9px; background: transparent; color: var(--sub2); font: inherit; font-size: 11.5px; font-weight: 600; text-align: left; cursor: pointer; }
.evstreamoption:hover, .evstreamoption:focus-visible { background: var(--button-hover); color: var(--txt); outline: none; }
.evstreamoption[aria-selected="true"] { background: var(--accent-surface); color: var(--run); }
.evstreamoption[aria-selected="true"] .evstreamoptioncount::before { content: '✓'; color: var(--run); margin-right: 8px; }
.evstreamoptioncount { color: var(--sub); font-size: 10.5px; font-weight: 500; font-variant-numeric: tabular-nums; }
.streammenuhead { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border-bottom: 1px solid var(--line); color: var(--sub); font-size: 10.5px; }
.streammenuactions { display: flex; gap: 4px; }
.streammenuactions button { padding: 3px 6px; border: 0; background: transparent; color: var(--run); cursor: pointer; font: inherit; font-weight: 650; }
.streammenuactions button:hover, .streammenuactions button:focus-visible { color: var(--txt); outline: none; text-decoration: underline; }
.streamoption { display: grid; grid-template-columns: 16px 10px minmax(0,1fr) auto; align-items: center; gap: 9px; padding: 9px 10px; border-top: 1px solid var(--line); cursor: pointer; }
.streamoption:first-of-type { border-top: 0; }
.streamoption:hover { background: var(--button-hover); }
.streamoption input { width: 14px; height: 14px; margin: 0; accent-color: var(--run); cursor: pointer; }
.streamoptiondot { color: var(--stream-color); }
.streamoption .streamname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; font-weight: 600; }
.streamoption .streamcount { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.logpane.ev .ln { min-height: 32px; align-items: center; padding: 6px var(--space-3); border-top: 0; }
.logpane.ev .ln:nth-child(even) { background: var(--bg2); }
.logpane.ev .m { color: var(--sub); min-width: 64px; }
.video { max-width: 640px; }
.vframe { height: min(72vh, 900px); max-width: 100%; aspect-ratio: 1/2; background-repeat: no-repeat; background-color: #000; border: 1px solid var(--line2); border-radius: 6px; margin-top: 10px; }
.vctl { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.vctl .count { font-variant-numeric: tabular-nums; }
.vctl input[type=range] { flex: 1; accent-color: var(--run); }
.scrub { position: sticky; top: 18px; height: calc(100vh - 238px); min-height: 300px; display: none; flex-direction: column; user-select: none; }
.scrubclock { color: var(--sub); font-size: 9.5px; text-align: center; font-variant-numeric: tabular-nums; }
.scrubtrack { position: relative; flex: 1; width: 32px; margin: 5px auto; cursor: pointer; }
.scrubline { position: absolute; left: 50%; width: 1px; transform: translateX(-50%); pointer-events: none; }
.scrubline.setup { top: 0; border-left: 1px dashed color-mix(in srgb,var(--purple) 62%,var(--line2)); }
.scrubline.trail { bottom: 0; background: var(--line2); }
.scrubphasebreak { position: absolute; left: 50%; width: 11px; height: 11px; border: 2px solid var(--bg); border-radius: 99px; background: var(--purple); box-shadow: 0 0 0 1px color-mix(in srgb,var(--purple) 55%,var(--line2)); transform: translate(-50%,-50%); pointer-events: none; }
.scrubtick { position: absolute; left: 0; right: 0; height: 3px; border: 0; padding: 0; border-radius: 2px; opacity: .72; pointer-events: none; }
.scrubhead { position: absolute; left: 50%; width: 10px; height: 10px; border-radius: 99px; transform: translate(-50%,-50%); background: #fff; border: 1px solid rgba(0,0,0,.45); box-shadow: 0 1px 5px rgba(0,0,0,.6); pointer-events: none; }
.streamrow { border-top: 1px solid var(--line); padding: 8px 14px 8px 22px; background: color-mix(in oklab, var(--stream-color) 5%, transparent); }
.streamrow summary { cursor: pointer; display: flex; align-items: center; gap: 8px; list-style: none; font-size: 11.5px; }
.streamrow summary::-webkit-details-marker { display: none; }
.streamrow .streamdot { width: 9px; height: 9px; border: 1.5px solid var(--stream-color); border-radius: 99px; background: transparent; flex-shrink: 0; }
.streamrow .streamtype { color: var(--stream-color); font-size: 11.5px; font-weight: 700; }
.streamrow .streamtime { margin-left: auto; color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamrow pre { margin: 7px 0 2px 16px; max-height: 220px; }
.streamitems { margin: 7px 0 2px 16px; display: grid; gap: 7px; }
.streamitems.timelineeventitems { margin-top: 3px; gap: 0; }
.streamitem { display: grid; grid-template-columns: 62px minmax(0,1fr); gap: 8px; align-items: start; }
.streamitem .streamtime { margin-left: 0; padding-top: 7px; }
.streamitem pre { margin: 0; }
.timelineevent { min-width: 0; border-top: 1px solid var(--line); }
.timelineevent:first-child { border-top: 0; }
.timelineevent summary { min-height: 38px; display: grid; grid-template-columns: 62px minmax(0,1fr) 10px; align-items: center; gap: 8px; padding: 7px 9px; color: var(--sub2); cursor: pointer; list-style: none; }
.timelineevent summary::-webkit-details-marker { display: none; }
.timelineevent summary:hover { background: var(--button-hover); }
.timelineevent .streamtime { margin: 0; padding: 0; }
.timelineeventlabel { min-width: 0; color: var(--txt); font-size: 11.5px; font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; white-space: normal; }
.timelineeventchev { width: 7px; height: 7px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.timelineevent[open] .timelineeventchev { transform: rotate(225deg) translate(-1px,-1px); }
.timelineevent pre { margin: 0 9px 9px 79px; max-height: 220px; background: var(--code-surface); color: var(--code-text); }
.eventlist { display: grid; gap: 8px; max-width: var(--content-wide); }
.eventcard { min-width: 0; overflow: hidden; border: 1px solid var(--line2); border-radius: 8px; background: var(--bg2); }
.eventsummary { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border-bottom: 1px solid var(--line); background: var(--bg3); }
.eventlabel { min-width: 0; color: var(--txt); font-size: 12px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.eventsource { color: var(--sub); font-size: 10px; font-weight: 650; text-transform: uppercase; letter-spacing: .07em; }
.eventfields { display: grid; grid-template-columns: repeat(auto-fit,minmax(170px,1fr)); gap: 1px; background: var(--line); }
.eventfield { min-width: 0; padding: 6px 9px; background: var(--bg2); }
.eventfield .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; }
.eventfield .v { margin-top: 2px; color: var(--sub2); font-size: 11.5px; font-weight: 600; overflow-wrap: anywhere; }
.eventdetails { border-top: 1px solid var(--line); }
.eventdetails summary { display: flex; align-items: center; gap: 6px; padding: 6px 9px; color: var(--sub); background: var(--bg3); font-size: 10.5px; font-weight: 650; cursor: pointer; list-style: none; }
.eventdetails summary::-webkit-details-marker { display: none; }
.eventdetails summary::before { content: '›'; font-size: 15px; line-height: 1; transform: rotate(90deg); }
.eventdetails[open] summary::before { transform: rotate(-90deg); }
.eventdetails pre { border: 0; border-top: 1px solid var(--line); border-radius: 0; max-height: 280px; background: var(--code-surface); color: var(--code-text); }
.yamlcompare { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.yamlcol { min-width: 0; }
.yamlcolhead { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.yamlcolhead .eyebrow { margin: 0; }
.yamlcopy { min-height: 24px; padding: 3px 7px; border-radius: 6px; font-size: 10px; }
.yamlcol .cmd { max-width: none; }
@media (min-width: 820px) { .yamlcompare { grid-template-columns: repeat(2,minmax(0,1fr)); } .llm { grid-template-columns: 300px 1fr; } }
@media (min-width: 960px) {
  main.timelinemain { overflow: hidden; }
  .timelinemain .tl { height: 100%; min-height: 0; grid-template-columns: minmax(320px,1fr) 44px minmax(340px,42%); grid-template-rows: minmax(0,1fr); gap: 24px; align-items: stretch; }
  .timelinemain .timeline-list { grid-row: auto; min-height: 0; overflow: auto; padding-right: 3px; }
  .timelinemain .preview { position: static; grid-row: auto; min-height: 0; height: 100%; display: flex; align-items: center; justify-content: center; }
  .timelinemain .deviceplayer { max-height: 100%; min-height: 0; align-self: center; }
  .timelinemain .shotwrap { max-height: calc(100vh - 286px); min-height: 0; }
  .timelinemain .shot { width: auto; height: auto; max-height: calc(100vh - 286px); object-fit: contain; }
  .timelinemain .noshot { height: auto; min-height: 0; aspect-ratio: 1/2; }
  .timelinemain .scrub { display: flex; position: static; height: 100%; min-height: 0; }
}
@media (max-width: 760px) { .indexcontext { align-items: flex-start; flex-direction: column; } .idxrow, .idxattemptrow { grid-template-columns: 12px minmax(0,1fr) 20px; gap: 10px 12px; } .idxretryrow { grid-template-columns: auto minmax(0,1fr) 20px; } .idxretrychev { grid-column: 3; grid-row: 1; } .idxattemptrow { padding-left: 28px; } .idxstatus { grid-row: 1 / span 2; } .idxfacts { grid-column: 2 / -1; } .idxrow .arr, .idxattemptrow .arr { grid-column: 3; grid-row: 1; } .idxfilter { grid-template-columns: minmax(0,1fr) auto 120px; } .idxsort { width: 120px; } .indexfootercontent { flex-wrap: wrap; } .indexmetrics { order: 2; width: 100%; margin-left: 0; } .indexrundate { margin-left: auto; } .streamselect summary { width: 100%; } .streammenu { left: 0; right: auto; } }
@media (max-width: 560px) { .idxfilter { grid-template-columns: minmax(0,1fr) 120px; } .idxfilter input { grid-column: 1 / -1; } .idxhealedfilter { justify-content: center; } }
@media (max-width: 560px) { .failurehead { align-items: flex-start; flex-wrap: wrap; } .failurecontext { width: 100%; margin-left: 30px; } .failuretool { grid-template-columns: 1fr; gap: 6px; } .failuretoolvalue { flex-wrap: wrap; } .failuretoolargs { display: block; } .failuretool .yamllink { margin-left: auto; } .failurebody { grid-template-columns: 1fr; } .failurefield + .failurefield { border-top: 1px solid rgba(248,71,82,.18); border-left: 0; } }
.step .ts { margin-left: auto; flex-shrink: 0; color: var(--sub); font-size: 10.5px; text-align: right; font-variant-numeric: tabular-nums; }
.step .ts .dur { display: block; color: var(--sub); opacity: .8; }
.lfilter { display: flex; align-items: center; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
.lfilter input { background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 10px; font-size: 12.5px; min-width: 220px; }
.lfilter input:focus { border-color: var(--run); }
.lfilter .count { font-size: 11px; color: var(--sub); margin-left: auto; font-variant-numeric: tabular-nums; }
.badge.selfheal { background: rgba(242,184,75,.16); color: var(--amber); }
.zoom .zoomwrap { position: relative; }
.zoom .zoomwrap img { display: block; }
button:focus-visible, [role="button"]:focus-visible, summary:focus-visible, input:focus-visible, .shot:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
@media (pointer: coarse) { nav button, button.btn, .evchip, .back, .streamselect summary, .idxsort summary, .exportmenu summary, .exportmenuitem, .phasecontrol, .grphdr { min-height: 44px; } .detailedge { width: 44px; height: 44px; } .back, .exportmenu summary { min-width: 44px; } .step { min-height: 44px; } .scrubtrack { width: 44px; } }
@media (prefers-reduced-motion: reduce) { #app.page-enter-forward, #app.page-enter-back { animation: none; } }
@media (max-width: 640px) {
  :root { --page-x: 18px; --page-y: 20px; }
  main { padding-bottom: var(--space-5); }
  h1 { font-size: 21px; }
  .detailheader h1 { font-size: 18px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }
`;

// The standalone viewer. This function is serialized via .toString() and embedded; it must be
// fully self-contained (no references to outer scope) and read its data from #run-data. Plain
// DOM only — no React, no external scripts — so the exported file runs offline anywhere.
function RUN_REPORT_VIEWER(): void {
  const RAW: Partial<ReportPayload> = window.__TB_RUN_DATA__ || {};
  const root = document.getElementById('app') as HTMLElement;
  const esc = (s: unknown) => String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const safeHref = (value: unknown) => {
    try { const url = new URL(String(value || '')); return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null; }
    catch (e) { return null; }
  };

  // Normalize to a sessions[] array. New reports embed { generatedAt, sessions:[...] }; tolerate the
  // older single-run shape ({ meta, trace, llm, shots }) so previously-exported files still open.
  const SESSIONS: SessionPayload[] = (RAW.sessions && RAW.sessions.length)
    ? RAW.sessions
    : [{ meta: RAW.meta || {}, trace: RAW.trace || [], llm: RAW.llm || [], shots: RAW.shots || {}, recordingYaml: (RAW.meta && RAW.meta.recordingYaml) || null, originalYaml: (RAW.meta && RAW.meta.originalYaml) || null }];
  const MULTI = SESSIONS.length > 1;
  const generatedAt = RAW.generatedAt || (SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta.generatedAt) || '';
  const themeKey = 'trailblaze-report-theme';
  const currentTheme = () => document.documentElement?.dataset?.theme === 'light' ? 'light' : 'dark';
  const renderThemeToggle = () => {
    const theme = currentTheme();
    const next = theme === 'dark' ? 'light' : 'dark';
    return `<button class="themetoggle" type="button" data-theme-toggle aria-label="Use ${next} mode" title="Use ${next} mode"><svg class="themeicon sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.6" fill="none" stroke="currentColor" stroke-width="3.2"/><path d="M12 2.5v2M12 19.5v2M5.28 5.28l1.42 1.42M17.3 17.3l1.42 1.42M2.5 12h2M19.5 12h2M5.28 18.72l1.42-1.42M17.3 6.7l1.42-1.42" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg><svg class="themeicon moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 15.1A8 8 0 0 1 8.9 4.5a8 8 0 1 0 10.6 10.6Z" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>`;
  };
  const setTheme = (theme, persist = true) => {
    document.documentElement.dataset.theme = theme;
    if (persist) { try { localStorage.setItem(themeKey, theme); } catch (e) {} }
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => {
      const next = theme === 'dark' ? 'light' : 'dark';
      button.setAttribute('aria-label', `Use ${next} mode`);
      button.setAttribute('title', `Use ${next} mode`);
    });
  };
  if (typeof matchMedia === 'function') {
    const media = matchMedia('(prefers-color-scheme: light)');
    const followSystem = (event) => { try { if (!localStorage.getItem(themeKey)) setTheme(event.matches ? 'light' : 'dark', false); } catch (e) {} };
    if (media.addEventListener) media.addEventListener('change', followSystem);
  }

  // Rebuild this self-contained document around either the full payload or one selected session.
  // No server is needed: screenshots, logs, event streams, and viewer code are already embedded.
  const downloadBlob = (parts, type, filename) => {
    const blob = new Blob(parts, { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = filename; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 0);
  };
  const exportReport = (sessions, filename, title) => {
    const clone = document.documentElement.cloneNode(true) as HTMLElement;
    const app = clone.querySelector('#app'); if (app) app.innerHTML = '';
    const data = clone.querySelector('#tb-run-data');
    if (!data) return;
    const payload = JSON.stringify({ generatedAt, sessions }).replace(/</g, '\\u003c');
    data.textContent = `window.__TB_RUN_DATA__ = ${payload};`;
    const titleEl = clone.querySelector('title'); if (titleEl) titleEl.textContent = title;
    downloadBlob(['<!doctype html>\n' + clone.outerHTML], 'text/html;charset=utf-8', filename);
  };
  const fileSlug = (value) => String(value || 'run').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'run';
  const screenshotEntries = (session) => (session.trace || []).filter((step) => step.screenshotFile && /^data:image\//.test(String((session.shots || {})[step.screenshotFile] || '')))
    .map((step, index) => [`${index + 1}. ${step.label || step.screenshotFile}`, session.shots[step.screenshotFile]]);
  const exportScreenshots = (session) => {
    const screenshots = screenshotEntries(session);
    if (!screenshots.length) return;
    const title = `${session.meta.title || 'Trailblaze run'} screenshots`;
    const cells = screenshots.map(([name, src]) => `<figure><img src="${esc(src)}" alt="${esc(name)}"><figcaption>${esc(name)}</figcaption></figure>`).join('');
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>body{margin:0;padding:24px;background:#0b0e11;color:#f4f5f7;font:14px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}h1{font-size:20px}.gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:18px}figure{margin:0;padding:12px;border:1px solid #2a3038;border-radius:10px;background:#14181d}img{display:block;width:100%;height:auto;border-radius:6px;background:#000}figcaption{margin-top:8px;color:#a8b0bc;font-size:12px;word-break:break-word}</style></head><body><h1>${esc(title)}</h1><div class="gallery">${cells}</div></body></html>`;
    downloadBlob([html], 'text/html;charset=utf-8', `trailblaze_run_${fileSlug(session.meta.title)}_screenshots.html`);
  };
  const logPayload = (session) => ({
    run: session.meta || {},
    deviceLog: session.deviceLog || null,
    network: session.network || [],
    events: session.events || [],
    llm: session.llm || [],
  });
  const hasLogs = (session) => Boolean(session.deviceLog || (session.network && session.network.length) || (session.events && session.events.length) || (session.llm && session.llm.length));
  const exportLogs = (session) => {
    if (!hasLogs(session)) return;
    downloadBlob([JSON.stringify(logPayload(session), null, 2)], 'application/json;charset=utf-8', `trailblaze_run_${fileSlug(session.meta.title)}_logs.json`);
  };

  // `D` is the session currently in view; every renderer reads D.trace / D.llm / D.shots / D.meta /
  // D.recordingYaml, so the single-run renderers below are unchanged across a session switch.
  let D: SessionPayload = SESSIONS[0];
  const st = { view: MULTI ? 'index' : 'detail', session: 0, tab: 'timeline', step: 0, llmSel: 0, evStream: 0, tlStreams: [], tlMenuOpen: false, trailheadOpen: true, trailOpen: true, collapsedGroups: [], lightboxAll: false, runSort: 'grouped', runFilter: '', playing: false, vSpeed: 1, pageTransition: '' };
  const TIMELINE_PLAY_MS = 900; // per-step dwell when auto-playing the screenshot timeline
  // Timeline screenshot-playback timer. Declared up here (before openSession, which stops it) so the
  // init-time openSession() call for a single-session report doesn't hit a temporal-dead-zone ref.
  let timelineTimer = null;
  const stopTimeline = () => { if (timelineTimer) { clearInterval(timelineTimer); timelineTimer = null; } st.playing = false; };

  // Open a session's detail. Failed runs lead with the actionable tool; passing runs start at the
  // authored trail so any recovery summary remains the first thing visible above it. Incidental
  // failed polling rows in a passing run are intentionally ignored.
  const openSession = (i) => {
    stopTimeline(); st.session = i; D = SESSIONS[i]; st.view = 'detail'; st.tab = 'timeline'; st.llmSel = 0; st.evStream = 0; st.tlStreams = []; st.tlMenuOpen = false; st.trailOpen = true; st.collapsedGroups = []; st.lightboxAll = false;
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    const failedTool = runFailed ? D.trace.findIndex((t) => !t.objective && !t.ok) : -1;
    const firstFail = failedTool >= 0 ? failedTool : (runFailed ? D.trace.findIndex((t) => !t.ok) : -1);
    st.step = firstFail >= 0 ? D.trace[firstFail].i : ((D.trace[0] && D.trace[0].i) || 0);
    const trailheadStart = D.trace.findIndex((t) => t.objective && t.trailhead);
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    const trailheadActions = trailheadStart >= 0 ? (trailStart >= 0 ? trailStart : D.trace.length) - trailheadStart - 1 : 0;
    const failureIsInTrailhead = firstFail >= 0 && trailheadStart >= 0 && (trailStart < 0 || firstFail < trailStart);
    // Setup is supporting context. Keep small setup visible, but collapse high-volume setup so the
    // authored Trail remains the dominant content. A setup failure overrides that default.
    st.trailheadOpen = trailStart < 0 || failureIsInTrailhead || trailheadActions <= 12;
    if (firstFail < 0 && !st.trailheadOpen && trailStart >= 0) st.step = D.trace[trailStart].i;
  };
  const revealTimelineStep = (stepId) => {
    const index = D.trace.findIndex((t) => t.i === stepId);
    if (index < 0) return;
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    if (trailStart >= 0 && index >= trailStart) st.trailOpen = true;
    else if (D.trace.some((t) => t.objective && t.trailhead)) st.trailheadOpen = true;
    let objective = null;
    for (let i = index; i >= 0; i--) { if (D.trace[i].objective) { objective = D.trace[i]; break; } }
    if (objective) st.collapsedGroups = st.collapsedGroups.filter((id) => id !== objective.i);
  };
  if (!MULTI) openSession(0);

  // Report state lives in query parameters so copied URLs communicate their selected run, view,
  // and step. Only these owned keys are changed: signed-artifact parameters such as `jwt` survive
  // every navigation. Legacy hash routes remain readable and are canonicalized on initial load.
  const routeKeys = ['view', 'runs', 'run', 'tab', 'step', 'streams', 'llm', 'stream', 'sort', 'filter'];
  const readRoute = () => {
    if (typeof location === 'undefined') return null;
    const query = new URLSearchParams(String(location.search || ''));
    const hasQueryRoute = routeKeys.some((key) => query.has(key));
    const p = hasQueryRoute ? query : new URLSearchParams(String(location.hash || '').replace(/^#/, ''));
    if (p.get('view') === 'runs' || p.has('runs')) return { view: 'index', sort: p.get('sort') || 'grouped', filter: p.get('filter') || '' };
    if (!p.has('run') && !p.has('tab') && !p.has('step')) return null;
    return {
      view: 'detail', session: Number(p.get('run') || 0), tab: p.get('tab') || 'timeline',
      step: p.has('step') ? Number(p.get('step')) : null,
      llm: Number(p.get('llm') || 0), stream: Number(p.get('stream') || 0),
      streams: p.get('streams'),
    };
  };
  const applyRoute = () => {
    const r = readRoute();
    if (!r) return;
    if (r.view === 'index' && MULTI) {
      stopTimeline(); st.view = 'index';
      if (['grouped', 'original', 'name'].indexOf(r.sort) >= 0) st.runSort = r.sort;
      st.runFilter = r.filter === 'self-healed' ? 'self-healed' : '';
      return;
    }
    const si = Number.isFinite(r.session) ? Math.max(0, Math.min(SESSIONS.length - 1, r.session)) : 0;
    openSession(si);
    const requestedTab = r.tab === 'grid' ? 'lightbox' : r.tab;
    const allowed = ['timeline', 'lightbox', 'video', 'llm', 'config', 'recording', 'device', 'network', 'events', 'info'];
    if (allowed.indexOf(requestedTab) >= 0) st.tab = requestedTab;
    if (r.step != null && Number.isFinite(r.step) && D.trace.some((t) => t.i === r.step)) { st.step = r.step; revealTimelineStep(st.step); }
    if (Number.isFinite(r.llm) && r.llm >= 0 && r.llm < D.llm.length) st.llmSel = r.llm;
    if (Number.isFinite(r.stream) && r.stream >= 0 && r.stream < (D.events || []).length) st.evStream = r.stream;
    if (r.streams != null) st.tlStreams = r.streams.split(',').map(Number).filter((i) => Number.isInteger(i) && i >= 0 && i < (D.events || []).length);
  };
  const writeRoute = (replace) => {
    if (typeof history === 'undefined' || typeof location === 'undefined') return;
    const params = new URLSearchParams(String(location.search || ''));
    routeKeys.forEach((key) => params.delete(key));
    if (st.view === 'index') {
      params.set('view', 'runs');
      if (st.runSort !== 'grouped') params.set('sort', st.runSort);
      if (st.runFilter) params.set('filter', st.runFilter);
    } else {
      params.set('run', String(st.session));
      params.set('tab', st.tab);
      if (st.tab === 'timeline' && Number.isFinite(st.step)) params.set('step', String(st.step));
      if (st.tab === 'timeline' && st.tlStreams.length) params.set('streams', st.tlStreams.join(','));
      if (st.tab === 'llm' && st.llmSel) params.set('llm', String(st.llmSel));
      if (st.tab === 'events' && st.evStream) params.set('stream', String(st.evStream));
    }
    const search = params.toString();
    const legacyHash = /^#(?:runs(?:&|$)|run=|tab=|step=)/.test(String(location.hash || ''));
    const next = `${String(location.pathname || '')}${search ? `?${search}` : ''}${legacyHash ? '' : String(location.hash || '')}`;
    const current = `${String(location.pathname || '')}${String(location.search || '')}${String(location.hash || '')}`;
    if (current === next) return;
    history[replace ? 'replaceState' : 'pushState'](null, '', next);
  };
  applyRoute();
  writeRoute(true);

  const stepCat = (t) => {
    if (!t.ok) return 'fail';
    const tool = String(t.tool || ''); const lbl = String(t.label || '').toLowerCase();
    if (tool === 'agent step' || tool.indexOf('llm') === 0) return 'llm';
    if (lbl.indexOf('assert') === 0 || lbl.indexOf('verify') === 0 || tool.toLowerCase().indexOf('assert') >= 0) return 'assert';
    return 'tool';
  };
  const catColor = { fail: 'var(--fail)', llm: 'var(--run)', assert: 'var(--pass)', tool: 'var(--pass)' };
  const stepIcon = (t) => {
    const label = String(t.label || '').toLowerCase();
    const tool = String(t.tool || '').toLowerCase();
    const assertion = label.indexOf('assert') === 0 || label.indexOf('verify') === 0 || tool.indexOf('assert') >= 0;
    const tap = label.indexOf('tap') === 0 || label.indexOf('longpress') === 0 || label.indexOf('long press') === 0;
    if (!t.ok) return { cls: 'failure', glyph: '×' };
    if (assertion) return { cls: 'verify', glyph: '✓' };
    if (tap) return { cls: 'tap', glyph: '👆' };
    return { cls: 'dot', glyph: '' };
  };

  // Error producers do not share one wire shape: JVM failures usually arrive as
  // `qualified.Exception: message\n\tat ...`, JS failures use `TypeError: message`, and plugins
  // may serialize a structured error object. Normalize those forms for one digestible summary.
  const parseFailure = (raw) => {
    const text = String(raw || '').replace(/\r\n/g, '\n').trim();
    if (!text) return null;
    try {
      const value = JSON.parse(text);
      const source = value && typeof value.error === 'object' ? value.error : value;
      if (source && typeof source === 'object') {
        const type = source.type || source.name || source.errorType || source.class || 'Error';
        const message = source.message || source.errorMessage || source.reason || source.detail || text;
        const stack = source.stack || source.stackTrace || source.stacktrace || '';
        return { type: String(type), message: String(message), stack: String(stack).trim() };
      }
    } catch (_) { /* Plain-text exception; parsed below. */ }
    const lines = text.split('\n');
    const first = lines[0].trim();
    const typed = first.match(/^(?:Caused by:\s*)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:Error|Exception|Failure|Throwable))(?::\s*(.*))?$/);
    const stackAt = lines.findIndex((line, i) => i > 0 && /^\s*(?:at\s|Caused by:|Suppressed:|\.\.\. \d+ more)/.test(line));
    const beforeStack = stackAt >= 0 ? lines.slice(0, stackAt) : lines;
    const stack = stackAt >= 0 ? lines.slice(stackAt).join('\n').trim() : '';
    const messageLines = typed ? [typed[2] || '', ...beforeStack.slice(1)] : beforeStack;
    return {
      type: typed ? typed[1] : 'Error',
      message: messageLines.join('\n').trim() || first,
      stack,
    };
  };

  const renderFailureSummary = (groups) => {
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    if (!runFailed && !(D.meta && D.meta.error)) return '';
    const failedTool = D.trace.find((t) => !t.objective && !t.ok);
    const errorStep = (failedTool && failedTool.err) ? failedTool : D.trace.find((t) => !t.ok && t.err);
    const failedStep = failedTool || errorStep;
    const parsed = parseFailure((errorStep && errorStep.err) || (D.meta && D.meta.error));
    if (!parsed) return '';
    const failedGroup = failedStep && groups.find((g) => g.header === failedStep || g.items.indexOf(failedStep) >= 0);
    const objective = failedGroup && failedGroup.header;
    const frames = parsed.stack ? parsed.stack.split('\n').filter((line) => /^\s*at\s/.test(line)).length : 0;
    const title = objective ? (objective.trailhead ? 'Trailhead failed' : `Step ${failedGroup.num} failed`) : 'Run failure';
    const context = objective ? objective.label : (failedStep ? failedStep.label : 'Run-level error');
    const typeName = parsed.type.split('.').pop() || parsed.type;
    const yamlLink = failedStep && (D.recordingYaml || D.originalYaml) ? `<button type="button" class="yamllink" data-yaml-step="${failedStep.i}">View YAML</button>` : '';
    return `<section class="failurepanel" aria-labelledby="failure-title">
      <div class="failurehead"><span class="failureicon" aria-hidden="true">!</span><span class="failuretitle" id="failure-title">${esc(title)}</span><span class="failurecontext">${esc(context)}</span></div>
      ${failedTool ? `<div class="failuretool"><div class="k">Failed tool call</div><div class="failuretoolvalue"><span class="failuretoolname">${esc(failedTool.label)}</span>${failedTool.tool ? `<code class="failuretoolargs mono">${esc(failedTool.tool)}</code>` : ''}${yamlLink}</div></div>` : yamlLink}
      <div class="failurebody"><div class="failurefield"><div class="k">Type</div><code class="failuretype mono" title="${esc(parsed.type)}">${esc(typeName)}</code></div><div class="failurefield"><div class="k">Message</div><div class="failuremessage">${esc(parsed.message).replace(/\n/g, '<br>')}</div></div></div>
      ${parsed.stack ? `<details class="failurestack" open><summary>Stack trace<span class="frames">${frames} frame${frames === 1 ? '' : 's'}</span></summary><pre class="mono">${esc(parsed.stack)}</pre></details>` : ''}
    </section>`;
  };

  const renderSelfHealSummary = (groups) => {
    const status = String((D.meta && D.meta.status) || '').toLowerCase();
    if (!(D.meta && D.meta.selfHeal) || (status !== 'passed' && status !== 'success')) return '';
    const healedGroup = groups.find((g) => g.header && g.header.selfHeal);
    if (!healedGroup) return `<section class="selfhealpanel" aria-labelledby="selfheal-title"><div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">Self-healed</span><span class="selfhealcontext">Recorded actions were repaired during this run</span></div></section>`;
    const healed = healedGroup.header;
    const parsed = parseFailure(healed.selfHealError);
    const title = healed.trailhead ? 'Trailhead self-healed' : `Step ${healedGroup.num} self-healed`;
    return `<section class="selfhealpanel" aria-labelledby="selfheal-title">
      <div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">${esc(title)}</span><span class="selfhealcontext">${esc(healed.label)}</span></div>
      <div class="selfhealbody">
        <div class="selfhealfield"><div class="k">Failed recorded action</div><span class="selfhealtoolname">${esc(healed.selfHealTool || 'Recorded action')}</span></div>
        <div class="selfhealfield"><div class="k">Recovery</div><div class="selfhealmessage">Trailblaze used AI to recover this step.${parsed && parsed.message ? ` <span title="${esc(parsed.type)}">${esc(parsed.message)}</span>` : ''}</div>${D.recordingYaml || D.originalYaml ? `<button type="button" class="yamllink" data-yaml-step="${healed.i}">View YAML</button>` : ''}</div>
      </div>
    </section>`;
  };

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

  // Group flat trace under objective rows -> { header, num, items } (same shape as the app's
  // StepStack). The trailhead (step 0) keeps num 0 so the trail steps still read STEP 1..N.
  const groupTrace = () => {
    const gs = []; let cur = null; let n = 0;
    for (const t of D.trace) {
      if (t.objective) { cur = { header: t, num: t.trailhead ? 0 : ++n, items: [] }; gs.push(cur); }
      else { if (!cur) { cur = { header: null, num: 0, items: [] }; gs.push(cur); } cur.items.push(t); }
    }
    return gs;
  };

  // First wall-clock timestamp in the trace — the run-clock zero every row's elapsed offset is
  // measured from (parity with the legacy report's elapsed-from-session-start gutter).
  const traceT0 = () => { for (const t of D.trace) { if (t.ts != null) return t.ts; } return null; };
  const fmtDur = (ms) => !ms ? '' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
  const fmtClock = (ms) => `${Math.floor((ms || 0) / 60000)}:${String(Math.floor(((ms || 0) % 60000) / 1000)).padStart(2, '0')}`;

  // Same time-compression model as Trail Runner's VerticalScrubber: real gaps are preserved but
  // clamped so a fast burst stays clickable and a long idle period does not consume the rail.
  const timelineAxis = () => {
    const tsVals = D.trace.map((t) => t.ts).filter((x) => x != null);
    const lo = tsVals.length ? tsVals.reduce((a, b) => b < a ? b : a, tsVals[0]) : 0;
    const hi = tsVals.length ? tsVals.reduce((a, b) => b > a ? b : a, tsVals[0]) : 0;
    const haveTs = tsVals.length >= 2 && hi > lo;
    const pos = []; const real = []; let cum = 0; let prevTs = null;
    D.trace.forEach((t, i) => {
      if (i > 0) {
        const gap = haveTs && t.ts != null && prevTs != null ? t.ts - prevTs : Math.max(t.ms || 0, 350);
        cum += Math.max(350, Math.min(4000, gap));
      }
      pos.push(cum);
      const raw = haveTs && t.ts != null ? t.ts - lo : (i > 0 ? real[i - 1] : 0);
      real.push(i > 0 ? Math.max(real[i - 1], raw) : Math.max(0, raw));
      if (t.ts != null) prevTs = t.ts;
    });
    const span = Math.max(1, cum);
    const stepFrac = pos.map((p) => p / span);
    const tsFrac = (ms) => {
      if (ms == null || !haveTs || !D.trace.length) return null;
      const r = ms - lo;
      if (r <= real[0]) return stepFrac[0];
      for (let i = 1; i < D.trace.length; i++) {
        if (r <= real[i]) {
          const d = real[i] - real[i - 1] || 1;
          return Math.min(1, Math.max(0, stepFrac[i - 1] + ((r - real[i - 1]) / d) * (stepFrac[i] - stepFrac[i - 1])));
        }
      }
      return 1;
    };
    return { stepFrac, tsFrac, totalMs: haveTs ? Math.max(1, hi - lo) : span };
  };

  // Match Trail Runner's high-volume stream behavior: streams are opt-in on the timeline. The
  // selected indices live in the URL so a filtered timeline can be shared exactly as viewed.
  const streamEvents = () => (D.events || []).flatMap((stream, streamIndex) =>
    st.tlStreams.indexOf(streamIndex) < 0 ? []
      : (stream.events || []).map((e, n) => ({ ...e, stream: stream.name, streamIndex, key: `${stream.name}-${n}` })),
  ).sort((a, b) => (a.t || 0) - (b.t || 0));

  const eventBuckets = (events) => {
    const buckets = D.trace.map(() => []);
    const timedSteps = D.trace
      .map((t, i) => ({ i, t: t.ts }))
      .filter((step) => step.t != null);
    let timedStep = -1;
    events.forEach((e) => {
      let at = 0;
      if (e.t != null) {
        while (timedStep + 1 < timedSteps.length && timedSteps[timedStep + 1].t <= e.t) timedStep++;
        if (timedStep >= 0) at = timedSteps[timedStep].i;
      }
      if (buckets[at]) buckets[at].push(e);
    });
    return buckets;
  };

  // Equal OKLCH lightness/chroma keeps qualitative stream colors visually balanced. Advancing by
  // the golden angle makes adjacent producer colors distinct without giving any stream a semantic
  // status color; the producer name and diamond remain redundant cues when color is unavailable.
  const streamColor = (index) => `oklch(74% .14 ${(70 + index * 137.508) % 360})`;

  const eventPayloadCache = new WeakMap();
  const parseEventJsonish = (value, depth = 0) => {
    if (depth > 8 || value == null) return value;
    if (typeof value !== 'string') {
      if (Array.isArray(value)) return value.map((v) => parseEventJsonish(v, depth + 1));
      if (typeof value === 'object') {
        const out = {};
        Object.keys(value).forEach((k) => { out[k] = parseEventJsonish(value[k], depth + 1); });
        return out;
      }
      return value;
    }
    const raw = value.trim();
    if (!raw) return value;
    const candidates = [raw];
    if (raw.indexOf('\\"') >= 0 || raw.indexOf('\\\\') >= 0) {
      // Let the JSON parser decode one quoting layer at a time. Manually replacing escape
      // sequences here can double-unescape producer-controlled text and change its meaning.
      candidates.push(`"${raw}"`);
    }
    for (const candidate of candidates) {
      try {
        const parsed = JSON.parse(candidate);
        return parseEventJsonish(parsed, depth + 1);
      } catch (_) {}
    }
    return value;
  };

  const eventValueText = (value) => {
    if (value == null) return '';
    if (typeof value === 'string') return value;
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    return JSON.stringify(value);
  };

  const eventFieldKinds: Array<[string, string[]]> = [
    ['Event', ['event', 'eventname', 'eventvalue', 'name', 'label', 'title', 'message']],
    ['Action', ['action', 'actiontext', 'blockeraction', 'cdfaction']],
    ['Entity', ['entity', 'cdfentity', 'namespace']],
    ['Path', ['path', 'urlpath', 'finalpath', 'uniquefinalpath']],
    ['Status', ['status', 'statuscode', 'code']],
    ['Method', ['method']],
    ['Journey', ['journey', 'journeyname', 'flow', 'clientscenario']],
    ['ID', ['id', 'messageuuid', 'blockerid', 'flowtoken']],
  ];

  const normalizeEventPayload = (event, source) => {
    const cached = eventPayloadCache.get(event);
    if (cached) return cached;
    const raw = String(event.d == null ? '' : event.d);
    const parsed = parseEventJsonish(raw);
    const found = new Map();
    const queue = [{ value: parsed, depth: 0 }];
    let visited = 0;
    while (queue.length && visited++ < 240) {
      const current = queue.shift();
      const value = current.value;
      if (current.depth > 6 || value == null || typeof value !== 'object') continue;
      if (Array.isArray(value)) {
        value.slice(0, 40).forEach((item) => queue.push({ value: item, depth: current.depth + 1 }));
        continue;
      }
      Object.keys(value).slice(0, 80).forEach((key) => {
        const child = value[key];
        const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, '');
        if (!found.has(normalized) && child != null && child !== '') found.set(normalized, child);
        if (child && typeof child === 'object') queue.push({ value: child, depth: current.depth + 1 });
      });
    }
    const fields = [];
    eventFieldKinds.forEach(([label, names]) => {
      const name = names.find((candidate) => found.has(candidate));
      const text = name ? eventValueText(found.get(name)) : '';
      if (text && !fields.some((field) => field.value === text)) fields.push({ label, value: text });
    });
    const labelField = eventFieldKinds[0][1].find((name) => found.has(name));
    const label = (labelField && eventValueText(found.get(labelField))) || source || 'Event';
    let pretty = raw;
    try { if (parsed !== raw) pretty = JSON.stringify(parsed, null, 2); } catch (_) {}
    const normalized = { raw, parsed, fields: fields.slice(0, 8), semanticLabel: labelField ? eventValueText(found.get(labelField)) : '', pretty };
    eventPayloadCache.set(event, normalized);
    return normalized;
  };

  const renderEventPayload = (event, source) => {
    const { fields, semanticLabel, pretty } = normalizeEventPayload(event, source);
    const label = semanticLabel || source || 'Event';
    const fieldHtml = fields.length ? `<div class="eventfields">${fields.map((f) => `<div class="eventfield"><div class="k">${esc(f.label)}</div><div class="v">${esc(f.value)}</div></div>`).join('')}</div>` : '';
    return `<div class="eventcard"><div class="eventsummary"><span class="eventlabel">${esc(label)}</span>${source ? `<span class="eventsource">${esc(source)}</span>` : ''}</div>${fieldHtml}<details class="eventdetails"><summary>Raw JSON</summary><pre class="mono">${esc(pretty)}</pre></details></div>`;
  };

  const streamGroupHtml = (events) => {
    if (!events.length) return '';
    const t0 = traceT0();
    const groups = [];
    events.forEach((e) => {
      let group = groups.find((g) => g.stream === e.stream);
      if (!group) { group = { stream: e.stream, streamIndex: e.streamIndex, events: [] }; groups.push(group); }
      group.events.push(e);
    });
    return groups.map((group) => {
      const rel = (e) => e.t != null && t0 != null ? `+${((e.t - t0) / 1000).toFixed(2)}s` : '';
      const items = group.events.map((e) => {
        const { semanticLabel, pretty } = normalizeEventPayload(e, 'Event');
        const label = semanticLabel || 'Event';
        return `<details class="timelineevent"><summary><span class="streamtime">${esc(rel(e))}</span><span class="timelineeventlabel">${esc(label)}</span><span class="timelineeventchev" aria-hidden="true"></span></summary><pre class="mono">${esc(pretty)}</pre></details>`;
      }).join('');
      return `<details class="streamrow" style="--stream-color:${streamColor(group.streamIndex)}" open><summary><span class="streamdot"></span><span class="streamtype">${esc(group.stream)}</span><span class="streamtime">${group.events.length} event${group.events.length === 1 ? '' : 's'}</span></summary><div class="streamitems timelineeventitems">${items}</div></details>`;
    }).join('');
  };

  const scrubberHtml = (axis, events, pos) => {
    const ticks = D.trace.map((t, i) => `<span class="scrubtick" aria-hidden="true" style="top:calc(${(axis.stepFrac[i] || 0) * 100}% - 1px);background:${catColor[stepCat(t)]}"></span>`).join('');
    const eventTicks = events.map((e) => {
      const f = axis.tsFrac(e.t); if (f == null) return '';
      return `<span class="scrubtick" aria-hidden="true" title="${esc(e.stream)}" style="top:calc(${f * 100}% - 1px);background:${streamColor(e.streamIndex)}"></span>`;
    }).join('');
    const frac = axis.stepFrac[pos] || 0;
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    const hasTrailhead = D.trace.some((t) => t.objective && t.trailhead);
    const trailFrac = trailStart >= 0 ? (axis.stepFrac[trailStart] || 0) : 1;
    const rail = hasTrailhead && trailStart < 0
      ? `<div class="scrubline setup" style="height:100%"></div>`
      : hasTrailhead
      ? `<div class="scrubline setup" style="height:${trailFrac * 100}%"></div><div class="scrubline trail" style="top:${trailFrac * 100}%"></div><span class="scrubphasebreak" style="top:${trailFrac * 100}%" title="Trail begins" aria-hidden="true"></span>`
      : `<div class="scrubline trail" style="top:0"></div>`;
    const phaseLabel = hasTrailhead && trailStart < 0 ? 'Timeline for Trailhead setup. The dotted rail marks deterministic setup.'
      : hasTrailhead ? 'Timeline. Dotted segment is Trailhead setup; solid segment is the authored Trail.'
      : 'Timeline for the authored Trail.';
    const current = D.trace[pos];
    const phase = hasTrailhead && (trailStart < 0 || pos < trailStart) ? 'Trailhead' : 'Trail';
    const valueText = `${phase}, item ${pos + 1} of ${D.trace.length}: ${(current && current.label) || 'Timeline item'}`;
    return `<div class="scrub"><div class="scrubclock">0:00</div><div class="scrubtrack" data-scrub role="slider" tabindex="0" aria-label="${phaseLabel}" aria-valuemin="1" aria-valuemax="${D.trace.length}" aria-valuenow="${pos + 1}" aria-valuetext="${esc(valueText)}">${rail}${ticks}${eventTicks}<div class="scrubhead" style="top:${frac * 100}%"></div></div><div class="scrubclock">${fmtClock(axis.totalMs)}</div></div>`;
  };

  const stepRowHtml = (t, child) => {
    const cat = stepCat(t); const sel = t.i === st.step;
    const icon = stepIcon(t);
    const kids = (t.children || []).length
      ? `<div class="kids">${t.children.map((c) => `<div><span class="mono">${esc(c.label)}</span> <span class="kt mono">${esc(c.tool)}</span></div>`).join('')}</div>` : '';
    const count = t.count ? ` <span style="color:var(--sub);font-variant-numeric:tabular-nums">×${t.count}</span>` : '';
    const t0 = traceT0();
    const rel = (t.ts != null && t0 != null) ? `+${((t.ts - t0) / 1000).toFixed(1)}s` : '';
    const dur = fmtDur(t.ms);
    const time = (rel || dur) ? `<span class="ts">${rel}${dur ? `<span class="dur">${dur}</span>` : ''}</span>` : '';
    return `<div class="step${sel ? ' sel' : ''}${child ? ' child' : ''}${t.selfHealSource ? ' selfheal' : ''}" data-step="${t.i}" role="button" tabindex="0"${sel ? ' aria-current="step"' : ''}>
      ${child ? '' : `<span class="num">${t.i}</span>`}
      <span class="ic ${icon.cls}"${icon.cls === 'dot' ? ` style="--icon-color:${catColor[cat]}"` : ''} aria-hidden="true">${icon.glyph}</span>
      <div style="flex:1;min-width:0">
        <div class="lbl">${esc(t.label)}${count}</div>
        ${t.tool ? `<div class="tl-tool mono">${esc(t.tool)}</div>` : ''}
        ${t.note ? `<div class="note">${esc(t.note)}</div>` : ''}
        ${kids}
      </div>
      ${time}
    </div>`;
  };

  const renderTimeline = () => {
    const groups = groupTrace();
    const failureSummary = renderFailureSummary(groups);
    const selfHealSummary = renderSelfHealSummary(groups);
    if (!D.trace.length) return `${failureSummary}${selfHealSummary}<div class="empty">This run didn't emit any agent-task steps.</div>`;
    const streams = D.events || [];
    const events = streamEvents();
    const buckets = eventBuckets(events);
    const streamChooser = streams.length ? `<details class="streamselect" data-streamselect${st.tlMenuOpen ? ' open' : ''}><summary><span class="streamselectoricon" aria-hidden="true"></span><span>Event streams</span><span class="selection">${st.tlStreams.length} of ${streams.length}</span><span class="chevron" aria-hidden="true"></span></summary><div class="streammenu"><div class="streammenuhead"><span>Include in timeline</span><span class="streammenuactions"><button type="button" data-tlstreams="all">Select all</button><button type="button" data-tlstreams="none">Clear</button></span></div>${streams.map((stream, i) => `<label class="streamoption" style="--stream-color:${streamColor(i)}"><input type="checkbox" data-tlstream="${i}"${st.tlStreams.indexOf(i) >= 0 ? ' checked' : ''}><span class="streamoptiondot" aria-hidden="true"></span><span class="streamname">${esc(stream.name)}</span><span class="streamcount">${stream.total || (stream.events || []).length}</span></label>`).join('')}</div></details>` : '';
    const withEvents = (t, child) => {
      const at = idxOf(t.i);
      return stepRowHtml(t, child) + streamGroupHtml(buckets[at] || []);
    };
    const hasSteps = groups.some((g) => g.header);
    let stepsHtml;
    if (!hasSteps) {
      stepsHtml = D.trace.map((t) => withEvents(t, false)).join('');
    } else {
      const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
      const groupsHtml = (phaseGroups) => phaseGroups.map((g) => {
        // The header dot reports the OBJECTIVE's outcome (from its Complete bookend), not the worst
        // row inside it for a passing run: an assertion poll can fail and recover. For a run whose
        // final status is failed, a failed child tool also marks its authored step as failed.
        const failed = g.header ? (!g.header.ok || (runFailed && g.items.some((t) => !t.ok))) : g.items.some((t) => !t.ok);
        const selfHealed = !!(g.header && g.header.selfHeal);
        const isTrailhead = g.header && g.header.trailhead;
        const groupOpen = !g.header || st.collapsedGroups.indexOf(g.header.i) < 0;
        const groupSelected = g.header && g.header.i === st.step;
        const hdr = g.header ? `<button type="button" class="grphdr${isTrailhead ? ' trailhead' : ''}${groupSelected ? ' sel' : ''}" data-group="${g.header.i}" aria-expanded="${groupOpen}"${groupSelected ? ' aria-current="step"' : ''}>
            <span class="chip">${isTrailhead ? 'TRAILHEAD' : `STEP ${g.num}`}</span>
            <span class="dot" style="background:${failed ? 'var(--fail)' : selfHealed ? 'var(--amber)' : 'var(--pass)'}"></span>
            ${g.items.length ? `<span style="font-size:11px;color:var(--sub)">${g.items.length} action${g.items.length === 1 ? '' : 's'}</span>` : ''}
            <span class="groupchev" aria-hidden="true"></span>
            <span class="lbl" style="width:100%">${esc(g.header.label)}</span>
          </button>` : '';
        const headerEvents = g.header ? streamGroupHtml(buckets[idxOf(g.header.i)] || []) : '';
        return `<div class="stepgroup${failed ? ' failed' : selfHealed ? ' selfhealed' : ''}">${hdr}<div class="stepgroupbody"${groupOpen ? '' : ' hidden'}>${headerEvents}${g.items.map((t) => withEvents(t, hasSteps)).join('')}</div></div>`;
      }).join('');
      const trailheadGroups = groups.filter((g) => g.header && g.header.trailhead);
      const trailGroups = groups.filter((g) => !g.header || !g.header.trailhead);
      const trailStepCount = trailGroups.filter((g) => g.header).length;
      const phaseStats = (phaseGroups) => {
        const actions = phaseGroups.reduce((n, g) => n + g.items.length, 0);
        const duration = phaseGroups.reduce((ms, g) => ms + g.items.reduce((sum, t) => sum + (t.ms || 0), 0), 0);
        return `${actions} action${actions === 1 ? '' : 's'}${duration ? ` · ${fmtDur(duration)}` : ''}`;
      };
      stepsHtml = `<div class="timelinephases">
        ${trailheadGroups.length ? `<section class="tlphase trailhead" aria-labelledby="trailhead-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trailhead" aria-expanded="${st.trailheadOpen}"><span class="name" id="trailhead-heading">Trailhead</span><span class="desc">Deterministic setup · step 0 · ${phaseStats(trailheadGroups)}</span><span class="phasechev" aria-hidden="true"></span></button></div><div class="tlphasebody"${st.trailheadOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailheadGroups)}</div></div></section>` : ''}
        ${trailGroups.length ? `<section class="tlphase" aria-labelledby="trail-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trail" aria-expanded="${st.trailOpen}"><span class="name" id="trail-heading">Trail</span><span class="desc">${trailStepCount} test step${trailStepCount === 1 ? '' : 's'} · ${phaseStats(trailGroups)}</span><span class="phasechev" aria-hidden="true"></span></button></div><div class="tlphasebody"${st.trailOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailGroups)}</div></div></section>` : ''}
      </div>`;
    }
    const cur = D.trace.find((t) => t.i === st.step) || D.trace[0];
    const shot = shotForStep(st.step);
    const pos = idxOf(st.step);
    const axis = timelineAxis();
    return `<div class="tl">
      <div class="timeline-list">${failureSummary}${selfHealSummary}${streamChooser ? `<div class="timelinecontrols">${streamChooser}</div>` : ''}${hasSteps ? stepsHtml : `<div class="steps">${stepsHtml}</div>`}</div>
      ${scrubberHtml(axis, events, pos)}
      <div class="preview">
        <div class="deviceplayer${shot ? '' : ' empty'}">
          ${shot ? `<div class="shotwrap"><img class="shot" id="shot" src="${shot}" role="button" tabindex="0" alt="${esc(cur.label)} at step ${pos + 1}" />${cur.screenshotFile ? markHtml(cur) : ''}</div>` : `<div class="noshot">No screenshot captured before this step.</div>`}
          <div class="pvctl" aria-label="Frame controls">
            <button class="btn transport" id="prev" aria-label="Previous frame" title="Previous frame"${pos <= 0 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
            <button class="btn play transport" id="tlplay" aria-label="${st.playing ? 'Pause' : 'Play'} timeline" title="${st.playing ? 'Pause' : 'Play'} timeline">${st.playing ? '<span class="transporticon pauseicon" aria-hidden="true"></span>' : '<svg class="transporticon playicon" viewBox="0 0 24 24" aria-hidden="true"><path d="M7.7 5.8c0-1.25 1.37-2.02 2.44-1.38l9.18 5.52c1.03.62 1.03 2.11 0 2.73l-9.18 5.52c-1.07.64-2.44-.13-2.44-1.38Z" fill="currentColor"/></svg>'}</button>
            <button class="btn transport" id="next" aria-label="Next frame" title="Next frame"${pos >= D.trace.length - 1 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
          </div>
        </div>
      </div>
    </div>`;
  };

  const fmtN = (n) => n == null ? '—' : n.toLocaleString();
  const fmtCost = (c) => c == null ? '—' : c === 0 ? '$0.000000' : c < 0.000001 ? '<$0.000001' : '$' + c.toFixed(6);
  const decisionOf = (r) => { const t = (r.response || []).find((p) => p.kind === 'tool'); return t ? t.tool : ((r.response || []).find((p) => p.kind === 'text') ? 'text reply' : r.label); };

  const viewPage = (title, meta, body, className = '') => `<section class="viewpage${className ? ` ${className}` : ''}">
    <div class="viewhead"><h2 class="viewtitle">${esc(title)}</h2>${meta ? `<span class="viewmeta">${esc(meta)}</span>` : ''}</div>
    <div class="viewbody">${body}</div>
  </section>`;

  const renderLlm = () => {
    if (!D.llm.length) return viewPage('LLM', '', `<div class="empty">This run has no LLM request logs.</div>`);
    const totals = D.llm.reduce((a, r) => ({ i: a.i + (r.inputTokens || 0), o: a.o + (r.outputTokens || 0), c: a.c + (r.totalCost || 0), k: a.k + (r.cacheReadTokens || 0), d: a.d + (r.durationMs || 0) }), { i: 0, o: 0, c: 0, k: 0, d: 0 });
    const list = D.llm.map((r, i) => `<div class="callrow${i === st.llmSel ? ' sel' : ''}" data-llm="${i}" role="button" tabindex="0"${i === st.llmSel ? ' aria-current="true"' : ''}>
        <div class="d">${i + 1}. ${esc(decisionOf(r))}</div>
        <div class="m">in ${fmtN(r.inputTokens)} · out ${fmtN(r.outputTokens)}${r.durationMs ? ' · ' + (r.durationMs / 1000).toFixed(1) + 's' : ''}</div>
      </div>`).join('');
    const r = D.llm[st.llmSel] || D.llm[0];
    const respParts = (r.response || []).length ? (r.response || []).map((p) => p.kind === 'tool'
      ? `${p.reasoning ? `<div class="reason">${esc(p.reasoning)}</div>` : ''}<div class="tool mono">⚙ ${esc(p.tool)}</div>${p.args && p.args !== '{}' ? `<pre>${esc(p.args)}</pre>` : ''}`
      : `<div class="reason">${esc(p.text)}</div>`).join('') : '<div style="color:var(--sub);font-size:12px">No response captured for this call.</div>';
    const detail = `<div class="card" style="display:flex;gap:16px;flex-wrap:wrap;align-items:baseline">
        <span style="font-weight:700">Call ${st.llmSel + 1} <span style="color:var(--sub);font-weight:500">of ${D.llm.length}</span></span>
        <span style="color:var(--sub);font-size:11.5px">${esc(r.model)}</span>
        <span style="color:var(--sub);font-size:11.5px">in ${fmtN(r.inputTokens)}${r.cacheReadTokens ? ' (' + fmtN(r.cacheReadTokens) + ' cached)' : ''} · out ${fmtN(r.outputTokens)}</span>
        ${r.totalCost != null ? `<span style="color:var(--sub);font-size:11.5px">${fmtCost(r.totalCost)}</span>` : ''}
      </div>
      ${r.instructions ? `<div style="margin:10px 2px 0;font-size:13px;font-weight:600">${esc(r.instructions)}</div>` : ''}
      <div class="resp"><div class="h">Assistant response</div>${respParts}</div>`;
    return viewPage('LLM', `${D.llm.length} call${D.llm.length === 1 ? '' : 's'}`, `<div class="llm">
      <div><div class="card"><div style="font-size:12px;font-weight:600;color:var(--sub)">Session totals · ${D.llm.length} calls</div>
        <div class="totals"><div><div class="n">${fmtN(totals.i)}</div><div class="t">input tokens</div></div>
        <div><div class="n">${fmtN(totals.o)}</div><div class="t">output tokens</div></div>
        <div><div class="n">${fmtCost(totals.c)}</div><div class="t">total cost</div></div>
        ${totals.k ? `<div><div class="n">${fmtN(totals.k)} <span style="font-weight:500;color:var(--sub)">(${Math.round((totals.k / (totals.i || 1)) * 100)}%)</span></div><div class="t">cached input</div></div>` : ''}
        ${totals.d ? `<div><div class="n">${(totals.d / D.llm.length / 1000).toFixed(1)}s</div><div class="t">avg response</div></div>` : ''}</div></div>
        <div style="margin-top:12px">${list}</div></div>
      <div>${detail}</div>
    </div>`);
  };

  // Screenshot lightbox: default to the final captured frame for each authored step so the view is
  // a concise visual summary. The optional expanded mode preserves access to every tool-level frame.
  const renderLightbox = () => {
    const entries = groupTrace().flatMap((group) => {
      const shots = [group.header, ...group.items].filter((t) => t && t.screenshotFile && D.shots[t.screenshotFile]);
      const selected = st.lightboxAll ? shots : shots.slice(-1);
      return selected.map((trace) => ({ trace, group }));
    });
    const cells = entries.map(({ trace, group }) => {
      const trailhead = Boolean(group.header && group.header.trailhead);
      const token = trailhead ? 'TRAILHEAD' : (group.num ? `STEP ${group.num}` : 'RUN');
      const label = (group.header && group.header.label) || trace.label;
      const tool = trace !== group.header ? trace.label : '';
      return `<button type="button" class="galcell" data-lightbox-step="${trace.i}">
        <div class="galshot" data-shot="${esc(trace.screenshotFile)}" role="button" tabindex="0"><img src="${D.shots[trace.screenshotFile]}" alt="${esc(label)}" /></div>
        <div class="cap"><span class="galchip${trailhead ? ' trailhead' : ''}">${token}</span><span class="gallabel">${esc(label)}</span>${tool ? `<span class="galtool">${esc(tool)}</span>` : ''}</div>
      </button>`;
    }).join('');
    const toggle = `<div class="lightboxtoolbar"><button class="lightboxtoggle" type="button" role="switch" id="lightboxmode" aria-checked="${st.lightboxAll}"><span class="lightboxtoggletrack" aria-hidden="true"><span class="lightboxtogglethumb"></span></span><span>Show all</span></button></div>`;
    const meta = entries.length ? `${entries.length} ${st.lightboxAll ? 'screenshots' : `step frame${entries.length === 1 ? '' : 's'}`}` : '';
    return viewPage('Lightbox', meta, `${toggle}${cells ? `<div class="gal">${cells}</div>` : `<div class="empty">No screenshots captured for this run.</div>`}`, 'lightboxpage');
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
    if (!D.deviceLog) return viewPage('Device log', '', `<div class="empty">No device log captured.</div>`);
    const lines = D.deviceLog.split('\n');
    const html = lines.map((l) => `<div class="ln ${logLevelClass(l)}">${esc(l)}</div>`).join('');
    return viewPage('Device log', `${lines.length} lines`, `
      <div class="lfilter" id="dlbar"><input id="dlq" type="search" placeholder="Filter log lines…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="w">Warn+</button>
        <button class="evchip" data-lvl="e">Errors</button>
        <span class="count" id="dlcount"></span></div>
      <div class="logpane" id="dlpane">${html}</div>`, 'logview');
  };

  const renderNetwork = () => {
    if (!D.network || !D.network.length) return viewPage('Network', '', `<div class="empty">No network activity captured.</div>`);
    const rows = D.network.map((e) => {
      const fail = e.phase === 'FAILED' || (e.statusCode != null && e.statusCode >= 400);
      const status = e.phase === 'FAILED' ? 'FAILED' : (e.statusCode != null ? String(e.statusCode) : (e.phase === 'REQUEST_START' ? '→' : ''));
      const dur = e.durationMs != null ? ` ${e.durationMs}ms` : '';
      return `<div class="ln ${fail ? 'e' : ''}"><span>${esc(e.method)}</span><span class="m">${esc(status)}${esc(dur)}</span><span>${esc(e.urlPath)}</span></div>`;
    }).join('');
    return viewPage('Network', `${D.network.length} events`, `
      <div class="lfilter" id="nlbar"><input id="nlq" type="search" placeholder="Filter by method, path, status…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="e">Failed</button>
        <span class="count" id="nlcount"></span></div>
      <div class="logpane net" id="nlpane">${rows}</div>`, 'logview');
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
    if (!streams || !streams.length) return viewPage('Events', '', `<div class="empty">No events captured.</div>`);
    const sel = Math.min(Math.max(st.evStream || 0, 0), streams.length - 1);
    const cur = streams[sel];
    const options = streams.map((s, i) =>
      `<button class="evstreamoption" type="button" role="option" aria-selected="${i === sel}" data-evstream="${i}"><span>${esc(s.name)}</span><span class="evstreamoptioncount">${s.total}</span></button>`).join('');
    const t0 = cur.events.length && cur.events[0].t != null ? cur.events[0].t : null;
    const rows = cur.events.map((e) => {
      const rel = (e.t != null && t0 != null) ? `+${((e.t - t0) / 1000).toFixed(2)}s` : '';
      return `<div class="streamitem"><span class="streamtime">${esc(rel)}</span>${renderEventPayload(e, cur.name)}</div>`;
    }).join('');
    const totalEvents = streams.reduce((a, s) => a + (s.total || 0), 0);
    const trunc = cur.truncated ? ` · showing last ${cur.events.length} of ${cur.total}` : '';
    return viewPage('Events', `${streams.length} stream${streams.length === 1 ? '' : 's'} · ${totalEvents} total${trunc}`, `
      <details class="streamselect evstreamselect" data-evstreamselect><summary><span class="evstreamlabel">${esc(cur.name)}</span><span class="evstreamcount">${cur.total}</span><span class="chevron" aria-hidden="true"></span></summary><div class="streammenu" role="listbox" aria-label="Event stream">${options}</div></details>
      <div class="eventlist">${rows}</div>`, 'eventview');
  };

  // Video playback over the embedded sprite sheet — pure CSS background-position scrubbing, no decode
  // step. Frame layout + range are precomputed (D.video); wireVideo() drives play/seek.
  const renderVideo = () => {
    const v = D.video;
    if (!v) return viewPage('Video', '', `<div class="empty">No video frames captured for this run.</div>`);
    const total = v.endFrame - v.startFrame + 1;
    // Controls ABOVE the frame (the frame is device-tall; controls below it would sit under the
    // fold), frame height-capped to the viewport — matching the legacy player's always-visible
    // transport with elapsed/total time and a playback-speed toggle.
    return viewPage('Video', `${total} frame${total === 1 ? '' : 's'} · ${v.fps}fps`, `<div class="video">
      <div class="vctl">
        <button class="btn play" id="vplay">▶ Play</button>
        <input type="range" id="vseek" min="0" max="${total - 1}" value="0" />
        <span class="count" id="vpos">0.0s / ${(total / v.fps).toFixed(1)}s</span>
        <button class="btn" id="vspeed" title="Playback speed">${st.vSpeed}×</button>
      </div>
      <div class="vframe" id="vframe" style="background-image:url('${v.sprite}')"></div>
    </div>`);
  };

  const renderInfo = () => {
    const m = D.meta;
    const rows = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package ID', m.appId], ['Trail', m.trailId], ['Total duration', m.duration], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt], ['Build', m.buildNumber], ['Commit', m.commitSha], ['Branch', m.branch]]
      .filter(([, v]) => v).map(([k, v]) => `<div class="r"><span class="k">${k}</span><span class="v">${esc(v)}</span></div>`).join('');
    return viewPage('Run details', '', `<div>
      ${m.cmd ? `<section class="infosection"><div class="eyebrow">Rerun this in the CLI</div><div class="cmd"><pre class="mono" id="cmd">${esc(m.cmd)}</pre><button class="btn" id="copycmd">Copy</button></div></section>` : ''}
      <section class="infosection"><div class="rows">${rows}</div></section>
    </div>`);
  };

  const yamlHighlightTarget = () => {
    const selected = D.trace.find((t) => t.i === st.step);
    if (!selected) return null;
    const groups = groupTrace();
    const group = groups.find((g) => g.header === selected || g.items.indexOf(selected) >= 0);
    const header = group && group.header;
    const tone = selected.selfHealSource || (header && header.selfHeal) ? 'selfheal' : (!selected.ok || (header && !header.ok)) ? 'failed' : '';
    if (!tone || !header) return null;
    const toolTerms = [selected.label, selected.tool || '', header.selfHealTool || '']
      .flatMap((term) => String(term).split(/\s{2,}|:\s*/))
      .map((term) => term.trim()).filter((term) => term.length >= 3);
    return { tone, stepLabel: header.label, toolTerms };
  };

  const highlightedYaml = (text) => {
    const target = yamlHighlightTarget();
    if (!text || !target) return esc(text || '');
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const lowerStep = target.stepLabel.toLowerCase();
    let start = lines.findIndex((line) => line.toLowerCase().indexOf(lowerStep) >= 0 && /(?:^|\s)step\s*:/.test(line));
    if (start < 0) start = lines.findIndex((line) => line.toLowerCase().indexOf(lowerStep) >= 0);
    if (start < 0) return esc(text);
    const startIndent = (lines[start].match(/^\s*/) || [''])[0].length;
    let end = lines.length;
    for (let i = start + 1; i < lines.length; i++) {
      const indent = (lines[i].match(/^\s*/) || [''])[0].length;
      if (indent <= startIndent && (/^\s*(?:-\s*)?step\s*:/.test(lines[i]) || /^[a-zA-Z0-9_-]+\s*:/.test(lines[i]))) { end = i; break; }
      if (/^\s*-\s*step\s*:/.test(lines[i]) && indent <= startIndent) { end = i; break; }
    }
    const lowerTerms = target.toolTerms.map((term) => term.toLowerCase());
    const toolLine = lines.findIndex((line, i) => i >= start && i < end && lowerTerms.some((term) => line.toLowerCase().indexOf(term) >= 0));
    let toolEnd = toolLine >= 0 ? toolLine + 1 : -1;
    if (toolLine >= 0) {
      const toolIndent = (lines[toolLine].match(/^\s*/) || [''])[0].length;
      for (let i = toolLine + 1; i < end; i++) {
        const indent = (lines[i].match(/^\s*/) || [''])[0].length;
        if (lines[i].trim() && indent <= toolIndent && /^\s*-?\s*[A-Za-z0-9_]+\s*:/.test(lines[i])) { toolEnd = i; break; }
        toolEnd = i + 1;
      }
    }
    return lines.map((line, i) => {
      const inStep = i >= start && i < end;
      const inTool = toolLine >= 0 && i >= toolLine && i < toolEnd;
      const cls = inTool ? `yamlmark tool ${target.tone}` : inStep ? `yamlmark ${target.tone}` : '';
      return `<span class="${cls || 'yamlline'}">${esc(line) || ' '}</span>`;
    }).join('');
  };

  const yamlColumn = (title, sub, text, id) => `<div class="yamlcol"><div class="yamlcolhead"><div class="eyebrow">${title} · ${sub}</div>${text ? `<button class="btn yamlcopy" id="copy-${id}">Copy</button>` : ''}</div>${text
      ? `<div class="cmd"><pre class="mono yaml" id="${id}">${highlightedYaml(text)}</pre></div>`
      : `<div class="empty">Not available for this run.</div>`}</div>`;

  const renderConfig = () => {
    const original = yamlRootSection(D.originalYaml, 'config');
    const recorded = yamlRootSection(D.recordingYaml, 'config');
    if (!original && !recorded) return viewPage('Config', '', `<div class="empty">No config captured for this run.</div>`);
    return viewPage('Config', 'Authored and recorded', `<div class="yamlcompare">${yamlColumn('Original config', 'authored inputs', original, 'config-original')}${yamlColumn('Recorded config', 'run snapshot', recorded, 'config-recorded')}</div>`);
  };

  const renderRecording = () => {
    if (!D.recordingYaml && !D.originalYaml) return viewPage('Recording', '', `<div class="empty">No trail YAML captured for this run.</div>`);
    return viewPage('Recording', 'Original and recorded YAML', `<div class="yamlcompare">${yamlColumn('Original trail', 'authored intent', D.originalYaml, 'original-yaml')}${yamlColumn('Recorded run', 'what actually ran', D.recordingYaml, 'recorded-yaml')}</div>`);
  };

  const isPass = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'passed' || v === 'success'; };
  const isFail = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'failed' || v === 'error'; };
  const indexOutcome = (s) => isFail(s) ? 'failed' : (s.meta && s.meta.selfHeal) ? 'selfheal' : isPass(s) ? 'passed' : 'other';
  const indexOutcomeLabel = (outcome) => outcome === 'selfheal' ? 'self-healed' : outcome;
  const sharedMeta = (key) => {
    const first = SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta[key];
    if (first == null || first === '') return null;
    return SESSIONS.every((s) => s.meta && String(s.meta[key] || '') === String(first)) ? first : null;
  };
  const dateLabel = (value) => {
    const raw = String(value || '').trim();
    return raw.match(/^\d{4}-\d{2}-\d{2}/)?.[0] || raw.match(/^[A-Za-z]{3,9} \d{1,2}, \d{4}/)?.[0] || null;
  };

  const indexRunDate = () => {
    const runDates = SESSIONS.map((s) => dateLabel(s.meta && s.meta.ranAt)).filter(Boolean);
    return runDates.length === SESSIONS.length && runDates.every((date) => date === runDates[0])
      ? runDates[0]
      : (runDates.length ? null : dateLabel(generatedAt));
  };

  const renderIndexHeader = () => {
    const meta = [['Target', sharedMeta('target')], ['App version', sharedMeta('appVersion')], ['Platform', sharedMeta('platform')], ['Bundle / package ID', sharedMeta('appId')]]
      .filter(([, value]) => value).map(([label, value]) => `<div><div class="k">${label}</div><div class="v">${esc(value)}</div></div>`).join('');
    const buildUrl = safeHref(sharedMeta('buildUrl'));
    const commitUrl = safeHref(sharedMeta('commitUrl'));
    const buildNumber = sharedMeta('buildNumber');
    const commitSha = sharedMeta('commitSha');
    const links = `${buildUrl ? `<a class="quietlink" href="${esc(buildUrl)}" target="_blank" rel="noopener">${esc(buildNumber ? `Build ${buildNumber}` : 'Build')} ↗</a>` : ''}${commitUrl ? `<a class="quietlink" href="${esc(commitUrl)}" target="_blank" rel="noopener">${esc(commitSha ? String(commitSha).slice(0, 8) : 'Commit')} ↗</a>` : ''}`;
    return `<header class="indexheader"><div class="indexshell">
      <div class="title-row indexheadrow"><h1>Trailblaze Report</h1><div class="indexheadactions">${renderThemeToggle()}<button class="btn headeraction" type="button" id="exportall">Share</button></div></div>
      ${(meta || links) ? `<div class="indexcontext"><div class="meta indexmeta">${meta}</div>${links ? `<div class="indexlinks">${links}</div>` : ''}</div>` : ''}
      </div>
    </header>`;
  };

  const renderIndexSummary = () => {
    const groups = indexRunGroups();
    const outcomes = groups.map((group) => group.outcome);
    const pass = outcomes.filter((outcome) => outcome === 'passed').length;
    const selfHeal = outcomes.filter((outcome) => outcome === 'selfheal').length;
    const fail = outcomes.filter((outcome) => outcome === 'failed').length;
    const other = outcomes.filter((outcome) => outcome === 'other').length;
    return `<div class="idxsummary"><span class="stat fail"><strong>${fail}</strong> failed</span><span class="stat selfheal"><strong>${selfHeal}</strong> self-healed</span><span class="stat pass"><strong>${pass}</strong> passed</span>${other ? `<span class="stat"><strong>${other}</strong> other</span>` : ''}</div>`;
  };

  const durationMs = (value) => {
    const raw = String(value || '').trim().toLowerCase();
    if (!raw) return null;
    const clock = raw.match(/^(?:(\d+):)?(\d+):(\d+(?:\.\d+)?)$/);
    if (clock) return Math.round(((Number(clock[1] || 0) * 3600) + (Number(clock[2]) * 60) + Number(clock[3])) * 1000);
    let total = 0;
    let matched = false;
    const token = /(\d+(?:\.\d+)?)\s*(ms|h|m|s)\b/g;
    let part;
    while ((part = token.exec(raw)) != null) {
      matched = true;
      const amount = Number(part[1]);
      total += part[2] === 'h' ? amount * 3_600_000 : part[2] === 'm' ? amount * 60_000 : part[2] === 's' ? amount * 1000 : amount;
    }
    return matched ? Math.round(total) : null;
  };

  const aggregateDurationLabel = () => {
    const durations = SESSIONS.map((s) => durationMs(s.meta && s.meta.duration)).filter((value) => value != null);
    if (!durations.length || durations.length !== SESSIONS.length) return '—';
    const totalSeconds = Math.round(durations.reduce((sum, value) => sum + value, 0) / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    return hours ? `${hours}h ${minutes}m ${seconds}s` : minutes ? `${minutes}m ${seconds}s` : `${seconds}s`;
  };

  const llmTokensLabel = (calls) => {
    if (!calls.length) return fmtN(0);
    if (calls.some((call) => call.inputTokens == null || call.outputTokens == null || String(call.inputTokens).trim() === '' || String(call.outputTokens).trim() === '' || !Number.isFinite(Number(call.inputTokens)) || !Number.isFinite(Number(call.outputTokens)))) return '—';
    return fmtN(calls.reduce((sum, call) => sum + Number(call.inputTokens) + Number(call.outputTokens), 0));
  };

  const llmCostLabel = (calls) => {
    if (!calls.length) return fmtCost(0);
    if (calls.some((call) => call.totalCost == null || String(call.totalCost).trim() === '' || !Number.isFinite(Number(call.totalCost)))) return '—';
    return fmtCost(calls.reduce((sum, call) => sum + Number(call.totalCost), 0));
  };

  const aggregateLlmCostLabel = () => llmCostLabel(SESSIONS.flatMap((s) => s.llm || []));

  const renderIndexMetrics = () => {
    const calls = SESSIONS.flatMap((s) => s.llm || []);
    return `<div class="indexmetrics"><span class="detailfooteritem"><span class="k">Total duration</span><span class="v">${esc(aggregateDurationLabel())}</span></span><span class="detailfooteritem"><span class="k">Total tokens</span><span class="v">${esc(llmTokensLabel(calls))}</span></span><span class="detailfooteritem"><span class="k">Total LLM cost</span><span class="v">${esc(aggregateLlmCostLabel())}</span></span></div>`;
  };

  const indexGroupKey = (s, index) => {
    const m = (s && s.meta) || {};
    // Only an explicit trail identity is safe to coalesce. Older exports without trailId can carry
    // independent same-title runs; keeping them separate avoids hiding a failure as retry history.
    if (!m.trailId) return `session:${index}`;
    return [m.trailId, m.target || '', m.platform || '', m.device || m.deviceType || ''].join('\u0001');
  };

  const attemptTime = (attempt) => {
    const parsed = Date.parse(String(attempt.s.meta && attempt.s.meta.ranAt || ''));
    return Number.isFinite(parsed) ? parsed : null;
  };

  const indexRunGroups = () => {
    const byTest = new Map();
    SESSIONS.forEach((s, i) => {
      const key = indexGroupKey(s, i);
      if (!byTest.has(key)) byTest.set(key, { key, first: i, attempts: [] });
      byTest.get(key).attempts.push({ s, i, outcome: indexOutcome(s) });
    });
    return Array.from(byTest.values()).map((group) => {
      const allDated = group.attempts.every((attempt) => attemptTime(attempt) != null);
      const attempts = group.attempts.sort((a, b) => allDated ? attemptTime(a) - attemptTime(b) || a.i - b.i : a.i - b.i);
      const latest = attempts[attempts.length - 1];
      return { ...group, attempts, latest, outcome: latest.outcome };
    });
  };

  // The landing page is grouped by unique trail, not raw session. A retry is attempt history, so
  // the final attempt determines the section while the earlier attempts remain nested beneath it.
  const renderIndex = () => {
    const allRuns = indexRunGroups();
    const filtered = st.runFilter === 'self-healed' ? allRuns.filter(({ outcome }) => outcome === 'selfheal') : allRuns;
    const outcomeRank = { failed: 0, selfheal: 1, passed: 2, other: 3 };
    const ordered = filtered.sort((a, b) => {
      if (st.runSort === 'grouped') return outcomeRank[a.outcome] - outcomeRank[b.outcome] || Number(b.attempts.length > 1) - Number(a.attempts.length > 1) || a.first - b.first;
      if (st.runSort === 'name') return String(a.latest.s.meta.title || '').localeCompare(String(b.latest.s.meta.title || '')) || a.first - b.first;
      return a.first - b.first;
    });
    const searchText = (s, outcome) => {
      const status = String((s.meta && s.meta.status) || 'unknown').toLowerCase();
      const outcomeLabel = indexOutcomeLabel(outcome);
      return [s.meta.title, status, outcomeLabel !== status ? outcomeLabel : null, s.meta.platform, s.meta.deviceType, s.meta.device, s.meta.target, s.meta.appId, s.meta.appVersion, s.meta.steps, s.meta.duration, s.meta.ranAt, s.meta.buildNumber, s.meta.commitSha, s.meta.branch]
        .filter((v) => v != null && v !== '').join(' ').toLowerCase();
    };
    const facts = (duration, steps, durationLabel = 'Duration', stepsLabel = 'Steps') => `<div class="idxfacts"><div class="idxfact"><div class="k">${durationLabel}</div><div class="v">${esc(duration || '—')}</div></div><div class="idxfact"><div class="k">${stepsLabel}</div><div class="v">${esc(steps != null ? steps : '—')}</div></div></div>`;
    const renderRow = ({ attempts, latest, outcome }) => {
      const { s, i } = latest;
      const outcomeLabel = indexOutcomeLabel(outcome);
      const search = attempts.map((attempt) => searchText(attempt.s, attempt.outcome)).join(' ');
      if (attempts.length > 1) {
        const attemptLabels = attempts.map((attempt) => indexOutcomeLabel(attempt.outcome));
        const attemptDots = attempts.map((attempt, attemptIndex) => `<span class="idxstatusdot ${esc(attempt.outcome)}" aria-hidden="true" title="Attempt ${attemptIndex + 1}: ${esc(attemptLabels[attemptIndex])}"></span>`).join('');
        const attemptRows = attempts.map((attempt, attemptIndex) => {
          const label = indexOutcomeLabel(attempt.outcome);
          return `<div class="idxattemptrow" data-session="${attempt.i}" data-outcome="${esc(attempt.outcome)}" role="button" tabindex="0" aria-label="Open attempt ${attemptIndex + 1}, ${esc(label)}">
            <span class="idxstatus" aria-label="${esc(label)}" title="${esc(label)}"><span class="idxstatusdot ${esc(attempt.outcome)}" aria-hidden="true"></span></span>
            <div class="idxattemptmain"><span class="idxattemptlabel">Attempt ${attemptIndex + 1}</span><span class="idxattemptstatus ${esc(attempt.outcome)}">${esc(label)}</span>${attempt.s.meta.ranAt ? `<span class="idxattempttime">${esc(attempt.s.meta.ranAt)}</span>` : ''}</div>
            ${facts(attempt.s.meta.duration, attempt.s.meta.steps)}
            <span class="arr" aria-hidden="true">→</span>
          </div>`;
        }).join('');
        return `<details class="idxretry" data-run-entry data-search="${esc(search)}"><summary class="idxrow idxretryrow" aria-label="${attempts.length} attempts for ${esc(s.meta.title || ('Run ' + (i + 1)))}">
          <span class="idxretrydots" role="img" aria-label="Attempt history: ${esc(attemptLabels.join(', '))}">${attemptDots}</span>
          <div class="idxmain"><div class="nm">${esc(s.meta.title || ('Run ' + (i + 1)))}</div></div>
          ${facts(s.meta.duration, attempts.length, 'Latest', 'Attempts')}
          <span class="idxretrychev" aria-hidden="true"></span>
        </summary><div class="idxattempts">${attemptRows}</div></details>`;
      }
      return `<div class="idxrow" data-run-entry data-session="${i}" data-search="${esc(search)}" role="button" tabindex="0">
          <span class="idxstatus" aria-label="${esc(outcomeLabel)}" title="${esc(outcomeLabel)}"><span class="idxstatusdot ${esc(outcome)}" aria-hidden="true"></span></span>
          <div class="idxmain"><div class="nm">${esc(s.meta.title || ('Run ' + (i + 1)))}</div></div>
          ${facts(s.meta.duration, s.meta.steps)}
          <span class="arr">→</span>
        </div>`;
    };
    const sectionLabel = { failed: 'Failed', selfheal: 'Self-healed', passed: 'Passed', other: 'Other' };
    const rows = st.runSort === 'grouped'
      ? ['failed', 'selfheal', 'passed', 'other'].map((outcome) => {
          const runs = ordered.filter((run) => run.outcome === outcome);
          if (!runs.length) return '';
          return `<section class="idxsection" data-index-section="${outcome}"><div class="idxsectionhead ${outcome}">${sectionLabel[outcome]} <span class="idxsectioncount">${runs.length}</span></div><div class="idx">${runs.map(renderRow).join('')}</div></section>`;
        }).join('')
      : `<div class="idx">${ordered.map(renderRow).join('')}</div>`;
    return `<div class="idxfilter">
        <input id="runsearch" type="search" aria-label="Search runs" placeholder="Search runs…" autocomplete="off" />
        <button class="idxhealedfilter" type="button" aria-pressed="${st.runFilter === 'self-healed'}" data-run-filter="self-healed">Self-healed</button>
        <details class="idxsort" id="runsort" data-runsort><summary aria-label="Sort runs" aria-haspopup="listbox"><span>${st.runSort === 'original' ? 'Run order' : st.runSort === 'name' ? 'Name A–Z' : 'Status groups'}</span><span class="idxsortchev" aria-hidden="true"></span></summary><div class="idxsortmenu" role="listbox" aria-label="Sort runs"><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'grouped'}" data-run-sort="grouped">Status groups</button><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'original'}" data-run-sort="original">Run order</button><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'name'}" data-run-sort="name">Name A–Z</button></div></details>
      </div>
      <div class="idxsections">${rows}<div class="empty" id="runempty" ${ordered.length ? 'hidden' : ''}>No runs match these filters.</div></div>`;
  };

  const render = (preserveTimelineScroll = false) => {
    const previousTimelineScroll = preserveTimelineScroll ? root.querySelector<HTMLElement>('.timeline-list')?.scrollTop : null;
    const active = preserveTimelineScroll ? document.activeElement as HTMLElement | null : null;
    const focusSelector = active && active.matches('[data-scrub]') ? '[data-scrub]'
      : active && active.matches('[data-step]') ? `[data-step="${active.dataset.step}"]`
      : active && active.matches('[data-tlstream]') ? `[data-tlstream="${active.dataset.tlstream}"]`
      : active && active.matches('[data-tlstreams]') ? `[data-tlstreams="${active.dataset.tlstreams}"]`
      : active && ['prev', 'next', 'tlplay'].indexOf(active.id) >= 0 ? `#${active.id}`
      : null;
    const pageTransition = st.pageTransition;
    st.pageTransition = '';
    root.className = pageTransition ? `page-enter-${pageTransition}` : '';
    if (st.view === 'index') {
      const runDate = indexRunDate();
      root.innerHTML = `
        ${renderIndexHeader()}
        <main><div class="indexshell">${renderIndex()}</div></main>
        <footer class="indexfooter"><div class="indexshell indexfootercontent">${renderIndexSummary()}${renderIndexMetrics()}${runDate ? `<span class="detailfooteritem indexrundate"><span class="k">Run on</span><span class="v">${esc(runDate)}</span></span>` : ''}</div></footer>`;
      wire();
      return;
    }
    const m = D.meta;
    const detailOutcome = indexOutcome(D);
    const detailOutcomeLabel = indexOutcomeLabel(detailOutcome);
    const hasShots = D.trace.some((t) => t.screenshotFile && D.shots[t.screenshotFile]);
    const tabs = [
      ['timeline', 'Timeline'],
      ...(hasShots ? [['lightbox', 'Lightbox']] : []),
      ...(D.video ? [['video', 'Video']] : []),
      ...(D.llm.length ? [['llm', `LLM (${D.llm.length})`]] : []),
      ...(yamlRootSection(D.recordingYaml, 'config') || yamlRootSection(D.originalYaml, 'config') ? [['config', 'Config']] : []),
      ...(D.recordingYaml || D.originalYaml ? [['recording', 'YAML']] : []),
      ...(D.deviceLog ? [['device', 'Device logs']] : []),
      ...(D.network && D.network.length ? [['network', 'Network']] : []),
      ...(D.events && D.events.length ? [['events', 'Events']] : []),
      ['info', 'Info'],
    ];
    const body = st.tab === 'timeline' ? renderTimeline()
      : st.tab === 'lightbox' ? renderLightbox()
      : st.tab === 'video' ? renderVideo()
      : st.tab === 'llm' ? renderLlm()
      : st.tab === 'config' ? renderConfig()
      : st.tab === 'recording' ? renderRecording()
      : st.tab === 'device' ? renderDevice()
      : st.tab === 'network' ? renderNetwork()
      : st.tab === 'events' ? renderEvents()
      : renderInfo();
    const shotCount = screenshotEntries(D).length;
    const logsAvailable = hasLogs(D);
    const localPrompt = localRunAgentPrompt(m);
    const exportMenu = `<details class="exportmenu" data-export-menu><summary aria-label="Run and export options" title="Run and export options"><span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span></summary><div class="exportmenuitems"><button class="exportmenuitem" type="button" id="copylocalprompt"${localPrompt ? '' : ' disabled'}>Copy local run prompt</button><button class="exportmenuitem" type="button" id="exportrun">Export report</button><button class="exportmenuitem" type="button" id="exportscreenshots"${shotCount ? '' : ' disabled'}><span>Export screenshots</span><span class="count">${shotCount}</span></button><button class="exportmenuitem" type="button" id="exportlogs"${logsAvailable ? '' : ' disabled'}>Export logs</button></div></details>`;
    const footerItems = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package', m.appId], ['Total duration', m.duration], ['Tokens used', llmTokensLabel(D.llm || [])], ['LLM cost', llmCostLabel(D.llm || [])]]
      .filter(([, v]) => v != null && v !== '').map(([k, v]) => `<span class="detailfooteritem"><span class="k">${k}</span><span class="v">${esc(v)}</span></span>`).join('');
    const runOn = m.ranAt ? `<span class="detailfooteritem runon"><span class="k">Run on</span><span class="v">${esc(m.ranAt)}</span></span>` : '';
    root.innerHTML = `
      <header class="detailheader">
        <div class="title-row detailtitle"><div class="detailedge">${MULTI ? `<button class="back" type="button" data-back aria-label="All runs" title="All runs"><span class="backarrow" aria-hidden="true">←</span></button>` : ''}</div><div class="runidentity"><span class="badge ${esc(detailOutcome)}">${esc(detailOutcomeLabel)}</span><h1>${esc(m.title)}</h1></div><div class="detailactions">${renderThemeToggle()}${exportMenu}</div></div>
        <nav aria-label="Report views">${tabs.map(([id, l]) => `<button class="${st.tab === id ? 'active' : ''}" data-tab="${id}">${l}</button>`).join('')}</nav>
      </header>
      <main class="${st.tab === 'timeline' ? 'timelinemain' : ''}">${body}</main>
      <footer class="detailfooter"><div class="detailfootermeta">${footerItems}${runOn}</div></footer>`;
    wire();
    if (previousTimelineScroll != null) {
      const timelineList = root.querySelector<HTMLElement>('.timeline-list');
      if (timelineList) timelineList.scrollTop = previousTimelineScroll;
    }
    if (focusSelector) root.querySelector<HTMLElement>(focusSelector)?.focus({ preventScroll: true });
  };

  let zoomEl = null;
  let zoomReturnFocus = null;
  let zoomMove = null;
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
  const closeZoom = () => {
    if (!zoomEl) return;
    zoomEl.remove(); zoomEl = null; zoomMove = null;
    if (zoomReturnFocus && zoomReturnFocus.focus) zoomReturnFocus.focus();
  };
  const openZoom = (src: string, markup?: string, gallery: string[] = [src], startIndex = 0) => {
    zoomReturnFocus = document.activeElement;
    zoomEl = document.createElement('div'); zoomEl.className = 'zoom';
    zoomEl.setAttribute('role', 'dialog'); zoomEl.setAttribute('aria-modal', 'true'); zoomEl.setAttribute('aria-label', 'Expanded screenshot'); zoomEl.tabIndex = -1;
    const wrap = document.createElement('div'); wrap.className = 'zoomwrap';
    const big = document.createElement('img'); big.src = src; big.alt = 'screenshot';
    wrap.appendChild(big);
    if (markup) wrap.insertAdjacentHTML('beforeend', markup);
    zoomEl.appendChild(wrap);
    let galleryIndex = Math.max(0, Math.min(gallery.length - 1, startIndex));
    const previous = document.createElement('button'); previous.type = 'button'; previous.className = 'zoomnav prev'; previous.setAttribute('aria-label', 'Previous screenshot'); previous.textContent = '‹';
    const next = document.createElement('button'); next.type = 'button'; next.className = 'zoomnav next'; next.setAttribute('aria-label', 'Next screenshot'); next.textContent = '›';
    const count = document.createElement('div'); count.className = 'zoomcount'; count.setAttribute('aria-live', 'polite');
    const show = () => {
      big.src = gallery[galleryIndex];
      previous.disabled = galleryIndex === 0; next.disabled = galleryIndex === gallery.length - 1;
      count.textContent = `${galleryIndex + 1} of ${gallery.length} · ← →`;
    };
    zoomMove = (delta) => { const target = galleryIndex + delta; if (target < 0 || target >= gallery.length) return; galleryIndex = target; show(); };
    previous.onclick = (e) => { e.stopPropagation(); zoomMove(-1); };
    next.onclick = (e) => { e.stopPropagation(); zoomMove(1); };
    zoomEl.appendChild(previous); zoomEl.appendChild(next); zoomEl.appendChild(count); show();
    zoomEl.onclick = closeZoom;
    document.body.appendChild(zoomEl);
    zoomEl.focus();
  };
  const centerTimelineSelection = () => {
    const center = () => {
      const list = root.querySelector<HTMLElement>('.timeline-list');
      const selected = root.querySelector<HTMLElement>(`[data-step="${st.step}"]`) || root.querySelector<HTMLElement>(`[data-group="${st.step}"]`);
      if (!list || !selected || !list.scrollTo || !list.getBoundingClientRect || !selected.getBoundingClientRect) return;
      const scrolls = (el: HTMLElement) => el.scrollHeight > el.clientHeight + 1
        && (typeof getComputedStyle === 'undefined' || /(auto|scroll)/.test(getComputedStyle(el).overflowY));
      let scroller = list;
      if (!scrolls(scroller)) {
        for (let parent = list.parentElement; parent; parent = parent.parentElement) {
          if (scrolls(parent)) { scroller = parent; break; }
        }
      }
      const listRect = scroller.getBoundingClientRect();
      const selectedRect = selected.getBoundingClientRect();
      const top = Math.max(0, scroller.scrollTop + selectedRect.top - listRect.top - (scroller.clientHeight - selectedRect.height) / 2);
      const reducedMotion = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
      scroller.scrollTo({ top, behavior: reducedMotion ? 'auto' : 'smooth' });
    };
    if (typeof requestAnimationFrame === 'undefined') center();
    else requestAnimationFrame(() => requestAnimationFrame(center));
  };
  const wire = () => {
    stopVideo(); // a re-render replaces the video element; drop any running playback timer.
    if (st.tab !== 'timeline') stopTimeline(); // playback only lives on the timeline tab
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => button.onclick = () => setTheme(currentTheme() === 'dark' ? 'light' : 'dark'));
    root.querySelectorAll<HTMLElement>('[data-session]').forEach((el) => {
      const open = () => { openSession(+el.dataset.session); st.pageTransition = 'forward'; writeRoute(false); render(); };
      el.onclick = open;
      el.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
    });
    const exportRun = document.getElementById('exportrun');
    const exportMenu = root.querySelector<HTMLDetailsElement>('[data-export-menu]');
    const closeExportMenu = () => { if (exportMenu) exportMenu.open = false; };
    const copyLocalPrompt = document.getElementById('copylocalprompt');
    if (copyLocalPrompt) copyLocalPrompt.onclick = () => {
      const prompt = localRunAgentPrompt(D.meta);
      if (!prompt) return;
      try {
        navigator.clipboard.writeText(prompt);
        copyLocalPrompt.textContent = 'Copied local run prompt';
        setTimeout(() => { copyLocalPrompt.textContent = 'Copy local run prompt'; }, 1500);
        closeExportMenu();
      } catch (e) {}
    };
    if (exportRun) exportRun.onclick = () => {
      const name = fileSlug(D.meta && D.meta.title);
      exportReport([D], `trailblaze_run_${name}.html`, `${D.meta.title || 'Trailblaze run'} · Trailblaze run`);
      closeExportMenu();
    };
    const exportScreenshotsButton = document.getElementById('exportscreenshots');
    if (exportScreenshotsButton) exportScreenshotsButton.onclick = () => { exportScreenshots(D); closeExportMenu(); };
    const exportLogsButton = document.getElementById('exportlogs');
    if (exportLogsButton) exportLogsButton.onclick = () => { exportLogs(D); closeExportMenu(); };
    if (exportMenu) {
      exportMenu.addEventListener('focusout', (e) => { if (!exportMenu.contains(e.relatedTarget as Node | null)) exportMenu.open = false; });
      exportMenu.onkeydown = (e) => { if (e.key === 'Escape') { exportMenu.open = false; exportMenu.querySelector<HTMLElement>('summary')?.focus(); } };
    }
    const exportAll = document.getElementById('exportall');
    if (exportAll) exportAll.onclick = () => exportReport(SESSIONS, 'trailblaze_runs.html', 'Trailblaze Report');
    const runSort = root.querySelector<HTMLDetailsElement>('[data-runsort]');
    if (runSort) {
      runSort.addEventListener('focusout', (e) => { if (!runSort.contains(e.relatedTarget as Node | null)) runSort.open = false; });
      runSort.onkeydown = (e) => { if (e.key === 'Escape') { runSort.open = false; runSort.querySelector<HTMLElement>('summary')?.focus(); } };
      runSort.querySelectorAll<HTMLElement>('[data-run-sort]').forEach((option) => option.onclick = () => {
        st.runSort = option.dataset.runSort || 'grouped'; runSort.open = false; writeRoute(false); render();
      });
    }
    const runFilter = root.querySelector<HTMLElement>('[data-run-filter]');
    if (runFilter) runFilter.onclick = () => {
      st.runFilter = st.runFilter === 'self-healed' ? '' : 'self-healed'; writeRoute(false); render();
    };
    const runSearch = document.getElementById('runsearch') as HTMLInputElement | null;
    if (runSearch) runSearch.oninput = () => {
      const terms = runSearch.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
      let shown = 0;
      root.querySelectorAll<HTMLElement>('[data-run-entry]').forEach((row) => {
        const match = terms.every((term) => String(row.dataset.search || '').indexOf(term) >= 0);
        row.hidden = !match;
        row.classList.toggle('firstmatch', match && shown === 0);
        if (match) shown++;
      });
      root.querySelectorAll<HTMLElement>('[data-index-section]').forEach((section) => {
        section.hidden = !Array.from(section.querySelectorAll<HTMLElement>('[data-run-entry]')).some((row) => !row.hidden);
      });
      const empty = document.getElementById('runempty');
      if (empty) empty.hidden = shown !== 0;
    };
    const backBtn = root.querySelector<HTMLElement>('[data-back]'); if (backBtn) backBtn.onclick = () => { stopTimeline(); st.view = 'index'; st.pageTransition = 'back'; writeRoute(false); render(); window.scrollTo({ top: 0 }); };
    root.querySelectorAll<HTMLElement>('[data-tab]').forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; writeRoute(false); render(); });
    root.querySelectorAll<HTMLElement>('[data-step]').forEach((el) => el.onclick = (e) => { if (e) e.stopPropagation(); stopTimeline(); st.step = +el.dataset.step; revealTimelineStep(st.step); writeRoute(true); render(true); });
    root.querySelectorAll<HTMLElement>('[data-llm]').forEach((el) => el.onclick = () => { st.llmSel = +el.dataset.llm; writeRoute(true); render(); });
    root.querySelectorAll<HTMLElement>('[data-evstream]').forEach((el) => el.onclick = () => { st.evStream = +el.dataset.evstream; writeRoute(true); render(); });
    const lightboxMode = document.getElementById('lightboxmode');
    if (lightboxMode) lightboxMode.onclick = () => { st.lightboxAll = !st.lightboxAll; render(); };
    const eventStreamSelect = root.querySelector<HTMLDetailsElement>('[data-evstreamselect]');
    if (eventStreamSelect) {
      eventStreamSelect.addEventListener('focusout', (e) => { if (!eventStreamSelect.contains(e.relatedTarget as Node | null)) eventStreamSelect.open = false; });
      eventStreamSelect.onkeydown = (e) => { if (e.key === 'Escape') { eventStreamSelect.open = false; eventStreamSelect.querySelector<HTMLElement>('summary')?.focus(); } };
    }
    root.querySelectorAll<HTMLElement>('[data-tlstream]').forEach((el) => el.onclick = () => {
      const i = +el.dataset.tlstream; st.tlStreams = st.tlStreams.indexOf(i) >= 0 ? st.tlStreams.filter((v) => v !== i) : [...st.tlStreams, i].sort((a, b) => a - b);
      st.tlMenuOpen = true; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-tlstreams]').forEach((el) => el.onclick = () => {
      st.tlStreams = el.dataset.tlstreams === 'all' ? (D.events || []).map((_, i) => i) : [];
      st.tlMenuOpen = true; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-phase]').forEach((control) => control.onclick = () => {
      const phase = control.dataset.phase;
      const open = control.getAttribute('aria-expanded') !== 'true';
      if (phase === 'trailhead') st.trailheadOpen = open;
      if (phase === 'trail') st.trailOpen = open;
      control.setAttribute('aria-expanded', String(open));
      const body = control.closest('.tlphase')?.querySelector<HTMLElement>('.tlphasebody');
      if (body) body.hidden = !open;
    });
    root.querySelectorAll<HTMLElement>('[data-group]').forEach((control) => control.onclick = () => {
      const id = +control.dataset.group;
      const open = control.getAttribute('aria-expanded') !== 'true';
      st.collapsedGroups = open ? st.collapsedGroups.filter((v) => v !== id) : [...st.collapsedGroups, id];
      control.setAttribute('aria-expanded', String(open));
      const body = control.closest('.stepgroup')?.querySelector<HTMLElement>('.stepgroupbody');
      if (body) body.hidden = !open;
    });
    const streamSelect = root.querySelector<HTMLDetailsElement>('[data-streamselect]');
    if (streamSelect) streamSelect.ontoggle = () => { st.tlMenuOpen = streamSelect.open; };
    root.querySelectorAll<HTMLElement>('[data-yaml-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.yamlStep;
      st.tab = 'recording';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-lightbox-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.lightboxStep;
      st.tab = 'timeline';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
      centerTimelineSelection();
    });
    const galleryShots = Array.from(root.querySelectorAll<HTMLElement>('[data-shot]'));
    const gallerySources = galleryShots.map((el) => D.shots[el.dataset.shot]).filter(Boolean);
    galleryShots.forEach((el, index) => el.onclick = (e) => { if (e) e.stopPropagation(); const s = D.shots[el.dataset.shot]; if (s) openZoom(s, '', gallerySources, index); });
    root.querySelectorAll<HTMLElement>('[role="button"][tabindex="0"]').forEach((el) => el.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); el.click(); }
    });
    const previewShot = root.querySelector<HTMLImageElement>('.preview .shot');
    if (previewShot && !previewShot.complete) previewShot.addEventListener('load', centerTimelineSelection, { once: true });
    const prev = document.getElementById('prev'); const next = document.getElementById('next');
    if (prev) prev.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p > 0) { st.step = D.trace[p - 1].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); } };
    if (next) next.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) { st.step = D.trace[p + 1].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); } };
    const scrub = root.querySelector<HTMLElement>('[data-scrub]');
    if (scrub) scrub.onclick = (e) => {
      const r = scrub.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (e.clientY - r.top) / r.height));
      const axis = timelineAxis(); let best = 0; let dist = Infinity;
      axis.stepFrac.forEach((sf, i) => { const d = Math.abs(sf - f); if (d < dist) { dist = d; best = i; } });
      if (D.trace[best]) { stopTimeline(); st.step = D.trace[best].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); }
    };
    if (scrub) scrub.onkeydown = (e) => {
      const p = idxOf(st.step);
      const target = e.key === 'Home' ? 0 : e.key === 'End' ? D.trace.length - 1 : (e.key === 'ArrowUp' || e.key === 'ArrowLeft') ? p - 1 : (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? p + 1 : -1;
      if (target >= 0 && target < D.trace.length) { e.preventDefault(); e.stopPropagation(); stopTimeline(); st.step = D.trace[target].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); }
    };
    const tlplay = document.getElementById('tlplay');
    if (tlplay) tlplay.onclick = () => {
      if (timelineTimer) { stopTimeline(); render(true); return; }
      if (idxOf(st.step) >= D.trace.length - 1) st.step = D.trace[0].i; // restart from the top if parked at the end
      st.playing = true;
      timelineTimer = setInterval(() => {
        const p = idxOf(st.step);
        if (p >= D.trace.length - 1) { stopTimeline(); render(true); return; }
        st.step = D.trace[p + 1].i; revealTimelineStep(st.step); writeRoute(true); render(true);
      }, TIMELINE_PLAY_MS);
      render(true);
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
    const wireCopyYaml = (id, text) => {
      const btn = document.getElementById(`copy-${id}`);
      if (btn) btn.onclick = () => { try { navigator.clipboard.writeText(text); btn.textContent = 'Copied'; setTimeout(() => { btn.textContent = 'Copy'; }, 1500); } catch (e) {} };
    };
    wireCopyYaml('original-yaml', D.originalYaml);
    wireCopyYaml('recorded-yaml', D.recordingYaml);
    wireCopyYaml('config-original', yamlRootSection(D.originalYaml, 'config'));
    wireCopyYaml('config-recorded', yamlRootSection(D.recordingYaml, 'config'));
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
    if (zoomEl) {
      if (e.key === 'Escape') { e.preventDefault(); closeZoom(); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); if (zoomMove) zoomMove(-1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); if (zoomMove) zoomMove(1); }
      return;
    }
    if (e.defaultPrevented) return;
    const target = e.target as HTMLElement | null;
    if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A)$/.test(target.tagName))) return;
    // Space toggles playback on the video tab too (parity with the legacy player's spacebar).
    if (st.view === 'detail' && st.tab === 'video' && e.key === ' ') { e.preventDefault(); const b = document.getElementById('vplay'); if (b) b.click(); return; }
    if (st.view !== 'detail' || st.tab !== 'timeline' || !D.trace.length) return;
    if (e.key === 'ArrowLeft') { stopTimeline(); const p = idxOf(st.step); if (p > 0) { e.preventDefault(); st.step = D.trace[p - 1].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); } }
    if (e.key === 'ArrowRight') { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) { e.preventDefault(); st.step = D.trace[p + 1].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); } }
    if (e.key === ' ') { e.preventDefault(); const b = document.getElementById('tlplay'); if (b) b.click(); } // space toggles play/pause
  });

  if (typeof window.addEventListener === 'function') {
    window.addEventListener('popstate', () => {
      const previousView = st.view;
      applyRoute();
      if (st.view !== previousView) st.pageTransition = st.view === 'detail' ? 'forward' : 'back';
      render();
    });
  }

  render();
}

// Export to the browser global scope (classic script) and to bun/node (require).
const RUN_REPORT_EXPORTS = {
  truncate, logClass, originalYamlFromLogs, yamlRootSection, localRunAgentPrompt, extractTrace, toolChildren, describeAction, parseLlmResponse, extractLlmLogs,
  stepText, toolDetail, summarizeToolArgs, describeSelector,
  slimTraceForShare, slimLlmForShare, buildRunReportHtml, buildMultiReportHtml, RUN_REPORT_CSS, RUN_REPORT_VIEWER,
};
if (typeof window !== 'undefined') Object.assign(window, RUN_REPORT_EXPORTS);
if (typeof module !== 'undefined' && module.exports) module.exports = RUN_REPORT_EXPORTS;

// Log → timeline extraction + share-payload slimming for the interactive run report (moved
// verbatim from data-extract.jsx / share-export.jsx; this is the only copy). Pure functions over
// raw Trailblaze log records — no DOM, no fetch — shared by the Trail Runner web app, the
// standalone viewer bundle, and the bun report driver.
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).

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
// Share-payload slimming (the assembly itself lives in run-report-html.ts).
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

export {
  truncate, logClass, originalYamlFromLogs, yamlRootSection, localRunAgentPrompt, extractTrace,
  toolChildren, describeAction, parseLlmResponse, extractLlmLogs, stepText, toolDetail,
  summarizeToolArgs, describeSelector, slimTraceForShare, slimLlmForShare,
};

// Log → timeline extraction + share-payload slimming for the interactive run report (moved
// verbatim from data-extract.jsx / share-export.jsx; this is the only copy). Pure functions over
// raw Trailblaze log records — no DOM, no fetch — shared by the Trail Runner web app, the
// standalone viewer bundle, and the bun report driver.
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).
import { deflateGzText } from './run-report-payload';

// Clamps at the last word boundary inside the budget, so a cut label reads as a phrase instead of
// ending mid-word ("…it may say 'Search al…"). Falls back to a hard cut when the budget holds no
// whitespace at all (a long unbroken token).
function truncate(s: unknown, n = 60): string {
  if (s == null) return '';
  const str = String(s);
  if (str.length <= n) return str;
  const head = str.slice(0, n - 1);
  const lastSpace = head.lastIndexOf(' ');
  return (lastSpace > n * 0.6 ? head.slice(0, lastSpace).replace(/[\s,;:.\-]+$/, '') : head) + '…';
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

// A web capture logs the SAME ARIA snapshot as two parallel trees, whose bounds come from two
// different DOM correlations: `trailblazeNodeTree` (the shape the inspector prefers — ariaRole /
// test id / landmark detail) gets bounds from a fuzzy role+name walk that leaves most nodes with
// no geometry at all, while the legacy `viewHierarchy` sibling gets them from the ref-resolved
// batched pass and covers 3–10x more nodes (a real dashboard-style web form: 60 vs 212 of 272). Hit-testing
// the sparse tree resolved most of the screenshot to whatever giant landmark container still had
// bounds (`<main>`, a dialog) because the element actually under the cursor had none. Graft the
// dense bounds onto the ARIA tree so the inspector gets the semantics AND the geometry.
//
// The two trees are parsed from one snapshot, so they are structurally parallel; this walks them
// in lockstep and copies each legacy node's rect onto its ARIA twin. Where both carry bounds the
// legacy rect wins — its fast pass is cardinality-gated and the remainder is resolved through
// Playwright's own aria refs, whereas the ARIA tree's unguarded matcher is the one that assigns
// an occluded background element's rect to a foreground node. Any structural disagreement
// (child-count or role mismatch at any position) returns the ARIA tree untouched — never worse
// than not merging. Non-web trees pass through untouched.
//
// Coordinate-space caveat: the two producers don't share one — the ARIA tree's walk adds
// window.scrollX/Y (page coordinates) while all three legacy producers keep the raw viewport
// rect. At scroll 0 (every committed capture) the spaces coincide. On a scrolled capture the
// graft moves merged nodes into viewport space — what the screenshot actually shows, a net win —
// but ARIA-only stragglers keep page coordinates, so the tree comes out mixed rather than
// uniformly page-space. Resolving that belongs to the deferred scroll-anchoring work.
function mergeWebHierarchyBounds(nodeTree: unknown, legacyTree: unknown): unknown {
  const tree = nodeTree as any;
  const legacy = legacyTree as any;
  if (!tree || typeof tree !== 'object' || !legacy || typeof legacy !== 'object') return nodeTree || null;
  const detail = tree.driverDetail;
  if (!detail || typeof detail !== 'object' || detail.class !== 'web') return nodeTree;
  // Legacy-shape bounds read (x1..y2 ints, all-zero means "unset") — same rule the inspector's
  // own bounds reader applies to this shape, so a rect grafted here is exactly a rect it shows.
  const legacyRect = (n: any): { left: number; top: number; right: number; bottom: number } | null => {
    if (!n || typeof n !== 'object') return null;
    if (![n.x1, n.y1, n.x2, n.y2].some((v: unknown) => typeof v === 'number' && v !== 0)) return null;
    const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
    const [x1, y1, x2, y2] = [num(n.x1), num(n.y1), num(n.x2), num(n.y2)];
    return x2 >= x1 && y2 >= y1 ? { left: x1, top: y1, right: x2, bottom: y2 } : null;
  };
  const kids = (n: any): any[] => (Array.isArray(n.children) ? n.children : []);
  const compatible = (a: any, b: any): boolean => {
    const aObj = a && typeof a === 'object';
    if (aObj !== (b && typeof b === 'object')) return false;
    if (!aObj) return true;
    const role = a.driverDetail && typeof a.driverDetail === 'object' ? a.driverDetail.ariaRole : null;
    if (role != null && b.className != null && role !== b.className) return false;
    const ac = kids(a);
    const bc = kids(b);
    return ac.length === bc.length && ac.every((child, i) => compatible(child, bc[i]));
  };
  if (!compatible(tree, legacy)) return nodeTree;
  const graft = (a: any, b: any): any => {
    if (!a || typeof a !== 'object') return a;
    const bc = kids(b);
    const rect = legacyRect(b);
    return {
      ...a,
      ...(rect ? { bounds: rect } : {}),
      children: Array.isArray(a.children) ? a.children.map((child: any, i: number) => graft(child, bc[i])) : a.children,
    };
  };
  return graft(tree, legacy);
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

  // Index every LLM-call-producing log into the session's llm list, mirroring extractLlmLogs'
  // selection EXACTLY (request logs + unpaired MCP sampling logs, in log order) so a trace row's
  // `llm` field is the index the viewer can hand to its transcript/usage views. Every such log
  // gets its own timeline row — the WASM report renders one child row per LLM request inside its
  // step, and that per-call row is where the transcript opens from.
  const samplingRequestTraceIds = new Set(
    logs.filter((l) => (l.llmMessages || l.llmResponse) && l.traceId).map((l) => l.traceId),
  );
  let llmIndex = 0;

  for (const log of logs) {
    const cls = logClass(log);
    const llmRowsForLog = (log.llmMessages || log.llmResponse ? 1 : 0)
      + (log.usageAndCost && log.systemPrompt !== undefined && !(log.traceId && samplingRequestTraceIds.has(log.traceId)) ? 1 : 0);
    const llmAt = llmRowsForLog ? llmIndex : null;
    llmIndex += llmRowsForLog;
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
    const viewHierarchy = log.viewHierarchyFiltered || mergeWebHierarchyBounds(log.trailblazeNodeTree, log.viewHierarchy) || log.viewHierarchy || null;
    // The log's device/viewport extent — the coordinate space the screenshot shows. Carried beside
    // the hierarchy because the tree's own extent can't reconstruct it: a web trailblazeNodeTree has
    // page-relative bounds (they run to the full scroll height) and off-viewport nodes (hidden
    // carousel slides past the right edge), so any extent derived from the nodes is polluted. The
    // inspector anchors its bounds overlay and hit-testing on this.
    const viewport = typeof log.deviceWidth === 'number' && log.deviceWidth > 0 && typeof log.deviceHeight === 'number' && log.deviceHeight > 0
      ? { w: log.deviceWidth, h: log.deviceHeight } : null;
    const ts = log.timestamp ? Date.parse(log.timestamp) : null;

    if (toolName) {
      asserts = new Map();
      if (group && traceId && group._trace === traceId) {
        if (!group.screenshotFile && screenshotFile) group.screenshotFile = screenshotFile;
        if (!group.viewHierarchy && viewHierarchy) group.viewHierarchy = viewHierarchy;
        if (!group.viewport && viewport) group.viewport = viewport;
        if (group.ok && err) { group.ok = false; group.err = err; }
        group._logs.push(log);
        continue;
      }
      closeGroup();
      const ok = log.successful !== false && !err;
      const detail = toolDetail(log);
      group = { _trace: traceId, _logs: [log], label: toolName, tool: detail.summary, note: detail.note, ms: log.durationMs || 0, ok, err: ok ? null : (err || truncate(log.resultSummary)), screenshotFile, viewHierarchy, viewport, ts };
      if (!traceId) closeGroup();
      continue;
    }

    if (action && group) {
      if (!group.screenshotFile && screenshotFile) group.screenshotFile = screenshotFile;
      if (!group.viewHierarchy && viewHierarchy) group.viewHierarchy = viewHierarchy;
      if (!group.viewport && viewport) group.viewport = viewport;
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
        if (open) { open.count++; open.ms += log.durationMs || 0; open.ok = aok; open.err = aerr; if (screenshotFile) open.screenshotFile = screenshotFile; if (viewHierarchy) open.viewHierarchy = viewHierarchy; if (viewport) open.viewport = viewport; open._logs.push(log); continue; }
        const row = { label: actionType, _logs: [log], tool: describeAction(action), ms: log.durationMs || 0, ok: aok, err: aerr, screenshotFile, viewHierarchy, viewport, ts, count: 1, mark: actionMark(action, log) };
        out.push(row); asserts.set(cond, row); continue;
      }
      asserts = new Map();
      const sig = actionType + ':' + describeAction(action);
      const prev = out[out.length - 1];
      if (prev && prev._sig === sig) { prev.count = (prev.count || 1) + 1; prev.ms += log.durationMs || 0; if (screenshotFile) prev.screenshotFile = screenshotFile; if (viewHierarchy) prev.viewHierarchy = viewHierarchy; if (viewport) prev.viewport = viewport; prev._logs.push(log); continue; }
      out.push({ _sig: sig, _logs: [log], label: actionType, tool: describeAction(action), ms: log.durationMs || 0, ok: true, err: null, screenshotFile, viewHierarchy, viewport, ts, count: 1, mark: actionMark(action, log) });
      continue;
    }

    if (promptText) {
      asserts = new Map(); closeGroup();
      const isObjective = cls === 'ObjectiveStartLog';
      if (isObjective) objective = promptText;
      // Each agent turn (TrailblazeLlmRequestLog) re-logs the active objective as its promptStep.
      // Repeating that text would be noise, but the CALL itself is a step of the run — the WASM
      // report shows one "LLM Request" child row per call inside its step, and that row is where
      // the transcript opens from — so the turn gets a row under the call's own label instead of
      // being dropped.
      // Deliberately screenshot-less: the request log carries its own set-of-mark image, and
      // passing it through would pull one more inlined screenshot into the report per LLM call —
      // measured at roughly double the embedded screenshot bytes on real sessions (+1MB on a
      // 615KB report), ~50x the transcript payload this feature adds. Like every other
      // screenshot-less row, it previews the next captured frame (the outcome of the tool this
      // call chose), and the call's own detail lives in the transcript the row opens.
      else if (promptText === objective && !err && llmAt != null) {
        out.push({ label: log.llmRequestLabel || 'LLM Request', _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: true, err: null, screenshotFile: null, viewHierarchy, viewport, ts, llm: llmAt });
        continue;
      }
      else if (promptText === objective && !err) continue;
      // `objective` marks the top-level trail steps (ObjectiveStartLog) so the timeline
      // can nest the tool calls / assertions that follow under their step. `trailhead` marks the
      // objective lowered from the trail's `trailhead:` (its step 0) — the DirectionStep.isTrailhead
      // flag rides through the ObjectiveStartLog's promptStep.
      const prow = { label: truncate(promptText, 120), _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: !err, err, screenshotFile, viewHierarchy, viewport, ts, objective: isObjective, trailhead: isObjective && log.promptStep?.isTrailhead === true, ...(llmAt != null ? { llm: llmAt } : {}) };
      out.push(prow);
      if (isObjective) objRow = prow;
      continue;
    }

    // An LLM-call log that carries no prompt text (e.g. a standalone MCP sampling call) still
    // surfaces as its own timeline row — every LLM call must be reachable from its step. Also
    // screenshot-less, for the embedded-bytes reason above.
    if (llmAt != null) {
      asserts = new Map(); closeGroup();
      out.push({ label: log.llmRequestLabel || (log.systemPrompt !== undefined ? 'MCP sampling' : 'LLM Request'), _logs: [log], tool: log.modelName ? `llm · ${log.modelName}` : 'agent step', ms: log.durationMs || 0, ok: !err, err, screenshotFile: null, viewHierarchy, ts, llm: llmAt });
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
      out.push({ label, _logs: [log], tool: '', terminal: true, ms: log.durationMs || 0, ok: log.displayName !== 'failure_screenshot', err: null, screenshotFile, viewHierarchy, ts });
      continue;
    }

    if (err) {
      asserts = new Map(); closeGroup();
      out.push({ label: 'Error', _logs: [log], tool: '', terminal: true, ms: 0, ok: false, err, screenshotFile, viewHierarchy, ts });
    }
  }
  closeGroup();

  return out.map((r, idx) => {
    const { _sig, _trace, count, note, ...rest } = r;
    const merged = count > 1 ? (note ? note + ' · ×' + count : '×' + count) : note;
    const children = toolChildren(r);
    const params = children ? toolParams(r) : null;
    const withChildren = children ? { ...rest, children, ...(params ? { params } : {}) } : rest;
    return merged != null ? { ...withChildren, note: merged, i: idx + 1 } : { ...withChildren, i: idx + 1 };
  });
}

// Every parameter of a composite tool call, unabridged. A composite (a scripted trailhead) is
// configured, not inferred — its arguments ARE its documentation — so the row lists all of them
// instead of the three-key crop summarizeToolArgs gives ordinary rows.
function toolParams(r: any): string[] | null {
  const log = (r._logs || [])[0];
  const raw = log && log.trailblazeTool && log.trailblazeTool.raw && typeof log.trailblazeTool.raw === 'object' ? log.trailblazeTool.raw : null;
  if (!raw) return null;
  const skip = { reason: 1, reasoning: 1 };
  const out = Object.keys(raw)
    .filter((k) => !skip[k] && raw[k] != null)
    .map((k) => `${k}=${typeof raw[k] === 'object' ? JSON.stringify(raw[k]) : String(raw[k])}`);
  return out.length ? out : null;
}

// Every tool call this row stands for besides the one it is labelled with, from two sources:
// the `executableTools` a DelegatingTrailblazeToolLog expanded into (e.g. `tap` on a ref
// resolving to `tapOnElementBySelector`), and the sibling tool logs the traceId fold merged in.
// Both are already collapsed into this single row, so surfacing them as expandable children is
// what makes "this step ran those tools" followable. Returns null for a row that really did stand
// for one tool (primitives, scripted host-side tools calling backend APIs directly, raw actions).
/**
 * Lift the machine-readable code off a structured tool-error payload (`TrailblazeToolLog.errorPayload`).
 * TS twin of the Kotlin `failureCodeOf`: an object payload's top-level string `code`, nothing else —
 * non-object payloads, missing `code`, and non-string `code` values all yield null.
 */
function errorCodeOf(payload: any): string | null {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  return typeof payload.code === 'string' ? payload.code : null;
}

function toolChildren(r: any): TraceChild[] | null {
  const logs: any[] = r._logs || [];
  const isDelegating = (l: any) => logClass(l) === 'DelegatingTrailblazeToolLog';
  // A traceId is allocated per LLM request (one turn's tool batch), not per objective, so an
  // objective spanning several turns folds into several of these rows — each row is one turn.
  // Only a TrailblazeToolLog is an executed tool: MCP request/response and agent-iteration logs
  // share the row's id and name, so keying off "anything with a toolName" nests the row under itself.
  const isExecuted = (l: any) => logClass(l) === 'TrailblazeToolLog';
  const executed = logs
    .map((l, i) => ({ l, i }))
    .filter(({ l, i }) => i > 0 && l && isExecuted(l))
    .map(({ l, i }) => ({ i, label: String(l.toolName), tool: toolDetail(l).summary, sig: JSON.stringify((l.trailblazeTool && l.trailblazeTool.raw) ?? null), ms: l.durationMs || 0, ok: l.successful !== false, err: l.successful === false ? (typeof l.errorMessage === 'string' && l.errorMessage) || (typeof l.exceptionMessage === 'string' && l.exceptionMessage) || null : null, code: l.successful === false ? errorCodeOf(l.errorPayload) : null }));
  // A delegating log is a dispatch wrapper, not a step: it declares executors the device then logs
  // itself under the same traceId. Keep executed records; surface a declaration only to fill in an
  // executor that never logged — matched to its executor by name AND args (not name alone) and by
  // remaining count, so a repeated primitive with one unlogged dispatch still shows the missing call.
  const key = (c: { label: string; tool: string }) => JSON.stringify([c.label, c.tool]);
  const unmatched = new Map<string, number>();
  // Seed logs[0]'s own identity (excluded from `executed` by the i>0 filter) so only a wrapper
  // re-declaring exactly it is absorbed — a separate same-named dispatch with other args still shows.
  unmatched.set(key(r), (unmatched.get(key(r)) || 0) + 1);
  for (const c of executed) unmatched.set(key(c), (unmatched.get(key(c)) || 0) + 1);
  const declared: Array<{ i: number; label: string; tool: string; sig: string; ms: number | null; ok: boolean; err: string | null; code: string | null }> = [];
  logs.forEach((l, i) => {
    if (!isDelegating(l)) return;
    for (const e of (Array.isArray(l.executableTools) ? l.executableTools : [])) {
      const c = { i, label: (e && e.toolName) || '', tool: summarizeToolArgs((e && e.raw) || {}, {}), sig: JSON.stringify((e && e.raw) ?? null), ms: null, ok: true, err: null, code: null };
      if (!c.label) continue;
      const n = unmatched.get(key(c)) || 0;
      if (n > 0) { unmatched.set(key(c), n - 1); continue; }
      declared.push(c);
    }
  });
  // Order by log position so children read in dispatch order, not declarations-first. Then fold
  // consecutive identical dispatches into one ×N child with the durations summed — a composite
  // trailhead tool (a scripted UI sign-in) dispatches the same primitive dozens of times in a row,
  // and N identical lines hide the one that matters. Only same-outcome runs fold, so a failed
  // dispatch never disappears into a green ×N. The fold compares the raw args (`sig`), not the
  // lossy display summary, so two dispatches whose differences were truncated away stay separate;
  // it also requires matching ms-nullity so an executed dispatch never folds into a declared-only
  // one (whose null ms means "never logged", not "took 0ms").
  const ordered = [...executed, ...declared].sort((a, b) => a.i - b.i);
  const kids: TraceChild[] = [];
  let prevSig = '';
  for (const c of ordered) {
    const prev = kids[kids.length - 1];
    if (prev && prev.label === c.label && prev.tool === c.tool && prev.ok === c.ok && prevSig === c.sig && (prev.ms == null) === (c.ms == null)) {
      prev.count = (prev.count || 1) + 1;
      if (c.ms != null) prev.ms = (prev.ms || 0) + c.ms;
      continue;
    }
    kids.push({ label: c.label, tool: c.tool, ms: c.ms, ok: c.ok, err: c.err ?? null, code: c.code ?? null, count: 1 });
    prevSig = c.sig;
  }
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

// ─────────────────────────────────────────────────────────────────────────────
// Input-token composition (parity with the legacy WASM report's LLM Usage tab).
//
// Parity anchor: LlmUsageComposable.kt (trailblaze-ui, xyz.block.trailblaze.ui.tabs.session)
// renders, per request, the LlmInputTokenBreakdown that LlmTokenBreakdownEstimator
// .estimateBreakdown (trailblaze-models, xyz.block.trailblaze.llm.LlmTokenBreakdownEstimator)
// computed at agent runtime and stored on the log's llmRequestUsageAndCost. Extraction embeds
// those numbers as a small `comp` object — never the messages themselves. When a log predates
// the stored breakdown, the math is re-run here over the log's flattened llmMessages
// ({role, message}) + toolOptions.
// ─────────────────────────────────────────────────────────────────────────────

const LLM_COMP_CHARS_PER_TOKEN = 4;
// ImageTokenFormula.DEFAULT_ESTIMATE — the flat per-image token estimate used when no image
// dimensions / provider formula are available (the stored breakdown already applied the model's
// real formula at runtime; this constant only serves the extraction-time fallback).
const LLM_COMP_TOKENS_PER_IMAGE = 765;
// JSON structure overhead the Kotlin estimator charges per tool descriptor.
const LLM_COMP_TOOL_OVERHEAD_CHARS = 200;

// Port of LlmTokenBreakdownEstimator.estimateBreakdown over the log's flattened message shape.
// Roles are the TrailblazeLlmMessage vocabulary ("system" | "user" | "assistant" | "tool_use" |
// "tool_result" — TrailblazeLogger.toTrailblazeLlmMessages flattens Koog messages so tool
// calls/results become their own entries). Character counts are categorized (system prompts /
// initial-phase user prompts / tool descriptors), converted at ~4 chars per token, images
// estimated flat, then everything is scaled so the categories sum to the LLM-reported input
// total, with the rounding remainder folded into tools (the largest category). Returns null when
// there is nothing to estimate from (no messages) or no reported input total to distribute
// (matching the runtime, which only computes a breakdown when inputTokens > 0).
function estimateLlmComp(
  messages: unknown[] | null | undefined,
  toolOptions: unknown[] | null | undefined,
  totalInputTokens: number | null | undefined,
): LlmComp | null {
  const msgs: any[] = Array.isArray(messages) ? messages : [];
  const tools: any[] = Array.isArray(toolOptions) ? toolOptions : [];
  const total = Number(totalInputTokens);
  if (!msgs.length || !(total > 0)) return null;
  let systemChars = 0, userChars = 0, imageCount = 0, systemCount = 0, userCount = 0;
  let initialPhase = true, seenFirstUser = false;
  for (const m of msgs) {
    const role = String((m && m.role) || '').toLowerCase();
    const text = m && typeof m.message === 'string' ? m.message : '';
    if (role === 'system') { systemCount++; systemChars += text.length; continue; }
    if (role === 'user') {
      userCount++;
      // The flattened log renders an image attachment as an "- Image (…)" inventory line
      // (TrailblazeLogger); those lines are the only image signal left in this shape.
      imageCount += (text.match(/^- Image \(/gm) || []).length;
      if (initialPhase) {
        userChars += text.length;
        if (!seenFirstUser) seenFirstUser = true;
        // The authored prompt ends at the first user turn carrying a view-hierarchy dump.
        else if (text.indexOf('view_hierarchy') >= 0 || text.indexOf('ViewHierarchy') >= 0) initialPhase = false;
      }
      continue;
    }
    // assistant / tool_use / tool_result: conversation history has begun — later user turns are
    // per-turn screen state, so (matching the Kotlin) their chars are NOT counted as user prompt.
    if (role === 'assistant' || role === 'tool_use' || role === 'tool_result') initialPhase = false;
  }
  const toolChars = tools.reduce(
    (n, t) => n + String((t && t.name) || '').length + String((t && t.description) || '').length + LLM_COMP_TOOL_OVERHEAD_CHARS,
    0,
  );
  const estimatedTextTokens = Math.trunc((systemChars + userChars + toolChars) / LLM_COMP_CHARS_PER_TOKEN);
  const estimatedImageTokens = imageCount * LLM_COMP_TOKENS_PER_IMAGE;
  const totalEstimated = estimatedTextTokens + estimatedImageTokens;
  const scale = totalEstimated > 0 ? total / totalEstimated : 1;
  const system = Math.trunc((systemChars / LLM_COMP_CHARS_PER_TOKEN) * scale);
  const user = Math.trunc((userChars / LLM_COMP_CHARS_PER_TOKEN) * scale);
  let toolTokens = Math.trunc((toolChars / LLM_COMP_CHARS_PER_TOKEN) * scale);
  const images = Math.trunc(estimatedImageTokens * scale);
  // Remainder fold, clamped at zero: when the measured chars over-estimate the reported total (a
  // standalone MCP-sampling call, whose tool descriptors dominate a short prompt) the raw fold goes
  // negative, and a negative category renders as a legend number the bar can't draw.
  toolTokens = Math.max(0, toolTokens + (total - (system + user + toolTokens + images)));
  return {
    system, user, tools: toolTokens, images,
    systemCount, userCount, toolsCount: tools.length, imagesCount: imageCount,
    est: system + user + toolTokens + images,
  };
}

// The runtime-computed breakdown stored on the log when present (exact parity with the numbers
// the WASM report rendered), else the extraction-time estimate above. `est` mirrors the Kotlin
// totalEstimatedTokens getter (a computed property, so it is never in the serialized log).
function llmCompOf(usage: any, messages: unknown[] | null | undefined, toolOptions: unknown[] | null | undefined): LlmComp | null {
  const b = usage && usage.inputTokenBreakdown;
  if (b) {
    const tokens = (c: any) => Number((c && c.tokens) || 0);
    const count = (c: any) => Number((c && c.count) || 0);
    const system = tokens(b.systemPrompt), user = tokens(b.userPrompt), tools = tokens(b.toolDescriptors), images = tokens(b.images);
    return {
      system, user, tools, images,
      systemCount: count(b.systemPrompt), userCount: count(b.userPrompt), toolsCount: count(b.toolDescriptors), imagesCount: count(b.images),
      est: system + user + tools + images,
    };
  }
  return estimateLlmComp(messages, toolOptions, usage ? usage.inputTokens : null);
}

// Port of LlmRequestUsageAndCost.cacheSavings: what this call's cached input reads would have
// cost at the full input rate minus what they cost at the cached rate. 0 when nothing was cached
// or the log carries no pricing; an absent cached rate defaults to the full rate (no discount),
// matching the Kotlin model default.
function llmCacheSavings(usage: any): number {
  const cached = Number((usage && usage.cacheReadInputTokens) || 0);
  if (!(cached > 0)) return 0;
  const model = (usage && usage.trailblazeLlmModel) || {};
  const fullRate = Number(model.inputCostPerOneMillionTokens);
  if (!Number.isFinite(fullRate)) return 0;
  const cachedRate = model.cachedInputCostPerOneMillionTokens == null ? fullRate : Number(model.cachedInputCostPerOneMillionTokens);
  return (cached * fullRate) / 1_000_000 - (cached * cachedRate) / 1_000_000;
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
  // The provider half of the repo's canonical `<provider id>/<model id>` LLM identity (the form
  // `trailblaze config` prints, TrailCommand's "Using LLM:" line uses, and workspace LLM config
  // keys models under). It rides on the log's TrailblazeLlmModel.trailblazeLlmProvider; a log that
  // only carries a bare model name (older payload, or a modelName-only log) has no provider, and
  // nothing recoverable — the built-in catalog is not part of the report payload, and inferring a
  // provider from a model id would be a guess presented as fact. Those render as the bare model id.
  const providerOf = (...candidates) => {
    for (const c of candidates) {
      const id = c?.trailblazeLlmProvider?.id;
      if (typeof id === 'string' && id) return id;
    }
    return null;
  };
  for (const log of logs) {
    if (log.llmMessages || log.llmResponse) {
      const u = log.llmRequestUsageAndCost;
      const model = (u?.trailblazeLlmModel?.modelId)
        || (log.trailblazeLlmModel?.modelId)
        || log.modelName
        || '?';
      rows.push({
        model,
        provider: providerOf(u?.trailblazeLlmModel, log.trailblazeLlmModel),
        inputTokens: u?.inputTokens ?? null,
        outputTokens: u?.outputTokens ?? null,
        cacheReadTokens: u?.cacheReadInputTokens ?? 0,
        promptCost: u?.promptCost ?? null,
        completionCost: u?.completionCost ?? null,
        cacheSavings: llmCacheSavings(u),
        comp: llmCompOf(u, log.llmMessages, log.toolOptions),
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
      const provider = providerOf(u?.trailblazeLlmModel);
      // A standalone sampling log has no llmMessages, but its prompt fields carry the same
      // composition signal — synthesize them in the flattened message shape estimateLlmComp
      // reads (an "- Image (…)" inventory line stands in for the included screenshot), so these
      // calls get a composition estimate instead of em-dashes when no breakdown was stored.
      const samplingMessages = [
        ...(typeof log.systemPrompt === 'string' && log.systemPrompt ? [{ role: 'system', message: log.systemPrompt }] : []),
        // Emit the user entry when there's a user message OR a screenshot — an empty user message
        // with includedScreenshot still carries the image signal.
        ...((typeof log.userMessage === 'string' && log.userMessage) || log.includedScreenshot
          ? [{ role: 'user', message: (typeof log.userMessage === 'string' ? log.userMessage : '') + (log.includedScreenshot ? '\n\nAttachments:\n- Image (screenshot)' : '') }]
          : []),
      ];
      rows.push({
        model,
        provider,
        inputTokens: u?.inputTokens ?? null,
        outputTokens: u?.outputTokens ?? null,
        cacheReadTokens: u?.cacheReadInputTokens ?? 0,
        promptCost: u?.promptCost ?? null,
        completionCost: u?.completionCost ?? null,
        cacheSavings: llmCacheSavings(u),
        comp: llmCompOf(u, samplingMessages, []),
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
  // Structured-payload tools used to summarize to nothing (the generic scan below drops every
  // object-valued arg), leaving a bare tool name in the timeline. Name the work instead:
  // `commands` is the maestro contract (a list of single-key command objects), `argv` is exec's.
  if (Array.isArray(a.commands) && a.commands.length) {
    const names = a.commands
      .map((c: any) => (c && typeof c === 'object' ? Object.keys(c)[0] : String(c)))
      .filter(Boolean);
    if (names.length) return truncate(names.join(' · '), 60);
  }
  if (Array.isArray(a.argv) && a.argv.length) return truncate(a.argv.join(' '), 60);
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
    terminal: !!t.terminal,
    count: t.count || null,
    mark: t.mark || null,
    // The row's index into the session's llm call list (extractLlmLogs order) — how the viewer
    // opens the right transcript from a timeline row. Only present on LLM-call rows.
    ...(t.llm != null ? { llm: t.llm } : {}),
    // The capture's viewport — the inspector's coordinate anchor (see TraceStep.viewport). Kept
    // only where a hierarchy rides along; ~20 bytes, unlike the hierarchy it describes.
    ...(t.viewport && t.viewHierarchy != null ? { viewport: t.viewport } : {}),
    // Per-child fields ride only when they carry signal (an executed ms, a failure, a real fold)
    // — the common green declared-or-single dispatch slims to just label+tool.
    ...(t.children && t.children.length ? { children: t.children.map((c: any) => ({ label: c.label, tool: c.tool || '', ...(c.ms != null ? { ms: c.ms } : {}), ...(c.ok === false ? { ok: false } : {}), ...(c.ok === false && c.err ? { err: c.err } : {}), ...(c.ok === false && c.code ? { code: c.code } : {}), ...((c.count || 1) > 1 ? { count: c.count } : {}) })) } : {}),
    // The composite call's full argument list (see toolParams). Only present beside children.
    ...(t.params && t.params.length ? { params: t.params } : {}),
  }));
}

// Per-step view hierarchies lifted off the extracted trace for the UI Inspector, keyed by the
// row's 1-based ordinal (TraceStep.i) — the side-channel SessionPayload.hierarchies carries so
// slimTraceForShare can keep dropping the heavy field from the embedded rows themselves.
//
// Size budget (grep REPORT_SIZE_BUDGET), matching the file's cap-plus-budget policy (an
// unconditional structural cap, with a tighter pass-gated budget layered on top — same shape as
// MAX_LOG_BYTES vs the passed-session log trim): hierarchies embed in step order until the
// serialized total crosses the budget, and the rest are dropped. An affirmatively PASSED session
// gets the tight budget; every other status (failed / cancelled / running / unknown — the
// evidence an inspector exists for) and `--full-report-payloads` (which reaches here as
// sessionPassed=false, see formatterContext in run-report-cli.ts) keeps everything up to the
// unconditional hard cap. Returns null when no row carries a hierarchy (or the first one alone
// already exceeds the budget).
const HIERARCHY_PASSED_BUDGET_CHARS = 8 * 1024 * 1024;
const HIERARCHY_HARD_CAP_CHARS = 64 * 1024 * 1024;
function traceHierarchies(
  trace: RawTraceRow[] | null | undefined,
  sessionPassed: boolean,
  budgetChars?: number,
): Record<string, unknown> | null {
  const budget = budgetChars != null ? budgetChars : (sessionPassed ? HIERARCHY_PASSED_BUDGET_CHARS : HIERARCHY_HARD_CAP_CHARS);
  const out: Record<string, unknown> = {};
  let total = 0;
  let kept = 0;
  for (const t of trace || []) {
    if (t.viewHierarchy == null || t.i == null) continue;
    total += JSON.stringify(t.viewHierarchy).length;
    if (total > budget) break;
    out[String(t.i)] = t.viewHierarchy;
    kept++;
  }
  return kept ? out : null;
}

// Agent-reasoning trace rows carry an `llm` call index or an 'agent step' / 'llm · …' tool; the
// index counts them as LLM calls (the session's llm request list), never as executed tool calls.
function isLlmTurnRow(t: { tool?: string | null; llm?: number | null }): boolean {
  if (t.llm != null) return true;
  const tool = String(t.tool || '');
  return tool === 'agent step' || tool.indexOf('llm') === 0;
}

// Count real test steps from a trace: objective rows, trailhead excluded — `meta.steps` is the
// flat trace length (action rows AND step headers), which overstates what a reader calls a step.
function traceStepCount(trace: TraceStep[]): number {
  return trace.filter((t) => t.objective && !t.trailhead).length;
}

// Count executed tool calls from a trace. Terminal snapshot/error rows carry `terminal` and stay
// out; a traceId fold merges one turn's extra tool calls into the row's children — each is a real
// dispatched call the timeline exposes, so they count too. These two counters feed the report's
// index entries (buildMultiReportHtml) AND the viewer's per-run stats, so both always agree.
function traceToolCallCount(trace: TraceStep[]): number {
  return trace.filter((t) => !t.objective && !t.terminal && !isLlmTurnRow(t))
    .reduce((n, t) => n + 1 + (t.children || []).reduce((m, c) => m + (c.count || 1), 0), 0);
}

// Keep what makes the LLM view skimmable — the model, token/cost accounting, the step it ran
// under, and the assistant's reasoning + chosen tool. `messages` (the system prompt + per-turn
// screen-state dumps) deliberately stay OFF these rows: embedded verbatim per call they'd dwarf
// the screenshots in file size. The full transcripts ride the session's llmMessages /
// llmMessagesGz side-channel instead (extractLlmTranscripts), pooled + compressed; the
// input-token composition survives on the row as the small `comp` numbers computed at
// extraction time.
function slimLlmForShare(llmLogs: RawLlmRow[] | null | undefined): LlmCall[] {
  return (llmLogs || []).map((r) => ({
    model: r.model,
    // Omitted when unknown, so an older payload (no provider recorded) is indistinguishable from a
    // new one that genuinely has none — both render the bare model id.
    ...(r.provider ? { provider: r.provider } : {}),
    inputTokens: r.inputTokens ?? null,
    outputTokens: r.outputTokens ?? null,
    cacheReadTokens: r.cacheReadTokens || 0,
    totalCost: r.totalCost ?? null,
    promptCost: r.promptCost ?? null,
    completionCost: r.completionCost ?? null,
    cacheSavings: r.cacheSavings || 0,
    comp: r.comp ? {
      system: r.comp.system || 0, user: r.comp.user || 0, tools: r.comp.tools || 0, images: r.comp.images || 0,
      systemCount: r.comp.systemCount || 0, userCount: r.comp.userCount || 0, toolsCount: r.comp.toolsCount || 0, imagesCount: r.comp.imagesCount || 0,
      est: r.comp.est || 0,
    } : null,
    durationMs: r.durationMs || 0,
    label: r.label || 'LLM Request',
    instructions: r.instructions || null,
    response: (r.response || []).map((p) => p.kind === 'tool'
      ? { kind: 'tool', tool: p.tool, args: p.args || null, reasoning: p.reasoning || null }
      : { kind: 'text', text: p.text || '' }),
  }));
}

// Full per-call chat transcripts for the LLM tab's Transcript view: the system prompt, per-turn
// user messages (including screen-state dumps), assistant turns, and tool calls/results, one
// message list per call, aligned by index with slimLlmForShare's rows. Two size disciplines make
// re-embedding what slimLlmForShare drops affordable:
//  - Message texts are POOLED (`texts` + per-call {role, t} refs): the conversation history
//    accumulates, so call N repeats every earlier turn verbatim — and gzip alone can't neutralize
//    that repetition, because its 32KB window is smaller than one screen-state dump.
//  - Image data URIs inside messages are replaced with a placeholder: those screenshots are
//    already embedded separately in the report (session.shots), so keeping them here would embed
//    every screenshot twice.
// Returns null when no call carries messages (MCP-sampling-only sessions, older logs).
const TRANSCRIPT_IMAGE_PLACEHOLDER = '[screenshot]';
function extractLlmTranscripts(llmLogs: RawLlmRow[] | null | undefined): LlmTranscripts | null {
  const rows = llmLogs || [];
  // A truthy non-array `messages` (a malformed log record) reads as no messages — this runs inside
  // the per-session map, so one bad record must never fail report generation for the whole run.
  const rowMessages = (r: RawLlmRow): any[] => Array.isArray(r.messages) ? r.messages : [];
  if (!rows.some((r) => rowMessages(r).length)) return null;
  const texts: string[] = [];
  const pool = new Map<string, number>();
  const calls = rows.map((r) => rowMessages(r).map((m: any) => {
    const text = String((m && m.message) ?? '')
      .replace(/data:image\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]+/gi, TRANSCRIPT_IMAGE_PLACEHOLDER);
    let t = pool.get(text);
    if (t == null) { t = texts.length; texts.push(text); pool.set(text, t); }
    const role = String((m && m.role) || '');
    return m && m.toolName ? { role, t, toolName: String(m.toolName) } : { role, t };
  }));
  return { texts, calls };
}

// Resolve one call's transcript back to renderable {role, text, toolName} messages. Null when the
// session carries no transcripts (older payloads, or a gz blob that hasn't inflated yet); an
// empty array when transcripts exist but this call logged no messages — including a malformed
// per-call entry that isn't an array, so a corrupt transcript degrades instead of throwing.
function transcriptCallMessages(tx: LlmTranscripts | null | undefined, call: number): Array<{ role: string; text: string; toolName?: string | null }> | null {
  if (!tx || !Array.isArray(tx.texts) || !Array.isArray(tx.calls)) return null;
  const messages = tx.calls[call];
  return (Array.isArray(messages) ? messages : []).map((m) => ({
    role: String((m && m.role) || ''),
    text: String(tx.texts[m && m.t] ?? ''),
    ...(m && m.toolName ? { toolName: m.toolName } : {}),
  }));
}

// Pre-pack the lifted hierarchies on session INPUTS for a producer that is about to serialize
// them into an HTML document (the in-app Share button, the zip pipeline's HTML export): compress
// past the same inline threshold the bun driver's packGz uses, so a browser-produced report gets
// the identical gz side-channel — the ~6.5x byte saving AND the deferred JSON.parse (an inline
// map lives inside the #tb-session chunk parsed on session open; the gz form is only inflated
// when an inspector is first opened). Mutates in place; a caller that already packed (the bun
// driver) or has no hierarchies is untouched, and a runtime without CompressionStream falls back
// to the inline lift toSessionPayloads would have done anyway. The shell's in-place hydrate path
// deliberately does NOT call this: its payloads stay live JS objects that are never serialized,
// so inline costs nothing there.
const HIERARCHY_INLINE_MAX_CHARS = 64 * 1024; // keep in lockstep with LOG_INLINE_MAX_CHARS (run-report-cli.ts)
async function packSessionInputsHierarchies(sessions: SessionInput[] | null | undefined): Promise<void> {
  for (const s of sessions || []) {
    if (s.hierarchies || s.hierarchiesGz) continue;
    const lifted = traceHierarchies(s.trace, String((s.meta || {}).status || '') === 'passed');
    if (!lifted) continue;
    const json = JSON.stringify(lifted);
    if (json.length > HIERARCHY_INLINE_MAX_CHARS) {
      const gz = await deflateGzText(json);
      if (gz) { s.hierarchiesGz = gz; continue; }
    }
    s.hierarchies = lifted;
  }
}

// Session inputs (what a report generator or the zip pipeline derives) → the payload shape the
// viewer reads: trace and LLM calls slimmed to their share subset, `llmLogs` renamed to the `llm`
// key the viewer indexes, and recording/original YAML lifted off meta so the (potentially large)
// strings aren't carried twice.
//
// Shared by the two ways a report reaches a viewer: buildMultiReportHtml embeds the result as inert
// JSON, and the viewer shell assigns it straight to window.__TB_RUN_DATA__ after loading an archive.
// Both therefore hand the viewer byte-identical session data — a shell-rendered report and an
// exported one can't diverge in what the viewer actually sees.
function toSessionPayloads({ generatedAt, sessions }: { generatedAt?: string; sessions: SessionInput[] }): SessionPayload[] {
  return (sessions || []).map((s) => {
    const trace = s.trace || [];
    const { recordingYaml = null, originalYaml = null, ...metaRest } = s.meta || {};
    // A caller that already packed hierarchies (the bun driver's packHierarchies, inline or gz)
    // wins; otherwise lift them off the trace rows here so browser producers (the in-app Share
    // button, the zip pipeline) carry the inspector data without their own packing step. The
    // shared budget in traceHierarchies keeps both paths' trimming behavior identical.
    const hierarchies = (s.hierarchies || s.hierarchiesGz)
      ? (s.hierarchies || null)
      : traceHierarchies(trace, String((s.meta || {}).status || '') === 'passed');
    return {
      meta: { generatedAt: generatedAt || '', ...metaRest, steps: trace.length || metaRest.steps || 0 },
      trace: slimTraceForShare(trace),
      llm: slimLlmForShare(s.llmLogs),
      shots: s.shots || {},
      recordingYaml: s.recordingYaml || recordingYaml,
      originalYaml: s.originalYaml || originalYaml,
      deviceLog: s.deviceLog || null,
      deviceLogGz: s.deviceLogGz || null,
      network: s.network || null,
      networkGz: s.networkGz || null,
      events: s.events || null,
      eventsGz: s.eventsGz || null,
      // The bun driver supplies the transcripts pre-packed (inline or gz — packLlmMessages in
      // run-report-cli.ts); the browser/zip paths hand raw llmLogs, so derive them here.
      llmMessages: s.llmMessages !== undefined ? s.llmMessages : (s.llmMessagesGz ? null : extractLlmTranscripts(s.llmLogs)),
      llmMessagesGz: s.llmMessagesGz || null,
      hierarchies,
      hierarchiesGz: s.hierarchiesGz || null,
      video: s.video || null,
    };
  });
}

export {
  truncate, logClass, originalYamlFromLogs, yamlRootSection, localRunAgentPrompt, extractTrace, mergeWebHierarchyBounds,
  toolChildren, describeAction, parseLlmResponse, extractLlmLogs, estimateLlmComp, stepText, toolDetail,
  summarizeToolArgs, describeSelector, slimTraceForShare, slimLlmForShare, toSessionPayloads,
  isLlmTurnRow, traceStepCount, traceToolCallCount, extractLlmTranscripts, transcriptCallMessages,
  traceHierarchies, packSessionInputsHierarchies,
};

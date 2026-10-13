// What a focused device has been doing: its sessions' logs folded into steps, the tools each step
// ran, and what is happening right now. Pure, so the rules have tests - the screen only streams
// logs in and draws the answer.
//
// Logs arrive raw (the session stream sends the on-disk JSON) and some are large - an LLM request
// carries a whole view hierarchy - so `slimLog` keeps only what the feed shows and the screen holds
// those, never the raw records.
//
// Dual-exported like follow-run-model.js: `window.DeviceActivityModel` for the classic-script app
// and `module.exports` for Bun tests.
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.DeviceActivityModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  // The tool the agent calls to report a step's outcome. It is the step's result, not a tool the
  // device ran, so it becomes the step's note instead of a row.
  var STATUS_TOOL = 'objectiveStatus';
  // Arguments only the LLM fills in; they would bury the ones that say what the call did.
  var HIDDEN_ARGS = { reasoning: true, toolName: true, class: true };
  var ARGS_MAX = 140;

  function lastSegment(cls) {
    var s = String(cls || '');
    var i = s.lastIndexOf('.');
    return i < 0 ? s : s.slice(i + 1);
  }

  // A tool's own arguments: recorded tools wrap them in `raw`, the rest are the tool object itself.
  function toolArgs(tool) {
    if (!tool || typeof tool !== 'object') return {};
    var args = tool.raw && typeof tool.raw === 'object' ? tool.raw : tool;
    var out = {};
    Object.keys(args).forEach(function (k) { if (!HIDDEN_ARGS[k]) out[k] = args[k]; });
    return out;
  }

  // `key: value` pairs on one line, nested values as compact JSON, cut to a readable length.
  function argsSummary(args) {
    var parts = Object.keys(args || {}).map(function (k) {
      var v = args[k];
      return k + ': ' + (typeof v === 'string' ? v : JSON.stringify(v));
    });
    var s = parts.join(', ');
    return s.length > ARGS_MAX ? s.slice(0, ARGS_MAX - 1) + '…' : s;
  }

  function stepOf(promptStep) {
    var p = promptStep || {};
    var verify = lastSegment(p['class']) === 'VerificationStep' || (p.verify != null && p.step == null);
    return { stepKind: verify ? 'verify' : 'step', text: String((verify ? p.verify : p.step) || p.verify || p.step || ''), recorded: !!p.recording };
  }

  // The fields of one raw log the feed uses, or null for a log it ignores.
  function slimLog(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var cls = lastSegment(raw['class']);
    var ts = Date.parse(raw.timestamp) || 0;
    switch (cls) {
      case 'TrailblazeSessionStatusChangeLog': {
        var st = raw.sessionStatus || {};
        var cfg = st.trailConfig || {};
        return { cls: 'status', ts: ts, status: lastSegment(st['class']), title: cfg.title || null };
      }
      case 'ObjectiveStartLog':
        return Object.assign({ cls: 'start', ts: ts }, stepOf(raw.promptStep));
      case 'ObjectiveCompleteLog': {
        var r = raw.objectiveResult || {};
        // Success classes live under AgentTaskStatus.Success, failures under .Failure.
        var passed = /\.Success\./.test(String(r['class'] || '')) || lastSegment(r['class']) === 'ObjectiveComplete';
        return Object.assign({ cls: 'complete', ts: ts, passed: passed, explanation: r.llmExplanation || null }, stepOf(raw.promptStep));
      }
      case 'TrailblazeToolLog': {
        if (raw.toolName === STATUS_TOOL) {
          var a = toolArgs(raw.trailblazeTool);
          return { cls: 'note', ts: ts, explanation: a.explanation || null };
        }
        return {
          cls: 'tool', ts: ts, name: raw.toolName, ok: raw.successful !== false, ms: raw.durationMs || 0,
          args: argsSummary(toolArgs(raw.trailblazeTool)), error: raw.exceptionMessage || null,
        };
      }
      case 'TrailblazeLlmRequestLog': {
        var names = (raw.actions || []).map(function (x) { return x && x.name; })
          .filter(function (n) { return n && n !== STATUS_TOOL; });
        return { cls: 'llm', ts: ts, actions: names };
      }
      default:
        return null;
    }
  }

  // Fold one session's slim logs, in arrival order, into what the feed draws:
  //   title      the session's trail title, if it said
  //   ended      'passed' | 'failed' | null while it is still open
  //   items      [{ kind: 'step', stepKind, text, recorded, state, startTs, endTs, tools, note }
  //               | { kind: 'tool', name, ok, ms, args, error, ts }]  - a tool outside any step is
  //               a tap or key from the mirror, or a lone tool call
  //   now        null, or { label, tool } for the step still running: the tool the AI just chose,
  //              or what the step is waiting on
  function fold(slims) {
    var title = null;
    var ended = null;
    var items = [];
    var cur = null;
    var pending = [];
    (slims || []).forEach(function (l) {
      if (!l) return;
      if (l.cls === 'status') {
        if (l.title) title = l.title;
        if (/^(Succeeded|SucceededWithSelfHeal)$/.test(l.status)) ended = 'passed';
        else if (l.status && l.status !== 'Started' && l.status !== 'Unknown') ended = 'failed';
        if (ended && cur) { cur.state = ended === 'passed' ? 'passed' : 'stopped'; cur = null; pending = []; }
      } else if (l.cls === 'start') {
        if (cur) cur.state = 'stopped';
        cur = { kind: 'step', stepKind: l.stepKind, text: l.text, recorded: l.recorded, state: 'running', startTs: l.ts, endTs: null, tools: [], note: null };
        items.push(cur);
        pending = [];
      } else if (l.cls === 'llm') {
        pending = l.actions.slice();
      } else if (l.cls === 'tool') {
        var row = { kind: 'tool', name: l.name, ok: l.ok, ms: l.ms, args: l.args, error: l.error, ts: l.ts };
        if (cur) cur.tools.push(row); else items.push(row);
        var at = pending.indexOf(l.name);
        if (at >= 0) pending.splice(at, 1);
      } else if (l.cls === 'note') {
        if (cur && l.explanation) cur.note = l.explanation;
      } else if (l.cls === 'complete') {
        var step = cur;
        // A complete with no open start (the stream joined mid-step) still says how the step went.
        if (!step) {
          step = { kind: 'step', stepKind: l.stepKind, text: l.text, recorded: l.recorded, startTs: null, tools: [], note: null };
          items.push(step);
        }
        step.state = l.passed ? 'passed' : 'failed';
        step.endTs = l.ts;
        if (!step.note && l.explanation) step.note = l.explanation;
        cur = null;
        pending = [];
      }
    });
    var now = null;
    if (cur && !ended) {
      if (pending.length) now = { label: 'Running ' + pending[0], tool: pending[0] };
      else if (cur.recorded) now = { label: 'Replaying recorded tools', tool: null };
      else now = { label: cur.tools.length ? 'AI is choosing the next action' : 'AI is reading the screen', tool: null };
    }
    return { title: title, ended: ended, items: items, now: now };
  }

  // The listed sessions worth following for a device, oldest first so the feed reads top to bottom:
  // its sessions that are still going, plus those it started within `windowMs`, the newest `max`.
  function sessionsForDevice(sessions, deviceId, nowMs, windowMs, max) {
    var picked = (sessions || []).filter(function (s) {
      if (!s || s.deviceInstanceId !== deviceId) return false;
      return s.status === 'running' || (s.timestampMs || 0) >= nowMs - windowMs;
    });
    picked.sort(function (a, b) { return (b.timestampMs || 0) - (a.timestampMs || 0); });
    return picked.slice(0, max).reverse();
  }

  // A run failure as one readable line: the tool's own reason when there is one, else a plain
  // statement that the step failed. The daemon's message wraps the reason in the step's whole record.
  function runErrorSummary(message) {
    var m = String(message || '');
    var i = m.lastIndexOf('failure: ');
    if (i >= 0) return m.slice(i + 'failure: '.length).trim();
    if (/Failed to successfully run prompt with AI/.test(m)) return 'The step failed. The activity below says why.';
    return m.replace(/^Trail run failed:\s*/, '') || 'The run failed.';
  }

  // The one-step trail a typed prompt becomes: a step for the AI to carry out, or a verify it
  // checks. Unrecorded, so the AI drives it.
  function promptYaml(kind, text) {
    var t = String(text || '').trim();
    if (!t) return '';
    return 'trail:\n  - ' + (kind === 'verify' ? 'verify' : 'step') + ': ' + JSON.stringify(t) + '\n';
  }

  // The heading for one session in the feed. A device's live session (the one its own taps and runs
  // log into) holds many runs, so it is named by when it began, not by the first run's title; a
  // title the runner made up from its class and method says nothing either.
  function sessionLabel(id, rowTitle, foldedTitle, startTs) {
    var time = startTs ? new Date(startTs).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : null;
    var live = 'Live session' + (time ? ' · started ' + time : '');
    if (/(^|_)recording_/.test(String(id || ''))) return live;
    var t = foldedTitle || rowTitle;
    if (!t || t === id || /^[A-Za-z_$][\w$]*:[A-Za-z_$][\w$]*$/.test(t)) return live;
    return t;
  }

  return {
    sessionLabel: sessionLabel,
    slimLog: slimLog,
    fold: fold,
    argsSummary: argsSummary,
    sessionsForDevice: sessionsForDevice,
    runErrorSummary: runErrorSummary,
    promptYaml: promptYaml,
  };
});

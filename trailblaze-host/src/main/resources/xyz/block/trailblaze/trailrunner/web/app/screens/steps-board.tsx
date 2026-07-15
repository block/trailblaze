// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The Steps × recordings board — a folder's blaze steps on the left (editable), one column per
// recorded <platform>.trail.yaml showing the tools it ran for each step. This is the shared working
// surface for a committed trail bundle (Trails screen): the
// two folders are structurally identical (a blaze.yaml spec + per-device recordings), so the grid is
// the same. The difference is purely the data layer — each parent passes its own dispatch/save/delete
// callbacks (the Trails screen hits /api/folder/*). The board owns only view-local state
// (which columns are shown, device pickers, hover, the record dialog, optimistic "starting…"); the
// parent owns the data (steps, the parsed variant docs) and the I/O.

// Icon + color for a recording's run status. Collapses healed → green-pass and timeout → red-fail
// (distinct from the global STATUS map). Shared by the board and the run-history rows.
const RUN_STATUS_DOT = {
  passed: ['check-circle-2', 'var(--tb-pass)'], healed: ['check-circle-2', 'var(--tb-pass)'],
  failed: ['x-circle', 'var(--tb-fail)'], timeout: ['x-circle', 'var(--tb-fail)'],
  running: ['loader-2', 'var(--tb-running)'],
};

// Parse a trail/blaze yaml into { config, prompts, trailhead, toolsItems }. The top-level doc is a
// list whose items carry a `config:`, `prompts:`, `tools:` (a recorded step the engine
// force-executes), or (new format) `trailhead:` key - the trailhead is the deterministic step 0
// the flow launches from.
function parseTrailYaml(content) {
  try {
    const doc = window.jsyaml ? window.jsyaml.load(content) : null;
    let config = {};
    let prompts = [];
    let trailhead = null;
    const toolsItems = [];
    const items = Array.isArray(doc) ? doc : doc ? [doc] : [];
    for (const it of items) {
      if (it && it.config) config = it.config;
      if (it && it.prompts) prompts = it.prompts;
      if (it && it.trailhead != null) trailhead = it.trailhead;
      if (it && Array.isArray(it.tools)) toolsItems.push(it.tools);
    }
    return { ok: true, config, prompts, trailhead, toolsItems };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e), config: {}, prompts: [], trailhead: null, toolsItems: [] };
  }
}

// Tool names from one parsed `tools:` array (entries are `{ toolName: {args} }` maps or bare strings).
function toolNamesOf(toolsArray) {
  const names = [];
  for (const t of toolsArray || []) {
    if (t && typeof t === 'object') names.push(Object.keys(t)[0]);
    else if (typeof t === 'string') names.push(t);
  }
  return names;
}

// Patch one recorded step's tool list in a variant file, PRESERVING every other top-level item —
// config AND any `- tools:` setup block the backend prepends before `- prompts:` (dropping it would
// silently break the recorded setup on replay). Re-reads the raw file (the in-memory {config,prompts}
// parse discards other items), patches in place, and re-dumps. An empty tools list REMOVES the
// `recording` block (an empty `tools:` fails ToolRecording validation). promptIndex null creates a
// recording for the given blaze step (stepInfo {text,kind}) so it aligns back to it by text.
// Returns { yaml } to save, { noop:true } when there's nothing to write, or { error }.
async function patchVariantRecording(fetchFile, id, name, promptIndex, newToolsArray, stepInfo) {
  const raw = await fetchFile(id, name);
  if (raw == null) return { error: 'Could not read ' + name };
  let items;
  try { const doc = window.jsyaml.load(raw); items = Array.isArray(doc) ? doc : doc ? [doc] : []; }
  catch (e) { return { error: 'Could not parse ' + name }; }
  const hasTools = !!(newToolsArray && newToolsArray.length);
  const pItem = items.find((it) => it && Array.isArray(it.prompts));
  if (promptIndex == null) {
    if (!hasTools) return { noop: true };
    const kindKey = (stepInfo && stepInfo.kind) === 'verify' ? 'verify' : 'step';
    const p = { [kindKey]: (stepInfo && stepInfo.text) || '', recording: { tools: newToolsArray } };
    if (pItem) pItem.prompts.push(p); else items.push({ prompts: [p] });
  } else {
    const prompt = pItem && pItem.prompts[promptIndex];
    if (!prompt) return { error: 'That step moved — reload and try again.' };
    if (hasTools) prompt.recording = { ...(prompt.recording || {}), tools: newToolsArray };
    else if (prompt.recording) delete prompt.recording;
  }
  let yaml;
  try { yaml = window.jsyaml.dump(items, { lineWidth: -1, noRefs: true }).trimEnd(); }
  catch (e) { return { error: 'Could not serialize the edit.' }; }
  return { yaml };
}

// One prompt list item -> { kind, text, recording }.
function promptEntry(p) {
  if (!p || typeof p !== 'object') return null;
  const kind = 'verify' in p ? 'verify' : 'step' in p ? 'step' : Object.keys(p).find((k) => k !== 'recording');
  if (!kind) return null;
  return { kind, text: String(p[kind]), recording: p.recording };
}

const SELECTOR_SKIP = new Set(['reason']);

// How many tool calls a step entry / a whole parsed file recorded.
function stepToolCount(entry) {
  return ((entry && entry.recording && entry.recording.tools) || []).length;
}
function fileToolCount(parsed) {
  return (parsed.prompts || []).reduce((n, p) => n + (((p && p.recording && p.recording.tools) || []).length), 0);
}

// One flattened selector line. The VALUE is the signal you scan for (emphasized: bright, bold) —
// that's the concrete thing the selector matched on. The property path is structural detail
// (de-emphasized: muted, normal weight), with the leaf key slightly more present than its prefix.
function SelectorLine({ path, value }) {
  const parts = path.split('.');
  const leaf = parts.pop();
  const prefix = parts.join('.');
  return (
    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, lineHeight: 1.5, wordBreak: 'break-word' }}>
      {prefix && <span style={{ color: 'var(--text-subtle)', opacity: 0.55 }}>{prefix}.</span>}
      <span style={{ color: 'var(--text-subtle)', fontWeight: 400 }}>{leaf}</span>
      <span style={{ color: 'var(--text-subtle)', opacity: 0.55 }}>: </span>
      <span style={{ color: 'var(--text-standard)', fontWeight: 700 }}>{value}</span>
    </div>
  );
}

// Render the recording (list of tool calls) for one step in one file. Empty is handled by the caller.
// Display-only — editing happens by clicking the whole cell (opens ToolCallsPopover in StepsBoard).
function RecordingCell({ recording }) {
  const tools = (recording && recording.tools) || [];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {tools.map((t, i) => {
        const name = Object.keys(t || {})[0] || 'tool';
        const body = (t && t[name]) || {};
        const parts = typeof body === 'object' ? flattenObject(body, { joinArray: JSON.stringify, skip: SELECTOR_SKIP }) : [{ path: 'value', value: String(body) }];
        return (
          <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <span className="tb-chip blue" style={{ alignSelf: 'flex-start', fontFamily: 'var(--font-mono)', fontSize: 10.5 }}>{name}</span>
            {/* the agent's rationale — italic prose, set apart from the structured params below */}
            {body && body.reason && <span className="tb-sub" title={body.reason} style={{ fontSize: 11, fontStyle: 'italic', lineHeight: 1.45, color: 'var(--text-subtle-variant)', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{body.reason}</span>}
            {/* parameters indented under a connector so the keys read as this tool's structure */}
            {parts.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 3, paddingLeft: 10, borderLeft: '2px solid var(--tb-hairline)' }}>
                {parts.map((p, j) => <SelectorLine key={j} path={p.path} value={p.value} />)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// A tool's docs (id · description · parameters), rendered in the Select's hover side-panel so you can
// read what a tool does while scanning the dropdown, before picking it.
function ToolDocPanel({ tool }) {
  if (!tool) return null;
  const params = tool.parameters || [];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--tb-link)', wordBreak: 'break-word' }}>{tool.id}</span>
      {(tool.llmDescription || tool.description) && (
        <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{tool.llmDescription || tool.description}</div>
      )}
      {params.length > 0 && (
        <div>
          <div className="tb-eyebrow" style={{ marginBottom: 5 }}>Parameters · {params.length}</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {params.map((p) => (
              <div key={p.name} style={{ lineHeight: 1.4 }}>
                <span className="tb-mono" style={{ fontSize: 11, fontWeight: 700 }}>{p.name}</span>
                <span className="tb-sub" style={{ fontSize: 10.5 }}> · {p.type}{p.required ? ' · required' : ''}</span>
                {p.description ? <div className="tb-sub" style={{ fontSize: 10.5, lineHeight: 1.4 }}>{p.description}</div> : null}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Rebuild the args YAML for a freshly-picked tool: scaffold its required params (a type-appropriate
// placeholder each) and carry over any values from the previous args whose key still applies to the new
// tool. Unknown tool (not in catalog) → keep the args as typed; a tool with no required params → empty.
function scaffoldArgs(doc, prevYaml) {
  if (!doc) return prevYaml;
  let prev = {};
  if (prevYaml && prevYaml.trim()) { try { const p = window.jsyaml.load(prevYaml); if (p && typeof p === 'object' && !Array.isArray(p)) prev = p; } catch (e) { /* ignore */ } }
  const placeholder = (t) => {
    const ty = String(t || '').toLowerCase();
    if (/(number|int|long|float|double)/.test(ty)) return 0;
    if (ty.includes('bool')) return false;
    if (ty.includes('[]') || ty.includes('array') || ty.includes('list')) return [];
    if (ty.includes('object') || ty.includes('map')) return {};
    return '';
  };
  const obj = {};
  for (const p of (doc.parameters || [])) {
    if (p.required || (p.name in prev)) obj[p.name] = (p.name in prev) ? prev[p.name] : placeholder(p.type);
  }
  if (!Object.keys(obj).length) return '';
  try { return window.jsyaml.dump(obj, { lineWidth: -1, noRefs: true }).trimEnd(); } catch (e) { return ''; }
}

// Inline editor for ALL tool calls recorded at one step in one variant (the whole matrix cell), opened
// as a popover anchored to the clicked cell. Edit each call (tool + args), remove calls, add new ones,
// then write the full list back.
//   calls  : [{ name, body }] the cell's current tool calls (body = everything under the tool key)
//   tools  : the scoped catalog (editorToolsFor output) — drives autocomplete + the docs per call
//   anchor : the cell's bounding rect; the popover opens beside it, clamped/flipped to stay on screen
//   onSave(newToolsArray) : array of { [name]: body }; onClose : dismiss
// Args are edited as YAML so arbitrary nesting (selectors, regexes) round-trips without a bespoke form.
// YAML key/value highlighter for the args field: keys (fields) muted gray, values bright. Color ONLY
// (no bold) so glyph widths match the transparent textarea exactly and the caret stays aligned.
function yamlHighlight(text) {
  const out = [];
  (text || '').split('\n').forEach((line, i) => {
    if (i) out.push('\n');
    const kv = line.match(/^(\s*)(-\s+)?([^:#\s][^:]*:)(\s?)(.*)$/);
    if (kv) {
      out.push(kv[1] + (kv[2] || ''));
      out.push(<span key={'k' + i} style={{ color: 'var(--text-subtle)' }}>{kv[3]}</span>);
      out.push(kv[4] || '');
      if (kv[5]) out.push(<span key={'v' + i} style={{ color: 'var(--text-standard)' }}>{kv[5]}</span>);
    } else {
      const li = line.match(/^(\s*)(-\s+)(.*)$/);
      if (li) { out.push(li[1] + li[2]); out.push(<span key={'l' + i} style={{ color: 'var(--text-standard)' }}>{li[3]}</span>); }
      else out.push(<span key={'p' + i} style={{ color: 'var(--text-standard)' }}>{line}</span>);
    }
  });
  return out;
}

// A YAML <textarea> with live key/value highlighting: a colored <pre> sits behind a transparent-text
// textarea sharing the exact font / padding / wrapping, so editing feels native but reads as code.
function HighlightedYamlField({ value, onChange, onFocus, onBlur, placeholder }) {
  const font = { fontFamily: 'var(--font-mono)', fontSize: 12, lineHeight: 1.5 };
  const pad = '7px 9px';
  const rows = Math.min(10, Math.max(2, (value || '').split('\n').length + 1));
  // The textarea is what actually scrolls; keep the highlight <pre> behind it in lockstep, else
  // scrolling looks broken (the visible colored text wouldn't move with the caret/content).
  const preRef = React.useRef(null);
  const syncScroll = (e) => { const p = preRef.current; if (p) { p.scrollTop = e.target.scrollTop; p.scrollLeft = e.target.scrollLeft; } };
  return (
    <div style={{ position: 'relative', border: '1px solid var(--tb-hairline)', borderRadius: 7, background: 'var(--bg-standard)', overflow: 'hidden' }}>
      <pre ref={preRef} aria-hidden="true" style={{ ...font, margin: 0, padding: pad, whiteSpace: 'pre-wrap', wordBreak: 'break-word', position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden', color: 'var(--text-standard)' }}>
        {value ? yamlHighlight(value) : <span style={{ color: 'var(--text-subtle)', fontStyle: 'italic' }}>{placeholder}</span>}
      </pre>
      <textarea value={value} onChange={onChange} onScroll={syncScroll} onFocus={onFocus} onBlur={onBlur} spellCheck={false} rows={rows} placeholder={placeholder} aria-label="Tool arguments (YAML)"
        style={{ ...font, display: 'block', width: '100%', boxSizing: 'border-box', padding: pad, whiteSpace: 'pre-wrap', wordBreak: 'break-word', resize: 'vertical', overflow: 'auto', background: 'transparent', color: 'transparent', caretColor: 'var(--text-standard)', border: 'none', outline: 'none', position: 'relative', zIndex: 1 }} />
    </div>
  );
}

// `singleTool`: cap the cell at ONE tool call (a unified trailhead classifier must be exactly one
// tool map — the Kotlin parser rejects a list or a multi-key map there).
function ToolCallsPopover({ calls, tools, allTools, anchor, busy, onSave, onClose, onRunTools, runDevices = [], singleTool = false }) {
  useLucide();
  // Docs come from the FULL catalog (allTools) so an already-recorded tool that isn't in this target's
  // scoped autocomplete list (e.g. a sign-in/trailhead tool) still shows its docs. Autocomplete options
  // stay scoped to `tools`.
  const findDoc = (id) => (allTools || []).find((t) => t.id === id) || (tools || []).find((t) => t.id === id) || null;
  // Faithful display of whatever body the file carries (the model preserves scalar/list bodies for
  // losslessness) — only the canonical no-args forms (`null` / `{}`) show as blank. Blanking a scalar
  // would make Save silently replace it with `{}`.
  const bodyToYaml = (body) => {
    if (body == null) return '';
    if (typeof body === 'object' && !Array.isArray(body) && !Object.keys(body).length) return '';
    try { return window.jsyaml.dump(body, { lineWidth: -1, noRefs: true }).trimEnd(); } catch (e) { return ''; }
  };
  const [rows, setRows] = React.useState(() => (calls || []).map((c) => ({ name: c.name, argsYaml: bodyToYaml(c.body) })));
  const [err, setErr] = React.useState(null);
  const [running, setRunning] = React.useState(false);
  const [runResult, setRunResult] = React.useState(null); // { ok, text } from a live device run
  const [runDeviceId, setRunDeviceId] = React.useState(''); // chosen Play device; '' → first available
  // While a row's args are being edited, its tool docs show in a panel to the LEFT of the popover.
  const popRef = React.useRef(null);
  const [focusedRow, setFocusedRow] = React.useState(-1);
  const [popRect, setPopRect] = React.useState(null);
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  const setRow = (i, patch) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const delRow = (i) => { setRows((rs) => rs.filter((_, j) => j !== i)); setErr(null); };
  const addRow = () => { setRows((rs) => [...rs, { name: (tools && tools[0] && tools[0].id) || '', argsYaml: '' }]); setErr(null); };
  // Parse the edited rows into the [{ [toolName]: body }] wire shape, or null (with an error set) if
  // any row is malformed. Shared by Save and the live Play run.
  const buildOut = () => {
    const out = [];
    for (const r of rows) {
      if (!r.name) { setErr('Every tool call needs a tool selected.'); return null; }
      let body = {};
      if (r.argsYaml.trim()) {
        try { body = window.jsyaml.load(r.argsYaml); }
        catch (e) { setErr(`“${r.name}” args aren’t valid YAML: ${e && e.message ? e.message : e}`); return null; }
        if (body == null || typeof body !== 'object' || Array.isArray(body)) { setErr(`“${r.name}” args must be key: value pairs.`); return null; }
      }
      out.push({ [r.name]: body });
    }
    if (singleTool && out.length > 1) {
      setErr('A trailhead is exactly one tool per platform — compose multiple actions inside that tool.');
      return null;
    }
    return out;
  };
  const save = () => { const out = buildOut(); if (out) onSave(out); };
  // The Play device: the chosen one if still connected, else the first available.
  const runDeviceEff = (runDevices.find((d) => d.id === runDeviceId) ? runDeviceId : (runDevices[0] && runDevices[0].id)) || '';
  // Play — dispatch the current (possibly unsaved) tool calls to the SELECTED device, like Run YAML.
  const run = async () => {
    if (!onRunTools || running || !runDevices.length) return;
    setErr(null); setRunResult(null);
    const out = buildOut();
    if (!out || !out.length) { if (out && !out.length) setErr('Add a tool call to run.'); return; }
    setRunning(true);
    const r = await onRunTools(out, runDeviceEff || undefined);
    setRunResult(r || { ok: false, text: 'No result returned.' });
    setRunning(false);
  };
  // Anchor beside the cell, clamped/flipped so the popover always stays fully on screen — open upward
  // when there's more room above than below (the fix for a bottom-row cell pushing it off the page).
  const W = 440; const M = 12;
  const left = Math.max(M, Math.min((anchor && anchor.left) || M, window.innerWidth - W - M));
  const below = window.innerHeight - ((anchor && anchor.bottom) || 0);
  const above = (anchor && anchor.top) || 0;
  const up = below < 360 && above > below;
  const maxH = Math.min(580, Math.max(220, (up ? above : below) - M - 6));
  const vpos = up ? { bottom: window.innerHeight - (anchor ? anchor.top : 0) + 6 } : { top: (anchor ? anchor.bottom : 0) + 6 };
  return (
    <React.Fragment>
      <div style={{ position: 'fixed', inset: 0, zIndex: 80 }} onClick={onClose} />
      <div ref={popRef} className="tb-card" onClick={(e) => e.stopPropagation()}
        style={{ position: 'fixed', left, ...vpos, width: W, maxHeight: maxH, display: 'flex', flexDirection: 'column', zIndex: 81, padding: 0, background: 'var(--bg-elevated)', boxShadow: '0 18px 50px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '11px 13px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
          <Ico n="wrench" s={14} c="var(--tb-link)" />
          <span style={{ fontSize: 13, fontWeight: 700 }}>Edit tool calls</span>
          <span className="tb-sub" style={{ fontSize: 11 }}>{rows.length} {rows.length === 1 ? 'call' : 'calls'}</span>
          <span style={{ flex: 1 }} />
          <span role="button" onClick={onClose} title="Close (Esc)" style={{ cursor: 'pointer', color: 'var(--text-subtle)' }}><Ico n="x" s={16} /></span>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '12px 13px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {rows.length === 0 && <span className="tb-sub" style={{ fontSize: 12 }}>No tool calls recorded here. Add one below.</span>}
          {rows.map((r, i) => {
            const doc = findDoc(r.name);
            return (
              <div key={i} style={{ border: '1px solid var(--tb-hairline)', borderRadius: 9, padding: '10px 11px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="tb-sub" style={{ fontSize: 10.5, fontWeight: 700, flex: '0 0 auto' }}>{i + 1}</span>
                  <span style={{ flex: 1, minWidth: 0 }}>
                    <Select full searchable title="Tool" value={r.name}
                      onChange={(e) => { const nm = e.target.value; setRow(i, { name: nm, argsYaml: scaffoldArgs((tools || []).find((x) => x.id === nm), r.argsYaml) }); setErr(null); }}
                      options={(tools || []).map((t) => ({ value: t.id, label: t.id, desc: t.description || '' }))}
                      hoverPanel={(id) => { const t = findDoc(id); return t ? <ToolDocPanel tool={t} /> : null; }} />
                  </span>
                  <span role="button" onClick={() => delRow(i)} title="Remove this tool call" style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex' }}><Ico n="trash-2" s={14} /></span>
                </div>
                {!doc && r.name && <span className="tb-sub" style={{ fontSize: 10.5 }}>Not in this target’s catalog — saved as typed.</span>}
                <HighlightedYamlField value={r.argsYaml}
                  onChange={(e) => { setRow(i, { argsYaml: e.target.value }); setErr(null); }}
                  onFocus={() => { setFocusedRow(i); if (popRef.current) setPopRect(popRef.current.getBoundingClientRect()); }}
                  onBlur={() => setFocusedRow((f) => (f === i ? -1 : f))}
                  placeholder="args (YAML), e.g.  index: 0" />
              </div>
            );
          })}
          {!(singleTool && rows.length >= 1) && (
            <span role="button" onClick={addRow} style={{ alignSelf: 'flex-start', color: 'var(--tb-pass)', fontSize: 12.5, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Ico n="plus" s={14} /> Add tool call
            </span>
          )}
          {err && <span style={{ fontSize: 11.5, color: 'var(--tb-fail)' }}>{err}</span>}
        </div>
        {onRunTools && (
          <div style={{ flex: '0 0 auto', padding: '9px 13px', borderTop: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 8 }}>
            {runDevices.length > 0
              ? <Select value={runDeviceEff} onChange={(e) => setRunDeviceId(e.target.value)} title="Device to run on"
                  options={runDevices.map((d) => [d.id, d.name])} style={{ maxWidth: 220 }} />
              : <span className="tb-sub" style={{ fontSize: 12 }}>No device connected</span>}
            <Btn sm kind="ghost" ico={running ? 'loader-2' : 'play'} spin={running} disabled={running || busy || !runDevices.length} onClick={run}
              title={runDevices.length ? 'Run these tool calls on the selected device' : 'Connect a device to run'}>Play</Btn>
            {runResult && (
              <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: runResult.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)', minWidth: 0, maxWidth: 180, overflow: 'hidden' }} title={runResult.text}>
                <Ico n={runResult.ok ? 'check' : 'x'} s={12} /> <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{runResult.text}</span>
              </span>
            )}
          </div>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 13px', borderTop: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
          <span style={{ flex: 1 }} />
          <Btn sm kind="ghost" onClick={onClose}>Cancel</Btn>
          <Btn sm kind="primary" ico={busy ? 'loader-2' : 'save'} spin={busy} disabled={busy} onClick={save}>Save tool calls</Btn>
        </div>
      </div>
      {/* Docs for the row being edited — shown to the LEFT of the popover, aligned to it, read-only.
          Explains the tool + its parameters while you type the args. */}
      {focusedRow >= 0 && rows[focusedRow] && popRect && (() => {
        const d = findDoc(rows[focusedRow].name);
        if (!d) return null;
        const DW = 320, gp = 10, m = 10;
        const lf = Math.max(m, popRect.left - gp - DW);
        return (
          <div className="tb-card" onMouseDown={(e) => e.preventDefault()} style={{ position: 'fixed', left: lf, top: popRect.top, width: DW, maxHeight: popRect.height, overflow: 'auto', zIndex: 81, padding: '12px 14px', background: 'var(--bg-elevated)', boxShadow: '0 18px 50px rgba(0,0,0,.55)' }}>
            <ToolDocPanel tool={d} />
          </div>
        );
      })()}
    </React.Fragment>
  );
}

// Tools for an editor palette given a target + platform: the target app's own tools plus the
// generalized ones (framework core + the platform's toolsets). Shared by the trails
// editors so both browse the same set — and the same trailmap scope the Tools/Trailmaps list views
// use (TB.scopeTrailmaps), so the editor palette and the catalog never drift.
function editorToolsFor(catalog, target, platform) {
  const scope = TB.scopeTrailmaps(target, platform);
  return (catalog || [])
    .filter((t) => t.id && scope.has(t.trailmap))
    .slice()
    .sort((a, b) => a.id.localeCompare(b.id));
}

// The per-platform config delta a recorded column carries (shown verbatim); non-recorded columns
// show the platform default (what will be written before recording).
const DEFAULT_DRIVER = { android: 'ANDROID_ONDEVICE_ACCESSIBILITY', ios: 'IOS_HOST', web: 'PLAYWRIGHT_NATIVE' };
const PLATFORM_LABEL = { android: 'Android', ios: 'iOS', web: 'Web' };

// The shared Steps × recordings board. See the file header for the parent/board responsibility split.
//
//   steps / onStepsChange : the blaze steps, controlled by the parent ([{kind:'do'|'verify', text}])
//   dirty / savingSteps? / onSaveSteps(steps) : Save-steps button state + writer (returns {success,error})
//   variants : [{ name, platform }] recorded files; variantDocs : { name -> parseTrailYaml(...) }
//   blazeConfigRows : flattenObject rows for the blaze's config (left config cell)
//   deviceList : connected devices; linkedSessions : sessions scoped to THIS folder (status per column)
//   home : folder path (CLI hints); blazeName : left-column file label; target : record-dialog default
//   canRecord : whether record/edit-steps are enabled (a folder needs a blaze.yaml to record from)
//   onOpenFile(name, highlight?) · dispatchRecord(devices, opts)→{ok,error} · dispatchPlay(col)→{ok,error}
//   onConfigureRun(col) (optional): when provided, the Run button opens the "Configure run" prompt
//     (seeded with the variant's file, device, and replay mode) instead of dispatching directly.
//   onDeleteFile(name)→{ok,error} · onViewRun(sess) · onError(msg|null) · boardRef
//   onSaveVariantTools(variantName, promptIndex, newToolsArray)→{success,error} (optional): when
//     provided, clicking a recorded cell opens ToolCallsPopover to edit that step's whole tool-call
//     list (the popover writes the replacement array back). Omit it to keep recordings read-only.
//   onSaveVariantYaml(name, yaml)→{success,error} (optional): full-file writer for the expanded
//     variant's inline raw-YAML editor (replaces the old slide-in push view).
function StepsBoard({
  steps, onStepsChange, dirty, onSaveSteps,
  variants = [], variantDocs = {}, blazeConfigRows = [], blazeTrailhead = null, blazeToolsSteps = [],
  deviceList = [], linkedSessions = [],
  home, blazeName = 'blaze.yaml', target, canRecord = true,
  onOpenFile, dispatchRecord, dispatchPlay, onConfigureRun, onDeleteFile, onViewRun, onError, boardRef, onSaveVariantTools, onSaveVariantYaml, onFetchVariantYaml,
}) {
  useLucide();
  const toolCatalog = TB.useTools(); // catalog (id/description/llmDescription/parameters) for the tool editor
  const [editCell, setEditCell] = React.useState(null); // { variantName, platform, promptIndex, calls, anchor }
  const [savingCell, setSavingCell] = React.useState(false);
  // Expand-to-fill: focus one variant column over the board (blaze steps stay as context on the left),
  // with a Steps/Raw-YAML toggle. `expandShown` drives the enter/exit animation (mount → rAF → shown).
  const [expanded, setExpanded] = React.useState(null); // platform of the focused variant, or null
  const [expandedRaw, setExpandedRaw] = React.useState(false); // false = recording view, true = raw YAML
  const [expandShown, setExpandShown] = React.useState(false);
  const rawDirtyRef = React.useRef(false); // set by the expanded Raw-YAML editor; guards discarding unsaved edits
  const guardRaw = () => !(expandedRaw && rawDirtyRef.current) || window.confirm('Discard unsaved YAML changes?');
  const setRawMode = (raw) => { if (raw || guardRaw()) { rawDirtyRef.current = false; setExpandedRaw(raw); } };
  const openExpanded = (platform, raw) => { rawDirtyRef.current = false; setExpanded(platform); setExpandedRaw(!!raw); requestAnimationFrame(() => setExpandShown(true)); };
  // Raw mode fades its overlay out (200ms); Steps mode just re-widens the grid (animated by its own
  // grid-template-columns transition), so collapse there is immediate.
  const collapseExpanded = () => { if (!guardRaw()) return; rawDirtyRef.current = false; setExpandShown(false); window.setTimeout(() => { setExpanded(null); setExpandedRaw(false); }, expandedRaw ? 200 : 0); };
  React.useEffect(() => {
    if (!expanded) return;
    const onKey = (e) => { if (e.key === 'Escape') collapseExpanded(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [expanded, expandedRaw]);
  const [extraCols, setExtraCols] = React.useState(() => new Set()); // platforms added via "+ Add Trail"
  const [addColOpen, setAddColOpen] = React.useState(false);
  const [colDeviceSel, setColDeviceSel] = React.useState({}); // platform -> chosen device id (when several share a platform)
  const [openDevDrop, setOpenDevDrop] = React.useState(null);
  const [hoveredStep, setHoveredStep] = React.useState(-1);
  const [dragStep, setDragStep] = React.useState(-1); // index being dragged; -1 = none
  const [dropStep, setDropStep] = React.useState(-1); // index the drag is hovering over (drop indicator)
  const [recDialogOpen, setRecDialogOpen] = React.useState(false);
  const [recDevices, setRecDevices] = React.useState(null); // Set of device ids selected to record; null = all column devices
  const [startingPlatforms, setStartingPlatforms] = React.useState(() => new Set()); // optimistic "starting…"
  // A run session is 'running' whether it's a re-record or a playback (Run); the session itself doesn't
  // say which. Track playback intent locally so the status reads "running" (not "recording") for a Run.
  const [playingPlatforms, setPlayingPlatforms] = React.useState(() => new Set());
  const prevRunningRef = React.useRef(new Set());
  const [savingSteps, setSavingSteps] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const trailmaps = TB.useTrailmaps(); // trailhead options for the per-platform trailhead picker
  // Per-platform trailhead choice (the deterministic step 0 baked into THAT platform's recording).
  // Keyed by platform — a trailhead's launch/sign-in tool is platform-specific, so it lives on the
  // recording, never on the cross-platform blaze. Feeds the record dialog + is prepended at record time.
  const [colTrailheads, setColTrailheads] = React.useState({}); // { android: 'sample_android_signedInFresh', … }
  const setErr = onError || (() => {});

  const sessForPlatform = (p) => linkedSessions
    .filter((s) => (s.platform || '').toLowerCase() === (p || '').toLowerCase())
    .sort((a, b) => (b.timestampMs || 0) - (a.timestampMs || 0))[0];

  const stepKeyOf = (s) => (s.kind === 'verify' ? 'verify' : 'step') + '::' + (s.text || '').trim().toLowerCase();

  // Drop the optimistic "starting…" flag once a platform's real running session shows up, and clear its
  // playback flag when that run stops (running→done transition, tracked via prevRunningRef).
  React.useEffect(() => {
    const runningPlats = new Set(linkedSessions.filter((s) => s.status === 'running').map((s) => (s.platform || '').toLowerCase()));
    if (startingPlatforms.size) {
      let changed = false; const n = new Set(startingPlatforms);
      startingPlatforms.forEach((p) => { if (runningPlats.has(p)) { n.delete(p); changed = true; } });
      if (changed) setStartingPlatforms(n);
    }
    setPlayingPlatforms((s) => {
      if (!s.size) return s;
      let changed = false; const n = new Set(s);
      s.forEach((p) => { if (prevRunningRef.current.has(p) && !runningPlats.has(p)) { n.delete(p); changed = true; } });
      return changed ? n : s;
    });
    prevRunningRef.current = runningPlats;
  }, [linkedSessions]);

  const markStarting = (plats) => setStartingPlatforms((s) => { const n = new Set(s); plats.forEach((p) => p && n.add(p)); return n; });
  const clearStarting = (plats) => setStartingPlatforms((s) => { const n = new Set(s); plats.forEach((p) => n.delete(p)); return n; });

  // ── editable steps (the parent owns the array; the board mutates via onStepsChange) ──
  const setStep = (i, patch) => onStepsChange(steps.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  const delStep = (i) => onStepsChange(steps.filter((_, j) => j !== i));
  const addStep = () => onStepsChange([...(steps || []), { kind: 'do', text: '' }]);
  const moveStep = (i, dir) => {
    const j = i + dir;
    if (j < 0 || j >= steps.length) return;
    const n = [...steps];
    [n[i], n[j]] = [n[j], n[i]];
    onStepsChange(n);
  };
  // Drag-to-reorder: pull step `from` out and re-insert at `to`. The recording columns map over this
  // same `steps` array (aligned per index), so they follow the new order automatically.
  const reorderStep = (from, to) => {
    if (from == null || to == null || from === to) return;
    const n = [...steps];
    const [moved] = n.splice(from, 1);
    // Insert at `to` (the original hovered index): dragging DOWN lands the step after the hovered row
    // (the element there shifted up by the removal), dragging UP lands it before — so every position,
    // including the last row, is reachable.
    n.splice(to, 0, moved);
    onStepsChange(n);
  };
  async function saveSteps() {
    setSavingSteps(true); setErr(null);
    const r = await onSaveSteps(steps);
    setSavingSteps(false);
    if (r && !r.success) setErr(r.error || 'Could not save steps.');
  }

  // ── board model: align each variant's recorded tools to the working steps ──
  // Group connected devices by platform — a platform can have several (e.g. two iOS sims), so the
  // column lets you pick which one to record on (see col.devices + the footer dropdown).
  const devsByPlatform = {};
  deviceList.forEach((dev) => { const p = (dev.platform || '').toLowerCase(); if (p) (devsByPlatform[p] = devsByPlatform[p] || []).push(dev); });
  // Which platform columns are SHOWN — driven explicitly by the files + the user's choices, NOT by
  // what's connected. A column shows when it has a recorded file (a variant) or it was added via
  // "+ Add Trail" (extraCols). Connected devices do NOT auto-create columns; sessions only attach
  // status below. So delete = gone, "+ Add Trail" = back.
  const colByPlatform = {};
  variants.forEach((v) => {
    const p = (v.platform || '').toLowerCase();
    if (p) colByPlatform[p] = { platform: p, variant: v, recorded: true };
  });
  extraCols.forEach((p) => {
    if (p && !colByPlatform[p]) colByPlatform[p] = { platform: p, variant: null, recorded: false };
  });
  Object.keys(colByPlatform).forEach((p) => { colByPlatform[p].sess = sessForPlatform(p); });
  // "+ Add Trail" menu — platforms not already shown. Prefer concrete connected devices; fall back to
  // a generic platform entry (e.g. Web, or a platform with nothing connected yet).
  // The platforms we support, minus any that already have a column. We list PLATFORMS here (not the
  // connected devices) — picking one creates that platform's trail column; the device is chosen later
  // in the Drive row when recording.
  const addMenu = [];
  ['android', 'ios', 'web'].forEach((p) => {
    if (colByPlatform[p]) return;
    addMenu.push({ key: 'plat-' + p, platform: p, label: PLATFORM_LABEL[p], sub: p });
  });
  const columns = Object.values(colByPlatform)
    .sort((a, b) => a.platform.localeCompare(b.platform))
    .map((c) => {
      const parsed = c.variant ? variantDocs[c.variant.name] : null;
      // Keep each recording entry's ORIGINAL index in the variant's prompts array (promptIndex) so an
      // inline tool edit can be written back to the right prompt — the filter below would otherwise lose it.
      const recEntries = parsed ? (parsed.prompts || []).map((p, idx) => { const pe = promptEntry(p); return pe ? { ...pe, promptIndex: idx } : null; }).filter(Boolean) : [];
      // Align recordings to the blaze steps: exact text match first, then fall back to POSITION (the
      // i-th unmatched blaze step takes the next unconsumed recording). So a recording whose wording
      // drifted from the blaze (a committed bundle's common case) still lines up instead of rendering
      // as an empty "—" cell. Recordings that align to no step become `extraCount` — surfaced on the
      // column header so they're discoverable, not silently hidden.
      const byText = {};
      recEntries.forEach((e, idx) => { const k = e.kind + '::' + e.text.trim().toLowerCase(); if (!(k in byText)) byText[k] = idx; });
      const consumed = new Set();
      const aligned = (steps || []).map((s) => {
        const k = stepKeyOf(s);
        if (k in byText && !consumed.has(byText[k])) { consumed.add(byText[k]); return { entry: recEntries[byText[k]], exact: true }; }
        return null;
      });
      let ri = 0;
      for (let i = 0; i < aligned.length; i++) {
        if (aligned[i]) continue;
        while (ri < recEntries.length && consumed.has(ri)) ri++;
        if (ri < recEntries.length) { aligned[i] = { entry: recEntries[ri], exact: false }; consumed.add(ri); ri++; }
      }
      const extraCount = recEntries.length - consumed.size;
      // Last step this device recorded — for a failed run, execution stopped here, so everything after
      // is "not reached" rather than a meaningless empty cell.
      let lastIdx = -1;
      aligned.forEach((a, i) => { if (a) lastIdx = i; });
      const devices = devsByPlatform[c.platform] || [];
      const device = devices.find((dv) => dv.id === colDeviceSel[c.platform]) || devices[0] || null;
      return { ...c, devices, device, name: c.variant ? c.variant.name : `${c.platform}.trail.yaml`, parsed, aligned, extraCount, lastIdx, trailheadOpts: TB.trailheadsForPlatform(trailmaps.data, target, c.platform) };
    });
  // Expanded Steps mode reuses the SAME matrix — it just hides the other variant columns and lets the
  // focused one fill the width (steps stay lined up, cells stay click-to-edit). Raw mode is a separate
  // inline editor (below), so the grid hides entirely then.
  const expandedSteps = expanded && !expandedRaw;
  const gridColumns = expandedSteps ? columns.filter((c) => c.platform === expanded) : columns;
  const boardCols = expandedSteps
    ? 'minmax(280px, 1fr) minmax(480px, 2.2fr)'
    : `minmax(280px, 1fr) repeat(${gridColumns.length || 1}, minmax(300px, 1fr))`;

  // The focused (expanded) variant + its raw YAML (reserialized from the parsed doc once per change, so
  // the inline editor doesn't reset on every parent re-render). A column that disappears collapses.
  const expCol = expanded ? columns.find((c) => c.platform === expanded) : null;
  // The Raw-YAML editor edits the WHOLE file, so it must load the actual file text (a reserialization
  // of {config,prompts} would drop any top-level setup `- tools:` block). Fetch it when raw mode opens;
  // fall back to a reserialization only until it arrives / if no fetcher is wired.
  const [rawYaml, setRawYaml] = React.useState(null);
  React.useEffect(() => {
    if (!(expanded && expandedRaw && expCol && onFetchVariantYaml)) { setRawYaml(null); return; }
    let cancelled = false;
    Promise.resolve(onFetchVariantYaml(expCol.name)).then((t) => { if (!cancelled) setRawYaml(t == null ? '' : t); });
    return () => { cancelled = true; };
  }, [expanded, expandedRaw, expCol && expCol.name, onFetchVariantYaml]);
  const expYaml = React.useMemo(() => {
    if (rawYaml != null) return rawYaml;
    if (!expCol || !expCol.parsed) return '';
    try { return window.jsyaml.dump([{ config: expCol.parsed.config || {} }, { prompts: expCol.parsed.prompts || [] }], { lineWidth: -1, noRefs: true }).trimEnd(); } catch (e) { return ''; }
  }, [rawYaml, expanded, expCol && expCol.name, expCol && expCol.parsed]);
  React.useEffect(() => { if (expanded && !expCol) collapseExpanded(); }, [expanded, expCol]);

  const colDeviceIds = columns.filter((c) => c.device).map((c) => c.device.id);
  const recSel = recDevices || new Set(colDeviceIds);
  const recChosen = columns.filter((c) => c.device && recSel.has(c.device.id)).map((c) => c.device);

  // ── record / play / delete (optimistic state here; the actual I/O is the parent's primitive) ──
  async function runRecord(chosen, options) {
    if (!chosen || chosen.length === 0) { setErr('Select at least one device to record on.'); return; }
    const plats = chosen.map((dev) => (dev.platform || '').toLowerCase());
    setBusy(true); setErr(null); markStarting(plats);
    setPlayingPlatforms((s) => { const n = new Set(s); plats.forEach((p) => n.delete(p)); return n; }); // this is a record, not a play
    setTimeout(() => clearStarting(plats), 12000); // safety: don't get stuck "starting" if the session never lands
    const r = await dispatchRecord(chosen, options);
    setBusy(false);
    if (!r || !r.ok) { clearStarting(plats); setErr((r && r.error) || 'Could not start recording.'); return; }
    if (r.error) setErr('Some devices did not start recording: ' + r.error); // partial failure
  }
  async function playVariant(col) {
    if (!col.device || !col.recorded) return;
    const plats = [col.platform];
    setBusy(true); setErr(null); markStarting(plats);
    setPlayingPlatforms((s) => new Set(s).add(col.platform)); // a Run, not a record — labels the status "running"
    setTimeout(() => clearStarting(plats), 12000);
    const r = await dispatchPlay(col);
    setBusy(false);
    if (!r || !r.ok) { clearStarting(plats); setPlayingPlatforms((s) => { const n = new Set(s); n.delete(col.platform); return n; }); setErr((r && r.error) || 'Could not start playback.'); }
  }
  // Delete a column from the top: a recorded variant deletes its file; an added-but-unrecorded
  // column (e.g. a "+ Add Trail" web column) just stops being shown.
  async function removeColumn(col) {
    if (col.recorded) {
      if (!window.confirm(`Delete ${col.name}? This removes the recorded trail file.`)) return;
      setErr(null);
      const r = await onDeleteFile(col.name);
      if (!r || !r.ok) { setErr((r && r.error) || 'Could not delete the recording.'); return; }
      setExtraCols((s) => { const n = new Set(s); n.delete(col.platform); return n; });
    } else {
      setExtraCols((s) => { const n = new Set(s); n.delete(col.platform); return n; });
    }
  }

  return (
    <div ref={boardRef} style={{ marginBottom: 24, scrollMarginTop: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 11, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-standard)' }}>Steps &amp; recordings</span>
        <span className="tb-sub" style={{ fontSize: 12 }}>the blaze on the left · one column per device</span>
        <HelpDot ico="table-2" color="var(--tb-link)" title="Reading the board">
          Each row is a step in the blaze; each column is a device. A cell shows what that device recorded for that step:
          <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 5 }}>
            <span><span className="tb-chip blue" style={{ fontSize: 10 }}>tool</span> the action(s) it ran</span>
            <span style={{ color: 'var(--tb-fail)' }}>✗ failed here: the run stopped at this step</span>
            <span style={{ color: 'var(--text-subtle)', fontStyle: 'italic' }}>not reached: the run had already stopped above</span>
            <span style={{ color: 'var(--tb-amber)' }}>no tools here: ran the step but recorded nothing</span>
            <span style={{ color: 'var(--text-subtle-variant)' }}>—  no recording for this step</span>
          </div>
        </HelpDot>
        <span style={{ flex: 1 }} />
        {dirty ? <Btn sm kind="primary" ico={savingSteps ? 'loader-2' : 'save'} spin={savingSteps} onClick={saveSteps}>Save steps</Btn> : null}
        {/* "+ Add Trail" — add a column for a platform that isn't shown yet (a connected device, or a
            generic platform like Web). Adds to extraCols; delete removes it again. Hidden when the board
            has no columns at all — the empty-state cell shows the same picker inline there. */}
        {columns.length > 0 && (
        <span style={{ position: 'relative', display: 'inline-flex' }}>
          <Btn sm kind="ghost" ico="plus" onClick={() => setAddColOpen((v) => !v)} disabled={addMenu.length === 0}
            title={addMenu.length === 0 ? 'Every platform already has a column' : 'Add a platform'}>Add Platform</Btn>
          {addColOpen && (
            <React.Fragment>
              <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={() => setAddColOpen(false)} />
              <div className="tb-card" style={{ position: 'absolute', top: 'calc(100% + 6px)', right: 0, zIndex: 71, minWidth: 240, padding: 6, background: 'var(--bg-elevated)', boxShadow: '0 14px 40px rgba(0,0,0,.5)' }}>
                <div className="tb-sub" style={{ fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.04em', padding: '6px 8px 4px' }}>Add a platform</div>
                {addMenu.map((m) => (
                  <div key={m.key} role="button" className="tb-menu-item" onClick={() => { setExtraCols((s) => new Set(s).add(m.platform)); setAddColOpen(false); }}
                    style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 9px', borderRadius: 7, cursor: 'pointer', fontSize: 13, color: 'var(--text-standard)' }}>
                    <PlatformGlyph platform={m.platform} s={15} c="var(--text-subtle-variant)" /> {m.label}
                  </div>
                ))}
              </div>
            </React.Fragment>
          )}
        </span>
        )}
      </div>

      <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, overflow: 'hidden', background: 'var(--bg-subtle)', position: 'relative' }}>
        <div className="tb-board-scroll"
          onScroll={(e) => { e.currentTarget.dataset.scrolled = e.currentTarget.scrollLeft > 0 ? 'true' : 'false'; }}
          style={{ overflowX: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: boardCols, minWidth: expandedSteps ? '100%' : 'min-content', transition: 'grid-template-columns .25s ease' }}>
            {/* header row — every column is a single centered row (icon + filename + actions) so the
                filenames line up across columns. */}
            <div className="tb-board-pin" style={{ padding: '10px 12px', borderBottom: '1px solid var(--tb-hairline-strong)', display: 'flex', alignItems: 'center', gap: 6, background: 'var(--bg-subtle)' }}>
              <Ico n="file-text" s={13} c="var(--tb-link)" style={{ flex: '0 0 auto' }} />
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--tb-link)', flex: '0 0 auto' }}>{blazeName}</span>
              <span className="tb-sub" style={{ fontSize: 10.5, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>· {(steps || []).length + blazeToolsSteps.length} {(steps || []).length + blazeToolsSteps.length === 1 ? 'step' : 'steps'}</span>
              <span {...clickable(() => onOpenFile(blazeName))} title={'Edit ' + blazeName} style={{ fontSize: 11, color: 'var(--tb-link)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3, flex: '0 0 auto' }}><Ico n="pencil" s={11} /> Edit YAML</span>
            </div>
            {/* empty state — no recorded columns yet. Show the platform picker inline (the same list
                the "+ Add Trail" dropdown carries) right here so it's the obvious next action: pick a
                platform → its column appears → record it → that writes the new <platform>.trail.yaml. */}
            {gridColumns.length === 0 && (
              <div style={{ gridColumn: 2, gridRow: '1 / span 99', borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '16px 14px' }}>
                {/* heading + buttons left-align to each other (icons line up), centered as a group */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 4 }}>No platforms · add one to start</span>
                  {addMenu.length === 0 ? (
                    <span className="tb-sub" style={{ fontSize: 11.5 }}>Every platform already has a trail.</span>
                  ) : addMenu.map((m) => (
                    <div key={m.key} role="button" className="tb-menu-item" onClick={() => setExtraCols((s) => new Set(s).add(m.platform))}
                      style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '9px 10px', borderRadius: 7, cursor: 'pointer', fontSize: 13.5, color: 'var(--text-standard)' }}>
                      <PlatformGlyph platform={m.platform} s={16} c="var(--text-subtle-variant)" /> {m.label}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {gridColumns.map((col) => {
              const st = col.sess ? col.sess.status : null;
              const dot = RUN_STATUS_DOT[st];
              const running = st === 'running' || startingPlatforms.has(col.platform);
              return (
                <div key={col.platform} style={{ padding: '10px 12px', borderBottom: '1px solid var(--tb-hairline-strong)', borderLeft: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 6, background: 'var(--bg-subtle)' }}>
                  <Ico n={dot ? dot[0] : 'circle-play'} s={13} c={dot ? dot[1] : 'var(--text-subtle)'} spin={running} style={{ flex: '0 0 auto' }} />
                  <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--text-standard)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{col.name}</span>
                  {expanded === col.platform ? (
                    <React.Fragment>
                      {/* focused: Steps / Raw-YAML toggle + collapse, in place of the per-column actions */}
                      <span style={{ display: 'inline-flex', border: '1px solid var(--tb-hairline-strong)', borderRadius: 7, overflow: 'hidden', flex: '0 0 auto' }}>
                        {[['Steps', false], ['Raw YAML', true]].map(([lbl, raw]) => (
                          <span key={lbl} role="button" onClick={() => setRawMode(raw)}
                            style={{ padding: '3px 9px', fontSize: 11, fontWeight: 600, cursor: 'pointer', background: expandedRaw === raw ? 'var(--bg-extra-prominent)' : 'transparent', color: expandedRaw === raw ? 'var(--text-standard)' : 'var(--text-subtle)' }}>{lbl}</span>
                        ))}
                      </span>
                      <span {...clickable(collapseExpanded)} aria-label="Collapse" title="Collapse (Esc)" style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex' }}><Ico n="minimize-2" s={13} /></span>
                    </React.Fragment>
                  ) : (
                    <React.Fragment>
                      {col.extraCount > 0 && (
                        <span {...clickable(() => openExpanded(col.platform, false))} title={`${col.extraCount} recorded step${col.extraCount === 1 ? '' : 's'} don’t match a blaze step — expand ${col.name} to view`}
                          style={{ fontSize: 10.5, color: 'var(--tb-amber)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3, flex: '0 0 auto' }}>
                          <Ico n="alert-triangle" s={11} c="var(--tb-amber)" /> {col.extraCount} off-blaze
                        </span>
                      )}
                      {col.recorded && <span {...clickable(() => openExpanded(col.platform, false))} aria-label={'Expand ' + col.name} title={'Expand ' + col.name} style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex' }}><Ico n="maximize-2" s={12} /></span>}
                      {col.recorded && <span {...clickable(() => openExpanded(col.platform, true))} title={'Edit ' + col.name + ' YAML'} style={{ fontSize: 11, color: 'var(--tb-link)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3, flex: '0 0 auto' }}><Ico n="pencil" s={11} /> Edit YAML</span>}
                      {(col.recorded || extraCols.has(col.platform)) && !running && <span {...clickable(() => removeColumn(col))} aria-label={col.recorded ? 'Delete ' + col.name : 'Remove this column'} title={col.recorded ? 'Delete ' + col.name : 'Remove this column'} style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex' }}><Ico n="trash-2" s={12} /></span>}
                    </React.Fragment>
                  )}
                </div>
              );
            })}

            {/* DRIVE — the device + Run / Re-record controls, the FIRST row under the filenames so you
                pick a device and act before reading config or recordings. */}
            <div className="tb-board-pin" style={{ borderTop: '1px solid var(--tb-hairline)', padding: '11px 12px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: '.05em', opacity: 0.7 }}>Drive</span>
              <span className="tb-sub" style={{ fontSize: 11.5 }}>Run or rerecord on device</span>
            </div>
            {gridColumns.map((col) => {
              const dev = col.device;
              const st = col.sess ? col.sess.status : null;
              const tc = fileToolCount(col.parsed || { prompts: [] });
              const realRunning = st === 'running';
              const starting = startingPlatforms.has(col.platform) && !realRunning; // optimistic, pre-poll
              const running = realRunning || starting;
              const recordCli = dev ? `trailblaze run ${home}/${blazeName} --device ${col.platform} --no-use-recorded-steps` : '';
              const playCli = dev ? `trailblaze run ${home}/${col.name} --device ${col.platform} --use-recorded-steps` : '';
              const verb = col.recorded ? 'Re-record' : 'Record';
              return (
                <div key={col.platform} style={{ borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '11px 12px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 7 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, minHeight: 26 }}>
                    {dev ? (
                      <React.Fragment>
                        {/* device picker — dropdown when this platform has more than one connected device.
                            Positioned fixed (anchored to the trigger) so it escapes the board's scroll clip. */}
                        <span style={{ flex: 1, minWidth: 0 }}>
                          <span role="button"
                            onClick={(e) => { if (col.devices.length <= 1) return; const r = e.currentTarget.getBoundingClientRect(); setOpenDevDrop(openDevDrop && openDevDrop.platform === col.platform ? null : { platform: col.platform, left: r.left, top: r.bottom + 6 }); }}
                            title={dev.name}
                            style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0, cursor: col.devices.length > 1 ? 'pointer' : 'default' }}>
                            <span style={{ fontSize: 12.5, color: 'var(--text-standard)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{dev.name}</span>
                            {col.devices.length > 1 && <Ico n="chevron-down" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />}
                          </span>
                          {openDevDrop && openDevDrop.platform === col.platform && (
                            <React.Fragment>
                              <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={() => setOpenDevDrop(null)} />
                              <div className="tb-card" style={{ position: 'fixed', left: openDevDrop.left, top: openDevDrop.top, zIndex: 71, minWidth: 240, maxWidth: 360, padding: 6, background: 'var(--bg-elevated)', boxShadow: '0 14px 40px rgba(0,0,0,.5)' }}>
                                <div className="tb-sub" style={{ fontSize: 10.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.04em', padding: '6px 8px 4px' }}>{col.platform} device</div>
                                {col.devices.map((dd) => (
                                  <div key={dd.id} role="button" onClick={() => { setColDeviceSel((s) => ({ ...s, [col.platform]: dd.id })); setRecDevices(null); setOpenDevDrop(null); }}
                                    style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 8px', borderRadius: 7, cursor: 'pointer', fontSize: 13, color: 'var(--text-standard)' }}>
                                    <Ico n={dd.id === dev.id ? 'check' : 'circle'} s={13} c={dd.id === dev.id ? 'var(--tb-pass)' : 'var(--text-subtle)'} />
                                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{dd.name}</span>
                                  </div>
                                ))}
                              </div>
                            </React.Fragment>
                          )}
                        </span>
                        {col.recorded && (
                          <Btn sm kind="ghost" ico="play"
                            title={running ? 'Recording…' : `Run ${col.name} on ${dev.name}\n${playCli}`}
                            disabled={busy || running}
                            onClick={() => { if (busy || running) return; onConfigureRun ? onConfigureRun(col) : playVariant(col); }}
                            style={{ color: 'var(--tb-pass)' }}>Run</Btn>
                        )}
                        {/* Ghost shell to match Run; a red REC dot (not an error-red fill) carries the
                            record meaning iconically — red-as-error stays reserved for fail/destructive. */}
                        <Btn sm kind="ghost" ico={running ? 'loader-2' : undefined} spin={running}
                          title={!canRecord ? 'Add a blaze.yaml to record from' : running ? 'Recording…' : `${verb} on ${dev.name}\n${recordCli}`}
                          disabled={busy || running || !canRecord}
                          onClick={() => { if (busy || running || !canRecord) return; setRecDevices(new Set([dev.id])); setRecDialogOpen(true); }}>
                          {!running && <span style={{ width: 9, height: 9, borderRadius: 99, background: 'var(--tb-fail)', flex: '0 0 auto' }} />}
                          <span>{verb}</span>
                        </Btn>
                      </React.Fragment>
                    ) : <span className="tb-sub" style={{ fontSize: 12.5, flex: 1, minWidth: 0 }}>device offline</span>}
                  </div>
                  {/* status */}
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    {starting ? (
                      <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--tb-running)', display: 'inline-flex', alignItems: 'center', gap: 5 }}><Ico n="loader-2" s={12} c="var(--tb-running)" spin /> starting…</span>
                    ) : realRunning ? (
                      <span role="button" onClick={() => onViewRun(col.sess)} style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--tb-running)', display: 'inline-flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>{playingPlatforms.has(col.platform) ? 'running' : 'recording'} · watch live <Ico n="arrow-right" s={11} /></span>
                    ) : st ? (
                      <span style={{ fontSize: 10.5, fontWeight: 700, color: RUN_STATUS_DOT[st] ? RUN_STATUS_DOT[st][1] : 'var(--text-subtle)', textTransform: 'capitalize' }}>{st}</span>
                    ) : null}
                    {!starting && col.recorded && <span className="tb-sub" style={{ fontSize: 10.5 }}>{tc} {tc === 1 ? 'tool' : 'tools'}</span>}
                    {!starting && !col.recorded && !realRunning && !st && <span className="tb-sub" style={{ fontSize: 10.5 }}>not recorded yet</span>}
                    {!starting && col.sess && !realRunning && <span {...clickable(() => onViewRun(col.sess))} style={{ fontSize: 10.5, color: 'var(--tb-link)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3 }}>view run <Ico n="arrow-right" s={10} /></span>}
                  </span>
                </div>
              );
            })}

            {/* config row — each file's config, at the top of its column. Read-only here; edit via the
                file's Edit button. Kept visually quiet (matches the step rows) — supporting detail. */}
            <div className="tb-board-pin" style={{ borderTop: '1px solid var(--tb-hairline)', padding: '8px 12px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: '.05em', opacity: 0.7 }}>Config</span>
              {blazeConfigRows.length === 0
                ? <span className="tb-sub" style={{ fontSize: 12 }}>no config keys</span>
                : (
                  <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '4px 10px', alignItems: 'flex-start', minWidth: 0 }}>
                    {blazeConfigRows.map(({ path, value }) => (
                      <React.Fragment key={path}>
                        <span className="tb-sub" style={{ fontSize: 'var(--fz-2xs)', whiteSpace: 'nowrap' }} title={path}>{path}</span>
                        <span style={{ minWidth: 0, fontFamily: 'var(--font-mono)', fontSize: 'var(--fz-xs)', lineHeight: 1.45, color: 'var(--text-standard)', wordBreak: 'break-word', whiteSpace: 'normal' }}>{value}</span>
                      </React.Fragment>
                    ))}
                  </div>
                )}
            </div>
            {gridColumns.map((col) => {
              const conf = (col.parsed && col.parsed.config) || {};
              const driver = conf.driver || DEFAULT_DRIVER[col.platform];
              const extra = Object.keys(conf).filter((k) => k !== 'driver' && k !== 'title');
              return (
                <div key={col.platform} style={{ borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                    <span className="tb-sub" style={{ fontSize: 'var(--fz-2xs)', flex: '0 0 52px' }}>driver</span>
                    <span style={{ flex: 1, minWidth: 0, fontFamily: 'var(--font-mono)', fontSize: 'var(--fz-xs)', lineHeight: 1.45, color: col.recorded ? 'var(--text-standard)' : 'var(--text-subtle)', wordBreak: 'break-word', whiteSpace: 'normal' }}>
                      {driver || '—'}{!col.recorded && ' (default)'}
                    </span>
                  </span>
                  {extra.map((k) => (
                    <span key={k} style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                      <span className="tb-sub" style={{ fontSize: 'var(--fz-2xs)', flex: '0 0 52px' }}>{k}</span>
                      <span style={{ flex: 1, minWidth: 0, fontFamily: 'var(--font-mono)', fontSize: 'var(--fz-xs)', lineHeight: 1.45, color: 'var(--text-standard)', wordBreak: 'break-word', whiteSpace: 'normal' }}>{String(conf[k])}</span>
                    </span>
                  ))}
                </div>
              );
            })}

            {/* trailhead row — the deterministic step 0 that runs before the steps (clear data /
                launch / sign in into a known account state). The new format authors it once on the
                blaze (left, the cross-platform spec) as `trailhead: { step }`; shown here as the spec
                text. The per-device Selects (right) are the legacy sidecar-trailhead picker, kept for
                targets still on that model — a trailhead's launch/sign-in tool is platform-specific. */}
            {(() => {
              // Condense the trailhead band to one quiet line when no column has a trailhead set or
              // available — it's an opt-in field and shouldn't carry a full row of "None" controls.
              const anyTh = gridColumns.some((c) => (c.trailheadOpts || []).length || colTrailheads[c.platform]);
              const specTrailhead = blazeTrailhead && blazeTrailhead.step ? String(blazeTrailhead.step) : null;
              // A trailhead may carry recorded tools with or without a `step:` sentence - show
              // them so a tools-only trailhead doesn't read as "none".
              const specTrailheadTools = blazeTrailhead && Array.isArray(blazeTrailhead.tools) ? toolNamesOf(blazeTrailhead.tools) : [];
              return (
                <React.Fragment>
                  <div className="tb-board-pin" style={{ borderTop: '1px solid var(--tb-hairline)', padding: '8px 12px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: '.05em', opacity: 0.7 }}>Trailhead</span>
                    {specTrailhead
                      ? <span data-selectable style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-standard)' }}>{specTrailhead}</span>
                      : specTrailheadTools.length === 0 && <span className="tb-sub" style={{ fontSize: 11.5 }}>{anyTh ? 'chosen per device' : 'none'}</span>}
                    {specTrailheadTools.length > 0 && (
                      <span className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle-variant)', display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        <Ico n="box" s={11} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto' }} /> {specTrailheadTools.join(' · ')}
                      </span>
                    )}
                  </div>
                  {gridColumns.length > 0 && !anyTh && (
                    <div style={{ gridColumn: 'span ' + gridColumns.length, borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '8px 12px', display: 'flex', alignItems: 'center' }}>
                      <span className="tb-sub" style={{ fontSize: 11.5 }}>No trailheads available for these platforms</span>
                    </div>
                  )}
                  {anyTh && gridColumns.map((col) => {
                    const opts = col.trailheadOpts || [];
                    const cur = colTrailheads[col.platform] || '';
                    return (
                      <div key={col.platform} style={{ borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                        {opts.length ? (
                          <Select full title="Trailhead" value={cur}
                            onChange={(e) => setColTrailheads((m) => ({ ...m, [col.platform]: e.target.value }))}
                            options={[['', 'None'], ...opts.map((t) => [t.name, t.name])]} />
                        ) : (
                          <span className="tb-sub" style={{ fontSize: 11.5 }}>None</span>
                        )}
                      </div>
                    );
                  })}
                </React.Fragment>
              );
            })()}

            {/* steps section header — the PRIMARY working area. A heavier top rule + extra space set it
                apart from the quiet Config/Trailhead bands above (white-space-first hierarchy, ch07). */}
            <div className="tb-board-pin" style={{ borderTop: '2px solid var(--tb-hairline-strong)', padding: '12px 12px 9px', background: 'var(--bg-subtle)' }}>
              <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-subtle-variant)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Steps</span>
            </div>
            {gridColumns.map((col) => (
              <div key={col.platform} style={{ borderTop: '2px solid var(--tb-hairline-strong)', borderLeft: '1px solid var(--tb-hairline)', padding: '12px 12px 9px' }}>
                <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-subtle-variant)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Recording</span>
              </div>
            ))}

            {/* root-level `- tools:` steps recorded straight into this file - real steps the engine
                force-executes, but not NL-editable rows (edit them via the YAML), so they render
                read-only. Before this, a tools-recorded blaze read as "0 steps". */}
            {blazeToolsSteps.map((toolsArr, i) => (
              <React.Fragment key={'bt' + i}>
                <div className="tb-board-pin" style={{ borderTop: '1px solid var(--tb-hairline)', padding: '9px 12px', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'flex-start', gap: 6 }}>
                  <span style={{ flex: '0 0 auto', width: 14, marginTop: 5 }} />
                  <Chip tone="green" style={{ width: 58, justifyContent: 'center', flex: '0 0 auto', marginTop: 2 }}>TOOLS</Chip>
                  <span className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, fontSize: 12, lineHeight: 1.6, color: 'var(--text-standard)', wordBreak: 'break-word', paddingTop: 2 }}>{toolNamesOf(toolsArr).join(' · ') || '(empty)'}</span>
                  <span {...clickable(() => onOpenFile(blazeName))} title={'Recorded in ' + blazeName + ' - edit via YAML'} style={{ fontSize: 11, color: 'var(--tb-link)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 3, flex: '0 0 auto', marginTop: 3 }}><Ico n="pencil" s={11} /></span>
                </div>
                {gridColumns.map((col) => (
                  <div key={col.platform} style={{ borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '9px 12px' }}>
                    {/* Not a gap in this device's recording: the shared file's tools run verbatim
                        on every device, so the cell says that instead of a cryptic dash. */}
                    <span style={{ fontSize: 11.5, color: 'var(--text-subtle-variant)', fontStyle: 'italic' }}>shared - runs as recorded</span>
                  </div>
                ))}
              </React.Fragment>
            ))}

            {/* one row per step */}
            {(steps || []).map((s, i) => {
              const isVerify = s.kind === 'verify';
              const hovered = hoveredStep === i;
              const anyRecorded = gridColumns.some((col) => stepToolCount((col.aligned[i] || {}).entry) > 0);
              return (
                <React.Fragment key={i}>
                  {/* left: editable step (the blaze) — chip inline with the text, controls on hover */}
                  {/* onFocus/onBlur mirror the hover state so the (otherwise hover-only) reorder/delete
                      controls reveal when a keyboard user tabs into the row — focus bubbles up here. */}
                  <div className="tb-board-pin" onMouseEnter={() => setHoveredStep(i)} onMouseLeave={() => setHoveredStep((h) => (h === i ? -1 : h))}
                    onFocus={() => setHoveredStep(i)} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setHoveredStep((h) => (h === i ? -1 : h)); }}
                    onDragOver={dragStep >= 0 ? (e) => { e.preventDefault(); setDropStep(i); } : undefined}
                    onDrop={dragStep >= 0 ? (e) => { e.preventDefault(); reorderStep(dragStep, i); setDragStep(-1); setDropStep(-1); } : undefined}
                    style={{ borderTop: (dropStep === i && dragStep >= 0 && dragStep !== i) ? '2px solid var(--tb-pass)' : '1px solid var(--tb-hairline)', padding: '9px 12px', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'flex-start', gap: 6, opacity: dragStep === i ? 0.4 : 1 }}>
                    {/* drag handle — grab to reorder the step; the recording columns follow automatically */}
                    <span draggable title="Drag to reorder" aria-label="Drag to reorder step"
                      onDragStart={(e) => { setDragStep(i); if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(i)); } }}
                      onDragEnd={() => { setDragStep(-1); setDropStep(-1); }}
                      style={{ cursor: 'grab', flex: '0 0 auto', marginTop: 5, color: 'var(--text-subtle)', opacity: hovered ? 0.85 : 0.25, transition: 'opacity .12s ease', display: 'inline-flex' }}><Ico n="grip-vertical" s={14} /></span>
                    <span {...clickable(() => setStep(i, { kind: isVerify ? 'do' : 'verify' }))} title="Toggle do / verify" style={{ cursor: 'pointer', flex: '0 0 auto', marginTop: 2 }}>
                      <Chip tone={isVerify ? 'blue' : 'purple'} style={{ width: 58, justifyContent: 'center' }}>{isVerify ? 'VERIFY' : 'STEP'}</Chip>
                    </span>
                    <textarea value={s.text} onChange={(e) => setStep(i, { text: e.target.value })} placeholder="Describe the step…"
                      rows={1} ref={(el) => { if (el) { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; } }}
                      style={{ flex: 1, minWidth: 0, resize: 'none', overflow: 'hidden', background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 13.5, lineHeight: 1.5, paddingTop: 1 }} />
                    <span style={{ display: 'flex', gap: 1, flex: '0 0 auto', opacity: hovered ? 0.8 : 0, transition: 'opacity .12s ease', pointerEvents: hovered ? 'auto' : 'none' }}>
                      <span {...clickable(() => moveStep(i, -1))} aria-label="Move step up" title="Move up" style={{ cursor: 'pointer', opacity: i === 0 ? 0.4 : 1 }}><Ico n="chevron-up" s={15} /></span>
                      <span {...clickable(() => moveStep(i, 1))} aria-label="Move step down" title="Move down" style={{ cursor: 'pointer', opacity: i === steps.length - 1 ? 0.4 : 1 }}><Ico n="chevron-down" s={15} /></span>
                      <span {...clickable(() => delStep(i))} aria-label="Delete step" title="Delete step" style={{ cursor: 'pointer' }}><Ico n="x" s={15} /></span>
                    </span>
                  </div>
                  {/* one device cell per column */}
                  {gridColumns.map((col) => {
                    const e = (col.aligned[i] || {}).entry;
                    const tc = stepToolCount(e);
                    const st = col.sess ? col.sess.status : null;
                    const running = st === 'running';
                    const failed = st === 'failed' || st === 'timeout';
                    const isFailPoint = failed && i === col.lastIdx && tc > 0;
                    const notReached = failed && i > col.lastIdx;
                    const cellBg = isFailPoint ? 'rgba(239,68,68,.10)'
                      : notReached ? 'rgba(120,120,120,.05)'
                      : 'transparent';
                    // A recorded step entry (e) can be edited as a whole cell: clicking opens
                    // ToolCallsPopover for its full tool-call list (even when it has 0 tools — to add some).
                    // Cells with no entry fall back to jumping into the file's YAML.
                    // Any cell in a recorded variant opens the tool-calls editor: an existing entry edits
                    // its tools; an empty cell (no recording for this step) opens blank and, on save,
                    // creates a recording for this blaze step (promptIndex null + the step's text/kind).
                    const canEdit = !!(onSaveVariantTools && col.recorded);
                    const hasEntry = !!(e && e.promptIndex != null);
                    const editing = !!(editCell && editCell.platform === col.platform && editCell.stepIndex === i);
                    const clickable = canEdit || col.recorded;
                    const openCell = (ev) => {
                      if (canEdit) {
                        const calls = hasEntry ? ((e.recording && e.recording.tools) || []).map((t) => { const k = Object.keys(t)[0] || ''; return { name: k, body: t[k] || {} }; }) : [];
                        setEditCell({ variantName: col.name, platform: col.platform, promptIndex: hasEntry ? e.promptIndex : null, stepIndex: i, stepText: s.text, stepKind: s.kind, calls, anchor: ev.currentTarget.getBoundingClientRect() });
                      } else if (col.recorded) {
                        onOpenFile(col.name, { text: s.text, kind: s.kind });
                      }
                    };
                    return (
                      <div key={col.platform}
                        className={clickable && dragStep < 0 ? 'tb-cell-click' : undefined}
                        onClick={clickable && dragStep < 0 ? openCell : undefined}
                        title={canEdit ? (hasEntry ? 'Edit tool calls for this step' : 'Add tool calls for this step') : clickable ? `Edit ${col.name} — jump to this step` : undefined}
                        onDragOver={dragStep >= 0 ? (ev) => { ev.preventDefault(); setDropStep(i); } : undefined}
                        onDrop={dragStep >= 0 ? (ev) => { ev.preventDefault(); reorderStep(dragStep, i); setDragStep(-1); setDropStep(-1); } : undefined}
                        style={{ position: 'relative', borderTop: (dropStep === i && dragStep >= 0 && dragStep !== i) ? '2px solid var(--tb-pass)' : '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', padding: '9px 12px', background: cellBg, cursor: clickable && dragStep < 0 ? 'pointer' : 'default', boxShadow: isFailPoint ? 'inset 2px 0 0 var(--tb-fail)' : undefined, opacity: dragStep === i ? 0.4 : 1 }}>
                        {/* selection: a rounded-rect frame floating a few px inside the cell, so the cell's
                            own padding stays identical to every sibling (the selection adds breathing room,
                            it doesn't reshape the cell) — foreground/background (ch06), white space (ch07). */}
                        {editing && <div style={{ position: 'absolute', inset: 4, border: '1.5px solid var(--tb-link)', borderRadius: 8, background: 'rgba(94,155,255,.07)', pointerEvents: 'none' }} />}
                        {tc > 0 ? (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                            {isFailPoint && <span style={{ alignSelf: 'flex-start', fontSize: 10.5, fontWeight: 700, color: 'var(--tb-fail)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><Ico n="x-circle" s={12} c="var(--tb-fail)" /> failed here</span>}
                            <RecordingCell recording={e.recording} />
                          </div>
                        ) : running ? (
                          i === 0 ? <span style={{ fontSize: 11, color: 'var(--tb-running)', display: 'inline-flex', alignItems: 'center', gap: 5 }}><Ico n="loader-2" s={12} c="var(--tb-running)" spin /> {playingPlatforms.has(col.platform) ? 'running…' : 'recording…'}</span> : null
                        ) : notReached ? (
                          i === col.lastIdx + 1 ? <span style={{ fontSize: 11, color: 'var(--text-subtle)', fontStyle: 'italic' }}>not reached</span> : null
                        ) : anyRecorded ? (
                          <span style={{ fontSize: 11.5, color: 'var(--tb-amber)', display: 'inline-flex', alignItems: 'center', gap: 5 }}><Ico n="minus-circle" s={12} /> no tools here</span>
                        ) : (
                          <span style={{ fontSize: 11.5, color: 'var(--text-subtle-variant)' }}>—</span>
                        )}
                      </div>
                    );
                  })}
                </React.Fragment>
              );
            })}

            {/* add-step row (empty device cells keep the grid aligned) */}
            <div className="tb-board-pin" style={{ borderTop: '1px solid var(--tb-hairline)', padding: '10px 12px', background: 'var(--bg-subtle)' }}>
              <span role="button" onClick={addStep} style={{ color: 'var(--tb-pass)', fontSize: 13, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <Ico n="plus" s={14} /> Add step
              </span>
            </div>
            {gridColumns.map((col) => (
              <div key={col.platform} style={{ borderTop: '1px solid var(--tb-hairline)', borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)' }} />
            ))}
          </div>
        </div>
        {/* Expanded variant — focuses one recording over the board: blaze steps stay as context on the
            left, the variant fills the right with a Steps / Raw-YAML toggle (the inline replacement for
            the old slide-in editor). Animates in (opacity + slight scale). */}
        {expanded && expandedRaw && expCol && (
          <div style={{ position: 'absolute', inset: 0, zIndex: 20, display: 'flex', background: 'var(--bg-window)', opacity: expandShown ? 1 : 0, transform: expandShown ? 'scale(1)' : 'scale(.985)', transformOrigin: 'center', transition: 'opacity .2s ease, transform .2s ease' }}>
            {/* left: the blaze's human-readable steps, read-only context */}
            <div style={{ flex: '0 0 38%', minWidth: 0, borderRight: '1px solid var(--tb-hairline-strong)', overflowY: 'auto', padding: '14px 16px', background: 'var(--bg-subtle)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12 }}>
                <Ico n="file-text" s={13} c="var(--tb-link)" />
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--tb-link)' }}>{blazeName}</span>
                <span className="tb-sub" style={{ fontSize: 10.5 }}>· {(steps || []).length + blazeToolsSteps.length} {(steps || []).length + blazeToolsSteps.length === 1 ? 'step' : 'steps'}</span>
              </div>
              {(steps || []).map((s, i) => {
                const isV = s.kind === 'verify';
                return (
                  <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '7px 0', borderTop: i === 0 ? 'none' : '1px solid var(--tb-hairline)' }}>
                    <Chip tone={isV ? 'blue' : 'purple'} style={{ width: 58, justifyContent: 'center', flex: '0 0 auto' }}>{isV ? 'VERIFY' : 'STEP'}</Chip>
                    <span style={{ fontSize: 13, lineHeight: 1.5, minWidth: 0 }}>{s.text}</span>
                  </div>
                );
              })}
            </div>
            {/* right: the focused variant */}
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', background: 'var(--bg-window)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 16px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
                <Ico n="circle-play" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--text-standard)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{expCol.name}</span>
                {/* Steps / Raw-YAML segmented toggle */}
                <span style={{ display: 'inline-flex', border: '1px solid var(--tb-hairline-strong)', borderRadius: 8, overflow: 'hidden', flex: '0 0 auto' }}>
                  {[['Steps', false], ['Raw YAML', true]].map(([lbl, raw]) => (
                    <span key={lbl} role="button" onClick={() => setRawMode(raw)}
                      style={{ padding: '5px 11px', fontSize: 12, fontWeight: 600, cursor: 'pointer', background: expandedRaw === raw ? 'var(--bg-extra-prominent)' : 'transparent', color: expandedRaw === raw ? 'var(--text-standard)' : 'var(--text-subtle)' }}>{lbl}</span>
                  ))}
                </span>
                <span role="button" onClick={collapseExpanded} title="Collapse (Esc)" aria-label="Collapse" style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex' }}><Ico n="minimize-2" s={15} /></span>
              </div>
              <div style={{ flex: 1, minHeight: 0, overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
                {onSaveVariantYaml
                  ? <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '12px 16px' }}>
                      <TrailYamlEditor content={expYaml} editable dirtyRef={rawDirtyRef} tools={editorToolsFor(toolCatalog.data, target, expCol.platform)}
                        onSave={(t) => onSaveVariantYaml(expCol.name, t)} />
                    </div>
                  : <div className="tb-sub" style={{ padding: 16, fontSize: 12.5 }}>Raw editing isn’t available here.</div>}
              </div>
            </div>
          </div>
        )}
      </div>
      {recDialogOpen && (() => {
        // Scope the dialog's trailhead options to the recording's platform (trailheads are
        // platform-specific) and default to whatever this platform's column already picked.
        const plat = (recChosen[0] && recChosen[0].platform) || '';
        return (
          <RecordConfigDialog devices={recChosen} bundleTarget={target} busy={busy}
            trailheads={TB.trailheadsForPlatform(trailmaps.data, target, plat)}
            defaultTrailhead={colTrailheads[plat] || ''}
            onClose={() => setRecDialogOpen(false)}
            onRecord={(opts) => {
              setRecDialogOpen(false);
              // Remember the per-platform choice so the board column reflects what got recorded.
              if (plat) setColTrailheads((m) => ({ ...m, [plat]: opts.trailheadId || '' }));
              runRecord(recChosen, opts);
            }} />
        );
      })()}
      {editCell && (
        <ToolCallsPopover
          calls={editCell.calls}
          tools={editorToolsFor(toolCatalog.data, target, editCell.platform)}
          allTools={toolCatalog.data || []}
          anchor={editCell.anchor}
          busy={savingCell}
          onClose={() => setEditCell(null)}
          onSave={async (newToolsArray) => {
            setSavingCell(true); setErr(null);
            const r = await onSaveVariantTools(editCell.variantName, editCell.promptIndex, newToolsArray, { text: editCell.stepText, kind: editCell.stepKind });
            setSavingCell(false);
            if (r && r.success) setEditCell(null);
            else setErr((r && r.error) || 'Could not save the tool calls.');
          }} />
      )}
    </div>
  );
}

// Configure-recording dialog (mirrors the Configure-run dialog): confirm the devices, pick the target
// app, and adjust recording properties before dispatching. `onRecord(options)` does the run.
function RecordConfigDialog({ devices, bundleTarget, busy, trailheads, defaultTrailhead, onClose, onRecord }) {
  useLucide();
  const primary = devices && devices[0];
  const deviceApps = TB.useDeviceApps(primary && primary.platform !== 'web' ? primary.platform : null, primary ? primary.id : null);
  const installed = (deviceApps.data && deviceApps.data.targets) || [];
  const [targetApp, setTargetAppId] = React.useState(null);
  React.useEffect(() => {
    if (!installed.length) return;
    const ids = installed.map((a) => a.id);
    if (targetApp && ids.includes(targetApp)) return;
    const cur = deviceApps.data && deviceApps.data.currentTargetAppId;
    setTargetAppId(bundleTarget && ids.includes(bundleTarget) ? bundleTarget : (cur && ids.includes(cur) ? cur : ids[0]));
  }, [installed.map((a) => a.id).join(','), bundleTarget]);
  // Trailhead options for THIS recording's platform (scoped by the caller) + the built-ins None /
  // Fresh install. Defaults to the platform column's current pick. The dialog remounts each open, so
  // the initial state tracks defaultTrailhead without a sync effect.
  const trailheadList = trailheads || [];
  const TRAILHEAD_SENTINELS = ['none', 'fresh']; // not real trailhead ids
  const [trailhead, setTrailhead] = React.useState(defaultTrailhead || 'none'); // tool id | 'none' | 'fresh'
  const [captureVideo, setCaptureVideo] = React.useState(true);
  const [selfHeal, setSelfHeal] = React.useState(false);
  const [maxLlmCalls, setMaxLlmCalls] = React.useState('50');
  const [starting, setStarting] = React.useState(false);
  const isMobile = primary && primary.platform !== 'web';
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  const start = async () => {
    setStarting(true);
    if (targetApp && installed.length) await TB.setTargetApp(targetApp).catch(() => {});
    const n = parseInt(maxLlmCalls, 10);
    // Any non-sentinel value is a real trailhead id — do NOT gate on trailheadList membership: the list
    // loads async (TB.useTrailmaps), so gating would drop a persisted/selected id if the dialog opened
    // before it resolved. The server re-validates the id (regex + no-op on a bad one) as the real gate.
    const isRealTrailhead = !!trailhead && !TRAILHEAD_SENTINELS.includes(trailhead);
    const fresh = trailhead === 'fresh' && isMobile && !!targetApp;
    onRecord({
      captureVideo, selfHeal, maxLlmCalls: Number.isFinite(n) && n > 0 ? n : null,
      trailheadId: isRealTrailhead ? trailhead : null,
      freshInstall: fresh, clearAppId: fresh ? targetApp : null,
    });
  };
  const Row = ({ label, hint, first, children }) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 0', borderTop: first ? 'none' : '1px solid var(--tb-hairline)' }}>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ fontSize: 13, color: 'var(--text-standard)' }}>{label}</span>
        {hint && <span className="tb-sub" style={{ fontSize: 11, display: 'block' }}>{hint}</span>}
      </span>
      <span style={{ flex: '0 0 auto' }}>{children}</span>
    </div>
  );
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', zIndex: 50, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 32 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, 94vw)', maxHeight: '88vh', overflow: 'auto', display: 'flex', flexDirection: 'column', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '13px 16px', borderBottom: '1px solid var(--tb-hairline)' }}>
          <Ico n="circle-dot" s={15} c="var(--tb-pass)" />
          <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-standard)' }}>Configure recording</span>
          <span style={{ flex: 1 }} />
          <span role="button" onClick={onClose} title="Close" style={{ cursor: 'pointer', color: 'var(--text-subtle)' }}><Ico n="x" s={18} /></span>
        </div>
        <div style={{ padding: '4px 16px 12px' }}>
          <Row first label="Devices" hint="the recording runs on each of these">
            <span style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {(devices || []).map((d) => <Chip key={d.id} tone="green">{d.name}</Chip>)}
            </span>
          </Row>
          {primary && primary.platform !== 'web' && (
            <Row label="Target app" hint="set on the device before recording">
              {installed.length ? (
                <Select title="Target app" value={targetApp || ''} onChange={(e) => setTargetAppId(e.target.value)}
                  options={installed.map((a) => [a.id, a.displayName || a.id])} />
              ) : <span className="tb-sub" style={{ fontSize: 12 }}>{bundleTarget || 'device default'}</span>}
            </Row>
          )}
          {(isMobile || trailheadList.length > 0) && (
            <Row label="Trailhead" hint={trailhead === 'fresh' && !targetApp ? 'pick a target app to clear' : 'deterministic step 0 — runs before the prompts'}>
              <Select title="Trailhead" value={trailhead} onChange={(e) => setTrailhead(e.target.value)}
                options={[
                  ['none', 'None'],
                  ...(isMobile ? [['fresh', 'Fresh install (clear app state)']] : []),
                  ...trailheadList.map((t) => [t.name, t.name]),
                ]} />
            </Row>
          )}
          <Row label="Model" hint="LLM that drives the AI steps while recording"><ModelPicker /></Row>
          <Row label="Agent" hint="agent runner that drives the recording"><AgentPicker /></Row>
          <Row label="Capture video"><Switch on={captureVideo} onClick={() => setCaptureVideo((v) => !v)} /></Row>
          <Row label="Self-heal" hint="let the agent recover from a failed step"><Switch on={selfHeal} onClick={() => setSelfHeal((v) => !v)} /></Row>
          <Row label="Max LLM calls" hint="budget per device">
            <input value={maxLlmCalls} onChange={(e) => setMaxLlmCalls(e.target.value.replace(/[^0-9]/g, ''))}
              style={{ width: 70, background: 'var(--bg-subtle)', color: 'var(--text-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '5px 8px', fontSize: 13, textAlign: 'right' }} />
          </Row>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'flex-end', padding: '12px 16px', borderTop: '1px solid var(--tb-hairline)' }}>
          <Btn kind="ghost" onClick={onClose}>Cancel</Btn>
          <Btn kind="primary" ico={busy || starting ? 'loader-2' : 'circle-dot'} spin={busy || starting}
            disabled={!devices || !devices.length || busy || starting} onClick={start}>
            Start recording{devices && devices.length > 1 ? ` on ${devices.length}` : ''}
          </Btn>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  RUN_STATUS_DOT, DEFAULT_DRIVER, parseTrailYaml, patchVariantRecording, promptEntry, stepToolCount, fileToolCount,
  SelectorLine, RecordingCell, ToolCallsPopover, editorToolsFor, StepsBoard, RecordConfigDialog,
});

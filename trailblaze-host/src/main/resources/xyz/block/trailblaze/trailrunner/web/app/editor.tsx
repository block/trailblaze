// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function CodeEditor({ value, onChange, onSave, serverLint, mode = 'yaml', readOnly = false, wrap = false, apiRef, highlight }) {
  const hostRef = React.useRef(null);
  const flashedRef = React.useRef(null);
  const cmRef = React.useRef(null);
  const changeRef = React.useRef(onChange);
  const saveRef = React.useRef(onSave);
  const lintRef = React.useRef(serverLint);
  changeRef.current = onChange;
  saveRef.current = onSave;
  lintRef.current = serverLint;

  const modeKey = typeof mode === 'string' ? mode : JSON.stringify(mode);
  React.useEffect(() => {
    if (!window.CodeMirror || !hostRef.current) return;
    const cm = window.CodeMirror(hostRef.current, {
      value: value != null ? value : '',
      mode,
      theme: 'tb',
      readOnly: readOnly ? 'nocursor' : false,
      lineNumbers: true,
      lineWrapping: !!wrap,
      indentUnit: 2,
      tabSize: 2,
      viewportMargin: Infinity,
      gutters: ['CodeMirror-lint-markers', 'CodeMirror-linenumbers'],
      lint: mode !== 'yaml' ? false : {
        async: true,
        delay: 600,
        getAnnotations: (text, updateLinting, options, editor) => {
          const anns = [];
          if (window.jsyaml) {
            try { window.jsyaml.loadAll(text); } catch (e) {
              const ln = (e.mark && e.mark.line) || 0;
              anns.push({ from: window.CodeMirror.Pos(ln, 0), to: window.CodeMirror.Pos(ln, 120), message: e.reason || String(e), severity: 'error' });
            }
          }
          if (anns.length || !lintRef.current) { updateLinting(editor, anns); return; }
          lintRef.current(text).then((r) => {
            if (r && r.unavailable) {
              // Server validation didn't run — surface a neutral note, don't imply the trail is valid.
              anns.push({ from: window.CodeMirror.Pos(0, 0), to: window.CodeMirror.Pos(0, 80), message: 'Trail validation unavailable (server check failed)', severity: 'warning' });
            } else {
              (r && r.errors || []).forEach((er) => {
                const ln = Math.max(0, (er.line || 1) - 1);
                anns.push({ from: window.CodeMirror.Pos(ln, 0), to: window.CodeMirror.Pos(ln, 120), message: er.message, severity: 'error' });
              });
            }
            updateLinting(editor, anns);
          }).catch(() => updateLinting(editor, anns));
        },
      },
      extraKeys: {
        'Cmd-S': () => saveRef.current && saveRef.current(),
        'Ctrl-S': () => saveRef.current && saveRef.current(),
      },
    });
    cm.on('change', () => changeRef.current && changeRef.current(cm.getValue()));
    cmRef.current = cm;
    if (readOnly) cm.getWrapperElement().classList.add('cm-readonly');
    // Imperative insert-at-cursor for the tools palette: indents continuation lines to the cursor line.
    if (apiRef) {
      apiRef.current = {
        insert: (text) => {
          const c = cmRef.current; if (!c) return;
          const cur = c.getCursor();
          const lineText = c.getLine(cur.line) || '';
          const indent = (lineText.match(/^\s*/) || [''])[0];
          const lines = String(text).split('\n');
          const body = lines.map((l, i) => (i === 0 ? l : indent + l)).join('\n');
          c.replaceRange(lineText.trim() === '' ? body : ('\n' + indent + body), cur);
          c.focus();
        },
        // Structural insert for the tools palette: drop the tool into the `recording: → tools:`
        // block of the step under the cursor, scaffolding those rows if the step has none yet.
        // Falls back to a plain cursor insert when the doc has no step to attach to.
        insertTool: (tool) => {
          const c = cmRef.current; if (!c) return;
          const res = insertToolIntoTrailYaml(c.getValue(), c.getCursor().line, tool);
          if (!res) { apiRef.current.insert(toolSnippet(tool)); return; }
          c.operation(() => {
            c.setValue(res.text);
            c.setCursor({ line: res.line, ch: res.ch });
          });
          c.scrollIntoView({ line: res.line, ch: res.ch }, 80);
          c.focus();
        },
      };
    }
    return () => {
      const el = cm.getWrapperElement();
      if (el && el.parentNode) el.parentNode.removeChild(el);
      cmRef.current = null;
    };
  }, [modeKey, readOnly]);

  React.useEffect(() => {
    const cm = cmRef.current;
    if (cm && value != null && value !== cm.getValue()) cm.setValue(value);
  }, [value]);

  React.useEffect(() => {
    const cm = cmRef.current;
    if (cm) cm.setOption('lineWrapping', !!wrap);
  }, [wrap]);

  // Jump to + flash a step's YAML block when the caller hands us a highlight target (e.g. clicking a
  // board cell). Matches the `- step:`/`- verify:` line carrying the text, then flashes through the
  // end of that step's block (its recording). Keyed by the target's identity so it fires once per
  // request (a fresh object per click), not on every keystroke.
  React.useEffect(() => {
    const cm = cmRef.current;
    if (!cm || !highlight || !highlight.text || flashedRef.current === highlight) return;
    const doc = cm.getValue();
    if (!doc) return; // content not loaded yet — re-runs when `value` arrives
    const lines = doc.split('\n');
    const isStep = (s) => /^\s*-\s*(step|verify|prompt)\s*:/.test(s);
    const needle = String(highlight.text).trim().slice(0, 48);
    let start = lines.findIndex((l) => isStep(l) && l.includes(needle));
    if (start < 0) start = lines.findIndex((l) => l.includes(needle));
    if (start < 0) { flashedRef.current = highlight; return; }
    let end = lines.length - 1;
    for (let i = start + 1; i < lines.length; i++) { if (isStep(lines[i])) { end = i - 1; break; } }
    flashedRef.current = highlight;
    cm.scrollIntoView({ from: { line: start, ch: 0 }, to: { line: Math.min(end, start + 10), ch: 0 } }, 60);
    cm.setCursor({ line: start, ch: (lines[start] || '').length });
    const handles = [];
    for (let ln = start; ln <= end; ln++) handles.push(cm.addLineClass(ln, 'background', 'cm-flash'));
    const t = setTimeout(() => handles.forEach((h) => cm.removeLineClass(h, 'background', 'cm-flash')), 1900);
    return () => clearTimeout(t);
  }, [highlight, value, readOnly]);

  if (!window.CodeMirror) {
    return (
      <textarea
        value={value != null ? value : ''}
        onChange={(e) => onChange && onChange(e.target.value)}
        readOnly={readOnly}
        spellCheck={false}
        style={{ width: '100%', height: '100%', background: 'transparent', color: 'var(--text-standard)', border: 'none', outline: 'none', padding: 12, fontSize: 12.5, fontFamily: 'inherit', resize: 'none' }}
      />
    );
  }
  return <div ref={hostRef} style={{ height: '100%', minHeight: 0 }} />;
}

function TrailEditor({ trail, go }) {
  const detail = TB.useTrailDetail(trail && trail.id);
  const [text, setText] = React.useState(null);
  const [baseline, setBaseline] = React.useState(null);
  const [busy, setBusy] = React.useState(null);
  const [note, setNote] = React.useState(null);

  React.useEffect(() => { setText(null); setBaseline(null); setNote(null); }, [trail && trail.id]);

  const yaml = detail.data && detail.data.yaml;
  React.useEffect(() => {
    // detail keeps the previous trail's data for a render while refetching —
    // without the loading guard a fast trail switch adopts the stale yaml.
    if (yaml != null && text === null && !detail.loading) { setText(yaml); setBaseline(yaml); }
  }, [yaml, text === null, detail.loading]);

  const dirty = text != null && text !== baseline;

  const save = async () => {
    if (text == null || busy) return false;
    setBusy('save'); setNote(null);
    const r = await TB.updateTrail(trail.id, text);
    setBusy(null);
    if (r.success) { setBaseline(text); setNote({ ok: true, msg: 'Saved ' + (r.savedPath || '') }); return true; }
    setNote({ ok: false, msg: r.error || 'Save failed' });
    return false;
  };

  if (detail.loading && text === null) {
    return <div className="tb-card" style={{ padding: '14px 16px' }}><Skeleton rows={8} /></div>;
  }
  if (yaml == null && text === null) {
    return <EmptyState ico="code" title="Couldn't load YAML" sub="This trail's file could not be read for editing." />;
  }

  return (
    <div style={{ display: 'flex', gap: 16, height: '100%', minHeight: 0 }}>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div className="tb-editor" style={{ flex: 1, minHeight: 0 }}>
          <CodeEditor value={text} onChange={setText} onSave={save} serverLint={(t) => TB.validateTrail(t)} mode="yaml" />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: '0 0 auto' }}>
          {dirty
            ? <Chip tone="amber">Unsaved changes</Chip>
            : <Chip>Saved</Chip>}
          {note && <span style={{ fontSize: 12, color: note.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0 }}>{note.msg}</span>}
          <div style={{ flex: 1 }} />
          <Btn sm ico="pencil" title="Open the .trail.yaml in your external editor" onClick={() => TB.openTrailInEditor(trail.id)}>Open in editor</Btn>
          <Btn sm ico="save" onClick={save} disabled={!dirty || !!busy}>{busy === 'save' ? 'Saving…' : 'Save'}</Btn>
        </div>
      </div>
    </div>
  );
}

// The YAML lines for one tool at `itemIndent`: a `- toolId:` list item with each param on its own
// `<param>: <type>` line, or the compact `- toolId: {}` form when the tool takes no params.
function toolBlockLines(tool, itemIndent) {
  const pad = ' '.repeat(itemIndent);
  const ps = tool.parameters || [];
  if (!ps.length) return [`${pad}- ${tool.id}: {}`];
  const paramPad = ' '.repeat(itemIndent + 4);
  return [`${pad}- ${tool.id}:`, ...ps.map((p) => `${paramPad}${p.name}: <${p.type || 'value'}>`)];
}

// Insert a tool into a recording trail.yaml, landing it in the right structural place rather than at
// the raw cursor: find the `- step:`/`- verify:` block the cursor sits in (else the last step in the
// file), then append the tool under that step's `recording: → tools:` list — creating the
// `recording:` and `tools:` rows if the step doesn't have them yet. Returns { text, line, ch } for the
// new cursor (parked on the first param to fill), or null when there's no step to attach to (the
// caller then does a plain cursor insert).
function insertToolIntoTrailYaml(text, cursorLine, tool) {
  const lines = text.split('\n');
  const indentOf = (s) => s.length - s.replace(/^\s+/, '').length;
  const blank = (s) => s.trim() === '';
  const isStep = (s) => /^\s*-\s*(step|verify|prompt)\s*:/.test(s);
  const isItem = (s) => /^\s*-/.test(s);

  const findStep = (from) => { for (let i = Math.min(from, lines.length - 1); i >= 0; i--) if (isStep(lines[i])) return i; return -1; };
  let stepIdx = findStep(cursorLine);
  if (stepIdx < 0) stepIdx = findStep(lines.length - 1);
  if (stepIdx < 0) return null;

  const stepIndent = indentOf(lines[stepIdx]);
  const childIndent = stepIndent + 2;
  // The step block runs until the next non-blank line indented at or above the step marker.
  let blockEnd = lines.length;
  for (let i = stepIdx + 1; i < lines.length; i++) {
    if (blank(lines[i])) continue;
    if (indentOf(lines[i]) <= stepIndent) { blockEnd = i; break; }
  }

  let recIdx = -1, toolsIdx = -1;
  for (let i = stepIdx + 1; i < blockEnd; i++) {
    if (recIdx < 0 && /^\s*recording\s*:/.test(lines[i])) recIdx = i;
    if (/^\s*tools\s*:/.test(lines[i])) { toolsIdx = i; break; }
  }

  // The last non-blank line of a sub-block [start+1, end), so we append after real content (not
  // into a trailing blank line).
  const lastContent = (start, end) => { let last = start; for (let i = start + 1; i < end; i++) if (!blank(lines[i])) last = i; return last; };

  let insertAt, blockLines;
  if (toolsIdx >= 0) {
    const toolsIndent = indentOf(lines[toolsIdx]);
    let end = blockEnd, firstItemIndent = -1;
    for (let i = toolsIdx + 1; i < blockEnd; i++) {
      if (blank(lines[i])) continue;
      const ind = indentOf(lines[i]);
      const inBlock = ind > toolsIndent || (ind === toolsIndent && isItem(lines[i]));
      if (!inBlock) { end = i; break; }
      if (isItem(lines[i]) && firstItemIndent < 0) firstItemIndent = ind;
    }
    const itemIndent = firstItemIndent >= 0 ? firstItemIndent : toolsIndent;
    insertAt = lastContent(toolsIdx, end) + 1;
    blockLines = toolBlockLines(tool, itemIndent);
  } else if (recIdx >= 0) {
    const toolsIndent = indentOf(lines[recIdx]) + 2;
    insertAt = lastContent(recIdx, blockEnd) + 1;
    blockLines = [`${' '.repeat(toolsIndent)}tools:`, ...toolBlockLines(tool, toolsIndent)];
  } else {
    const toolsIndent = childIndent + 2;
    insertAt = lastContent(stepIdx, blockEnd) + 1;
    blockLines = [`${' '.repeat(childIndent)}recording:`, `${' '.repeat(toolsIndent)}tools:`, ...toolBlockLines(tool, toolsIndent)];
  }

  const out = lines.slice(0, insertAt).concat(blockLines, lines.slice(insertAt));
  // Park the cursor on the first param line (or the tool line when paramless).
  const toolLineOffset = blockLines.findIndex((l) => /-\s*\S+\s*:/.test(l));
  const paramOffset = blockLines.findIndex((l, i) => i > toolLineOffset && /\S+\s*:/.test(l));
  const cursorOffset = paramOffset >= 0 ? paramOffset : Math.max(0, toolLineOffset);
  const line = insertAt + cursorOffset;
  return { text: out.join('\n'), line, ch: (out[line] || '').length };
}

// Coarse category for a tool, used to group the palette into sections. Derived from the id + flavor
// (there's no category in the catalog DTO) — assertions and actions are the buckets that matter to an
// author; the rest fall into Waits / Device / Setup / Scripted / Other. Order matters: more specific
// checks (assertions, waits, device) run before the broad UI-action / setup-fixture keyword sweeps.
function toolCategory(t) {
  const id = (t.id || '').toLowerCase();
  if (t.flavor === 'scripted') return 'Scripted';
  if (/assert|verify|expect/.test(id)) return 'Assertions';
  if (/wait|idle|sleep/.test(id)) return 'Waits';
  if (/^(android|ios)_|^adb|broadcast|systemui|appops|grantpermission/.test(id)) return 'Device';
  // UI interaction primitives.
  if (/tap|press|swipe|scroll|fling|drag|input|type|erase|text|fill|hover|paste|clipboard|launch|open|navigate|click|select|\bkey|back|hide|focus|long|double|hold|clear|enter/.test(id)) return 'Actions';
  // App/domain setup + fixtures (accounts, logins, seeding, teardown) — high-level flows, not UI taps.
  if (/account|login|signin|sign_in|signup|logout|teardown|setup|fund|merchant|customer|catalog|subscription|staging|provision|seed|ensure|enterpin|create|generate|register|onboard|pin/.test(id)) return 'Setup';
  return 'Other';
}
const TOOL_CATEGORY_ORDER = ['Actions', 'Assertions', 'Waits', 'Setup', 'Device', 'Scripted', 'Other'];
// Chip tone per category, so the popover's category tag carries the same color cue as the palette
// groups (warm/cool separation, ch09): actions cool-blue, assertions green, waits/amber, setup purple.
const TOOL_CATEGORY_TONE = { Actions: 'blue', Assertions: 'green', Waits: 'amber', Setup: 'purple' };

// Tool docs popover for the palette. Two things the inline version got wrong: it could run off the
// bottom of the window, and it was visually cramped. This measures itself after mount and clamps to
// the viewport (preferring the left of the hovered row, flipping right when there's no room), and
// lays the content out in clear zones — header (name + category), description, a parameters table,
// and an insert hint — with real breathing room between them.
function ToolDocPopover({ tool, rect }) {
  useLucide();
  const ref = React.useRef(null);
  const [pos, setPos] = React.useState(null);
  const W = 340;
  React.useLayoutEffect(() => {
    const el = ref.current; if (!el || !rect) return;
    const M = 12, GAP = 12;
    const w = el.offsetWidth || W, h = el.offsetHeight;
    // Horizontal: sit to the left of the row; flip to the right side when that would clip the left
    // edge, then clamp so neither edge leaves the window.
    let left = rect.left - GAP - w;
    if (left < M) left = rect.right + GAP;
    if (left + w > window.innerWidth - M) left = Math.max(M, window.innerWidth - M - w);
    // Vertical: align with the row, then pull the whole card up so its bottom stays on-screen.
    let top = rect.top;
    if (top + h > window.innerHeight - M) top = window.innerHeight - M - h;
    if (top < M) top = M;
    setPos({ left, top });
  }, [tool, rect]);
  const cat = toolCategory(tool);
  const params = tool.parameters || [];
  return (
    <div ref={ref} className="tb-pop" style={{
      position: 'fixed',
      left: pos ? pos.left : (rect ? Math.max(12, rect.left - 12 - W) : 0),
      top: pos ? pos.top : (rect ? rect.top : 0),
      width: W, zIndex: 90, pointerEvents: 'none',
      background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 12,
      boxShadow: '0 18px 50px rgba(8,10,22,.55)', padding: '15px 17px',
      maxHeight: 'calc(100vh - 24px)', overflow: 'hidden',
      visibility: pos ? 'visible' : 'hidden',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="tb-mono" style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-standard)', wordBreak: 'break-word', flex: 1, minWidth: 0 }}>{tool.id}</span>
        {cat && <Chip tone={TOOL_CATEGORY_TONE[cat] || ''}>{cat}</Chip>}
      </div>
      {tool.trailmap && <div className="tb-sub" style={{ fontSize: 11, marginTop: 4 }}>{tool.trailmap}{tool.flavor ? ' · ' + tool.flavor : ''}</div>}
      {tool.llmDescription && <div style={{ fontSize: 12.5, lineHeight: 1.6, marginTop: 12, color: 'var(--text-subtle-variant)' }}>{tool.llmDescription}</div>}
      {params.length > 0 && (
        <div style={{ marginTop: 14, paddingTop: 13, borderTop: '1px solid var(--tb-hairline)' }}>
          <div className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 8 }}>Parameters</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 14px', alignItems: 'baseline' }}>
            {params.map((p) => (
              <React.Fragment key={p.name}>
                <span className="tb-mono" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>{p.name}</span>
                <span className="tb-mono tb-sub" style={{ fontSize: 11 }}>{p.type || ''}</span>
              </React.Fragment>
            ))}
          </div>
        </div>
      )}
      <div className="tb-sub" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--tb-hairline)' }}>
        <Ico n="mouse-pointer-click" s={12} /> Click to insert
      </div>
    </div>
  );
}

// The shared "Edit" surface used by the trail detail view (Trails screen inline + Drafts popup):
// a YAML editor with live trail validation, a soft-wrap toggle, its own Save, and a tools browser
// (hover a tool for its docs, click to insert at the cursor). Content + save are supplied by the
// caller so the same editor works for a workspace trail and a draft file. `dirtyRef` lets a host
// (the drafts drawer) check for unsaved edits before closing.
// Best-effort read of `config.target` / `config.platform` from raw trail YAML, so the trail schema can
// be scoped to the trail's target without parsing the whole doc. Regex, not a YAML parse, on purpose:
// it must never throw on half-typed content, and the first top-level `target:`/`platform:` is the config.
function parseTrailTargetPlatform(yaml) {
  // Read the FULL scalar value (to end of line), not a restricted char class — a web trail's `target:`
  // is a URL (`https://…`) that a `[A-Za-z0-9_.-]+` class would truncate, silently mis-scoping the
  // schema to the whole catalog. Strip surrounding quotes; for unquoted values strip a trailing YAML
  // ` # comment`.
  const field = (name) => {
    const m = yaml && yaml.match(new RegExp('(^|\\n)\\s*' + name + ':\\s*(.+)'));
    if (!m) return null;
    let v = m[2].trim();
    const q = v.charAt(0);
    if ((q === '"' || q === "'") && v.charAt(v.length - 1) === q) {
      v = v.slice(1, -1);
    } else {
      const c = v.indexOf(' #'); // trailing inline comment (unquoted scalars only)
      if (c >= 0) v = v.slice(0, c).trim();
    }
    return v || null;
  };
  return { target: field('target'), platform: field('platform'), driver: field('driver') };
}

// 0-based inclusive [start,end] line range of the step block whose `- step:`/`- verify:` line carries
// `needle`, or null. Mirrors the flash-target logic CodeEditor uses, extracted so the Monaco path can
// carry over the board-cell → YAML jump.
function findStepLineRange(doc, needle) {
  if (!doc || !needle) return null;
  const lines = doc.split('\n');
  const isStep = (s) => /^\s*-\s*(step|verify|prompt)\s*:/.test(s);
  const key = String(needle).trim().slice(0, 48);
  let start = lines.findIndex((l) => isStep(l) && l.includes(key));
  if (start < 0) start = lines.findIndex((l) => l.includes(key));
  if (start < 0) return null;
  let end = lines.length - 1;
  for (let i = start + 1; i < lines.length; i++) { if (isStep(lines[i])) { end = i - 1; break; } }
  return { start0: start, end0: end };
}

// Monaco-backed `.trail.yaml` editor (schema-driven autocomplete/validation via yaml-language-server),
// the trail counterpart to MonacoToolEditor. Exposes the same `apiRef` insert surface the tools palette
// drives, so palette clicks keep inserting into the step's `recording: → tools:` block. Carries over the
// two Edit-tab behaviors the language server doesn't provide: the server-side SEMANTIC lint (as markers
// under a distinct owner, alongside the schema diagnostics) and the board-cell → YAML flash. Falls back
// to CodeMirror if Monaco's CDN load or the mount fails. Schema is scoped at mount to the trail's target
// (open-time scoping — editing config.target re-scopes on the next mount).
function MonacoTrailEditor({ value, onChange, onSave, target, platform, driver, readOnly, apiRef, wrap, highlight }) {
  const hostRef = React.useRef(null);
  const handleRef = React.useRef(null);
  const flashedRef = React.useRef(null);
  const [failed, setFailed] = React.useState(false);
  const cbRef = React.useRef({ onChange, onSave });
  cbRef.current = { onChange, onSave };
  React.useEffect(() => {
    let disposed = false;
    setFailed(false);
    window.TBMonaco.mountTrailYaml({
      host: hostRef.current,
      value: value != null ? value : '',
      target, platform, driver, readOnly, wrap,
      onChange: (t) => cbRef.current.onChange && cbRef.current.onChange(t),
      onSave: () => cbRef.current.onSave && cbRef.current.onSave(),
    }).then((h) => {
      if (disposed) { h.dispose(); return; }
      handleRef.current = h;
      if (apiRef) {
        apiRef.current = {
          insert: (text) => h.insertAtCursor(text),
          insertTool: (tool) => {
            const res = insertToolIntoTrailYaml(h.getValue(), h.getCursorLine(), tool);
            if (!res) { h.insertAtCursor(toolSnippet(tool)); return; }
            h.applyFullTextWithCursor(res.text, res.line, res.ch);
          },
        };
      }
    }).catch((e) => {
      if (window.console) console.warn('[MonacoTrailEditor] mount failed, falling back to CodeMirror:', e);
      if (!disposed) setFailed(true);
    });
    return () => { disposed = true; if (handleRef.current) { handleRef.current.dispose(); handleRef.current = null; } };
  }, [target, platform, driver, readOnly]);
  React.useEffect(() => {
    const h = handleRef.current;
    if (h && value != null && h.getValue() !== value) h.setValue(value);
  }, [value]);
  // Apply the soft-wrap toggle to the Monaco editor (parity with the CodeMirror path's wrap toggle).
  React.useEffect(() => {
    const h = handleRef.current;
    if (h && h.setWrap) h.setWrap(!!wrap);
  }, [wrap]);
  // Server-side semantic lint (beyond what the schema validates) → markers. Debounced; skips the network
  // check when the client-side YAML is unparseable (the schema already flags syntax). Coexists with the
  // language server's schema diagnostics (distinct marker owner).
  React.useEffect(() => {
    if (readOnly) return undefined;
    let cancelled = false;
    const t = setTimeout(async () => {
      const h = handleRef.current;
      if (!h || value == null) return;
      if (window.jsyaml) { try { window.jsyaml.loadAll(value); } catch (_) { return; } }
      const r = await TB.validateTrail(value).catch(() => null);
      if (cancelled || !r) return;
      const markers = r.unavailable
        ? [{ line0: 0, message: 'Trail validation unavailable (server check failed)', severity: 'warning' }]
        : (r.errors || []).map((e) => ({ line0: Math.max(0, (e.line || 1) - 1), message: e.message, severity: 'error' }));
      h.setMarkers(markers);
    }, 600);
    return () => { cancelled = true; clearTimeout(t); };
  }, [value, readOnly]);
  // Board-cell → YAML jump + flash, keyed by the highlight target's identity (fires once per click).
  React.useEffect(() => {
    const h = handleRef.current;
    if (!h || !highlight || !highlight.text || flashedRef.current === highlight) return;
    const range = findStepLineRange(h.getValue(), highlight.text);
    flashedRef.current = highlight;
    if (range) h.revealAndFlashLines(range.start0, Math.min(range.end0, range.start0 + 10));
  }, [highlight, value]);
  if (failed) return <CodeEditor value={value} onChange={onChange} onSave={onSave} serverLint={(t) => TB.validateTrail(t)} mode="yaml" readOnly={readOnly} wrap={wrap} apiRef={apiRef} highlight={highlight} />;
  return <div ref={hostRef} style={{ height: '100%', minHeight: 0 }} />;
}

function TrailYamlEditor({ content, editable = true, tools, onSave, onSaved, dirtyRef, highlight, resetKey }) {
  useLucide();
  const [text, setText] = React.useState(null);
  const [baseline, setBaseline] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [note, setNote] = React.useState(null);
  const [toolQuery, setToolQuery] = React.useState('');
  const [hoverTool, setHoverTool] = React.useState(null);
  const [wrap, setWrap] = useStickyState('tb-yaml-wrap', true);
  const editorApi = React.useRef(null);
  // Mirror the latest text/baseline into refs so the content-sync effect can read them without
  // re-subscribing to every keystroke.
  const textRef = React.useRef(null); textRef.current = text;
  const baselineRef = React.useRef(null); baselineRef.current = baseline;
  // (Re)seed from `content` on mount AND whenever it changes — switching trails in the sidebar, or an
  // external reload — but NEVER clobber unsaved edits. Previously this seeded only once (`text === null`),
  // so switching trails left the prior trail's YAML in the editor (read as "the detail doesn't update
  // unless you're on the first tab", since only the Edit tab was stale).
  React.useEffect(() => {
    if (content == null) return;
    const dirtyNow = textRef.current !== null && textRef.current !== baselineRef.current;
    if (!dirtyNow) { setText(content); setBaseline(content); }
  }, [content]);
  // Discard-on-switch: when the parent swaps to a DIFFERENT trail/file (`resetKey` changes), reset the
  // editor to the incoming content even if there were unsaved edits — this replaces the `key={id}` remount
  // that used to discard the prior trail's state on switch, now that Monaco + the LSP socket are reused
  // instead of torn down. A background reload of the SAME trail keeps `resetKey` stable, so the
  // dirty-preserving effect above still guards real edits. Skips the initial run (mount already seeds above).
  const firstResetRef = React.useRef(true);
  React.useEffect(() => {
    if (firstResetRef.current) { firstResetRef.current = false; return; }
    setText(content); setBaseline(content); setNote(null);
    // Also clear per-file editor-surface state so a switch fully resets: a stale palette filter can make
    // the new file's tool list look empty, and a hover popover can get stuck (unmounting the hovered row
    // never fires onMouseLeave).
    setToolQuery(''); setHoverTool(null);
  }, [resetKey]);
  const dirty = editable && text != null && text !== baseline;
  if (dirtyRef) dirtyRef.current = dirty;
  const save = async () => {
    if (!editable || text == null || busy) return;
    setBusy(true); setNote(null);
    const r = await onSave(text);
    setBusy(false);
    if (r && r.success) { setBaseline(text); setNote({ ok: true, msg: 'Saved' }); onSaved && onSaved(); }
    else setNote({ ok: false, msg: (r && r.error) || 'Save failed' });
  };
  // Scope the trail schema to the trail's target. Derived from `content` (the loaded baseline), not the
  // live `text`, so typing in config.target doesn't remount the editor on every keystroke — open-time
  // scoping; re-opening the trail picks up a changed target.
  const cfg = parseTrailTargetPlatform(content);
  const palette = editable && (tools || []).length > 0;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 0 10px', flex: '0 0 auto' }}>
        {editable && (dirty ? <Chip tone="amber">Unsaved</Chip> : <Chip>Saved</Chip>)}
        {note && <span style={{ fontSize: 12, color: note.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)' }}>{note.msg}</span>}
        <span style={{ flex: 1 }} />
        <span role="button" data-testid="wrap-toggle" title={wrap ? 'Wrap long lines: on' : 'Wrap long lines: off'} onClick={() => setWrap((w) => !w)}
          style={{ display: 'inline-flex', alignItems: 'center', cursor: 'pointer', padding: '4px 6px', borderRadius: 7, border: '1px solid ' + (wrap ? 'rgba(94,155,255,.5)' : 'var(--tb-hairline)'), background: wrap ? 'rgba(94,155,255,.12)' : 'transparent', color: wrap ? 'var(--tb-running)' : 'var(--text-subtle)' }}>
          <Ico n="wrap-text" s={15} />
        </span>
        {editable && <Btn sm kind="primary" ico={busy ? 'loader-2' : 'save'} spin={busy} onClick={save} disabled={!dirty || busy}>Save</Btn>}
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg-standard)' }}>
        <div className="tb-editor" style={{ flex: 1, minHeight: 0, minWidth: 0, border: 'none', borderRadius: 0 }}>
          {content === null
            ? <div className="tb-sub" style={{ padding: 16 }}>Loading…</div>
            : (window.TBMonaco && window.TBMonaco.mountTrailYaml
              ? <MonacoTrailEditor value={text != null ? text : content} onChange={editable ? setText : undefined} onSave={save} target={cfg.target} platform={cfg.platform} driver={cfg.driver} readOnly={!editable} wrap={wrap} apiRef={editorApi} highlight={highlight} />
              : <CodeEditor value={text != null ? text : content} onChange={editable ? setText : undefined} onSave={save} serverLint={(t) => TB.validateTrail(t)} mode="yaml" readOnly={!editable} wrap={wrap} apiRef={editorApi} highlight={highlight} />)}
        </div>
        {palette && (
          <div style={{ width: 280, flex: '0 0 auto', borderLeft: '1px solid var(--tb-hairline)', display: 'flex', flexDirection: 'column', minHeight: 0, background: 'var(--bg-subtle)' }}>
            <div style={{ padding: '9px 12px', borderBottom: '1px solid var(--tb-hairline)' }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 7 }}>Tools</div>
              <input value={toolQuery} onChange={(e) => setToolQuery(e.target.value)} placeholder="Filter tools…"
                style={{ width: '100%', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 7, outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 12.5, padding: '5px 8px' }} />
            </div>
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 6 }}>
              {(() => {
                const q = toolQuery.toLowerCase();
                const matches = tools.filter((t) => !q || t.id.toLowerCase().includes(q));
                if (matches.length === 0) return <div className="tb-sub" style={{ fontSize: 12, padding: 8 }}>No matching tools.</div>;
                const groups = {};
                matches.forEach((t) => { const c = toolCategory(t); (groups[c] = groups[c] || []).push(t); });
                const order = TOOL_CATEGORY_ORDER.filter((c) => groups[c]);
                return order.map((cat) => (
                  <div key={cat} style={{ marginBottom: 6 }}>
                    <div className="tb-eyebrow" style={{ fontSize: 10, padding: '6px 8px 4px', position: 'sticky', top: 0, background: 'var(--bg-subtle)', zIndex: 1 }}>{cat} <span style={{ color: 'var(--text-subtle)', fontWeight: 600 }}>{groups[cat].length}</span></div>
                    {groups[cat].map((t) => (
                      <div key={t.id} role="button" onClick={() => editorApi.current && editorApi.current.insertTool(t)}
                        style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '6px 8px', borderRadius: 7, cursor: 'pointer' }}
                        onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--bg-prominent)'; setHoverTool({ tool: t, rect: e.currentTarget.getBoundingClientRect() }); }}
                        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; setHoverTool((h) => (h && h.tool.id === t.id ? null : h)); }}>
                        <Ico n="wrench" s={12} c="var(--text-subtle)" style={{ marginTop: 2, flex: '0 0 auto' }} />
                        <div style={{ minWidth: 0 }}>
                          <div className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-standard)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t.id}</div>
                          {(t.parameters || []).length > 0 && <div className="tb-mono tb-sub" style={{ fontSize: 10.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t.parameters.map((p) => p.name).join(', ')}</div>}
                        </div>
                      </div>
                    ))}
                  </div>
                ));
              })()}
            </div>
          </div>
        )}
      </div>
      {hoverTool && hoverTool.tool && ReactDOM.createPortal(
        <ToolDocPopover tool={hoverTool.tool} rect={hoverTool.rect} />, document.body)}
    </div>
  );
}

Object.assign(window, { CodeEditor, TrailEditor, TrailYamlEditor });

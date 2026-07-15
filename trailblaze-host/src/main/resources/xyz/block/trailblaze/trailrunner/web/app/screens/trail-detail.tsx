// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Platform buckets for grouping the variant list. Known platforms render via the shared
// PlatformGlyph (Apple / Android robot / globe); only the catch-all `other` carries its own icon.
const VARIANT_PLAT = {
  android: { label: 'Android' },
  ios: { label: 'iOS' },
  web: { label: 'Web' },
  other: { label: 'Other', ico: 'file-text' },
};
function prettyVariant(stem) {
  const special = { ios: 'iOS', ipad: 'iPad', iphone: 'iPhone' };
  return stem.split(/[-_]/).filter(Boolean)
    .map((s) => special[s.toLowerCase()] || (s.charAt(0).toUpperCase() + s.slice(1)))
    .join(' ');
}
function VariantsMode({ variants, currentId, onSelect, openRun }) {
  useLucide();
  const stemOf = (p) => (p || '').split('/').pop().replace(/\.trail\.yaml$/, '');
  const groups = {};
  variants.forEach((v) => { const p = VARIANT_PLAT[v.platform] ? v.platform : 'other'; (groups[p] = groups[p] || []).push(v); });
  const order = ['android', 'ios', 'web', 'other'].filter((p) => groups[p]);
  return (
    <div style={{ maxWidth: 680 }}>
      {order.map((p) => (
        <div key={p} style={{ marginBottom: 18 }}>
          <div className="tb-eyebrow" style={{ color: 'var(--tb-running)', marginBottom: 8 }}>{VARIANT_PLAT[p].label}</div>
          {groups[p].map((v) => {
            const on = v.id === currentId;
            const stem = stemOf(v.path);
            return (
              <div key={v.id} className="tb-card" {...clickable(() => onSelect(v.id))}
                style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', marginBottom: 8, cursor: 'pointer', border: on ? '1px solid var(--tb-primary-green)' : '1px solid var(--tb-hairline)' }}>
                {p === 'other'
                  ? <Ico n={VARIANT_PLAT.other.ico} s={20} c="var(--text-subtle-variant)" />
                  : <PlatformGlyph platform={p} s={20} c="var(--text-subtle-variant)" />}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 600 }}>{prettyVariant(stem)}</div>
                  <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{stem}.trail.yaml</div>
                </div>
                {on
                  ? <Chip tone="green">current</Chip>
                  : <span {...clickable((e) => { e.stopPropagation(); openRun(v); })} aria-label="Run this variant" title="Run this variant" style={{ cursor: 'pointer', display: 'inline-flex', flex: '0 0 auto' }}><Ico n="play" s={16} c="var(--text-subtle)" /></span>}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}


function parseTrailSteps(yaml) {
  try {
    if (!window.jsyaml) return null;
    const doc = window.jsyaml.load(yaml);
    if (!Array.isArray(doc)) return null;
    const mapTools = (rec) => {
      const tools = [];
      for (const t of rec || []) {
        if (t && typeof t === 'object') { const name = Object.keys(t)[0]; tools.push({ name, args: t[name] }); }
        else if (typeof t === 'string') tools.push({ name: t, args: null });
      }
      return tools;
    };
    // Walk the doc ONCE in file order: `- trailhead:` (the deterministic step 0), `- prompts:`
    // blocks, and root-level `- tools:` items (recorded steps the engine force-executes) all
    // become steps. Dropping any of them made recorded trails read as empty.
    const entries = [];
    for (const it of doc) {
      if (!it || typeof it !== 'object') continue;
      if (it.trailhead != null && typeof it.trailhead === 'object' && !Array.isArray(it.trailhead)) entries.push({ ...it.trailhead, __trailhead: true });
      else if (Array.isArray(it.prompts)) entries.push(...it.prompts);
      else if (Array.isArray(it.tools)) entries.push({ __tools: it.tools });
    }
    if (!entries.length) return null;
    return entries.map((pr) => {
      if (pr.__tools) return { kind: 'step', text: '', recorded: true, tools: mapTools(pr.__tools) };
      const kind = pr.__trailhead ? 'trailhead' : pr.verify != null ? 'verify' : 'step';
      const text = pr.step || pr.verify || pr.prompt || '';
      const rec = pr.__trailhead
        ? (Array.isArray(pr.tools) ? pr.tools : null)
        : (pr.recording && Array.isArray(pr.recording.tools) ? pr.recording.tools : null);
      return { kind, text: String(text), recorded: !!rec, tools: mapTools(rec) };
    });
  } catch (e) {
    return null;
  }
}

function fmtArgValue(v) {
  if (v == null) return 'null';
  if (typeof v === 'object') return truncate(JSON.stringify(v), 100);
  return truncate(String(v), 100);
}

// The unified single-file trail format is a MAP with `config` / `trailhead` / `trail` keys — NL once
// per step, per-platform recordings inline (`recording: { <platform>: … }`), one file per case. This
// replaced the legacy per-platform `- prompts:` list that parseTrailSteps handles.
//
// Lower a parsed StepMatrix (from the unit-tested trail-model.js — the ONE unified parser) into the
// read-only table's { platforms, rows, skip } shape. Returns null when there are no rows to show
// (the caller falls back to the legacy "No steps" state).
function unifiedTableFromMatrix(m) {
  if (!m) return null;
  const rows = [];
  let stepNo = 0;
  const addRow = (s, isTrailhead) => {
    const byPlatform = {};
    let recorded = 0;
    Object.keys(s.recording || {}).forEach((p) => {
      const tools = (s.recording[p] || []).map((t) => ({ [t.name]: t.body }));
      byPlatform[p] = tools;
      recorded += tools.length;
    });
    rows.push({ kind: s.kind, idx: isTrailhead ? null : stepNo++, text: s.text, byPlatform, recorded });
  };
  if (m.trailhead) addRow(m.trailhead, true);
  (m.steps || []).forEach((s) => addRow(s, false));
  if (!rows.length) return null;
  return { platforms: m.platforms, rows, skip: (m.config && m.config.skip) || {} };
}

// yaml -> table data, or null when the YAML isn't the unified shape / has no rows.
function parseUnifiedTrail(yaml) {
  return unifiedTableFromMatrix(parseUnifiedModel(yaml));
}

// ── unified editable model ──
// The parse/serialize logic lives in the pure, unit-tested app/screens/trail-model.js (window.TM), so
// it round-trips through the single file identically in the browser and under `bun test`. These
// wrappers just move js-yaml I/O to the edge: parse a file into the editable StepMatrix model (config
// kept verbatim; steps' recordings bound BY IDENTITY so reorder/rename carries them), and serialize a
// model back to YAML. Returns null when the YAML isn't the unified shape (caller falls back).
function parseUnifiedModel(yaml) {
  if (!window.jsyaml) return null;
  let doc;
  try { doc = window.jsyaml.load(yaml); } catch (e) { return null; }
  return window.TM.unifiedDocToMatrix(doc);
}
function serializeUnifiedModel(model) {
  return window.jsyaml.dump(window.TM.matrixToUnifiedDoc(model), { lineWidth: -1, noRefs: true }).trimEnd();
}

// Wrap a [{ toolName: body }] list into the minimal trail YAML runToolQuick executes (config +
// one prompt whose recording is those tools) — the same shape the trailmaps "Run" tab uses.
function buildToolRunYaml(tools) {
  const lines = ['- config:', '    title: "Run tool"', '- prompts:', '  - step: "Run tool"', '    recording:', '      tools:'];
  const dumped = (window.jsyaml ? window.jsyaml.dump(tools, { lineWidth: -1 }) : '').replace(/\n+$/, '');
  dumped.split('\n').forEach((l) => lines.push(l ? '      ' + l : l));
  return lines.join('\n');
}

// Base platform for glyph/label lookup ('android-phone' -> 'android', 'ios-iphone' -> 'ios').
function platformBase(p) { return String(p || '').split(/[-_]/)[0].toLowerCase(); }
function platformLabel(p) { const b = platformBase(p); return (VARIANT_PLAT[b] && VARIANT_PLAT[b].label) || (b.charAt(0).toUpperCase() + b.slice(1)); }
// A new unified platform column defaults to the standard device key for its base platform (matches
// the device keys authored files use). The driver comes from steps-board's DEFAULT_DRIVER (read via
// window at call time — same classic-script convention as RecordingCell etc.) so the default driver
// per platform is stated exactly once.
const UNIFIED_DEFAULT_DEVICE_KEY = { android: 'android-phone', ios: 'ios-iphone', web: 'web' };

// Chip tone + label per step kind — shared by the read-only table, the editable board, and StepRow.
const STEP_KIND = { trailhead: ['green', 'TRAILHEAD'], verify: ['blue', 'VERIFY'], step: ['purple', 'STEP'] };

// Read-only steps × platforms table for a unified trail: each row is a step (trailhead + trail
// steps), each column a platform, each cell that platform's recorded tools (or an agent-step marker
// when nothing was recorded). Reuses RecordingCell (from steps-board) so cell styling matches the
// bundle matrix exactly.
function UnifiedStepsTable({ data }) {
  useLucide();
  const { platforms, rows, skip } = data;
  const cols = platforms.length ? platforms : ['—'];
  const gridColumns = `minmax(240px, 1.5fr) ${cols.map(() => 'minmax(240px, 1fr)').join(' ')}`;
  const skipKeys = Object.keys(skip || {});
  return (
    <div>
      <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, overflow: 'hidden', background: 'var(--bg-subtle)' }}>
        <div className="tb-board-scroll" style={{ overflowX: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: gridColumns, minWidth: 'min-content' }}>
            {/* header: step column + one per platform */}
            <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'center', gap: 6 }}>
              <Ico n="list" s={13} c="var(--tb-link)" />
              <span className="tb-eyebrow">Step</span>
            </div>
            {cols.map((p) => (
              <div key={p} style={{ padding: '10px 14px', borderBottom: '1px solid var(--tb-hairline-strong)', borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'center', gap: 8 }}>
                {p === '—' ? <Ico n="file-text" s={16} c="var(--text-subtle-variant)" /> : <PlatformGlyph platform={platformBase(p)} s={18} c="var(--text-subtle-variant)" />}
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{p === '—' ? 'Recording' : platformLabel(p)}</div>
                  {p !== '—' && <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p}</div>}
                </div>
              </div>
            ))}
            {/* one grid row per step */}
            {rows.map((r, i) => {
              const [tone, base] = STEP_KIND[r.kind] || STEP_KIND.step;
              const label = base + (r.idx != null ? ' ' + (r.idx + 1) : '');
              const notLast = i < rows.length - 1;
              const border = notLast ? '1px solid var(--tb-hairline)' : 'none';
              return (
                <React.Fragment key={i}>
                  <div style={{ padding: '12px 14px', borderBottom: border, background: 'var(--bg-standard)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
                      <Chip tone={tone}>{label}</Chip>
                      {r.recorded === 0 && <Chip><Ico n="sparkles" s={11} /> agent step</Chip>}
                    </div>
                    <div data-selectable style={{ fontSize: 13, lineHeight: 1.5 }}>{r.text}</div>
                  </div>
                  {cols.map((p) => {
                    const tools = r.byPlatform[p] || [];
                    return (
                      <div key={p} style={{ padding: '12px 14px', borderBottom: border, borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)' }}>
                        {tools.length ? <RecordingCell recording={{ tools }} /> : <span className="tb-sub" style={{ fontSize: 12, opacity: 0.5 }}>—</span>}
                      </div>
                    );
                  })}
                </React.Fragment>
              );
            })}
          </div>
        </div>
      </div>
      {skipKeys.length > 0 && (
        <div className="tb-sub" style={{ marginTop: 10, fontSize: 11.5, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {skipKeys.map((k) => (
            <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Ico n="circle-minus" s={12} c="var(--tb-amber)" />
              <span><b>{platformLabel(k)}</b> skipped: {String(skip[k])}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// Editable steps × platforms board for a unified single-file trail — the SAME matrix the legacy
// per-platform bundle showed (steps down the left, one column per platform), but backed by ONE file.
// Editing a step's text, toggling do/verify, reordering, adding/deleting a step, or editing a
// platform cell's tool calls all mutate the parsed model and write the whole file back via
// onSaveYaml. Recordings are bound to their step by identity, so reorder/rename carries them (no
// text-realignment guesswork the multi-file bundle needed). `target` scopes the tool-call editor's
// autocomplete. onSaveYaml(yaml) -> { success, error }.
function UnifiedStepsBoard({ yaml, target, onSaveYaml, catalog = [] }) {
  useLucide();
  const devices = TB.useDevices();
  const [model, setModel] = React.useState(() => parseUnifiedModel(yaml));
  const [editCell, setEditCell] = React.useState(null); // { rowKey, platform, tools, anchor }
  const [saving, setSaving] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const [hoveredRow, setHoveredRow] = React.useState(null);
  const [hoveredCol, setHoveredCol] = React.useState(null);
  const [addPlatOpen, setAddPlatOpen] = React.useState(false);
  const [dragStep, setDragStep] = React.useState(-1);
  const [dropStep, setDropStep] = React.useState(-1);
  // Re-derive from disk whenever the file changes (initial load, and after a save reloads it).
  React.useEffect(() => { setModel(parseUnifiedModel(yaml)); setErr(null); }, [yaml]);
  const savedYaml = React.useMemo(() => { const m = parseUnifiedModel(yaml); return m ? serializeUnifiedModel(m) : null; }, [yaml]);
  const curYaml = React.useMemo(() => (model ? serializeUnifiedModel(model) : null), [model]);
  const dirty = savedYaml != null && curYaml != null && curYaml !== savedYaml;

  if (!model) return <EmptyState ico="list" title="No steps" sub="This trail has no parsed steps, or couldn't be loaded." />;

  const platforms = model.platforms.length ? model.platforms : ['—'];
  const gridColumns = `minmax(260px, 1.5fr) ${platforms.map(() => 'minmax(240px, 1fr)').join(' ')}`;

  // ── mutations (immutable) ──
  const patchStep = (i, patch) => setModel((m) => ({ ...m, steps: m.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const patchTrailhead = (patch) => setModel((m) => ({ ...m, trailhead: { ...m.trailhead, ...patch } }));
  const addStep = () => setModel((m) => ({ ...m, steps: [...m.steps, { kind: 'step', text: '', recording: {}, extra: {} }] }));
  const delStep = (i) => setModel((m) => ({ ...m, steps: m.steps.filter((_, j) => j !== i) }));
  const moveStep = (i, dir) => setModel((m) => { const j = i + dir; if (j < 0 || j >= m.steps.length) return m; const n = [...m.steps]; [n[i], n[j]] = [n[j], n[i]]; return { ...m, steps: n }; });
  const reorderStep = (from, to) => setModel((m) => { if (from == null || to == null || from === to) return m; const n = [...m.steps]; const [mv] = n.splice(from, 1); n.splice(to, 0, mv); return { ...m, steps: n }; });
  // Deleting every tool call UN-records the cell (the classifier key is dropped, so resolution can
  // fall back to a broader family recording or the agent). A pre-existing explicit `classifier: []`
  // no-op in the file round-trips untouched — only an edit of that very cell rewrites it.
  const applyCellTools = (m, rowKey, platform, tools) => {
    const upd = (rec) => { const r = { ...rec }; if ((tools || []).length) r[platform] = tools; else delete r[platform]; return r; };
    return rowKey === 'trailhead'
      ? { ...m, trailhead: { ...m.trailhead, recording: upd(m.trailhead.recording) } }
      : { ...m, steps: m.steps.map((s, j) => (j === rowKey ? { ...s, recording: upd(s.recording) } : s)) };
  };
  const openCell = (rowKey, platform, tools, ev) => setEditCell({ rowKey, platform, tools: (tools || []).slice(), anchor: ev.currentTarget.getBoundingClientRect() });
  // Add a platform column: register its device (key + default driver) in config.devices. Cells start
  // empty; recording per step happens by editing each cell.
  const addPlatform = (base) => setModel((m) => {
    const key = UNIFIED_DEFAULT_DEVICE_KEY[base];
    const driver = (window.DEFAULT_DRIVER || {})[base];
    if (!key || !driver || m.platforms.some((p) => platformBase(p) === base)) return m;
    return { ...m, platforms: [...m.platforms, key], config: { ...m.config, devices: { ...(m.config.devices || {}), [key]: driver } } };
  });
  // Remove a platform column: drop it from config.devices AND strip its recording from the trailhead
  // and every step. Reversible until Save (reload re-reads the file).
  const removePlatform = (key) => setModel((m) => {
    const devices = { ...(m.config.devices || {}) }; delete devices[key];
    const strip = (rec) => { const r = { ...rec }; delete r[key]; return r; };
    return {
      ...m,
      platforms: m.platforms.filter((p) => p !== key),
      config: { ...m.config, devices },
      trailhead: m.trailhead ? { ...m.trailhead, recording: strip(m.trailhead.recording) } : null,
      steps: m.steps.map((s) => ({ ...s, recording: strip(s.recording) })),
    };
  });
  const availBases = ['android', 'ios', 'web'].filter((b) => !model.platforms.some((p) => platformBase(p) === b));

  // Persist a specific model to the file. Takes the model explicitly (not `curYaml`, which lags a
  // just-applied setState) so an edit-then-save gesture writes the fresh state in one go.
  async function persist(nextModel) {
    if (!onSaveYaml || !nextModel) return;
    setSaving(true); setErr(null);
    try { const r = await onSaveYaml(serializeUnifiedModel(nextModel)); if (r && r.success === false) setErr(r.error || 'Could not save.'); }
    catch (e) { setErr(String((e && e.message) || e)); }
    setSaving(false);
  }
  const save = () => persist(model);
  // Dispatch a cell's tool calls to a connected device of that platform (like Run YAML) — resolve +
  // connect the device, then runToolQuick. Returns { ok, text } for the popover to display.
  async function runTools(toolsArray, platform, deviceId) {
    try {
      const resolved = await TB.resolveRunDevice({ platform: platformBase(platform) }, deviceId || null);
      if (resolved.error) return { ok: false, text: resolved.error };
      const connected = await TB.connectDevice(resolved.trailblazeDeviceId);
      if (!connected) return { ok: false, text: 'Could not connect to the device.' };
      const r = await TB.runToolQuick(buildToolRunYaml(toolsArray), resolved.trailblazeDeviceId);
      return { ok: r && r.success === true, text: (r && r.success === true) ? (r.result || 'Ran OK') : ((r && r.error) || 'Run failed') };
    } catch (e) { return { ok: false, text: String((e && e.message) || e) }; }
  }

  // one platform cell — click to edit that platform's tool calls for this row (trailhead or step)
  const renderCell = (rowKey, platform) => {
    if (platform === '—') return <span className="tb-sub" style={{ fontSize: 12, opacity: 0.5 }}>—</span>;
    const src = rowKey === 'trailhead' ? model.trailhead : model.steps[rowKey];
    const cell = src ? src.recording[platform] : undefined;
    const tools = cell || [];
    // A key present with an empty list is the explicit `classifier: []` no-op — render it as such,
    // not as "add tools", so the deliberate skip is visible in the matrix.
    return (
      <div role="button" onClick={(ev) => openCell(rowKey, platform, tools, ev)} title={tools.length ? 'Edit tool calls for this step' : 'Add tool calls for this step'} style={{ cursor: 'pointer' }}>
        {tools.length
          ? <RecordingCell recording={{ tools: tools.map((t) => ({ [t.name]: t.body })) }} />
          : cell
            ? <span className="tb-sub" style={{ fontSize: 12, opacity: 0.75, display: 'inline-flex', alignItems: 'center', gap: 5 }} title="Explicit no-op — this device class deliberately runs no tools for this step"><Ico n="ban" s={12} /> no-op</span>
            : <span className="tb-sub" style={{ fontSize: 12, opacity: 0.55, display: 'inline-flex', alignItems: 'center', gap: 5 }}><Ico n="plus" s={12} /> add tools</span>}
      </div>
    );
  };

  // one grid row (trailhead is pinned — no reorder/delete; steps drag/reorder and carry recordings)
  const renderRow = (rowKey) => {
    const isTh = rowKey === 'trailhead';
    const s = isTh ? model.trailhead : model.steps[rowKey];
    const [tone, base] = STEP_KIND[s.kind] || STEP_KIND.step;
    const label = isTh ? 'TRAILHEAD' : base + ' ' + (rowKey + 1);
    const isVerify = s.kind === 'verify';
    const rowId = isTh ? 'th' : rowKey;
    const hov = hoveredRow === rowId;
    const dropBorder = (!isTh && dropStep === rowKey && dragStep >= 0 && dragStep !== rowKey) ? '2px solid var(--tb-pass)' : '1px solid var(--tb-hairline)';
    const dragProps = !isTh && dragStep >= 0
      ? { onDragOver: (e) => { e.preventDefault(); setDropStep(rowKey); }, onDrop: (e) => { e.preventDefault(); reorderStep(dragStep, rowKey); setDragStep(-1); setDropStep(-1); } }
      : {};
    return (
      <React.Fragment key={rowId}>
        <div className="tb-board-pin" onMouseEnter={() => setHoveredRow(rowId)} onMouseLeave={() => setHoveredRow((h) => (h === rowId ? null : h))} {...dragProps}
          style={{ borderTop: dropBorder, padding: '10px 12px', background: 'var(--bg-standard)', display: 'flex', alignItems: 'flex-start', gap: 6, opacity: dragStep === rowKey ? 0.4 : 1 }}>
          {!isTh && (
            <span draggable title="Drag to reorder" aria-label="Drag to reorder step"
              onDragStart={(e) => { setDragStep(rowKey); if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(rowKey)); } }}
              onDragEnd={() => { setDragStep(-1); setDropStep(-1); }}
              style={{ cursor: 'grab', flex: '0 0 auto', marginTop: 4, color: 'var(--text-subtle)', opacity: hov ? 0.85 : 0.25, transition: 'opacity .12s ease', display: 'inline-flex' }}><Ico n="grip-vertical" s={14} /></span>
          )}
          <span onClick={isTh ? undefined : () => patchStep(rowKey, { kind: isVerify ? 'step' : 'verify' })} title={isTh ? undefined : 'Toggle do / verify'} style={{ cursor: isTh ? 'default' : 'pointer', flex: '0 0 auto', marginTop: 2 }}>
            <Chip tone={tone} style={{ minWidth: 66, justifyContent: 'center' }}>{label}</Chip>
          </span>
          <textarea value={s.text} onChange={(e) => (isTh ? patchTrailhead({ text: e.target.value }) : patchStep(rowKey, { text: e.target.value }))}
            rows={1} ref={(el) => { if (el) { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; } }} placeholder="Describe the step…"
            style={{ flex: 1, minWidth: 0, resize: 'none', overflow: 'hidden', background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 13, lineHeight: 1.5, paddingTop: 1 }} />
          {!isTh && (
            <span style={{ display: 'flex', gap: 1, flex: '0 0 auto', opacity: hov ? 0.8 : 0, transition: 'opacity .12s ease', pointerEvents: hov ? 'auto' : 'none' }}>
              <span {...clickable(() => moveStep(rowKey, -1))} aria-label="Move step up" title="Move up" style={{ cursor: 'pointer', opacity: rowKey === 0 ? 0.4 : 1 }}><Ico n="chevron-up" s={15} /></span>
              <span {...clickable(() => moveStep(rowKey, 1))} aria-label="Move step down" title="Move down" style={{ cursor: 'pointer', opacity: rowKey === model.steps.length - 1 ? 0.4 : 1 }}><Ico n="chevron-down" s={15} /></span>
              <span {...clickable(() => delStep(rowKey))} aria-label="Delete step" title="Delete step" style={{ cursor: 'pointer' }}><Ico n="x" s={15} /></span>
            </span>
          )}
        </div>
        {platforms.map((p) => (
          <div key={p} {...dragProps} style={{ borderTop: dropBorder, borderLeft: '1px solid var(--tb-hairline)', padding: '10px 12px', background: 'var(--bg-standard)', opacity: dragStep === rowKey ? 0.4 : 1 }}>
            {renderCell(rowKey, p)}
          </div>
        ))}
      </React.Fragment>
    );
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <Btn kind="primary" sm ico={saving ? 'loader-2' : 'save'} spin={saving} disabled={!dirty || saving} onClick={save}>Save</Btn>
        <span className="tb-sub" style={{ fontSize: 12, color: dirty ? 'var(--tb-amber)' : 'var(--text-subtle)' }}>{dirty ? 'Unsaved changes' : 'Saved'}</span>
        {err && <span style={{ fontSize: 12, color: 'var(--tb-fail)' }}>{err}</span>}
        <span style={{ flex: 1 }} />
        {availBases.length > 0 && (
          <span style={{ position: 'relative', display: 'inline-flex' }}>
            <Btn kind="ghost" sm ico="plus" onClick={() => setAddPlatOpen((o) => !o)}>Platform</Btn>
            {addPlatOpen && (
              <React.Fragment>
                <div style={{ position: 'fixed', inset: 0, zIndex: 40 }} onClick={() => setAddPlatOpen(false)} />
                <div className="tb-card" style={{ position: 'absolute', top: '100%', right: 0, marginTop: 6, zIndex: 41, minWidth: 168, padding: 6, background: 'var(--bg-elevated)', boxShadow: '0 14px 40px rgba(0,0,0,.4)' }}>
                  {availBases.map((b) => (
                    <div key={b} role="button" className="tb-menu-item" onClick={() => { addPlatform(b); setAddPlatOpen(false); }}
                      style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 10px', borderRadius: 7, cursor: 'pointer', fontSize: 13, color: 'var(--text-standard)' }}>
                      <PlatformGlyph platform={b} s={16} c="var(--text-subtle-variant)" /> {platformLabel(b)}
                    </div>
                  ))}
                </div>
              </React.Fragment>
            )}
          </span>
        )}
        <Btn kind="ghost" sm ico="plus" onClick={addStep}>Add step</Btn>
      </div>
      <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, overflow: 'hidden', background: 'var(--bg-subtle)' }}>
        <div className="tb-board-scroll" style={{ overflowX: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: gridColumns, minWidth: 'min-content' }}>
            <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'center', gap: 6 }}>
              <Ico n="list" s={13} c="var(--tb-link)" />
              <span className="tb-eyebrow">Step</span>
            </div>
            {platforms.map((p) => (
              <div key={p} onMouseEnter={() => setHoveredCol(p)} onMouseLeave={() => setHoveredCol((c) => (c === p ? null : c))}
                style={{ padding: '10px 14px', borderBottom: '1px solid var(--tb-hairline-strong)', borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'center', gap: 8 }}>
                {p === '—' ? <Ico n="file-text" s={16} c="var(--text-subtle-variant)" /> : <PlatformGlyph platform={platformBase(p)} s={18} c="var(--text-subtle-variant)" />}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{p === '—' ? 'Recording' : platformLabel(p)}</div>
                  {p !== '—' && <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p}</div>}
                </div>
                {p !== '—' && (
                  <span {...clickable(() => { if (window.confirm(`Remove the ${platformLabel(p)} column? This deletes its recordings from every step in this file. It's reversible until you Save (reload to undo).`)) removePlatform(p); })}
                    aria-label={`Remove ${p} column`} title={`Remove ${platformLabel(p)} column`}
                    style={{ cursor: 'pointer', color: 'var(--text-subtle)', flex: '0 0 auto', display: 'inline-flex', opacity: hoveredCol === p ? 0.85 : 0, transition: 'opacity .12s ease' }}><Ico n="trash-2" s={13} /></span>
                )}
              </div>
            ))}
            {model.trailhead && renderRow('trailhead')}
            {model.steps.map((s, i) => renderRow(i))}
          </div>
        </div>
      </div>
      {editCell && (() => {
        const scoped = editorToolsFor(catalog, target, platformBase(editCell.platform));
        const runDevices = (devices.data || []).filter((d) => platformBase(d.platform) === platformBase(editCell.platform));
        return (
          <ToolCallsPopover calls={editCell.tools} tools={scoped} allTools={catalog} anchor={editCell.anchor} busy={saving}
            runDevices={runDevices}
            singleTool={editCell.rowKey === 'trailhead'}
            onRunTools={(out, deviceId) => runTools(out, editCell.platform, deviceId)}
            onSave={(out) => {
              const norm = (out || []).map((o) => { const n = Object.keys(o)[0]; return { name: n, body: o[n] == null ? {} : o[n] }; });
              const next = applyCellTools(model, editCell.rowKey, editCell.platform, norm);
              setModel(next);
              setEditCell(null);
              persist(next);
            }}
            onClose={() => setEditCell(null)} />
        );
      })()}
    </div>
  );
}

function StepRow({ step, idx, go, toolMap = new Map() }) {
  useLucide();
  const isTrailhead = step.kind === 'trailhead';
  const tools = step.tools || [];
  const [chipTone, chipBase] = STEP_KIND[step.kind] || STEP_KIND.step;
  const chipLabel = isTrailhead ? chipBase : chipBase + (idx != null ? ' ' + (idx + 1) : '');
  return (
    <div className="tb-card" style={{ marginBottom: 10, overflow: 'hidden' }}>
      <div style={{ padding: '12px 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <Chip tone={chipTone}>{chipLabel}</Chip>
          {step.recorded
            ? <Chip tone="green"><Ico n="circle-play" s={11} /> recorded · {tools.length} tool{tools.length === 1 ? '' : 's'}</Chip>
            : <Chip><Ico n="sparkles" s={11} /> agent step</Chip>}
        </div>
        <div data-selectable style={{ fontSize: 13.5, lineHeight: 1.55 }}>{step.text}</div>
      </div>
      {tools.length > 0 && (
        <div style={{ borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {tools.map((t, j) => {
            const inCatalog = toolMap.has(t.name);
            const entries = t.args && typeof t.args === 'object' && !Array.isArray(t.args) ? Object.entries(t.args) : null;
            return (
              <div key={j}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                  <Ico n="wrench" s={12} c={inCatalog ? 'var(--tb-running)' : 'var(--text-subtle)'} />
                  <span
                    className="tb-mono"
                    role={inCatalog ? 'button' : undefined}
                    tabIndex={inCatalog ? 0 : undefined}
                    title={inCatalog ? 'Open this tool in the catalog' : undefined}
                    onClick={inCatalog && go ? () => go('tools', { tool: t.name }) : undefined}
                    onKeyDown={inCatalog && go ? (e) => { if (e.key === 'Enter') go('tools', { tool: t.name }); } : undefined}
                    style={{ fontSize: 12.5, fontWeight: 700, cursor: inCatalog ? 'pointer' : 'default', textDecoration: inCatalog ? 'underline' : 'none', textDecorationColor: 'rgba(94,155,255,.35)', textUnderlineOffset: 3 }}
                  >{t.name}</span>
                </div>
                {entries && entries.length > 0 && (
                  <div style={{ marginTop: 4, marginLeft: 19, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {entries.map(([k, v]) => (
                      <div key={k} className="tb-mono" data-selectable style={{ fontSize: 11.5, lineHeight: 1.5 }}>
                        <span style={{ color: 'var(--tb-running)' }}>{k}</span>
                        <span className="tb-sub">: </span>
                        <span style={{ color: 'var(--text-subtle-variant)' }}>{fmtArgValue(v)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// Renders the trail's whole config block verbatim — every key, flattened (nested objects become
// dotted keys like metadata.objective, arrays join with commas). One predictable rule for every
// field, so nothing is dropped just because it isn't a hardcoded column. `config` is the parsed
// config object.
function TrailConfigCard({ config }) {
  const rows = flattenObject(config || {}, { joinArray: (a) => a.join(', ') });
  return (
    <div className="tb-card pad">
      <div className="tb-eyebrow" style={{ marginBottom: 11 }}>Config</div>
      {rows.length === 0
        ? <span className="tb-sub" style={{ fontSize: 12 }}>No config keys.</span>
        : (
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '8px 12px', alignItems: 'baseline', minWidth: 0 }}>
            {rows.map(({ path, value }) => (
              <React.Fragment key={path}>
                <span className="tb-sub" style={{ fontSize: 11.5, whiteSpace: 'nowrap' }} title={path}>{path}</span>
                <span className="tb-mono" style={{ fontSize: 12.5, color: 'var(--text-standard)', wordBreak: 'break-word', minWidth: 0 }}>{value}</span>
              </React.Fragment>
            ))}
          </div>
        )}
    </div>
  );
}

// `yaml` (optional): parse steps from this YAML directly instead of fetching by trail id — lets a
// folder file (not in the workspace index) reuse this view. `configTrail` overrides the config card.
function StepsMode({ trail, go, yaml, configTrail, editable, onSave, onSaved }) {
  const detail = TB.useTrailDetail(yaml == null && trail ? trail.id : null);
  const effYaml = yaml != null ? yaml : detail.data?.yaml;
  const loading = yaml == null && detail.loading;
  // Unified single-file format (config/trailhead/trail map) renders as a steps×platforms table; the
  // legacy per-platform `- prompts:` list falls through to parseTrailSteps + StepRow below. The
  // MODEL (not the table's row count) is the unified gate: a unified trail whose steps were all
  // deleted still parses to a model, and the editable board must keep rendering so the user can add
  // steps back — gating on `unified` alone stranded such trails in the read-only "No steps" state.
  const unifiedModel = React.useMemo(() => (effYaml ? parseUnifiedModel(effYaml) : null), [effYaml]);
  const unified = React.useMemo(() => unifiedTableFromMatrix(unifiedModel), [unifiedModel]);
  const parsed = React.useMemo(() => (effYaml ? parseTrailSteps(effYaml) : null), [effYaml]);
  const steps = parsed || (yaml == null ? (detail.data?.steps || []).map((s) => ({ kind: s.kind, text: s.text, recorded: (s.tools || []).length > 0, tools: (s.tools || []).map((n) => ({ name: n, args: null })) })) : []);
  // Session-shared catalog (not per-switch useTools): the catalog only powers the cosmetic "open in
  // catalog" tool links here + the unified board's arg editor, so rebuilding it on every trail switch
  // (which starved the trail-detail fetch and hung the Steps skeleton) isn't worth it.
  const catalog = TB.useToolCatalog();
  const toolMap = React.useMemo(() => {
    const m = new Map();
    (catalog.data || []).forEach((t) => { if (!m.has(t.id)) m.set(t.id, t); });
    return m;
  }, [catalog.data]);
  // Show the whole config block — every key from the YAML in its natural order (title,
  // metadata.objective, …), then any index-derived fields (target/platform/priority/tags) the file's
  // config block happens to omit, appended so nothing useful is dropped on the Trails view.
  const cfgObj = React.useMemo(() => {
    const fromYaml = (effYaml ? parseTrailYaml(effYaml).config : null) || {};
    const t = configTrail || trail || {};
    const out = { ...fromYaml };
    ['target', 'platform', 'priority', 'driver'].forEach((k) => { if (t[k] && !(k in out)) out[k] = t[k]; });
    if (t.tags && t.tags.length && !('tags' in out)) out.tags = t.tags;
    return out;
  }, [effYaml, configTrail, trail]);

  // Unified trails span platforms, so the step board goes full width with config ABOVE it (the trail's
  // identity/context reads first, then its steps). When the view is editable (a committed trail with a
  // save handler), render the editable matrix that writes the whole file back; otherwise the read-only
  // table (non-editable contexts).
  if (!loading && unifiedModel) {
    const canEdit = editable && typeof onSave === 'function';
    const saveYaml = canEdit ? async (t) => { const r = await onSave(t); if (r && r.success) onSaved && onSaved(); return r; } : null;
    return (
      <div>
        <div style={{ marginBottom: 20, maxWidth: 560 }}>
          <TrailConfigCard config={cfgObj} />
        </div>
        {canEdit
          ? <UnifiedStepsBoard yaml={effYaml} target={cfgObj.target} onSaveYaml={saveYaml} catalog={catalog.data || []} />
          : unified
            ? <UnifiedStepsTable data={unified} />
            : <EmptyState ico="list" title="No steps" sub="This unified trail has no steps yet." />}
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', gap: 20 }}>
      <div style={{ flex: 1.7, minWidth: 0 }}>
        {loading && <Skeleton rows={4} />}
        {!loading && steps.length === 0 && (
          <EmptyState ico="list" title="No steps" sub="This trail has no parsed steps, or couldn't be loaded." />
        )}
        {(() => { let n = 0; return steps.map((s, i) => <StepRow key={i} step={s} idx={s.kind === 'trailhead' ? null : n++} go={go} toolMap={toolMap} />); })()}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <TrailConfigCard config={cfgObj} />
      </div>
    </div>
  );
}

function YamlMode({ trail }) {
  const detail = TB.useTrailDetail(trail?.id);
  const yaml = detail.data?.yaml;
  if (detail.loading) return <div className="tb-card" style={{ padding: '14px 16px' }}><div className="tb-skel" style={{ height: 200 }} /></div>;
  if (!yaml) return <span className="tb-sub" style={{ fontSize: 12 }}>Couldn't load YAML</span>;
  return <SearchableText text={yaml} language="yaml" fontSize={12.5} minHeight={200} />;
}

// `runs` (optional): an explicit list of run rows (for callers that scope to a folder's own
// sessions). Otherwise sessions are fetched and matched to this trail.
function RunsMode({ trail, go, runs }) {
  const sessions = TB.useSessions();
  const matches = runs != null ? runs : (sessions.data || []).filter((s) => s.title === trail.title || (s.title || '').includes(trail.id));
  if (runs == null && sessions.loading) return <Skeleton rows={3} />;
  if (matches.length === 0) return <EmptyState ico="history" title="No runs yet" sub="Hit Run trail to see results land here." />;
  return (
    <div style={{ maxWidth: 720 }}>
      {matches.map((s) => (
        <div className="tb-row" key={s.id} {...clickable(() => go('completed', { sel: s.id }))} style={{ marginBottom: 8, cursor: 'pointer' }}>
          <Dot c={STATUS[s.status][1]} s={9} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13.5, fontWeight: 600 }}>{[s.device, s.dur].filter(Boolean).join(' · ') || 'Run'}</div>
            <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 2 }}>{s.ago}</div>
          </div>
          <StatusChip s={s.status} />
        </div>
      ))}
    </div>
  );
}

// The Trails "Implementations" tab - the bundle-level Steps × recordings board
// over a committed trail BUNDLE (a folder of blaze.yaml + per-platform <platform>.trail.yaml files).
// It loads the blaze + each variant via the /api/folder/* endpoints, holds the editable steps, and
// hands StepsBoard the trail-side I/O primitives (save steps → blaze.yaml, record/play on a device,
// delete a recording). A bundle with no blaze.yaml yet seeds its steps from the representative
// recording and creates the blaze.yaml on first Save — which is also what enables recording.
function TrailImplementationsBoard({ folderId, home, blazeEntry, variantEntries, deviceList, target, platform, go, openRun, onOpenFile, reloadIndex }) {
  useLucide();
  const sessions = TB.useSessions();
  const [blazeYaml, setBlazeYaml] = React.useState(null); // null = loading; '' = no blaze.yaml in the folder
  const [variantDocs, setVariantDocs] = React.useState({});
  const [steps, setSteps] = React.useState(null);
  const [err, setErr] = React.useState(null);
  const [reloadTick, setReloadTick] = React.useState(0);
  // Presence comes from what was ACTUALLY loaded from the folder, not only the workspace index:
  // right after Save creates blaze.yaml the index lags a round trip, and trusting it alone put a
  // "no blaze.yaml yet" banner directly above a populated blaze.yaml column.
  const fileOf = (p) => (p || '').split('/').pop();
  const variants = React.useMemo(
    () => variantEntries.map((v) => ({ name: fileOf(v.path), platform: (v.platform || derivePlatformFromTrail(v) || '').toLowerCase() })),
    [variantEntries.map((v) => v.id).join(',')],
  );

  // Load blaze.yaml (or '') + each variant's parsed yaml; refetch on membership change or after a
  // record/save/delete (reloadTick).
  const memberKey = (blazeEntry ? blazeEntry.id : '') + '|' + variants.map((v) => v.name).join(',');
  React.useEffect(() => {
    let cancelled = false;
    // Always fetch: null on 404 normalizes to '' below, and a blaze.yaml the index doesn't know
    // about yet (just saved / just committed) still loads.
    const blazeP = TB.fetchTrailFolderFile(folderId, 'blaze.yaml');
    const varsP = Promise.all(variants.map((v) => TB.fetchTrailFolderFile(folderId, v.name).then((c) => [v.name, parseTrailYaml(c || '')])));
    Promise.all([blazeP, varsP]).then(([bz, entries]) => {
      if (cancelled) return;
      setBlazeYaml(bz || '');
      setVariantDocs(Object.fromEntries(entries));
    });
    return () => { cancelled = true; };
  }, [folderId, memberKey, reloadTick]);

  const hasBlaze = !!blazeEntry || !!(blazeYaml && blazeYaml.trim());

  // The canonical step list from disk: the blaze's prompts, or (no blaze yet) the representative
  // recording's prompts so the matrix still shows steps to edit / promote into a blaze.
  const seededSteps = React.useMemo(() => {
    if (blazeYaml == null) return null;
    let prompts = blazeYaml ? parseTrailYaml(blazeYaml).prompts : null;
    if ((!prompts || !prompts.length) && variants.length) { const rep = variantDocs[variants[0].name]; prompts = rep ? rep.prompts : null; }
    return (prompts || []).map((p) => { const e = promptEntry(p); return e ? { kind: e.kind === 'verify' ? 'verify' : 'do', text: e.text } : null; }).filter(Boolean);
  }, [blazeYaml, variants.map((v) => v.name).join(','), hasBlaze ? '' : Object.keys(variantDocs).join(',')]);
  React.useEffect(() => { if (seededSteps != null) setSteps(seededSteps); }, [seededSteps]);
  // Show Save when the steps diverge from disk OR when the bundle has no blaze yet but does have
  // steps (seeded from a recording) — that lets the user promote them into a fresh blaze.yaml
  // without first making an arbitrary edit (which also unlocks recording).
  const dirty = (steps && seededSteps && JSON.stringify(steps) !== JSON.stringify(seededSteps)) ||
    (!hasBlaze && !!(steps && steps.length));

  const blazeConfigRows = React.useMemo(() => flattenObject(parseTrailYaml(blazeYaml || '').config || {}, { joinArray: (a) => a.join(', ') }), [blazeYaml]);
  const blazeTrailhead = React.useMemo(() => parseTrailYaml(blazeYaml || '').trailhead, [blazeYaml]);
  const blazeToolsSteps = React.useMemo(() => parseTrailYaml(blazeYaml || '').toolsItems || [], [blazeYaml]);
  const linkedSessions = (sessions.data || []).filter((s) => s.trailId === folderId);
  const reloadAll = () => { setReloadTick((n) => n + 1); reloadIndex && reloadIndex(); };

  // Save the blaze steps → write blaze.yaml (creating it if the bundle didn't have one). Preserves
  // any config the file already carried via mergeBlazeYaml.
  async function saveSteps(next) {
    const cfg = parseTrailYaml(blazeYaml || '').config || {};
    const yaml = TB.mergeBlazeYaml(blazeYaml || '', {
      title: cfg.title || ((home || '').split('/').pop()),
      target: cfg.target || target || null,
      platform: cfg.platform || platform || null,
      objective: (cfg.metadata && cfg.metadata.objective) || null,
      context: cfg.context || null,
      destination: (cfg.metadata && cfg.metadata.destination) || null,
      steps: next,
    });
    const r = await TB.saveTrailFolderFile(folderId, 'blaze.yaml', yaml);
    if (r.success) reloadAll();
    return r;
  }
  async function dispatchRecord(chosen, options) {
    const deviceIds = chosen.map((dev) => ({ instanceId: dev.id, trailblazeDevicePlatform: (dev.platform || '').toUpperCase() }));
    for (const tbId of deviceIds) {
      const ok = await TB.connectDevice(tbId);
      if (!ok) return { ok: false, error: `Couldn't connect to ${chosen.find((d) => d.id === tbId.instanceId)?.name || 'the device'}. Is it still online?` };
    }
    const r = await TB.recordTrailFolder(folderId, deviceIds, options);
    if (!r.ok) return { ok: false, error: r.error };
    setTimeout(reloadAll, 4000);
    return { ok: true, error: r.error };
  }
  async function dispatchPlay(col) {
    if (!col.device || !col.recorded) return { ok: false };
    const yaml = await TB.fetchTrailFolderFile(folderId, col.name);
    if (!yaml) return { ok: false, error: 'Could not read ' + col.name };
    const tbId = { instanceId: col.device.id, trailblazeDevicePlatform: (col.device.platform || '').toUpperCase() };
    const ok = await TB.connectDevice(tbId);
    if (!ok) return { ok: false, error: `Couldn't connect to ${col.device.name}. Is it still online?` };
    const r = await TB.dispatchRun(tbId, yaml, { useRecordedSteps: true, trailId: folderId });
    if (!r.ok) return { ok: false, error: r.error };
    setTimeout(reloadAll, 3000);
    return { ok: true };
  }
  async function deleteFile(name) {
    const r = await TB.deleteTrailFolderFile(folderId, name);
    if (r.ok) reloadAll();
    return r;
  }
  // Replace a step's whole tool-call list in a variant file. Builds the new YAML from the already-loaded
  // in-memory parse (variantDocs — the exact thing rendered on screen) rather than re-reading the file,
  // then writes it back. newToolsArray is the rebuilt [{ [toolName]: { …args } }] from the popover.
  async function saveVariantTools(variantName, promptIndex, newToolsArray, stepInfo) {
    const r0 = await patchVariantRecording(TB.fetchTrailFolderFile, folderId, variantName, promptIndex, newToolsArray, stepInfo);
    if (r0.error) return { success: false, error: r0.error };
    if (r0.noop) return { success: true };
    const r = await TB.saveTrailFolderFile(folderId, variantName, r0.yaml);
    if (r.success) setVariantDocs((prev) => ({ ...prev, [variantName]: parseTrailYaml(r0.yaml) }));
    return r;
  }
  // Full-file writer for the expanded variant's inline raw-YAML editor. Hot-updates the parsed doc.
  async function saveVariantYaml(name, yaml) {
    const r = await TB.saveTrailFolderFile(folderId, name, yaml);
    if (r.success) setVariantDocs((prev) => ({ ...prev, [name]: parseTrailYaml(yaml) }));
    return r;
  }

  if (blazeYaml == null) return <Skeleton rows={4} />;
  return (
    <div>
      {err && <div style={{ marginBottom: 12, color: 'var(--tb-danger-text)', fontSize: 13 }}>{err}</div>}
      {!hasBlaze && (
        <div className="tb-sub" style={{ marginBottom: 14, fontSize: 12.5, padding: '8px 11px', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 8 }}>
          This bundle has no <span className="tb-mono">blaze.yaml</span> yet — its steps are read from the first recording. Hit <b>Save steps</b> to create one (edit them first if you like), which also unlocks recording on a device.
        </div>
      )}
      <StepsBoard
        steps={steps || []} onStepsChange={setSteps} dirty={dirty} onSaveSteps={saveSteps}
        variants={variants} variantDocs={variantDocs} blazeConfigRows={blazeConfigRows} blazeTrailhead={blazeTrailhead} blazeToolsSteps={blazeToolsSteps}
        deviceList={deviceList} linkedSessions={linkedSessions}
        home={home} blazeName="blaze.yaml" target={target} canRecord={hasBlaze}
        onOpenFile={onOpenFile} dispatchRecord={dispatchRecord} dispatchPlay={dispatchPlay}
        onConfigureRun={openRun ? (col) => {
          const entry = variantEntries.find((v) => fileOf(v.path) === col.name);
          openRun(entry || { id: folderId, path: home, target }, { deviceId: col.device && col.device.id, replay: true });
        } : null}
        onDeleteFile={deleteFile} onViewRun={(s) => go('completed', { sel: s.id })} onError={setErr}
        onSaveVariantTools={saveVariantTools}
        onSaveVariantYaml={saveVariantYaml}
        onFetchVariantYaml={(name) => TB.fetchTrailFolderFile(folderId, name)}
      />
    </div>
  );
}

// Shared tabbed detail view for a SINGLE trail OR a folder file: Steps · Edit · Runs (+ Variants when
// given a sibling list). Edit is the YAML+tools editor. Used inline on the Trails screen (for one
// selected trail) and for bundle folder files. Tab can be controlled (tab + onTab) or internal
// (defaultTab). Data comes in via props (yaml, runs, tools, onSave) so it works for both a workspace
// trail and a folder file. The bundle-level Steps × recordings matrix is a SEPARATE folder view
// (TrailImplementationsBoard), shown when a bundle folder — not a single trail — is selected.
function TrailDetailView({ trail, configTrail, yaml, editable = true, tools, onSave, onSaved, runs, go, openRun, variants, currentId, onSelectVariant, tab: tabProp, onTab, defaultTab, dirtyRef, highlight }) {
  useLucide();
  const hasVariants = variants && variants.length > 1;
  const [tabState, setTabState] = React.useState(defaultTab || 'steps');
  const allowed = ['steps', 'edit', 'runs', 'variants'];
  const rawTab = tabProp != null ? tabProp : tabState;
  const tab = allowed.includes(rawTab) && (rawTab !== 'variants' || hasVariants) ? rawTab : 'steps';
  const setTab = onTab || setTabState;
  const tabs = [
    ['steps', 'Steps', 'list'],
    ['edit', 'Edit', 'pencil'],
    ['runs', 'Runs', 'history'],
    ...(hasVariants ? [['variants', `Variants · ${variants.length}`, 'layers']] : []),
  ];
  const isEdit = tab === 'edit';
  // Keep the YAML editor (Monaco + a live language-server socket) ALIVE across tab switches once it's been
  // opened, instead of unmounting it every time you leave the Edit tab — remounting rebuilt Monaco, respawned
  // the language server, and refetched the schema on every Steps↔Edit toggle. Mount it LAZILY on first Edit
  // open (so a trail you only browse in Steps never pays that cost), then just hide it with `display:none`.
  const [editEverOpened, setEditEverOpened] = React.useState(isEdit);
  React.useEffect(() => { if (isEdit) setEditEverOpened(true); }, [isEdit]);
  // Identity of the trail/file the editor is showing. When it changes the editor resets to the new content
  // even if there were unsaved edits (see TrailYamlEditor) — this replaces the `key={id}` remount that used
  // to discard the prior trail's state on switch, without tearing down Monaco + the LSP socket.
  const resetKey = (trail && trail.id) || currentId || null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="tb-tabs" style={{ flex: '0 0 auto' }}>
        {tabs.map(([id, label, ico]) => (
          <div key={id} className={'tb-tab ' + (tab === id ? 'active' : '')} onClick={() => setTab(id)} style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer' }}>
            <Ico n={ico} s={15} />{label}
          </div>
        ))}
      </div>
      <div style={{ flex: 1, minHeight: 0, paddingTop: 16, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Non-edit tabs are cheap to remount; render them in their own scroll container, hidden while editing. */}
        {!isEdit && (
          <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
            {tab === 'steps' && <StepsMode trail={configTrail || trail} configTrail={configTrail} yaml={yaml} go={go} editable={editable} onSave={onSave} onSaved={onSaved} />}
            {tab === 'runs' && <RunsMode trail={trail} runs={runs} go={go} />}
            {tab === 'variants' && hasVariants && <VariantsMode variants={variants} currentId={currentId} onSelect={onSelectVariant} openRun={openRun} />}
          </div>
        )}
        {/* Editor stays mounted once opened; toggled via `display` so Monaco + the LSP socket survive switches. */}
        {editEverOpened && (
          <div style={{ flex: 1, minHeight: 0, display: isEdit ? 'flex' : 'none', flexDirection: 'column' }}>
            <TrailYamlEditor content={yaml} editable={editable} tools={tools} onSave={onSave} onSaved={onSaved} dirtyRef={dirtyRef} highlight={highlight} resetKey={resetKey} />
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { prettyVariant, VariantsMode, StepRow, parseTrailSteps, parseUnifiedTrail, parseUnifiedModel, serializeUnifiedModel, platformBase, platformLabel, UnifiedStepsTable, UnifiedStepsBoard, fmtArgValue, TrailConfigCard, StepsMode, YamlMode, RunsMode, TrailDetailView, TrailImplementationsBoard, VARIANT_PLAT });

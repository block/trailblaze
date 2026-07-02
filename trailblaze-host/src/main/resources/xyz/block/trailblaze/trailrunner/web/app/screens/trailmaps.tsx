// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Trailmaps section — one page per component type (Toolsets, Waypoints, Shortcuts,
// Trailheads). Each page is a flat, cross-trailmap list of that component type,
// grouped by trailmap, with a read-only viewer. (Tools has its own richer screen.)

const TM_COMP_TYPES = {
  tools: { ico: 'wrench', color: 'var(--text-subtle-variant)', label: 'Tools', singular: 'tool',
    def: 'Named actions a trail can call - tap, seed data, clear state.',
    help: { title: 'Actions the agent can take', tag: '3 flavors',
      body: 'A tool is one named action a trail can invoke - tap, seed an account, clear app state. Authored as typed TypeScript (.ts), a declarative .tool.yaml, or a Kotlin-backed class. The Tools page lists, edits, and tests them.' } },
  toolsets: { ico: 'boxes', color: 'var(--tb-running)', label: 'Toolsets', singular: 'toolset',
    def: 'A named bundle of tool IDs + drivers; a target opts in per platform via tool_sets.',
    help: { title: 'Reusable tool bundles', foot: 'toolsets/<name>.yaml',
      body: 'A named bundle of tool IDs plus the drivers they need. A target opts in per platform via tool_sets, so several targets can share one curated set instead of re-listing tools.' } },
  waypoints: { ico: 'map-pin', color: 'var(--tb-pass)', label: 'Waypoints', singular: 'waypoint',
    def: 'A named UI state defined by selector matchers that must (or must not) be present.',
    help: { title: 'Known UI states', foot: 'waypoints/**.waypoint.yaml',
      body: 'A named, recognizable screen - “logged-in home”, “cart open” - defined by selector matchers that must (or must not) be present. Trails assert waypoints; the agent uses them to know where it is.' } },
  shortcuts: { ico: 'route', color: 'var(--tb-amber)', label: 'Shortcuts', singular: 'shortcut',
    def: 'An authored navigation edge between two waypoints (from → to) - deterministic movement between known states.',
    help: { title: 'The navigation graph', foot: '*.shortcut.yaml',
      body: 'An authored edge between two waypoints (from → to) - the fast, deterministic way to move between known states without re-deriving every step.' } },
  trailheads: { ico: 'flag', color: 'var(--tb-pass)', label: 'Trailheads', singular: 'trailhead',
    def: 'Bootstraps from any state to a known waypoint (→ to). Always available.',
    help: { title: 'Entry points', foot: '*.trailhead.yaml',
      body: 'Bootstraps from any state to a known waypoint (→ to). Always available, so the agent can always get to a known starting point.' } },
  systemPrompts: { ico: 'file-text', color: 'var(--text-subtle)', label: 'System prompt', singular: 'system prompt',
    def: 'Markdown template that frames this target for the model - prepended when the agent runs.' },
};
const TM_FLAVOR_TINT = { scripted: 'var(--tb-pass)', yaml: 'var(--tb-running)', kotlin: 'var(--tb-amber)', unknown: 'var(--text-subtle)' };
const TM_DIR = { tools: 'tools', toolsets: 'toolsets', waypoints: 'waypoints', shortcuts: 'shortcuts', trailheads: 'trailheads' };
const TM_SUFFIX = { tools: '.ts', toolsets: '.yaml', waypoints: '.waypoint.yaml', shortcuts: '.shortcut.yaml', trailheads: '.trailhead.yaml' };

// The path within a trailmap, minus the leading component dir and the file suffix —
// e.g. .../sample/waypoints/web/about.waypoint.yaml → "web/about". Gives waypoints
// their platform context that a bare stem would lose.
function tmInnerLabel(relPath, name) {
  const m = relPath.split('/trailmaps/');
  const after = m.length > 1 ? m[1] : relPath;
  const segs = after.split('/').slice(2, -1); // drop <trailmap>/<dir>, drop filename
  return segs.length ? segs.join('/') + '/' + name : name;
}

// The label shown in list rows and the detail title. For shortcuts/trailheads the
// file stems repeat the trailmap and platform (e.g. android/sample_android_openDashboard),
// which is pure noise once the row is already under the "sample" group and "android/" path —
// strip those prefixes for display. The full id stays in the row tooltip and the Source row.
// Toolsets are left intact: their id (e.g. sample_checkout_flow) is referenced verbatim in
// a target's tool_sets config, so the displayed string must stay copy-accurate.
function tmDisplayLabel(kind, trailmap, label) {
  if (kind !== 'shortcuts' && kind !== 'trailheads') return label;
  const slash = label.lastIndexOf('/');
  const dir = slash >= 0 ? label.slice(0, slash + 1) : '';
  let stem = slash >= 0 ? label.slice(slash + 1) : label;
  if (trailmap && stem.startsWith(trailmap + '_')) stem = stem.slice(trailmap.length + 1);
  const plat = dir ? dir.replace(/\/+$/, '').split('/')[0] : '';
  if (plat && stem.startsWith(plat + '_')) stem = stem.slice(plat.length + 1);
  return dir + stem;
}

// Build a component descriptor from a relPath alone — lets a just-created file be
// selected and viewed (read via ?path=) before the catalog re-scan picks it up.
function tmSynthComp(relPath) {
  const after = (relPath.split('/trailmaps/')[1]) || relPath;
  const trailmap = after.split('/')[0];
  const name = (relPath.split('/').pop() || '').replace(/\.(waypoint|shortcut|trailhead)\.yaml$/, '').replace(/\.(yaml|ts|md)$/, '');
  return { name, relPath, flavor: null, trailmap, label: tmInnerLabel(relPath, name) };
}

function ComponentTypeScreen({ kind, go, initSel }) {
  useLucide();
  const meta = TM_COMP_TYPES[kind] || TM_COMP_TYPES.tools;
  const tmResult = TB.useTrailmaps();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const tms = tmResult.data || [];
  const [q, setQ] = React.useState('');
  const [sort, setSort] = useStickyState('tb-' + kind + '-sort', 'group');
  const [selPath, setSelPath] = React.useState(null);
  const [collapsedGroups, setCollapsedGroups] = React.useState(() => new Set());
  const [showNew, setShowNew] = React.useState(false);
  const [menu, setMenu] = React.useState(null);
  const [treeW, startTreeDrag] = useResizableWidth('tb-trailmap-comp-tree-w', 320, 240, 640);
  const toggleGroup = (tm) => setCollapsedGroups((prev) => { const n = new Set(prev); if (n.has(tm)) n.delete(tm); else n.add(tm); return n; });

  // The single platform of the active target's selected devices (null when mixed or none).
  const gtPlatform = React.useMemo(() => {
    const list = devices.data || [];
    const plats = new Set(((gt && gt.deviceIds) || []).map((id) => { const d = list.find((x) => x.id === id); return d && d.platform; }).filter(Boolean));
    return plats.size === 1 ? [...plats][0] : null;
  }, [gt && (gt.deviceIds || []).join(','), devices.data]);
  // When a global target is set, scope to the trailmaps that apply to it: the target's own trailmap
  // PLUS the generalized framework + platform trailmaps (so platform-wide components stay visible
  // for an app target, not just the target's own). "Show all" drops the scope; switching targets
  // (or component type) re-applies it.
  const [scopeOff, setScopeOff] = React.useState(false);
  React.useEffect(() => { setScopeOff(false); }, [gt && gt.target, kind]);
  const scoped = !!(gt && gt.target) && !scopeOff;
  const scopeSet = React.useMemo(() => (gt && gt.target ? TB.scopeTrailmaps(gt.target, gtPlatform) : null), [gt && gt.target, gtPlatform]);
  const all = React.useMemo(() => {
    const out = [];
    tms.forEach((t) => { if (scoped && scopeSet && !scopeSet.has(t.id)) return; (t[kind] || []).forEach((c) => out.push({ name: c.name, relPath: c.relPath, flavor: c.flavor, trailmap: t.id, label: tmInnerLabel(c.relPath, c.name) })); });
    return out;
  }, [tms, kind, scoped, scopeSet]);
  // The full count across every trailmap (denominator of "N of M") — so the footer shows the
  // scope reduction against the absolute total, matching the Trails footer ("23 of 1016").
  const fullCount = React.useMemo(() => tms.reduce((n, t) => n + ((t[kind] || []).length), 0), [tms, kind]);

  // Map a waypoint id (e.g. "sample/web/about") → its relPath, so shortcuts and
  // trailheads can deep-link their from/to waypoints to the Waypoints page.
  const waypointIndex = React.useMemo(() => {
    const idx = {};
    tms.forEach((t) => (t.waypoints || []).forEach((w) => { idx[t.id + '/' + tmInnerLabel(w.relPath, w.name)] = w.relPath; }));
    return idx;
  }, [tms]);

  const filtered = React.useMemo(() => {
    const ql = q.trim().toLowerCase();
    return all.filter((c) => !ql || c.label.toLowerCase().includes(ql) || c.trailmap.toLowerCase().includes(ql));
  }, [all, q]);

  const { groups, trailmaps } = React.useMemo(() => {
    const g = {};
    filtered.forEach((c) => { (g[c.trailmap] = g[c.trailmap] || []).push(c); });
    Object.values(g).forEach((arr) => arr.sort((a, b) => a.label.localeCompare(b.label)));
    let keys = Object.keys(g);
    if (sort === 'name') {
      // flatten into a single pseudo-group sorted by label
      const flat = [...filtered].sort((a, b) => a.label.localeCompare(b.label));
      return { groups: { '': flat }, trailmaps: [''] };
    }
    keys = keys.sort((a, b) => g[b].length - g[a].length || a.localeCompare(b));
    return { groups: g, trailmaps: keys };
  }, [filtered, sort]);

  // Re-apply whenever the routed component changes (keyed on the value, not a one-shot)
  // — screens stay mounted, so a deep-link to a different waypoint/shortcut must move
  // the selection. Value key avoids re-clobbering a manual pick on catalog reload.
  const appliedSel = React.useRef(null);
  React.useEffect(() => {
    if (initSel && initSel !== appliedSel.current && all.find((c) => c.relPath === initSel)) { setSelPath(initSel); appliedSel.current = initSel; }
  }, [initSel, all]);
  React.useEffect(() => { setSelPath(null); }, [kind]);

  // Fall back to a synthesized descriptor so a freshly-created (or not-yet-rescanned)
  // file can still be opened — its body reads fine via ?path=.
  const cur = all.find((c) => c.relPath === selPath) || (selPath ? tmSynthComp(selPath) : null);
  const flatVisible = trailmaps.filter((tm) => !collapsedGroups.has(tm)).flatMap((tm) => groups[tm]);

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      <div style={{ width: treeW, flex: '0 0 ' + treeW + 'px', borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <RailHeader ico={meta.ico} iconColor={meta.color} title={meta.label}
          help={meta.help && <HelpDot ico={meta.ico} color={meta.color} title={meta.help.title} tag={meta.help.tag} foot={meta.help.foot}>{meta.help.body}</HelpDot>}
          right={<button className="tb-btn ghost sm" style={{ padding: 6 }} title={'New ' + meta.singular} onClick={() => setShowNew(true)}><Ico n="plus" s={16} /></button>} />
        <div style={{ padding: '0 12px 8px' }}>
          <div className="tb-input"><Ico n="search" s={14} /><input placeholder={`Search ${meta.label.toLowerCase()}…`} value={q} onChange={(e) => setQ(e.target.value)} /></div>
        </div>
        <div style={{ padding: '0 12px 10px', display: 'flex', gap: 6 }}>
          <Select compact ico="arrow-down-up" label="Sort" value={sort} onChange={(e) => setSort(e.target.value)} title="Order of the list"
            options={[['group', 'Grouped by trailmap'], ['name', 'Name A–Z']]} />
        </div>
        <div tabIndex={0} style={{ flex: 1, overflowY: 'auto', padding: '0 8px 10px', outline: 'none' }}
          onKeyDown={(e) => listNavKeyDown(e, { index: flatVisible.findIndex((x) => x.relPath === selPath), count: flatVisible.length, set: (i) => setSelPath(flatVisible[i].relPath) })}>
          {tmResult.loading && <div style={{ padding: 12 }}><Skeleton rows={6} /></div>}
          {trailmaps.map((tm) => {
            const open = !collapsedGroups.has(tm);
            const groupName = tm || 'All ' + meta.label.toLowerCase();
            return (
              <div key={tm || '_all'}>
                {tm !== '' && (
                  <div className="tb-tree" style={{ cursor: 'pointer' }} onClick={() => toggleGroup(tm)} title={open ? 'Collapse' : 'Expand'}>
                    <span style={{ display: 'inline-flex', flex: '0 0 auto', transition: 'transform .15s var(--ease-out-soft)', transform: open ? 'none' : 'rotate(-90deg)' }}><Ico n="chevron-down" s={13} c="var(--text-subtle)" /></span>
                    <Ico n="map" s={15} c="var(--tb-running)" style={{ flex: '0 0 auto' }} />
                    <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{groupName}</span>
                    <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>{groups[tm].length}</span>
                  </div>
                )}
                {open && groups[tm].map((c) => {
                  const on = c.relPath === selPath;
                  return (
                    <div key={c.relPath} data-navrow role="button" onClick={() => setSelPath(c.relPath)}
                      onContextMenu={(e) => { e.preventDefault(); setSelPath(c.relPath); setMenu({ x: e.clientX, y: e.clientY, comp: c }); }}
                      className={'tb-tree ' + (on ? 'active' : '')} style={{ paddingLeft: tm === '' ? 9 : 23, cursor: 'pointer' }} title={c.relPath}>
                      <Ico n={kind === 'tools' ? 'wrench' : meta.ico} s={13} c={kind === 'tools' ? (TM_FLAVOR_TINT[c.flavor] || meta.color) : meta.color} style={{ flex: '0 0 auto' }} />
                      <span className="tb-mono" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 12 }}>{tmDisplayLabel(kind, c.trailmap, c.label)}</span>
                      {sort === 'name' && tm === '' ? <span className="tb-sub" style={{ fontSize: 10.5, flex: '0 0 auto' }}>{c.trailmap}</span> : null}
                    </div>
                  );
                })}
              </div>
            );
          })}
          {!tmResult.loading && filtered.length === 0 && <div className="tb-sub" style={{ padding: '20px 10px', fontSize: 12.5 }}>No {meta.label.toLowerCase()} {q ? 'match.' : 'in this workspace yet.'}</div>}
        </div>
        <TargetScopeBanner label={scoped ? (gt.label || gt.target) : null} platform={scoped ? gtPlatform : null} onShowAll={() => setScopeOff(true)} />
        <div style={{ padding: '9px 12px', borderTop: '1px solid var(--tb-hairline)' }}>
          <div className="tb-sub" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>{filtered.length === fullCount ? `${fullCount} ${meta.label.toLowerCase()}` : `${filtered.length} of ${fullCount} ${meta.label.toLowerCase()}`}</div>
        </div>
      </div>
      <Splitter onDown={startTreeDrag} />
      <div style={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
        {tmResult.loading && <div style={{ padding: 32 }}><Skeleton rows={5} /></div>}
        {!tmResult.loading && !cur && (
          <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <EmptyState ico={meta.ico} title={`Select a ${meta.singular}`} sub={meta.def} />
          </div>
        )}
        {cur && <ComponentDetail comp={cur} kind={kind} go={go} waypointIndex={waypointIndex} />}
      </div>
      {showNew && <NewComponentModal kind={kind} trailmaps={tms.map((t) => t.id)} onClose={() => setShowNew(false)} onCreated={(rel) => { setSelPath(rel); tmResult.reload(); }} />}
      {menu && <TrailmapComponentContextMenu menu={menu} onClose={() => setMenu(null)} />}
    </div>
  );
}

function NewComponentModal({ kind, trailmaps, onClose, onCreated }) {
  useLucide();
  const meta = TM_COMP_TYPES[kind] || TM_COMP_TYPES.tools;
  const [trailmap, setTrailmap] = React.useState(trailmaps[0] || '');
  const [name, setName] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const create = async () => {
    const nm = name.trim();
    if (!trailmap || !nm || busy) return;
    setBusy(true); setErr(null);
    const r = await TB.createTrailmapComponent({ trailmap, kind, name: nm });
    setBusy(false);
    if (r.ok) { onCreated(r.relPath); onClose(); } else setErr(r.error || 'Could not create');
  };
  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px, 94vw)', padding: 24 }}>
        <h2 className="tb-h2" style={{ marginBottom: 6 }}>New {meta.singular}</h2>
        <p className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '0 0 16px' }}>{meta.def}</p>
        <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Trailmap</div>
        <Select full value={trailmap} onChange={(e) => setTrailmap(e.target.value)} options={trailmaps.map((t) => [t, t])} />
        <div className="tb-eyebrow" style={{ margin: '14px 0 6px' }}>Name</div>
        <div className="tb-input"><input autoFocus placeholder={kind === 'waypoints' ? 'e.g. android/home' : 'e.g. my_' + meta.singular} value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') create(); }} /></div>
        <div className="tb-sub" style={{ fontSize: 11, marginTop: 6 }}>Creates <span className="tb-mono">{trailmap || '<trailmap>'}/{TM_DIR[kind]}/{name.trim() || '<name>'}{TM_SUFFIX[kind]}</span></div>
        {err ? <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-fail)' }}>{err}</div> : null}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
          <Btn sm onClick={onClose}>Cancel</Btn>
          <Btn sm kind="primary" ico="plus" onClick={create} style={{ opacity: busy ? 0.5 : 1, pointerEvents: busy ? 'none' : 'auto' }}>{busy ? 'Creating…' : 'Create'}</Btn>
        </div>
      </div>
    </div>
  );
}

function tmParseYaml(text) { try { return window.jsyaml ? window.jsyaml.load(text) : null; } catch (_) { return null; } }

function ComponentDetail({ comp, kind, go, waypointIndex }) {
  useLucide();
  const [tab, setTab] = React.useState('overview');
  const [text, setText] = React.useState(null);
  const [err, setErr] = React.useState(false);
  React.useEffect(() => { setTab('overview'); }, [comp.relPath]);
  React.useEffect(() => {
    let live = true; setText(null); setErr(false);
    TB.fetchComponentSource(comp.relPath).then((t) => { if (!live) return; if (t == null) setErr(true); else setText(t); });
    return () => { live = false; };
  }, [comp.relPath]);
  const body = (renderBody) => (
    text == null && !err ? <Skeleton rows={6} />
      : err ? <div className="tb-sub" style={{ fontSize: 12.5 }}>Could not read this file.</div>
      : renderBody()
  );
  return (
    <div style={{ padding: '24px 28px', maxWidth: 980, margin: '0 auto', display: 'flex', flexDirection: 'column', boxSizing: 'border-box', ...(tab === 'edit' ? { height: '100%', minHeight: 0 } : { minHeight: '100%' }) }}>
      <DetailHeader
        title={tmDisplayLabel(kind, comp.trailmap, comp.label)}
        badges={<React.Fragment><Chip>{comp.trailmap}</Chip>{kind === 'tools' && comp.flavor ? <Chip>{comp.flavor}</Chip> : null}</React.Fragment>}
        meta={<ToolMeta ico="folder" label="Source"><span className="tb-mono" data-selectable title={comp.relPath}>{comp.relPath}</span></ToolMeta>}
        right={<Btn sm ico="folder-open" onClick={() => TB.revealToolSource({ path: comp.relPath })}>Open in Finder</Btn>}
      />
      <div className="tb-tabs" style={{ marginTop: 16, marginBottom: 18 }}>
        {[['overview', 'Overview', 'eye'], ...(kind === 'shortcuts' || kind === 'trailheads' ? [['run', 'Run', 'play']] : []), ['edit', 'Edit', 'pencil']].map(([id, label, ico]) => (
          <div key={id} className={'tb-tab ' + (tab === id ? 'active' : '')} onClick={() => setTab(id)} style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer' }}>
            <Ico n={ico} s={15} />{label}
          </div>
        ))}
      </div>
      {tab === 'overview' && body(() => <ComponentOverview comp={comp} kind={kind} text={text} go={go} waypointIndex={waypointIndex} />)}
      {tab === 'run' && body(() => <ComponentRunTab comp={comp} kind={kind} text={text} />)}
      {tab === 'edit' && body(() => (
        <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
          <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Definition ({comp.relPath.endsWith('.md') ? 'md' : comp.relPath.endsWith('.ts') ? 'ts' : 'yaml'})</div>
          <ToolSourceEditor key={comp.relPath} initial={text} mode={comp.relPath.endsWith('.ts') ? { name: 'javascript', typescript: true } : 'yaml'} target={{ path: comp.relPath }} caveat="Changes take effect on the next run." />
        </div>
      ))}
    </div>
  );
}

// A small chip/button vocabulary shared by the structured overviews.
function TmPill({ ico, color, children, onClick, title }) {
  const base = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 7, padding: '5px 9px', fontSize: 12, color: 'var(--text-standard)', maxWidth: '100%' };
  const inner = <React.Fragment>{ico ? <Ico n={ico} s={12} c={color || 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} /> : null}<span className="tb-mono" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{children}</span>{onClick ? <Ico n="arrow-up-right" s={11} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} /> : null}</React.Fragment>;
  return onClick
    ? <button onClick={onClick} title={title} style={{ ...base, cursor: 'pointer' }}>{inner}</button>
    : <span title={title} style={base}>{inner}</span>;
}

function TmSection({ label, children }) {
  return <div style={{ marginBottom: 18 }}><div className="tb-eyebrow" style={{ marginBottom: 8 }}>{label}</div>{children}</div>;
}

// Wrap a component's recorded `tools:` steps into a one-step trail the daemon can
// execute directly (same shape the Tools Test tab uses).
function buildStepsRunYaml(label, tools) {
  const lines = ['- config:', `    title: ${JSON.stringify('Run: ' + label)}`, '- prompts:', `  - step: ${JSON.stringify('Run: ' + label)}`, '    recording:', '      tools:'];
  const dumped = (window.jsyaml ? window.jsyaml.dump(tools, { lineWidth: -1 }) : '').replace(/\n+$/, '');
  dumped.split('\n').forEach((l) => lines.push(l ? '      ' + l : l));
  return lines.join('\n');
}

function ComponentRunTab({ comp, kind, text }) {
  useLucide();
  const devices = TB.useDevices();
  const [deviceId, setDeviceId] = useStickyState('tb-comp-run-device', '');
  const [busy, setBusy] = React.useState(false);
  const [note, setNote] = React.useState(null);
  const [outcome, setOutcome] = React.useState(null);
  React.useEffect(() => { setOutcome(null); setNote(null); }, [comp.relPath]);
  const doc = React.useMemo(() => tmParseYaml(text), [text]);
  const steps = doc && Array.isArray(doc.tools) ? doc.tools : [];
  const list = devices.data || [];
  const picked = list.find((d) => d.id === deviceId) || null;
  const yaml = steps.length ? buildStepsRunYaml(comp.label, steps) : '';

  const run = async () => {
    if (!picked || busy || !steps.length) return;
    setBusy(true); setNote(null); setOutcome(null);
    const resolved = await TB.resolveRunDevice({ platform: picked.platform }, picked.id);
    if (resolved.error) { setBusy(false); setNote({ ok: false, msg: resolved.error }); return; }
    const connected = await TB.connectDevice(resolved.trailblazeDeviceId);
    if (!connected) { setBusy(false); setNote({ ok: false, msg: 'Could not connect to the device.' }); return; }
    const r = await TB.runToolQuick(yaml, resolved.trailblazeDeviceId);
    setBusy(false);
    setOutcome({ ok: r.success === true, text: r.success === true ? (r.result || 'OK') : (r.error || 'Run failed'), ms: r.durationMs || 0 });
  };

  return (
    <div style={{ marginTop: 4 }}>
      <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginBottom: 12 }}>
        Runs this {(TM_COMP_TYPES[kind] || {}).singular}'s {steps.length} step{steps.length === 1 ? '' : 's'} on the selected device, as-is. {kind === 'shortcuts' ? "Assumes the device is already at the shortcut's “from” state." : 'Drives from the current state to the target waypoint.'}
      </div>
      {steps.length === 0 ? <div className="tb-sub" style={{ fontSize: 12.5 }}>No steps to run.</div> : (
        <div className="tb-card" style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 6 }}>Steps it will run</div>
            <pre className="tb-mono" data-selectable style={{ margin: 0, fontSize: 11.5, lineHeight: 1.6, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '9px 11px', maxHeight: 220, overflow: 'auto' }}>{yaml}</pre>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {note ? <span style={{ fontSize: 12, color: note.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{note.msg}</span> : null}
            <div style={{ flex: 1 }} />
            <Select value={picked ? deviceId : ''} onChange={(e) => setDeviceId(e.target.value)}
              options={[['', list.length === 0 ? 'No device connected' : 'Pick a device…'], ...list.map((d) => [d.id, d.name + ' · ' + d.platform])]} style={{ maxWidth: 230 }} />
            <Btn kind="primary" sm ico="play" onClick={run} disabled={!picked || busy}>{busy ? 'Running…' : 'Run on device'}</Btn>
          </div>
          {outcome ? (
            <div style={{ borderTop: '1px solid var(--tb-hairline)', paddingTop: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 7 }}>
                <Ico n={outcome.ok ? 'circle-check' : 'circle-x'} s={14} c={outcome.ok ? 'var(--tb-pass)' : 'var(--tb-fail)'} />
                <span style={{ fontSize: 12.5, fontWeight: 600, color: outcome.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)' }}>{outcome.ok ? 'Succeeded' : 'Failed'}</span>
                {outcome.ms > 0 ? <span className="tb-sub" style={{ fontSize: 11 }}>{(outcome.ms / 1000).toFixed(1)}s</span> : null}
                <div style={{ flex: 1 }} />
                <Btn sm ico="x" onClick={() => setOutcome(null)}>Dismiss</Btn>
              </div>
              <pre className="tb-mono" data-selectable style={{ margin: 0, fontSize: 11.5, lineHeight: 1.55, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 240, overflow: 'auto', background: outcome.ok ? 'rgba(0,224,19,.05)' : 'rgba(248,71,82,.07)', border: '1px solid ' + (outcome.ok ? 'rgba(0,224,19,.25)' : 'rgba(248,71,82,.3)'), borderRadius: 8, padding: '9px 11px', color: 'var(--text-subtle-variant)' }}>{outcome.text}</pre>
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

// A toolset lists tool IDs; the LLM-facing descriptions live in the tools catalog.
// Join them into a clickable table so you can read what each tool does and jump to it.
function ToolsetToolsTable({ tools, go }) {
  useLucide();
  const toolsResult = TB.useTools();
  const byId = React.useMemo(() => {
    const m = {};
    (toolsResult.data || []).forEach((t) => { if (m[t.id] == null) m[t.id] = t; });
    return m;
  }, [toolsResult.data]);
  if (!tools.length) return <div className="tb-sub" style={{ fontSize: 12.5 }}>No tools listed.</div>;
  return (
    <div className="tb-card" style={{ padding: 0, overflow: 'hidden' }}>
      {tools.map((id, i) => {
        const t = byId[id];
        const desc = (t && (t.llmDescription || t.description)) || '';
        return (
          <div key={id} role="button" onClick={() => go && go('tools', { tool: id })} title={'Open ' + id + ' in Tools'}
            style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '11px 13px', borderTop: i ? '1px solid var(--tb-hairline)' : 'none', cursor: 'pointer' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--bg-standard)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}>
            <Ico n="wrench" s={13} c={t ? (TM_FLAVOR_TINT[t.flavor] || 'var(--text-subtle-variant)') : 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto', marginTop: 2 }} />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="tb-mono" style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-standard)' }}>{id}</div>
              {desc
                ? <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginTop: 3, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{desc}</div>
                : <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 3, fontStyle: 'italic' }}>{toolsResult.loading ? 'Loading…' : 'No description'}</div>}
            </div>
            <Ico n="arrow-up-right" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto', marginTop: 2 }} />
          </div>
        );
      })}
    </div>
  );
}

function ComponentOverview({ comp, kind, text, go, waypointIndex }) {
  useLucide();
  const doc = React.useMemo(() => tmParseYaml(text), [text]);
  if (!doc || typeof doc !== 'object') {
    return <SearchableText text={text} language="yaml" fontSize={12} minHeight={240} />;
  }
  const desc = typeof doc.description === 'string' ? doc.description : '';
  const Desc = desc ? <p className="tb-sub" style={{ fontSize: 13, lineHeight: 1.55, margin: '0 0 18px' }}>{desc}</p> : null;
  const wpLink = (id) => {
    const rel = waypointIndex && waypointIndex[id];
    return <TmPill key={id} ico="map-pin" color="var(--tb-pass)" onClick={rel ? (() => go && go('waypoints', { sel: rel })) : null} title={rel ? 'Open waypoint' : id}>{id}</TmPill>;
  };
  const wrap = (children) => <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{children}</div>;
  const dump = (v) => { try { return window.jsyaml ? window.jsyaml.dump(v).trimEnd() : JSON.stringify(v, null, 2); } catch (_) { return JSON.stringify(v); } };

  const renderSteps = (tools) => (Array.isArray(tools) ? tools : []).map((step, i) => {
    const k = step && typeof step === 'object' ? Object.keys(step)[0] : null;
    const args = k ? step[k] : null;
    return (
      <div key={i} style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '6px 0', borderBottom: '1px solid var(--tb-hairline)' }}>
        <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto', minWidth: 16 }}>{i + 1}</span>
        <span className="tb-mono" style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-standard)', flex: '0 0 auto' }}>{k || String(step)}</span>
        {args != null && args !== '' ? <span className="tb-mono tb-sub" style={{ fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{typeof args === 'object' ? JSON.stringify(args) : String(args)}</span> : null}
      </div>
    );
  });

  const matcherList = (arr) => (Array.isArray(arr) ? arr : []).map((m, i) => (
    <div key={i} className="tb-card" style={{ padding: '10px 12px', marginBottom: 8 }}>
      {m && m.description ? <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-standard)', marginBottom: m.selector ? 6 : 0 }}>{m.description}</div> : null}
      {m && m.selector ? <pre className="tb-mono" style={{ fontSize: 11, lineHeight: 1.5, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text-subtle-variant)' }}>{dump(m.selector)}</pre> : null}
      {(!m || (!m.description && !m.selector)) ? <pre className="tb-mono" style={{ fontSize: 11, margin: 0, whiteSpace: 'pre-wrap' }}>{dump(m)}</pre> : null}
    </div>
  ));

  if (kind === 'toolsets') {
    const tools = Array.isArray(doc.tools) ? doc.tools : [];
    const drivers = Array.isArray(doc.drivers) ? doc.drivers : [];
    return (
      <div>
        {Desc}
        {drivers.length ? <TmSection label="Drivers">{wrap(drivers.map((d) => <TmPill key={d} ico="cpu">{d}</TmPill>))}</TmSection> : null}
        <TmSection label={`Tools · ${tools.length}`}><ToolsetToolsTable tools={tools.map((t) => (typeof t === 'string' ? t : (t && t.id) || String(t)))} go={go} /></TmSection>
      </div>
    );
  }
  if (kind === 'waypoints') {
    const req = Array.isArray(doc.required) ? doc.required : [];
    const forb = Array.isArray(doc.forbidden) ? doc.forbidden : [];
    return (
      <div>
        {Desc}
        <TmSection label={`Required · ${req.length}`}>{req.length ? matcherList(req) : <div className="tb-sub" style={{ fontSize: 12.5 }}>None.</div>}</TmSection>
        {forb.length ? <TmSection label={`Forbidden · ${forb.length}`}>{matcherList(forb)}</TmSection> : null}
      </div>
    );
  }
  if (kind === 'shortcuts') {
    const sc = doc.shortcut || {};
    const tools = Array.isArray(doc.tools) ? doc.tools : [];
    return (
      <div>
        {Desc}
        <TmSection label="Navigation">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            {sc.from ? wpLink(sc.from) : <span className="tb-sub">any state</span>}
            <Ico n="arrow-right" s={16} c="var(--text-subtle)" />
            {sc.to ? wpLink(sc.to) : <span className="tb-sub">?</span>}
          </div>
        </TmSection>
        <TmSection label={`Steps · ${tools.length}`}>{tools.length ? <div>{renderSteps(tools)}</div> : <div className="tb-sub" style={{ fontSize: 12.5 }}>No steps.</div>}</TmSection>
      </div>
    );
  }
  if (kind === 'trailheads') {
    const th = doc.trailhead || {};
    const tools = Array.isArray(doc.tools) ? doc.tools : [];
    return (
      <div>
        {Desc}
        <TmSection label="Bootstraps to">{th.to ? wpLink(th.to) : <span className="tb-sub">a known waypoint</span>}</TmSection>
        <TmSection label={`Steps · ${tools.length}`}>{tools.length ? <div>{renderSteps(tools)}</div> : <div className="tb-sub" style={{ fontSize: 12.5 }}>No steps.</div>}</TmSection>
      </div>
    );
  }
  return <SearchableText text={text} language="yaml" fontSize={12} minHeight={240} />;
}

function TrailmapComponentContextMenu({ menu, onClose }) {
  useLucide();
  React.useEffect(() => {
    const k = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);
  const c = menu.comp;
  const copy = (t) => { try { navigator.clipboard.writeText(t); } catch (_) {} };
  const items = [
    { ico: 'folder-open', label: 'Open in Finder', fn: () => { onClose(); TB.revealToolSource({ path: c.relPath }); } },
    { sep: true },
    { ico: 'copy', label: 'Copy name', fn: () => { onClose(); copy(c.name); } },
    { ico: 'copy', label: 'Copy path', fn: () => { onClose(); copy(c.relPath); } },
  ];
  const left = Math.min(menu.x, window.innerWidth - 220);
  const top = Math.min(menu.y, window.innerHeight - 200);
  return (
    <React.Fragment>
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }}></div>
      <div className="tb-card" style={{ position: 'fixed', left, top, zIndex: 61, minWidth: 196, padding: 5, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
        {items.map((it, i) => it.sep
          ? <div key={i} style={{ height: 1, background: 'var(--tb-hairline)', margin: '4px 6px' }} />
          : (
            <div key={i} onClick={it.fn} style={{ display: 'flex', alignItems: 'center', gap: 9, cursor: 'pointer', padding: '7px 10px', borderRadius: 6, fontSize: 13 }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--bg-standard)'; }} onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}>
              <Ico n={it.ico} s={15} c="var(--text-subtle-variant)" />
              <span style={{ color: 'var(--text-standard)' }}>{it.label}</span>
            </div>
          ))}
      </div>
    </React.Fragment>
  );
}

window.ComponentTypeScreen = ComponentTypeScreen;
window.NewComponentModal = NewComponentModal;

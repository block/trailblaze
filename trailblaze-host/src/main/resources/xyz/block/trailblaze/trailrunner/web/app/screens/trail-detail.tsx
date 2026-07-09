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
    // A trail can split steps across several top-level `- prompts:` blocks; collect
    // every array-valued one so later blocks aren't dropped.
    const prompts = doc.filter((it) => it && Array.isArray(it.prompts)).flatMap((it) => it.prompts);
    if (!prompts.length) return null;
    return prompts.map((pr) => {
      const kind = pr.verify != null ? 'verify' : 'step';
      const text = pr.step || pr.verify || pr.prompt || '';
      const rec = pr.recording && Array.isArray(pr.recording.tools) ? pr.recording.tools : null;
      const tools = [];
      if (rec) {
        for (const t of rec) {
          if (t && typeof t === 'object') { const name = Object.keys(t)[0]; tools.push({ name, args: t[name] }); }
          else if (typeof t === 'string') tools.push({ name: t, args: null });
        }
      }
      return { kind, text: String(text), recorded: !!rec, tools };
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

function StepRow({ step, idx, go, toolMap = new Map() }) {
  useLucide();
  const isVerify = step.kind === 'verify';
  const tools = step.tools || [];
  return (
    <div className="tb-card" style={{ marginBottom: 10, overflow: 'hidden' }}>
      <div style={{ padding: '12px 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <Chip tone={isVerify ? 'blue' : 'purple'}>{(isVerify ? 'VERIFY' : 'STEP') + (idx != null ? ' ' + (idx + 1) : '')}</Chip>
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
// draft file (not in the workspace index) reuse this view. `configTrail` overrides the config card.
function StepsMode({ trail, go, yaml, configTrail }) {
  const detail = TB.useTrailDetail(yaml == null && trail ? trail.id : null);
  const effYaml = yaml != null ? yaml : detail.data?.yaml;
  const loading = yaml == null && detail.loading;
  const parsed = React.useMemo(() => (effYaml ? parseTrailSteps(effYaml) : null), [effYaml]);
  const steps = parsed || (yaml == null ? (detail.data?.steps || []).map((s) => ({ kind: s.kind, text: s.text, recorded: (s.tools || []).length > 0, tools: (s.tools || []).map((n) => ({ name: n, args: null })) })) : []);
  const catalog = TB.useTools();
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

  return (
    <div style={{ display: 'flex', gap: 20 }}>
      <div style={{ flex: 1.7, minWidth: 0 }}>
        {loading && <Skeleton rows={4} />}
        {!loading && steps.length === 0 && (
          <EmptyState ico="list" title="No steps" sub="This trail has no parsed steps, or couldn't be loaded." />
        )}
        {steps.map((s, i) => <StepRow key={i} step={s} idx={i} go={go} toolMap={toolMap} />)}
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

// `runs` (optional): an explicit list of run rows (used by the drafts popup, which scopes to the
// draft's own sessions). Otherwise sessions are fetched and matched to this trail.
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

// The Trails "Implementations" tab — the same Steps × recordings board the Drafts screen uses, but
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
  const hasBlaze = !!blazeEntry;
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
    const blazeP = hasBlaze ? TB.fetchTrailFolderFile(folderId, 'blaze.yaml') : Promise.resolve('');
    const varsP = Promise.all(variants.map((v) => TB.fetchTrailFolderFile(folderId, v.name).then((c) => [v.name, parseTrailYaml(c || '')])));
    Promise.all([blazeP, varsP]).then(([bz, entries]) => {
      if (cancelled) return;
      setBlazeYaml(bz || '');
      setVariantDocs(Object.fromEntries(entries));
    });
    return () => { cancelled = true; };
  }, [folderId, memberKey, reloadTick]);

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
        variants={variants} variantDocs={variantDocs} blazeConfigRows={blazeConfigRows}
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

// Shared tabbed detail view for a SINGLE trail OR a draft file: Steps · Edit · Runs (+ Variants when
// given a sibling list). Edit is the YAML+tools editor. Used inline on the Trails screen (for one
// selected trail) and inside the Drafts editor popup. Tab can be controlled (tab + onTab) or internal
// (defaultTab). Data comes in via props (yaml, runs, tools, onSave) so it works for both a workspace
// trail and a draft file. The bundle-level Steps × recordings matrix is a SEPARATE folder view
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
            {tab === 'steps' && <StepsMode trail={configTrail || trail} configTrail={configTrail} yaml={yaml} go={go} />}
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

Object.assign(window, { prettyVariant, VariantsMode, StepRow, parseTrailSteps, fmtArgValue, TrailConfigCard, StepsMode, YamlMode, RunsMode, TrailDetailView, TrailImplementationsBoard, VARIANT_PLAT });

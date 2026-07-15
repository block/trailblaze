// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function TrailsScreen({ go, openRun, initSel, initMode }) {
  const trails = TB.useTrails();
  const roots = TB.useTrailRoots();
  const favorites = TB.useFavorites();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const toolsCatalog = TB.useTools();
  const [mode, setMode] = React.useState(initMode || 'steps');
  const [selected, setSelected] = React.useState(null);
  // When a bundle FOLDER row is selected, this holds its acc and the right pane shows the
  // Implementations matrix (folder overview). null = a single trail (`selected`) is in focus and its
  // Steps/Edit/Runs detail shows instead. Exactly one of {folderView, single-trail detail} is active.
  const [folderView, setFolderView] = React.useState(null);
  // Set when a matrix cell is opened into a trail's Edit tab: `{ id, text, kind }`. The editor jumps
  // to + flashes the step whose YAML matches `text`. Scoped to `id` so it only fires for that trail
  // (a later tree/variant selection changes `current` and the gate below drops the stale highlight).
  const [editHighlight, setEditHighlight] = React.useState(null);
  React.useEffect(() => {
    // An explicit selection handoff (e.g. a just-saved trail) also dismisses any lingering
    // folder overview - otherwise the previous visit's pane wins over the requested trail.
    if (initSel) { setSelected(initSel); setFolderView(null); }
    if (initMode) setMode(initMode);
  }, [initSel, initMode]);
  const [query, setQuery] = React.useState('');
  const [filterPlatform, setFilterPlatform] = useStickyState('tb-trails-platform', 'all');
  const [filterTarget, setFilterTarget] = useStickyState('tb-trails-target', 'all');
  const [filterFormat, setFilterFormat] = useStickyState('tb-trails-format', 'all');
  const [sortBy, setSortBy] = useStickyState('tb-trails-sort', 'path');
  // Derive the platform of the active target's selected devices (only when they all share
  // one platform — mixed/none means don't force a platform filter).
  const gtPlatform = React.useMemo(() => {
    const list = devices.data || [];
    const plats = new Set(((gt && gt.deviceIds) || []).map((id) => { const d = list.find((x) => x.id === id); return d && d.platform; }).filter(Boolean));
    return plats.size === 1 ? [...plats][0] : null;
  }, [gt && (gt.deviceIds || []).join(','), devices.data]);
  // The active target scopes the Trails list: prefill the target (and platform) filters from
  // it so the tree shows only that target's trails. Re-runs when the target/platform changes;
  // manual filter tweaks afterward persist until the target changes again.
  React.useEffect(() => {
    if (!gt || !gt.target) return;
    if (!trails.data) return; // wait for the list before deciding
    // Only scope to targets that actually exist in the loaded trails - a stale sticky target
    // (e.g. pointing at a deleted trailmap) would otherwise filter every trail out ("0 of N").
    setFilterTarget(targets.includes(gt.target) ? gt.target : 'all');
    // Reset to 'all' when the selection has no single platform (mixed devices or none),
    // so a previously-forced platform doesn't keep hiding the other variants.
    setFilterPlatform(gtPlatform || 'all');
  }, [gt && gt.target, gtPlatform, trails.data]);
  const [addBusy, setAddBusy] = React.useState(false);
  const [addError, setAddError] = React.useState(null);
  const [editedOnly, setEditedOnly] = React.useState(false);
  const [editedPaths, setEditedPaths] = React.useState(null);
  const [newOpen, setNewOpen] = React.useState(false);
  const [newPath, setNewPath] = React.useState('');
  const [newTitle, setNewTitle] = React.useState('');
  const [creating, setCreating] = React.useState(false);
  const [createErr, setCreateErr] = React.useState(null);
  const [treeDir, setTreeDir] = React.useState(null); // tree-cursor directory (acc, includes root label)
  // Pushed YAML editor: a file from the bundle matrix slides in from the right over the board (mac
  // NavigationStack feel), instead of navigating away to the trail's detail page. Just the editor.
  const [viewFile, setViewFile] = React.useState(null); // { name, content, highlight, platform }
  const [pushShown, setPushShown] = React.useState(false); // false while parked off-screen right
  const [boardReloadKey, setBoardReloadKey] = React.useState(0); // bump to remount the folder board after a pushed-editor save
  // "Migrate to unified" flow for the selected legacy bundle folder. `migrate` is the modal phase:
  // null (closed) | 'confirm' | 'busy' | a result { ok, outputName?, steps?, drift?, error? }.
  const [migrate, setMigrate] = React.useState(null);
  const editorDirty = React.useRef(false); // set by the editor; guards close-on-discard
  const reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useLucide();

  React.useEffect(() => {
    if (!editedOnly) return;
    let cancelled = false;
    TB.fetchEditedTrails().then((p) => { if (!cancelled) setEditedPaths(p); });
    return () => { cancelled = true; };
  }, [editedOnly, trails.data]);

  const favSet = React.useMemo(() => new Set(favorites.ids), [favorites.ids]);
  const onToggleFavorite = favorites.toggle;

  const onRemoveRoot = React.useCallback(async (path) => {
    setAddBusy(true);
    await TB.removeTrailRoot(path);
    setAddBusy(false);
    roots.reload();
    trails.reload();
  }, [roots, trails]);

  const onChangeWorkspace = React.useCallback(async () => {
    setAddError(null);
    const path = await TB.pickDirectoryViaShell(roots.data?.primary);
    if (!path) return;
    setAddBusy(true);
    // switchWorkspace persists the pick and broadcasts `tb:workspace-changed`, so every
    // useFetched-backed list (including this screen's trails + roots) re-resolves against the new
    // folder — no explicit roots/trails reload needed (it would just double-fetch).
    const res = await TB.switchWorkspace(path);
    setAddBusy(false);
    if (!res.ok) {
      setAddError(res.error || 'Could not set workspace');
      return;
    }
    setSelected(null);
  }, [roots]);

  const allTrails = trails.data || [];

  // Effective platform: the config's `platform`, else derived from the file name (e.g.
  // `ios-iphone.trail.yaml` → ios, `android-phone.trail.yaml` → android). Many recordings carry the
  // platform only in the file name, not the blaze config, so the platform list + filter key on this
  // rather than `t.platform` alone — otherwise those files read as "Not specified" and drop out.
  const platOf = (t) => (t && t.platform) || (t && derivePlatformFromTrail(t)) || null;

  const platforms = React.useMemo(() => {
    const s = new Set(allTrails.map(platOf).filter(Boolean));
    return [...s].sort();
  }, [allTrails]);

  const targets = React.useMemo(() => {
    const s = new Set(allTrails.map((t) => t.target).filter(Boolean));
    return [...s].sort();
  }, [allTrails]);
  const hasUntargeted = React.useMemo(() => allTrails.some((t) => !t.target), [allTrails]);
  const hasUnplatformed = React.useMemo(() => allTrails.some((t) => !platOf(t)), [allTrails]);

  const filteredTrails = React.useMemo(() => {
    // A blaze is a cross-platform, prompt-only definition (no platform of its own), so it satisfies
    // ANY platform filter — that keeps a bundle that's just a blaze.yaml (no recordings yet) from
    // dropping out under a platform filter.
    const platOk = (t) => filterPlatform === 'all' || t.kind === 'blaze' || (filterPlatform === '__none__' ? !platOf(t) : platOf(t) === filterPlatform);
    const targetOk = (t) => filterTarget === 'all' || (filterTarget === '__none__' ? !t.target : t.target === filterTarget);
    let arr = allTrails;
    // Platform/target filter at the BUNDLE level, not per file: keep a whole folder (all its files)
    // when it has any file matching the platform AND any file matching the target. Filtering individual
    // files would split bundles — you'd see only the matching variant and lose the folder-as-one-trail
    // view (and a blaze.yaml, which has no platform, would always drop out under a platform filter).
    if (filterPlatform !== 'all' || filterTarget !== 'all') {
      const byFolder = new Map();
      for (const t of allTrails) { const f = t.folder || ''; if (f === '') continue; if (!byFolder.has(f)) byFolder.set(f, []); byFolder.get(f).push(t); }
      const okFolders = new Set();
      for (const [f, sibs] of byFolder) { if (sibs.some(platOk) && sibs.some(targetOk)) okFolders.add(f); }
      // Root-level files (no folder) are NOT a bundle — matching buildTrailTree / countTrailBundles,
      // which only bundle non-root folders — so filter them per file instead of keeping or dropping
      // every root file together.
      arr = allTrails.filter((t) => { const f = t.folder || ''; return f === '' ? (platOk(t) && targetOk(t)) : okFolders.has(f); });
    }
    if (filterFormat !== 'all') {
      // Format is a per-file property (bundle vs unified vs blaze); classify against the FULL trail set
      // so folder-bundle membership is judged the same way the tree derives it, then keep matches.
      const fmt = TB.trailFormatMap(allTrails);
      arr = arr.filter((t) => fmt.get(t.id) === filterFormat);
    }
    if (editedOnly) {
      const set = new Set(editedPaths || []);
      arr = arr.filter((t) => (t.rootIdx || 0) === 0 && set.has(t.path));
    }
    return arr;
  }, [allTrails, filterPlatform, filterTarget, filterFormat, editedOnly, editedPaths]);

  const rows = React.useMemo(() => {
    if (sortBy === 'path') return TB.buildTrailTree(filteredTrails, trails.extra && trails.extra.folders);
    // Sorted views are flat (folder structure would fight the sort) but still bundle-aware: one row
    // per logical trail, sorted by title / priority — not every device file flattened out of context.
    return TB.buildTrailBundleRows(filteredTrails, sortBy);
  }, [filteredTrails, sortBy, trails.extra]);
  // A selection may arrive as a bundle-folder id (a save returns the folder, not a
  // file) - resolve it to the folder's first trail. Reactive on purpose: right after a save
  // the refreshed list may not have landed yet, and this picks the trail up when it does.
  // No arbitrary default: `selected` is plain React state so a reload clears it while the sticky
  // filters survive - falling back to allTrails[0] put a trail the user never opened (often
  // filtered out of the visible tree) in the detail pane, and Run launched it.
  const current = allTrails.find((t) => t.id === selected)
    || (selected ? allTrails.find((t) => (t.id || '').startsWith(selected + '/')) : null)
    || null;
  const [treeW, startTreeDrag] = useResizableWidth('tb-trails-tree-w', 280, 200, 600);

  // The list is "scoped by target" when the target filter still matches the active target —
  // drives the clear scope banner at the bottom of the tree.
  const scopedByTarget = !!(gt && gt.target && filterTarget === gt.target);

  const folderOf = (p) => { const i = (p || '').lastIndexOf('/'); return i < 0 ? '' : p.slice(0, i); };
  const variants = React.useMemo(() => {
    if (!current) return [];
    const cf = folderOf(current.path);
    return allTrails.filter((t) => folderOf(t.path) === cf).sort((a, b) => (a.path || '').localeCompare(b.path || ''));
  }, [allTrails, current]);
  const detailTab = ['steps', 'edit', 'runs', 'variants'].includes(mode) ? mode : 'steps';

  // ── folder (bundle) matrix view ──
  // The Implementations matrix is a FOLDER-level overview, shown only when a bundle folder row is
  // selected (folderView = its acc). Selecting a single trail shows that trail's own detail instead.
  // (Progressive disclosure, ch07: the folder reads as the overview, a leaf as the detail.)
  const folderEntries = React.useMemo(
    () => (folderView ? allTrails.filter((t) => t.folder === folderView) : []),
    [allTrails, folderView],
  );
  const fvBlaze = React.useMemo(() => folderEntries.find((t) => (t.kind || 'trail') === 'blaze') || null, [folderEntries]);
  const fvVariants = React.useMemo(() => folderEntries.filter((t) => (t.kind || 'trail') !== 'blaze'), [folderEntries]);
  // "Migrate to unified" applies ONLY to a legacy per-platform bundle — every file must be v1. The
  // folder view is also reachable (via the back-arrow) for a directory of already-unified trails;
  // offering migrate there would merge + delete distinct trails, so gate the button on all-v1. (The
  // server enforces the same guard; this just hides an action that would only ever error/harm.)
  const fvAllV1 = React.useMemo(
    () => folderEntries.length > 0 && folderEntries.every((t) => (t.format || 'v1') === 'v1'),
    [folderEntries],
  );
  // /api/folder/* folder id = a member trail's id minus its file segment ("0/sample/login/x" -> "0/sample/login").
  // NOT `folder` (the display path with the "trails/…" workspace-root prefix the id scheme omits).
  const fvFolderId = folderEntries.length ? folderEntries[0].id.split('/').slice(0, -1).join('/') : '';
  const fvRel = fvFolderId.split('/').slice(1).join('/');
  const fvTarget = (folderEntries.find((t) => t.target) || {}).target || null;
  const fvName = folderView ? folderView.split('/').filter(Boolean).pop() : '';
  // The bundle's display title: prefer the blaze's authored title; else humanize the folder slug into a
  // readable name. NOT the raw slug — that just echoes the last breadcrumb segment verbatim (ch07
  // redundancy) and a long kebab string reads awkwardly as a bold headline (ch03).
  const fvTitle = (() => {
    const bt = fvBlaze && fvBlaze.title;
    return bt && bt.trim() && bt.trim().toLowerCase() !== 'blaze' ? bt.trim() : TB.humanizeTarget(fvName);
  })();
  // Open a file from the matrix as a pushed YAML editor that slides in from the right OVER the board
  // (no navigating away to the trail's detail page — just the editor). Every entry point here (the
  // per-cell "Edit … jump to this step", the header "Edit YAML" link, the off-blaze "open to view")
  // is a view/edit-this-file intent. An optional `highlight` ({text, kind}) jumps the editor to that
  // step's YAML block. Content is fetched from the bundle folder, not the workspace index.
  // Convert the selected legacy bundle folder into a single unified `<folder>.trail.yaml` via the
  // server-side migrator, then jump to the new file. The per-platform files (+ blaze.yaml) are folded
  // in and deleted (git-recoverable). On refusal (trailhead / top-level tools / already migrated) the
  // server returns a reason we surface in the modal without touching any files.
  const doMigrate = async () => {
    // Cheap early-out; the real double-submit protection is the button's `disabled={busy}` (React
    // disables it between click events) plus the server-side idempotent "already migrated" guard.
    if (migrate === 'busy') return;
    setMigrate('busy');
    const r = await TB.migrateTrailFolder(fvFolderId);
    if (r && r.success) {
      // The migration itself succeeded — a reload failure must not strand the busy spinner (Cancel
      // and the backdrop are disabled while busy), so degrade to the success modal either way.
      try { await trails.reload(); } catch (e) { /* index refetches on next navigation */ }
      // Index id of the written file = folderId + its stem (server's authoritative outputName minus
      // the .trail.yaml suffix), NOT the client's folder name — trust what the server actually wrote.
      const stem = String(r.outputName || `${fvName}.trail.yaml`).replace(/\.trail\.yaml$/, '');
      const newId = `${fvFolderId}/${stem}`;
      setMigrate({ ok: true, outputName: r.outputName, steps: r.steps, drift: r.drift || [], removed: r.removed || [], newId });
    } else {
      setMigrate({ ok: false, error: (r && r.error) || 'Migration failed' });
    }
  };

  // Whole-folder delete (the trash button in the folder-view header). Deliberate and loud: the
  // native confirm names the folder and its file count; recovery is git-only for committed files.
  const doDeleteFolder = async () => {
    const fileCount = fvVariants.length + (fvBlaze ? 1 : 0);
    if (!window.confirm(`Delete "${fvName}" and the ${fileCount} file${fileCount === 1 ? '' : 's'} inside it? Committed files are recoverable via git; anything uncommitted is lost.`)) return;
    const r = await TB.deleteTrailFolder(fvFolderId);
    if (!r || r.ok === false) { window.alert((r && r.error) || 'Could not delete the folder.'); return; }
    setFolderView(null);
    try { await trails.reload(); } catch (e) { /* index refetches on next navigation */ }
  };

  const openFolderTrail = React.useCallback((name, highlight = null) => {
    const plat = (name.match(/^(android|ios|web)\./) || [])[1] || null;
    setViewFile({ name, content: null, highlight, platform: plat });
    requestAnimationFrame(() => setPushShown(true));
    TB.fetchTrailFolderFile(fvFolderId, name).then((content) =>
      setViewFile((vf) => (vf && vf.name === name ? { name, content: content || '(could not read file)', highlight, platform: plat } : vf)));
  }, [fvFolderId]);
  // Pop the pushed editor: guard unsaved edits, slide back out, unmount after the transition. Gate the
  // delayed unmount on the file name we're closing, so quickly opening another file before the 320ms
  // timeout fires doesn't clear the newly-opened one.
  const closeFile = () => {
    if (editorDirty.current && !window.confirm('Discard unsaved changes?')) return;
    const closing = viewFile && viewFile.name;
    setPushShown(false);
    window.setTimeout(() => { setViewFile((vf) => (vf && vf.name === closing ? null : vf)); editorDirty.current = false; }, reduce ? 0 : 320);
  };
  React.useEffect(() => {
    if (!viewFile) return;
    const onKey = (e) => { if (e.key === 'Escape') closeFile(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [viewFile]);

  // Trail YAML + tools palette for the shared detail view's Edit tab.
  const detail = TB.useTrailDetail(current ? current.id : null);
  const trailTools = React.useMemo(
    () => editorToolsFor(toolsCatalog.data, current && current.target, current && current.platform),
    [toolsCatalog.data, current && current.target, current && current.platform],
  );

  const openNewTrail = () => {
    // Prefer the directory the tree cursor is in. Its acc starts with the root
    // label; the create API wants a path relative to the primary workspace, so
    // strip that first segment — and only trust it when it IS the primary root
    // (files can't be created in extra roots).
    const wsName = ((roots.data && roots.data.primary) || '').split('/').filter(Boolean).pop() || '';
    let folder = null;
    if (treeDir != null) {
      const segs = treeDir.split('/').filter(Boolean);
      if (segs.length && segs[0] === wsName) folder = segs.slice(1).join('/');
    }
    if (folder == null) folder = current && current.path ? current.path.split('/').slice(0, -1).join('/') : '';
    setNewPath(folder ? folder + '/' : '');
    setNewTitle(''); setCreateErr(null); setNewOpen(true);
  };
  const newPathIsDir = newPath.trim().endsWith('/') && newPath.trim().replace(/\/+$/, '') !== '';
  const doCreateTrail = async () => {
    const isDir = newPathIsDir;
    const p = newPath.trim().replace(/^\/+|\/+$/g, '').replace(/\.trail\.yaml$/, '');
    if (!p) { setCreateErr('Enter a relative file path, e.g. sample/login/android-phone - end with / to create a folder'); return; }
    setCreating(true); setCreateErr(null);
    if (isDir) {
      const r = await TB.createTrailDir(p);
      setCreating(false);
      if (!r.success) { setCreateErr(r.error || 'Could not create the folder'); return; }
      setNewOpen(false);
      trails.reload();
      return;
    }
    const title = newTitle.trim() || p.split('/').pop().replace(/[-_]+/g, ' ');
    const yaml = ['- config:', `    title: ${JSON.stringify(title)}`, '- prompts:', '  - step: "<describe what this step accomplishes>"', ''].join('\n');
    const r = await TB.createTrail(p, yaml);
    setCreating(false);
    if (!r.success) { setCreateErr(r.error || 'Could not create the trail'); return; }
    setNewOpen(false);
    trails.reload();
    setSelected('0/' + p);
    setMode('edit');
  };

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      <TrailTree
        width={treeW}
        rows={rows}
        query={query}
        setQuery={setQuery}
        filterPlatform={filterPlatform}
        setFilterPlatform={setFilterPlatform}
        filterTarget={filterTarget}
        setFilterTarget={setFilterTarget}
        filterFormat={filterFormat}
        setFilterFormat={setFilterFormat}
        platforms={platforms}
        targets={targets}
        sortBy={sortBy}
        setSortBy={setSortBy}
        hasUntargeted={hasUntargeted}
        hasUnplatformed={hasUnplatformed}
        selected={selected}
        selectedFolder={folderView}
        onSelect={(id) => { setSelected(id); setFolderView(null); }}
        onSelectFolder={(acc) => { setFolderView(acc); }}
        total={TB.countTrailBundles(allTrails)}
        roots={roots.data}
        addError={addError}
        addBusy={addBusy}
        onRemoveRoot={onRemoveRoot}
        onChangeWorkspace={onChangeWorkspace}
        help={(
          <HelpButton title="How trails work" align="left"
            sub="A trail is a UI test written in YAML. Each step states its intent in plain language; a recording pins the exact tool calls that accomplish it on a given device, so replays are deterministic with zero LLM calls. The folder is the logical trail; the files inside are its per-device variants.">
            <HelpCard ico="folder-tree" color="var(--tb-running)" title="The folder is the trail"
              snippet={'trails/\n  login/\n    blaze.yaml                # plain-language definition\n    android-phone.trail.yaml  # Android recording\n    ios-iphone.trail.yaml     # iOS recording\n    web.trail.yaml            # web recording'}
              foot="workspace anchor: trails/config/trailblaze.yaml">
              One directory per logical trail: a <span className="tb-mono">blaze.yaml</span> holds the plain-language definition (flame icon), and each <span className="tb-mono">&lt;device&gt;.trail.yaml</span> beside it is that device's recording. Pinning pins the directory, so the whole trail comes along.
            </HelpCard>
            <HelpCard ico="footprints" color="var(--tb-pass)" title="Steps · intent first"
              snippet={'- config:\n    title: "Login"\n    target: sample\n- prompts:\n  - step: "Open the dashboard"\n    recording:\n      tools:\n      - tapOn:\n          selector: { text: "Dashboard" }'}>
              Each step is a sentence describing <i>what</i> it accomplishes. "Tap sign in" survives a redesign; coordinates don't. The recording underneath captures the exact tool calls that did it. That step text is what makes repair possible: it preserves intent when the UI drifts.
            </HelpCard>
            <HelpCard ico="play" color="var(--tb-amber)" title="Replay is deterministic">
              Recorded steps replay verbatim with no LLM in the loop: fast, cheap, repeatable. Steps without a recording fall back to the agent (which records as it goes). Opt-in self-heal patches a recorded step that no longer matches the live screen and regenerates the recording; it's off by default so real failures stay loud.
            </HelpCard>
            <HelpCard ico="braces" color="var(--text-subtle)" title="Memory, tags & organizing">
              <span className="tb-mono">config.memory</span> pre-seeds variables. Write <span className="tb-mono">{'{{email}}'}</span> in steps or tool params and override per run. <span className="tb-mono">tags</span> partition the suite for selective runs. The tree mirrors your workspace folders; add extra trail directories with the folder button for trails living in another repo.
            </HelpCard>
          </HelpButton>
        )}
        onNewTrail={openNewTrail}
        onFocusDir={setTreeDir}
        editedOnly={editedOnly}
        setEditedOnly={setEditedOnly}
        favSet={favSet}
        onToggleFavorite={onToggleFavorite}
        targetScopeLabel={scopedByTarget ? (gt.label || gt.target) : null}
        targetScopePlatform={scopedByTarget && filterPlatform !== 'all' && filterPlatform !== '__none__' ? filterPlatform : null}
        onClearTargetScope={() => { setFilterTarget('all'); setFilterPlatform('all'); }}
      />
      {newOpen && (
        <div className="tb-overlay" onClick={() => setNewOpen(false)} style={{ alignItems: 'center', padding: 24 }}>
          <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, 94vw)', padding: 24 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
              <h2 className="tb-h2" style={{ fontSize: 17, margin: 0 }}>New trail</h2>
              <Btn sm ico="x" onClick={() => setNewOpen(false)}>Close</Btn>
            </div>
            <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 16 }}>Scaffolds a <span className="tb-mono">.trail.yaml</span> in your workspace - folders are created as needed. End the path with <span className="tb-mono">/</span> to create just a folder.</div>
            <div className="tb-eyebrow" style={{ marginBottom: 6 }}>{newPathIsDir ? 'Folder path' : 'File path'}</div>
            <input
              autoFocus value={newPath} spellCheck={false} placeholder="sample/login/android-phone"
              className="tb-mono"
              onChange={(e) => setNewPath(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') doCreateTrail(); if (e.key === 'Escape') setNewOpen(false); }}
              style={{ width: '100%', boxSizing: 'border-box', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 11px', color: 'var(--text-standard)', fontSize: 13, outline: 'none' }}
            />
            <div className="tb-sub" style={{ fontSize: 11, marginTop: 4 }}>{newPathIsDir ? 'Ends with / - creates an empty folder' : <>Relative to the workspace · <span className="tb-mono">.trail.yaml</span> is added for you</>}</div>
            {!newPathIsDir && (
              <>
                <div className="tb-eyebrow" style={{ margin: '14px 0 6px' }}>Title <span style={{ opacity: .6 }}>optional</span></div>
                <input
                  value={newTitle} placeholder="What this trail verifies"
                  onChange={(e) => setNewTitle(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') doCreateTrail(); if (e.key === 'Escape') setNewOpen(false); }}
                  style={{ width: '100%', boxSizing: 'border-box', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 11px', color: 'var(--text-standard)', fontSize: 13, outline: 'none', fontFamily: 'inherit' }}
                />
              </>
            )}
            {createErr && <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-danger-text)' }}>{createErr}</div>}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
              <Btn onClick={() => setNewOpen(false)}>Cancel</Btn>
              <Btn kind="primary" ico={newPathIsDir ? 'folder-plus' : 'file-plus-2'} onClick={doCreateTrail} disabled={creating} data-testid="create-trail">{creating ? 'Creating…' : (newPathIsDir ? 'Create folder' : 'Create trail')}</Btn>
            </div>
          </div>
        </div>
      )}
      {migrate && (
        <div className="tb-overlay" onClick={() => { if (migrate !== 'busy') setMigrate(null); }} style={{ alignItems: 'center', padding: 24 }}>
          <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px, 94vw)', padding: 24 }}>
            {(() => {
              const fileCount = fvVariants.length + (fvBlaze ? 1 : 0);
              const result = typeof migrate === 'object' ? migrate : null;
              if (result && result.ok) {
                return (
                  <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                      <Ico n="circle-check" s={18} c="var(--tb-pass)" />
                      <h2 className="tb-h2" style={{ fontSize: 17, margin: 0 }}>Migrated to unified</h2>
                    </div>
                    <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 14 }}>
                      Wrote <span className="tb-mono">{result.outputName}</span> ({result.steps} step{result.steps === 1 ? '' : 's'}).
                      {result.removed && result.removed.length
                        ? <> Removed: <span className="tb-mono">{result.removed.join(', ')}</span></>
                        : <> No input files were removed — check the folder before re-running.</>}
                    </div>
                    {result.drift && result.drift.length > 0 && (
                      <div style={{ marginBottom: 14, padding: '9px 11px', background: 'color-mix(in srgb, var(--tb-amber) 12%, transparent)', border: '1px solid color-mix(in srgb, var(--tb-amber) 40%, transparent)', borderRadius: 8 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, marginBottom: 4 }}>
                          <Ico n="triangle-alert" s={13} c="var(--tb-amber)" /> Review these drift warnings
                        </div>
                        <div className="tb-mono tb-sub" style={{ fontSize: 11, whiteSpace: 'pre-wrap', maxHeight: 180, overflowY: 'auto' }}>{result.drift.join('\n')}</div>
                      </div>
                    )}
                    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                      <Btn kind="primary" ico="arrow-right" onClick={() => { const id = result.newId; setMigrate(null); setFolderView(null); setSelected(id); }}>Open unified trail</Btn>
                    </div>
                  </>
                );
              }
              if (result && !result.ok) {
                return (
                  <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                      <Ico n="circle-x" s={18} c="var(--tb-danger-text)" />
                      <h2 className="tb-h2" style={{ fontSize: 17, margin: 0 }}>Couldn’t migrate</h2>
                    </div>
                    <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 16 }}>{result.error}</div>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                      <Btn onClick={() => setMigrate(null)}>Close</Btn>
                    </div>
                  </>
                );
              }
              // 'confirm' or 'busy'
              const busy = migrate === 'busy';
              return (
                <>
                  <h2 className="tb-h2" style={{ fontSize: 17, margin: '0 0 4px' }}>Migrate to unified</h2>
                  <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 16 }}>
                    Combine the <b>{fileCount}</b> file{fileCount === 1 ? '' : 's'} in <span className="tb-mono">{fvName}</span> into a single unified <span className="tb-mono">{fvName}.trail.yaml</span> — one file, every platform’s recording inline. The per-platform files{fvBlaze ? ' and blaze.yaml' : ''} are deleted — recoverable via git only if they were committed; uncommitted recordings are lost.
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                    <Btn onClick={() => setMigrate(null)} disabled={busy}>Cancel</Btn>
                    <Btn kind="primary" ico={busy ? 'loader-2' : 'layers'} spin={busy} disabled={busy} onClick={doMigrate}>{busy ? 'Migrating…' : 'Migrate to unified'}</Btn>
                  </div>
                </>
              );
            })()}
          </div>
        </div>
      )}
      <Splitter onDown={startTreeDrag} />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', position: 'relative' }}>
        {trails.loading && (
          <div style={{ padding: 32 }}><Skeleton rows={4} /></div>
        )}
        {!trails.loading && !folderView && !current && (
          <div style={{ padding: '60px 32px' }}>
            {allTrails.length === 0
              ? <EmptyState ico="folder-search" title="No trails found" sub="Set a trails directory in Settings, or open a workspace that contains .trail.yaml files." />
              : <EmptyState ico="mouse-pointer-click" title="Select a trail" sub="Pick a trail from the list to see its steps, edit it, or run it." />}
          </div>
        )}
        {/* Folder view: the bundle's Implementations matrix (steps × per-device recordings). Shown
            only when a bundle folder row is selected; clicking a column opens that trail's detail. */}
        {!trails.loading && folderView && folderEntries.length > 0 && (
          <>
            <div style={{ padding: '24px 28px 14px', flex: '0 0 auto', background: 'var(--bg-standard)', borderBottom: '1px solid var(--tb-hairline)', position: 'relative', zIndex: 6 }}>
              {/* No breadcrumb — the sidebar tree already shows where this lives. Tokens sit under the
                  title; the pin (favorite) moves to the far right. */}
              <DetailHeader
                title={fvTitle}
                meta={(
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                    {fvTarget && <Chip tone="blue">{fvTarget}</Chip>}
                    <Chip>{fvVariants.length} {fvVariants.length === 1 ? 'implementation' : 'implementations'}</Chip>
                    {fvBlaze && <Chip tone="amber"><Ico n="flame" s={11} /> blaze</Chip>}
                  </div>
                )}
                right={(
                  <>
                    {/* Fold this legacy per-platform bundle into a single unified .trail.yaml — the
                        format trails are moving toward. Only for all-v1 bundles (see fvAllV1). */}
                    {fvAllV1 && <Btn sm ico="layers" onClick={() => setMigrate('confirm')} title="Combine these per-platform files into one unified trail">Migrate to unified</Btn>}
                    <Btn sm ico="folder-symlink" onClick={() => TB.revealTrailFolder(fvFolderId)} title="Reveal this trail's folder in Finder" />
                    <Btn sm ico="trash-2" onClick={doDeleteFolder} title="Delete this trail's folder and every file in it" />
                    <button
                      onClick={() => onToggleFavorite(folderView)}
                      title={favSet.has(folderView) ? 'Unpin this trail’s directory' : 'Pin this trail’s directory (all platform variants)'}
                      style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, borderRadius: 8, border: 'none', background: 'transparent', cursor: 'pointer', color: favSet.has(folderView) ? 'var(--tb-amber)' : 'var(--text-subtle-variant)' }}
                    ><Ico n="pin" s={17} /></button>
                  </>
                )}
              />
            </div>
            <div style={{ flex: 1, minHeight: 0, padding: '16px 28px 18px', display: 'flex', flexDirection: 'column', overflowY: 'auto' }}>
              <TrailImplementationsBoard
                key={fvFolderId + ':' + boardReloadKey}
                folderId={fvFolderId} home={fvRel} blazeEntry={fvBlaze} variantEntries={fvVariants}
                deviceList={devices.data || []} target={fvTarget} platform={null}
                go={go} openRun={openRun} onOpenFile={openFolderTrail} reloadIndex={() => trails.reload()}
              />
            </div>
          </>
        )}
        {!trails.loading && !folderView && current && (
          <>
            <div style={{ padding: '24px 28px 14px', flex: '0 0 auto', background: 'var(--bg-standard)', borderBottom: '1px solid var(--tb-hairline)', position: 'relative', zIndex: 6 }}>
              {/* No breadcrumb (the sidebar shows location); tokens under the title; pin to the right. */}
              <DetailHeader
                title={current.title}
                leading={current.folder ? (
                  <button
                    onClick={() => { setFolderView(current.folder); setEditHighlight(null); }}
                    title="Back to the bundle’s implementations"
                    style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, borderRadius: 8, border: '1px solid var(--tb-hairline)', background: 'transparent', cursor: 'pointer', color: 'var(--text-subtle-variant)' }}
                  ><Ico n="arrow-left" s={17} /></button>
                ) : null}
                meta={(
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                    {current.kind === 'blaze' && <Chip tone="amber"><Ico n="flame" s={11} /> Blaze · {current.hasRecordedSteps ? 'recorded' : 'prompt-only'}</Chip>}
                    {current.target && <Chip tone="blue">{current.target}</Chip>}
                    {current.platform && <Chip>{current.platform}</Chip>}
                    {current.priority && <Chip tone="amber">{current.priority}</Chip>}
                    {(current.tags || []).map((t) => <Chip key={t}>{t}</Chip>)}
                  </div>
                )}
                right={(
                  <>
                    <Btn kind="primary" ico="play" onClick={() => openRun(current)}>Run trail</Btn>
                    <button
                      onClick={() => onToggleFavorite(current.folder)}
                      title={favSet.has(current.folder) ? 'Unpin this trail’s directory' : 'Pin this trail’s directory (all platform variants)'}
                      style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, borderRadius: 8, border: 'none', background: 'transparent', cursor: 'pointer', color: favSet.has(current.folder) ? 'var(--tb-amber)' : 'var(--text-subtle-variant)' }}
                    ><Ico n="pin" s={17} /></button>
                  </>
                )}
              />
            </div>
            {/* Single-trail detail (Steps · Edit · Runs · Variants). NOT keyed by id: switching trails keeps
                the view mounted so the Edit tab's Monaco editor + language-server socket are reused instead of
                rebuilt on every switch. TrailDetailView resets the editor to the new trail's content on switch
                (via its `resetKey`); the Steps/Runs panes are prop-driven and re-render on their own. */}
            <div style={{ flex: 1, minHeight: 0, padding: '16px 28px 18px', display: 'flex', flexDirection: 'column' }}>
              <TrailDetailView
                trail={current}
                configTrail={current}
                // While switching trails, useTrailDetail keeps the PREVIOUS trail's data during the
                // reload — surfacing it here would show the old trail's steps/YAML under the new
                // trail's header until the fetch lands (reads as "stuck, nothing happened"). Gate on
                // `!loading` so a trail switch yields null → the detail view shows its loading state
                // and re-derives for the new trail. (A same-trail refresh keeps loading=false, so no
                // flicker there.)
                yaml={(detail.data && !detail.loading) ? (detail.data.yaml || '') : null}
                editable
                tools={trailTools}
                onSave={(t) => TB.updateTrail(current.id, t)}
                onSaved={() => { detail.reload(); trails.reload(); }}
                go={go}
                openRun={openRun}
                variants={variants}
                currentId={current.id}
                onSelectVariant={(id) => { setSelected(id); setFolderView(null); }}
                tab={detailTab}
                onTab={setMode}
                highlight={editHighlight && current && editHighlight.id === current.id ? editHighlight : null}
              />
            </div>
          </>
        )}
        {/* dim the board behind the push (cheap opacity fade, synced to the slide) */}
        {viewFile && <div style={{ position: 'absolute', inset: 0, zIndex: 9, background: 'rgba(0,0,0,.32)', pointerEvents: 'none', opacity: pushShown ? 1 : 0, transition: reduce ? 'none' : 'opacity 320ms cubic-bezier(.32,.72,0,1)' }} />}
        {/* Pushed YAML editor — slides in from the right over the matrix; back-arrow / Esc pops it. Just
            the YAML editor (no Steps/Runs tabs); blaze.yaml is prose-only so it gets no tool palette. */}
        {viewFile && (() => {
          const isBlaze = viewFile.name === 'blaze.yaml';
          const scopedTools = editorToolsFor(toolsCatalog.data, fvTarget, viewFile.platform);
          return (
            <div style={{ position: 'absolute', inset: 0, zIndex: 10, display: 'flex', flexDirection: 'column', background: 'var(--bg-window)', borderLeft: '1px solid var(--tb-hairline)', boxShadow: pushShown ? '-18px 0 50px rgba(0,0,0,.30)' : 'none', transform: reduce ? 'none' : (pushShown ? 'translateX(0)' : 'translateX(100%)'), transition: reduce ? 'none' : 'transform 320ms cubic-bezier(.32,.72,0,1), box-shadow 320ms cubic-bezier(.32,.72,0,1)', willChange: 'transform' }}>
              <div style={{ padding: '20px 28px 0', flex: '0 0 auto' }}>
                <DetailHeader
                  title={viewFile.name}
                  leading={<span role="button" onClick={closeFile} title="Back (Esc)" style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, marginLeft: -6, borderRadius: 8, cursor: 'pointer', color: 'var(--text-subtle-variant)', flex: '0 0 auto' }}><Ico n="arrow-left" s={18} /></span>}
                  meta={(
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                      {fvTarget && <Chip tone="blue">{fvTarget}</Chip>}
                      {viewFile.platform && <Chip>{viewFile.platform}</Chip>}
                      <Chip tone={isBlaze ? 'amber' : ''}>{isBlaze ? 'source' : 'recording'}</Chip>
                    </div>
                  )}
                />
              </div>
              <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '14px 28px 20px' }}>
                <TrailYamlEditor content={viewFile.content} editable tools={isBlaze ? null : scopedTools}
                  onSave={(t) => TB.saveTrailFolderFile(fvFolderId, viewFile.name, t)}
                  onSaved={() => { trails.reload(); setBoardReloadKey((k) => k + 1); }}
                  dirtyRef={editorDirty} highlight={viewFile.highlight} />
              </div>
            </div>
          );
        })()}
      </div>
    </div>
  );
}

window.TrailsScreen = TrailsScreen;

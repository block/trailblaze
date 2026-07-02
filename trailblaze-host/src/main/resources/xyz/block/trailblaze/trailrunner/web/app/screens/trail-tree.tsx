// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The per-device recordings inside a bundle, shown inline on the (collapsed) bundle row so
// it reads as one logical trail with its variants at a glance: a flame if it carries a
// plain-language blaze, then the shared PlatformGlyph (Apple / Android robot / globe) per
// platform it's recorded on — the one platform-icon set used everywhere in the app.
// The compressed-away ancestor directories of a flattened row, shown dim before the bold name so a
// lone trail buried in a chain of folders still makes its location obvious. Shrinks/ellipsizes first
// (the name never shrinks) so the unit name stays readable when the path is long.
function PathPrefix({ prefix }) {
  if (!prefix) return null;
  return (
    <span className="tb-mono" title={prefix + '/'}
      style={{ flex: '0 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-subtle)', opacity: 0.7, fontSize: 11.5 }}>
      {prefix}/
    </span>
  );
}

// Display names for the platform filter — the raw config/file-derived keys (`ios`/`android`/`web`)
// read better cased the way the platforms are actually written. File-unique name: these screens load
// as non-module global scripts, and steps-board.jsx already declares a top-level `PLATFORM_LABEL`, so
// a second bare `const PLATFORM_LABEL` would throw "already been declared" at app load.
const TREE_PLATFORM_LABEL = { ios: 'iOS', android: 'Android', web: 'Web' };

function BundleVariants({ platforms = [], hasBlaze }) {
  if (!hasBlaze && platforms.length === 0) return null;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flex: '0 0 auto', marginLeft: 2 }}>
      {hasBlaze && <Ico n="flame" s={15} c="var(--tb-amber)" title="Has a plain-language blaze" />}
      {platforms.map((p) => <PlatformGlyph key={p} platform={p} s={17} c="var(--text-subtle)" title={p} bare />)}
    </span>
  );
}

function TrailTree({ rows, query, setQuery, filterPlatform, setFilterPlatform, filterTarget, setFilterTarget, platforms, targets, sortBy, setSortBy, hasUntargeted = false, hasUnplatformed = false, total, selected, selectedFolder, onSelect, onSelectFolder, onRemoveRoot, onChangeWorkspace, help, onNewTrail, onFocusDir, editedOnly, setEditedOnly, roots, addError, addBusy = false, width = 280, favSet = new Set(), onToggleFavorite, targetScopeLabel, targetScopePlatform, onClearTargetScope }) {
  const targetMap = TB.useTargetAppMap();
  // Container (suite/section) folders collapse independently; the default is all-open. Bundles use a
  // single-open accordion instead — `openBundle` holds the one expanded bundle's acc (null = none), so
  // opening one variant list closes any other. Default null keeps the view at one row per bundle.
  const [collapsed, setCollapsed] = React.useState(() => new Set());
  const [openBundle, setOpenBundle] = React.useState(null);
  const [cursor, setCursor] = React.useState(null); // row key the keyboard cursor is on
  const [menu, setMenu] = React.useState(null); // right-click menu: { x, y, trail }
  const listRef = React.useRef(null);
  const searchRef = React.useRef(null);
  const keyOf = (r) => (r.t === 'folder' ? 'f-' + r.acc : 't-' + r.id);

  // Type-to-search: when the Trails view is up and focus isn't already in a text field, a printable
  // keystroke jumps into the search box (and the character lands there, since we focus during keydown
  // without preventing the default). Lets you just start typing instead of clicking the field first.
  React.useEffect(() => {
    const onKey = (e) => {
      if (e.metaKey || e.ctrlKey || e.altKey || e.key.length !== 1 || e.key === ' ') return;
      const el = document.activeElement;
      const tag = (el && el.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || (el && el.isContentEditable)) return;
      if (searchRef.current) searchRef.current.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // When a single variant is selected (e.g. from the detail's variant list / matrix), open the
  // bundle that holds it so its highlighted row is actually visible in the tree.
  React.useEffect(() => {
    if (selectedFolder || !selected) return;
    const row = rows.find((r) => r.t === 'trail' && r.variant && r.id === selected);
    if (row && row.acc) setOpenBundle(row.acc);
  }, [selected, selectedFolder, rows]);

  // Tell the screen which directory the cursor is in (a folder row is itself the
  // directory; a trail row reports its containing folder) — the New trail modal
  // prefills from it.
  React.useEffect(() => {
    if (!onFocusDir || !cursor) return;
    const r = rows.find((x) => keyOf(x) === cursor);
    if (r) onFocusDir(r.t === 'folder' ? r.acc : (r.acc || ''));
  }, [cursor, rows]);
  const toggleFolder = (acc) => setCollapsed((prev) => {
    const next = new Set(prev);
    if (next.has(acc)) next.delete(acc); else next.add(acc);
    return next;
  });

  const hiddenByCollapse = (r) => {
    const acc = r.acc || '';
    // Hidden when an ANCESTOR container folder is collapsed. A container's own header row (a folder
    // whose acc === the collapsed acc) stays visible; everything strictly beneath it is hidden.
    for (const c of collapsed) {
      if (acc === c) { if (!(r.t === 'folder' && !r.bundle)) return true; }
      else if (acc.startsWith(c + '/')) return true;
    }
    // Variant files only show when their bundle is the one open in the accordion.
    if (r.t === 'trail' && r.variant && openBundle !== acc) return true;
    return false;
  };

  const fileName = (r) => (r.path ? r.path.split('/').pop() : r.name);
  const ql = query.toLowerCase();
  const filtered = (() => {
    if (!ql) return rows.filter((r) => !hiddenByCollapse(r));
    const isMatch = (r) => r.t === 'trail' && (r.name + ' ' + (r.id || '') + ' ' + (r.path || '')).toLowerCase().includes(ql);
    const keepFolders = new Set();
    rows.forEach((r) => {
      if (!isMatch(r)) return;
      let cur = '';
      (r.acc || '').split('/').filter(Boolean).forEach((seg) => { cur = cur ? cur + '/' + seg : seg; keepFolders.add(cur); });
    });
    return rows.filter((r) => (r.t === 'trail' ? isMatch(r) : keepFolders.has(r.acc)));
  })();

  // The "X of Y" footer count. `rows` already reflects the platform/target filters; this also folds
  // in the search query so the count tracks every filter (the prior prop ignored search). A unit is a
  // bundle (counted once) or a standalone trail; a bundle matches the query if its own name or any of
  // its variant files match. Independent of collapse/accordion state.
  const shownCount = React.useMemo(() => {
    const m = (txt) => !ql || (txt || '').toLowerCase().includes(ql);
    const qVariantAccs = new Set();
    if (ql) for (const r of rows) if (r.t === 'trail' && r.variant && m(`${r.name || ''} ${r.path || ''} ${r.id || ''}`)) qVariantAccs.add(r.acc);
    let n = 0;
    for (const r of rows) {
      if (r.t === 'folder' && r.bundle) { if (!ql || m(`${r.name} ${r.acc}`) || qVariantAccs.has(r.acc)) n++; }
      else if (r.t === 'trail' && !r.variant) { if (m(`${r.name || ''} ${r.path || ''} ${r.id || ''}`)) n++; }
    }
    return n;
  }, [rows, ql]);

  // Pins are directories: pinning trails/sample/login pins the logical trail with
  // all its platform variants.
  const pinnedDirs = [...favSet].filter((p) => rows.some((r) => r.t === 'folder' && r.acc === p)).sort();
  const pinnedChildren = (acc) => rows.filter((r) => r.t === 'trail' && r.acc === acc);

  const scrollCursorIntoView = (k) => {
    requestAnimationFrame(() => {
      const el = listRef.current && listRef.current.querySelector(`[data-rowkey="${CSS.escape(k)}"]`);
      if (el) el.scrollIntoView({ block: 'nearest' });
    });
  };
  const moveCursor = (k) => { setCursor(k); scrollCursorIntoView(k); };

  const onTreeKeyDown = (e) => {
    const tag = (e.target.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea') return;
    const idx = filtered.findIndex((r) => keyOf(r) === cursor);
    const cur = idx >= 0 ? filtered[idx] : null;
    // Selecting a bundle opens its variant list (single-open accordion) and its matrix.
    const selectBundle = (acc) => { onSelectFolder(acc); setOpenBundle(acc); };
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const next = filtered[Math.min(filtered.length - 1, Math.max(0, (idx < 0 ? (e.key === 'ArrowDown' ? -1 : filtered.length) : idx) + (e.key === 'ArrowDown' ? 1 : -1)))];
      if (next) {
        moveCursor(keyOf(next));
        if (next.t === 'trail') onSelect(next.id);
        else if (next.bundle) selectBundle(next.acc);
      }
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      if (cur && cur.t === 'folder' && cur.bundle && openBundle === cur.acc) { setOpenBundle(null); return; }
      if (cur && cur.t === 'folder' && !cur.bundle && !collapsed.has(cur.acc)) { toggleFolder(cur.acc); return; }
      if (cur) {
        const parentAcc = cur.t === 'folder' ? cur.acc.split('/').slice(0, -1).join('/') : (cur.acc || '');
        const parent = filtered.find((r) => r.t === 'folder' && r.acc === parentAcc);
        if (parent) moveCursor(keyOf(parent));
      }
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (cur && cur.t === 'folder' && cur.bundle) { if (openBundle !== cur.acc) selectBundle(cur.acc); return; }
      if (cur && cur.t === 'folder') {
        if (collapsed.has(cur.acc)) { toggleFolder(cur.acc); return; }
        const next = filtered[idx + 1];
        if (next) { moveCursor(keyOf(next)); if (next.t === 'trail') onSelect(next.id); else if (next.bundle) selectBundle(next.acc); }
      }
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (cur && cur.t === 'folder' && cur.bundle) { onSelectFolder(cur.acc); setOpenBundle(openBundle === cur.acc ? null : cur.acc); }
      else if (cur && cur.t === 'folder') toggleFolder(cur.acc);
      else if (cur && cur.t === 'trail') onSelect(cur.id);
    }
  };

  const extras = roots?.extras || [];
  const workspaceName = (roots?.primary || '').split('/').filter(Boolean).pop() || 'Not set';
  return (
    <div style={{ width, flex: '0 0 ' + width + 'px', borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
      <RailHeader ico="route" iconColor="var(--tb-running)" title="Trails"
        help={help}
        right={<React.Fragment>
          {onNewTrail && (
            <button className="tb-btn ghost sm" style={{ padding: 6 }} title="Create a new trail" onClick={onNewTrail} data-testid="new-trail">
              <Ico n="plus" s={16} />
            </button>
          )}
          {setEditedOnly && (
            <button
              className="tb-btn ghost sm"
              style={{ padding: 6, color: editedOnly ? 'var(--tb-running)' : undefined }}
              title={editedOnly ? 'Showing only trails with uncommitted edits - click to show all' : 'Show only trails with uncommitted edits (git)'}
              onClick={() => setEditedOnly((v) => !v)}
              data-testid="edited-only"
            >
              <Ico n="file-pen-line" s={16} c={editedOnly ? 'var(--tb-running)' : undefined} />
            </button>
          )}
        </React.Fragment>} />
      {addError && (
        <div style={{ margin: '0 14px 8px', fontSize: 11.5, color: 'var(--tb-danger-text)', background: 'rgba(248,71,82,.1)', border: '1px solid rgba(248,71,82,.22)', borderRadius: 6, padding: '6px 9px' }}>
          {addError}
        </div>
      )}
      {extras.length > 0 && (
        <div style={{ padding: '0 12px 10px' }}>
          <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Extra roots</div>
          {extras.map((p) => (
            <div key={p} className="tb-sub" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, padding: '3px 0' }}>
              <Ico n="folder" s={12} c="var(--tb-running)" />
              <span className="tb-mono" style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={p}>{p}</span>
              <Ico n="x" s={13} c="var(--text-subtle)" style={{ cursor: 'pointer' }} onClick={() => onRemoveRoot(p)} />
            </div>
          ))}
        </div>
      )}
      <div style={{ padding: '0 12px 10px' }}>
        <div className="tb-input">
          <Ico n="search" s={14} />
          <input ref={searchRef} placeholder="Search or go to trail id…" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
      </div>
      <div style={{ padding: '0 12px 10px', display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', minWidth: 0 }} data-testid="trail-filters">
        <Select compact ico="smartphone" label="Platform" value={filterPlatform} onChange={(e) => setFilterPlatform(e.target.value)} title="Filter by platform"
          options={[
            { value: 'all', label: 'All platforms' },
            ...platforms.map((p) => ({ value: p, label: TREE_PLATFORM_LABEL[p] || p, glyph: <PlatformGlyph platform={p} s={15} c="var(--text-subtle-variant)" bare /> })),
            ...(hasUnplatformed ? [{ value: '__none__', label: 'Not specified' }] : []),
          ]} />
        <Select compact ico="crosshair" label="Target" value={filterTarget} onChange={(e) => setFilterTarget(e.target.value)} title="Filter by target"
          options={[
            { value: 'all', label: 'All Targets' },
            ...targets.map((t) => ({ value: t, label: TB.targetLabel(t, targetMap), glyph: <AppIcon target={t} size={15} radius={4} /> })),
            ...(hasUntargeted ? [{ value: '__none__', label: 'Not specified' }] : []),
          ]} />
        {setSortBy && (
          <Select compact ico="arrow-down-up" label="Sort" value={sortBy} onChange={(e) => setSortBy(e.target.value)} title="Order of the trail list"
            options={[['path', 'Folder structure'], ['title', 'Title A–Z'], ['priority', 'Priority']]} />
        )}
      </div>
      {/* "Scoped by your target" banner sits directly under the filters it explains (shared with the
          Tools and Trailmaps list views, where it sits in the footer instead). */}
      <TargetScopeBanner label={targetScopeLabel} platform={targetScopePlatform} onShowAll={onClearTargetScope} />
      <div ref={listRef} tabIndex={0} style={{ flex: 1, overflowY: 'auto', padding: '0 8px 12px', outline: 'none' }} onKeyDown={onTreeKeyDown} data-testid="trail-tree-list">
        {!ql && pinnedDirs.length > 0 && (
          <div style={{ marginBottom: 6 }}>
            <div className="tb-eyebrow" style={{ padding: '6px 8px 4px', display: 'flex', alignItems: 'baseline', gap: 6 }}><span>Pinned</span><span style={{ opacity: .6 }}>{pinnedDirs.length}</span></div>
            {pinnedDirs.map((acc) => (
              <div key={'pin-' + acc}>
                <div className="tb-tree" style={{ cursor: 'default' }}>
                  <Ico n="pin" s={12} c="var(--tb-amber)" />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', fontWeight: 600 }} title={acc}>{acc.split('/').pop()}</span>
                  {onToggleFavorite && (
                    <span onClick={() => onToggleFavorite(acc)} title="Unpin directory" className="tb-pin" style={{ flex: '0 0 auto', display: 'inline-flex', cursor: 'pointer' }}>
                      <Ico n="pin-off" s={12} c="var(--text-subtle)" />
                    </span>
                  )}
                </div>
                {pinnedChildren(acc).map((r) => (
                  <div key={'pin-' + acc + '-' + r.id} className={'tb-tree ' + (!selectedFolder && r.id === selected ? 'active' : '')} style={{ paddingLeft: 23, cursor: 'pointer' }} onClick={() => onSelect(r.id)}
                    onContextMenu={(e) => { e.preventDefault(); onSelect(r.id); setMenu({ x: e.clientX, y: e.clientY, trail: r }); }}>
                    {r.kind === 'blaze'
                      ? <Ico n="flame" s={13} c="var(--tb-amber)" style={{ flex: '0 0 auto' }} />
                      : <Dot c={STATUS[r.status][1]} s={7} />}
                    <span className="tb-mono" style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 12 }}>{fileName(r)}</span>
                  </div>
                ))}
              </div>
            ))}
            <div style={{ height: 1, background: 'var(--tb-hairline)', margin: '7px 8px 3px' }} />
          </div>
        )}
        {filtered.length === 0 && <div className="tb-sub" style={{ fontSize: 12, padding: '8px 12px' }}>No trails match.</div>}
        {filtered.map((r) => r.t === 'folder' ? (
          r.bundle ? (
            // A bundle = one logical trail. Clicking the row opens its Implementations matrix (the
            // folder overview); the chevron expands the per-device variant files, each of which opens
            // its own trail detail. Dominance (ch07) — bolder name + variant chips make the bundle the
            // unit the eye lands on, not the files beneath it.
            <div
              className={'tb-tree ' + (selectedFolder === r.acc ? 'active' : '')}
              key={'f-' + r.acc}
              data-rowkey={'f-' + r.acc}
              data-navrow
              style={{ paddingLeft: 8 + r.depth * 15, cursor: 'pointer', outline: cursor === 'f-' + r.acc ? '1px solid var(--tb-hairline-stronger)' : 'none', outlineOffset: -1 }}
              onClick={() => { moveCursor('f-' + r.acc); onSelectFolder(r.acc); setOpenBundle(r.acc); }}
              title={(r.prefix ? r.prefix + '/' : '') + r.name}
            >
              <span onClick={(e) => { e.stopPropagation(); moveCursor('f-' + r.acc); setOpenBundle(openBundle === r.acc ? null : r.acc); }} title={openBundle === r.acc ? 'Hide variants' : 'Show variants'} style={{ display: 'inline-flex', flex: '0 0 auto', cursor: 'pointer', transition: 'transform .15s var(--ease-out-soft)', transform: openBundle === r.acc ? 'none' : 'rotate(-90deg)' }}>
                <Ico n="chevron-down" s={13} c="var(--text-subtle)" />
              </span>
              {/* A bundle is a logical TRAIL, not a directory — give it the Trailblaze route glyph in
                  the accent color so it reads distinctly from the muted plain-folder rows below. */}
              <Ico n="route" s={15} c="var(--tb-running)" style={{ flex: '0 0 auto' }} />
              <PathPrefix prefix={r.prefix} />
              <span style={{ flex: '0 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 600 }}>{r.name}</span>
              <span style={{ flex: 1 }} />
              <BundleVariants platforms={r.platforms} hasBlaze={r.hasBlaze} />
              {onToggleFavorite && (() => { const fav = favSet.has(r.acc); return (
                <span onClick={(e) => { e.stopPropagation(); onToggleFavorite(r.acc); }} title={fav ? 'Unpin trail' : 'Pin trail'} className="tb-pin" style={{ flex: '0 0 auto', display: 'inline-flex', cursor: 'pointer', opacity: fav ? 1 : 0.28 }}>
                  <Ico n="pin" s={13} c={fav ? 'var(--tb-amber)' : 'var(--text-subtle)'} />
                </span>
              ); })()}
            </div>
          ) : (
          <div
            className="tb-tree"
            key={'f-' + r.acc}
            data-rowkey={'f-' + r.acc}
            style={{ paddingLeft: 8 + r.depth * 15, cursor: 'pointer', outline: cursor === 'f-' + r.acc ? '1px solid var(--tb-hairline-stronger)' : 'none', outlineOffset: -1 }}
            onClick={() => { moveCursor('f-' + r.acc); toggleFolder(r.acc); }}
            title={collapsed.has(r.acc) ? 'Expand' : 'Collapse'}
          >
            <span style={{ display: 'inline-flex', flex: '0 0 auto', transition: 'transform .15s var(--ease-out-soft)', transform: collapsed.has(r.acc) ? 'rotate(-90deg)' : 'none' }}>
              <Ico n="chevron-down" s={13} c="var(--text-subtle)" />
            </span>
            <Ico n={collapsed.has(r.acc) ? 'folder' : 'folder-open'} s={15} c="var(--text-subtle)" style={{ opacity: 0.7 }} />
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', color: 'var(--text-subtle)', opacity: 0.7 }}>{r.name}</span>
            {onToggleFavorite && (() => { const fav = favSet.has(r.acc); return (
              <span onClick={(e) => { e.stopPropagation(); onToggleFavorite(r.acc); }} title={fav ? 'Unpin directory' : 'Pin directory'} className="tb-pin" style={{ flex: '0 0 auto', display: 'inline-flex', cursor: 'pointer', opacity: fav ? 1 : 0.28 }}>
                <Ico n="pin" s={13} c={fav ? 'var(--tb-amber)' : 'var(--text-subtle)'} />
              </span>
            ); })()}
          </div>
          )
        ) : (
          <div
            key={'t-' + r.id}
            data-rowkey={'t-' + r.id}
            data-navrow
            className={'tb-tree ' + (!selectedFolder && r.id === selected ? 'active' : '')}
            // +5 over the plain depth indent so the icon slot lines up under the PARENT row's icon
            // (which sits past its chevron: 13px chevron + 7px gap = 20, vs the 15px-per-level indent).
            style={{ paddingLeft: 8 + r.depth * 15 + 5, cursor: 'pointer', outline: cursor === 't-' + r.id ? '1px solid var(--tb-hairline-stronger)' : 'none', outlineOffset: -1 }}
            onClick={() => { moveCursor('t-' + r.id); onSelect(r.id); }}
            onContextMenu={(e) => { e.preventDefault(); moveCursor('t-' + r.id); onSelect(r.id); setMenu({ x: e.clientX, y: e.clientY, trail: r }); }}
            title={r.name}
          >
            {/* Dot/flame centered in a 15px slot — the parent bundle/folder icon's footprint — so it
                sits directly under that icon rather than tucked to its left. */}
            <span style={{ width: 15, flex: '0 0 auto', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
              {r.kind === 'blaze'
                ? <Ico n="flame" s={13} c="var(--tb-amber)" />
                : <Dot c={STATUS[r.status][1]} s={7} />}
            </span>
            {/* Flat (sorted) rows are standalone trails shown out of their folder, so they read by
                TITLE + a platform glyph — the filename alone is meaningless once flattened. Folder-mode
                rows keep the dim path prefix + mono filename (the folder header above gives context). */}
            {r.flat ? (
              <React.Fragment>
                <span style={{ flex: '0 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 }}>{r.name}</span>
                <span style={{ flex: 1 }} />
                {r.platform && <PlatformGlyph platform={r.platform} s={17} c="var(--text-subtle)" title={r.platform} bare />}
              </React.Fragment>
            ) : (
              <React.Fragment>
                <PathPrefix prefix={r.prefix} />
                <span className="tb-mono" style={{ flex: '0 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 12 }}>{fileName(r)}</span>
                <span style={{ flex: 1 }} />
              </React.Fragment>
            )}
          </div>
        ))}
      </div>
      <div style={{ padding: '9px 12px', borderTop: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="tb-sub" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>{shownCount === total ? `${total} trail${total === 1 ? '' : 's'}` : `${shownCount} of ${total} trails`}{editedOnly ? ' · edited only' : ''}</div>
          <button
            onClick={onChangeWorkspace}
            disabled={addBusy}
            className="tb-ws-btn"
            title={(TB.WORKSPACE_BLURB || 'Change the workspace folder') + (roots?.primary ? `\n(currently ${roots.primary})` : '')}
            style={{ display: 'flex', alignItems: 'center', gap: 6, width: '100%', minWidth: 0, background: 'none', border: 'none', borderRadius: 6, padding: '2px 4px', margin: '1px 0 -2px -4px', cursor: addBusy ? 'default' : 'pointer', opacity: addBusy ? 0.6 : 1, textAlign: 'left' }}
          >
            <Ico n={addBusy ? 'loader-2' : 'folder-open'} spin={addBusy} s={14} c="var(--tb-primary-green)" style={{ flex: '0 0 auto' }} />
            <span style={{ flex: '0 0 auto', fontSize: 11, fontWeight: 600, color: 'var(--text-standard)' }}>Change…</span>
            <span className="tb-mono" style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 11, color: 'var(--text-subtle)' }}>{workspaceName}</span>
          </button>
        </div>
        <button className="tb-btn ghost sm" style={{ padding: '3px 9px', fontSize: 11 }} title="Reveal the workspace folder in Finder" onClick={() => TB.revealTrailsRoot()}>Reveal in Finder</button>
      </div>
      {menu && <TrailRowContextMenu menu={menu} onClose={() => setMenu(null)} />}
    </div>
  );
}

// Right-click menu for a trail file row: reveal it in Finder, open it in the editor,
// copy its id/path. Mirrors ToolContextMenu / the trailmap component menu.
function TrailRowContextMenu({ menu, onClose }) {
  useLucide();
  React.useEffect(() => {
    const k = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);
  const r = menu.trail;
  const copy = (s) => { try { navigator.clipboard.writeText(s); } catch (_) {} };
  const items = [
    { ico: 'folder-open', label: 'Open in Finder', fn: () => { onClose(); TB.revealTrail(r.id); } },
    { ico: 'pencil', label: 'Open in editor', fn: () => { onClose(); TB.openTrailInEditor(r.id); } },
    { sep: true },
    { ico: 'copy', label: 'Copy trail id', fn: () => { onClose(); copy(r.id); } },
    { ico: 'copy', label: 'Copy path', fn: () => { onClose(); copy(r.path || r.id); } },
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

Object.assign(window, { TrailTree, TrailRowContextMenu });

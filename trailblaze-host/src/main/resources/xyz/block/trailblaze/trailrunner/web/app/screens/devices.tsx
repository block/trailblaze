// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The target + device picker, embedded in the Home screen's left rail (the standalone
// Devices screen was removed). Pick a target app, then tick the devices to run it on.
// The choice is the global target: it scopes the Trailmaps views and is the default for
// runs. Styled as a left list-rail to match the other tabs.
function TargetDevicePicker({ go }) {
  const devices = TB.useDevices();
  const [gt, setGlobalTarget] = TB.useGlobalTarget();
  const [appsByDevice, setAppsByDevice] = React.useState({});
  const [appsLoading, setAppsLoading] = React.useState(false);
  // Bumped by Refresh so installed apps/versions re-fetch even when the device set is
  // unchanged (the common case: a device stays connected but its app build changed).
  const [appsNonce, setAppsNonce] = React.useState(0);
  const refresh = () => { devices.reload(); setAppsNonce((n) => n + 1); };
  useLucide();

  // Edit Target: looks up the trailmap manifest by id to pre-populate the form. Assumes the
  // target id equals its trailmap id — true for the default (id-omitted) case docs.trailmaps.md
  // describes; an explicit mismatched target `id:` surfaces as an "unknown trailmap" save error.
  const trailmapsResult = TB.useTrailmaps();
  const trailmapList = trailmapsResult.data || [];
  // { id, label } to edit an existing target; { isNew: true } for the create flow.
  const [editingTarget, setEditingTarget] = React.useState(null);
  // Bumped after a successful Edit Target save to bust the <img> icon cache (same URL, new bytes).
  const [iconNonce, setIconNonce] = React.useState(0);
  // Targets awaiting a daemon restart (created here, or flagged by a workspace switch) — the same
  // sessionStorage-backed state behind the shell's restart banner, mirrored as a per-card label.
  const [restartIds, setRestartIds] = React.useState(() => TB.getTargetsRestartNeeded() || []);
  React.useEffect(() => {
    const sync = () => setRestartIds(TB.getTargetsRestartNeeded() || []);
    window.addEventListener('tb:targets-restart-changed', sync);
    return () => window.removeEventListener('tb:targets-restart-changed', sync);
  }, []);

  const deviceList = devices.data || [];
  const deviceIdsKey = deviceList.map((d) => d.id).join(',');

  // Installed apps come per-device from /api/device/apps. Grouping by target needs them
  // all, so fetch every (non-web) device's apps up front and invert into target groups.
  React.useEffect(() => {
    const appish = deviceList.filter((d) => d.platform !== 'web');
    if (appish.length === 0) { setAppsByDevice({}); setAppsLoading(false); return; }
    let cancelled = false;
    setAppsLoading(true);
    Promise.all(appish.map((d) => Promise.resolve(TB.fetchDeviceApps(d.platform, d.id))
      .then((r) => [d.id, (r && r.targets) || []]).catch(() => [d.id, []])))
      .then((entries) => { if (!cancelled) { setAppsByDevice(Object.fromEntries(entries)); setAppsLoading(false); } });
    return () => { cancelled = true; };
  }, [deviceIdsKey, appsNonce]);

  // target id -> { id, label, items: [{ device, app }] }
  const groups = {};
  const webDevices = [];
  deviceList.forEach((d) => {
    if (d.platform === 'web') { webDevices.push(d); return; }
    (appsByDevice[d.id] || []).forEach((a) => {
      const g = groups[a.id] || (groups[a.id] = { id: a.id, label: a.displayName || a.id, items: [] });
      g.items.push({ device: d, app: a });
    });
  });
  // Declared-but-undetected targets: a workspace trailmap with a `target:` block whose app isn't
  // installed on any connected device would otherwise be invisible here (the groups above come
  // from a live installed-app scan). Surface it as a device-less card so a freshly created — or
  // simply not-yet-installed — target has a presence instead of a dead end. Web-only targets are
  // skipped: apps aren't "installed" on a web device, so such a card could never come alive
  // (web runs keep flowing through the Web card below). Merged unconditionally — while the app
  // scan is in flight an installed app briefly reads as "not installed," which beats the
  // alternative (gating on the scan makes dimmed cards vanish and reappear on every Refresh).
  trailmapList.forEach((t) => {
    if (!t.displayName || groups[t.id]) return;
    if (!(t.platforms || []).some((p) => p !== 'web')) return;
    // Excluded by the workspace's explicit targets: allow-list — the runtime will never load it,
    // so a card here could never activate no matter what's installed or restarted.
    if (t.workspaceListed === false) return;
    groups[t.id] = { id: t.id, label: t.displayName, items: [] };
  });
  // Selectable (device-backed) targets first, then the dimmed declared-only ones, A-Z within each.
  const groupList = Object.values(groups).sort((x, y) =>
    (y.items.length > 0 ? 1 : 0) - (x.items.length > 0 ? 1 : 0) || x.label.localeCompare(y.label));

  // Toggle a device under a target. Switching targets resets the device set (one active
  // target at a time; it's the filtering axis); within a target, devices add/remove.
  const toggleDevice = async (targetId, label, device) => {
    const sameTarget = gt && (gt.target || null) === (targetId || null);
    let nextIds;
    if (!sameTarget) {
      nextIds = [device.id];
      if (targetId) await TB.setTargetApp(targetId);
    } else {
      const has = (gt.deviceIds || []).includes(device.id);
      nextIds = has ? gt.deviceIds.filter((x) => x !== device.id) : [...(gt.deviceIds || []), device.id];
    }
    setGlobalTarget({ target: targetId || null, label: targetId ? label : 'Web', deviceIds: nextIds });
  };

  // Header action: select every (connected) device in a group, or clear if all already on.
  const toggleGroup = async (g) => {
    const ids = g.items.map((it) => it.device.id);
    const allOn = gt && gt.target === g.id && ids.every((id) => (gt.deviceIds || []).includes(id));
    if (allOn) { setGlobalTarget({ target: g.id, label: g.label, deviceIds: [] }); return; }
    if (!gt || gt.target !== g.id) await TB.setTargetApp(g.id);
    setGlobalTarget({ target: g.id, label: g.label, deviceIds: ids });
  };

  const empty = !devices.loading && deviceList.length === 0;
  const noGroups = !appsLoading && groupList.length === 0 && webDevices.length === 0;
  const selCountTotal = gt ? (gt.deviceIds || []).length : 0;
  const connectedCount = deviceList.filter((d) => d.connected).length;

  return (
    <React.Fragment>
      <RailHeader
        ico="package" iconColor="var(--tb-running)"
        title="Target"
        help={(
          <HelpButton title="How targets work" align="left"
            sub="A target is the app under test - a name like sample mapped to the right bundle id per platform. Pick a target, then choose every device you want it available on.">
            <HelpCard ico="package" color="var(--tb-amber)" title="Targets group your devices">
              Each card is a target app and the connected devices it's installed on. Selecting a target scopes the Trailmaps views and preflights as the run target. Switching targets clears the device set - one target is active at a time.
            </HelpCard>
            <HelpCard ico="smartphone" color="var(--tb-running)" title="Select multiple devices">
              Tick every device you want under the active target. Runs default to the first selected device (you pick which one in Configure run); the rest stay queued in your selection for quick switching.
            </HelpCard>
          </HelpButton>
        )}
        sub="Pick an app, then the devices to run it on."
        right={(
          <div style={{ display: 'flex', gap: 4 }}>
            <button className="tb-btn ghost sm" style={{ padding: 6 }} title="New target" onClick={() => setEditingTarget({ isNew: true })}><Ico n="plus" s={16} /></button>
            <button className="tb-btn ghost sm" style={{ padding: 6 }} title="Refresh devices" onClick={refresh}><Ico n="refresh-cw" s={16} spin={devices.loading || appsLoading} /></button>
          </div>
        )}
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '2px 10px 12px' }}>
        {(devices.loading || appsLoading) && groupList.length === 0 && <div style={{ padding: '4px 2px' }}><Skeleton rows={4} /></div>}
        {empty && <EmptyState ico="smartphone" title="No devices" sub="Connect a phone or boot an emulator, then Refresh." />}
        {noGroups && !empty && (
          <React.Fragment>
            <EmptyState ico="package" title="No targets yet" sub="A target maps your app - a name plus its bundle ids per platform - to a trailmap in your workspace." />
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: 10 }}>
              <Btn sm kind="primary" ico="plus" onClick={() => setEditingTarget({ isNew: true })}>Create your first target</Btn>
            </div>
          </React.Fragment>
        )}

        {groupList.map((g) => {
          const selectedIds = gt && gt.target === g.id ? (gt.deviceIds || []).filter((id) => g.items.some((it) => it.device.id === id)) : [];
          return (
            <TargetCard key={g.id} targetId={g.id} iconNonce={iconNonce}
              icon={<AppIcon target={g.id} size={18} v={iconNonce} fallbackColor={selectedIds.length ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />}
              label={g.label} items={g.items} selectedIds={selectedIds}
              statusLabel={restartIds.includes(g.id) ? 'Restart Trail Runner to activate' : undefined}
              onToggleTarget={() => toggleGroup(g)} onToggleDevice={(d) => toggleDevice(g.id, g.label, d)}
              onEdit={() => setEditingTarget({ id: g.id, label: g.label })} />
          );
        })}

        {webDevices.length > 0 && (() => {
          const items = webDevices.map((d) => ({ device: d, app: null }));
          const selectedIds = gt && gt.target == null ? (gt.deviceIds || []).filter((id) => webDevices.some((d) => d.id === id)) : [];
          const ids = webDevices.map((d) => d.id);
          const toggleWeb = () => {
            const allOn = selectedIds.length > 0 && ids.every((id) => selectedIds.includes(id));
            setGlobalTarget({ target: null, label: 'Web', deviceIds: allOn ? [] : ids });
          };
          return (
            <TargetCard
              icon={<Ico n="globe" s={17} c={selectedIds.length ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto' }} />}
              label="Web" items={items} selectedIds={selectedIds}
              onToggleTarget={toggleWeb} onToggleDevice={(d) => toggleDevice(null, 'Web', d)} />
          );
        })()}

        {/* Only the always-available Web target is here — no app target declared OR detected. A
            mobile target needs a workspace trailmap first (an installed app alone is NOT enough —
            the old copy implied auto-discovery and left users at a dead end, block/trailblaze#190),
            so lead with the create flow rather than "open an emulator." */}
        {!appsLoading && groupList.length === 0 && webDevices.length > 0 && (
          <div style={{ marginTop: 12, padding: '12px 13px', border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <Ico n="smartphone" s={16} c="var(--text-subtle)" style={{ flex: '0 0 auto', marginTop: 1 }} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-standard)', marginBottom: 3 }}>Testing a mobile app?</div>
              <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5 }}>Only Web is available. A mobile target first needs a trailmap — a name mapped to your app's bundle id. Create one here, then open a simulator/emulator with the app installed and Refresh.</div>
              <div style={{ marginTop: 8 }}>
                <Btn sm ico="plus" onClick={() => setEditingTarget({ isNew: true })}>Create your first target</Btn>
              </div>
            </div>
          </div>
        )}
      </div>
      <div style={{ padding: '9px 12px', borderTop: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 7, height: 7, borderRadius: 99, flex: '0 0 auto', background: selCountTotal ? 'var(--tb-pass)' : 'var(--text-subtle)' }} />
        <span className="tb-sub" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>
          {selCountTotal ? `${selCountTotal} selected · ` : 'No target selected · '}{connectedCount} of {deviceList.length} connected
        </span>
      </div>
      {editingTarget && (
        <EditTargetModal
          isNew={!!editingTarget.isNew}
          existingIds={trailmapList.map((t) => t.id)}
          deviceList={deviceList}
          trailmapId={editingTarget.id}
          manifestPath={editingTarget.isNew ? null : (trailmapList.find((t) => t.id === editingTarget.id) || {}).manifestPath}
          initialDisplayName={editingTarget.label}
          onClose={() => setEditingTarget(null)}
          onSaved={(savedId, r) => {
            setIconNonce((n) => n + 1);
            if (r && r.created && !r.registeredLive) {
              // A brand-new target the daemon couldn't register live can't be selected until a
              // restart — flag it through the same state the shell's restart banner and the
              // per-card "Restart Trail Runner to activate" label already read.
              const cur = TB.getTargetsRestartNeeded() || [];
              if (!cur.includes(savedId)) TB.setTargetsRestartNeeded([...cur, savedId]);
            } else if (r && r.registeredLive) {
              // Registered live — no restart. Re-fetch the per-device installed-apps list (keyed
              // on appsNonce) alongside the trailmap reload, so a target for an app already on a
              // connected device renders as selectable now instead of a declared-only "Not
              // installed" card until the next manual Refresh.
              setAppsNonce((n) => n + 1);
              // Defensive: if an earlier attempt had flagged this id for restart, clear it now that
              // it's live so the stale "Restart Trail Runner to activate" label doesn't linger.
              const cur = TB.getTargetsRestartNeeded() || [];
              if (cur.includes(savedId)) TB.setTargetsRestartNeeded(cur.filter((id) => id !== savedId));
            }
            trailmapsResult.reload();
          }}
        />
      )}
    </React.Fragment>
  );
}

// A target is the unit you click: the whole header selects the app and its devices in one
// click, no need to tick a device. A single-device target shows the device inline; a
// multi-device target reveals per-device toggles below for fine-grained control. An empty
// `items` means the target is declared in the workspace but its app isn't installed on any
// connected device — rendered dimmed and unselectable (nothing to run it on yet), with
// `statusLabel` (or a generic not-installed note) explaining why; Edit stays available.
function TargetCard({ targetId, iconNonce, icon, label, items, selectedIds, onToggleTarget, onToggleDevice, onEdit, statusLabel }) {
  const accent = selectedIds.length > 0;
  const notInstalled = items.length === 0;
  const single = items.length === 1;
  const it0 = items[0];
  const plat = (p) => (p === 'ios' ? 'iOS' : p === 'android' ? 'Android' : 'Web');
  const summary = notInstalled
    ? (statusLabel || 'Not installed on any connected device')
    : single
      ? [it0.device.name, plat(it0.device.platform), it0.app && it0.app.versionName].filter(Boolean).join(' · ')
      : `${items.length} devices · ${items.filter((it) => it.device.connected).length} connected`;
  return (
    <div style={{ border: '1px solid ' + (accent ? 'var(--tb-pass)' : 'var(--tb-hairline)'), borderRadius: 10, marginBottom: 8, overflow: 'hidden', background: 'var(--bg-standard)' }}>
      <div style={{ display: 'flex', alignItems: 'stretch' }}>
        <button onClick={notInstalled ? undefined : onToggleTarget} aria-disabled={notInstalled}
          title={notInstalled ? 'Install the app on a connected device, then Refresh, to select this target' : accent ? 'Selected, click to clear' : 'Select this target'}
          aria-pressed={notInstalled ? undefined : accent}
          aria-label={(notInstalled ? 'Target (unavailable) ' : accent ? 'Selected target ' : 'Select target ') + label + ' (' + summary + ')'}
          style={{ display: 'flex', alignItems: 'center', gap: 9, flex: 1, minWidth: 0, textAlign: 'left', padding: '10px 11px', background: 'transparent', border: 'none',
            cursor: notInstalled ? 'default' : 'pointer', opacity: notInstalled ? 0.55 : 1 }}>
          <span style={{ flex: '0 0 auto', width: 14, height: 14, borderRadius: 4, boxSizing: 'border-box',
            background: accent ? 'var(--tb-pass)' : 'transparent', border: accent ? 'none' : '1.5px solid var(--text-subtle-variant)' }} />
          {icon}
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13.5, fontWeight: 700, color: accent ? 'var(--tb-pass)' : 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{label}</div>
            <div className={notInstalled ? 'tb-sub' : 'tb-mono tb-sub'} style={{ fontSize: 10, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{summary}</div>
          </div>
          {notInstalled
            ? null
            : single
              ? <span role="img" title={it0.device.connected ? 'Connected' : 'Offline'} aria-label={it0.device.connected ? 'Connected' : 'Offline'}
                  style={{ flex: '0 0 auto', width: 8, height: 8, borderRadius: 99, background: it0.device.connected ? 'var(--tb-pass)' : 'var(--tb-fail)' }} />
              : <Chip tone={accent ? 'green' : ''}>{accent ? selectedIds.length + ' of ' + items.length : items.length}</Chip>}
        </button>
        {onEdit && (
          <button onClick={(e) => { e.stopPropagation(); onEdit(); }} title="Edit target" aria-label={'Edit target ' + label}
            style={{ flex: '0 0 auto', width: 30, display: 'flex', alignItems: 'center', justifyContent: 'center',
              background: 'transparent', border: 'none', borderLeft: '1px solid var(--tb-hairline)', cursor: 'pointer' }}>
            <Ico n="pencil" s={13} c="var(--text-subtle-variant)" />
          </button>
        )}
      </div>
      {!single && !notInstalled && (
        <div style={{ padding: '0 7px 7px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {items.map(({ device: d, app: a }) => (
            <DeviceToggleRow key={d.id} targetId={targetId} iconNonce={iconNonce} device={d} app={a} selected={selectedIds.includes(d.id)} onToggle={() => onToggleDevice(d)} />
          ))}
        </div>
      )}
    </div>
  );
}

function DeviceToggleRow({ targetId, iconNonce, device: d, app: a, selected, onToggle }) {
  const platLabel = d.platform === 'ios' ? 'iOS' : d.platform === 'android' ? 'Android' : 'Web';
  const ver = a ? (a.versionName || (a.versionCode ? 'build ' + a.versionCode : null)) : null;
  // Compact row: selection is a solid green fill (no checkmark), connection is a single
  // green/red dot. Kept small so the version + name never wrap in the narrow rail.
  return (
    <button onClick={onToggle} title={selected ? 'Selected, click to remove' : 'Add this device to the selection'}
      aria-pressed={selected} aria-label={(selected ? 'Selected ' : 'Select ') + (ver || 'Unknown') + ' ' + platLabel + ' ' + d.name + ' (' + (d.connected ? 'connected' : 'offline') + ')'}
      style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%', textAlign: 'left', padding: '6px 9px', borderRadius: 7, cursor: 'pointer',
        border: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-standard)' }}>
      <span style={{ flex: '0 0 auto', width: 13, height: 13, borderRadius: 4, boxSizing: 'border-box',
        background: selected ? 'var(--tb-pass)' : 'transparent', border: selected ? 'none' : '1.5px solid var(--text-subtle-variant)' }} />
      <AppIcon target={targetId} platform={d.platform} appId={a && a.appId} size={14} v={iconNonce}
        fallbackNode={<PlatformGlyph platform={d.platform} s={14} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto' }} />} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, minWidth: 0 }}>
          <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{ver || 'Unknown'}</span>
          <span style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-subtle)', whiteSpace: 'nowrap', flex: '0 0 auto' }}>{platLabel}</span>
        </div>
        <div className="tb-mono tb-sub" style={{ fontSize: 9.5, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{d.name} · {d.id}</div>
      </div>
      <span role="img" title={d.connected ? 'Connected' : 'Offline'} aria-label={d.connected ? 'Connected' : 'Offline'}
        style={{ flex: '0 0 auto', width: 7, height: 7, borderRadius: 99, background: d.connected ? 'var(--tb-pass)' : 'var(--tb-fail)' }} />
    </button>
  );
}

const EDIT_TARGET_PLATFORMS = ['android', 'ios', 'web'];
// Mirrors the server's SAFE_TRAILMAP_ID (TrailmapRoutes.kt): a new trailmap id doubles as its
// directory name, so it must be a single safe path segment.
const NEW_TARGET_ID = /^[A-Za-z0-9_][A-Za-z0-9_-]*$/;

// Edits a target's display name, top-level icon override, and per-platform app_ids/base_url/icon
// — the common fields covered by SaveTargetConfigRequest. Rarer fields (tool_sets, drivers,
// system_prompt_file, etc.) still require hand-editing trailmap.yaml; this covers the icon /
// display-name authoring gap without trying to be a full trailmap editor. Loads the trailmap's
// current YAML (via the existing tool-source-by-path RPC + window.jsyaml, same as the Trailmaps
// screen's raw preview) to pre-populate the form, since there's no dedicated "get target config"
// endpoint — reusing what's already there rather than adding one just for this.
//
// With `isNew`, the same form doubles as the create flow (block/trailblaze#190): an extra
// Trailmap ID field, no manifest prefetch (there is nothing to load), and the save goes out with
// createIfMissing so the server bootstraps the trailmap directory + minimal manifest. onSaved is
// called as onSaved(trailmapId, response) so the picker can flag a genuine creation for restart.
function EditTargetModal({ isNew, existingIds, deviceList, trailmapId, manifestPath, initialDisplayName, onClose, onSaved }) {
  useLucide();
  // Only an edit with a manifest has anything to prefetch; starting false otherwise avoids a
  // one-frame skeleton flash in create mode before the effect below runs.
  const [loading, setLoading] = React.useState(!!manifestPath);
  const [newId, setNewId] = React.useState('');
  const [displayName, setDisplayName] = React.useState(initialDisplayName || '');
  const [icon, setIcon] = React.useState('');
  const [platforms, setPlatforms] = React.useState({});
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  // ok=true with a server-side caveat (e.g. trailblaze.yaml couldn't be updated) — the save
  // landed, so the modal stays open just long enough to show it instead of auto-closing.
  const [warn, setWarn] = React.useState(null);

  React.useEffect(() => {
    let live = true;
    if (!manifestPath) { setLoading(false); return; }
    TB.fetchComponentSource(manifestPath).then((text) => {
      if (!live) return;
      let parsed = null;
      if (text && window.jsyaml) { try { parsed = window.jsyaml.load(text); } catch (_) { parsed = null; } }
      const target = (parsed && parsed.target) || {};
      setDisplayName(target.display_name || initialDisplayName || '');
      setIcon(target.icon || '');
      const next = {};
      EDIT_TARGET_PLATFORMS.forEach((p) => {
        const cfg = (target.platforms && target.platforms[p]) || null;
        next[p] = {
          enabled: !!cfg,
          // Whether this platform existed on the loaded manifest — distinct from `enabled`, which
          // tracks the checkbox's current (possibly since-toggled) state. Needed so unchecking an
          // existing platform can send an explicit removal instead of silently no-op'ing (the
          // server only touches platform keys present in the request; a platform the user never
          // had and left unchecked has nothing to remove).
          wasPresent: !!cfg,
          appIds: (cfg && cfg.app_ids || []).join(', '),
          baseUrl: (cfg && cfg.base_url) || '',
          icon: (cfg && cfg.icon) || '',
        };
      });
      setPlatforms(next);
      setLoading(false);
    });
    return () => { live = false; };
  }, [manifestPath]);

  const setPlatformField = (p, field, value) => {
    setPlatforms((prev) => ({ ...prev, [p]: { ...(prev[p] || { enabled: false, appIds: '', baseUrl: '', icon: '' }), [field]: value } }));
  };

  // Create mode's "Browse installed apps": one lazily-fetched inventory per mobile platform,
  // unioned across that platform's connected devices, cached for the modal's lifetime. Absent key =
  // not fetched (or no connected devices — the picker just doesn't render, free text remains).
  const [installedByPlatform, setInstalledByPlatform] = React.useState({});
  // Off by default (declutters the common case: targeting your own app, not the ~200 OS
  // packages on a stock image) — but a preinstalled app (the device's browser, calculator, etc.)
  // is a legitimate target, not an edge case to hide entirely. `force` re-fetches an
  // already-cached platform (used when this toggle flips) instead of trusting the cache.
  const [showSystemApps, setShowSystemApps] = React.useState(false);
  // `includeSystemApps` is an explicit param (not read from the `showSystemApps` state closure)
  // so the toggle's onChange can force a re-fetch with the NEW value in the same tick, rather
  // than racing the state update.
  const ensureInstalledApps = (p, force, includeSystemApps) => {
    if (!isNew || p === 'web') return;
    if (!force && installedByPlatform[p]) return;
    const devs = (deviceList || []).filter((d) => d.platform === p && d.connected);
    if (devs.length === 0) return;
    setInstalledByPlatform((prev) => ({ ...prev, [p]: { loading: true, apps: [] } }));
    Promise.all(devs.map((d) => Promise.resolve(TB.fetchInstalledApps(p, d.id, includeSystemApps)).catch(() => [])))
      .then((lists) => {
        const byId = {};
        lists.forEach((list, i) => (list || []).forEach((a) => {
          // Keep which device contributed the app — the per-app badge (label + icon) extraction
          // reads that device's copy.
          if (a && a.appId && !byId[a.appId]) byId[a.appId] = { ...a, deviceId: devs[i].id };
        }));
        // Label then appId as a tiebreaker (matches the server-side sort in toInstalledAppPickerDtos)
        // — two builds of the same app commonly share a label, and without the tiebreaker their
        // relative order would depend on device probe order rather than being deterministic.
        const apps = Object.values(byId).sort((x, y) =>
          (x.label || x.appId).toLowerCase().localeCompare((y.label || y.appId).toLowerCase()) || x.appId.localeCompare(y.appId));
        setInstalledByPlatform((prev) => ({ ...prev, [p]: { loading: false, apps } }));
        // Enrich each row with its human label + real app icon as extraction completes — rows
        // render immediately with app ids and upgrade in place (no re-sort: rows shouldn't jump
        // under the pointer while the popup is open).
        apps.forEach((a) => {
          Promise.resolve(TB.fetchInstalledAppBadge(p, a.deviceId, a.appId)).then((b) => {
            if (!b) return;
            setInstalledByPlatform((prev) => {
              const cur = prev[p];
              if (!cur) return prev;
              return {
                ...prev,
                [p]: { ...cur, apps: cur.apps.map((x) => (x.appId === a.appId ? { ...x, label: x.label || b.label, hasIcon: !!b.hasIcon } : x)) },
              };
            });
          }).catch(() => {});
        });
      });
  };

  const idToSave = isNew ? newId.trim() : trailmapId;
  const idInvalid = isNew && !NEW_TARGET_ID.test(idToSave);
  // The server treats a createIfMissing save on an existing trailmap as an edit (deliberately —
  // that's the registration-retry path), so guard here against unknowingly mutating an existing
  // trailmap (or converting a library one into a target) from a form that says "Creates ...".
  const idTaken = isNew && !!idToSave && (existingIds || []).includes(idToSave);
  // A created target with no platforms would be invisible in the picker (declared cards only
  // render for targets with a platform) — success with nothing to show. Require one up front.
  const platformsMissing = isNew && !EDIT_TARGET_PLATFORMS.some((p) => platforms[p] && platforms[p].enabled);

  const save = async () => {
    const nm = displayName.trim();
    if (!nm || busy || idInvalid || idTaken || platformsMissing) return;
    setBusy(true); setErr(null);
    const platformsPatch = {};
    EDIT_TARGET_PLATFORMS.forEach((p) => {
      const cfg = platforms[p];
      if (!cfg) return;
      if (cfg.enabled) {
        // A blank field means "leave/clear this field", not "set it to an empty list" — the
        // server's `null` clears the field, but an empty array is a real value that gets written
        // to the YAML (`app_ids: []`), which would inject a spurious empty list any time the field
        // is blank (always true for `web`, which has no app_ids field in this form at all).
        const appIds = cfg.appIds.trim() ? cfg.appIds.split(',').map((s) => s.trim()).filter(Boolean) : null;
        platformsPatch[p] = {
          appIds,
          baseUrl: cfg.baseUrl.trim() || null,
          icon: cfg.icon.trim() || null,
        };
      } else if (cfg.wasPresent) {
        // Unchecked a platform that existed on the manifest — send an explicit removal rather
        // than omitting the key, which the server treats as "leave untouched."
        platformsPatch[p] = { remove: true };
      }
    });
    const r = await TB.saveTargetConfig({
      trailmapId: idToSave, displayName: nm, icon: icon.trim() || null, platforms: platformsPatch,
      createIfMissing: !!isNew,
    });
    setBusy(false);
    if (!r.ok) { setErr(r.error || 'Could not save'); return; }
    onSaved(idToSave, r);
    if (r.warning) setWarn(r.warning); else onClose();
  };

  const platLabel = (p) => (p === 'ios' ? 'iOS' : p === 'android' ? 'Android' : 'Web');

  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, 94vw)', padding: 24, maxHeight: '86vh', overflowY: 'auto' }}>
        <h2 className="tb-h2" style={{ marginBottom: 6 }}>{isNew ? 'Create target' : 'Edit target'}</h2>
        <p className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '0 0 16px' }}>
          {isNew
            ? <React.Fragment>Creates <span className="tb-mono">trails/config/trailmaps/{idToSave || '<id>'}/trailmap.yaml</span> in your workspace with a minimal <span className="tb-mono">target:</span> block.</React.Fragment>
            : manifestPath
              ? <React.Fragment>Updates the <span className="tb-mono">target:</span> block of <span className="tb-mono">{manifestPath}</span>.</React.Fragment>
              : <React.Fragment>Updates the <span className="tb-mono">target:</span> block of the <span className="tb-mono">{trailmapId}</span> trailmap's manifest.</React.Fragment>}
        </p>
        {!manifestPath && !isNew ? (
          <div className="tb-sub" style={{ fontSize: 12.5 }}>Could not find a trailmap manifest for this target — editing isn't available here.</div>
        ) : loading ? <Skeleton rows={6} /> : (
          <React.Fragment>
            {isNew && (
              <React.Fragment>
                <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Trailmap ID</div>
                <div className="tb-input"><input autoFocus className="tb-mono" placeholder="myapp" value={newId} onChange={(e) => setNewId(e.target.value)} /></div>
                <div className="tb-sub" style={{ fontSize: 11, margin: '4px 0 0' }}>
                  {newId.trim() && idInvalid ? 'Letters, numbers, _ and - only.' : idTaken ? `A trailmap named \u201C${idToSave}\u201D already exists \u2014 edit it from its card instead.` : 'Doubles as the folder name and the target id you run against.'}
                </div>
              </React.Fragment>
            )}
            <div className="tb-eyebrow" style={{ margin: isNew ? '14px 0 6px' : '0 0 6px' }}>Display name</div>
            <div className="tb-input"><input autoFocus={!isNew} value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></div>
            <div className="tb-eyebrow" style={{ margin: '14px 0 6px' }}>Icon (optional override)</div>
            <div className="tb-input"><input placeholder="assets/icons/my-app.png" value={icon} onChange={(e) => setIcon(e.target.value)} /></div>

            <div className="tb-eyebrow" style={{ margin: '18px 0 8px' }}>Platforms</div>
            {isNew && (
              <React.Fragment>
                <div className="tb-sub" style={{ fontSize: 11, margin: '0 0 8px' }}>Pick at least one — the target only shows up for the platforms it declares.</div>
                <label style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer', fontSize: 11.5, marginBottom: 10 }}>
                  <input type="checkbox" checked={showSystemApps} onChange={(e) => {
                    const next = e.target.checked;
                    setShowSystemApps(next);
                    // Re-fetch every already-expanded platform's Browse list with the new
                    // setting — otherwise toggling it would silently do nothing until the user
                    // unchecked and rechecked a platform.
                    EDIT_TARGET_PLATFORMS.forEach((p) => {
                      if (p !== 'web' && platforms[p] && platforms[p].enabled) ensureInstalledApps(p, true, next);
                    });
                  }} />
                  <span className="tb-sub">Include system apps in Browse (browser, calculator, etc.)</span>
                </label>
              </React.Fragment>
            )}
            {EDIT_TARGET_PLATFORMS.map((p) => {
              const cfg = platforms[p] || { enabled: false, appIds: '', baseUrl: '', icon: '' };
              return (
                <div key={p} style={{ border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: 10, marginBottom: 8 }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 12.5, fontWeight: 700 }}>
                    <input type="checkbox" checked={cfg.enabled} onChange={(e) => { setPlatformField(p, 'enabled', e.target.checked); if (e.target.checked) ensureInstalledApps(p, false, showSystemApps); }} />
                    {platLabel(p)}
                  </label>
                  {cfg.enabled && (
                    <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 8 }}>
                      <div>
                        <div className="tb-sub" style={{ fontSize: 11, marginBottom: 3 }}>{p === 'web' ? 'Base URL' : 'App IDs (comma-separated)'}</div>
                        {p === 'web'
                          ? <div className="tb-input"><input value={cfg.baseUrl} onChange={(e) => setPlatformField(p, 'baseUrl', e.target.value)} placeholder="https://example.com" /></div>
                          : <div className="tb-input"><input value={cfg.appIds} onChange={(e) => setPlatformField(p, 'appIds', e.target.value)} placeholder="com.example.app" /></div>}
                        {/* Create mode: pick the app id off a connected device instead of typing it
                            blind. Selecting fills App IDs (appending, deduped) and seeds an empty
                            Display Name from the app's label. Hidden when no device of this
                            platform is connected — free text is the only option there anyway. */}
                        {isNew && p !== 'web' && (() => {
                          const inv = installedByPlatform[p];
                          if (!inv) return null;
                          if (inv.loading) return <div className="tb-sub" style={{ fontSize: 11, marginTop: 5 }}>Scanning connected devices…</div>;
                          if (inv.apps.length === 0) return null;
                          return (
                            <div style={{ marginTop: 6 }}>
                              <Select full value="" onChange={(e) => {
                                const appId = e.target.value;
                                if (!appId) return;
                                const app = inv.apps.find((a) => a.appId === appId);
                                const cur = cfg.appIds.trim();
                                const already = cur && cur.split(',').map((s) => s.trim()).includes(appId);
                                if (!already) setPlatformField(p, 'appIds', cur ? cur + ', ' + appId : appId);
                                if (app && app.label && !displayName.trim()) setDisplayName(app.label);
                              }}
                                options={[
                                  { value: '', label: `Browse ${inv.apps.length} installed app${inv.apps.length === 1 ? '' : 's'} on your connected device${(deviceList || []).filter((d) => d.platform === p && d.connected).length === 1 ? '' : 's'}…`, ico: 'search' },
                                  ...inv.apps.map((a) => ({
                                    value: a.appId,
                                    label: a.label ? `${a.label} (${a.appId})` : a.appId,
                                    img: a.hasIcon ? TB.installedAppIconUrl(p, a.deviceId, a.appId) : undefined,
                                    ico: a.hasIcon ? undefined : (p === 'ios' ? 'apple' : 'smartphone'),
                                  })),
                                ]} />
                            </div>
                          );
                        })()}
                      </div>
                      <div>
                        <div className="tb-sub" style={{ fontSize: 11, marginBottom: 3 }}>Icon override</div>
                        <div className="tb-input"><input value={cfg.icon} onChange={(e) => setPlatformField(p, 'icon', e.target.value)} placeholder={`assets/icons/${p}.png`} /></div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </React.Fragment>
        )}
        {err ? <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-fail)' }}>{err}</div> : null}
        {warn ? <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-amber)' }}>{warn}</div> : null}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
          {warn ? (
            <Btn sm kind="primary" onClick={onClose}>Close</Btn>
          ) : (
            <React.Fragment>
              <Btn sm onClick={onClose}>Cancel</Btn>
              {(manifestPath || isNew) && (
                <Btn sm kind="primary" ico={isNew ? 'plus' : 'check'} onClick={save}
                  style={{ opacity: (busy || loading || idInvalid || idTaken || platformsMissing) ? 0.5 : 1, pointerEvents: (busy || loading || idInvalid || idTaken || platformsMissing) ? 'none' : 'auto' }}>
                  {busy ? 'Saving…' : isNew ? 'Create' : 'Save'}
                </Btn>
              )}
            </React.Fragment>
          )}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { TargetDevicePicker });

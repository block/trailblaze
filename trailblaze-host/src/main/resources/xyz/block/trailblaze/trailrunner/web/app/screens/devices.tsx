// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The target + device picker. It now lives in the Home screen's left rail. Home and the
// old standalone "Targets" screen are one screen. Pick a target app, then tick the devices
// to run it on. The choice is the global target: it scopes the Trailmaps views and is the
// default for runs. Styled as a left list-rail to match the other tabs.
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
  const groupList = Object.values(groups).sort((x, y) => x.label.localeCompare(y.label));

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
        right={<button className="tb-btn ghost sm" style={{ padding: 6 }} title="Refresh devices" onClick={refresh}><Ico n="refresh-cw" s={16} spin={devices.loading || appsLoading} /></button>}
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '2px 10px 12px' }}>
        {(devices.loading || appsLoading) && groupList.length === 0 && <div style={{ padding: '4px 2px' }}><Skeleton rows={4} /></div>}
        {empty && <EmptyState ico="smartphone" title="No devices" sub="Connect a phone or boot an emulator, then Refresh." />}
        {noGroups && !empty && <EmptyState ico="package" title="No target apps" sub="No known target apps are installed on the connected devices." />}

        {groupList.map((g) => {
          const selectedIds = gt && gt.target === g.id ? (gt.deviceIds || []).filter((id) => g.items.some((it) => it.device.id === id)) : [];
          return (
            <TargetCard key={g.id}
              icon={<AppIcon target={g.id} size={18} fallbackColor={selectedIds.length ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />}
              label={g.label} items={g.items} selectedIds={selectedIds}
              onToggleTarget={() => toggleGroup(g)} onToggleDevice={(d) => toggleDevice(g.id, g.label, d)} />
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

        {/* Only the always-available Web target is here — no booted iOS/Android device, so no app
            targets to group. Tell the user how to get them rather than leaving the rail looking empty. */}
        {!appsLoading && groupList.length === 0 && webDevices.length > 0 && (
          <div style={{ marginTop: 12, padding: '12px 13px', border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <Ico n="smartphone" s={16} c="var(--text-subtle)" style={{ flex: '0 0 auto', marginTop: 1 }} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-standard)', marginBottom: 3 }}>Testing a mobile app?</div>
              <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5 }}>Only Web is available. Open an iOS Simulator or Android emulator with your target app installed, then Refresh — it'll appear here as a target.</div>
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
    </React.Fragment>
  );
}

// A target is the unit you click: the whole header selects the app and its devices in one
// click, no need to tick a device. A single-device target shows the device inline; a
// multi-device target reveals per-device toggles below for fine-grained control.
function TargetCard({ icon, label, items, selectedIds, onToggleTarget, onToggleDevice }) {
  const accent = selectedIds.length > 0;
  const single = items.length === 1;
  const it0 = items[0];
  const plat = (p) => (p === 'ios' ? 'iOS' : p === 'android' ? 'Android' : 'Web');
  const summary = single
    ? [it0.device.name, plat(it0.device.platform), it0.app && it0.app.versionName].filter(Boolean).join(' · ')
    : `${items.length} devices · ${items.filter((it) => it.device.connected).length} connected`;
  return (
    <div style={{ border: '1px solid ' + (accent ? 'var(--tb-pass)' : 'var(--tb-hairline)'), borderRadius: 10, marginBottom: 8, overflow: 'hidden', background: 'var(--bg-standard)' }}>
      <button onClick={onToggleTarget} title={accent ? 'Selected, click to clear' : 'Select this target'}
        aria-pressed={accent} aria-label={(accent ? 'Selected target ' : 'Select target ') + label + ' (' + summary + ')'}
        style={{ display: 'flex', alignItems: 'center', gap: 9, width: '100%', textAlign: 'left', padding: '10px 11px', background: 'transparent', border: 'none', cursor: 'pointer' }}>
        <span style={{ flex: '0 0 auto', width: 14, height: 14, borderRadius: 4, boxSizing: 'border-box',
          background: accent ? 'var(--tb-pass)' : 'transparent', border: accent ? 'none' : '1.5px solid var(--text-subtle-variant)' }} />
        {icon}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13.5, fontWeight: 700, color: accent ? 'var(--tb-pass)' : 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{label}</div>
          <div className="tb-mono tb-sub" style={{ fontSize: 10, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{summary}</div>
        </div>
        {single
          ? <span role="img" title={it0.device.connected ? 'Connected' : 'Offline'} aria-label={it0.device.connected ? 'Connected' : 'Offline'}
              style={{ flex: '0 0 auto', width: 8, height: 8, borderRadius: 99, background: it0.device.connected ? 'var(--tb-pass)' : 'var(--tb-fail)' }} />
          : <Chip tone={accent ? 'green' : ''}>{accent ? selectedIds.length + ' of ' + items.length : items.length}</Chip>}
      </button>
      {!single && (
        <div style={{ padding: '0 7px 7px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {items.map(({ device: d, app: a }) => (
            <DeviceToggleRow key={d.id} device={d} app={a} selected={selectedIds.includes(d.id)} onToggle={() => onToggleDevice(d)} />
          ))}
        </div>
      )}
    </div>
  );
}

function DeviceToggleRow({ device: d, app: a, selected, onToggle }) {
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
      <PlatformGlyph platform={d.platform} s={14} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto' }} />
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

function DevicesSummaryPanel({ go }) {
  useLucide();
  const devices = TB.useDevices();
  const [gt] = TB.useGlobalTarget();
  const deviceList = devices.data || [];
  const selected = ((gt && gt.deviceIds) || []).map((id) => deviceList.find((d) => d.id === id)).filter(Boolean);
  const connected = deviceList.filter((d) => d.connected);
  const selectedConnected = selected.filter((d) => d.connected !== false);
  const platformLabel = (p) => (p === 'ios' ? 'iOS' : p === 'android' ? 'Android' : p === 'web' ? 'Web' : (p || 'Unknown'));
  return (
    <div style={{ padding: '28px 32px 60px', overflowY: 'auto', height: '100%', boxSizing: 'border-box' }}>
      <ScreenHead ico="smartphone" title="Devices" sub="Choose the app under test and the connected devices Trail Runner should use." />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12, marginTop: 18 }}>
        <div className="tb-card pad">
          <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Target</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <AppIcon target={gt && gt.target} size={28} radius={7} fallbackColor={gt ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{gt ? (gt.label || gt.target || 'Web') : 'No target selected'}</div>
              <div className="tb-sub" style={{ fontSize: 12, marginTop: 2 }}>{selected.length} selected device{selected.length === 1 ? '' : 's'}</div>
            </div>
          </div>
        </div>
        <div className="tb-card pad">
          <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Connected</div>
          <div style={{ fontSize: 24, fontWeight: 700, lineHeight: 1 }}>{connected.length}</div>
          <div className="tb-sub" style={{ fontSize: 12, marginTop: 5 }}>{deviceList.length} total device{deviceList.length === 1 ? '' : 's'} discovered</div>
        </div>
        <div className="tb-card pad">
          <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Next run</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 8, height: 8, borderRadius: 99, background: selectedConnected.length ? 'var(--tb-pass)' : 'var(--tb-amber)', flex: '0 0 auto' }} />
            <span style={{ fontSize: 13.5, fontWeight: 600 }}>{selectedConnected.length ? 'Ready to run' : selected.length ? 'Selected devices are offline' : 'Select a device'}</span>
          </div>
          <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.45, marginTop: 7 }}>Run dialogs default to this target and device selection.</div>
        </div>
      </div>

      <div className="tb-card" style={{ marginTop: 16, overflow: 'hidden' }}>
        <div style={{ padding: '13px 16px', borderBottom: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 9 }}>
          <Ico n="check-square" s={15} c="var(--tb-running)" />
          <span style={{ fontSize: 13.5, fontWeight: 700 }}>Selected devices</span>
        </div>
        <div style={{ padding: 14 }}>
          {selected.length === 0 ? (
            <EmptyState ico="mouse-pointer-2" title="No devices selected" sub="Use the picker on the left to choose where Trail Runner should run trails." />
          ) : (
            <div style={{ display: 'grid', gap: 8 }}>
              {selected.map((d) => (
                <div key={d.id} className="tb-row" style={{ marginBottom: 0 }}>
                  <PlatformGlyph platform={d.platform} s={18} c="var(--text-subtle-variant)" />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13.5, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.name}</div>
                    <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 2 }}>{platformLabel(d.platform)} · {d.driver || 'driver'} · {d.id}</div>
                  </div>
                  <Chip tone={d.connected ? 'green' : 'red'}>{d.connected ? 'Connected' : 'Offline'}</Chip>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 14, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
        <Btn kind="ghost" ico="sparkles" onClick={() => go('create')}>Create prompt</Btn>
        <Btn kind="ghost" ico="pointer" onClick={() => go('interact')}>Interact</Btn>
        <Btn kind="primary" ico="route" onClick={() => go('trails')}>Browse trails</Btn>
      </div>
    </div>
  );
}

function DevicesScreen({ go }) {
  const [railW, startDrag] = useResizableWidth('tb-devices-target-w', 332, 280, 520);
  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      <div style={{ width: railW, flex: '0 0 ' + railW + 'px', minWidth: 0, borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <TargetDevicePicker go={go} />
      </div>
      <Splitter onDown={startDrag} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <DevicesSummaryPanel go={go} />
      </div>
    </div>
  );
}

Object.assign(window, { TargetDevicePicker, DeviceToggleRow, DevicesScreen, DevicesSummaryPanel });

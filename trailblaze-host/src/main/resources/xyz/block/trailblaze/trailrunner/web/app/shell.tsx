// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

const { useState, useEffect } = React;

class Boundary extends React.Component {
  constructor(p){ super(p); this.state = { err: null }; }
  static getDerivedStateFromError(err){ return { err }; }
  componentDidCatch(err){ console.error('Screen error:', err); }
  render(){
    if (this.state.err) return <div style={{padding:32, color:'var(--tb-danger-text)', fontFamily:'var(--font-mono)', fontSize:13, whiteSpace:'pre-wrap'}}>Screen error:{'\n'}{String(this.state.err && this.state.err.stack || this.state.err)}</div>;
    return this.props.children;
  }
}

// Last-resort boundary around the WHOLE app. The per-screen Boundary above only covers .tb-main;
// a throw in the nav rail, command palette, or run dialog previously unmounted the entire root -
// a solid blank page with the only recovery being a manual reload. This gives that failure an
// honest card and a reload button instead.
class RootBoundary extends React.Component {
  constructor(p){ super(p); this.state = { err: null }; }
  static getDerivedStateFromError(err){ return { err }; }
  componentDidCatch(err){ console.error('App error:', err); }
  render(){
    if (this.state.err) {
      return (
        <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-standard, #111)' }}>
          <div className="tb-card" style={{ width: 'min(440px, 90vw)', padding: 20, background: 'var(--bg-elevated, #1c1c1c)' }}>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-standard, #eee)' }}>Something broke</div>
            <div style={{ fontSize: 12.5, lineHeight: 1.55, marginTop: 8, color: 'var(--text-subtle, #aaa)' }}>
              The app hit an unexpected error and couldn't recover on its own. Reloading usually fixes it - your trails and runs are safe on disk.
            </div>
            <div className="tb-mono" style={{ fontSize: 10.5, marginTop: 10, color: 'var(--text-subtle, #888)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 120, overflowY: 'auto' }}>
              {String((this.state.err && this.state.err.message) || this.state.err)}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 14 }}>
              <button type="button" onClick={() => window.location.reload()}
                style={{ cursor: 'pointer', font: 'inherit', fontSize: 12.5, fontWeight: 600, borderRadius: 8, padding: '7px 14px', border: '1px solid var(--tb-hairline, #333)', background: 'var(--bg-subtle, #222)', color: 'var(--text-standard, #eee)' }}>
                Reload the app
              </button>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

const NAV = [
  // Home is reached via the target chip at the top of the rail (no standalone nav item) — it folds
  // in the target/device picker. The Create group (one door: drive the device, describe a flow,
  // or both) renders its own session list above these, in NavRail.
  { group: 'Trails', items: [['trails', 'List', 'route']] },
  { group: 'Runs', items: [['active', 'Active', 'radio'], ['completed', 'Completed', 'check-circle-2']] },
];
// Trailmaps is reference material, not part of the Blaze→Trails authoring flow — pin it to the
// bottom of the rail (just above Search), visually separated by a divider.
const TRAILMAPS = [['trailheads', 'Trailheads', 'flag'], ['tools', 'Tools', 'wrench']];
// Integrations is folded into the Settings screen (reached from there), so the rail foot is just
// Settings. The `integrations` route still exists; Settings links to it.
const FOOT = [['settings', 'Settings', 'settings']];

// Top-of-sidebar target picker. Shows "Select Target" when nothing is
// chosen; otherwise the active target app and how many devices are selected for it, with
// a status dot. Tapping opens Home, where the full target + device picker now lives.
function TargetPicker({ go, route, collapsed }) {
  useLucide();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const deviceList = devices.data || [];
  // Count from gt.deviceIds (the authoritative selection), not from what we can re-resolve in this
  // component's device list — a transient mismatch there was making the chip read "No devices
  // selected" with one device chosen, or show a single device's name when two were selected.
  const selIds = (gt && gt.deviceIds) || [];
  const selCount = selIds.length;
  const selectedDevices = selIds.map((id) => deviceList.find((d) => d.id === id)).filter(Boolean);
  const anyOffline = selectedDevices.some((d) => !d.connected);
  const dot = !gt ? 'var(--tb-fail)'
    : selCount === 0 ? 'var(--tb-amber)'
    : anyOffline ? 'var(--tb-amber)'
    : 'var(--tb-pass)';
  const platLabel = (p) => (p === 'ios' ? 'iOS' : p === 'android' ? 'Android' : p === 'web' ? 'Web' : (p || ''));
  const selPlatforms = [...new Set(selectedDevices.map((d) => d.platform).filter(Boolean))];
  const genericWeb = !!(gt && !gt.target && gt.label === 'Web');
  const genericWebIcon = genericWeb ? <Ico n="globe" s={17} c="var(--tb-pass)" /> : undefined;
  // 0 → none; 1 → the device name; 2+ → both platforms ("iOS · Android") when every selected device
  // resolved to a distinct platform, otherwise just the count so the number is always right.
  const deviceSummary = selCount === 0 ? 'No devices selected'
    : selCount === 1 ? (selectedDevices[0] ? selectedDevices[0].name : '1 device')
    : (selectedDevices.length === selCount && selPlatforms.length === selCount ? selPlatforms.map(platLabel).join(' · ') : `${selCount} devices`);
  // The target picker now lives on Home (the standalone Targets screen was folded in), so
  // tapping this jumps Home and highlights while you're there.
  const on = route === 'home';
  if (collapsed) {
    return (
      <div style={{ padding: '0 0 10px', display: 'flex', justifyContent: 'center' }}>
        <div role="button" tabIndex={0} onClick={() => go('home')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('home'); } }}
          title={(gt ? (gt.label || gt.target) + ' · ' + deviceSummary : 'Select a target') + ' - open Home'}
          style={{ position: 'relative', display: 'inline-flex', cursor: 'pointer', padding: 3, borderRadius: 8, background: on ? 'var(--bg-standard)' : 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)' }}>
          <AppIcon target={gt && gt.target} size={30} radius={7} fallbackColor={gt ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} fallbackNode={genericWebIcon} />
          <span style={{ position: 'absolute', right: -2, bottom: -2, width: 9, height: 9, borderRadius: 99, background: dot, border: '2px solid var(--bg-sheet)' }} />
        </div>
      </div>
    );
  }
  return (
    <div style={{ padding: '0 0 10px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 11, width: '100%', padding: '8px 9px', borderRadius: 8,
        background: on ? 'var(--bg-standard)' : 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', color: 'var(--text-standard)' }}>
        <div role="button" tabIndex={0} onClick={() => go('home')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('home'); } }}
          title="Open Home (choose the target app and its devices)" style={{ display: 'flex', alignItems: 'center', gap: 11, flex: 1, minWidth: 0, cursor: 'pointer' }}>
          {/* Size the icon to the two-line text block so its top/bottom line up with the words. */}
          <AppIcon target={gt && gt.target} size={30} radius={7} fallbackColor={gt ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} fallbackNode={genericWebIcon} />
          <div style={{ flex: 1, minWidth: 0 }}>
            {/* Always render both lines so selecting a target doesn't change the row height. */}
            <div style={{ fontSize: 13, fontWeight: 600, lineHeight: 1.3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{gt ? (gt.label || gt.target) : 'Select Target'}</div>
            <div className="tb-sub" style={{ fontSize: 10.5, lineHeight: 1.3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{gt ? deviceSummary : 'No target selected'}</div>
          </div>
        </div>
        <span style={{ width: 8, height: 8, borderRadius: 99, flex: '0 0 auto', background: dot }} />
      </div>
    </div>
  );
}

// One badge cluster (right-aligned) for a nav item: a glowing dot when the item has live
// activity (an in-flight run), then a count pill. Kept together so the dot reads as
// attached to its number, not floating after the label.
function NavBadge({ badge }) {
  if (!badge || (!badge.glow && !badge.count)) return null;
  // Layout (margin-left auto) lives in CSS (.tb-nav .bdg): the collapsed rail re-anchors the
  // cluster to the row corner - an inline auto margin would defeat the row's centered icon there.
  return (
    <span className="bdg" style={{ display: 'inline-flex', alignItems: 'center', gap: 7, flex: '0 0 auto' }}>
      {badge.glow && <span className="tb-glowdot" role="img" title="Run in progress" aria-label="Run in progress" />}
      {badge.count ? <span className="cnt" style={{ marginLeft: 0 }}>{badge.count}</span> : null}
    </span>
  );
}

// Workspace marker sitting right under the target picker: the repo-root directory name of the
// active trails workspace, so you always know which checkout you're driving (worktrees resolve
// to the real repo name). Clicking opens a popover that explains the workspace is the source of
// every trail/tool/recording, and lets you re-point it to another folder or reveal it on disk.
// The popover is fixed-positioned (the rail is overflow:hidden, so an absolute card would clip).
function WorkspaceChip({ collapsed }) {
  useLucide();
  const status = TB.useStatus();
  const dir = status.data && status.data.trailsDirectory;
  const [open, setOpen] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const [notice, setNotice] = React.useState(null);
  // Persistent "restart to load app targets" signal (array of added target ids, or null). The chip is
  // always visible, so it owns this badge no matter which surface triggered the switch — see
  // setTargetsRestartNeeded. It survives navigation and SPA re-renders (sessionStorage-backed).
  const [restart, setRestart] = React.useState(() => TB.getTargetsRestartNeeded());
  const [pos, setPos] = React.useState(null);
  const btnRef = React.useRef(null);
  React.useEffect(() => {
    const sync = () => setRestart(TB.getTargetsRestartNeeded());
    window.addEventListener('tb:targets-restart-changed', sync);
    return () => window.removeEventListener('tb:targets-restart-changed', sync);
  }, []);
  React.useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    // The popover is fixed-positioned from a one-shot rect captured on open. The rail itself
    // scrolls and the window can resize, either of which would detach the card from the chip,
    // so close on scroll (capture, to catch the rail's own scroll) and on resize.
    const close = () => setOpen(false);
    window.addEventListener('keydown', onKey);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [open]);
  if (!dir) return null;
  const name = workspaceRepoName(dir);
  const branch = status.data && status.data.workspaceBranch;
  const isWorktree = !!(status.data && status.data.workspaceIsWorktree);
  const openPop = () => {
    const r = btnRef.current && btnRef.current.getBoundingClientRect();
    if (r) setPos({ left: r.left, top: r.bottom + 6 });
    setErr(null);
    setNotice(null);
    setOpen(true);
  };
  const change = async () => {
    setBusy(true);
    setErr(null);
    setNotice(null);
    // Wrap the whole flow (picker + switch) so a rejection can't leave the button stuck disabled,
    // and a failed switch surfaces an inline error instead of silently leaving the popover open
    // looking like nothing happened. switchWorkspace persists the pick and broadcasts the global
    // refresh signal so every list (trails, trailmaps, tools, …) re-resolves against the new folder.
    try {
      const path = await TB.pickDirectoryViaShell(dir);
      if (!path) return; // cancelled — finally still clears busy
      const res = await TB.switchWorkspace(path);
      if (!res.ok) {
        setErr(res.error || 'Could not switch to that folder. It may not be a readable directory.');
        return;
      }
      // No explicit status.reload() here: switchWorkspace already broadcast tb:workspace-changed, and
      // useStatus is useFetched-backed, so the chip's own status refetches from that one signal.
      // Empty-workspace note is advisory about the folder you just picked, so it's fine inline.
      // Restart-needed is handled by the persistent badge (switchWorkspace already recorded it), so
      // it isn't duplicated here.
      if (res.empty) {
        setNotice(TB.WORKSPACE_EMPTY_NOTICE);
      } else {
        setOpen(false);
      }
    } catch (e) {
      setErr('Could not change the workspace: ' + (e && e.message ? e.message : String(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div style={{ padding: '0 0 10px', ...(collapsed ? { display: 'flex', justifyContent: 'center' } : {}) }}>
      <button ref={btnRef} onClick={() => (open ? setOpen(false) : openPop())} aria-expanded={open}
        title={'Workspace: ' + dir + '\n' + TB.WORKSPACE_BLURB + (branch ? '\nBranch: ' + branch + (isWorktree ? ' (git worktree)' : '') : '') + (restart ? '\n\nRestart needed to load this workspace’s app targets.' : '')}
        style={collapsed
          // 38px square: the exact outer box of the collapsed target chip above (30px icon + 3px
          // padding + 1px border per side), so the two read as one aligned column of tiles.
          ? { position: 'relative', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 38, height: 38, borderRadius: 8, background: open ? 'var(--bg-standard)' : 'transparent', border: '1px solid var(--tb-hairline)', cursor: 'pointer', color: 'var(--text-standard)' }
          : { display: 'flex', alignItems: 'center', gap: 9, width: '100%', padding: '7px 9px', borderRadius: 8, background: open ? 'var(--bg-standard)' : 'transparent', border: '1px solid var(--tb-hairline)', cursor: 'pointer', color: 'var(--text-standard)' }}>
        <Ico n="folder-git-2" s={15} c={collapsed && isWorktree ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
        {!collapsed && (
          <span className="label" style={{ flex: 1, minWidth: 0, textAlign: 'left', overflow: 'hidden' }}>
            <span style={{ display: 'block', fontSize: 12.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</span>
            {branch && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--text-subtle)', overflow: 'hidden' }}>
                <Ico n="git-branch" s={10} c={isWorktree ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{branch}</span>
                {isWorktree && <span style={{ flex: '0 0 auto', color: 'var(--tb-running)', fontWeight: 600 }}>· worktree</span>}
              </span>
            )}
          </span>
        )}
        {!collapsed && restart && <span aria-label="Restart needed" title="Restart needed to load this workspace’s app targets" style={{ flex: '0 0 auto', width: 7, height: 7, borderRadius: '50%', background: 'var(--tb-amber)' }} />}
        {!collapsed && <Ico n="chevron-down" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />}
        {collapsed && restart && <span aria-label="Restart needed" style={{ position: 'absolute', right: -2, top: -2, width: 8, height: 8, borderRadius: '50%', background: 'var(--tb-amber)', border: '2px solid var(--bg-sheet)' }} />}
      </button>
      {open && pos && (
        <React.Fragment>
          <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={() => setOpen(false)} onContextMenu={(e) => { e.preventDefault(); setOpen(false); }} />
          <div className="tb-card" role="dialog" aria-label="Workspace" onClick={(e) => e.stopPropagation()} style={{ position: 'fixed', left: pos.left, top: pos.top, zIndex: 71, width: 312, maxWidth: '90vw', padding: 14, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)', cursor: 'default' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <Ico n="folder-git-2" s={16} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
              <span style={{ fontWeight: 600, fontSize: 13.5, flex: 1, color: 'var(--text-standard)' }}>Workspace</span>
              <Chip>{name}</Chip>
            </div>
            <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.6 }}>
              {TB.WORKSPACE_BLURB}
            </div>
            <div className="tb-mono" data-selectable style={{ fontSize: 11, lineHeight: 1.5, color: '#94a3b8', marginTop: 10, padding: '8px 10px', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, overflowWrap: 'anywhere', wordBreak: 'break-word' }}>{dir}</div>
            {branch && (
              <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, lineHeight: 1.5 }}>
                <Ico n="git-branch" s={13} c={isWorktree ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                <span style={{ color: 'var(--text-standard)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{branch}</span>
                {isWorktree && <Chip>git worktree</Chip>}
              </div>
            )}
            {restart && (
              <div role="status" style={{ marginTop: 10, fontSize: 11.5, lineHeight: 1.5, color: 'var(--tb-amber)', display: 'flex', gap: 7 }}>
                <Ico n="rotate-ccw" s={13} c="var(--tb-amber)" style={{ flex: '0 0 auto', marginTop: 1 }} />
                <span>{TB.workspaceRestartNotice(restart)}</span>
              </div>
            )}
            {err && <div role="alert" style={{ marginTop: 10, fontSize: 11.5, lineHeight: 1.5, color: 'var(--tb-danger-text)' }}>{err}</div>}
            {notice && <div role="status" style={{ marginTop: 10, fontSize: 11.5, lineHeight: 1.5, color: 'var(--text-subtle)' }}>{notice}</div>}
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <Btn sm ico={busy ? 'loader-2' : 'folder-open'} spin={busy} onClick={change} disabled={busy}>Change…</Btn>
              <Btn sm kind="ghost" ico="external-link" onClick={() => { TB.revealTrailsRoot(); setOpen(false); }}>Reveal in Finder</Btn>
            </div>
          </div>
        </React.Fragment>
      )}
    </div>
  );
}

// A session row in one of the rail's Create groups: status icon + title, smaller than the main
// nav items so the lists read as children of their group.
function SessionNavRow({ ico, color, spin, label, count, on, onClick, title }) {
  return (
    <div className={'tb-nav' + (on ? ' active' : '')} onClick={onClick} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
      role="button" tabIndex={0} title={title || label}>
      <span className="ico"><Ico n={ico} s={14} c={color} spin={spin} /></span>
      <span className="label" style={{ fontSize: 12.5, fontWeight: 500, color: on ? 'var(--text-standard)' : 'var(--text-subtle)' }}>{label}</span>
      {count != null && <NavBadge badge={{ count }} />}
    </div>
  );
}

// Group header for the rail's Create sections: the label with an inline "+" on the right (the way
// a new session starts - there is no separate "New Session" row). The + reads as pressed while
// that group's blank composer is the open screen.
function RailGroupHeader({ label, plusTitle, onPlus, plusOn }) {
  return (
    <div className="tb-rail-h" style={{ display: 'flex', alignItems: 'center', gap: 6, paddingRight: 5 }}>
      <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>{label}</span>
      <button type="button" onClick={onPlus} title={plusTitle} aria-label={plusTitle}
        style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 20, height: 20, borderRadius: 6, border: 'none', cursor: 'pointer', flex: '0 0 auto',
          color: plusOn ? 'var(--text-standard)' : 'var(--text-subtle)', background: plusOn ? 'var(--bg-prominent)' : 'transparent' }}>
        <Ico n="plus" s={13} />
      </button>
    </div>
  );
}

// How many agent conversations the rail lists before folding the rest behind "N older" (the daemon
// retains up to 50 finished conversations - the rail must not become a 50-row scroll by default).
const RAIL_AGENT_SESSIONS_VISIBLE = 6;

function NavRail({ route, go, badges = {}, openPalette, agentRuns = [], studioSel, companionSel, goStudio, interactSession, goInteract }) {
  const [collapsed, setCollapsed] = useStickyState('tb-rail-collapsed', false);
  const [showAllAgents, setShowAllAgents] = useState(false);
  const agentVisible = showAllAgents ? agentRuns : agentRuns.slice(0, RAIL_AGENT_SESSIONS_VISIBLE);
  const agentHidden = agentRuns.length - agentVisible.length;
  const interactActive = !!(interactSession && interactSession.active);
  return (
    <div className={'tb-rail' + (collapsed ? ' collapsed' : '')}>
      <div className="tb-brand" style={{ justifyContent: collapsed ? 'center' : 'space-between' }}>
        <span className="name">Trail Runner</span>
        <button type="button" onClick={() => setCollapsed((v) => !v)}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', border: 'none', background: 'transparent', color: 'var(--text-subtle)', cursor: 'pointer', padding: 4, borderRadius: 6 }}>
          <Ico n={collapsed ? 'panel-left-open' : 'panel-left-close'} s={16} />
        </button>
      </div>
      <TargetPicker go={go} route={route} collapsed={collapsed} />
      <WorkspaceChip collapsed={collapsed} />
      {/* Divider separating the workspace/target context from the Blaze→Runs authoring nav. */}
      <div style={{ height: 1, background: 'var(--tb-hairline)', margin: '0 8px 10px' }} />
      <div className="tb-rail-scroll">
        {/* Create: one door. The group header's + starts a session where you drive the device by
            hand, describe a flow to the agent, or both; the sessions themselves list right under
            it. The list lives here (not in a column on the Create screen) so sessions are
            reachable from anywhere. The collapsed (icon-only) rail swaps the header + rows for a
            single sparkles entry. */}
        <div className="tb-rail-group">
          {!collapsed && <RailGroupHeader label="Create" plusTitle="New session - drive the device by hand or describe a flow, every action becomes a step"
            onPlus={() => goStudio('new')} plusOn={route === 'create' && studioSel === 'new'} />}
          {collapsed && (
            <div className={'tb-nav' + (route === 'create' ? ' active' : '')} onClick={() => goStudio('new')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); goStudio('new'); } }}
              role="button" tabIndex={0} title="Create - new session">
              <span className="ico"><Ico n="sparkles" s={18} /></span><span className="label">Create</span><NavBadge badge={badges.create} />
            </div>
          )}
          {!collapsed && agentVisible.map((run) => {
            // A solo run's lifecycle status is an implementation detail (born finished, no
            // process): its row wears the hands-on identity instead of a status glyph.
            const solo = run.agentType === 'solo';
            const [ico, color] = solo ? ['pointer', 'var(--tb-running)'] : externalAgentStatusIcon(run.status);
            return (
              <SessionNavRow key={run.id} ico={ico} color={color} spin={run.status === 'running'}
                label={run.title || run.id} title={(run.title || run.id) + ' · ' + (solo ? 'solo' : run.status)}
                on={(route === 'create' && studioSel === run.id) || companionSel === run.id} onClick={() => goStudio(run.id)} />
            );
          })}
          {!collapsed && (agentHidden > 0 || showAllAgents) && (
            <div className="tb-nav" role="button" tabIndex={0} onClick={() => setShowAllAgents((v) => !v)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setShowAllAgents((v) => !v); } }}>
              <span className="ico"><Ico n={showAllAgents ? 'chevron-up' : 'chevron-down'} s={14} c="var(--text-subtle)" /></span>
              <span className="label" style={{ fontSize: 12, color: 'var(--text-subtle)' }}>{showAllAgents ? 'Show fewer' : agentHidden + ' older'}</span>
            </div>
          )}
          {/* An in-progress legacy Interact recording session (reached via the command palette's
              ad hoc YAML action) folds into the same group so navigating away and back stays
              obvious. */}
          {!collapsed && interactActive && (
            <SessionNavRow ico="disc" color="var(--tb-running)" spin={false}
              label={interactSession.label || 'Recording session'} count={interactSession.steps || null}
              title={(interactSession.label || 'Recording session') + (interactSession.steps ? ' · ' + interactSession.steps + ' steps' : '')}
              on={route === 'interact'} onClick={goInteract} />
          )}
        </div>
        {NAV.map((g, gi) => (
          <div className="tb-rail-group" key={gi}>
            {g.group && <div className="tb-rail-h">{g.group}</div>}
            {g.items.map(([id, label, ico]) => {
              const on = route === id;
              return (
              <div key={id} className={'tb-nav' + (on ? ' active' : '')} onClick={() => go(id)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(id); } }} role="button" tabIndex={0} title={label}>
                {on && <span style={{ position: 'absolute', left: -10, top: 7, bottom: 7, width: 3, borderRadius: 99, background: 'var(--tb-primary-green)' }}></span>}
                <span className="ico"><Ico n={ico} s={18} /></span><span className="label">{label}</span><NavBadge badge={badges[id]} />
              </div>
            ); })}
          </div>
        ))}
      </div>
      {/* Trailmaps = reference material, pinned to the bottom of the rail just above Settings. The
          flex-grow scroll area above is the spacer that pushes it down; it only scrolls (rather
          than crowding the authoring flow) when the window is too short to fit everything. */}
      <div style={{ height: 1, background: 'var(--tb-hairline)', margin: '4px 8px 8px' }} />
      <div className="tb-rail-group" style={{ marginBottom: 0 }}>
        <div className="tb-rail-h">Trailmaps</div>
        {TRAILMAPS.map(([id, label, ico]) => {
          const on = route === id;
          return (
            <div key={id} className={'tb-nav' + (on ? ' active' : '')} onClick={() => go(id)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(id); } }} role="button" tabIndex={0} title={label}>
              <span className="ico"><Ico n={ico} s={18} /></span><span className="label">{label}</span>
            </div>
          );
        })}
      </div>
      <div style={{ height: 1, background: 'var(--tb-hairline)', margin: '10px 8px 0' }} />
      <div className="tb-rail-foot">
        {FOOT.map(([id, label, ico]) => { const on = route === id;
          return (
          <div key={id} className={'tb-nav' + (on ? ' active' : '')} onClick={() => go(id)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(id); } }} role="button" tabIndex={0} title={label}>
            {on && <span style={{ position: 'absolute', left: -10, top: 7, bottom: 7, width: 3, borderRadius: 99, background: 'var(--tb-primary-green)' }}></span>}
            <span className="ico"><Ico n={ico} s={18} /></span><span className="label">{label}</span>
          </div>
        ); })}
      </div>
    </div>
  );
}

function CommandPalette({ go, openRun, close, closing, trails = [] }) {
  useLucide();
  const sessions = TB.useSessions();
  const [q, setQ] = useState('');
  const [sel, setSel] = useState(0);
  useEffect(() => { setSel(0); }, [q]);

  const ql = q.trim().toLowerCase();
  const match = (...vals) => !ql || vals.some((v) => v != null && String(v).toLowerCase().includes(ql));

  const actions = [
    ['sparkles', 'Create a trail', '⌘B', () => { go('create'); close(); }],
    ['braces', 'Run ad hoc YAML…', null, () => { go('interact', { openYaml: true }); close(); }],
    ['play', 'Run a trail…', '⌘↵', () => { openRun(); close(); }],
    ['smartphone', 'Choose target & devices…', '⌘D', () => { go('home'); close(); }],
    ['gallery-vertical-end', 'Go to Runs', '⌘O', () => { go('completed'); close(); }],
    ['wrench', 'Browse custom tools', null, () => { go('tools'); close(); }],
  ].filter(([, label]) => match(label)).map(([ico, label, kbd, fn]) => ({ ico, label, kbd, sub: null, fn }));

  const trailItems = (trails || [])
    .filter((t) => match(t.title, t.id, t.target, t.platform, t.path, (t.tags || []).join(' ')))
    .slice(0, 12)
    .map((t) => ({
      ico: 'file-text',
      label: t.title || t.id,
      variant: (t.path || t.id || '').split('/').pop().replace(/\.trail\.yaml$/, ''),
      target: t.target,
      sub: t.id,
      fn: () => { openRun(t); close(); },
    }));

  const sessionItems = (sessions.data || [])
    .filter((s) => match(s.title, s.id, s.target, s.platform))
    .slice(0, 6)
    .map((s) => ({
      ico: 'gallery-vertical-end',
      label: s.title || s.id,
      status: s.status,
      sub: [...new Set([s.target, s.platform, s.device].filter(Boolean)), s.ago].filter(Boolean).join(' · '),
      fn: () => { go('completed', { sel: s.id }); close(); },
    }));

  const flat = [...actions, ...trailItems, ...sessionItems];
  const cur = flat.length ? Math.min(sel, flat.length - 1) : 0;

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setSel((s) => Math.min(s + 1, flat.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); const it = flat[cur]; if (it) it.fn(); }
  };

  const section = (title, items, offset) => items.length > 0 && (
    <React.Fragment key={title}>
      <div className="tb-eyebrow" style={{ padding: offset === 0 ? '8px 12px 5px' : '12px 12px 5px' }}>{title}</div>
      {items.map((it, i) => {
        const gi = offset + i;
        const on = gi === cur;
        const rich = it.sub != null || it.target != null || it.status != null || it.variant != null;
        return (
          <div key={title + i} className={'tb-pal-row' + (on ? ' on' : '')} onClick={it.fn} onMouseEnter={() => setSel(gi)} style={{ cursor: 'pointer', alignItems: rich ? 'flex-start' : 'center' }}>
            <Ico n={it.ico} s={17} c={on ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} style={{ flex: '0 0 auto', marginTop: rich ? 1 : 0 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                <span style={{ fontSize: 14, fontWeight: on ? 600 : 500, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{it.label}</span>
                {it.target && <Chip tone="blue">{it.target}</Chip>}
                {it.status && <StatusChip s={it.status} />}
                {it.variant && <span className="tb-mono" style={{ fontSize: 10.5, color: 'var(--text-subtle)', flex: '0 0 auto' }}>{it.variant}</span>}
              </div>
              {it.sub && <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{it.sub}</div>}
            </div>
            {it.kbd && <span className="tb-kbd" style={{ flex: '0 0 auto' }}>{it.kbd}</span>}
          </div>
        );
      })}
    </React.Fragment>
  );

  return (
    <div className={'tb-overlay' + (closing ? ' closing' : '')} onClick={close}>
      <div className="tb-palette" onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '15px 18px', borderBottom: '1px solid var(--tb-hairline)' }}>
          <Ico n="search" s={18} c="var(--text-subtle)" />
          <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onKeyDown} aria-label="Search commands, trails, and sessions" placeholder="Type a command, trail, or session…" style={{ background: 'transparent', border: 'none', outline: 'none', color: '#fff', fontSize: 15.5, fontFamily: 'var(--font-display)', width: '100%' }} />
          <span className="tb-kbd">esc</span>
        </div>
        <div style={{ padding: 8, maxHeight: 380, overflowY: 'auto' }}>
          {section('Actions', actions, 0)}
          {section('Trails', trailItems, actions.length)}
          {section('Sessions', sessionItems, actions.length + trailItems.length)}
          {flat.length === 0 && <div className="tb-sub" style={{ padding: '16px 12px', fontSize: 13 }}>No matches for “{q}”</div>}
        </div>
      </div>
    </div>
  );
}

// A retired route that forwards to its replacement (params intact), so old links and
// TRAILRUNNER_UI navigate commands keep working after routes merge.
function RedirectScreen({ go, to, params, active }) {
  React.useEffect(() => { if (active) go(to, params || {}); }, [active]);
  return null;
}

// Daemon health watchdog. Every data hook fetches through the daemon; when it dies or wedges,
// those fetches hang or fail silently and the app degrades to skeletons with no explanation
// (an hour of "checking…" with no indication anything was wrong). This pings a cheap endpoint on a short abort
// timeout so unreachability becomes an explicit, recoverable state: the shell shows a banner
// while down, and the down-to-up transition broadcasts `tb:daemon-recovered` so every
// useFetched-backed hook refetches the data that went stale during the outage.
function useDaemonHealth() {
  const [down, setDown] = React.useState(false);
  const failsRef = React.useRef(0);
  const check = React.useCallback(async () => {
    try {
      const res = await fetch('/ping', { cache: 'no-store', signal: AbortSignal.timeout(4000) });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      failsRef.current = 0;
      setDown((was) => {
        if (was) window.dispatchEvent(new Event('tb:daemon-recovered'));
        return false;
      });
    } catch (e) {
      failsRef.current += 1;
      // Two consecutive misses before declaring down - a single slow response during a heavy
      // operation must not flash a scary banner.
      if (failsRef.current >= 2) setDown(true);
    }
  }, []);
  React.useEffect(() => {
    check();
    const id = setInterval(check, 5000);
    return () => clearInterval(id);
  }, [check]);
  return { down, retry: check };
}

function DaemonDownBanner({ retry }) {
  return (
    <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', background: 'rgba(255,90,90,.14)', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
      <Ico n="unplug" s={15} c="var(--tb-danger-text)" />
      <div style={{ flex: 1, minWidth: 0 }}>
        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--tb-danger-text)' }}>Can't reach the Trail Runner daemon.</span>
        <span className="tb-sub" style={{ fontSize: 12, marginLeft: 8 }}>
          Requests are timing out; data on screen may be stale. Reconnecting automatically - if this persists, restart Trail Runner.
        </span>
      </div>
      <Btn sm ico="refresh-cw" onClick={retry}>Retry now</Btn>
    </div>
  );
}

// The one deep link the SPA honors: #companion/<runId>, set by `trailblaze companion start` so
// the window opens straight onto that companion session. Read once at boot - there is no hash
// router; in-app navigation stays go()-driven.
const companionDeepLink = (() => {
  const m = /^#companion\/([^/?#]+)/.exec(window.location.hash || '');
  if (!m) return null;
  // A malformed percent-escape must not throw at module eval - that would abort this whole
  // script and boot a blank app. Run ids are plain `agent-<uuid>`, so the raw match is fine.
  try { return decodeURIComponent(m[1]); } catch (_) { return m[1]; }
})();

function App() {
  const initialRoute = companionDeepLink ? 'companion' : 'home';
  const initialPayload = companionDeepLink ? { for: 'companion', data: { runId: companionDeepLink } } : {};
  const [route, setRoute] = useState(initialRoute);
  const [payload, setPayload] = useState(initialPayload);
  // Browser-style view history: a stack of {route, payload} with a pointer.
  // go() pushes (dropping any forward entries); ⌘[ / ⌘] move the pointer.
  const histRef = React.useRef([{ route: initialRoute, payload: initialPayload }]);
  const histIdxRef = React.useRef(0);
  const [palette, setPalette] = useState(false);
  const [paletteClosing, setPaletteClosing] = useState(false);
  const [run, setRun] = useState(false);
  const [runClosing, setRunClosing] = useState(false);
  const visitedRef = React.useRef(new Set());
  visitedRef.current.add(route);
  // Payloads are addressed to one screen: with every visited screen now staying
  // mounted, an unscoped payload would also fire effects on hidden screens that
  // happen to read the same prop names (Active and Completed both take `sel`).
  const go = (r, p = {}) => {
    const pl = { for: r, data: p };
    setRoute(r); setPayload(pl);
    const h = histRef.current.slice(0, histIdxRef.current + 1);
    h.push({ route: r, payload: pl });
    histRef.current = h;
    histIdxRef.current = h.length - 1;
  };
  const goHistory = (delta) => {
    const next = histIdxRef.current + delta;
    if (next < 0 || next >= histRef.current.length) return;
    histIdxRef.current = next;
    const e = histRef.current[next];
    setRoute(e.route); setPayload(e.payload);
  };
  const pf = (id) => (payload.for === id && payload.data) || {};
  // seed (optional): { deviceId, replay } pre-selects the device + replay mode when a
  // specific recorded variant is launched from the board (vs. the generic "Run a trail…").
  const openRun = (trail = null, seed = null) => setRun({ trail, seed });
  const EXIT_MS = 150;
  const closePalette = () => { setPaletteClosing(true); setTimeout(() => { setPalette(false); setPaletteClosing(false); }, EXIT_MS); };
  const closeRun = () => { setRunClosing(true); setTimeout(() => { setRun(false); setRunClosing(false); }, EXIT_MS); };
  useLucide();
  useThemeController();

  const paletteRef = React.useRef(false); paletteRef.current = palette && !paletteClosing;
  const runRef = React.useRef(false); runRef.current = !!run && !runClosing;
  useEffect(() => {
    const h = e => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); paletteRef.current ? closePalette() : setPalette(true); }
      if (e.key === 'Escape') { if (paletteRef.current) closePalette(); if (runRef.current) closeRun(); }
      // Browser-style back/forward: ⌘[ / ⌘] and ⌘← / ⌘→. Skip when an editor or
      // text field is focused so the arrows still move the caret and ⌘[ outdents.
      if ((e.metaKey || e.ctrlKey) && (e.key === '[' || e.key === ']' || e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        const ae = document.activeElement;
        if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.isContentEditable || (ae.closest && ae.closest('.CodeMirror')))) return;
        e.preventDefault();
        goHistory(e.key === '[' || e.key === 'ArrowLeft' ? -1 : 1);
      }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);

  const health = useDaemonHealth();
  const devices = TB.useDevices();
  const deviceList = devices.data || [];
  const trails = TB.useTrails();
  const [gt, setGt] = TB.useGlobalTarget();
  // Validate the sticky target against the daemon's live target list once per target change.
  // localStorage can outlive a target (e.g. its trailmap was deleted): every screen would then
  // show it as active while device connect / mirror / run all fail with "no target app selected".
  // Clearing it sends the user back through the picker instead of half-working. Targets pending
  // a daemon restart are spared - they're legitimately absent from the live list.
  const trailsRef = React.useRef(null);
  trailsRef.current = trails.data;
  React.useEffect(() => {
    if (!gt || !gt.target) return;
    let stale = false;
    const missingFromLiveList = async () => {
      const r = await Promise.resolve(TB.getTargetApps()).catch(() => null);
      const apps = (r && r.targetApps) || [];
      if (apps.length === 0) return false; // empty/failed fetch proves nothing
      if (apps.some((a) => a.id === gt.target)) return false;
      if (((TB.getTargetsRestartNeeded && TB.getTargetsRestartNeeded()) || []).includes(gt.target)) return false;
      // A target the loaded trails index still references exists in this workspace - a live list
      // that omits it is degraded (partially initialized daemon), not proof the target is gone.
      if ((trailsRef.current || []).some((t) => t.target === gt.target)) return false;
      return true;
    };
    (async () => {
      if (stale || !(await missingFromLiveList())) return;
      // Confirm before clearing: a daemon that just (re)started or is degraded can briefly serve
      // a built-ins-only list that omits a perfectly valid workspace target - one bad snapshot
      // must not nuke the user's selection. Only two agreeing reads, seconds apart, clear it.
      await new Promise((res) => setTimeout(res, 4000));
      if (stale || !(await missingFromLiveList()) || stale) return;
      setGt({ target: null, label: null, deviceIds: gt.deviceIds || [] });
    })();
    return () => { stale = true; };
  }, [gt && gt.target]);
  const sessions = TB.useSessions();
  const externalAgents = TB.useExternalAgents();
  // Create session selection lives here (not in the screen) because the rail lists the
  // sessions: rail rows and the screen must agree on which one is open. 'new' = composer.
  const [studioSel, setStudioSel] = useState(null);
  // The legacy Interact screen (reached via the palette's ad hoc YAML action) broadcasts its
  // in-progress recording session (device connected or steps captured) so the rail can fold it
  // into the Create group; null when idle.
  const [interactSession, setInteractSession] = useState(null);
  useEffect(() => {
    const h = (e) => setInteractSession(e.detail && e.detail.active ? e.detail : null);
    window.addEventListener('tb:interact-session', h);
    return () => window.removeEventListener('tb:interact-session', h);
  }, []);
  // A companion run's rail entry opens its read-only companion view; the Create screen's chat
  // (composer, permissions) is only for runs this daemon spawned and can talk back to.
  const goStudio = (id) => {
    const target = (externalAgents.data && externalAgents.data.runs || []).find((r) => r.id === id);
    if (target && target.companion) { go('companion', { runId: id }); return; }
    setStudioSel(id); go('create');
  };
  // Sidebar "Trails" count = trail bundles, scoped to the active target AND its platform when
  // the selected devices all share one. Mirrors the Trails list's target/platform filter so the
  // badge matches the scoped list (a target with iOS-only and Android-only trails counts only
  // the relevant ones once a single-platform device set is chosen).
  const gtPlatform = React.useMemo(() => {
    const plats = new Set(((gt && gt.deviceIds) || []).map((id) => { const d = deviceList.find((x) => x.id === id); return d && d.platform; }).filter(Boolean));
    return plats.size === 1 ? [...plats][0] : null;
  }, [gt && (gt.deviceIds || []).join(','), devices.data]);
  const bundleCount = React.useMemo(() => {
    let arr = trails.data || [];
    if (gt && gt.target) arr = arr.filter((t) => t.target === gt.target);
    if (gtPlatform) arr = arr.filter((t) => t.platform === gtPlatform);
    return TB.countTrailBundles(arr);
  }, [trails.data, gt && gt.target, gtPlatform]);
  // Runs badges: a live count, plus a glowing dot on Active while a run is in flight (the
  // sessions hook polls every 2.5s while anything is running, so this stays current).
  const sessionList = sessions.data || [];
  const runningCount = sessionList.filter((s) => s.status === 'running').length;
  const completedCount = sessionList.length - runningCount;
  const externalAgentRuns = (externalAgents.data && externalAgents.data.runs) || [];
  // A generation run (non-null demoRunId) is embedded inside its demo run's view, never its own
  // sidebar entry - the demo run is the single door for the whole Record -> Generate flow.
  const createSidebarRuns = externalAgentRuns.filter((r) => !r.demoRunId);
  const runningExternalAgentCount = externalAgentRuns.filter((r) => r.status === 'running').length;
  const navBadges = {
    create: { count: runningExternalAgentCount, glow: runningExternalAgentCount > 0 },
    trails: { count: bundleCount },
    active: { count: runningCount, glow: runningCount > 0 },
    completed: { count: completedCount },
  };
  const [pinnedId, setPinnedId] = useState(null);
  useEffect(() => {
    if (!pinnedId && deviceList.length) setPinnedId(deviceList[0].id);
    if (pinnedId && deviceList.length && !deviceList.find((d) => d.id === pinnedId)) setPinnedId(deviceList[0].id);
  }, [devices.data]);
  const device = deviceList.find((d) => d.id === pinnedId) || deviceList[0] || null;
  const screens = {
    home: <HomeScreen go={go} />,
    prompt: <BlazeScreen pinnedId={pinnedId} go={go} />,
    create: <ExternalAgentsScreen go={go} agents={externalAgents} sel={studioSel} onSel={setStudioSel} initSel={pf('create').sel} />,
    agents: <RedirectScreen go={go} to="create" params={pf('agents')} />,
    'agents-setup': <AgentSetupScreen go={go} />,
    interact: <RecordScreen key="interact" go={go} yamlSeed={pf('interact')} />,
    trails: <TrailsScreen go={go} openRun={openRun} initSel={pf('trails').sel} initMode={pf('trails').mode} />,
    tools: <ToolsScreen initTool={pf('tools').tool} go={go} />,
    trailheads: <ComponentTypeScreen kind="trailheads" initSel={pf('trailheads').sel} />,
    active: <SessionsScreen view="active" initSel={pf('active').sel} followLive={pf('active').followLive} go={go} />,
    completed: <SessionsScreen view="completed" initSel={pf('completed').sel} followLive={pf('completed').followLive} go={go} />,
    runs: <SessionsScreen view="all" initSel={pf('runs').sel} followLive={pf('runs').followLive} go={go} />,
    integrations: <IntegrationsScreen />,
    settings: <SettingsScreen go={go} initTab={pf('settings').tab} />,
    // Deep-link only (#companion/<runId>, opened by `trailblaze companion start`): the read-only
    // window onto a session an external agent CLI drives from the user's own repo. The boot deep
    // link arrives through the initial payload; the screen latches the id itself, so no fallback
    // here (a live one would flip the hidden screen back to the boot id on every navigation).
    companion: <CompanionScreen agents={externalAgents} initRunId={pf('companion').runId} go={go} />,
  };
  // Fall back to Home for any route that no longer maps to a screen, so an upgrade can't strand the
  // main pane blank.
  const activeRoute = screens[route] ? route : 'home';
  visitedRef.current.add(activeRoute);

  return (
    <div className="tb-window">
      {health.down && <DaemonDownBanner retry={health.retry} />}
      <div className="tb-body">
        <NavRail route={activeRoute} go={go} badges={navBadges} openPalette={() => setPalette(true)}
          agentRuns={createSidebarRuns} studioSel={studioSel} goStudio={goStudio}
          companionSel={activeRoute === 'companion' ? pf('companion').runId : null}
          interactSession={interactSession} goInteract={() => go('interact')} />
        <div className="tb-main">
          {/* Visited screens stay mounted and just hide, so every tab keeps its
              state (selection, filters, collapsed folders, scroll) across
              navigation. Screens still mount lazily on first visit. */}
          {Object.keys(screens).filter((id) => visitedRef.current.has(id)).map((id) => (
            <div className="tb-screen" key={id} style={id === activeRoute ? undefined : { display: 'none' }}>
              {/* `active` tells a mounted-but-hidden screen when it's the visible tab,
                  so it can refetch list data that may have gone stale while hidden
                  (e.g. a run that finished in Active and handed off to Completed). */}
              <Boundary key={id}>{React.cloneElement(screens[id], { active: id === activeRoute })}</Boundary>
            </div>
          ))}
        </div>
        {/* App-level right rail, mirroring the left nav rail. Screens that carry a companion
            chat portal into this slot; empty (zero width) on every other screen. */}
        <div className="tb-rail-right" id="tb-agent-rail"></div>
      </div>
      {palette && <Boundary><CommandPalette go={go} openRun={openRun} close={closePalette} closing={paletteClosing} trails={trails.data || []} /></Boundary>}
      {run && <Boundary><RunConfigDialog trail={run.trail} seed={run.seed} pinnedId={pinnedId} go={go} close={closeRun} closing={runClosing} /></Boundary>}
    </div>
  );
}
ReactDOM.createRoot(document.getElementById('root')).render(<RootBoundary><App /></RootBoundary>);

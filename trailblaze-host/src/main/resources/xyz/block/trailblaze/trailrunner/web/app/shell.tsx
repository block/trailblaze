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

const NAV = [
  // Home is reached via the target chip at the top of the rail (no standalone nav item) — it folds
  // in the target/device picker. Blaze = the ways to start a trail.
  // "Interact" (not "Record") so it doesn't collide with the Blaze run recordings.
  { group: 'Create', items: [
    ['create', 'Prompt', 'sparkles'],
    ['interact', 'Interact', 'pointer'],
  ] },
  { group: 'Drafts', items: [['drafts', 'In Progress', 'files']] },
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
function TargetPicker({ go, route }) {
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
  // 0 → none; 1 → the device name; 2+ → both platforms ("iOS · Android") when every selected device
  // resolved to a distinct platform, otherwise just the count so the number is always right.
  const deviceSummary = selCount === 0 ? 'No devices selected'
    : selCount === 1 ? (selectedDevices[0] ? selectedDevices[0].name : '1 device')
    : (selectedDevices.length === selCount && selPlatforms.length === selCount ? selPlatforms.map(platLabel).join(' · ') : `${selCount} devices`);
  // The target picker now lives on Home (the standalone Targets screen was folded in), so
  // tapping this jumps Home and highlights while you're there.
  const on = route === 'home';
  return (
    <div style={{ padding: '0 0 10px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 11, width: '100%', padding: '8px 9px', borderRadius: 8,
        background: on ? 'var(--bg-standard)' : 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', color: 'var(--text-standard)' }}>
        <div role="button" tabIndex={0} onClick={() => go('home')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('home'); } }}
          title="Open Home (choose the target app and its devices)" style={{ display: 'flex', alignItems: 'center', gap: 11, flex: 1, minWidth: 0, cursor: 'pointer' }}>
          {/* Size the icon to the two-line text block so its top/bottom line up with the words. */}
          <AppIcon target={gt && gt.target} size={30} radius={7} fallbackColor={gt ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />
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
  return (
    <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 7, flex: '0 0 auto' }}>
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
function WorkspaceChip() {
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
    <div style={{ padding: '0 0 10px' }}>
      <button ref={btnRef} onClick={() => (open ? setOpen(false) : openPop())} aria-expanded={open}
        title={'Workspace: ' + dir + '\n' + TB.WORKSPACE_BLURB + (branch ? '\nBranch: ' + branch + (isWorktree ? ' (git worktree)' : '') : '') + (restart ? '\n\nRestart needed to load this workspace’s app targets.' : '')}
        style={{ display: 'flex', alignItems: 'center', gap: 9, width: '100%', padding: '7px 9px', borderRadius: 8,
          background: open ? 'var(--bg-standard)' : 'transparent', border: '1px solid var(--tb-hairline)', cursor: 'pointer', color: 'var(--text-standard)' }}>
        <Ico n="folder-git-2" s={15} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
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
        {restart && <span aria-label="Restart needed" title="Restart needed to load this workspace’s app targets" style={{ flex: '0 0 auto', width: 7, height: 7, borderRadius: '50%', background: 'var(--tb-amber)' }} />}
        <Ico n="chevron-down" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
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

function NavRail({ route, go, badges = {}, openPalette }) {
  return (
    <div className="tb-rail">
      <div className="tb-brand"><span className="name">Trail Runner</span></div>
      <TargetPicker go={go} route={route} />
      <WorkspaceChip />
      {/* Divider separating the workspace/target context from the Blaze→Runs authoring nav. */}
      <div style={{ height: 1, background: 'var(--tb-hairline)', margin: '0 8px 10px' }} />
      <div className="tb-rail-scroll">
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
    ['sparkles', 'Blaze from a prompt', '⌘B', () => { go('create'); close(); }],
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

function App() {
  const [route, setRoute] = useState('home');
  const [payload, setPayload] = useState({});
  // Browser-style view history: a stack of {route, payload} with a pointer.
  // go() pushes (dropping any forward entries); ⌘[ / ⌘] move the pointer.
  const histRef = React.useRef([{ route: 'home', payload: {} }]);
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

  const devices = TB.useDevices();
  const deviceList = devices.data || [];
  const trails = TB.useTrails();
  const [gt] = TB.useGlobalTarget();
  const sessions = TB.useSessions();
  const drafts = TB.useDrafts();
  // In-progress drafts = those still living under drafts/ (not yet promoted to the library).
  const draftCount = (drafts.data || []).filter((d) => d.inDrafts).length;
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
  // sessions hook polls every 2.5s while anything is running, so this stays current). Exclude
  // draft-recording sessions (trailId like `<n>/drafts/...`) so the badges match what the Active
  // and Completed screens actually list (both filter those out); otherwise drafts inflate the count.
  const sessionList = (sessions.data || []).filter((s) => !/^\d+\/drafts\//.test(String(s.trailId || '')));
  const runningCount = sessionList.filter((s) => s.status === 'running').length;
  const completedCount = sessionList.length - runningCount;
  const navBadges = {
    drafts: { count: draftCount },
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
    home: <HomeScreen go={go} openRun={openRun} />,
    prompt: <BlazeScreen pinnedId={pinnedId} go={go} />,
    create: <CreateScreen go={go} />,
    interact: <RecordScreen go={go} yamlSeed={pf('interact')} />,
    drafts: <DraftsScreen go={go} initSel={pf('drafts').sel} />,
    trails: <TrailsScreen go={go} openRun={openRun} initSel={pf('trails').sel} initMode={pf('trails').mode} />,
    tools: <ToolsScreen initTool={pf('tools').tool} go={go} />,
    trailheads: <ComponentTypeScreen kind="trailheads" initSel={pf('trailheads').sel} />,
    active: <SessionsScreen view="active" initSel={pf('active').sel} followLive={pf('active').followLive} go={go} />,
    completed: <SessionsScreen view="completed" initSel={pf('completed').sel} followLive={pf('completed').followLive} go={go} />,
    runs: <SessionsScreen view="all" initSel={pf('runs').sel} followLive={pf('runs').followLive} go={go} />,
    integrations: <IntegrationsScreen />,
    settings: <SettingsScreen go={go} initTab={pf('settings').tab} />,
  };
  // Fall back to Home for any route that no longer maps to a screen, so an upgrade can't strand the
  // main pane blank.
  const activeRoute = screens[route] ? route : 'home';
  visitedRef.current.add(activeRoute);

  return (
    <div className="tb-window">
      <div className="tb-body">
        <NavRail route={activeRoute} go={go} badges={navBadges} openPalette={() => setPalette(true)} />
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
        {/* App-level right rail, mirroring the left nav rail. The draft editor's pair-with-agent
            chat portals into this slot (so it keeps the draft's context); empty (zero width) on
            every other screen. */}
        <div className="tb-rail-right" id="tb-agent-rail"></div>
      </div>
      {palette && <CommandPalette go={go} openRun={openRun} close={closePalette} closing={paletteClosing} trails={trails.data || []} />}
      {run && <RunConfigDialog trail={run.trail} seed={run.seed} pinnedId={pinnedId} go={go} close={closeRun} closing={runClosing} />}
    </div>
  );
}
ReactDOM.createRoot(document.getElementById('root')).render(<App />);

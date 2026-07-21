// @ts-nocheck
// Companion mode: a session driven by an agent CLI OUTSIDE Trail Runner - the user's own Claude
// Code / Codex session working in their repo. That agent is the single writer: it authors the
// trail folder directly on disk and narrates through the companion API. This screen is a window
// onto both - no composer, no editor - plus the agent's DIRECTIVES: guidance cards, quick-reply
// chips, and an armed recording it can steer from its CLI. The human acts only through those
// directed affordances (and the sanctioned recording save); every reply streams back to the agent
// as a user-action event. Reached only by deep link (#companion/<runId>, opened by `trailblaze
// companion start`); it has no nav-rail entry.
//
// Layout: the agent's voice on the left (guidance + narration + replies), the
// demonstration stage in the center (the shared AgentRecordStage mirror, live sessions only;
// taps drive the device, step capture stays in the armed-recording flow), and the read-only
// trail folder on the right. With no live session the transcript and folder share the width.

function companionEventIcon(kind) {
  if (kind === 'error') return { n: 'triangle-alert', c: 'var(--tb-danger-text)' };
  if (kind === 'lifecycle') return { n: 'activity', c: 'var(--text-subtle-variant)' };
  return { n: 'bot', c: 'var(--tb-ai)' };
}

// What the window should show right now, read from the run DTO's standing-directive state. The
// daemon owns that state (latest directive per name, retracted on empty payloads and on the
// answering user actions), so it survives event retention and a window reload without replaying
// the transcript. Every payload field is coerced defensively: a malformed value renders as
// absent, never as a crash - the poster was already type-checked, this guards skew.
function deriveCompanionDirectives(companion) {
  const src = (companion && companion.directives) || {};
  const str = (v) => (typeof v === 'string' && v.trim() ? v.trim() : null);
  const entry = (name) => {
    const e = src[name];
    if (!e || typeof e !== 'object') return null;
    let p = {};
    if (typeof e.payload === 'string' && e.payload) {
      try {
        const parsed = JSON.parse(e.payload);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) p = parsed;
      } catch (_) { /* an unparseable payload directs nothing */ }
    }
    return { seq: typeof e.seq === 'number' ? e.seq : null, p };
  };
  const items = (p) => (Array.isArray(p.items) ? p.items.filter((x) => typeof x === 'string' && x.trim()) : []);
  const st = { banner: null, checklist: null, actions: null, armed: null, selectDevice: null, selectAppTarget: null };
  const banner = entry('banner');
  if (banner && str(banner.p.text)) st.banner = { text: str(banner.p.text) };
  const checklist = entry('checklist');
  if (checklist) {
    const its = items(checklist.p);
    if (its.length) st.checklist = { title: str(checklist.p.title), items: its };
  }
  const actions = entry('actions');
  if (actions) {
    const its = items(actions.p);
    if (its.length) st.actions = { seq: actions.seq, items: its };
  }
  const armed = entry('arm-recording');
  if (armed) st.armed = { variant: str(armed.p.variant), platform: str(armed.p.platform), note: str(armed.p.text) };
  const dev = entry('select-device');
  if (dev) st.selectDevice = { platform: str(dev.p.platform) };
  const app = entry('select-app-target');
  if (app) st.selectAppTarget = { app: str(app.p.app), label: str(app.p.label) };
  return st;
}

// A checklist item may carry its own done marker: "[x] Scaffold the trail".
function parseChecklistItem(s) {
  const m = /^\[([ xX])\]\s+(.*)$/.exec(s);
  return m ? { done: m[1].toLowerCase() === 'x', label: m[2] } : { done: false, label: s };
}

function CompanionCard({ ico, tone, title, children }) {
  const color = tone === 'accent' ? 'var(--tb-ai)' : 'var(--text-subtle-variant)';
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', padding: '10px 12px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: title ? 6 : 0 }}>
        <Ico n={ico} s={13} c={color} />
        {title && <span style={{ fontSize: 12, fontWeight: 700 }}>{title}</span>}
      </div>
      {children}
    </div>
  );
}

// The agent's standing guidance, reduced from its directives: a banner, a checklist, a device or
// app-target prompt, an armed recording. Rendered above the narration so the "what should I do
// now" answer never scrolls away with the chatter.
function CompanionGuidance({ directed, running, folder, runId, active, go }) {
  const devices = TB.useDevices();
  // While the connect-a-device ask is up, keep the list fresh: the human is out booting a
  // simulator, and the card must notice it without a manual reload.
  const devicesReloadRef = React.useRef(devices.reload);
  devicesReloadRef.current = devices.reload;
  const wantDevicePoll = !!directed.selectDevice && !!active && !!running;
  React.useEffect(() => {
    if (!wantDevicePoll) return undefined;
    const t = setInterval(() => devicesReloadRef.current(), 2000);
    return () => clearInterval(t);
  }, [wantDevicePoll]);
  const wanted = (directed.selectDevice && directed.selectDevice.platform || '').toLowerCase();
  // Only devices of the asked-for platform count as "connected" here: the daemon won't retract a
  // platform-specific ask for a mismatched device, so neither may this card claim it satisfied.
  const connected = (devices.data || []).filter((d) => d.connected && (!wanted || (d.platform || '').toLowerCase() === wanted));
  const hasAny = directed.banner || directed.checklist || directed.selectDevice || directed.selectAppTarget || directed.armed;
  // Standing directives outlive the session on the DTO, but an ended session has no agent to
  // answer its asks - rendering them would direct the human at nobody.
  if (!hasAny || !running) return null;
  return (
    <div style={{ flex: '0 0 auto', maxHeight: '45%', overflowY: 'auto', padding: '12px 16px 4px', display: 'flex', flexDirection: 'column', gap: 8 }}>
      {directed.banner && (
        <CompanionCard ico="megaphone" tone="accent">
          <div data-selectable style={{ fontSize: 12.5, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{directed.banner.text}</div>
        </CompanionCard>
      )}
      {directed.checklist && (
        <CompanionCard ico="list-checks" title={directed.checklist.title || 'Plan'}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {directed.checklist.items.map((raw, i) => {
              const it = parseChecklistItem(raw);
              return (
                <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <Ico n={it.done ? 'circle-check' : 'circle'} s={13} c={it.done ? 'var(--tb-pass)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto', marginTop: 2 }} />
                  <span data-selectable style={{ fontSize: 12.5, lineHeight: 1.5, color: it.done ? 'var(--text-subtle-variant)' : 'var(--text-standard)', textDecoration: it.done ? 'line-through' : 'none' }}>{it.label}</span>
                </div>
              );
            })}
          </div>
        </CompanionCard>
      )}
      {directed.selectDevice && (
        <CompanionCard ico="smartphone" title={`Connect a device${wanted ? ` (${wanted})` : ''}`}>
          <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5 }}>
            {connected.length === 0
              ? 'No device is connected yet - boot a simulator/emulator, then start the recording below when the agent arms it.'
              : <>Connected: {connected.map((d) => d.name || d.id).join(', ')}. The recording surface connects when you start.</>}
          </div>
        </CompanionCard>
      )}
      {directed.selectAppTarget && (
        <CompanionCard ico="crosshair" title="Pick the app target">
          <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, marginBottom: 6 }}>
            The agent wants {directed.selectAppTarget.label || directed.selectAppTarget.app || 'a specific app target'} selected.
          </div>
          <Btn sm ico="crosshair" onClick={() => go && go('home')}>Open the target picker</Btn>
        </CompanionCard>
      )}
      {directed.armed && (
        <CompanionCard ico="video" tone="accent" title="Recording armed">
          <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, marginBottom: 6 }}>
            {directed.armed.note || 'The agent wants you to demonstrate the flow on a device.'}{' '}
            Saves <span className="tb-mono">{TB.variantSlug(directed.armed.variant || 'recording')}.trail.yaml</span> into <span className="tb-mono">{folder || 'the trail folder'}</span>{directed.armed.platform ? <> ({directed.armed.platform} device)</> : null}.
          </div>
          <Btn sm kind="primary" ico="video" disabled={!running || !folder}
            title={!folder ? 'This session declared no trail folder' : undefined}
            onClick={() => go && go('interact', { companion: { runId, folder, variant: directed.armed.variant || null, platform: directed.armed.platform || null } })}>
            Start recording
          </Btn>
        </CompanionCard>
      )}
    </div>
  );
}

// The shared-brain ask: hand "review my trail" to the attached agent instead of the daemon's own
// LLM. The daemon queues it on this session (an agent-request event plus a pending entry in
// companion.requests); completion is the entry settling - the agent edits the trail files
// directly, so the result shows up in the folder rail, not as suggestion cards here.
function CompanionReviewAsk({ running, requests, sessionId }) {
  const [ask, setAsk] = React.useState(null); // null | { phase: sending|pending|done|error, requestId?, msg? }
  const [slow, setSlow] = React.useState(false);
  // The pending spinner resolves off the daemon's requests map - the one source of truth that
  // survives a window reload (a purely local flag would strand the spinner forever).
  const entry = (ask && ask.phase === 'pending' && requests && requests[ask.requestId]) || null;
  const entryStatus = entry ? entry.status : null;
  // A reload wipes the local ask while the daemon's pending entry stands: re-adopt it, or the
  // button re-arms mid-review and the settle note is lost. Only fills an empty ask - a settled
  // local phase (done/error) must not be hijacked by another window's fresh review.
  React.useEffect(() => {
    if (ask) return;
    const pending = Object.values(requests || {}).find((r) => r.status === 'pending' && r.kind === 'review-trail');
    if (pending) setAsk({ phase: 'pending', requestId: pending.requestId });
  }, [requests, ask]);
  React.useEffect(() => {
    if (!entryStatus || entryStatus === 'pending') return;
    if (entryStatus === 'done') setAsk({ phase: 'done', msg: (entry && entry.note) || null });
    else if (entryStatus === 'cancelled') setAsk({ phase: 'error', msg: 'the session ended before your agent answered' });
    else setAsk({ phase: 'error', msg: (entry && entry.note) || 'your agent reported an error' });
  }, [entryStatus]);
  // Long-pending hint only - a real review can legitimately take minutes, so never auto-cancel.
  const pendingId = ask && ask.phase === 'pending' ? ask.requestId : null;
  React.useEffect(() => {
    setSlow(false);
    if (!pendingId) return undefined;
    const t = setTimeout(() => setSlow(true), 120000);
    return () => clearTimeout(t);
  }, [pendingId]);
  const busy = !!ask && (ask.phase === 'sending' || ask.phase === 'pending');
  const send = async () => {
    if (!sessionId || busy) return;
    setAsk({ phase: 'sending' });
    const r = await TB.reviewSession(sessionId);
    if (r && r.deferred && r.requestId) setAsk({ phase: 'pending', requestId: r.requestId });
    else if (r && r.degraded) setAsk({ phase: 'error', msg: r.error || 'agent not listening - ask it in your CLI' });
    else if (r && !r.error && Array.isArray(r.suggestions)) {
      // No companion took the ask and the daemon's own reviewer answered instead - rare on this
      // screen, but don't render a wall of suggestion cards a companion window has no home for
      // (and an empty array is a clean bill of health, not a failure).
      setAsk({
        phase: 'done',
        msg: r.suggestions.length
          ? `the daemon's own reviewer returned ${r.suggestions.length} suggestion${r.suggestions.length === 1 ? '' : 's'}`
          : "the daemon's own reviewer found no issues",
      });
    } else setAsk({ phase: 'error', msg: (r && r.error) || 'the review could not be started' });
  };
  if (!running) return null;
  return (
    <div style={{ flex: '0 0 auto', padding: '8px 16px', display: 'flex', flexDirection: 'column', gap: 6, borderBottom: '1px solid var(--tb-hairline)' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <Btn sm ico={busy ? 'loader-2' : 'scan-search'} spin={busy} disabled={!sessionId || busy}
          title={!sessionId ? 'Run the trail first - the review reads the last run\'s recorded session' : undefined}
          onClick={send}>
          Review my trail
        </Btn>
        {!sessionId && <span className="tb-sub" style={{ fontSize: 11 }}>Available after a trail run finishes.</span>}
      </div>
      {ask && ask.phase === 'sending' && <span className="tb-sub" style={{ fontSize: 11 }}>Sending to your agent…</span>}
      {ask && ask.phase === 'pending' && (
        <span className="tb-sub" style={{ fontSize: 11, display: 'flex', alignItems: 'center', gap: 6 }}>
          <Ico n="loader-2" s={11} c="var(--text-subtle)" spin />
          Sent to your agent - it's reviewing in your CLI{slow ? ' (still working - check your CLI if this seems stuck)' : ''}…
        </span>
      )}
      {ask && ask.phase === 'done' && (
        <span className="tb-sub" style={{ fontSize: 11, color: 'var(--tb-pass)' }}>
          Review finished{ask.msg ? `: ${ask.msg}` : ' - any edits show in the trail folder.'}
        </span>
      )}
      {ask && ask.phase === 'error' && (
        <span role="alert" className="tb-sub" style={{ fontSize: 11, color: 'var(--tb-danger-text)' }}>{ask.msg}</span>
      )}
    </div>
  );
}

function CompanionNarration({ events, loading, running, agentLabel }) {
  const shownEvents = (events || []).filter((e) => e.kind === 'assistant_message' || e.kind === 'lifecycle' || e.kind === 'error');
  // Newest narration stays in view unless the reader scrolled up to re-read something.
  const scrollRef = React.useRef(null);
  const stickRef = React.useRef(true);
  React.useEffect(() => {
    const el = scrollRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  });
  const onScroll = (e) => {
    const el = e.currentTarget;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
  };
  return (
    <div ref={scrollRef} onScroll={onScroll} style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      {shownEvents.length === 0 ? (
        <EmptyState ico={running ? 'loader-2' : 'bot'}
          title={running ? 'Waiting for the agent…' : (loading ? 'Loading…' : 'No narration')}
          sub={`${agentLabel} narrates its progress here as it authors the trail.`} />
      ) : shownEvents.map((e) => {
        const ico = companionEventIcon(e.kind);
        return (
          <div key={e.id} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <Ico n={ico.n} s={14} c={ico.c} style={{ flex: '0 0 auto', marginTop: 3 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              {e.title && <div style={{ fontSize: 12.5, fontWeight: 700 }}>{e.title}</div>}
              {e.text && <div data-selectable style={{ fontSize: 12.5, lineHeight: 1.55, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: e.kind === 'error' ? 'var(--tb-danger-text)' : 'var(--text-standard)' }}>{e.text}</div>}
              <div className="tb-sub" style={{ fontSize: 10.5, marginTop: 2 }}>{new Date(e.timeMs).toLocaleTimeString()}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// Quick-reply chips (the agent's `actions` directive) + the standing handback control. Every
// click becomes a user-action event on the run; the agent hears it on its `companion listen`
// stream and answers by narrating and re-directing - the chips stay until it replaces them.
function CompanionReplies({ runId, actions, running }) {
  const [busy, setBusy] = React.useState(null); // the label in flight
  const [sent, setSent] = React.useState(null); // { label, err } - last outcome, shown briefly
  const [note, setNote] = React.useState('');
  const sentTimer = React.useRef(null);
  React.useEffect(() => () => { if (sentTimer.current) clearTimeout(sentTimer.current); }, []);
  const flash = (label, err) => {
    setSent({ label, err: err || null });
    if (sentTimer.current) clearTimeout(sentTimer.current);
    sentTimer.current = setTimeout(() => setSent(null), err ? 6000 : 2500);
  };
  const post = async (type, payload, label) => {
    if (busy) return;
    setBusy(label);
    const r = await TB.companionUserAction(runId, type, payload);
    setBusy(null);
    flash(label, r.ok ? null : (r.error || 'could not reach the agent'));
    if (r.ok && type === 'handback') setNote('');
  };
  // The chip's reply carries the seq of the `actions` directive that offered it, so the agent can
  // tell which round of choices was answered when it re-sends chips mid-flight.
  const reply = (label) => {
    const payload = { actionId: label };
    if (actions && typeof actions.seq === 'number') payload.directiveSeq = actions.seq;
    post('user-action', payload, label);
  };
  const chipLabels = (actions && actions.items) || [];
  return (
    <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '10px 16px 12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
      {chipLabels.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, maxHeight: 110, overflowY: 'auto' }}>
          {chipLabels.map((label, i) => (
            <button key={`${i}:${label}`} disabled={!running || !!busy}
              onClick={() => reply(label)}
              style={{ border: '1px solid var(--tb-hairline)', borderRadius: 999, background: 'var(--bg-standard)', color: 'var(--text-standard)', fontSize: 12, padding: '5px 12px', cursor: running ? 'pointer' : 'default', opacity: running ? 1 : 0.5, display: 'flex', alignItems: 'center', gap: 6 }}>
              {busy === label && <Ico n="loader-2" s={11} c="var(--text-subtle)" spin />}
              {label}
            </button>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <input value={note} onChange={(e) => setNote(e.target.value)} disabled={!running}
          placeholder="Optional note for the agent…"
          onKeyDown={(e) => { if (e.key === 'Enter' && running && !busy) post('handback', { note: note.trim() || null }, 'handback'); }}
          style={{ flex: 1, minWidth: 0, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '6px 10px', color: 'var(--text-standard)', fontSize: 12, outline: 'none' }} />
        <Btn sm ico={busy === 'handback' ? 'loader-2' : 'corner-down-left'} spin={busy === 'handback'} disabled={!running || !!busy}
          title="Tell the agent you're done here and it should take over"
          onClick={() => post('handback', { note: note.trim() || null }, 'handback')}>
          Hand back to agent
        </Btn>
      </div>
      {sent && (
        <div role={sent.err ? 'alert' : undefined} className="tb-sub" style={{ fontSize: 11, color: sent.err ? 'var(--tb-danger-text)' : 'var(--text-subtle-variant)' }}>
          {sent.err ? `Could not send "${sent.label}": ${sent.err}` : `Sent to your agent ✓`}
        </div>
      )}
    </div>
  );
}

// One folder file parsed into StepRow-shaped steps: the legacy list format first, then the
// unified single-file format lowered to the same shape (platform recordings flattened - this is
// a read-only glance, the detail page owns the per-platform matrix). Null when it's not a trail.
function companionFileSteps(content) {
  const legacy = parseTrailSteps(content || '');
  if (legacy) return legacy;
  const uni = parseUnifiedTrail(content || '');
  if (!uni) return null;
  return uni.rows.map((row) => {
    const tools = [];
    Object.keys(row.byPlatform || {}).forEach((p) => {
      (row.byPlatform[p] || []).forEach((t) => { const name = Object.keys(t)[0]; tools.push({ name, args: t[name] }); });
    });
    return { kind: row.kind, text: row.text, recorded: row.recorded > 0, tools };
  });
}

// The parsed-steps view of the folder (the TRAIL tab): every YAML that parses as a trail, as the
// same StepRow cards the trail detail pages use. Step numbering restarts per file (each file is
// its own variant).
function CompanionTrailSteps({ files, running }) {
  const parsed = files
    .filter((f) => /\.ya?ml$/i.test(f.name))
    .map((f) => ({ name: f.name, steps: companionFileSteps(f.content) }))
    .filter((x) => x.steps && x.steps.length);
  if (!parsed.length) {
    return <EmptyState ico={running ? 'loader-2' : 'list-checks'}
      title={running ? 'Waiting for the first trail…' : 'No trail steps'}
      sub="Steps appear here as the agent writes trail YAML into the folder." />;
  }
  return parsed.map(({ name, steps }) => {
    let n = 0;
    return (
      <div key={name}>
        <div className="tb-mono" style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-subtle-variant)', margin: '0 0 8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}</div>
        {steps.map((s, i) => <StepRow key={i} step={s} idx={s.kind === 'trailhead' ? null : n++} />)}
      </div>
    );
  });
}

// The DEMO FILES tab: the declared folder's full recursive listing, rendered as an always-expanded
// tree. Names and sizes come from the folder-tree route; contents stay behind the per-file
// affordance (right-click opens the file in the editor; the button reveals the folder in Finder).
function CompanionDemoFiles({ runId, running }) {
  const [entries, setEntries] = React.useState(null); // null until the first listing lands
  const [err, setErr] = React.useState(null);
  React.useEffect(() => {
    if (!runId) return undefined;
    let alive = true;
    const load = async () => {
      const r = await TB.companionFolderTree(runId);
      if (!alive) return;
      // Keep the last good listing across a hiccuped poll, same policy as the file contents.
      if (r && r.ok && Array.isArray(r.entries)) setEntries(r.entries);
      else if (r && r.ok === false) setEntries((prev) => prev || []);
    };
    load();
    if (!running) return () => { alive = false; };
    const t = setInterval(load, 2000);
    return () => { alive = false; clearInterval(t); };
  }, [runId, running]);
  const openFile = async (path) => {
    setErr(null);
    const r = await TB.companionOpenFile(runId, path);
    if (!r || r.ok === false) setErr((r && r.error) || 'could not open the file');
  };
  // Flat `a/b/c` paths -> nested nodes; the listing includes directory entries, so empty dirs show.
  const root = React.useMemo(() => {
    const mk = () => ({ dirs: new Map(), files: [] });
    const tree = mk();
    for (const e of entries || []) {
      const parts = e.path.split('/');
      let node = tree;
      for (let i = 0; i < parts.length - 1; i++) {
        if (!node.dirs.has(parts[i])) node.dirs.set(parts[i], mk());
        node = node.dirs.get(parts[i]);
      }
      const leaf = parts[parts.length - 1];
      if (e.dir) { if (!node.dirs.has(leaf)) node.dirs.set(leaf, mk()); }
      else node.files.push({ name: leaf, path: e.path, size: e.size });
    }
    return tree;
  }, [entries]);
  const renderNode = (node, depth) => [
    ...[...node.dirs.keys()].sort().map((name) => (
      <React.Fragment key={'d:' + depth + ':' + name}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '4px 6px', paddingLeft: 6 + depth * 16 }}>
          <Ico n="folder" s={13} c="var(--text-subtle)" />
          <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-subtle-variant)' }}>{name}</span>
        </div>
        {renderNode(node.dirs.get(name), depth + 1)}
      </React.Fragment>
    )),
    ...node.files.slice().sort((a, b) => a.name.localeCompare(b.name)).map((f) => (
      <div key={'f:' + f.path} title="Right-click to open in your editor"
        onContextMenu={(e) => { e.preventDefault(); openFile(f.path); }}
        style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '4px 6px', paddingLeft: 6 + depth * 16, borderRadius: 6, cursor: 'context-menu' }}>
        <Ico n={artifactIcon(f.name)} s={13} c="var(--text-subtle)" />
        <span className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</span>
        <span className="tb-sub" style={{ fontSize: 10.5, flexShrink: 0 }}>{fmtBytes(f.size)}</span>
      </div>
    )),
  ];
  return (
    <React.Fragment>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
        <Btn sm ico="folder-open" onClick={() => TB.companionRevealFolder(runId)}>Open in Finder</Btn>
        <span className="tb-sub" style={{ fontSize: 11 }}>Right-click a file to open it in your editor</span>
      </div>
      {err && <div role="alert" style={{ fontSize: 11.5, color: 'var(--tb-danger-text)' }}>{err}</div>}
      {entries === null ? (
        <EmptyState ico="loader-2" title="Loading…" sub="Listing the trail folder." />
      ) : entries.length === 0 ? (
        <EmptyState ico="folder-tree" title="No files yet" sub="Everything the agent saves into the folder shows up here." />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column' }}>{renderNode(root, 0)}</div>
      )}
    </React.Fragment>
  );
}

// The trail folder the external agent declared, polled while the session runs so its on-disk
// writes stream into view. Read-only by design; three views of the same folder - parsed step
// cards (styled like the detail pages), the raw file contents, and the full file tree.
function CompanionFolderRail({ runId, running, folder, active, wide }) {
  const [files, setFiles] = React.useState([]); // [{ name, content }]
  const [fetched, setFetched] = React.useState(false);
  const [tab, setTab] = React.useState('trail');
  React.useEffect(() => {
    // Hidden screens don't poll (the daemon reads every file in the folder per tick); the rail
    // keeps its last listing and refetches the moment the tab is visible again. Same pause on
    // the Demo files tab: it runs its own tree poll and never reads file contents.
    if (!runId || !active || tab === 'files') return undefined;
    let alive = true;
    const load = async () => {
      const r = await TB.companionFolderContent(runId);
      if (!alive) return;
      // A hiccuped poll (daemon GC pause, restart 404) must not blank a view that had files -
      // keep the last good listing and let the next tick catch up.
      if (r && r.ok !== false && Array.isArray(r.files)) setFiles(r.files);
      setFetched(true);
    };
    load();
    if (!running) return () => { alive = false; };
    const t = setInterval(load, 1500);
    return () => { alive = false; clearInterval(t); };
  }, [runId, running, active, tab]);
  // `wide` only without the demonstration stage (session ended): with the stage up, the device
  // is the subject and the rail yields the leftover width to it (the design's 1fr center).
  return (
    <div style={{ flex: wide ? '1 1 520px' : '0 1 400px', minWidth: 320, minHeight: 0, overflow: 'hidden', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', borderLeft: wide ? 'none' : '1px solid var(--tb-hairline)' }}>
      <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px 10px' }}>
        <Ico n="file-code" s={14} c="var(--tb-ai)" />
        <span style={{ fontSize: 13, fontWeight: 700 }}>Trail folder</span>
        {folder && <span className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, fontSize: 10.5, color: 'var(--text-subtle-variant)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{folder}</span>}
        <Chip>read-only</Chip>
      </div>
      <div className="tb-tabs" style={{ flex: '0 0 auto', padding: '0 14px' }}>
        {[['trail', 'Trail', 'list-checks'], ['yaml', 'Raw YAML', 'file-code'], ['files', 'Demo files', 'folder-tree']].map(([id, label, ico]) => (
          <div key={id} className={'tb-tab ' + (tab === id ? 'active' : '')} onClick={() => setTab(id)} style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer' }}>
            <Ico n={ico} s={15} />{label}
          </div>
        ))}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {tab === 'trail' && <CompanionTrailSteps files={files} running={running} />}
        {tab === 'yaml' && (files.length === 0 ? (
          <EmptyState ico={running ? 'loader-2' : 'file-code'}
            title={running ? 'Waiting for the first file…' : (fetched ? 'No files' : 'Loading…')}
            sub="The agent writes the trail files in your repo; they appear here as it saves." />
        ) : files.map((f) => (
          <div key={f.name} style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', overflow: 'hidden' }}>
            <div className="tb-mono" style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-subtle-variant)', padding: '7px 11px', borderBottom: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</div>
            <pre className="tb-mono" data-selectable style={{ margin: 0, padding: '10px 12px', fontSize: 11.5, lineHeight: 1.55, color: 'var(--text-standard)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{f.content || ''}</pre>
          </div>
        )))}
        {tab === 'files' && active && <CompanionDemoFiles runId={runId} running={running} />}
      </div>
    </div>
  );
}

function CompanionScreen({ agents, initRunId, go, active = true }) {
  // The shell's payload is scoped to the visible route, so while another tab is up `initRunId`
  // is null - a live prop would flip this mounted-but-hidden screen's identity. Latch the id and
  // adopt a new one only when the shell hands us a real one (rail click on another companion run).
  const [runId, setRunId] = React.useState(initRunId || null);
  React.useEffect(() => { if (initRunId) setRunId(initRunId); }, [initRunId]);
  const runs = (agents.data && agents.data.runs) || [];
  const run = runs.find((r) => r.id === runId) || null;
  // The deep-linked window can open before the daemon settles: the launcher may have just booted
  // it, and a failed first fetch surfaces as an EMPTY run list (fetchExternalAgents masks errors),
  // which useFetched never retries on its own. While the run is missing, re-ask on a short clock -
  // capped, so a genuinely unknown id still reaches the empty state below and the reloads can't
  // starve a consistently-slow fetch forever (each one cancels the previous in-flight apply).
  const [attempts, setAttempts] = React.useState(0);
  const waiting = !!runId && !run && attempts < 8;
  const reloadRef = React.useRef(agents.reload);
  reloadRef.current = agents.reload;
  // The cap counts CONSECUTIVE misses: any sighting of the run (or losing it again) refills the
  // budget, so a mid-session daemon blip - which fetchExternalAgents masks as an empty run list -
  // retries afresh instead of stranding the screen on the empty state with every clock stopped.
  React.useEffect(() => { setAttempts(0); }, [runId, !!run]);
  // While a blip has the run missing from the list, keep rendering its last known state: dropping
  // to the "Connecting…" placeholder would unmount the narration (closing the SSE stream and the
  // scroll position) for what is usually a single bad poll. Cleared when the id changes so a
  // session switch can never wear the previous session's state.
  const lastRunRef = React.useRef(null);
  if (lastRunRef.current && lastRunRef.current.id !== runId) lastRunRef.current = null;
  if (run) lastRunRef.current = run;
  const shown = run || (waiting ? lastRunRef.current : null);
  React.useEffect(() => {
    if (!waiting || !active) return undefined;
    const t = setInterval(() => { setAttempts((n) => n + 1); reloadRef.current(); }, 1500);
    return () => clearInterval(t);
  }, [waiting, active]);
  // Back on this tab: the shared run list may have gone stale while hidden (the hook only polls
  // while something is running) - same refresh-on-return as the Create screen.
  React.useEffect(() => {
    if (!active) return;
    reloadRef.current();
  }, [active]);
  const running = !!shown && shown.status === 'running';
  // The demonstration stage (center column): the same live mirror the Create
  // screen owns, held only while this tab is visible AND the session is live - a hidden tab or an
  // ended session must not keep a device connection open. Taps drive the device (runId stays
  // null, so nothing records); actual step capture still goes through the armed-recording flow.
  const mirror = useAgentDeviceMirror(active && running);
  // The event subscription feeds the narration; guidance cards come from the DTO's standing
  // directives instead (deriveCompanionDirectives). navigate is the one directive applied LIVE
  // here (the hook's onLiveEvent contract): it never becomes standing state, so a reload can
  // repaint guidance without yanking the human across the app.
  const goRef = React.useRef(go);
  goRef.current = go;
  const onLive = React.useCallback((e) => {
    if (!e || e.kind !== 'ui_command' || e.title !== 'navigate') return;
    let p = {};
    try { p = e.input ? JSON.parse(e.input) : {}; } catch (_) { return; }
    if (p.route) TB.applyTrailRunnerUiCommand({ action: 'navigate', route: p.route, params: p.params || {} }, goRef.current);
  }, []);
  const eventsHook = TB.useExternalAgentEvents(runId, running, onLive, false);
  const companionState = shown && shown.companion;
  const directed = React.useMemo(() => deriveCompanionDirectives(companionState), [companionState]);
  // The newest finished trail run on this folder, read off the run-finished lifecycle receipts -
  // that run's session is what "Review my trail" reviews.
  const lastRunSessionId = React.useMemo(() => {
    const evs = eventsHook.data || [];
    for (let i = evs.length - 1; i >= 0; i--) {
      const e = evs[i];
      if (e && e.kind === 'lifecycle' && e.title === 'run-finished' && e.input) {
        try {
          const p = JSON.parse(e.input);
          if (p && typeof p.sessionId === 'string' && p.sessionId) return p.sessionId;
        } catch (_) { /* a malformed receipt reviews nothing */ }
      }
    }
    return null;
  }, [eventsHook.data]);
  if (!runId || (!waiting && !run)) {
    return (
      <div style={{ flex: 1, minHeight: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <EmptyState ico="unplug" title="No companion session"
          sub="Start one from your agent's CLI with `trailblaze companion start`." />
      </div>
    );
  }
  if (!shown) {
    return (
      <div style={{ flex: 1, minHeight: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <EmptyState ico="loader-2" title="Connecting…" sub="Waiting for the daemon's session list." />
      </div>
    );
  }
  const companion = shown.companion || {};
  const agentLabel = companion.agentLabel || 'your external agent';
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 10, padding: '12px 18px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="satellite-dish" s={16} c={running ? 'var(--tb-running)' : 'var(--text-subtle-variant)'} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{shown.title || 'Companion session'}</div>
          <div className="tb-sub" style={{ fontSize: 11.5 }}>
            {running ? `Driven by ${agentLabel} - request changes there, not here.` : `This session has ended. ${agentLabel} drove it.`}
          </div>
        </div>
        {/* Just "Live": listener presence only surfaces per-ask (the shared-brain defer answers
            "degraded" when nobody listens), so a standing "agent is listening" chip would be an
            assertion, not an observation. */}
        <Chip tone={running ? 'blue' : (shown.status === 'failed' ? 'red' : '')}>{running ? 'Live' : 'Ended'}</Chip>
      </div>
      <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex' }}>
        {/* One keyed fragment around ALL the panes: reply drafts, busy flags, scroll positions,
            and the rail's file listing must die together with the session. A single key on the
            fragment (never the same key on two siblings - React reconciles siblings BY key, and
            duplicates corrupt the child list, leaking stale pane instances on session switch). */}
        <React.Fragment key={runId}>
          <div style={{ flex: '0 1 400px', minWidth: 300, minHeight: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid var(--tb-hairline)' }}>
            <CompanionGuidance directed={directed} running={running} folder={companion.folder} runId={runId} active={active} go={go} />
            <CompanionReviewAsk running={running} requests={companion.requests || {}} sessionId={lastRunSessionId} />
            <CompanionNarration events={eventsHook.data} loading={eventsHook.loading} running={running} agentLabel={agentLabel} />
            <CompanionReplies runId={runId} actions={directed.actions} running={running} />
          </div>
          {/* The center demonstration stage, live sessions only: an ended session has nothing to
              demonstrate for, so the transcript and folder share the width instead. */}
          {running && <AgentRecordStage mirror={mirror} runId={null} hasSteps mode="companion" />}
          <CompanionFolderRail runId={runId} running={running} folder={companion.folder} active={active} wide={!running} />
        </React.Fragment>
      </div>
    </div>
  );
}

Object.assign(window, { CompanionScreen });

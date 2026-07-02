// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

const TOOL_FLAVORS = {
  scripted: {
    short: 'Scripted', label: 'Scripted TypeScript', ico: 'file-code-2', color: 'var(--tb-pass)', tag: 'Recommended', lang: 'typescript',
    what: 'A typed .ts tool that runs on Bun - a handler over the typed ctx.tools.<name>(args) surface. One file per tool.',
    when: 'Branching, retries, async, or composition beyond a literal substitution.',
    where: '<trailmap>/tools/<name>.ts · listed in trailmap.yaml under target.tools',
    snippet: 'export const clock_clearAlarms = trailblaze.tool(\n  { supportedPlatforms: ["android"], requiresContext: true },\n  async (input, ctx) => {\n    await ctx.tools.android_adbShell({ command: ["pm", "clear", appId] });\n    return `Cleared.`;\n  },\n);',
  },
  yaml: {
    short: 'YAML', label: 'YAML-composed', ico: 'file-text', color: 'var(--tb-running)', tag: null, lang: 'yaml',
    what: 'A declarative <id>.tool.yaml that substitutes params into existing framework tool calls. No code; auto-discovered.',
    when: 'Thin wrappers where a TypeScript handler would be more ceremony than the wrapper deserves.',
    where: '<trailmap>/tools/<id>.tool.yaml',
    snippet: 'id: eraseText\ndescription: Erase text from the focused field.\nparameters:\n  - name: charactersToErase\n    type: integer\n    required: false\ntools:\n  - mobile_maestro:\n      commands:\n        - eraseText:\n            charactersToErase: "{{params.charactersToErase}}"',
  },
  kotlin: {
    short: 'Kotlin', label: 'Kotlin-backed', ico: 'box', color: 'var(--tb-amber)', tag: 'Advanced', lang: 'yaml',
    what: 'A @TrailblazeToolClass implementing HostLocalExecutableTrailblazeTool, registered by a sibling .tool.yaml with a class: FQN.',
    when: 'Host-side state only - a JVM library, a host process handle, a private internal API.',
    where: 'Kotlin class in your source tree + <trailmap>/tools/<id>.tool.yaml with class: FQN',
    snippet: '# seedTestData.tool.yaml\nid: seedTestData\nclass: com.example.trailblaze.tools.SeedTestDataTool',
  },
  unknown: { short: 'Unknown', label: 'Unparsed', ico: 'file-question', color: 'var(--text-subtle)', lang: 'yaml' },
};
const FLAVOR_ORDER = ['scripted', 'yaml', 'kotlin'];
const flavorOf = (f) => TOOL_FLAVORS[f] || TOOL_FLAVORS.unknown;

function FlavorBadge({ flavor, dot }) {
  const f = flavorOf(flavor);
  if (dot) return <span title={f.label} style={{ width: 7, height: 7, borderRadius: 99, background: f.color, flex: '0 0 auto' }} />;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '2px 8px', borderRadius: 99, fontSize: 11, fontWeight: 600, color: f.color, background: 'color-mix(in srgb, ' + f.color + ' 14%, transparent)', border: '1px solid color-mix(in srgb, ' + f.color + ' 30%, transparent)', whiteSpace: 'nowrap' }}>
      <Ico n={f.ico} s={12} c={f.color} />{f.short}
    </span>
  );
}

function SourceCode({ source, lang }) {
  if (!source) return <div className="tb-sub" style={{ fontSize: 12.5 }}>No source available.</div>;
  return <SearchableText text={source} language={lang} fontSize={12.5} minHeight={200} />;
}

function ToolMeta({ ico, label, children }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <Ico n={ico} s={13} c="var(--text-subtle)" />
      <span className="tb-sub" style={{ width: 54, flex: '0 0 54px' }}>{label}</span>
      <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{children}</span>
    </div>
  );
}

// A small icon+count token for the tool list's far-right usage indicators: the trail icon ("route")
// with how many trails record the tool, and the tool icon ("wrench") with how many other tools
// dispatch it. Two icons so the two counts are distinguishable at a glance.
function UsageChip({ ico, n, title }) {
  return (
    <span title={title} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 10, lineHeight: 1, color: 'var(--text-subtle)', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 5, padding: '2px 5px', flex: '0 0 auto' }}>
      <Ico n={ico} s={11} c="var(--text-subtle-variant)" />{n}
    </span>
  );
}

function toolUsageSnippet(t) {
  const lines = ['- prompts:', '  - step: "<what this step accomplishes>"', '    recording:', '      tools:'];
  const ps = (t.parameters || []).filter((p) => p.required);
  if (ps.length === 0) {
    lines.push(`      - ${t.id}: {}`);
  } else {
    lines.push(`      - ${t.id}:`);
    ps.forEach((p) => lines.push(`          ${p.name}: <${p.type}>`));
  }
  return lines.join('\n');
}

function ToolSummary({ t, go }) {
  // Scripted (.ts) tools carry no params in the static catalog (the TypeScript arg-schema analyzer
  // is too slow to run catalog-wide), so resolve them on demand here; Kotlin/yaml tools already
  // ship their params inline on the catalog entry.
  const isScripted = t.flavor === 'scripted';
  const scripted = TB.useScriptedToolParams(isScripted ? t.trailmap : null, isScripted ? t.id : null);
  // Gate on `loading` alone (not `!data`): useFetched keeps the PREVIOUS tool's data while the next
  // request is in flight, so switching scripted tools would otherwise briefly show the prior tool's
  // params. While loading, treat params as empty and show the analyzing state instead of stale data.
  const paramsLoading = isScripted && scripted.loading;
  const params = isScripted ? (paramsLoading ? [] : (scripted.data || [])) : (t.parameters || []);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>What the LLM is told</div>
        {t.llmDescription
          ? <div className="tb-card" data-selectable style={{ padding: '12px 15px', fontSize: 13, lineHeight: 1.65, whiteSpace: 'pre-wrap' }}>{t.llmDescription}</div>
          : <div className="tb-sub" style={{ fontSize: 12.5 }}>No LLM-facing description - the model only sees the tool name and parameters. Add an @LLMDescription (Kotlin) or description (yaml) in the Edit tab.</div>}
      </div>

      <div>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Parameters{params.length ? ` · ${params.length}` : ''}</div>
        {paramsLoading
          ? <div className="tb-sub" style={{ fontSize: 12.5 }}>Analyzing TypeScript arguments…</div>
          : params.length === 0
            ? <div className="tb-sub" style={{ fontSize: 12.5 }}>None - the tool takes no arguments.</div>
            : (
              <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden' }}>
                {params.map((p, i) => (
                  <div key={p.name} style={{ display: 'flex', gap: 10, alignItems: 'baseline', padding: '9px 13px', background: 'var(--bg-subtle)', borderBottom: i < params.length - 1 ? '1px solid var(--tb-hairline)' : 'none' }}>
                    <span className="tb-mono" style={{ fontSize: 12.5, fontWeight: 700, flex: '0 0 auto' }}>{p.name}</span>
                    <Chip>{p.type}{p.required ? ' · required' : ' · optional'}</Chip>
                    <span className="tb-sub" data-selectable style={{ fontSize: 12, lineHeight: 1.5, minWidth: 0 }}>{p.description || ''}</span>
                  </div>
                ))}
              </div>
            )}
      </div>

      <div>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Use it in a trail</div>
        <pre className="tb-mono" data-selectable style={{ margin: 0, fontSize: 11.5, lineHeight: 1.6, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 10, padding: '10px 13px' }}>{toolUsageSnippet({ ...t, parameters: params })}</pre>
      </div>

    </div>
  );
}

// A Monaco editor wired to the daemon's language server over the WebSocket bridge — vtsls for
// `language='typescript'` (scripted `.ts` tools), yaml-language-server for `language='yaml'`
// (`.tool.yaml` definitions). Gives autocomplete / hover / validation. Mounts the imperative
// `window.TBMonaco` editor into a host div; only rendered when that module loaded (callers fall back
// to <CodeEditor> otherwise). `sourcePath` is the tool's workspace-relative source path — the daemon
// turns it into the real on-disk file:// URI so the server opens the document at its real path
// (TS resolves the tsconfig; YAML matches the *.tool.yaml schema glob).
function MonacoToolEditor({ sourcePath, value, onChange, onSave, mode, language = 'typescript', trailmap }) {
  const hostRef = React.useRef(null);
  const handleRef = React.useRef(null);
  // When Monaco's lazy CDN load (or the mount itself) fails — offline, blocked unpkg, subresource
  // error — fall back to the plain CodeMirror editor instead of leaving an empty host div. The
  // parent already chose this component, so the fallback has to live here.
  const [failed, setFailed] = React.useState(false);
  // Mirror the latest callbacks so the mount effect (which runs once per file) always calls through
  // to current handlers without re-mounting the editor on every keystroke.
  const cbRef = React.useRef({ onChange, onSave });
  cbRef.current = { onChange, onSave };
  React.useEffect(() => {
    let disposed = false;
    setFailed(false); // re-arm Monaco for a newly-selected file (so a prior file's failure doesn't stick)
    const mount = language === 'yaml' ? window.TBMonaco.mountYaml : window.TBMonaco.mountTypescript;
    mount({
      host: hostRef.current,
      value: value != null ? value : '',
      sourcePath,
      trailmap,
      onChange: (t) => cbRef.current.onChange && cbRef.current.onChange(t),
      onSave: () => cbRef.current.onSave && cbRef.current.onSave(),
    }).then((h) => {
      if (disposed) { h.dispose(); return; }
      handleRef.current = h;
    }).catch((e) => {
      if (window.console) console.warn('[MonacoToolEditor] mount failed, falling back to CodeMirror:', e);
      if (!disposed) setFailed(true);
    });
    return () => { disposed = true; if (handleRef.current) { handleRef.current.dispose(); handleRef.current = null; } };
  }, [sourcePath, language]);
  // Re-seed the editor when `value` is reset from outside (e.g. baseline reload after save). The
  // getValue guard makes this a no-op for edits that originated in the editor, so there's no loop.
  React.useEffect(() => {
    const h = handleRef.current;
    if (h && value != null && h.getValue() !== value) h.setValue(value);
  }, [value]);
  // Hooks above always run (rules of hooks); the render branches only after.
  if (failed) return <CodeEditor value={value} onChange={onChange} onSave={onSave} mode={mode} />;
  return <div ref={hostRef} style={{ height: '100%', minHeight: 0 }} />;
}

function ToolSourceEditor({ initial, mode, target, caveat, rebuildable, onSaved, lspSourcePath, lspLanguage, lspTrailmap }) {
  const [text, setText] = React.useState(initial);
  const [baseline, setBaseline] = React.useState(initial);
  const [busy, setBusy] = React.useState(false);
  const [note, setNote] = React.useState(null);
  const [rebuild, setRebuild] = React.useState(null);
  const dirty = text !== baseline;

  const rebuildAndRestart = async () => {
    if (rebuild === 'compiling' || rebuild === 'restarting') return;
    setRebuild('compiling');
    const r = await TB.rebuildDaemon();
    if (!r.ok) { setRebuild({ error: r.error || 'rebuild failed' }); return; }
    setRebuild('restarting');
    const ping = () => fetch('/ping', { cache: 'no-store' }).then((x) => x.ok).catch(() => false);
    let down = false;
    for (let i = 0; i < 400; i++) {
      const up = await ping();
      if (!down && !up) down = true;
      else if (down && up) { location.reload(); return; }
      await new Promise((res) => setTimeout(res, 1500));
    }
    setRebuild({ error: 'Daemon did not come back - check /tmp/trailrunner-daemon-restart.log, or relaunch with ./trailblaze trailrunner' });
  };
  const save = async () => {
    if (!dirty || busy) return;
    setBusy(true); setNote(null);
    const r = await TB.updateToolSource(target, text);
    setBusy(false);
    if (r.success) { setBaseline(text); setNote({ ok: true, msg: 'Saved ' + (r.savedPath || '') }); if (onSaved) onSaved(); }
    else setNote({ ok: false, msg: r.error || 'Save failed' });
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, flex: 1, minHeight: 0 }}>
      {caveat && (
        <div className="tb-card" style={{ padding: '9px 12px', display: 'flex', gap: 9, alignItems: 'center', borderColor: 'rgba(245,166,35,.35)' }}>
          <Ico n="triangle-alert" s={14} c="var(--tb-amber)" />
          <span className="tb-sub" style={{ fontSize: 12, flex: 1, minWidth: 0 }}>{caveat}</span>
          {rebuildable && (
            <Btn sm ico="refresh-cw" onClick={rebuildAndRestart} disabled={rebuild === 'compiling' || rebuild === 'restarting'} data-testid="rebuild-daemon">
              {rebuild === 'compiling' ? 'Compiling…' : rebuild === 'restarting' ? 'Restarting…' : 'Rebuild & restart'}
            </Btn>
          )}
        </div>
      )}
      {rebuild === 'restarting' && (
        <div className="tb-card" style={{ padding: '9px 12px', display: 'flex', gap: 9, alignItems: 'center' }}>
          <Dot c="var(--tb-amber)" />
          <span className="tb-sub" style={{ fontSize: 12 }}>Compile OK - daemon restarting with your change. This page reloads automatically (the window stays open).</span>
        </div>
      )}
      {rebuild && rebuild.error && (
        <div className="tb-card" style={{ padding: '9px 12px', borderColor: 'rgba(248,71,82,.4)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--tb-danger-text)', marginBottom: 4 }}>Rebuild failed</div>
          <pre className="tb-mono" data-selectable style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap', maxHeight: 180, overflow: 'auto', color: 'var(--text-subtle)' }}>{rebuild.error}</pre>
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {dirty ? <Chip tone="amber">Unsaved changes</Chip> : <Chip>Saved</Chip>}
        {note && <span style={{ fontSize: 12, color: note.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0 }}>{note.msg}</span>}
        <div style={{ flex: 1 }} />
        <Btn sm ico="save" onClick={save} disabled={!dirty || busy}>{busy ? 'Saving…' : 'Save'}</Btn>
      </div>
      <div className="tb-editor" style={{ flex: 1, minHeight: 320 }}>
        {(() => {
          const mountFn = lspLanguage === 'yaml' ? (window.TBMonaco && window.TBMonaco.mountYaml) : (window.TBMonaco && window.TBMonaco.mountTypescript);
          return lspSourcePath && mountFn
            ? <MonacoToolEditor sourcePath={lspSourcePath} value={text} onChange={setText} onSave={save} mode={mode} language={lspLanguage || 'typescript'} trailmap={lspTrailmap} />
            : <CodeEditor value={text} onChange={setText} onSave={save} mode={mode} />;
        })()}
      </div>
    </div>
  );
}

function ToolEditTab({ t }) {
  const ktSource = TB.useToolSource(t.flavor === 'kotlin' ? t.className : null);
  const kotlinTarget = { className: t.className };
  const fileTarget = { path: t.sourcePath };
  if (t.flavor === 'kotlin') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Implementation (Kotlin)</div>
        {ktSource.loading ? <div className="tb-skel" style={{ height: 200 }} />
          : ktSource.data
            ? <ToolSourceEditor key={t.className} initial={ktSource.data} mode="text/x-kotlin" target={kotlinTarget} rebuildable
                caveat="Kotlin changes need a daemon rebuild + restart to take effect - use the button; the app stays open and reloads when the daemon is back."
                onSaved={() => ktSource.reload()} />
            : <div className="tb-sub" style={{ fontSize: 12.5 }}>Source not available (no source tree on this host). The class is registered by FQN in <span className="tb-mono">{t.sourcePath}</span>.</div>}
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
      <div className="tb-eyebrow" style={{ marginBottom: 8 }}>{t.flavor === 'scripted' ? 'Source (.ts)' : 'Definition (.tool.yaml)'}</div>
      <ToolSourceEditor key={t.sourcePath} initial={t.source} mode={t.flavor === 'scripted' ? { name: 'javascript', typescript: true } : 'yaml'} target={fileTarget}
        lspSourcePath={t.sourcePath}
        lspLanguage={t.flavor === 'scripted' ? 'typescript' : 'yaml'}
        lspTrailmap={t.trailmap}
        caveat={t.flavor === 'scripted' ? 'Scripted tools take effect on the next run.' : null} />
    </div>
  );
}

function ToolUsedBy({ t, go }) {
  const usages = TB.useToolUsages(t.id);
  const toolCallers = TB.useToolToolUsages(t.id);
  const allTools = TB.useTools();
  // Some callers are Kotlin orchestrators registered via a toolset/trailhead with no .tool.yaml —
  // they're real callers but not browsable catalog entries, so their rows are non-navigable.
  const catalogIds = React.useMemo(() => new Set((allTools.data || []).map((x) => x.id)), [allTools.data]);
  const list = usages.data || [];
  const callers = toolCallers.data || [];
  const [selIdx, setSelIdx] = React.useState(-1);
  const stillLoading = (usages.loading && !usages.data) || (toolCallers.loading && !toolCallers.data);
  if (stillLoading) return <Skeleton rows={4} />;
  // A tool reached only through another tool (ctx.tools / invokeFrameworkTool) has 0 trail usage but
  // is not dead — so the empty state only fires when neither a trail nor a tool references it.
  if (list.length === 0 && callers.length === 0) {
    return <EmptyState ico="route" title="Nothing uses this tool yet" sub={'No recording or other tool in your workspace calls ' + t.id + '. The Summary tab has a copy-ready snippet to add it to a trail.'} />;
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
      {callers.length > 0 && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="tb-eyebrow">Used by other tools · {callers.length}</div>
            <span className="tb-sub" style={{ fontSize: 11.5 }}>Tools that dispatch <span className="tb-mono">{t.id}</span> via <span className="tb-mono">ctx.tools</span> or <span className="tb-mono">invokeFrameworkTool</span>.</span>
          </div>
          <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden' }}>
            {callers.map((c, i) => {
              const navigable = catalogIds.has(c.id);
              const open = navigable ? () => go && go('tools', { tool: c.id }) : null;
              return (
                <div
                  key={(c.trailmap || '') + '/' + c.id}
                  role={navigable ? 'button' : undefined}
                  tabIndex={navigable ? 0 : undefined}
                  data-navrow={navigable ? '' : undefined}
                  data-testid="tool-tool-usage-row"
                  title={navigable ? undefined : 'Registered via a toolset/trailhead — not a browsable tool file'}
                  onClick={open || undefined}
                  onKeyDown={navigable ? (e) => { if (e.key === 'Enter') open(); } : undefined}
                  style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '10px 13px', cursor: navigable ? 'pointer' : 'default', background: 'var(--bg-subtle)', borderBottom: i < callers.length - 1 ? '1px solid var(--tb-hairline)' : 'none' }}
                >
                  <Ico n="wrench" s={13} c={flavorOf(c.flavor).color} style={{ flex: '0 0 auto' }} />
                  <span className="tb-mono" style={{ flex: 1, minWidth: 0, fontSize: 12.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.id}</span>
                  <FlavorBadge flavor={c.flavor} dot />
                  {c.trailmap && <Chip>{c.trailmap}</Chip>}
                  {navigable && <Ico n="arrow-up-right" s={13} c="var(--text-subtle-variant)" />}
                </div>
              );
            })}
          </div>
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="tb-eyebrow">Trails using this tool · {list.length}</div>
          <span className="tb-sub" style={{ fontSize: 11.5 }}>Recordings in your workspace that call <span className="tb-mono">{t.id}</span> - click to open the trail.</span>
        </div>
        {list.length === 0
          ? <div className="tb-sub" style={{ fontSize: 12.5 }}>No recording in your workspace calls <span className="tb-mono">{t.id}</span> directly.</div>
          : (
            <div tabIndex={0} style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden', outline: 'none' }}
              onKeyDown={(e) => listNavKeyDown(e, { index: selIdx, count: list.length, set: (i) => { setSelIdx(i); } })}>
              {list.map((tr, i) => (
                <div
                  key={tr.id}
                  role="button"
                  tabIndex={0}
                  data-navrow
                  data-testid="tool-usage-row"
                  onClick={() => go && go('trails', { sel: tr.id })}
                  onKeyDown={(e) => { if (e.key === 'Enter') go && go('trails', { sel: tr.id }); }}
                  style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '10px 13px', cursor: 'pointer', background: selIdx === i ? 'var(--bg-standard)' : 'var(--bg-subtle)', borderBottom: i < list.length - 1 ? '1px solid var(--tb-hairline)' : 'none' }}
                >
                  <Ico n="route" s={14} c="var(--text-subtle)" />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{tr.title}</div>
                    <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{tr.path}</div>
                  </div>
                  {tr.target && <Chip>{tr.target}</Chip>}
                  {tr.platform && <Chip>{tr.platform}</Chip>}
                  <Ico n="arrow-up-right" s={13} c="var(--text-subtle-variant)" />
                </div>
              ))}
            </div>
          )}
      </div>
    </div>
  );
}

function ToolDetail({ t, go, usageCount }) {
  useLucide();
  const [tab, setTab] = React.useState('summary');
  React.useEffect(() => { setTab('summary'); }, [t && t.id]);
  const usedLabel = usageCount > 0 ? `Used by (${usageCount})` : 'Used by';
  return (
    <div style={{ padding: '24px 28px', maxWidth: 980, margin: '0 auto', display: 'flex', flexDirection: 'column', boxSizing: 'border-box', ...(tab === 'edit' ? { height: '100%', minHeight: 0 } : { minHeight: '100%' }) }}>
      <DetailHeader
        title={t.id}
        badges={<><FlavorBadge flavor={t.flavor} /><Chip>{t.trailmap}</Chip></>}
        meta={<>
          <ToolMeta ico="folder" label="Source"><span className="tb-mono" data-selectable title={t.sourcePath}>{t.sourcePath}</span></ToolMeta>
          {t.className && <ToolMeta ico="box" label="Class"><span className="tb-mono" data-selectable title={t.className}>{t.className}</span></ToolMeta>}
        </>}
        right={<Btn sm ico="folder-open" onClick={() => TB.revealToolSource({ className: t.flavor === 'kotlin' ? t.className : null, path: t.sourcePath })}>Open in Finder</Btn>}
      />
      <div className="tb-tabs" style={{ marginTop: 16, marginBottom: 18 }}>
        {[['summary', 'Overview', 'eye'], ['edit', 'Edit', 'pencil'], ['used', usedLabel, 'route']].map(([id, label, ico]) => (
          <div key={id} className={'tb-tab ' + (tab === id ? 'active' : '')} onClick={() => setTab(id)} style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer' }}>
            <Ico n={ico} s={15} />{label}
          </div>
        ))}
      </div>
      {tab === 'summary' && <ToolSummary t={t} go={go} />}
      {tab === 'edit' && <ToolEditTab t={t} />}
      {tab === 'used' && <ToolUsedBy t={t} go={go} />}
    </div>
  );
}
function ToolsScreen({ initTool, go }) {
  useLucide();
  const toolsResult = TB.useTools();
  const usageCounts = TB.useToolUsageCounts();
  const toolUsageCounts = TB.useToolToolUsageCounts();
  const [gt] = TB.useGlobalTarget();
  const devices = TB.useDevices();
  const allRaw = toolsResult.data || [];
  // The single platform of the active target's selected devices (null when mixed or none) — used
  // to widen the scope to the right platform-shared trailmaps (android/ios/web/mobile/compose).
  const gtPlatform = React.useMemo(() => {
    const list = devices.data || [];
    const plats = new Set(((gt && gt.deviceIds) || []).map((id) => { const d = list.find((x) => x.id === id); return d && d.platform; }).filter(Boolean));
    return plats.size === 1 ? [...plats][0] : null;
  }, [gt && (gt.deviceIds || []).join(','), devices.data]);
  // When a global target is set, scope the list to the tools that actually apply to it: its own
  // trailmap PLUS the generalized framework + platform trailmaps (so android_*/mobile_*/trailblaze_*
  // tools stay visible for an app target). "Show all" (the footer banner) drops the scope; switching
  // targets re-applies it.
  const [scopeOff, setScopeOff] = React.useState(false);
  React.useEffect(() => { setScopeOff(false); }, [gt && gt.target]);
  const scoped = !!(gt && gt.target) && !scopeOff;
  const scopeSet = React.useMemo(() => (gt && gt.target ? TB.scopeTrailmaps(gt.target, gtPlatform) : null), [gt && gt.target, gtPlatform]);
  const all = React.useMemo(() => (scoped && scopeSet ? allRaw.filter((t) => scopeSet.has(t.trailmap)) : allRaw), [allRaw, scoped, scopeSet]);
  const [q, setQ] = React.useState('');
  const [flavor, setFlavor] = useStickyState('tb-tools-flavor', 'all');
  const [targetRaw, setTarget] = useStickyState('tb-tools-targets', []);
  const target = Array.isArray(targetRaw) ? targetRaw : [];
  const [sort, setSort] = useStickyState('tb-tools-sort', 'group');
  const targetMap = TB.useTargetAppMap();
  const [selId, setSelId] = React.useState(null);
  const [collapsedGroups, setCollapsedGroups] = React.useState(() => new Set());
  const toggleGroup = (tm) => setCollapsedGroups((prev) => {
    const next = new Set(prev);
    if (next.has(tm)) next.delete(tm); else next.add(tm);
    return next;
  });
  const [menu, setMenu] = React.useState(null);
  const [showNew, setShowNew] = React.useState(false);
  const [pendingSelect, setPendingSelect] = React.useState(null);
  const tmAll = TB.useTrailmaps();
  const [treeW, startTreeDrag] = useResizableWidth('tb-tools-tree-w', 300, 220, 600);

  const keyOf = (t) => t.sourcePath + '#' + t.id;
  const counts = React.useMemo(() => {
    const c = { all: all.length, scripted: 0, yaml: 0, kotlin: 0 };
    all.forEach((t) => { c[t.flavor] = (c[t.flavor] || 0) + 1; });
    return c;
  }, [all]);
  const allTargets = React.useMemo(() => [...new Set(all.map((t) => t.trailmap).filter(Boolean))].sort(), [all]);
  const hasUntargeted = React.useMemo(() => all.some((t) => !t.trailmap), [all]);
  const usageOf = (t) => (usageCounts.data || {})[t.id] || 0;
  const toolCallersOf = (t) => (toolUsageCounts.data || {})[t.id] || 0;
  const filtered = React.useMemo(() => {
    const ql = q.trim().toLowerCase();
    return all.filter((t) =>
      (flavor === 'all' || t.flavor === flavor) &&
      (target.length === 0 || target.includes(t.trailmap) || (target.includes('__none__') && !t.trailmap)) &&
      // "Unused" means reached by neither a trail NOR another tool — a helper dispatched only via
      // ctx.tools/invokeFrameworkTool is load-bearing, so it must not show up here.
      (sort !== 'unused' || (usageOf(t) === 0 && toolCallersOf(t) === 0)) &&
      (!ql || [t.id, t.trailmap, t.description, t.className].some((v) => v && String(v).toLowerCase().includes(ql))));
  }, [all, q, flavor, target, sort, usageCounts.data, toolUsageCounts.data]);
  const groups = React.useMemo(() => {
    if (sort === 'group') {
      const g = {};
      filtered.forEach((t) => { (g[t.trailmap] = g[t.trailmap] || []).push(t); });
      return g;
    }
    const nameOf = (t) => (t.className ? t.className.split('.').pop() : t.id);
    const arr = [...filtered];
    if (sort === 'usage') arr.sort((a, b) => (usageOf(b) - usageOf(a)) || nameOf(a).localeCompare(nameOf(b)));
    else arr.sort((a, b) => nameOf(a).localeCompare(nameOf(b)));
    return { '': arr };
  }, [filtered, sort, usageCounts.data]);
  const trailmaps = sort === 'group' ? Object.keys(groups).sort() : [''];
  const cur = all.find((t) => keyOf(t) === selId) || null;

  React.useEffect(() => {
    if (filtered.length && !filtered.find((t) => keyOf(t) === selId)) setSelId(keyOf(filtered[0]));
  }, [toolsResult.data, flavor, q, target, sort]);

  // Re-apply whenever the routed tool changes (keyed on the value, not a one-shot) —
  // screens stay mounted, so navigating to a different tool must re-select it. The
  // value key keeps a catalog reload from clobbering a tool the user picked manually.
  const appliedTool = React.useRef(null);
  React.useEffect(() => {
    if (!initTool || initTool === appliedTool.current || !all.length) return;
    appliedTool.current = initTool;
    const hit = all.find((t) => t.id === initTool);
    if (hit) setSelId(keyOf(hit));
    else setQ(initTool);
  }, [all, initTool]);

  // After scaffolding a new tool, select it once the catalog reload surfaces it.
  React.useEffect(() => {
    if (!pendingSelect) return;
    const hit = all.find((t) => t.id === pendingSelect);
    if (hit) { setSelId(keyOf(hit)); setPendingSelect(null); }
  }, [pendingSelect, all]);

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      <div style={{ width: treeW, flex: '0 0 ' + treeW + 'px', borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <RailHeader ico="wrench" iconColor="var(--text-subtle-variant)" title="Tools"
          help={<HelpDot ico="wrench" color="var(--text-subtle-variant)" title={TM_COMP_TYPES.tools.help.title} tag={TM_COMP_TYPES.tools.help.tag}>{TM_COMP_TYPES.tools.help.body}</HelpDot>}
          right={<button className="tb-btn ghost sm" style={{ padding: 6 }} title="New tool" onClick={() => setShowNew(true)}><Ico n="plus" s={16} /></button>} />
        <div style={{ padding: '0 12px 8px' }}>
          <div className="tb-input"><Ico n="search" s={14} /><input placeholder="Search tools…" value={q} onChange={(e) => setQ(e.target.value)} /></div>
        </div>
        <div style={{ padding: '0 12px 10px', display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <Select compact ico="filter" label="Type" value={flavor} onChange={(e) => setFlavor(e.target.value)} title="Filter by tool type"
            options={[['all', `All (${counts.all})`], ...FLAVOR_ORDER.map((fl) => [fl, `${TOOL_FLAVORS[fl].short} (${counts[fl] || 0})`])]} />
          <Select compact multi ico="crosshair" label="Target" value={target} onChange={(e) => setTarget(e.target.value)} title="Filter by targets - pick any number"
            options={[['all', 'All Targets'], ...allTargets.map((tm) => [tm, TB.targetLabel(tm, targetMap)]), ...(hasUntargeted ? [['__none__', 'Not specified']] : [])]} />
          <Select compact ico="arrow-down-up" label="Sort" value={sort} onChange={(e) => setSort(e.target.value)} title="Order of the tool list"
            options={[['group', 'Grouped by target'], ['usage', 'Most used'], ['name', 'Name A–Z'], ['unused', 'Unused only']]} />
        </div>
        <div tabIndex={0} style={{ flex: 1, overflowY: 'auto', padding: '0 8px 10px', outline: 'none' }} data-testid="tools-tree"
          onKeyDown={(e) => { const flat = trailmaps.filter((tm) => !collapsedGroups.has(tm)).flatMap((tm) => groups[tm]); listNavKeyDown(e, { index: flat.findIndex((x) => keyOf(x) === selId), count: flat.length, set: (i) => setSelId(keyOf(flat[i])) }); }}>
          {trailmaps.map((tm) => {
            const groupName = tm || (sort === 'usage' ? 'Most used' : sort === 'unused' ? 'Unused' : 'All tools');
            const open = !collapsedGroups.has(tm);
            return (
              <div key={tm}>
                <div className="tb-tree" style={{ cursor: 'pointer' }} onClick={() => toggleGroup(tm)} title={open ? 'Collapse' : 'Expand'}>
                  <span style={{ display: 'inline-flex', flex: '0 0 auto', transition: 'transform .15s var(--ease-out-soft)', transform: open ? 'none' : 'rotate(-90deg)' }}>
                    <Ico n="chevron-down" s={13} c="var(--text-subtle)" />
                  </span>
                  <Ico n="map" s={15} c="var(--tb-running)" />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{groupName}</span>
                  <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>{groups[tm].length}</span>
                </div>
                {open && groups[tm].map((t) => {
                  const k = keyOf(t), on = k === selId;
                  const n = (usageCounts.data || {})[t.id] || 0;
                  const tn = (toolUsageCounts.data || {})[t.id] || 0;
                  return (
                    <div key={k} data-navrow onClick={() => setSelId(k)} role="button"
                      onContextMenu={(e) => { e.preventDefault(); setSelId(k); setMenu({ x: e.clientX, y: e.clientY, tool: t }); }}
                      className={'tb-tree ' + (on ? 'active' : '')} style={{ paddingLeft: 23, cursor: 'pointer' }}>
                      <Ico n="wrench" s={13} c={flavorOf(t.flavor).color} style={{ flex: '0 0 auto' }} />
                      <span className="tb-mono" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 12 }}>{t.id}</span>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flex: '0 0 auto' }}>
                        {(n > 0 || sort === 'usage') && <UsageChip ico="route" n={n} title={`Used by ${n} trail${n === 1 ? '' : 's'}`} />}
                        {tn > 0 && <UsageChip ico="wrench" n={tn} title={`Used by ${tn} other tool${tn === 1 ? '' : 's'}`} />}
                      </span>
                    </div>
                  );
                })}
              </div>
            );
          })}
          {!toolsResult.loading && trailmaps.length === 0 && <div className="tb-sub" style={{ padding: '20px 10px', fontSize: 12.5 }}>No tools match.</div>}
        </div>
        <TargetScopeBanner label={scoped ? (gt.label || gt.target) : null} platform={scoped ? gtPlatform : null} onShowAll={() => setScopeOff(true)} />
        <div style={{ padding: '9px 12px', borderTop: '1px solid var(--tb-hairline)' }}>
          <div className="tb-sub" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>{filtered.length === allRaw.length ? `${allRaw.length} tool${allRaw.length === 1 ? '' : 's'}` : `${filtered.length} of ${allRaw.length} tools`}</div>
        </div>
      </div>
      <Splitter onDown={startTreeDrag} />
      <div style={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
        {toolsResult.loading && <div style={{ padding: 32 }}><Skeleton rows={5} /></div>}
        {!toolsResult.loading && all.length === 0 && <div style={{ padding: '60px 32px' }}><EmptyState ico="wrench" title="No custom tools found" sub="Add a tool under a trailmap's tools/ directory and it'll appear here." /></div>}
        {cur && <ToolDetail t={cur} go={go} usageCount={(usageCounts.data || {})[cur.id] || 0} />}
      </div>
      {menu && <ToolContextMenu menu={menu} onClose={() => setMenu(null)} go={go} />}
      {showNew && (
        <NewComponentModal
          kind="tools"
          trailmaps={((tmAll.data && tmAll.data.length ? tmAll.data.map((t) => t.id) : [...new Set(all.map((t) => t.trailmap))])).sort()}
          onClose={() => setShowNew(false)}
          onCreated={(rel) => { const id = (rel.split('/').pop() || '').replace(/\.ts$/, ''); setShowNew(false); setPendingSelect(id); toolsResult.reload(); }}
        />
      )}
    </div>
  );
}
window.ToolsScreen = ToolsScreen;
// Reused by the Trailmaps component pages for their Edit tab.
window.ToolSourceEditor = ToolSourceEditor;

// Right-click menu for a tool row: reveal in Finder, jump to the editor, copy id/path.
function ToolContextMenu({ menu, onClose, go }) {
  useLucide();
  React.useEffect(() => {
    const k = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);
  const t = menu.tool;
  const copy = (s) => { try { navigator.clipboard.writeText(s); } catch (_) {} };
  const items = [
    { ico: 'pencil', label: 'Open in editor', fn: () => { onClose(); if (go) go('tools', { tool: t.id }); } },
    { ico: 'folder-open', label: 'Open in Finder', fn: () => { onClose(); TB.revealToolSource({ className: t.flavor === 'kotlin' ? t.className : null, path: t.sourcePath }); } },
    { sep: true },
    { ico: 'copy', label: 'Copy name', fn: () => { onClose(); copy(t.id); } },
    { ico: 'copy', label: 'Copy path', fn: () => { onClose(); copy(t.sourcePath || t.className || ''); } },
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

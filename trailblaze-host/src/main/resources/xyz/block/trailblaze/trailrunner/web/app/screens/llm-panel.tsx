// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function llmFmtCost(c) { return c == null ? '—' : c < 0.000001 ? '<$0.000001' : `$${c.toFixed(6)}`; }
function llmFmtN(n) { return n == null ? '—' : n.toLocaleString(); }

function classifyUserMessage(text) {
  const t = String(text || '');
  if (/^\*\*Objective\*\*/.test(t)) return 'Objective';
  if (/^>?\s*#?#?\s*CURRENT OBJECTIVE|^> Task /.test(t)) return 'Task guidance';
  if (/view hierarchy|screenshot|screen state/i.test(t.slice(0, 120))) return 'Screen state';
  return 'Context';
}

function Collapsible({ label, tone, mono = true, children, text, startOpen = false, maxClosed = 0, bare = false }) {
  const [open, setOpen] = React.useState(startOpen);
  const body = text != null ? text : children;
  const chars = text != null ? text.length : null;
  // `bare`: no box. For the lowest-priority process/thinking content — a border there is redundant
  // ornamentation on content the eye should skip (Tufte 1+1=3). Just a muted caret + label.
  const containerStyle = bare
    ? { }
    : { borderRadius: 10, border: '1px solid ' + (tone === 'user' ? 'rgba(94,155,255,.25)' : tone === 'assistant' ? 'rgba(0,224,19,.25)' : 'var(--tb-hairline)'), background: tone === 'user' ? 'rgba(94,155,255,.07)' : tone === 'assistant' ? 'rgba(0,224,19,.05)' : 'var(--bg-prominent)', overflow: 'hidden' };
  const labelColor = tone === 'user' ? (bare ? 'var(--text-subtle)' : 'var(--tb-running)') : tone === 'assistant' ? (bare ? 'var(--text-subtle)' : 'var(--tb-pass)') : 'var(--text-subtle)';
  return (
    <div style={containerStyle}>
      <div
        role="button"
        tabIndex={0}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen((o) => !o); } }}
        style={{ display: 'flex', alignItems: 'center', gap: bare ? 5 : 8, padding: bare ? '3px 2px' : '7px 11px', cursor: 'pointer', userSelect: 'none' }}
      >
        <Ico n={open ? 'chevron-down' : 'chevron-right'} s={bare ? 12 : 13} c="var(--text-subtle)" />
        <span style={{ fontSize: bare ? 11 : 10.5, fontWeight: bare ? 500 : 700, letterSpacing: bare ? 0 : '.06em', textTransform: bare ? 'none' : 'uppercase', color: labelColor }}>{label}</span>
        {!open && chars != null && <span className="tb-sub" style={{ fontSize: 10.5 }}>{(chars / 1000).toFixed(1)}k chars</span>}
      </div>
      {open && (
        <div style={{ padding: bare ? '2px 0 8px 17px' : '0 12px 10px' }}>
          {text != null
            ? <pre className={mono ? 'tb-mono' : ''} data-selectable style={{ margin: 0, fontSize: 11.5, lineHeight: 1.55, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 420, overflow: 'auto' }}>{text}</pre>
            : children}
        </div>
      )}
    </div>
  );
}

function LlmCallDetail({ r, idx, total }) {
  const sys = (r.messages || []).filter((m) => m.role === 'system');
  const thread = (r.messages || []).filter((m) => m.role !== 'system');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="tb-card" style={{ padding: '10px 14px', display: 'flex', gap: 18, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13, fontWeight: 700 }}>Call {idx + 1} <span className="tb-sub" style={{ fontWeight: 500 }}>of {total}</span></span>
        <span className="tb-mono tb-sub" style={{ fontSize: 11.5 }}>{r.model}</span>
        <span className="tb-sub" style={{ fontSize: 11.5 }}>in {llmFmtN(r.inputTokens)}{r.cacheReadTokens > 0 ? ` (${llmFmtN(r.cacheReadTokens)} cached)` : ''} · out {llmFmtN(r.outputTokens)}</span>
        {r.totalCost != null && <span className="tb-sub" style={{ fontSize: 11.5 }}>{llmFmtCost(r.totalCost)}</span>}
        {r.durationMs > 0 && <span className="tb-sub" style={{ fontSize: 11.5 }}>{(r.durationMs / 1000).toFixed(1)}s</span>}
      </div>

      {r.instructions && (
        <div style={{ display: 'flex', gap: 9, alignItems: 'baseline', padding: '2px 2px 0' }}>
          <span className="tb-eyebrow" style={{ flex: '0 0 auto' }}>Step</span>
          <span data-selectable style={{ fontSize: 13, fontWeight: 600 }}>{r.instructions}</span>
        </div>
      )}

      {sys.map((m, i) => (
        <Collapsible key={'sys' + i} label="System prompt" text={m.message || ''} />
      ))}

      {thread.map((m, i) => {
        if (m.role === 'user') {
          const kind = classifyUserMessage(m.message);
          const big = (m.message || '').length > 700 || kind === 'Screen state';
          return <Collapsible key={'m' + i} tone="user" label={'User · ' + kind} text={m.message || ''} startOpen={!big} />;
        }
        if (m.role === 'tool_use' || m.toolName) {
          return <Collapsible key={'m' + i} label={'Earlier tool call · ' + (m.toolName || 'tool')} text={m.message || ''} />;
        }
        return <Collapsible key={'m' + i} tone="assistant" label={'Assistant (earlier)'} text={m.message || ''} startOpen />;
      })}

      <div style={{ borderRadius: 10, border: '1px solid rgba(181,140,255,.3)', background: 'rgba(181,140,255,.07)', padding: '10px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: (r.response || []).length ? 8 : 0 }}>
          <Ico n="sparkles" s={13} c="var(--tb-ai)" />
          <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--tb-ai)' }}>Assistant response</span>
        </div>
        {(r.response || []).length === 0 && <div className="tb-sub" style={{ fontSize: 12 }}>No response captured for this call.</div>}
        {(r.response || []).map((part, i) => part.kind === 'tool' ? (
          <div key={i} style={{ marginBottom: 8 }}>
            {part.reasoning && <div data-selectable style={{ fontSize: 12.5, lineHeight: 1.55, marginBottom: 6 }}>{part.reasoning}</div>}
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 4 }}>
              <Ico n="wrench" s={12} c="var(--text-subtle)" />
              <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700 }}>{part.tool}</span>
            </div>
            {part.args && part.args !== '{}' && (
              <pre className="tb-mono" data-selectable style={{ margin: 0, fontSize: 11, lineHeight: 1.5, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 220, overflow: 'auto', background: 'var(--bg-prominent)', borderRadius: 8, padding: '7px 9px', border: '1px solid var(--tb-hairline)' }}>{part.args}</pre>
            )}
          </div>
        ) : (
          <div key={i} data-selectable style={{ fontSize: 12.5, lineHeight: 1.55, whiteSpace: 'pre-wrap', marginBottom: 6 }}>{part.text}</div>
        ))}
      </div>
    </div>
  );
}

function LlmPanel({ llmLogs }) {
  const [sel, setSel] = React.useState(0);
  const detailRef = React.useRef(null);
  React.useEffect(() => { if (detailRef.current) detailRef.current.scrollTop = 0; }, [sel]);
  if (llmLogs.length === 0) {
    return <EmptyState ico="cpu" title="No LLM calls" sub="This session has no LLM request logs." />;
  }

  const totals = llmLogs.reduce(
    (acc, r) => ({
      inputTokens: acc.inputTokens + (r.inputTokens || 0),
      outputTokens: acc.outputTokens + (r.outputTokens || 0),
      totalCost: acc.totalCost + (r.totalCost || 0),
    }),
    { inputTokens: 0, outputTokens: 0, totalCost: 0 }
  );

  const groups = [];
  llmLogs.forEach((r, idx) => {
    const last = groups[groups.length - 1];
    if (last && last.step === (r.instructions || '')) last.calls.push({ r, idx });
    else groups.push({ step: r.instructions || '', calls: [{ r, idx }] });
  });

  const cur = llmLogs[sel] || null;
  const decisionOf = (r) => {
    const t = (r.response || []).find((p) => p.kind === 'tool');
    return t ? t.tool : ((r.response || []).find((p) => p.kind === 'text') ? 'text reply' : r.label);
  };

  return (
    <div style={{ display: 'flex', gap: 20, alignItems: 'stretch', height: '100%', minHeight: 0, width: '100%', overflowX: 'auto' }}>
      <div tabIndex={0} style={{ flex: '0 0 auto', width: 300, outline: 'none', overflowY: 'auto', minHeight: 0 }}
        onKeyDown={(e) => listNavKeyDown(e, { index: sel, count: llmLogs.length, set: setSel })}>
        <div className="tb-card" style={{ padding: '10px 14px', marginBottom: 12 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-subtle)', marginBottom: 6 }}>Session totals · {llmLogs.length} calls</div>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <div><div className="tb-mono" style={{ fontSize: 13, fontWeight: 700 }}>{llmFmtN(totals.inputTokens)}</div><div className="tb-sub" style={{ fontSize: 10.5 }}>input tokens</div></div>
            <div><div className="tb-mono" style={{ fontSize: 13, fontWeight: 700 }}>{llmFmtN(totals.outputTokens)}</div><div className="tb-sub" style={{ fontSize: 10.5 }}>output tokens</div></div>
            <div><div className="tb-mono" style={{ fontSize: 13, fontWeight: 700 }}>{llmFmtCost(totals.totalCost)}</div><div className="tb-sub" style={{ fontSize: 10.5 }}>total cost</div></div>
          </div>
        </div>
        {groups.map((g, gi) => (
          <div key={gi} style={{ marginBottom: 14 }}>
            <div className="tb-eyebrow" title={g.step} style={{ marginBottom: 6, whiteSpace: 'normal', lineHeight: 1.5 }}>{g.step || 'Untitled step'}</div>
            {g.calls.map(({ r, idx }) => (
              <div
                key={idx}
                data-navrow
                onClick={() => setSel(idx)}
                className="tb-card"
                style={{ padding: '8px 11px', marginBottom: 5, cursor: 'pointer', background: sel === idx ? 'var(--bg-standard)' : 'var(--bg-subtle)', borderColor: sel === idx ? 'rgba(0,224,19,.45)' : 'var(--tb-hairline)' }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, flex: '0 0 auto' }}>{idx + 1}</span>
                  <Ico n="wrench" s={11} c="var(--text-subtle)" />
                  <span className="tb-mono" style={{ fontSize: 11.5, fontWeight: 600, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{decisionOf(r)}</span>
                </div>
                <div className="tb-sub" style={{ fontSize: 10.5, marginTop: 3 }}>
                  in {llmFmtN(r.inputTokens)} · out {llmFmtN(r.outputTokens)}{r.durationMs > 0 ? ` · ${(r.durationMs / 1000).toFixed(1)}s` : ''}
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
      {cur && (
        <div ref={detailRef} style={{ flex: 1, minWidth: 340, overflowY: 'auto', minHeight: 0 }}>
          <LlmCallDetail r={cur} idx={sel} total={llmLogs.length} />
        </div>
      )}
    </div>
  );
}

Object.assign(window, { LlmPanel, LlmCallDetail, Collapsible, classifyUserMessage, llmFmtCost, llmFmtN });

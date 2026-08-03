// @ts-nocheck -- matches the other screens (global-scope babel script; see tsconfig.check.json).
//
// Report-from-zip: renders the full interactive run report from a session archive URL — the
// per-session zips CI publishes to the results CDN, or any `trailblaze report`-shaped archive —
// with no daemon data. Deep-linkable: /trailrunner?zip=<url> (or #zip-report/<encoded-url>) boots
// straight into the rendered report.
//
// The pipeline is entirely client-side: fetch the zip (the host must allow cross-origin GET), then
// hand the bytes to TbZipReport.buildReportHtmlFromZipBytes — the SHARED zip-bytes → report-HTML
// assembly (zip-report-core.js) that the standalone static edition also uses, so the two homes
// can't drift. It parses + decompresses the archive, derives the run meta the Kotlin way, extracts
// the timeline via run-report-core.js, and emits the same HTML the CLI report and Share button do.

function ZipReportScreen({ initZipUrl }) {
  useLucide();
  const [url, setUrl] = React.useState(initZipUrl || '');
  const [phase, setPhase] = React.useState('idle'); // idle | loading | ready | error
  const [stage, setStage] = React.useState('');
  const [err, setErr] = React.useState(null);
  const [html, setHtml] = React.useState(null);
  const [stats, setStats] = React.useState(null);
  const runIdRef = React.useRef(0);

  const load = async (zipUrl) => {
    const target = String(zipUrl || '').trim();
    if (!target) return;
    const runId = ++runIdRef.current;
    setPhase('loading'); setErr(null); setHtml(null); setStats(null);
    setStage('Downloading archive…');
    try {
      let res;
      try {
        res = await fetch(target);
      } catch (e) {
        // A cross-origin fetch the host refuses surfaces as an opaque TypeError — say what to fix.
        throw new Error('Could not download the archive. Check the URL, and note the file\'s host ' +
          'must allow cross-origin requests (CORS) for a web page to read it.');
      }
      if (!res.ok) throw new Error(`Download failed (HTTP ${res.status})`);
      const bytes = new Uint8Array(await res.arrayBuffer());
      if (runId !== runIdRef.current) return;
      setStage('Reading sessions…');
      const built = await TbZipReport.buildReportHtmlFromZipBytes(bytes, {
        onStage: (s) => { if (runId === runIdRef.current) setStage(s); },
      });
      if (runId !== runIdRef.current) return;
      setStats({
        sessions: built.sessions.length,
        steps: built.sessions.reduce((n, s) => n + s.trace.length, 0),
        zipBytes: built.zipBytes,
      });
      setHtml(built.html);
      setPhase('ready');
    } catch (e) {
      if (runId !== runIdRef.current) return;
      setErr(String((e && e.message) || e));
      setPhase('error');
    }
  };

  React.useEffect(() => { if (initZipUrl) load(initZipUrl); }, []);

  const fmtSize = (n) => n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : (n / 1048576).toFixed(1) + ' MB';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="file-archive" s={16} c="var(--text-subtle)" />
        <span style={{ fontSize: 13, fontWeight: 700, whiteSpace: 'nowrap' }}>Report from zip</span>
        <input
          className="tb-mono"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') load(url); }}
          placeholder="https://…/runs/<build>-<job>-<session>.zip (or latest.zip)"
          spellCheck={false}
          style={{ flex: 1, minWidth: 120, fontSize: 12, padding: '7px 10px', borderRadius: 8, border: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', color: 'var(--text-standard)', outline: 'none' }}
        />
        <Btn sm kind="primary" ico="play" onClick={() => load(url)} disabled={phase === 'loading' || !url.trim()}>
          Render
        </Btn>
        {phase === 'ready' && stats && (
          <span className="tb-sub" style={{ fontSize: 11.5, whiteSpace: 'nowrap' }}>
            {stats.sessions === 1 ? `${stats.steps} steps` : `${stats.sessions} sessions`} · {fmtSize(stats.zipBytes)}
          </span>
        )}
      </div>

      {phase === 'idle' && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', flex: 1, gap: 10, color: 'var(--text-subtle)' }}>
          <Ico n="file-archive" s={34} c="var(--text-subtle)" />
          <div style={{ fontSize: 13, fontWeight: 600 }}>Render a report from a session archive</div>
          <div style={{ fontSize: 12, maxWidth: 460, textAlign: 'center', lineHeight: 1.5 }}>
            Paste the URL of a Trailblaze session zip (e.g. a CI-published results archive) and every
            log, screenshot, and step timeline in it renders right here — no daemon data needed.
          </div>
        </div>
      )}

      {phase === 'loading' && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 1, gap: 10, fontSize: 12.5, color: 'var(--text-subtle)' }}>
          <Ico n="loader-2" s={15} c="var(--tb-running)" spin />
          {stage}
        </div>
      )}

      {phase === 'error' && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', flex: 1, gap: 10, padding: 24 }}>
          <Ico n="alert-triangle" s={22} c="var(--tb-fail)" />
          <div style={{ fontSize: 12.5, color: 'var(--tb-fail)', maxWidth: 560, textAlign: 'center', lineHeight: 1.5 }} data-selectable>{err}</div>
        </div>
      )}

      {phase === 'ready' && html != null && (
        <iframe
          title="Trailblaze run report"
          srcDoc={html}
          style={{ flex: 1, width: '100%', border: 'none', background: '#0d0d0d' }}
        />
      )}
    </div>
  );
}

Object.assign(window, { ZipReportScreen });

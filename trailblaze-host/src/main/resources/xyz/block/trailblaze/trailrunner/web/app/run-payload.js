// Assembles a run-report payload from LIVE daemon data. Two producers need that payload and differ
// only in how the screenshots are carried:
//
//   - the in-app Share button (share-export.tsx) EMBEDS each frame as a data URI, so the saved
//     .html is one portable file that opens with no daemon;
//   - the daemon-served live report document (report-live.html) LINKS each frame to `/static/`, so
//     the page renders without downloading a byte of image data.
//
// Everything else — the run `meta`, the recording/original YAML, the event streams — is identical,
// so it lives here once instead of being re-derived per producer. The screenshot file list comes
// from run-report-core's traceScreenshotFiles, the same walk the CLI and zip report generators use.
//
// Plain JS (classic <script>, no transpile step) with a CommonJS tail so app/run-payload.test.ts can
// require it. run-report-core's globals and `fetch` are resolved off the global scope by default and
// overridable through the trailing `deps` argument — that is what the unit tests inject.
(function () {
  'use strict';

  function resolve(overrides) {
    var g = typeof globalThis !== 'undefined' ? globalThis : {};
    var o = overrides || {};
    return {
      fetch: o.fetch || (typeof g.fetch === 'function' ? g.fetch.bind(g) : null),
      traceScreenshotFiles: o.traceScreenshotFiles || g.traceScreenshotFiles,
      packSessionInputsHierarchies: o.packSessionInputsHierarchies || g.packSessionInputsHierarchies,
      originalYamlFromLogs: o.originalYamlFromLogs || g.originalYamlFromLogs,
    };
  }

  function apiUrl(sessionId, suffix) {
    return '/trailrunner/api/session/' + encodeURIComponent(sessionId) + '/' + suffix;
  }

  // encodeURIComponent leaves `'` alone, and the report's own image-src check rejects a quote rather
  // than reason about the attribute it would land in, so a frame from a file with an apostrophe in
  // its name would silently not render. Encode it here.
  function staticUrl(sessionId, file) {
    var part = function (v) { return encodeURIComponent(v).replace(/'/g, '%27'); };
    return '/static/' + part(sessionId) + '/' + part(file);
  }

  function humanDuration(ms) {
    if (!ms || ms < 0) return '—';
    if (ms < 1000) return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
    var m = Math.floor(ms / 60000);
    var s = Math.round((ms - m * 60000) / 1000);
    return m + 'm ' + s + 's';
  }

  // A run's log records in the order the report is derived from: by each record's own timestamp.
  //
  // The daemon serves them in log-FILENAME order instead, and the two disagree — a driver action and
  // the tool call it belongs to are written under sequential filenames but land microseconds apart,
  // sometimes in the other order. Filename order therefore splits a pair the extractor would have
  // folded into one step, and the same run reads as more steps here than in its exported report,
  // which is built from a timestamp-sorted snapshot (SessionLogSnapshot). Sorting here is what makes
  // the two agree.
  //
  // Stable, and records with no parseable timestamp keep their filename order and sort first — the
  // same rule the snapshot applies, because a hex-prefixed log filename carries no sequence at all.
  function orderLogsForExtraction(records) {
    return (records || []).map(function (r, i) {
      return { r: r, i: i, t: r && typeof r.timestamp === 'string' ? instantKey(r.timestamp) : null };
    }).sort(function (a, b) {
      if (a.t === null || b.t === null) return a.t === b.t ? a.i - b.i : (a.t === null ? -1 : 1);
      if (a.t.ms !== b.t.ms) return a.t.ms - b.t.ms;
      if (a.t.sub !== b.t.sub) return a.t.sub - b.t.sub;
      return a.i - b.i;
    }).map(function (x) { return x.r; });
  }

  // An ISO-8601 instant as a sortable { ms, sub } pair. Date.parse stops at the millisecond, but the
  // records this sort exists to put in order — a driver action and the tool call it belongs to —
  // land microseconds apart, so a millisecond-only key calls them equal and the tiebreak falls back
  // to the very filename order being corrected. `sub` carries the digits past the millisecond.
  function instantKey(ts) {
    var ms = Date.parse(ts);
    if (isNaN(ms)) return null;
    var frac = /\.(\d+)/.exec(ts);
    return { ms: ms, sub: frac ? Number((frac[1] + '000000000').slice(3, 9)) : 0 };
  }

  // `/api/sessions` wire DTO → the UI-shaped session object the report producers read. The React app
  // gets this shape from useSessions(); a document without the React app (report-live.html) produces
  // the SAME shape here, so runMeta below has exactly one input contract.
  function normalizeSummary(wire) {
    var w = wire || {};
    return {
      id: w.id,
      title: w.title || w.id,
      status: w.status || 'unknown',
      target: w.target || '',
      device: w.device || w.platform || '',
      platform: w.platform || null,
      appId: w.appId || null,
      appVersionName: w.appVersionName || null,
      appVersionCode: w.appVersionCode || null,
      appBuildNumber: w.appBuildNumber || null,
      dur: humanDuration(w.durationMs),
      ago: w.timestampMs ? new Date(w.timestampMs).toLocaleString() : '',
      err: w.error || null,
      trailId: w.trailId || null,
      metadata: w.metadata || null,
      imported: !!w.imported,
      timestampMs: w.timestampMs || 0,
    };
  }

  // Pure: session summary + trace + side channels → the report's `meta`.
  function runMeta(args) {
    var a = args || {};
    var s = a.s || {};
    var trace = a.trace || [];
    // The report's vocabulary for a self-healed run is a PASSING run carrying a marker, which is
    // what RunReportGenerator emits for one. The daemon instead reports a status of its own
    // ("healed"), which the viewer recognizes as no outcome at all, and a run that self-healed but
    // ended on a plain pass says nothing in its status either. So read the same two sources the CLI
    // report reads: the status, and the self-heal the trace itself recorded.
    var healed = String(s.status || '').toLowerCase() === 'healed';
    var selfHealed = healed || trace.some(function (t) { return !!(t && t.selfHeal); });
    return {
      title: s.title || s.id || 'Trailblaze run',
      status: healed ? 'passed' : (s.status || 'unknown'),
      ...(selfHealed ? { selfHeal: true } : {}),
      target: s.target || null,
      appId: s.appId || null,
      // "5.58.0.0 (67500009)" — same display rule as the Info tab and RunReportGenerator.
      appVersion: s.appVersionName
        ? s.appVersionName + ((s.appBuildNumber || s.appVersionCode) ? ' (' + (s.appBuildNumber || s.appVersionCode) + ')' : '')
        : (s.appBuildNumber || s.appVersionCode || null),
      device: s.device || null,
      platform: s.platform || null,
      duration: s.dur || null,
      ranAt: s.timestampMs ? new Date(s.timestampMs).toLocaleString() : (s.ago || null),
      steps: trace.length,
      trailId: s.trailId || null,
      ...(s.metadata && Object.keys(s.metadata).length ? { metadata: s.metadata } : {}),
      cmd: a.cmd || null,
      error: s.err || null,
      recordingYaml: a.recordingYaml || null,
      originalYaml: a.originalYaml || null,
      generatedAt: a.generatedAt || new Date().toLocaleString(),
    };
  }

  // Best-effort CLI command to reproduce a run, which the report offers on its Info tab. Replays of
  // an existing trail map to `trailblaze run <file>`; ad-hoc objectives (Blaze) map to
  // `trailblaze step "<objective>"`. Device is platform-only — the per-device id isn't persisted on
  // the session record.
  function cliRerunCommand(s, sourceTrail) {
    var dev = s.platform ? ' --device ' + s.platform : '';
    if (s.trailId) {
      // `run` reads the target from the trail file's own config.target — it has no --target flag
      // (that lives on `step`/`tool`), so we don't pass one here.
      var path = (sourceTrail && sourceTrail.path) || s.trailId;
      return 'trailblaze run ' + path + dev;
    }
    // Ad-hoc objective (Blaze): `step` takes the target explicitly. Escape backslashes before quotes
    // so the double-quoted shell argument can't be broken out of.
    var tgt = s.target ? ' --target ' + s.target : '';
    var objective = (s.title || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    return 'trailblaze step "' + objective + '"' + dev + tgt;
  }

  // The same command for a caller holding only the session summary. A trail's FILE PATH lives in the
  // trails index rather than on the session record, so resolve it there; a failed lookup falls back
  // to the trail's id, exactly as the app's own Info panel does before its index has loaded.
  async function fetchRerunCommand(summary, deps) {
    var s = summary || {};
    if (!s.trailId) return cliRerunCommand(s, null);
    try {
      var res = await resolve(deps).fetch('/trailrunner/api/trails');
      if (!res.ok) return cliRerunCommand(s, null);
      var raw = await res.json();
      var trail = ((raw && raw.trails) || []).filter(function (t) { return t && t.id === s.trailId; })[0];
      return cliRerunCommand(s, trail || null);
    } catch (e) { return cliRerunCommand(s, null); }
  }

  // Chunked so a multi-hundred-KB screenshot can't blow the argument limit of a single apply().
  function base64(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(out);
  }

  async function fetchAsDataUrl(url, fetchFn) {
    try {
      var res = await fetchFn(url);
      if (!res || !res.ok) return null;
      var buf = await res.arrayBuffer();
      var type = String((res.headers && res.headers.get('content-type')) || '').split(';')[0].trim().toLowerCase();
      return 'data:' + (type || 'image/png') + ';base64,' + base64(new Uint8Array(buf));
    } catch (e) { return null; }
  }

  // Every screenshot the trace references (parents plus folded children, deduped by filename) as a
  // { filename -> src } map. `mode`:
  //   'embed' fetches the bytes and inlines them as data URIs, reporting onProgress(done, total);
  //   'link'  points at the daemon's /static/ tree and fetches nothing — a linked report must not
  //           download the images, that is the whole point of linking them.
  async function collectShots(trace, sessionId, mode, onProgress, deps) {
    var d = resolve(deps);
    var files = d.traceScreenshotFiles(trace);
    var shots = {};
    if (mode === 'link') {
      files.forEach(function (f) { shots[f] = staticUrl(sessionId, f); });
      return shots;
    }
    var done = 0;
    if (onProgress) onProgress(0, files.length);
    for (var i = 0; i < files.length; i++) {
      var data = await fetchAsDataUrl(staticUrl(sessionId, files[i]), d.fetch);
      if (data) shots[files[i]] = data;
      done++;
      if (onProgress) onProgress(done, files.length);
    }
    return shots;
  }

  // Best-effort fetch of the session's recorded .trail.yaml so the report's Recording tab matches
  // the headless `trailblaze report` output. Failure is non-fatal — the tab just won't show.
  async function fetchRecordingYaml(sessionId, d) {
    try {
      var res = await d.fetch(apiUrl(sessionId, 'export'));
      if (!res.ok) return null;
      var text = await res.text();
      return text && text.trim() ? text : null;
    } catch (e) { return null; }
  }

  async function fetchOriginalYaml(sessionId, d, logs) {
    try {
      if (logs) return d.originalYamlFromLogs(logs);
      var res = await d.fetch(apiUrl(sessionId, 'logs'));
      if (!res.ok) return null;
      return d.originalYamlFromLogs(await res.json());
    } catch (e) { return null; }
  }

  // Normalize the live route's generic event-stream DTO into the compact report shape. This carries
  // generic plugin event streams from any producer into the report.
  async function fetchReportEvents(sessionId, d) {
    try {
      var res = await d.fetch(apiUrl(sessionId, 'events'));
      if (!res.ok) return null;
      var raw = await res.json();
      var streams = ((raw && raw.streams) || []).map(function (s) {
        return {
          name: s.label || s.streamId,
          total: s.count || (s.events || []).length,
          truncated: !!s.truncated,
          events: (s.events || []).map(function (e) {
            return { t: e.timeMs == null ? null : e.timeMs, d: JSON.stringify(e.data == null ? e : e.data) };
          }),
        };
      });
      return streams.length ? streams : null;
    } catch (e) { return null; }
  }

  // The app's analytics capture (Trail Runner interleaves these into its timeline as their own
  // category) as one more report event stream, so embedding the report doesn't lose them. Same
  // stream shape as the generic streams above; the analytics DTO's own fields land in the row
  // payload the reader expands.
  async function fetchAnalyticsStream(sessionId, d) {
    try {
      var res = await d.fetch(apiUrl(sessionId, 'analytics'));
      if (!res.ok) return null;
      var raw = await res.json();
      var events = (raw && raw.events) || [];
      if (!events.length) return null;
      return {
        name: 'Analytics',
        total: events.length,
        truncated: false,
        events: events.map(function (e) {
          return {
            t: e.timeMs == null ? null : e.timeMs,
            // Properties first: they are app-supplied, so a property called `name` or `source` must
            // not take the place of the event's own identity in what the reader sees.
            d: JSON.stringify({ ...(e.properties || {}), name: e.name, source: e.source || null }),
          };
        }),
      };
    } catch (e) { return null; }
  }

  // The side channels, in parallel, each failing soft to null. `logs` lets a caller that has already
  // fetched the session's log records (the live document derives its trace from them) reuse them
  // instead of downloading the same — potentially very large — payload a second time.
  //
  // `withRecordingYaml` (default true) is what a caller rebuilding the SAME run over and over turns
  // off: /export re-derives the recording from the whole session on every request, which is real
  // daemon work to produce a recording of a run that hasn't finished being recorded. The live
  // document asks for it once, on the build that follows the run's terminal status.
  async function fetchSideChannels(sessionId, deps, logs, withRecordingYaml) {
    var d = resolve(deps);
    var out = await Promise.all([
      withRecordingYaml === false ? null : fetchRecordingYaml(sessionId, d),
      fetchOriginalYaml(sessionId, d, logs),
      fetchReportEvents(sessionId, d),
      fetchAnalyticsStream(sessionId, d),
    ]);
    var events = (out[2] || []).concat(out[3] ? [out[3]] : []);
    return { recordingYaml: out[0], originalYaml: out[1], events: events.length ? events : null };
  }

  // The full run-report session input: `{ meta, trace, llmLogs, shots, events }`, ready for
  // buildRunReportHtml (the exported file) or toSessionPayloads (a served document).
  async function buildSessionInput(args) {
    var a = args || {};
    var mode = a.mode === 'link' ? 'link' : 'embed';
    var shots = await collectShots(a.trace, a.sessionId, mode, a.onProgress, a.deps);
    var side = await fetchSideChannels(a.sessionId, a.deps, a.logs, a.withRecordingYaml);
    var input = {
      meta: runMeta({
        s: a.s,
        trace: a.trace,
        cmd: a.cmd,
        recordingYaml: side.recordingYaml,
        originalYaml: side.originalYaml,
        generatedAt: a.generatedAt,
      }),
      trace: a.trace,
      llmLogs: a.llmLogs,
      shots: shots,
      events: side.events,
    };
    // 'embed' compresses the per-step view hierarchies into the same gz side-channel the CLI-built
    // report carries: inline hierarchies would otherwise dominate the exported file's size AND be
    // JSON.parse'd every time that file opens.
    //
    // 'link' must NOT pack. The live document is served over localhost, so size is irrelevant, and
    // the viewer's inflaters cache the inflated result against the session object — a gz channel
    // would become a staleness hazard the moment this document starts refreshing a running session.
    if (mode === 'embed') {
      var pack = resolve(a.deps).packSessionInputsHierarchies;
      if (typeof pack === 'function') await pack([input]);
    }
    return input;
  }

  var api = {
    orderLogsForExtraction: orderLogsForExtraction,
    normalizeSummary: normalizeSummary,
    runMeta: runMeta,
    cliRerunCommand: cliRerunCommand,
    fetchRerunCommand: fetchRerunCommand,
    collectShots: collectShots,
    fetchSideChannels: fetchSideChannels,
    buildSessionInput: buildSessionInput,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbRunPayload = api;             // browser classic script
})();

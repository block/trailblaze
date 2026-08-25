// Behavior tests for the shared run-report payload assembler (app/run-payload.js): what a LINKED
// report carries vs what an EMBEDDED one carries, and how a live session summary maps onto the
// report's `meta`. No DOM and no network — `fetch` and the run-report-core globals the module reads
// are injected through the `deps` argument, so a linked build can be asserted to fetch nothing.
//
// Run: `bun test app/run-payload.test.ts` from the web/ directory.
import { describe, expect, test } from 'bun:test';
// run-payload.js dual-exports via module.exports; bun interops the CJS default import.
import Payload from './run-payload.js';

// The real run-report-core walk (traceScreenshotFiles): each row's frame plus every folded child's,
// deduped, order preserved.
const traceScreenshotFiles = (trace: any[]) => [...new Set((trace || [])
  .flatMap((t) => [t.screenshotFile, ...(t.children || []).map((c: any) => c.screenshotFile)])
  .filter(Boolean))];

const TRACE = [
  { i: 0, screenshotFile: 'a.png', children: [{ screenshotFile: 'a-child.png' }, { screenshotFile: null }] },
  { i: 1, screenshotFile: 'b.png' },
  { i: 2, screenshotFile: 'a.png', children: [{ screenshotFile: 'c.png' }] },
  { i: 3, screenshotFile: null, children: [] },
];

/** A fetch that fails the test if anything calls it, plus the call log to assert emptiness. */
function forbiddenFetch() {
  const calls: string[] = [];
  return {
    calls,
    fetch: (url: string) => { calls.push(url); throw new Error(`unexpected fetch: ${url}`); },
  };
}

/** A fetch that answers /static/ image requests with 3 known bytes and 404s everything else. */
function imageFetch(contentType = 'image/png') {
  const calls: string[] = [];
  return {
    calls,
    fetch: async (url: string) => {
      calls.push(url);
      if (!url.startsWith('/static/')) return { ok: false, status: 404 };
      return {
        ok: true,
        status: 200,
        headers: { get: (h: string) => (h.toLowerCase() === 'content-type' ? `${contentType}; charset=binary` : null) },
        arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer,
      };
    },
  };
}

describe('collectShots — link mode', () => {
  test('points every referenced frame, folded children included, at /static/ and fetches nothing', async () => {
    const net = forbiddenFetch();
    const shots = await Payload.collectShots(TRACE, 'sess 42', 'link', null, {
      fetch: net.fetch,
      traceScreenshotFiles,
    });

    expect(shots).toEqual({
      'a.png': '/static/sess%2042/a.png',
      'a-child.png': '/static/sess%2042/a-child.png',
      'b.png': '/static/sess%2042/b.png',
      'c.png': '/static/sess%2042/c.png',
    });
    expect(net.calls).toEqual([]);
  });

  // The report's image-src check refuses a URL containing a quote, so an apostrophe left unencoded
  // here would mean the frame simply never renders.
  test('encodes an apostrophe in a session or file name, which the report would otherwise refuse', async () => {
    const shots = await Payload.collectShots(
      [{ i: 0, screenshotFile: "steven's shot.png" }],
      "sam's run",
      'link',
      null,
      { fetch: forbiddenFetch().fetch, traceScreenshotFiles },
    );
    expect(shots["steven's shot.png"]).toBe('/static/sam%27s%20run/steven%27s%20shot.png');
  });

  test('reports no progress — there is nothing to wait for', async () => {
    const progress: number[][] = [];
    await Payload.collectShots(TRACE, 's1', 'link', (done: number, total: number) => progress.push([done, total]), {
      fetch: forbiddenFetch().fetch,
      traceScreenshotFiles,
    });
    expect(progress).toEqual([]);
  });
});

describe('collectShots — embed mode', () => {
  test('inlines each frame as a data URI carrying the served content type', async () => {
    const net = imageFetch('image/jpeg');
    const shots = await Payload.collectShots(TRACE, 's1', 'embed', null, {
      fetch: net.fetch,
      traceScreenshotFiles,
    });

    expect(Object.keys(shots).sort()).toEqual(['a-child.png', 'a.png', 'b.png', 'c.png']);
    expect(shots['a.png']).toBe(`data:image/jpeg;base64,${btoa('\x01\x02\x03')}`);
    // One request per DEDUPED file, not per trace row (a.png appears on two rows).
    expect(net.calls).toEqual([
      '/static/s1/a.png', '/static/s1/a-child.png', '/static/s1/b.png', '/static/s1/c.png',
    ]);
  });

  test('reports progress from 0 to the total frame count', async () => {
    const progress: number[][] = [];
    await Payload.collectShots(TRACE, 's1', 'embed', (done: number, total: number) => progress.push([done, total]), {
      fetch: imageFetch().fetch,
      traceScreenshotFiles,
    });
    expect(progress).toEqual([[0, 4], [1, 4], [2, 4], [3, 4], [4, 4]]);
  });

  test('drops a frame the daemon can no longer serve instead of failing the whole export', async () => {
    const shots = await Payload.collectShots(TRACE, 's1', 'embed', null, {
      fetch: async (url: string) => (url.endsWith('b.png') ? { ok: false, status: 404 } : {
        ok: true,
        headers: { get: () => 'image/png' },
        arrayBuffer: async () => new Uint8Array([9]).buffer,
      }),
      traceScreenshotFiles,
    });
    expect('b.png' in shots).toBe(false);
    expect(Object.keys(shots).sort()).toEqual(['a-child.png', 'a.png', 'c.png']);
  });
});

describe('normalizeSummary', () => {
  test('maps the /api/sessions wire DTO onto the UI session shape', () => {
    const at = Date.UTC(2026, 7, 20, 15, 4, 5);
    expect(Payload.normalizeSummary({
      id: 'sess_1',
      title: 'Add item to cart',
      status: 'passed',
      durationMs: 95_500,
      timestampMs: at,
      platform: 'IOS',
      device: 'iPhone 16',
      target: 'demo-ios',
      appId: 'com.example.shop',
      appVersionName: '5.58.0.0',
      appVersionCode: 67_500_009,
      appBuildNumber: '67500009',
      error: null,
      trailId: 'trails/add-to-cart',
      imported: true,
      metadata: { branch: 'main' },
    })).toEqual({
      id: 'sess_1',
      title: 'Add item to cart',
      status: 'passed',
      target: 'demo-ios',
      device: 'iPhone 16',
      platform: 'IOS',
      appId: 'com.example.shop',
      appVersionName: '5.58.0.0',
      appVersionCode: 67_500_009,
      appBuildNumber: '67500009',
      dur: '1m 36s',
      ago: new Date(at).toLocaleString(),
      err: null,
      trailId: 'trails/add-to-cart',
      metadata: { branch: 'main' },
      imported: true,
      timestampMs: at,
    });
  });

  test('falls back for a bare summary: title from id, device from platform, no duration', () => {
    const s = Payload.normalizeSummary({ id: 'sess_2', platform: 'ANDROID' });
    expect(s.title).toBe('sess_2');
    expect(s.device).toBe('ANDROID');
    expect(s.status).toBe('unknown');
    expect(s.dur).toBe('—');
    expect(s.ago).toBe('');
    expect(s.timestampMs).toBe(0);
    expect(s.imported).toBe(false);
  });

  test('formats sub-second and sub-minute durations', () => {
    expect(Payload.normalizeSummary({ id: 'x', durationMs: 420 }).dur).toBe('420ms');
    expect(Payload.normalizeSummary({ id: 'x', durationMs: 4200 }).dur).toBe('4.2s');
  });
});

// The command the report's Info tab offers as "Rerun this in the CLI". The app has the trails index
// in hand; a document that has only the session summary has to go and resolve the trail's path.
describe('fetchRerunCommand', () => {
  const index = (trails: unknown[]) => async (url: string) => (url === '/trailrunner/api/trails'
    ? { ok: true, json: async () => ({ trails }) }
    : { ok: false, status: 404 });

  test('names the trail FILE, which lives in the index rather than on the session', async () => {
    const cmd = await Payload.fetchRerunCommand({ trailId: '0/checkout/pay', platform: 'IOS' }, {
      fetch: index([{ id: '0/checkout/pay', path: 'trails/checkout/pay.trail.yaml' }]),
    });
    expect(cmd).toBe('trailblaze run trails/checkout/pay.trail.yaml --device IOS');
  });

  test('falls back to the trail id when the index cannot be read', async () => {
    const cmd = await Payload.fetchRerunCommand({ trailId: '0/checkout/pay' }, {
      fetch: async () => { throw new Error('daemon went away'); },
    });
    expect(cmd).toBe('trailblaze run 0/checkout/pay');
  });

  test('an ad-hoc objective becomes a `step` command carrying its target, quotes escaped', async () => {
    const net = forbiddenFetch();
    const cmd = await Payload.fetchRerunCommand(
      { title: 'tap the "Pay" button', target: 'demo-ios', platform: 'IOS' },
      { fetch: net.fetch },
    );
    expect(cmd).toBe('trailblaze step "tap the \\"Pay\\" button" --device IOS --target demo-ios');
    // No trail to resolve, so there is nothing to ask the daemon for.
    expect(net.calls).toEqual([]);
  });
});

describe('runMeta', () => {
  const summary = {
    id: 'sess_1',
    title: 'Add item to cart',
    status: 'failed',
    target: 'demo-ios',
    device: 'iPhone 16',
    platform: 'IOS',
    appId: 'com.example.shop',
    appVersionName: '5.58.0.0',
    appBuildNumber: '67500009',
    dur: '1m 36s',
    err: 'assertion failed',
    trailId: 'trails/add-to-cart',
    metadata: {},
    timestampMs: Date.UTC(2026, 7, 20, 15, 4, 5),
  };

  test('renders the app version as "name (build)"', () => {
    expect(Payload.runMeta({ s: summary, trace: TRACE }).appVersion).toBe('5.58.0.0 (67500009)');
  });

  test('renders a bare version name when there is no build number or code', () => {
    const s = { ...summary, appBuildNumber: null, appVersionCode: null };
    expect(Payload.runMeta({ s, trace: TRACE }).appVersion).toBe('5.58.0.0');
  });

  test('falls back to the version code when there is no name', () => {
    const s = { ...summary, appVersionName: null, appBuildNumber: null, appVersionCode: 67_500_009 };
    expect(Payload.runMeta({ s, trace: TRACE }).appVersion).toBe(67_500_009);
  });

  test('omits `metadata` entirely when the session carries none', () => {
    expect('metadata' in Payload.runMeta({ s: summary, trace: TRACE })).toBe(false);
    expect('metadata' in Payload.runMeta({ s: { ...summary, metadata: null }, trace: TRACE })).toBe(false);
    expect(Payload.runMeta({ s: { ...summary, metadata: { ci: '42' } }, trace: TRACE }).metadata).toEqual({ ci: '42' });
  });

  test('maps status, error, duration, ranAt, step count and the side channels', () => {
    const meta = Payload.runMeta({
      s: summary,
      trace: TRACE,
      cmd: 'trailblaze run x.trail.yaml',
      recordingYaml: 'trail: []',
      originalYaml: 'steps: []',
      generatedAt: 'Aug 20, 2026, 8:00 AM',
    });
    expect(meta.title).toBe('Add item to cart');
    expect(meta.status).toBe('failed');
    expect(meta.error).toBe('assertion failed');
    expect(meta.duration).toBe('1m 36s');
    expect(meta.ranAt).toBe(new Date(summary.timestampMs).toLocaleString());
    expect(meta.steps).toBe(TRACE.length);
    expect(meta.cmd).toBe('trailblaze run x.trail.yaml');
    expect(meta.recordingYaml).toBe('trail: []');
    expect(meta.originalYaml).toBe('steps: []');
    expect(meta.generatedAt).toBe('Aug 20, 2026, 8:00 AM');
  });

  // The daemon has a status the report has no notion of, and the exported report of the same run
  // says `passed` + SELF-HEALED. These pin the translation in both directions.
  test('a self-healed run reads as a pass carrying the marker, like the exported report', () => {
    const meta = Payload.runMeta({ s: { ...summary, status: 'healed' }, trace: TRACE });
    expect(meta.status).toBe('passed');
    expect(meta.selfHeal).toBe(true);
  });

  test('a self-heal the trace recorded is marked even when the status is a plain pass', () => {
    const trace = [{ i: 0, selfHeal: true, selfHealTool: 'tapPay' }, { i: 1 }];
    expect(Payload.runMeta({ s: { ...summary, status: 'passed' }, trace }).selfHeal).toBe(true);
  });

  test('a run that never self-healed omits the marker, which the viewer reads as its absence', () => {
    expect('selfHeal' in Payload.runMeta({ s: { ...summary, status: 'passed' }, trace: TRACE })).toBe(false);
  });

  test('uses the relative `ago` label only when the run has no timestamp', () => {
    const meta = Payload.runMeta({ s: { ...summary, timestampMs: 0, ago: '3h ago' }, trace: [] });
    expect(meta.ranAt).toBe('3h ago');
    expect(meta.steps).toBe(0);
  });
});

describe('fetchSideChannels', () => {
  const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

  test('a 404 export yields a null recordingYaml rather than throwing', async () => {
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        if (url.endsWith('/export')) return { ok: false, status: 404, text: async () => 'nope' };
        if (url.endsWith('/events')) return okJson({ streams: [] });
        return okJson([]);
      },
      originalYamlFromLogs: () => null,
    });
    expect(side).toEqual({ recordingYaml: null, originalYaml: null, events: null });
  });

  test('a thrown request yields nulls rather than rejecting', async () => {
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: () => { throw new Error('daemon went away'); },
      originalYamlFromLogs: () => null,
    });
    expect(side).toEqual({ recordingYaml: null, originalYaml: null, events: null });
  });

  test('flattens the event-stream DTO and derives the original YAML from the logs', async () => {
    const calls: string[] = [];
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        calls.push(url);
        if (url.endsWith('/export')) return { ok: true, text: async () => 'trail:\n  - x\n' };
        if (url.endsWith('/events')) {
          return okJson({
            streams: [{
              streamId: 'network', label: 'Network', count: 2, truncated: true,
              events: [{ timeMs: 12, data: { url: '/a' } }, { timeMs: null, data: null }],
            }],
          });
        }
        return okJson([{ kind: 'log' }]);
      },
      originalYamlFromLogs: (logs: any[]) => `logs:${logs.length}`,
    }, [{ kind: 'log' }, { kind: 'log' }]);

    expect(side.recordingYaml).toBe('trail:\n  - x\n');
    // The caller's already-fetched logs are reused: no second /logs round trip.
    expect(side.originalYaml).toBe('logs:2');
    expect(calls.some((u) => u.endsWith('/logs'))).toBe(false);
    expect(side.events).toEqual([{
      name: 'Network',
      total: 2,
      truncated: true,
      events: [{ t: 12, d: '{"url":"/a"}' }, { t: null, d: '{"timeMs":null,"data":null}' }],
    }]);
  });

  // Trail Runner's own timeline interleaves captured analytics as their own category, so a report
  // that replaces that timeline has to carry them or the run loses evidence it used to show.
  test('carries captured analytics as a stream of their own, after the generic ones', async () => {
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        if (url.endsWith('/export')) return { ok: false, status: 404 };
        if (url.endsWith('/events')) return okJson({ streams: [{ streamId: 'network', label: 'Network', count: 0, events: [] }] });
        if (url.endsWith('/analytics')) {
          return okJson({
            available: true,
            events: [{ id: 'a1', name: 'ItemViewed', timeMs: 40, source: 'app', properties: { sku: '12' } }],
          });
        }
        return okJson([]);
      },
      originalYamlFromLogs: () => null,
    }, []);

    expect(side.events!.map((s: any) => s.name)).toEqual(['Network', 'Analytics']);
    expect(side.events![1]).toEqual({
      name: 'Analytics',
      total: 1,
      truncated: false,
      events: [{ t: 40, d: '{"sku":"12","name":"ItemViewed","source":"app"}' }],
    });
  });

  // The app chooses its own property names, so `name` and `source` can collide with the two fields
  // that say WHICH event this is. The event's own identity has to win.
  test('an app property named like a canonical field cannot displace the event identity', async () => {
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        if (url.endsWith('/export')) return { ok: false, status: 404 };
        if (url.endsWith('/events')) return okJson({ streams: [] });
        if (url.endsWith('/analytics')) {
          return okJson({
            events: [{
              name: 'ItemViewed',
              timeMs: 40,
              source: 'app',
              properties: { name: 'spoofed', source: 'spoofed' },
            }],
          });
        }
        return okJson([]);
      },
      originalYamlFromLogs: () => null,
    }, []);

    expect(JSON.parse(side.events![0].events[0].d)).toEqual({ name: 'ItemViewed', source: 'app' });
  });

  test('a run with no analytics provider wired adds no empty stream', async () => {
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        if (url.endsWith('/export')) return { ok: false, status: 404 };
        if (url.endsWith('/events')) return okJson({ streams: [] });
        if (url.endsWith('/analytics')) return okJson({ available: false, events: [] });
        return okJson([]);
      },
      originalYamlFromLogs: () => null,
    }, []);
    expect(side.events).toBeNull();
  });

  // /export re-derives a run's recording from the whole session, so a caller that rebuilds the same
  // run every few hundred milliseconds while it executes has to be able to say "not this time".
  test('skips the recording export when the caller opts out, and asks for it when it does not', async () => {
    const calls: string[] = [];
    const deps = {
      fetch: async (url: string) => {
        calls.push(url);
        if (url.endsWith('/export')) return { ok: true, text: async () => 'trail: []' };
        if (url.endsWith('/events')) return okJson({ streams: [] });
        return okJson([]);
      },
      originalYamlFromLogs: () => 'steps: []',
    };

    const live = await Payload.fetchSideChannels('sess_1', deps, [], false);
    expect(calls.some((u) => u.endsWith('/export'))).toBe(false);
    expect(live.recordingYaml).toBeNull();
    // The authored trail still comes from the logs, so opting out costs the reader nothing else.
    expect(live.originalYaml).toBe('steps: []');

    const done = await Payload.fetchSideChannels('sess_1', deps, [], true);
    expect(calls.some((u) => u.endsWith('/export'))).toBe(true);
    expect(done.recordingYaml).toBe('trail: []');
  });

  test('fetches the logs itself when the caller has none in hand', async () => {
    const calls: string[] = [];
    const side = await Payload.fetchSideChannels('sess_1', {
      fetch: async (url: string) => {
        calls.push(url);
        if (url.endsWith('/export')) return { ok: false, status: 404 };
        if (url.endsWith('/events')) return okJson({ streams: [] });
        return okJson([{ kind: 'log' }]);
      },
      originalYamlFromLogs: (logs: any[]) => `logs:${logs.length}`,
    });
    expect(calls).toContain('/trailrunner/api/session/sess_1/logs');
    expect(side.originalYaml).toBe('logs:1');
  });
});

describe('buildSessionInput', () => {
  const sideChannelFetch = (calls: string[]) => async (url: string) => {
    calls.push(url);
    if (url.startsWith('/static/')) {
      return { ok: true, headers: { get: () => 'image/png' }, arrayBuffer: async () => new Uint8Array([7]).buffer };
    }
    if (url.endsWith('/export')) return { ok: true, text: async () => 'trail: []' };
    if (url.endsWith('/events')) return { ok: true, json: async () => ({ streams: [] }) };
    return { ok: true, json: async () => [] };
  };

  const summary = { id: 'sess_1', title: 'Run', status: 'passed', dur: '2.0s', timestampMs: 0 };

  test('link mode leaves the hierarchies unpacked — no *Gz side channel on the input', async () => {
    const calls: string[] = [];
    let packed = 0;
    const input = await Payload.buildSessionInput({
      s: summary,
      trace: TRACE,
      llmLogs: [{ i: 0 }],
      sessionId: 'sess_1',
      mode: 'link',
      logs: [{ kind: 'log' }],
      deps: {
        fetch: sideChannelFetch(calls),
        traceScreenshotFiles,
        originalYamlFromLogs: () => 'steps: []',
        packSessionInputsHierarchies: async () => { packed++; },
      },
    });

    expect(packed).toBe(0);
    expect(Object.keys(input).filter((k) => k.endsWith('Gz'))).toEqual([]);
    expect(input.hierarchies).toBeUndefined();
    expect(input.hierarchiesGz).toBeUndefined();
    expect(input.shots['a.png']).toBe('/static/sess_1/a.png');
    expect(calls.some((u) => u.startsWith('/static/'))).toBe(false);
    expect(input.meta.title).toBe('Run');
    expect(input.meta.recordingYaml).toBe('trail: []');
    expect(input.meta.originalYaml).toBe('steps: []');
    expect(input.trace).toBe(TRACE);
    expect(input.llmLogs).toEqual([{ i: 0 }]);
    expect(input.events).toBeNull();
  });

  test('embed mode inlines the frames and hands the input to the hierarchy packer', async () => {
    const calls: string[] = [];
    const packedInputs: any[] = [];
    const input = await Payload.buildSessionInput({
      s: summary,
      trace: TRACE,
      llmLogs: [],
      sessionId: 'sess_1',
      mode: 'embed',
      logs: [{ kind: 'log' }],
      deps: {
        fetch: sideChannelFetch(calls),
        traceScreenshotFiles,
        originalYamlFromLogs: () => null,
        packSessionInputsHierarchies: async (inputs: any[]) => {
          packedInputs.push(...inputs);
          inputs[0].hierarchiesGz = 'gz-bytes';
        },
      },
    });

    expect(input.shots['a.png']).toStartWith('data:image/png;base64,');
    expect(calls.filter((u) => u.startsWith('/static/')).length).toBe(4);
    expect(packedInputs).toEqual([input]);
    expect(input.hierarchiesGz).toBe('gz-bytes');
  });
});

describe('log order (agreeing with the exported report)', () => {
  // The daemon serves a run's records in log-filename order. A driver action and the tool call it
  // belongs to are written under sequential filenames but land microseconds apart, sometimes in the
  // other order — and the extractor folds the pair into one step only when the driver record comes
  // second. Reading a run in filename order therefore shows more steps than its own exported report,
  // which is built from a timestamp-sorted snapshot.
  test('reorders a pair the filenames sequenced the other way round', () => {
    const ordered = Payload.orderLogsForExtraction([
      { name: '006_driver', timestamp: '2026-08-20T10:00:00.120Z' },
      { name: '007_tool', timestamp: '2026-08-20T10:00:00.100Z' },
      { name: '008_objective', timestamp: '2026-08-20T10:00:00.300Z' },
    ]);
    expect(ordered.map((r: any) => r.name)).toEqual(['007_tool', '006_driver', '008_objective']);
  });

  test('records with no usable timestamp keep their filename order, ahead of the timestamped ones', () => {
    const ordered = Payload.orderLogsForExtraction([
      { name: 'later', timestamp: '2026-08-20T10:00:00.200Z' },
      { name: 'no-stamp-1' },
      { name: 'earlier', timestamp: '2026-08-20T10:00:00.100Z' },
      { name: 'no-stamp-2', timestamp: 'not a date' },
    ]);
    expect(ordered.map((r: any) => r.name)).toEqual(['no-stamp-1', 'no-stamp-2', 'earlier', 'later']);
  });

  // The pair above is the common case; this is the same pair a microsecond apart, which is what the
  // agent actually writes. A millisecond-only key would call these equal and leave them in the
  // filename order the sort exists to correct.
  test('reorders a pair that differs only past the millisecond', () => {
    const ordered = Payload.orderLogsForExtraction([
      { name: '006_driver', timestamp: '2026-08-20T10:00:00.100900Z' },
      { name: '007_tool', timestamp: '2026-08-20T10:00:00.100200Z' },
    ]);
    expect(ordered.map((r: any) => r.name)).toEqual(['007_tool', '006_driver']);
  });

  test('a sub-millisecond timestamp still sorts against a whole-millisecond one', () => {
    const ordered = Payload.orderLogsForExtraction([
      { name: 'c', timestamp: '2026-08-20T10:00:00.101Z' },
      { name: 'b', timestamp: '2026-08-20T10:00:00.100500Z' },
      { name: 'a', timestamp: '2026-08-20T10:00:00.100Z' },
    ]);
    expect(ordered.map((r: any) => r.name)).toEqual(['a', 'b', 'c']);
  });

  test('equal timestamps keep the order they arrived in', () => {
    const same = '2026-08-20T10:00:00.000Z';
    const ordered = Payload.orderLogsForExtraction([
      { name: 'a', timestamp: same }, { name: 'b', timestamp: same }, { name: 'c', timestamp: same },
    ]);
    expect(ordered.map((r: any) => r.name)).toEqual(['a', 'b', 'c']);
  });
});

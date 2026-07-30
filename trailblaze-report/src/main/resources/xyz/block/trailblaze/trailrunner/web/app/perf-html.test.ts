// Tests for the performance-analysis report's document assembly (perf-html.ts) and the viewer's
// pure range/format helpers (perf-viewer.ts). The interactive behavior (zoom/pan/selection) is
// canvas + DOM and is validated in a browser walkthrough; these pin the payload contract and the
// math the detail pane recomputes over a selected range.
import { describe, expect, test } from 'bun:test';
import { buildPerfReportHtml } from './perf-html';
import { clipLen, diffRows, fmtDelta, fmtMs, fmtTick, niceTickStep, rangeSelf, sessionDisplay } from './perf-viewer';
import { extractPerfSession } from './perf-extract';

const T0 = Date.parse('2026-07-27T10:00:00.000Z');

/** A tool log whose span is [startMs, startMs + durMs) - log timestamps are start-anchored. */
function toolLog(name: string, startMs: number, durMs: number): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog',
    timestamp: new Date(T0 + startMs).toISOString(),
    durationMs: durMs,
    toolName: name,
    successful: true,
    trailblazeTool: { class: `tools.${name}` },
  };
}

function sessionData(logs: TrailblazeLogRecord[]): PerfSessionData {
  const anchor: TrailblazeLogRecord = {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog',
    timestamp: new Date(T0).toISOString(),
    sessionStatus: { class: 'SessionStatus.Started' },
  };
  return extractPerfSession([anchor, ...logs])!;
}

describe('buildPerfReportHtml', () => {
  const html = buildPerfReportHtml({
    generatedAt: '2026-07-27 10:00:00',
    sessions: [{ meta: { title: 'my trail </script> run' }, data: sessionData([toolLog('tapOn', 1_000, 400)]) }],
  });

  test('embeds the payload as an inert JSON script the viewer can parse back', () => {
    const match = html.match(/<script type="application\/json" id="tb-perf-data">(.*?)<\/script>/s)!;
    expect(match).not.toBeNull();
    const payload: PerfReportPayload = JSON.parse(match[1]);
    expect(payload.generatedAt).toBe('2026-07-27 10:00:00');
    expect(payload.sessions).toHaveLength(1);
    expect(payload.sessions[0].meta.title).toBe('my trail </script> run');
    expect(payload.sessions[0].data.spans.map((sp) => sp.name)).toContain('tapOn');
  });

  test('a literal </script> in session data cannot close the payload element early', () => {
    // The payload region between its opening tag and the next real </script> must not contain a
    // raw close tag (toInertJson escapes `<`).
    const start = html.indexOf('id="tb-perf-data">') + 'id="tb-perf-data">'.length;
    const end = html.indexOf('</script>', start);
    expect(html.slice(start, end)).not.toContain('</script');
    expect(html.slice(start, end)).toContain('\\u003c/script>');
  });

  test('is a self-contained document: inline styles and an embedded viewer bundle', () => {
    expect(html).toContain('<style>');
    expect(html).toContain('PERF_VIEWER');
    expect(html).toContain('trailblaze-report-theme');
    expect(html).not.toContain('src="http');
  });
});

describe('range math (detail pane recompute)', () => {
  test('clipLen clips to the range and never goes negative', () => {
    expect(clipLen(100, 200, 0, 1_000)).toBe(100);
    expect(clipLen(100, 200, 150, 1_000)).toBe(50);
    expect(clipLen(100, 200, 300, 1_000)).toBe(0);
  });

  test('rangeSelf sums a span selfSegs clipped to the range', () => {
    const data = sessionData([toolLog('outer', 0, 1_000), toolLog('inner', 400, 200)]);
    const outer = data.spans.find((sp) => sp.name === 'outer')!;
    expect(rangeSelf(outer, 0, 1_000)).toBe(800);
    // Range covering only the child's window contributes no parent self time.
    expect(rangeSelf(outer, 400, 600)).toBe(0);
    expect(rangeSelf(outer, 0, 300)).toBe(300);
  });

  test('diffRows groups by tool name and sorts by |delta|', () => {
    const a = sessionData([toolLog('tapOn', 1_000, 400), toolLog('swipe', 3_000, 100)]);
    const b = sessionData([toolLog('tapOn', 1_000, 900), toolLog('swipe', 3_000, 150)]);
    const rows = diffRows(a, b, 0, 10_000);
    expect(rows[0].name).toBe('tapOn');
    expect(rows[0].delta).toBe(500);
    expect(rows[0].countA).toBe(1);
    expect(rows[1].name).toBe('swipe');
    expect(rows[1].delta).toBe(50);
  });
});

describe('format helpers', () => {
  test('fmtMs scales units', () => {
    expect(fmtMs(450)).toBe('450ms');
    expect(fmtMs(2_410)).toBe('2.41s');
    expect(fmtMs(12_340)).toBe('12.3s');
    expect(fmtMs(192_400)).toBe('3m 12s');
  });

  test('fmtDelta signs durations', () => {
    expect(fmtDelta(2_410)).toBe('+2.41s');
    expect(fmtDelta(-450)).toBe('-450ms');
    expect(fmtDelta(0)).toBe('0ms');
  });

  test('niceTickStep picks 1/2/5 decades at or below the raw spacing', () => {
    expect(niceTickStep(7_000)).toBe(5_000);
    expect(niceTickStep(2_400)).toBe(2_000);
    expect(niceTickStep(1_000)).toBe(1_000);
    expect(niceTickStep(0.4)).toBe(1);
  });

  test('fmtTick renders seconds below the minute scale and m:ss above it', () => {
    expect(fmtTick(45_000, 5_000)).toBe('45s');
    expect(fmtTick(125_000, 5_000)).toBe('2:05');
    expect(fmtTick(1_500, 100)).toBe('1.5s');
  });
});

describe('sessionDisplay', () => {
  test('parses a session-dir id into trail name + platform, demoting the raw id', () => {
    expect(sessionDisplay({ title: 'ios_send_money__contribute_to_pool_trail_8ab83d55' }))
      .toEqual({ name: 'Send money · Contribute to pool', platform: 'ios' });
    expect(sessionDisplay({ title: 'android_business_accounts__can_create_new_business_account_trail_4a24ed4d' }))
      .toEqual({ name: 'Business accounts · Can create new business account', platform: 'android' });
  });

  test('a dir-shaped id without platform prefix or trail suffix still humanizes', () => {
    expect(sessionDisplay({ title: 'checkout__favorites_grid' })).toEqual({ name: 'Checkout · Favorites grid', platform: '' });
    expect(sessionDisplay({ title: 'ios_smoke_test' })).toEqual({ name: 'Smoke test', platform: 'ios' });
  });

  test('a real display name passes through untouched; meta.platform wins over the parsed prefix', () => {
    expect(sessionDisplay({ title: 'New member can be added' })).toEqual({ name: 'New member can be added', platform: '' });
    expect(sessionDisplay({ title: 'ios_pay_trail_abcdef12', platform: 'iOS' })).toEqual({ name: 'Pay', platform: 'ios' });
  });

  test('falls back to trailId when title is absent', () => {
    expect(sessionDisplay({ trailId: 'web_login_trail_00ff00aa' })).toEqual({ name: 'Login', platform: 'web' });
    expect(sessionDisplay({})).toEqual({ name: '', platform: '' });
  });
});

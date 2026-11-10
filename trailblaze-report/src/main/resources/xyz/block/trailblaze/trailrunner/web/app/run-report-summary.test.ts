import { describe, expect, test } from 'bun:test';
import { runNotesHtml, warningsHtml, noteSignature, pinnedAt, resolvePin, summaryAvailable } from './run-report-summary';
import type { StreamLane, StreamNote } from './run-report-summary-model';

describe('pinned moment', () => {
  test('an explicit navigation always wins over the card the reader pinned', () => {
    expect(pinnedAt(true, 1000, 9000)).toBe(1000);
  });

  test('a plain redraw keeps the pinned card over the route', () => {
    expect(pinnedAt(false, 1000, 9000)).toBe(9000);
    expect(pinnedAt(undefined, 1000, 9000)).toBe(9000);
  });

  test('with nothing pinned the route is used, else nothing', () => {
    expect(pinnedAt(false, 1000, undefined)).toBe(1000);
    expect(pinnedAt(true, null, 9000)).toBe(9000);
    expect(pinnedAt(undefined, undefined, 'x')).toBeNull();
  });
});

describe('finding a pinned note again', () => {
  const request = (status: string, ms: string): StreamNote => ({ t: 1000, label: 'POST /pay', badges: [{ text: status }], fields: [{ k: 'Duration', v: ms }] });
  const lane = (notes: StreamNote[]) => ({ id: 'network', name: 'Network', bands: [notes] }) as unknown as StreamLane;

  test('of two requests in the same instant, the pinned one is found after an earlier note shifts them', () => {
    const first = request('500', '12 ms');
    const second = request('502', '80 ms');
    const saved = { b: 0, laneId: 'network', n: 1, t: 1000, label: 'POST /pay', sig: noteSignature(second) };
    const earlier: StreamNote = { t: 500, label: 'GET /cart', badges: [], fields: [] };
    // One note in front: the old place, n=1, is now the first request.
    expect(resolvePin(lane([earlier, first, second]), saved)).toEqual({ b: 0, n: 2 });
  });

  test('a pinned note whose content changed since is still found by its time and label', () => {
    const saved = { b: 0, laneId: 'network', n: 0, t: 1000, label: 'POST /pay', sig: noteSignature(request('…', '')) };
    expect(resolvePin(lane([request('200', '40 ms')]), saved)).toEqual({ b: 0, n: 0 });
  });
});

describe('run warnings', () => {
  test('each warning is a row naming the survey that raised it', () => {
    const html = warningsHtml([{ survey: 'serverErrors', summary: 'GET /cart returned 503' }]);
    expect(html).toContain('aria-label="Run warnings"');
    expect(html).toContain('GET /cart returned 503');
    expect(html).toContain('via serverErrors');
  });

  test('a warning from an archive renders as text, never markup', () => {
    const html = warningsHtml([{ survey: '<b>s</b>', summary: '<img src=x onerror=alert(1)>' }]);
    expect(html).not.toContain('<img');
    expect(html).not.toContain('<b>');
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;');
  });

  test('warnings and the diagnosis share one bounded scroller, so together they cannot crowd out the grid', () => {
    const diagnosis = '<section class="streamsdiag" aria-label="Run diagnosis"></section>';
    const html = runNotesHtml([{ survey: 'appCrashes', summary: 'com.example crashed' }], diagnosis);
    // Its own class: `.streamsnotes` is the grid's per-cell notes layer, positioned over its cell.
    expect(html.startsWith('<div class="streamsrunnotes">')).toBe(true);
    expect(html).toContain('aria-label="Run warnings"');
    expect(html.endsWith(`${diagnosis}</div>`)).toBe(true);
    expect(runNotesHtml(undefined, '')).toBe('');
  });

  test('a run with no warnings shows no section', () => {
    expect(warningsHtml(undefined)).toBe('');
    expect(warningsHtml([])).toBe('');
  });

  test('a run that crashed before its first step still shows its warnings on the Summary', () => {
    const traceless = (warnings?: { survey: string; summary: string }[]) =>
      ({ trace: [], meta: warnings ? { warnings } : {} }) as unknown as Parameters<typeof summaryAvailable>[0];
    expect(summaryAvailable(traceless([{ survey: 'crashes', summary: 'App crashed at launch' }]))).toBe(true);
    expect(summaryAvailable(traceless())).toBe(false);
    expect(summaryAvailable(traceless([]))).toBe(false);
  });
});

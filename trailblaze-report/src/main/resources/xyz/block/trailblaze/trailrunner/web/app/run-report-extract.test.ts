// Behavior of the dispatches a tool batch folds under its row (toolChildren), as the report's
// timeline rows and the Theater read them.
import { describe, expect, test } from 'bun:test';
import { extractTrace, slimTraceForShare } from './run-report-extract';

const T = 'xyz.block.trailblaze.logs.client.TrailblazeLog';
const at = (second: number) => `2024-01-01T00:00:${String(second).padStart(2, '0')}Z`;
const tool = (name: string, second: number, durationMs: number, raw: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) => ({
  class: `${T}.TrailblazeToolLog`, toolName: name, traceId: 'batch', successful: true, durationMs, trailblazeTool: { raw }, timestamp: at(second), ...extra,
});
const tapLog = (second: number, x: number, y: number, extra: Record<string, unknown> = {}) => ({
  class: `${T}.MaestroDriverLog`, traceId: 'batch', action: { class: 'xyz.AgentDriverAction.TapPoint', x, y },
  deviceWidth: 1080, deviceHeight: 2400, timestamp: at(second), ...extra,
});

describe('a tool batch\'s folded dispatches', () => {
  const objective = { class: `${T}.ObjectiveStartLog`, promptStep: { step: 'Sign in' }, timestamp: at(0) };

  test('a dispatch that waited before acting carries the instant the driver tapped, not its start', () => {
    const [, row] = extractTrace([
      objective,
      tool('signIn', 1, 100),
      tool('tapOn', 2, 6000, { text: 'Next' }),
      tapLog(6, 10, 20),
    ]);
    const [child] = row.children!;
    expect(child.ts).toBe(Date.parse(at(2)));
    expect(child.markTs).toBe(Date.parse(at(6)));
    // The share slimming keeps it, so the standalone report can draw the tap at the same moment.
    expect(slimTraceForShare([row])[0].children![0].markTs).toBe(Date.parse(at(6)));
  });

  test('a driver instant outside the dispatch\'s own span is another clock, so no tap time is claimed', () => {
    const [, row] = extractTrace([
      objective,
      tool('signIn', 1, 100),
      tool('tapOn', 2, 1000, { text: 'Next' }),
      tapLog(50, 10, 20),
    ]);
    expect(row.children![0].markTs).toBeUndefined();
  });

  test('a successful switchDevice dispatch names the device it handed over to; a failed one does not', () => {
    const [, row] = extractTrace([
      objective,
      tool('signIn', 1, 100),
      tool('switchDevice', 2, 5, { name: 'buyer' }, { deviceName: 'buyer' }),
      tool('switchDevice', 3, 5, { name: 'seller' }, { successful: false }),
      tool('tapOn', 4, 10, { text: 'Pay' }),
    ]);
    expect(row.children!.map((c) => [c.label, c.switchTo])).toEqual([
      ['switchDevice', 'buyer'],
      ['switchDevice', undefined],
      ['tapOn', undefined],
    ]);
    expect(slimTraceForShare([row])[0].children![0].switchTo).toBe('buyer');
  });
});

describe('a visibility assertion\'s mark', () => {
  const objective = { class: `${T}.ObjectiveStartLog`, promptStep: { step: 'See More' }, timestamp: at(0) };
  const node = (left: number, top: number, right: number, bottom: number, children: unknown[] = []) => ({ bounds: { left, top, right, bottom }, children });
  // The screen, a row centered on the same point, and the button inside it the check found.
  const tree = node(0, 0, 1080, 2400, [node(0, 2130, 1080, 2326, [node(813, 2130, 1069, 2326)])]);
  const assertLog = (action: Record<string, unknown>) => ({
    class: `${T}.MaestroDriverLog`, traceId: 'check', deviceWidth: 1080, deviceHeight: 2400, timestamp: at(1), trailblazeNodeTree: tree,
    action: { class: 'xyz.AgentDriverAction.AssertCondition', ...action },
  });
  const markOf = (action: Record<string, unknown>) => {
    const rows = extractTrace([objective, { ...tool('assertVisible', 1, 200, { text: 'More' }), traceId: 'check' }, assertLog(action)]);
    return slimTraceForShare(rows).find((row) => row.mark)?.mark;
  };

  test('outlines the smallest element centered on the point it found', () => {
    expect(markOf({ x: 941, y: 2228, isVisible: true })).toMatchObject({ kind: 'assert', x: 941, y: 2228, box: { left: 813, top: 2130, right: 1069, bottom: 2326 } });
  });

  test('a point nothing is centered on keeps only the point', () => {
    const mark = markOf({ x: 300, y: 300, isVisible: true });
    expect(mark).toMatchObject({ kind: 'assert', x: 300, y: 300 });
    expect(mark?.box).toBeUndefined();
  });

  test('a not-visible check found nothing, so it has no box and says so', () => {
    const mark = markOf({ x: 540, y: 1200, isVisible: false });
    expect(mark?.box).toBeUndefined();
    expect(mark?.visible).toBe(false);
  });
});

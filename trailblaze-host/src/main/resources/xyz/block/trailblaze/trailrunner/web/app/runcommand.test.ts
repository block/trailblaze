import { beforeAll, describe, expect, test } from 'bun:test';

declare global {
  var window: Record<string, any>;
  var DEFAULT_MAX_LLM_CALLS: number;
}

beforeAll(async () => {
  globalThis.window = {};
  globalThis.DEFAULT_MAX_LLM_CALLS = 30;
  await import('./runcommand.tsx');
});

describe('buildRunCommand', () => {
  test('seeds from the daemon effective agent until the picker is touched', () => {
    expect(window.seedRunAgent('', 'KOOG_STRATEGY_GRAPH', false)).toBe('KOOG_STRATEGY_GRAPH');
    expect(window.seedRunAgent('TRAILBLAZE_RUNNER', 'KOOG_STRATEGY_GRAPH', true))
      .toBe('TRAILBLAZE_RUNNER');
  });

  test('always carries the dialog agent so the copied command runs the same implementation', () => {
    expect(window.buildRunCommand({ trailPath: 'demo.trail.yaml', agent: 'KOOG_STRATEGY_GRAPH' }))
      .toContain('--agent KOOG_STRATEGY_GRAPH');
    expect(window.buildRunCommand({ trailPath: 'demo.trail.yaml', agent: 'TRAILBLAZE_RUNNER' }))
      .toContain('--agent TRAILBLAZE_RUNNER');
  });

  test('defers agent resolution when settings did not seed the picker', () => {
    expect(window.runAgentOption('')).toBeNull();
    expect(window.buildRunCommand({ trailPath: 'demo.trail.yaml', agent: '' }))
      .not.toContain('--agent');
  });
});

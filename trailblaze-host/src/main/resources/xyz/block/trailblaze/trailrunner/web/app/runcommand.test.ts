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

  test('sends no memory override until the saved setting lands, so an early Run cannot disable capture', () => {
    // The window a Run click can land in: the settings fetch has not resolved, so there is nothing
    // to seed from. Staying null is what makes the daemon use its own saved setting instead of
    // being told `false` by a toggle the user never touched.
    expect(window.seedCaptureMemory(null, undefined, false)).toBeNull();
    expect(window.seedCaptureMemory(null, null, false)).toBeNull();
    // Settings arrive: seed from them, either way.
    expect(window.seedCaptureMemory(null, true, false)).toBe(true);
    expect(window.seedCaptureMemory(null, false, false)).toBe(false);
    // Touched wins over any later seed, so the user's own choice is not overwritten.
    expect(window.seedCaptureMemory(false, true, true)).toBe(false);
    expect(window.seedCaptureMemory(true, false, true)).toBe(true);
  });

  test('only an explicit memory-off prints the negative flag', () => {
    const cmd = (captureMemory) => window.buildRunCommand({ trailPath: 'demo.trail.yaml', captureMemory });
    expect(cmd(false)).toContain('--no-capture-memory');
    // On matches the CLI default, and null is "no override" — neither can be spelled as a flag, and
    // printing the negative one would show a command that disables what the run is about to capture.
    expect(cmd(true)).not.toContain('--no-capture-memory');
    expect(cmd(null)).not.toContain('--no-capture-memory');
  });
});

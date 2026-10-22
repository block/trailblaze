import { describe, expect, test } from 'bun:test';
import {
  besideInset,
  actionLabel, acts, buildTheaterStory, cameraCuts, chapterIndexAt, chapterStep, clock, cutAt, fit, handoffLayout,
  insetAt, isLapsing, lapseEnd, lapseWindows, locateYaml, markAt, recordingAt, sameSnapshot, scenes, scriptLines,
  settledMs, snapshotAt, stepClock, stepNumber, syncVideo, yamlLineState, yamlSeekTimes, yamlSegments, frameAt, screenshotOutlasts,
  type TheaterStory,
} from './run-report-theater-model';

const T0 = 1_000_000;

/** A trace row with only what a case names; the rest takes the quiet defaults. */
function row(over: Partial<TraceStep> & { at?: number }): TraceStep {
  const { at, ...rest } = over;
  return {
    i: 0, label: 'tool', tool: '', note: null, ms: 0, ts: at != null ? T0 + at : null, ok: true, err: null,
    screenshotFile: null, objective: false, trailhead: false, count: null, mark: null,
    ...rest,
  } as TraceStep;
}

const objective = (label: string, at: number, extra: Partial<TraceStep> = {}) => row({ objective: true, label, at, ...extra });

function story(trace: TraceStep[], extra: Partial<Parameters<typeof buildTheaterStory>[0]> = {}): TheaterStory {
  return buildTheaterStory({ trace, meta: { title: 'Checkout', status: 'passed' }, clips: [], ...extra });
}

describe('building a story', () => {
  test('a run with more timestamps than a call can take as arguments still finds its start', () => {
    const clips = Array.from({ length: 1_200_000 }, (_, i) => ({ startMs: T0 + 100 + i, endMs: T0 + 200 + i, device: 'phone' }));
    const s = story([objective('Step', 0)], { clips });
    expect(s.chapters[0].startMs).toBe(0);
    expect(s.endMs).toBe(1_200_199);
  });

  test('each objective row opens a chapter that runs until the next one starts', () => {
    const s = story([
      objective('Open the app.', 0),
      row({ label: 'tapOn', tool: 'Tap "Pay"', at: 500, ms: 300 }),
      objective('Pay for the order.', 4000),
      row({ label: 'tapOn', tool: 'Tap "Done"', at: 4500, ms: 200 }),
    ]);
    expect(s.chapters.map((c) => [c.headline, c.startMs, c.endMs])).toEqual([
      ['Open the app', 0, 4000],
      ['Pay for the order', 4000, 4700],
    ]);
    expect(s.originMs).toBe(T0);
    expect(s.title).toBe('Checkout');
    expect(s.outcome).toBe('passed');
  });

  test('times are measured from the earliest timestamp, even a recording that started before the trace', () => {
    const s = story([objective('Step', 2000), row({ label: 'tapOn', at: 2500 })], { clips: [{ startMs: T0 - 1000, endMs: T0 + 9000, device: 'phone' }] });
    expect(s.originMs).toBe(T0 - 1000);
    expect(s.chapters[0].startMs).toBe(3000);
    expect(s.devices[0].recordings).toEqual([{ clip: 0, startMs: 0, endMs: 10_000 }]);
  });

  test('driver plumbing and model-call rows are not part of the script, unless they drew something', () => {
    const s = story([
      objective('Step', 0),
      row({ label: 'android_dumpHierarchy', at: 100 }),
      row({ label: 'wait', at: 150 }),
      row({ label: 'agent step', tool: 'agent step', at: 200, llm: 1 } as any),
      row({ label: 'android_tap', at: 300, mark: { kind: 'tap', x: 10, y: 20, dw: 100, dh: 200 } }),
      row({ label: 'tapOn', tool: 'Tap "Go"', at: 400 }),
    ]);
    expect(s.chapters[0].actions.map((a) => a.tool)).toEqual(['android_tap', 'tapOn']);
  });

  test('a dispatch row that only hands over to the tool doing the work is folded into it', () => {
    const note = 'Open the cart from the tab bar';
    const s = story([
      objective('Step', 0),
      row({ label: 'tapOnElement', note, at: 100 }),
      row({ label: 'android_tap', note, at: 100, ms: 250, screenshotFile: 'a.webp' }),
    ]);
    expect(s.chapters[0].actions).toHaveLength(1);
    expect(s.chapters[0].actions[0].tool).toBe('android_tap');
  });

  test('a short reason is the line as written; a long one gives way to what the tool did', () => {
    expect(actionLabel(row({ label: 'tapOn', note: 'Open the cart' })).label).toBe('Open the cart');
    const long = 'x'.repeat(120);
    const result = actionLabel(row({ label: 'tapOn', note: long, args: '- tapOn:\n    text: Pay now' }));
    expect(result.label).toBe('Tap "Pay now"');
    expect(result.detail).toBe(long);
    expect(actionLabel(row({ label: 'web_navigate' })).label).toBe('Navigate');
  });

  test('taps, swipes and checks become marks at the time the driver acted, not when the row began', () => {
    const s = story([
      objective('Step', 0),
      row({ label: 'tapOn', at: 100, ms: 900, markTs: T0 + 700, mark: { kind: 'tap', x: 50, y: 100, dw: 500, dh: 1000 } }),
      row({ label: 'swipe', at: 2000, mark: { kind: 'swipe', x1: 10, y1: 800, x2: 10, y2: 200, dw: 500, dh: 1000 } }),
      row({ label: 'assertVisible', at: 3000, mark: { kind: 'assert', x: 5, y: 6, dw: 500, dh: 1000, ok: false } }),
    ]);
    const [tap, swipe, check] = s.chapters[0].actions.map((a) => a.mark!);
    expect(tap).toMatchObject({ kind: 'tap', t: 700, x: 50, y: 100 });
    expect(swipe).toMatchObject({ kind: 'swipe', x: 10, y: 800, x2: 10, y2: 200 });
    expect(check).toMatchObject({ kind: 'check', ok: false });
    expect(s.devices[0].screen).toEqual({ w: 500, h: 1000 });
  });

  test('a visibility check carries the element it found; a not-visible check marks nothing', () => {
    const s = story([
      objective('Step', 0),
      row({ label: 'assertVisible', at: 100, mark: { kind: 'assert', x: 941, y: 2228, dw: 1080, dh: 2400, box: { left: 813, top: 2130, right: 1069, bottom: 2326 } } }),
      row({ label: 'assertNotVisible', at: 500, mark: { kind: 'assert', x: 540, y: 1200, dw: 1080, dh: 2400, visible: false } }),
    ]);
    const [found, absent] = s.chapters[0].actions;
    expect(found.mark).toMatchObject({ kind: 'check', x: 941, y: 2228, box: { left: 813, top: 2130, right: 1069, bottom: 2326 } });
    expect(absent.mark).toBeUndefined();
  });

  test('a screenshot missing from the report is skipped, so the one before it stays up', () => {
    const s = story([
      objective('Step', 0),
      row({ label: 'tapOn', at: 100, ms: 400, screenshotFile: 'one.webp' }),
      row({ label: 'tapOn', at: 1000, ms: 100, screenshotFile: 'gone.webp' }),
    ], { hasShot: (file) => file !== 'gone.webp' });
    expect(frameAt(s.devices[0], 9000)?.file).toBe('one.webp');
  });

  test('a saved still stands in for a capture that has no screenshot', () => {
    const s = story([objective('Step', 0), row({ label: 'tapOn', at: 100, ms: 400, captureIds: ['cap1'] } as Partial<TraceStep>)]);
    expect(s.devices[0].frames).toEqual([{ t: 500, file: 'cap1.webp' }]);
  });

  test('past a recording that ended early, a later screenshot takes over from its last frame', () => {
    const recording = { clip: 0, startMs: 0, endMs: 1000 };
    expect(screenshotOutlasts(recording, { t: 1500, file: 'late.webp' }, 2000)).toBe(true);
    expect(screenshotOutlasts(recording, { t: 500, file: 'early.webp' }, 2000)).toBe(false);
    expect(screenshotOutlasts(recording, { t: 1500, file: 'late.webp' }, 800)).toBe(false);
    // Between the recording's end and the screenshot, the screenshot hasn't been taken yet.
    expect(screenshotOutlasts(recording, { t: 3000, file: 'later.webp' }, 2000)).toBe(false);
  });

  test('screenshots become a device slideshow stamped when each was taken', () => {
    const s = story([
      objective('Step', 0),
      row({ label: 'tapOn', at: 100, ms: 400, screenshotFile: 'one.webp' }),
      row({ label: 'tapOn', at: 1000, ms: 100, shotEndTs: T0 + 1500, screenshotFile: 'two.webp' }),
    ]);
    const device = s.devices[0];
    expect(device.frames).toEqual([{ t: 500, file: 'one.webp' }, { t: 1500, file: 'two.webp' }]);
    expect(frameAt(device, 0)?.file).toBe('one.webp');
    expect(frameAt(device, 1200)?.file).toBe('one.webp');
    expect(frameAt(device, 9000)?.file).toBe('two.webp');
  });

  test('a value read on one device and used on another is a handoff between their chapters', () => {
    const s = story([
      objective('On the seller, read the code.', 0, { device: 'seller' }),
      row({ label: 'rememberText', device: 'seller', at: 100, ms: 100, args: '- rememberText:\n    variable: code' }),
      objective('On the buyer, enter the code.', 2000, { device: 'buyer' }),
      row({ label: 'inputText', device: 'buyer', at: 2100, args: '- inputText:\n    text: "{{code}}"' }),
    ]);
    expect(s.devices.map((d) => d.name)).toEqual(['seller', 'buyer']);
    expect(s.handoffs).toEqual([{ variable: 'code', from: 'seller', to: 'buyer', producedMs: 200, consumedMs: 2100, fromChapter: 0, toChapter: 1 }]);
    expect(s.chapters.map((c) => c.device)).toEqual(['seller', 'buyer']);
    expect(scenes(s.chapters).map((x) => x.device)).toEqual(['seller', 'buyer']);
  });

  test('a value used on the device that read it is no handoff', () => {
    const s = story([
      objective('Read then use', 0, { device: 'a' }),
      row({ label: 'rememberText', device: 'a', at: 100, args: 'variable: code' }),
      row({ label: 'inputText', device: 'a', at: 200, args: 'text: "{{code}}"' }),
    ]);
    expect(s.handoffs).toEqual([]);
  });

  test('outcome follows the objective: failed beats self-healed beats passed', () => {
    const s = story([
      objective('Passed', 0),
      objective('Healed', 1000, { selfHeal: true }),
      objective('Failed', 2000, { ok: false }),
    ]);
    expect(s.chapters.map((c) => c.outcome)).toEqual(['passed', 'healed', 'failed']);
    expect(story([objective('x', 0)], { meta: { status: 'failed' } }).outcome).toBe('failed');
    expect(story([objective('x', 0)], { meta: { status: 'passed', selfHeal: true } }).outcome).toBe('healed');
  });

  test('actions after a failed recorded step are marked as the AI recovering it', () => {
    const s = story([
      objective('Step', 0, { selfHeal: true }),
      row({ label: 'tapOn', at: 100, selfHealSource: true, ok: false }),
      row({ label: 'tapOn', at: 500 }),
    ]);
    expect(s.chapters[0].actions.map((a) => !!a.healed)).toEqual([true, true]);
  });

  test('every dispatch a tool batch ran plays its own screen and mark; one it only declared does not', () => {
    const tap = { kind: 'tap' as const, x: 10, y: 20, dw: 500, dh: 1000 };
    const later = { kind: 'tap' as const, x: 300, y: 600, dw: 500, dh: 1000 };
    const s = story([
      objective('Sign in', 0),
      row({
        label: 'signIn', at: 100, ms: 2000, screenshotFile: 'one.webp', mark: tap, markTs: T0 + 250,
        children: [
          { label: 'tapOn', tool: '', args: 'text: Email', ts: T0 + 200, ms: 300, ok: true, screenshotFile: 'one.webp', mark: tap, markTs: T0 + 250 },
          { label: 'tapOn', tool: '', args: 'text: Next', ts: T0 + 1200, ms: 300, ok: true, shotEndTs: T0 + 1600, screenshotFile: 'two.webp', mark: later },
          { label: 'tapOn', tool: '', args: 'text: Skip', ts: null, ms: null, ok: true, screenshotFile: null, mark: null },
        ],
      }),
    ]);
    const actions = s.chapters[0].actions;
    expect(actions.map((a) => a.label)).toEqual(['Sign in', 'Tap "Email"', 'Tap "Next"']);
    expect(actions.map((a) => a.t)).toEqual([100, 200, 1200]);
    // The batch's own overlay is its first dispatch's, so it is drawn once, by that dispatch.
    expect(actions.map((a) => a.mark && [a.mark.x, a.mark.y])).toEqual([undefined, [10, 20], [300, 600]]);
    expect(s.devices[0].frames.map((f) => f.file).sort()).toEqual(['one.webp', 'two.webp']);
    expect(s.devices[0].frames.find((f) => f.file === 'two.webp')?.t).toBe(1600);
  });

  test('a repeated tap on the same spot keeps both marks; only the one drawn at the same cue is shared', () => {
    const tap = { kind: 'tap' as const, x: 10, y: 20, dw: 500, dh: 1000 };
    const s = story([
      objective('Add twice', 0),
      row({
        label: 'addItem', at: 100, ms: 3000, screenshotFile: 'a.webp', mark: tap, markTs: T0 + 300,
        children: [
          // Its own tap came first, before this dispatch began...
          { label: 'tapOn', tool: '', args: 'text: Add', ts: T0 + 100, ms: 200, ok: true, screenshotFile: 'a.webp', mark: tap, markTs: T0 + 300 },
          // ...and a later dispatch taps the very same spot.
          { label: 'tapOn', tool: '', args: 'text: Add', ts: T0 + 1500, ms: 200, ok: true, screenshotFile: 'b.webp', mark: tap, markTs: T0 + 1600 },
        ],
      }),
    ]);
    expect(s.chapters[0].actions.map((a) => a.mark?.t)).toEqual([undefined, 300, 1600]);
    // Were the row's mark its own dispatch's (a different cue), both would draw.
    const separate = story([
      objective('Add twice', 0),
      row({
        label: 'addItem', at: 100, ms: 3000, mark: tap, markTs: T0 + 150,
        children: [{ label: 'tapOn', tool: '', ts: T0 + 1500, ms: 200, ok: true, mark: tap, markTs: T0 + 1600 }],
      }),
    ]);
    expect(separate.chapters[0].actions.map((a) => a.mark?.t)).toEqual([150, 1600]);
  });

  test('a dispatch that waited before acting draws its tap at the driver\'s instant, not its start', () => {
    const s = story([
      objective('Step', 0),
      row({
        label: 'batch', at: 100, ms: 5000,
        children: [{ label: 'tapOn', tool: '', ts: T0 + 200, ms: 4000, ok: true, mark: { kind: 'tap', x: 1, y: 2, dw: 500, dh: 1000 }, markTs: T0 + 3500 }],
      }),
    ]);
    const tap = s.chapters[0].actions.find((a) => a.tool === 'tapOn')!;
    expect([tap.t, tap.mark?.t]).toEqual([200, 3500]);
  });

  test('a batch whose first mark is on the device it handed over to keeps its own screen size for its own device', () => {
    const wide = { kind: 'tap' as const, x: 5, y: 6, dw: 1000, dh: 500 };
    const s = story([
      objective('Start on seller', 0, { device: 'seller' }),
      row({
        label: 'flow', at: 500, ms: 4000, device: 'buyer', mark: wide, markTs: T0 + 1500, viewport: { w: 400, h: 800 },
        children: [
          { label: 'switchDevice', tool: '', args: '- switchDevice:\n    name: buyer', ts: T0 + 1000, ms: 50, ok: true, switchTo: 'buyer' },
          { label: 'tapOn', tool: '', args: 'text: Pay', ts: T0 + 1500, ms: 100, ok: true, mark: wide, markTs: T0 + 1500 },
        ],
      } as Partial<TraceStep>),
    ]);
    expect(s.devices.map((d) => [d.name, d.screen])).toEqual([['seller', { w: 400, h: 800 }], ['buyer', { w: 1000, h: 500 }]]);
  });

  test('a batch that hands over between dispatches keeps each dispatch on the device that was active for it', () => {
    const point = { kind: 'tap' as const, x: 5, y: 6, dw: 500, dh: 1000 };
    const s = story([
      objective('Start on seller', 0, { device: 'seller' }),
      row({ label: 'tapOn', at: 100, ms: 50, device: 'seller' }),
      // The row's own stamp is its LAST destination (buyer).
      row({
        label: 'flow', at: 500, ms: 4000, device: 'buyer', screenshotFile: 'seller.webp',
        children: [
          { label: 'tapOn', tool: '', args: 'text: Ready', ts: T0 + 600, ms: 100, ok: true, screenshotFile: 'seller.webp', mark: point },
          { label: 'switchDevice', tool: '', args: '- switchDevice:\n    name: buyer', ts: T0 + 1000, ms: 50, ok: true, switchTo: 'buyer' },
          { label: 'tapOn', tool: '', args: 'text: Pay', ts: T0 + 1500, ms: 100, ok: true, screenshotFile: 'buyer.webp', mark: point },
        ],
      }),
    ]);
    expect(s.devices.map((d) => d.name)).toEqual(['seller', 'buyer']);
    expect(s.chapters[0].actions.map((a) => [a.tool, a.device])).toEqual([
      ['tapOn', 'seller'], ['flow', 'seller'], ['tapOn', 'seller'], ['tapOn', 'buyer'],
    ]);
    expect(s.devices.map((d) => d.frames.map((f) => f.file))).toEqual([['seller.webp'], ['buyer.webp']]);
  });

  test('a handover that failed inside a batch moves nothing, and the next row starts on the last device', () => {
    const s = story([
      objective('Step', 0, { device: 'seller' }),
      row({
        label: 'flow', at: 100, ms: 500, device: 'seller',
        children: [
          { label: 'switchDevice', tool: '', args: '- switchDevice:\n    name: buyer', ts: T0 + 200, ms: 5, ok: false },
          { label: 'tapOn', tool: '', args: 'text: Pay', ts: T0 + 300, ms: 100, ok: true },
        ],
      }),
      row({ label: 'tapOn', at: 700, device: 'seller' }),
    ]);
    expect(s.chapters[0].actions.map((a) => a.device)).toEqual(['seller', 'seller', 'seller']);
  });

  test('an older batch with no handover destination recorded still walks its successful switchDevice', () => {
    const point = { kind: 'tap' as const, x: 5, y: 6, dw: 500, dh: 1000 };
    const s = story([
      objective('Start on seller', 0, { device: 'seller' }),
      row({ label: 'tapOn', at: 100, ms: 50, device: 'seller' }),
      row({
        label: 'flow', at: 500, ms: 4000, device: 'buyer', screenshotFile: 'seller.webp',
        children: [
          { label: 'tapOn', tool: '', args: 'text: Ready', ms: 100, ok: true, screenshotFile: 'seller.webp', mark: point },
          { label: 'switchDevice', tool: '', args: '- switchDevice:\n    name: buyer', ms: 50, ok: true },
          { label: 'tapOn', tool: '', args: 'text: Pay', ms: 100, ok: true, screenshotFile: 'buyer.webp', mark: point },
        ],
      }),
    ]);
    expect(s.chapters[0].actions.map((a) => [a.tool, a.device])).toEqual([
      ['tapOn', 'seller'], ['flow', 'seller'], ['tapOn', 'seller'], ['tapOn', 'buyer'],
    ]);
    expect(s.devices.map((d) => d.frames.map((f) => f.file))).toEqual([['seller.webp'], ['buyer.webp']]);
  });

  test('older dispatches with no timestamps of their own play in the order they ran, inside their row', () => {
    const s = story([
      objective('Step', 0),
      row({
        label: 'flow', at: 100, ms: 1000,
        children: [
          { label: 'tapOn', tool: '', args: 'text: A', ms: 600, ok: true, screenshotFile: 'a.webp' },
          { label: 'tapOn', tool: '', args: 'text: B', ms: 100, ok: true, screenshotFile: 'b.webp' },
        ],
      }),
    ]);
    const times = s.chapters[0].actions.map((a) => a.t);
    expect(times).toEqual([...times].sort((x, y) => x - y));
    expect(new Set(times).size).toBe(times.length);
    expect(times.every((t) => t >= 100 && t <= 1100)).toBe(true);
    expect(s.devices[0].frames.map((f) => f.file)).toEqual(['a.webp', 'b.webp']);
  });

  test('a failed self-heal the agent retried is one step, with the retry\'s outcome', () => {
    const s = story([
      objective('Open the app.', 0),
      objective('Pay for the order.', 1000, { selfHeal: true, ok: false }),
      row({ label: 'tapOn', at: 1100, selfHealSource: true, ok: false }),
      objective('Pay for the order.', 2000),
      row({ label: 'tapOn', tool: 'Tap "Pay"', at: 2100 }),
      objective('Finish.', 3000),
    ], { originalYaml: 'trail:\n  - step: Open the app.\n  - step: Pay for the order.\n    recording:\n      tools:\n        - tapOn:\n            text: Pay\n  - step: Finish.\n' });
    expect(s.chapters.map((c) => [c.headline, c.outcome, c.startMs, c.endMs])).toEqual([
      ['Open the app', 'passed', 0, 1000],
      ['Pay for the order', 'healed', 1000, 3000],
      ['Finish', 'passed', 3000, 3000],
    ]);
    expect(s.chapters.map((_, k) => stepNumber(s.chapters, k))).toEqual(['1', '2', '3']);
    expect(s.chapters[1].actions.map((a) => !!a.healed)).toEqual([true, true]);
    expect(s.chapters.map((c) => c.yaml?.from)).toEqual([2, 3, 8]);
  });

  test('a retry that failed again leaves the step failed', () => {
    const s = story([
      objective('Pay.', 0, { selfHeal: true, ok: false }),
      objective('Pay.', 1000, { ok: false }),
    ]);
    expect(s.chapters.map((c) => c.outcome)).toEqual(['failed']);
  });

  describe('a run that failed without its objective logging a failure', () => {
    const crashed = { title: 'Checkout', status: 'failed' };

    test('the objective the failed tool interrupted is the failed chapter', () => {
      const s = story([
        objective('Open the app.', 0),
        row({ label: 'tapOn', at: 100 }),
        objective('Pay for the order.', 1000),
        row({ label: 'tapOn', at: 1100, ok: false, err: 'boom' }),
      ], { meta: crashed });
      expect(s.chapters.map((c) => c.outcome)).toEqual(['passed', 'failed']);
    });

    test('the run\'s end-of-run error is the trail\'s, never a clean Teardown\'s', () => {
      const s = story([
        objective('Pay for the order.', 0),
        row({ label: 'tapOn', at: 100 }),
        objective('Teardown', 1000, { teardown: true }),
        row({ label: 'Error', at: 1100, ok: false, err: 'timed out', terminal: true, endOfRun: true }),
      ], { meta: crashed });
      expect(s.chapters.map((c) => [c.kind, c.outcome])).toEqual([['step', 'failed'], ['teardown', 'passed']]);
    });

    test('a tolerated self-heal attempt, or an app crash over passing steps, blames no step', () => {
      const healed = story([
        objective('Pay.', 0, { selfHeal: true, ok: false }),
        row({ label: 'tapOn', at: 100, selfHealSource: true, ok: false }),
        objective('Pay.', 1000),
        objective('Finish.', 2000),
      ], { meta: crashed });
      expect(healed.chapters.map((c) => c.outcome)).toEqual(['healed', 'passed']);
      const crash = story([
        objective('Pay.', 0),
        row({ label: 'tapOn', at: 100, ok: false }),
      ], { meta: { ...crashed, failureCode: 'app_crashed' } });
      expect(crash.chapters.map((c) => c.outcome)).toEqual(['passed']);
    });

    test('a passing run keeps its tolerated failed rows quiet', () => {
      const s = story([objective('Pay.', 0), row({ label: 'tapOn', at: 100, ok: false })]);
      expect(s.chapters.map((c) => c.outcome)).toEqual(['passed']);
    });
  });

  test('step numbers skip the trailhead and teardown', () => {
    const s = story([
      objective('Trailhead', 0, { trailhead: true }),
      objective('First', 1000),
      objective('Second', 2000),
      objective('Teardown', 3000, { teardown: true }),
    ]);
    expect(s.chapters.map((_, k) => stepNumber(s.chapters, k))).toEqual(['0', '1', '2', '']);
    expect(acts(s).map((a) => [a.title, a.chapters])).toEqual([['Trailhead', [0]], ['Trail', [1, 2, 3]]]);
  });
});

describe('the trail YAML as a script', () => {
  const yaml = [
    'trailhead:',
    '  setup: sign_in',
    'trail:',
    '  - step: Open the cart',
    '    recording:',
    '      tools:',
    '        - tapOn:',
    '            text: Cart',
    '        - assertVisible:',
    '            text: Total',
    '  - verify: Total is shown',
    '',
  ].join('\n');

  test('locates the trailhead block and each step by indentation', () => {
    const map = locateYaml(yaml);
    expect(map.trailhead).toEqual({ from: 1, to: 2 });
    expect(map.items.map((i) => [i.kind, i.text, i.span])).toEqual([
      ['step', 'Open the cart', { from: 4, to: 10 }],
      ['verify', 'Total is shown', { from: 11, to: 11 }],
    ]);
  });

  test('chapters take the span of the step they name and their actions the line of their tool', () => {
    const s = story([
      objective('Trailhead', 0, { trailhead: true }),
      objective('Open the cart', 1000),
      row({ label: 'tapOn', at: 1100 }),
      row({ label: 'assertVisible', at: 1500 }),
      objective('Total is shown', 3000),
    ], { originalYaml: yaml });
    expect(s.yaml).toEqual({ text: yaml, source: 'original' });
    expect(s.chapters[0].yaml).toEqual({ from: 1, to: 2 });
    expect(s.chapters[1].yaml).toEqual({ from: 4, to: 10 });
    expect(s.chapters[1].actions.map((a) => a.yaml)).toEqual([{ from: 7, to: 8 }, { from: 9, to: 10 }]);
    expect(s.chapters[2].kind).toBe('verify');
  });

  test('a line seeks to the first action written on it, else to its chapter', () => {
    const s = story([
      objective('Open the cart', 1000),
      row({ label: 'tapOn', at: 1100 }),
    ], { originalYaml: yaml });
    const seeks = yamlSeekTimes(s);
    // Times are from the first row, so the chapter starts at 0.
    expect(seeks.get(7)).toBe(100);
    expect(seeks.get(4)).toBe(0);
    expect(seeks.get(1)).toBeUndefined();
  });

  test('lines are now, done, next or rest by where the playhead is in the chapter', () => {
    const chapter = { from: 4, to: 10 };
    const cue = { from: 7, to: 8 };
    expect(yamlLineState(7, chapter, cue, 8)).toBe('now');
    expect(yamlLineState(5, chapter, cue, 8)).toBe('done');
    expect(yamlLineState(10, chapter, cue, 8)).toBe('next');
    expect(yamlLineState(11, chapter, cue, 8)).toBe('rest');
  });

  test('highlighting splits keys, scalars, comments and variable references', () => {
    expect(yamlSegments('  - inputText: "{{code}}" # fill')).toEqual([
      { text: '  - ' },
      { text: 'inputText', type: 'key' },
      { text: ':', type: 'punct' },
      { text: ' ' },
      { text: '"', type: 'string' },
      { text: '{{code}}', type: 'string', variable: true },
      { text: '"', type: 'string' },
      { text: ' ' },
      { text: '# fill', type: 'comment' },
    ]);
    expect(yamlSegments('variable: code')).toContainEqual({ text: 'code', variable: true });
    expect(yamlSegments('    count: 3').at(-1)).toEqual({ text: '3', type: 'number' });
  });
});

describe('reading a story at a moment', () => {
  const s = story([
    objective('One', 0, { device: 'a' }),
    row({ label: 'tapOn', device: 'a', at: 500, ms: 300, mark: { kind: 'tap', x: 1, y: 1, dw: 10, dh: 10 } }),
    row({ label: 'tapOn', device: 'a', at: 1500, ms: 100 }),
    objective('Two', 4000, { device: 'b' }),
    row({ label: 'tapOn', device: 'b', at: 4500, ms: 100 }),
  ]);

  test('the chapter at a time is the last one started by then', () => {
    expect([0, 3999, 4000, 99_999].map((t) => chapterIndexAt(s.chapters, t))).toEqual([0, 0, 1, 1]);
  });

  test('a step jump parks where its screen has settled, and back returns to a step played well past', () => {
    expect(settledMs(s.chapters[0])).toBe(1600);
    expect(chapterStep(s.chapters, 100, 1)).toBe(1);
    expect(chapterStep(s.chapters, 3000, -1)).toBe(0);
    expect(chapterStep(s.chapters, 4100, -1)).toBe(0);
    expect(chapterStep(s.chapters, 9000, -1)).toBe(1);
  });

  test('a mark stays on its screen for a beat past its action, and only on the device that made it', () => {
    const a = s.chapters[0].actions;
    expect(markAt(a, 400)).toBeUndefined();
    expect(markAt(a, 500)?.tool).toBe('tapOn');
    expect(markAt(a, 1700)).toBeUndefined();
    expect(markAt(s.chapters[1].actions, 4600)).toBeUndefined();
  });

  test('consecutive identical lines collapse into one counted line', () => {
    const c = story([objective('x', 0), row({ label: 'wait', at: 100, mark: { kind: 'tap', x: 1, y: 1, dw: 2, dh: 2 } }), row({ label: 'wait', at: 200, mark: { kind: 'tap', x: 1, y: 1, dw: 2, dh: 2 } }), row({ label: 'tapOn', at: 300 })]).chapters[0];
    const lines = scriptLines(c);
    expect(lines.map((l) => [l.label, l.count])).toEqual([['Wait', 2], ['Tap on', 1]]);
  });

  test('the program cuts to each chapter\'s lead device, holding a shot at least a beat', () => {
    const quick = story([
      objective('One', 0, { device: 'a' }),
      row({ label: 'tapOn', device: 'a', at: 100 }),
      objective('Blink', 500, { device: 'b' }),
      row({ label: 'tapOn', device: 'b', at: 600 }),
      objective('Back', 700, { device: 'a' }),
      row({ label: 'tapOn', device: 'a', at: 800 }),
      objective('Later', 9000, { device: 'b' }),
      row({ label: 'tapOn', device: 'b', at: 9100 }),
    ]);
    const cuts = cameraCuts(quick.chapters);
    expect(cuts).toEqual([{ t: 0, device: 'a' }, { t: 9000, device: 'b' }]);
    expect(cutAt(cuts, 100)).toBe('a');
    expect(cutAt(cuts, 9500)).toBe('b');
  });

  test('a snapshot names who is on air, honors a pin, and previews without moving the playhead', () => {
    const cuts = cameraCuts(s.chapters);
    const base = snapshotAt(s, cuts, { t: 100, playing: true, rate: 1 }, { speed: 1 });
    expect(base).toMatchObject({ chapter: 0, onAir: 'a', playing: true, previewing: false });
    const pinned = snapshotAt(s, cuts, { t: 100, playing: true, rate: 1 }, { speed: 1, pinned: 'b' });
    expect(pinned.onAir).toBe('b');
    expect(pinned.inset).toBeUndefined();
    const peek = snapshotAt(s, cuts, { t: 100, playing: false, rate: 1 }, { speed: 1 }, 4500);
    expect(peek).toMatchObject({ chapter: 0, shown: 1, previewing: true, onAir: 'b' });
    expect(sameSnapshot(base, { ...base })).toBe(true);
    expect(sameSnapshot(base, pinned)).toBe(false);
  });
});

describe('insets and handoffs', () => {
  const two = story([
    objective('On the seller, show the code.', 0, { device: 'seller' }),
    row({ label: 'rememberText', device: 'seller', at: 500, ms: 100, args: 'variable: code' }),
    objective('On the buyer, wait for the sale.', 20_000, { device: 'buyer' }),
    row({ label: 'waitUntil', device: 'buyer', at: 20_500, ms: 4000 }),
    objective('On the buyer, enter the code.', 60_000, { device: 'buyer' }),
    row({ label: 'inputText', device: 'buyer', at: 60_100, args: 'text: "{{code}}"' }),
  ]);

  test('a long-held value shows only as its use comes up, on either end', () => {
    expect(insetAt(two, 30_000, 'buyer')).toBeUndefined();
    expect(insetAt(two, 56_000, 'buyer')).toMatchObject({ device: 'seller', reason: 'handoff', variable: 'code', from: 'seller' });
    expect(insetAt(two, 56_000, 'seller')).toMatchObject({ device: 'buyer', reason: 'handoff' });
  });

  test('a device waiting shows the one whose step it is waiting on', () => {
    expect(insetAt(two, 22_000, 'buyer')).toEqual({ device: 'seller', reason: 'meanwhile' });
  });

  test('one device never has an inset', () => {
    expect(insetAt(story([objective('x', 0)]), 0, 'device')).toBeUndefined();
  });

  test('a handoff lays out a box between the frames with an arrow into and out of it', () => {
    const zone = { x: 20, y: 40, w: 600, h: 400 };
    const cam = { x: 640, y: 40, w: 100, h: 200 };
    const result = handoffLayout(zone, { w: 120, h: 50 }, cam, { program: 2, cam: 2 }, true);
    expect(result.program.w).toBe(zone.w - 120 - 64);
    expect(result.node.x).toBeGreaterThan(result.program.x + 100);
    expect(result.node.x + result.node.w).toBeLessThanOrEqual(cam.x);
    expect(result.links).toHaveLength(2);
    for (const link of result.links) expect(link).toMatch(/^M[\d. -]+C/);
  });

  test('a frame fits its box by whichever side runs out first, centered', () => {
    const wide = fit({ x: 0, y: 0, w: 1000, h: 500 }, 2);
    expect(wide).toEqual({ x: 375, y: 0, s: 0.25 });
    const tall = fit({ x: 10, y: 10, w: 100, h: 1000 }, 2);
    expect(tall.s).toBeCloseTo(0.1);
  });
});

describe('the clock', () => {
  const s = story([
    objective('One', 0),
    row({ label: 'tapOn', at: 500, ms: 200 }),
    objective('Two', 30_000),
    row({ label: 'tapOn', at: 30_500, ms: 200 }),
  ]);
  const windows = lapseWindows(s);

  test('stretches where nothing starts or ends are time-lapse windows, kept clear of the moments around them', () => {
    expect(windows).toEqual([[1700, 28_500]]);
    expect(lapseEnd(windows, 5000)).toBe(28_500);
    expect(lapseEnd(windows, 28_500)).toBeUndefined();
    expect(lapseEnd(windows, 100)).toBeUndefined();
  });

  test('inside a window the clock runs at the lapse rate and stops at the window\'s end', () => {
    const fast = stepClock(5000, 8, 1, true, windows, 0.1);
    expect(fast.t).toBeCloseTo(5000 + 0.1 * 1000 * 8, 5);
    const late = stepClock(28_000, 8, 1, true, windows, 5);
    expect(late.t).toBe(28_500);
    const off = stepClock(5000, 1, 1, false, windows, 1);
    expect(off.t).toBeCloseTo(6000, 5);
  });

  test('the rate eases up toward a lapse and snaps down faster', () => {
    const up = stepClock(5000, 1, 1, true, windows, 0.1);
    expect(up.rate).toBeGreaterThan(1);
    expect(up.rate).toBeLessThan(8);
    const down = stepClock(100, 8, 1, true, windows, 0.1);
    expect(down.rate).toBeLessThan(8);
    expect(isLapsing(8, 1)).toBe(true);
    expect(isLapsing(1, 1)).toBe(false);
  });

  test('a recording plays when the clock is live and seeks back only past its drift', () => {
    const video = { currentTime: 10, duration: 60, paused: false, seeking: false };
    expect(syncVideo(video, 10.1, true, 1, 1)).toEqual({ play: true, rate: 1 });
    expect(syncVideo(video, 12, true, 1, 1)).toEqual({ seek: 12, play: true, rate: 1 });
    expect(syncVideo(video, 10, false, 1, 1)).toEqual({ play: false });
    expect(syncVideo(video, 10.1, false, 1, 1)).toEqual({ seek: 10.1, play: false });
    expect(syncVideo(video, 10, true, 8, 1)).toEqual({ play: false });
    expect(syncVideo(video, -5, true, 1, 1)).toEqual({ seek: 0, play: false });
    expect(syncVideo({ ...video, seeking: true }, 40, true, 1, 1).seek).toBeUndefined();
  });

  test('a device rebound mid-run shows the recording started last', () => {
    const rebound = story([objective('x', 0, { device: 'a' })], { clips: [{ startMs: T0, endMs: T0 + 5000, device: 'a' }, { startMs: T0 + 8000, endMs: T0 + 12_000, device: 'a' }] });
    const device = rebound.devices[0];
    expect(recordingAt(device, 100)?.clip).toBe(0);
    expect(recordingAt(device, 9000)?.clip).toBe(1);
  });

  test('clock text counts minutes and seconds', () => {
    expect([0, 59_999, 61_000, 3_725_000].map(clock)).toEqual(['0:00', '0:59', '1:01', '62:05']);
  });
});

describe('the on-air screen while the inset shows', () => {
  // A 1000x600 zone with the inset's corner at (760, 400).
  const zone = { x: 0, y: 0, w: 1000, h: 600 };
  const corner = { x: 760, y: 400 };

  test('a portrait phone takes the full-height strip beside the inset', () => {
    expect(besideInset(zone, corner, 2, 16)).toEqual({ x: 0, y: 0, w: 744, h: 600 });
  });

  test('a landscape screen takes the full-width strip above the inset', () => {
    expect(besideInset(zone, corner, 0.5, 16)).toEqual({ x: 0, y: 0, w: 1000, h: 384 });
  });

  test('a wide screen squeezed above would show bigger beside, and goes there', () => {
    // At 16:9 the strip above allows 384 tall (683 wide); beside allows the full 744 width.
    expect(besideInset(zone, corner, 9 / 16, 16)).toEqual({ x: 0, y: 0, w: 744, h: 600 });
  });
});

describe('what each device in a multi-device run is', () => {
  test('a binding driven by browser tools is a browser; the others stay unnamed', () => {
    const s = story([
      objective('Ring up a sale', 0, { device: 'phone' }),
      row({ label: 'tapOnElement', device: 'phone', at: 100 }),
      objective('Read it back', 1000, { device: 'dashboard' }),
      row({ label: 'shop_signIn', device: 'dashboard', at: 1100, children: [{ label: 'web_navigate', tool: 'url=/' }] }),
      row({ label: 'web_verifyElementVisible', device: 'dashboard', at: 1200 }),
    ]);
    expect(s.devices.map((device) => [device.name, device.platform])).toEqual([['phone', undefined], ['dashboard', 'web']]);
  });

  test('a desktop window is told apart from a browser', () => {
    const s = story([
      row({ label: 'tapOnElement', device: 'phone', at: 0 }),
      row({ label: 'playwright_desktop_click', device: 'app', at: 100 }),
    ]);
    expect(s.devices.find((device) => device.name === 'app')?.platform).toBe('desktop');
  });

  test('a single-device run keeps the platform the run reported', () => {
    const s = story([row({ label: 'web_navigate', at: 0 })], { meta: { title: 'x', status: 'passed', platform: 'android' } });
    expect(s.devices[0].platform).toBe('android');
  });
});

// Behavior tests for the Run trail dialog's device selection and multi-device launch
// (app/run-fanout.js): which devices a freshly opened dialog starts with, and what happens when one
// trail is launched on several devices at once. The run route takes one device per request, so a
// multi-device launch is one run per device - and every device gets its own outcome.
//
// No DOM and no daemon: the connect and dispatch round trips are injected, so each test asserts what
// the daemon was asked for and what the dialog is told back.
//
// Run: `bun test app/run-fanout.test.ts` from the web/ directory.
import { describe, expect, test } from 'bun:test';
// run-fanout.js dual-exports via module.exports; bun interops the CJS default import.
import Fanout from './run-fanout.js';

const IPHONE = { id: 'sim-1', name: 'iPhone 16', platform: 'ios' };
const PIXEL = { id: 'emu-1', name: 'Pixel 8', platform: 'android' };
const CHROME = { id: 'web-1', name: 'Chrome', platform: 'web' };
const ALL = [IPHONE, PIXEL, CHROME];

/** A connect that answers per device id, recording the handles it was asked to connect. */
function connector(answers: Record<string, unknown>) {
  const calls: any[] = [];
  return {
    calls,
    connect: async (tbId: any) => {
      calls.push(tbId);
      const answer = answers[tbId.instanceId];
      if (typeof answer === 'function') return (answer as () => unknown)();
      return answer === undefined ? { ok: true } : answer;
    },
  };
}

/** A dispatch that answers per device id, recording the handles it was asked to run on. */
function dispatcher(answers: Record<string, unknown>) {
  const calls: any[] = [];
  return {
    calls,
    dispatch: async (tbId: any) => {
      calls.push(tbId);
      const answer = answers[tbId.instanceId];
      if (typeof answer === 'function') return (answer as () => unknown)();
      return answer === undefined ? { ok: true, success: true, sessionId: `sess-${tbId.instanceId}` } : answer;
    },
  };
}

/**
 * A dispatch that leaves one device's answer to the test, so a deadline can be crossed on purpose
 * and the answer delivered afterwards - the shape of a daemon that is slow rather than dead.
 */
function deferredDispatcher(slowId: string) {
  let release: (answer: unknown) => void = () => {};
  const net = dispatcher({ [slowId]: () => new Promise((res) => { release = res; }) });
  return { calls: net.calls, dispatch: net.dispatch, release: (answer: unknown) => release(answer) };
}

const connectedAll = (devices: any[]) => devices.map((device) => ({ device, ok: true, error: null }));

describe('defaultDeviceIds — what a freshly opened dialog starts with', () => {
  test('the seeded device (a board launch for that variant) wins over every other source', () => {
    expect(Fanout.defaultDeviceIds({
      selected: [],
      devices: ALL,
      seedDeviceId: PIXEL.id,
      gtFirstDevice: IPHONE.id,
      pinnedId: CHROME.id,
    })).toEqual([PIXEL.id]);
  });

  test('falls back to the target picker\'s first device, then the pinned one, then the first connected', () => {
    const base = { selected: [], devices: ALL };
    expect(Fanout.defaultDeviceIds({ ...base, gtFirstDevice: PIXEL.id, pinnedId: CHROME.id })).toEqual([PIXEL.id]);
    expect(Fanout.defaultDeviceIds({ ...base, pinnedId: CHROME.id })).toEqual([CHROME.id]);
    expect(Fanout.defaultDeviceIds(base)).toEqual([IPHONE.id]);
  });

  test('skips a preference naming a device that is not connected', () => {
    expect(Fanout.defaultDeviceIds({
      selected: [],
      devices: [IPHONE, PIXEL],
      seedDeviceId: 'gone-1',
      gtFirstDevice: 'gone-2',
      pinnedId: PIXEL.id,
    })).toEqual([PIXEL.id]);
  });

  test('no connected devices means nothing is selected', () => {
    expect(Fanout.defaultDeviceIds({ selected: [], devices: [], seedDeviceId: IPHONE.id })).toEqual([]);
  });

  // The whole point of the multi-select: a pick of several devices must survive the device list
  // refreshing, rather than snapping back to one default.
  test('keeps a multi-device selection instead of re-defaulting it', () => {
    expect(Fanout.defaultDeviceIds({
      selected: [PIXEL.id, CHROME.id],
      devices: ALL,
      seedDeviceId: IPHONE.id,
      gtFirstDevice: IPHONE.id,
    })).toEqual([PIXEL.id, CHROME.id]);
  });

  test('drops a selected device that went away, keeping the rest', () => {
    expect(Fanout.defaultDeviceIds({ selected: [PIXEL.id, 'unplugged'], devices: ALL })).toEqual([PIXEL.id]);
  });

  test('re-defaults once the whole selection is gone', () => {
    expect(Fanout.defaultDeviceIds({ selected: ['unplugged'], devices: ALL, pinnedId: CHROME.id })).toEqual([CHROME.id]);
  });

  test('keeps a selection the user emptied on purpose empty', () => {
    // The device list is polled, so this runs again every refresh. Re-defaulting here would tick a
    // checkbox back on underneath the user and launch on a device they had just unchecked.
    expect(Fanout.defaultDeviceIds({
      selected: [],
      devices: ALL,
      seedDeviceId: PIXEL.id,
      pinnedId: CHROME.id,
      touched: true,
    })).toEqual([]);
  });

  test('still defaults for a dialog the user has not touched yet', () => {
    expect(Fanout.defaultDeviceIds({ selected: [], devices: ALL, pinnedId: CHROME.id, touched: false })).toEqual([CHROME.id]);
  });
});

describe('toggleDeviceId', () => {
  test('checking a device appends it, so the first checked stays the primary one', () => {
    expect(Fanout.toggleDeviceId([IPHONE.id], PIXEL.id)).toEqual([IPHONE.id, PIXEL.id]);
  });

  test('unchecking removes just that device', () => {
    expect(Fanout.toggleDeviceId([IPHONE.id, PIXEL.id], IPHONE.id)).toEqual([PIXEL.id]);
  });

  test('the last device can be unchecked — an empty selection is refused at launch, not prevented here', () => {
    expect(Fanout.toggleDeviceId([IPHONE.id], IPHONE.id)).toEqual([]);
    expect(Fanout.selectionError([])).toContain('at least one device');
  });
});

describe('appsDevice — which device the target-app picker reads', () => {
  test('reads the checked device that can host an app, not the web one checked first', () => {
    // The whole point: a web-first mixed selection used to offer no target at all, so the Android
    // device in the same run connected with none and failed.
    expect(Fanout.appsDevice([CHROME, PIXEL])).toBe(PIXEL);
  });

  test('keeps the first app-hosting device when several are checked', () => {
    expect(Fanout.appsDevice([IPHONE, PIXEL])).toBe(IPHONE);
  });

  test('a web-only selection has no device to read apps from', () => {
    expect(Fanout.appsDevice([CHROME])).toBeNull();
    expect(Fanout.appsDevice([])).toBeNull();
  });
});

describe('connectPlan — what to dial, and what to release first', () => {
  const plan = (args: Record<string, unknown>) => Fanout.connectPlan(args);

  test('dials nothing until the target app is resolved', () => {
    // A connect binds its target for the life of the connection, so dialing before the picker has
    // settled binds whatever the daemon had selected - and that binding then wins over the pick.
    expect(plan({ devices: [PIXEL], connected: {}, targetApp: null, targetReady: false })).toEqual({ drop: [], dial: [] });
    expect(plan({ devices: [PIXEL], connected: {}, targetApp: 'alpha-app', targetReady: true })).toEqual({ drop: [], dial: [PIXEL] });
  });

  test('never dials a web device: the browser is the device', () => {
    expect(plan({ devices: [CHROME, PIXEL], connected: {}, targetApp: 'alpha-app', targetReady: true }).dial).toEqual([PIXEL]);
  });

  test('leaves a device already connected under this target alone', () => {
    expect(plan({ devices: [PIXEL], connected: { [PIXEL.id]: 'alpha-app' }, targetApp: 'alpha-app', targetReady: true }))
      .toEqual({ drop: [], dial: [] });
  });

  test('releases a device this dialog bound to a different target', () => {
    // Re-dialing without releasing gets the live connection back unchanged, so the run would use
    // the instrumentation the OLD target installed.
    expect(plan({ devices: [PIXEL], connected: { [PIXEL.id]: 'beta-app' }, targetApp: 'alpha-app', targetReady: true }))
      .toEqual({ drop: [PIXEL], dial: [] });
  });

  test('a device with a dial already out is left to it', () => {
    expect(plan({ devices: [PIXEL], connected: {}, inFlight: { [PIXEL.id]: true }, targetApp: 'alpha-app', targetReady: true }))
      .toEqual({ drop: [], dial: [] });
  });

  test('a connection bound to no target is released once one is resolved', () => {
    expect(plan({ devices: [PIXEL], connected: { [PIXEL.id]: null }, targetApp: 'alpha-app', targetReady: true }).drop).toEqual([PIXEL]);
  });
});

describe('selectionError — an empty selection is refused, not silently ignored', () => {
  test('nothing checked gives the user a reason', () => {
    expect(Fanout.selectionError([])).toContain('at least one device');
    expect(Fanout.selectionError(null)).toContain('at least one device');
  });

  test('any checked device clears the refusal', () => {
    expect(Fanout.selectionError([IPHONE])).toBeNull();
    expect(Fanout.selectionError([IPHONE, PIXEL])).toBeNull();
  });
});

describe('retryDeviceIds — what stays checked after a partial launch', () => {
  test('keeps only the devices that did not start', () => {
    // The device that started is already running the trail. Leaving it checked makes the obvious
    // retry after "1 of 2 runs started" re-dispatch to it, and the busy gate turns the run that
    // succeeded on the first click into a failure row on the second.
    const outcomes = [
      { device: IPHONE, ok: true, sessionId: 'sess-1', error: null },
      { device: PIXEL, ok: false, sessionId: null, error: 'Run failed to start' },
    ];
    expect(Fanout.retryDeviceIds(outcomes)).toEqual([PIXEL.id]);
  });

  test('an all-failed launch keeps every device checked, so one click retries them all', () => {
    const outcomes = [IPHONE, PIXEL].map((device) => ({ device, ok: false, sessionId: null, error: 'nope' }));
    expect(Fanout.retryDeviceIds(outcomes)).toEqual([IPHONE.id, PIXEL.id]);
  });

  test('a device whose dispatch is still starting is not offered for retry', () => {
    // Retrying it either races the live dispatch or hits the busy gate once it lands, and either way
    // the run that did start gets reported as a failure.
    const outcomes = [
      { device: IPHONE, ok: false, slow: true, sessionId: null, error: 'still starting' },
      { device: PIXEL, ok: false, sessionId: null, error: 'Run failed to start' },
    ];
    expect(Fanout.retryDeviceIds(outcomes)).toEqual([PIXEL.id]);
  });
});

describe('launchSummary', () => {
  test('counts the runs that started', () => {
    expect(Fanout.launchSummary([
      { device: IPHONE, ok: true, sessionId: 'sess-1', error: null },
      { device: PIXEL, ok: false, sessionId: null, error: 'nope' },
    ])).toBe('1 of 2 runs started.');
  });

  test('says how many are still starting, so a slow dispatch is not counted as a failure', () => {
    expect(Fanout.launchSummary([
      { device: IPHONE, ok: true, sessionId: 'sess-1', error: null },
      { device: PIXEL, ok: false, slow: true, sessionId: null, error: 'still starting' },
    ])).toBe('1 of 2 runs started. 1 still starting.');
  });
});

describe('launchFailed', () => {
  test('is false when every device that has not started is merely slow', () => {
    // The summary is styled from this. A launch where nothing failed used to headline in red as
    // "0 of 2 runs started." while the rows underneath it correctly read as waiting.
    expect(Fanout.launchFailed([
      { device: IPHONE, ok: false, slow: true, sessionId: null, error: 'still starting' },
      { device: PIXEL, ok: false, slow: true, sessionId: null, error: 'still starting' },
    ])).toBe(false);
  });

  test('is true as soon as one device really failed, even alongside a slow one', () => {
    expect(Fanout.launchFailed([
      { device: IPHONE, ok: false, slow: true, sessionId: null, error: 'still starting' },
      { device: PIXEL, ok: false, sessionId: null, error: 'Could not connect.' },
    ])).toBe(true);
  });

  test('is false for a launch where everything started', () => {
    expect(Fanout.launchFailed([{ device: IPHONE, ok: true, sessionId: 'sess-1', error: null }])).toBe(false);
  });
});

describe('connectDevices', () => {
  test('never dials a web device: the browser is the device', async () => {
    // Connecting a web device is not a no-op, it fails - which would turn a runnable web selection
    // into "could not connect" and stop the dispatch.
    const net = connector({ 'web-1': { ok: false, error: 'no driver for web' } });
    const outcomes = await Fanout.connectDevices([CHROME, PIXEL], { connect: net.connect });

    expect(net.calls).toEqual([{ instanceId: 'emu-1', trailblazeDevicePlatform: 'ANDROID' }]);
    expect(outcomes.map((o: any) => [o.device.id, o.ok])).toEqual([['web-1', true], ['emu-1', true]]);
  });

  test('connects every selected device, each under its own platform-uppercased handle', async () => {
    const net = connector({});
    const outcomes = await Fanout.connectDevices([IPHONE, PIXEL], { connect: net.connect });

    expect(net.calls).toEqual([
      { instanceId: 'sim-1', trailblazeDevicePlatform: 'IOS' },
      { instanceId: 'emu-1', trailblazeDevicePlatform: 'ANDROID' },
    ]);
    expect(outcomes.map((o: any) => [o.device.id, o.ok])).toEqual([['sim-1', true], ['emu-1', true]]);
  });

  test('does not reconnect a device the dialog already connected', async () => {
    const net = connector({});
    const outcomes = await Fanout.connectDevices([IPHONE, PIXEL], {
      isConnected: (d: any) => d.id === IPHONE.id,
      connect: net.connect,
    });

    expect(net.calls.map((c: any) => c.instanceId)).toEqual(['emu-1']);
    expect(outcomes.every((o: any) => o.ok)).toBe(true);
  });

  test('keeps the daemon\'s own reason per device, and one unreachable device does not stop the others', async () => {
    const outcomes = await Fanout.connectDevices([IPHONE, PIXEL], {
      connect: connector({ 'sim-1': { ok: false, error: 'No target app selected.' } }).connect,
    });

    expect(outcomes[0]).toEqual({ device: IPHONE, ok: false, error: 'No target app selected.' });
    expect(outcomes[1].ok).toBe(true);
  });

  test('a daemon that never answers fails only that device, with a reason saying so', async () => {
    const outcomes = await Fanout.connectDevices([IPHONE, PIXEL], {
      connect: connector({ 'sim-1': () => '__timeout__' }).connect,
    });

    expect(outcomes[0].ok).toBe(false);
    expect(outcomes[0].error).toContain('did not respond');
    expect(outcomes[1].ok).toBe(true);
  });

  test('a thrown connect becomes that device\'s failure rather than rejecting the launch', async () => {
    const outcomes = await Fanout.connectDevices([IPHONE, PIXEL], {
      connect: connector({ 'emu-1': () => { throw new Error('adb went away'); } }).connect,
    });

    expect(outcomes[0].ok).toBe(true);
    expect(outcomes[1]).toEqual({ device: PIXEL, ok: false, error: 'adb went away' });
  });
});

describe('dispatchRuns — one run per device', () => {
  test('starts a run on each connected device and reports each session id', async () => {
    const net = dispatcher({});
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE, PIXEL, CHROME]), { dispatch: net.dispatch });

    // One request per device: the route takes a single device, so N devices is N runs.
    expect(net.calls).toEqual([
      { instanceId: 'sim-1', trailblazeDevicePlatform: 'IOS' },
      { instanceId: 'emu-1', trailblazeDevicePlatform: 'ANDROID' },
      { instanceId: 'web-1', trailblazeDevicePlatform: 'WEB' },
    ]);
    expect(outcomes.map((o: any) => [o.device.name, o.ok, o.sessionId])).toEqual([
      ['iPhone 16', true, 'sess-sim-1'],
      ['Pixel 8', true, 'sess-emu-1'],
      ['Chrome', true, 'sess-web-1'],
    ]);
  });

  test('a device that never connected is not dispatched to, and keeps its connect failure', async () => {
    const net = dispatcher({});
    const outcomes = await Fanout.dispatchRuns([
      { device: IPHONE, ok: false, error: 'No target app selected.' },
      { device: PIXEL, ok: true, error: null },
    ], { dispatch: net.dispatch });

    expect(net.calls.map((c: any) => c.instanceId)).toEqual(['emu-1']);
    expect(outcomes[0]).toEqual({ device: IPHONE, ok: false, sessionId: null, error: 'No target app selected.' });
    expect(outcomes[1].sessionId).toBe('sess-emu-1');
  });

  // The failure this exists for: one busy device must not swallow the run that did start elsewhere.
  test('one device\'s failure neither hides nor is hidden by another\'s session id', async () => {
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE, PIXEL]), {
      dispatch: dispatcher({
        'sim-1': { ok: false, error: 'This device is busy: a trail run is still using it.' },
      }).dispatch,
    });

    expect(outcomes[0]).toEqual({
      device: IPHONE,
      ok: false,
      sessionId: null,
      error: 'This device is busy: a trail run is still using it.',
    });
    expect(outcomes[1]).toEqual({ device: PIXEL, ok: true, sessionId: 'sess-emu-1', error: null });
  });

  test('an accepted request that started nothing is a failure, not a success', async () => {
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE, PIXEL, CHROME]), {
      dispatch: dispatcher({
        // 2xx, but the daemon says the run did not start.
        'sim-1': { ok: true, success: false, error: 'target is not registered' },
        // Accepted with no session to follow.
        'emu-1': { ok: true, success: true, sessionId: null },
        'web-1': { ok: false, error: 'deviceManager not available' },
      }).dispatch,
    });

    expect(outcomes.map((o: any) => o.ok)).toEqual([false, false, false]);
    expect(outcomes[0].error).toBe('target is not registered');
    expect(outcomes[1].error).toContain('failed to start');
    expect(outcomes[2].error).toBe('deviceManager not available');
  });

  test('a daemon that has not answered by the deadline holds up only that device, as still starting', async () => {
    // Not a failure: the daemon is still working on it (abandoning the request stops nothing), so it
    // can still mint a session. Calling it failed is what let the outcome list miss a run that
    // really started and let the retry race a live dispatch.
    const net = deferredDispatcher('sim-1');
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE, PIXEL]), { dispatch: net.dispatch, timeoutMs: 20 });

    expect(outcomes[0].slow).toBe(true);
    expect(outcomes[0].ok).toBe(false);
    expect(outcomes[0].sessionId).toBe(null);
    expect(outcomes[0].error).toContain('may still be starting');
    expect(outcomes[1]).toEqual({ device: PIXEL, ok: true, sessionId: 'sess-emu-1', error: null });
  });

  test('the session a still-starting dispatch goes on to create replaces its outcome', async () => {
    const net = deferredDispatcher('sim-1');
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE]), { dispatch: net.dispatch, timeoutMs: 20 });
    net.release({ ok: true, success: true, sessionId: 'sess-late' });

    expect(await outcomes[0].settled).toEqual({ device: IPHONE, ok: true, sessionId: 'sess-late', error: null });
  });

  test('a still-starting dispatch that then fails settles as that failure, with the daemon\'s reason', async () => {
    const net = deferredDispatcher('sim-1');
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE]), { dispatch: net.dispatch, timeoutMs: 20 });
    net.release({ ok: true, success: false, error: 'target is not registered' });

    expect(await outcomes[0].settled).toEqual({ device: IPHONE, ok: false, sessionId: null, error: 'target is not registered' });
  });

  test('a thrown dispatch becomes that device\'s failure rather than rejecting the launch', async () => {
    const outcomes = await Fanout.dispatchRuns(connectedAll([IPHONE, PIXEL]), {
      dispatch: dispatcher({ 'emu-1': () => { throw new Error('socket closed'); } }).dispatch,
    });

    expect(outcomes[0].ok).toBe(true);
    expect(outcomes[1]).toEqual({ device: PIXEL, ok: false, sessionId: null, error: 'socket closed' });
  });

  test('nothing to launch means no requests and no outcomes to report', async () => {
    const net = dispatcher({});
    expect(await Fanout.dispatchRuns([], { dispatch: net.dispatch })).toEqual([]);
    expect(net.calls).toEqual([]);
  });
});

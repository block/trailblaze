// Device selection + multi-device launch for the Run trail dialog.
//
// The daemon's run route takes ONE device per request (RunRequest.trailblazeDeviceId, and its
// response is just the new session's id), so running a trail on several devices is several requests
// - one run, one session, one outcome per device. That makes per-device outcomes the contract here:
// a device that never connected is never dispatched to and must not be reported as started, and one
// device's failure must not hide the session ids of the others.
//
// Plain JS (classic <script>, no transpile step) with a CommonJS tail so app/run-fanout.test.ts can
// require it. Every side effect (connect, dispatch) is injected by the caller, so the fan-out is
// exercised with plain inputs and no daemon.
(function () {
  'use strict';

  // TB.withTimeout resolves to this instead of rejecting when the daemon never answers.
  var TIMEOUT = '__timeout__';

  // Deadline for both launch-path round trips. The RPC layer has no timeout of its own, so a wedged
  // daemon or device would otherwise leave the dialog on "Starting…" forever.
  var RUN_TIMEOUT_MS = 45000;

  function errorText(e) {
    return (e && e.message) || String(e);
  }

  // The daemon's handle for a device, on both the connect and the run request.
  function deviceRunId(device) {
    return {
      instanceId: device.id,
      trailblazeDevicePlatform: String((device && device.platform) || '').toUpperCase(),
    };
  }

  // A web device has no driver to dial: the browser IS the device, durable across sessions, so the
  // dialog treats it as always ready. Connecting one turns a perfectly runnable web selection into
  // a connect failure, which is why every other surface skips it too.
  function needsConnect(device) {
    return String((device && device.platform) || '').toLowerCase() !== 'web';
  }

  // Which device the dialog reads its installed target apps from: the first checked device that
  // can host an app. A web device has no installed apps to list, so a selection that starts with
  // one (a mixed-platform run picked web first) would otherwise offer no target at all - and then
  // the Android device in the same run connects with no target and fails.
  function appsDevice(devices) {
    return (devices || []).filter(function (d) { return needsConnect(d); })[0] || null;
  }

  // Which selected devices to dial, and which live connections to drop first, for the target app
  // the dialog has resolved.
  //
  // A connect BINDS its target app for the life of the connection - the daemon reuses a live
  // connection rather than rebinding it - so this decides two things the dialog can't get right by
  // dialing eagerly: nothing is dialed before the target is known (`targetReady`), and a device
  // this dialog connected under a DIFFERENT target is dropped so the next pass can re-dial it for
  // this one. Devices the dialog didn't connect are absent from `connected` and so are never
  // dropped - another surface's connection is not ours to close.
  //
  // `connected` maps a device id to the target app id it was connected under (null for "no target
  // resolved then"); `inFlight` marks a dial already out.
  function connectPlan(args) {
    var a = args || {};
    var connected = a.connected || {};
    var inFlight = a.inFlight || {};
    var drop = [];
    var dial = [];
    (a.devices || []).forEach(function (device) {
      if (!needsConnect(device) || inFlight[device.id]) return;
      if (Object.prototype.hasOwnProperty.call(connected, device.id)) {
        if (connected[device.id] !== a.targetApp) drop.push(device);
        return;
      }
      if (a.targetReady) dial.push(device);
    });
    return { drop: drop, dial: dial };
  }

  /**
   * The in-flight connect promises a launch for `targetApp` must wait out before it can decide
   * which devices to release, given the dialog's `inFlight` map of `{ release | target, p }` entries.
   *
   * Only a release (`release: true`) or a dial binding some OTHER target qualifies. A dial already
   * binding this launch's own target cannot be the stale binding a release plan looks for, so
   * waiting on it decides nothing and only spends the launch's deadline - which is what used to
   * cancel the run of a device slow enough that the dialog was still dialing it when Run was
   * clicked. Such a device is left to `connectDevices`, which waits without a deadline.
   */
  function blockingDials(devices, inFlight, targetApp) {
    var map = inFlight || {};
    return (devices || [])
      .map(function (d) { return map[d.id]; })
      .filter(function (e) { return e && (e.release || e.target !== targetApp); })
      .map(function (e) { return e.p; });
  }

  // Which devices a freshly opened dialog starts with, as a list of device ids.
  //
  // Preference order for the default, unchanged from when a run took a single device: the seeded
  // device (launched from the board for that variant), then the target picker's first device, then
  // the pinned device, then the first connected device. An existing selection is kept as-is (minus
  // any device that went away) - that is the user's own pick, possibly of several devices.
  function defaultDeviceIds(args) {
    var a = args || {};
    var devices = a.devices || [];
    var connected = function (id) {
      return !!(id && devices.some(function (d) { return d.id === id; }));
    };
    var kept = (a.selected || []).filter(connected);
    if (kept.length > 0) return kept;
    // An empty selection the user emptied themselves is intent, not an absent default. Without
    // this, the next device poll re-checks a device underneath them and the empty-selection
    // refusal can never be seen.
    if (a.touched) return [];
    var fallback = connected(a.seedDeviceId) ? a.seedDeviceId
      : connected(a.gtFirstDevice) ? a.gtFirstDevice
        : connected(a.pinnedId) ? a.pinnedId
          : (devices[0] ? devices[0].id : null);
    return fallback ? [fallback] : [];
  }

  // Check/uncheck one device, preserving the order the user picked them in. The first selected
  // device is the dialog's primary one: it drives the target-app picker and the YAML preview.
  function toggleDeviceId(selected, id) {
    var cur = selected || [];
    return cur.indexOf(id) >= 0
      ? cur.filter(function (x) { return x !== id; })
      : cur.concat([id]);
  }

  // Why a launch can't start, or null when it can. The dialog keeps Run enabled with nothing
  // checked so an empty selection is REFUSED with a reason the user can act on, rather than being a
  // disabled button (or a click that quietly does nothing).
  function selectionError(devices) {
    return (devices || []).length === 0 ? 'Choose at least one device to run this trail on.' : null;
  }

  // What stays checked after a partial launch: only the devices that didn't start. A device that
  // started IS running the trail, so re-dispatching to it just hits the busy gate and reports the
  // run that succeeded as a failure. A device whose dispatch is merely slow (`o.slow`) is in that
  // same position - it may be seconds from a session - so it is not offered for retry either.
  function retryDeviceIds(outcomes) {
    return (outcomes || [])
      .filter(function (o) { return !o.ok && !o.slow; })
      .map(function (o) { return o.device.id; });
  }

  // Whether a launch actually failed, as opposed to not having finished. A device that is merely
  // slow has not failed - the daemon just hasn't answered - so a launch whose only non-started
  // devices are slow must not be presented as a failure. The rows already draw that distinction;
  // this is the same distinction for the summary above them.
  function launchFailed(outcomes) {
    return (outcomes || []).some(function (o) { return !o.ok && !o.slow; });
  }

  // The one-line summary above the outcome rows. Derived from the outcomes rather than counted at
  // launch time, because a slow dispatch settles later and the summary has to move with it.
  function launchSummary(outcomes) {
    var list = outcomes || [];
    var started = list.filter(function (o) { return o.ok; }).length;
    var starting = list.filter(function (o) { return o.slow; }).length;
    return started + ' of ' + list.length + ' runs started.' +
      (starting ? ' ' + starting + ' still starting.' : '');
  }

  // Connect every selected device, in parallel, keeping the daemon's own failure reason per device
  // (e.g. "No target app selected...") rather than a generic message nobody can act on.
  //
  // `isConnected(device)` skips a device this dialog already connected; `connect(tbId)` resolves the
  // daemon's detailed { ok, error } answer, or TIMEOUT.
  // Like dispatchRuns below, `connect(tbId)` is handed over WITHOUT a deadline, because the deadline
  // belongs here: a connect that blows it has NOT failed. A device that is slow to answer is usually
  // just slow - a fresh install has to finish installing, and the daemon goes on waiting for it -
  // and the connect then succeeds seconds later. Deadlining at the call site threw that promise away
  // and reported the device unreachable, which cancelled a run that would have started. Such a
  // device comes back as `slow` instead, carrying the still-running promise as `settled`, so the
  // launch can wait it out rather than refuse.
  async function connectDevices(devices, deps) {
    var d = deps || {};
    var deadlineMs = d.timeoutMs || RUN_TIMEOUT_MS;
    return await Promise.all((devices || []).map(async function (device) {
      var ok = { device: device, ok: true, error: null };
      if (!needsConnect(device)) return ok;
      if (d.isConnected && d.isConnected(device)) return ok;
      var settled = (async function () {
        try {
          var conn = await d.connect(deviceRunId(device));
          // Kept for a caller that still deadlines its own connect: that promise is gone either way,
          // so there is nothing to wait out and it stays a plain failure.
          if (conn === TIMEOUT) {
            return {
              device: device,
              ok: false,
              error: 'The daemon did not respond after 45s while connecting to the device. The device driver may be wedged - check the device and try again.',
            };
          }
          if (!conn || !conn.ok) return { device: device, ok: false, error: (conn && conn.error) || 'Could not connect to the device.' };
          return ok;
        } catch (e) {
          return { device: device, ok: false, error: errorText(e) };
        }
      })();
      var timer;
      var early = await Promise.race([
        settled,
        new Promise(function (res) { timer = setTimeout(function () { res(TIMEOUT); }, deadlineMs); }),
      ]);
      clearTimeout(timer);
      if (early !== TIMEOUT) return early;
      return {
        device: device,
        ok: false,
        slow: true,
        settled: settled,
        error: 'The device has not finished connecting after ' + Math.round(deadlineMs / 1000) +
          's. It may still be starting up - the run begins on its own once the device answers.',
      };
    }));
  }

  // One run per device, in parallel, from the connect outcomes above: a device that never connected
  // is NOT dispatched to and carries its connect failure through, so the caller ends up with
  // exactly one outcome per device it was asked to run on, in selection order.
  //
  // `dispatch(tbId)` resolves the run route's { ok, success, sessionId, error } answer. Unlike
  // `connect`, it is handed over WITHOUT a deadline: the deadline belongs here, because what happens
  // past it is part of the outcome. A dispatch the browser gives up on keeps running on the daemon -
  // the run route has no cancellation handle, and abandoning the fetch stops nothing (Ktor's Netty
  // engine does not cancel a handler when the client disconnects) - so it can still mint a session.
  // Reporting that as a failure is what made the outcome list miss a run that really started, and
  // what let the obvious retry race the dispatch it had been told was dead. Such a device comes back
  // as `slow` instead, carrying the still-running promise as `settled` so the caller can replace the
  // row with the real answer when it lands.
  //
  // `deps.isLive()` says whether the launch this belongs to is still worth finishing. Required, not
  // optional: the only thing it guards is a dispatch the caller can no longer see, so a caller that
  // forgot to pass it should fail loudly here rather than quietly lose the guard.
  async function dispatchRuns(connects, deps) {
    var d = deps || {};
    // Checked up front rather than where it is used, which is inside the slow-connect branch only.
    // A caller that forgot it would otherwise work on every normal launch and throw on the rare slow
    // one - and throw from inside `settled`, which the single-device caller chains with `.then` and
    // no catch, so it would surface as an unhandled rejection and a card that never resolves.
    if (typeof d.isLive !== 'function') throw new Error('dispatchRuns requires deps.isLive()');
    var deadlineMs = d.timeoutMs || RUN_TIMEOUT_MS;
    return await Promise.all((connects || []).map(async function (c) {
      var failed = function (error) { return { device: c.device, ok: false, sessionId: null, error: error }; };
      // A connect that merely ran long is not a connect that failed, so its run is not written off.
      if (!c.ok && !(c.slow && c.settled)) return failed(c.error);
      var settled = (async function () {
        // Wait the slow connect out, then dispatch if the device did come up. Inside `settled` so
        // the caller is never held by it: that promise is handed back below and reconciled when it
        // lands, exactly like a slow dispatch.
        if (c.slow && c.settled) {
          var late = await c.settled;
          // That wait has no upper bound - a cold emulator can take minutes - and the launch it
          // belongs to is stoppable the entire time. Dispatching a device that finally answered
          // after the user gave up starts a run nobody is watching, which is the one thing the
          // undeadlined wait must not buy. `launchRetry` re-checks between its own steps for
          // exactly this reason; a wait this long needs it more, not less.
          // `abandoned`, not `failed`: there is no longer anyone to report a failure TO. The card
          // this belonged to has either been stopped or aged out, and both `failPendingRun` and the
          // Active screen's error rendering ignore the TTL - so calling this a failure would put a
          // card the user already dismissed back on screen, minutes later, to say the run they
          // stopped was stopped. Checked before the connect's own outcome, because that reasoning
          // does not depend on how the wait ended: an abandoned launch has nobody to tell either way.
          if (!d.isLive()) return { device: c.device, ok: false, abandoned: true, sessionId: null, error: null };
          if (!late.ok) return failed(late.error);
        }
        try {
          var r = await d.dispatch(deviceRunId(c.device));
          // success:false is a dispatch failure too, not just ok:false (a non-2xx answer).
          if (r && r.ok !== false && r.success !== false && r.sessionId) {
            return { device: c.device, ok: true, sessionId: r.sessionId, error: null };
          }
          return failed((r && r.error) || 'Run failed to start');
        } catch (e) {
          return failed(errorText(e));
        }
      })();
      // A device that already blew the connect deadline has had its wait; report it slow at once
      // rather than holding the whole launch for a second deadline on top of the first.
      if (c.slow) {
        return { device: c.device, ok: false, slow: true, sessionId: null, settled: settled, error: c.error };
      }
      var timer;
      var early = await Promise.race([
        settled,
        new Promise(function (res) { timer = setTimeout(function () { res(TIMEOUT); }, deadlineMs); }),
      ]);
      clearTimeout(timer); // a dispatch that answered in time leaves no 45s timer behind
      if (early !== TIMEOUT) return early;
      return {
        device: c.device,
        ok: false,
        slow: true,
        sessionId: null,
        settled: settled,
        error: 'The daemon has not answered after ' + Math.round(deadlineMs / 1000) +
          's. The run may still be starting - this row updates itself when the daemon answers, and the Active tab shows the session either way.',
      };
    }));
  }

  var api = {
    RUN_TIMEOUT_MS: RUN_TIMEOUT_MS,
    deviceRunId: deviceRunId,
    needsConnect: needsConnect,
    appsDevice: appsDevice,
    connectPlan: connectPlan,
    blockingDials: blockingDials,
    defaultDeviceIds: defaultDeviceIds,
    toggleDeviceId: toggleDeviceId,
    selectionError: selectionError,
    retryDeviceIds: retryDeviceIds,
    launchSummary: launchSummary,
    launchFailed: launchFailed,
    connectDevices: connectDevices,
    dispatchRuns: dispatchRuns,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbRunFanout = api;               // browser classic script
})();

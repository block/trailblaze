// Which run the Active screen should follow after a launch. Pure, because every branch here is a bug
// that shipped: the screen followed a run that was already going, or followed nothing and sat on
// "Starting…" until the pending marker went stale. Driving the app was the only way to see either.
//
// The caller owns the state - it passes the tick counters in and applies the answer:
//   { kind: 'follow',  sessionId, locked, restartWatchdog }  dispatch named a session; select it
//   { kind: 'abandon', sessionId }                           it named one that never appeared
//   { kind: 'guess',   sessionId }                           no id is coming; newest running row
//   { kind: 'giveUp',  sessionId }                           nothing left to wait for (may be null)
//   { kind: 'wait' }                                         poll again
//
// Dual-exported like target-picker-model.js: `window.FollowRunModel` for the classic-script app and
// `module.exports` for Bun tests.
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.FollowRunModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  // Ticks (~1.2s each) to wait for the row after dispatch named a session, and to wait for an id at
  // all before the newest-running guess is the only thing left.
  var ROW_WAIT_TICKS = 20;
  var ID_WAIT_TICKS = 16;

  function decide(input) {
    var data = input.rows || [];
    var attempts = input.attempts || 0;
    // An expired marker is not evidence about anything. Its card is gone and the loop that was
    // following it has already given up, but it stays installed - so a later launch that navigates
    // here without a marker of its own (a multi-device run, or "View active runs") would otherwise
    // inherit it: waiting out its dispatch, and following ITS session if that ever answers, instead
    // of the run the user just started. Only the error survives expiry, and that belongs to the card.
    var p = input.pending;
    if (p && !p.error && input.now - p.at >= input.ttlMs) p = null;
    if (p && p.sessionId) {
      // The watchdog counts ticks spent waiting for the ROW, so it must not inherit the ticks spent
      // waiting for the DISPATCH: an id that arrives at 45s would otherwise land with the counter
      // already past the watchdog and abandon a run that just started.
      var restartWatchdog = input.followed !== p.sessionId;
      var present = data.some(function (s) { return s.id === p.sessionId; });
      if (present) return { kind: 'follow', sessionId: p.sessionId, locked: true, restartWatchdog: restartWatchdog };
      // The daemon accepted the run (it minted an id) but the session never materialized - it died
      // before writing its first log. Without this the card sits on "Initializing run…" forever.
      if (!restartWatchdog && attempts > ROW_WAIT_TICKS) return { kind: 'abandon', sessionId: p.sessionId };
      return { kind: 'follow', sessionId: p.sessionId, locked: false, restartWatchdog: restartWatchdog };
    }
    // Skipped only while a marker is waiting for a dispatch that WILL report an id, because that id
    // is authoritative and this guess is not: rows come back newest-first, so a run that was ALREADY
    // going satisfies it, and locking onto that leaves the real answer nowhere to land. A marker that
    // failed, or that never promised an id (a retry dispatches without reporting one), has nothing
    // left to wait for - holding the guess back for it is what parks the screen on "Starting…".
    var waiting = !!(p && !p.error && p.awaitsDispatch);
    var newest = data[0];
    if (!waiting && newest && newest.status === 'running') return { kind: 'guess', sessionId: newest.id };
    // Never while still waiting, so giving up waits out the marker rather than these ticks: a
    // dispatch can take longer than they allow to answer with the session it started, and stopping
    // before then is how a run that really started stopped being followed. Expiry is what ends the
    // wait, and it ends it above by dropping the marker.
    if (attempts > ID_WAIT_TICKS && !waiting) return { kind: 'giveUp', sessionId: newest ? newest.id : null };
    return { kind: 'wait' };
  }

  return {
    decide: decide,
    ROW_WAIT_TICKS: ROW_WAIT_TICKS,
    ID_WAIT_TICKS: ID_WAIT_TICKS,
  };
});

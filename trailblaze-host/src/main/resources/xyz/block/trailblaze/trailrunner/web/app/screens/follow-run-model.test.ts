import { describe, expect, test } from "bun:test";
import FollowRunModel from "./follow-run-model.js";

const TTL = 90000;
const NOW = 1_700_000_000_000;

// A marker as recordPendingRun writes it. `awaitsDispatch` is the caller promising to patch a session
// id on later; without it, no id is ever coming for this marker.
const marker = (over = {}) => ({ title: "Checkout", at: NOW, error: null, sessionId: null, awaitsDispatch: false, ...over });
const running = (id: string) => ({ id, status: "running" });

const decide = (over = {}) => FollowRunModel.decide({ pending: null, rows: [], attempts: 0, followed: null, now: NOW, ttlMs: TTL, ...over });

describe("a dispatch that reports its session id", () => {
  test("is followed before its row exists, and locked once it appears", () => {
    const pending = marker({ sessionId: "s-new", awaitsDispatch: true });

    const early = decide({ pending, rows: [running("s-old")] });
    expect(early).toMatchObject({ kind: "follow", sessionId: "s-new", locked: false });

    const arrived = decide({ pending, rows: [running("s-new")], followed: "s-new" });
    expect(arrived).toMatchObject({ kind: "follow", sessionId: "s-new", locked: true });
  });

  test("is locked onto a row that already finished, not just a running one", () => {
    // The run can outpace the poll: by the first tick after dispatch answered it may already be
    // done, and a lock that only accepts `running` would never fire and would abandon it.
    const d = decide({ pending: marker({ sessionId: "s-fast", awaitsDispatch: true }), rows: [{ id: "s-fast", status: "failed" }], followed: "s-fast" });
    expect(d).toMatchObject({ kind: "follow", locked: true });
  });

  test("is abandoned once the row never shows up", () => {
    const pending = marker({ sessionId: "s-dead", awaitsDispatch: true });
    const attempts = FollowRunModel.ROW_WAIT_TICKS + 1;
    expect(decide({ pending, attempts, followed: "s-dead" })).toEqual({ kind: "abandon", sessionId: "s-dead" });
  });

  test("gets the full row wait even when the id arrives late", () => {
    // The counter has been running since the launch. A slow dispatch answering near the deadline
    // would otherwise be abandoned on the very tick it reported a session that had just started.
    const pending = marker({ sessionId: "s-slow", awaitsDispatch: true });
    const attempts = FollowRunModel.ROW_WAIT_TICKS + 1;
    expect(decide({ pending, attempts, followed: null })).toMatchObject({ kind: "follow", sessionId: "s-slow", restartWatchdog: true });
  });
});

describe("the newest-running guess", () => {
  test("is held back while a dispatch is still going to report an id", () => {
    // The rows are newest-first, so a run that was already going satisfies the guess. Locking onto
    // it leaves the id dispatch is about to report with nowhere to land.
    const d = decide({ pending: marker({ awaitsDispatch: true }), rows: [running("s-unrelated")] });
    expect(d).toEqual({ kind: "wait" });
  });

  test("is taken immediately for a marker that will never carry an id", () => {
    // A retry dispatches without reporting a session id, so this guess is the only follow mechanism
    // it has. Waiting on a promise nobody made parked the retry on "Starting…" for the whole TTL.
    const d = decide({ pending: marker(), rows: [running("s-retried")] });
    expect(d).toEqual({ kind: "guess", sessionId: "s-retried" });
  });

  test("is taken once a waiting marker has failed", () => {
    const d = decide({ pending: marker({ awaitsDispatch: true, error: "Could not connect." }), rows: [running("s-other")] });
    expect(d).toEqual({ kind: "guess", sessionId: "s-other" });
  });

  test("is not taken when the newest row is already finished", () => {
    expect(decide({ pending: marker(), rows: [{ id: "s-done", status: "succeeded" }] })).toEqual({ kind: "wait" });
  });
});

describe("giving up", () => {
  test("waits past the tick budget while the marker is still fresh", () => {
    // A dispatch can take longer to answer than this budget, and stopping before the marker itself
    // goes stale is how a run that really started stopped being followed.
    const d = decide({ pending: marker({ awaitsDispatch: true }), attempts: FollowRunModel.ID_WAIT_TICKS + 1 });
    expect(d).toEqual({ kind: "wait" });
  });

  test("stops once the marker is stale, selecting whatever is newest", () => {
    const d = decide({
      pending: marker({ awaitsDispatch: true }),
      rows: [{ id: "s-newest", status: "succeeded" }],
      attempts: FollowRunModel.ID_WAIT_TICKS + 1,
      now: NOW + TTL + 1,
    });
    expect(d).toEqual({ kind: "giveUp", sessionId: "s-newest" });
  });

  test("stops with nothing selected when there are no rows at all", () => {
    const d = decide({ pending: marker(), attempts: FollowRunModel.ID_WAIT_TICKS + 1 });
    expect(d).toEqual({ kind: "giveUp", sessionId: null });
  });
});

describe("an expired marker", () => {
  // It stays installed after the loop following it gives up, so a later launch that arrives here with
  // no marker of its own inherits it. Treating that as evidence made the new launch wait out a dead
  // dispatch, and follow ITS session if it ever answered.
  const expired = { pending: marker({ awaitsDispatch: true }), now: NOW + TTL };

  test("does not hold the guess back from the run that just started", () => {
    expect(decide({ ...expired, rows: [running("s-brand-new")] })).toEqual({ kind: "guess", sessionId: "s-brand-new" });
  });

  test("is not followed even once its dispatch finally answers", () => {
    const d = decide({ ...expired, pending: marker({ sessionId: "s-long-gone", awaitsDispatch: true }), rows: [running("s-brand-new")] });
    expect(d).toEqual({ kind: "guess", sessionId: "s-brand-new" });
  });

  test("gives up immediately rather than after another full tick budget", () => {
    expect(decide({ ...expired, attempts: FollowRunModel.ID_WAIT_TICKS + 1 })).toEqual({ kind: "giveUp", sessionId: null });
  });

  test("still counts when it carries an error, because that card is still on screen", () => {
    // Expiry hides a waiting card, not a failed one - the user has to be able to read why it failed.
    const d = decide({ ...expired, pending: marker({ awaitsDispatch: true, error: "Could not connect." }), rows: [running("s-other")] });
    expect(d).toEqual({ kind: "guess", sessionId: "s-other" });
  });
});

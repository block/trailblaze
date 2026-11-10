// A survey reads the part of a run its `scope` names: by default the trail's own steps, so what the
// trailhead did to set the run up (signing in, a first sync) is never reported as what it tested.

import { afterAll, describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { RunPhase, SurveyScope, SurveySession, eventStream, phaseOf, runSurveys, scopedSession, survey, trailStartOf, trailblaze } from "./index.js";
import { renderMarkdown, renderSummaryLines } from "./report.js";
import { withId } from "./survey.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus";
const T0 = 1_700_000_000_000;
const iso = (ms: number) => new Date(ms).toISOString();
const ndjson = (rows: unknown[]) => rows.map((r) => JSON.stringify(r)).join("\n") + "\n";
const dirs: string[] = [];
afterAll(() => dirs.forEach((d) => rmSync(d, { recursive: true, force: true })));

const status = (ms: number, cls: string) => ({ class: `${LOG}.TrailblazeSessionStatusChangeLog`, session: "s", timestamp: iso(ms), sessionStatus: { class: `${STATUS}.${cls}` } });
/** A tool that ran at host time [ms]; with [deviceAheadMs], stamped by a device clock that far ahead, as an on-device run logs it. */
const tool = (ms: number, toolName: string, deviceAheadMs?: number) => ({
  class: `${LOG}.TrailblazeToolLog`,
  session: "s",
  timestamp: iso(ms + (deviceAheadMs ?? 0)),
  ...(deviceAheadMs === undefined ? {} : { clock: "device", hostReceivedAt: iso(ms + 10) }),
  toolName,
  trailblazeTool: { toolName, raw: {} },
  successful: true,
  durationMs: 10,
});
const SUCCESS = "xyz.block.trailblaze.AgentTaskStatus.Success.ObjectiveComplete";
const FAILURE = "xyz.block.trailblaze.AgentTaskStatus.Failure.MaxCallsLimitReached";
const objective = (ms: number, type: "ObjectiveStartLog" | "ObjectiveCompleteLog", step: string, isTrailhead = false, result = SUCCESS) => ({
  class: `${LOG}.${type}`,
  session: "s",
  timestamp: iso(ms),
  promptStep: { step, ...(isTrailhead ? { isTrailhead: true } : {}) },
  ...(type === "ObjectiveCompleteLog" ? { objectiveResult: { class: result } } : {}),
});
/** The device setup's objective, which the runner marks `isSetup`. */
const setupObjective = (ms: number, type: "ObjectiveStartLog" | "ObjectiveCompleteLog", result = SUCCESS) => ({
  ...objective(ms, type, "Device setup: reset the app"),
  promptStep: { step: "Device setup: reset the app", isSetup: true },
});

interface Run {
  /** A device setup before the trailhead: started at T0+20, `resetApp` at T0+40, completed at T0+60 unless "unfinished". */
  setup?: boolean | "unfinished";
  /** The trailhead objective: started at T0+100, completed at T0+1000 unless `trailheadDone` is false. */
  trailhead?: boolean;
  trailheadDone?: boolean;
  /** The trailhead objective's result class when it completes. */
  trailheadResult?: string;
  ended?: string;
  /** Rows of `events/trailhead.ndjson`. */
  trailheadStream?: object[];
  network?: object[];
  /** Other `events/<name>.ndjson` streams. */
  events?: Record<string, object[]>;
  /** Stamps the tool logs with a device clock this far ahead of the host's, anchored by `hostReceivedAt`. */
  deviceAheadMs?: number;
  /** Logs after the trail's `pay` tool, before the run ends. */
  later?: object[];
  /** Stamps the tools and objectives with the device's clock and nothing anchoring it, as an on-device Android run logs them. */
  onDevice?: boolean;
}

/** A run that signs in during its trailhead (T0+500) and pays in its trail (T0+2000). */
function run({ setup = false, trailhead = true, trailheadDone = true, trailheadResult = SUCCESS, ended = "Ended.Succeeded", trailheadStream, network, events, deviceAheadMs, later = [], onDevice = false }: Run = {}): SurveySession {
  const dir = mkdtempSync(join(tmpdir(), "phase-"));
  dirs.push(dir);
  const logs = [
    status(T0, "Started"),
    ...(setup ? [setupObjective(T0 + 20, "ObjectiveStartLog"), tool(T0 + 40, "resetApp", deviceAheadMs)] : []),
    ...(setup === true ? [setupObjective(T0 + 60, "ObjectiveCompleteLog")] : []),
    ...(trailhead ? [objective(T0 + 100, "ObjectiveStartLog", "Sign in", true)] : []),
    tool(T0 + 500, "signIn", deviceAheadMs),
    ...(trailhead && trailheadDone ? [objective(T0 + 1000, "ObjectiveCompleteLog", "Sign in", true, trailheadResult)] : []),
    tool(T0 + 2000, "pay", deviceAheadMs),
    ...later,
    status(T0 + 3000, ended),
  ];
  const stamped = onDevice ? logs.map((log) => (/(Tool|Objective\w+)Log$/.test(log.class) ? { ...log, clock: "device" } : log)) : logs;
  stamped.forEach((log, i) => writeFileSync(join(dir, `${String(i).padStart(3, "0")}_Log.json`), JSON.stringify(log)));
  const streams = { ...(events ?? {}), ...(trailheadStream ? { trailhead: trailheadStream } : {}) };
  if (Object.keys(streams).length) mkdirSync(join(dir, "events"), { recursive: true });
  for (const [name, rows] of Object.entries(streams)) writeFileSync(join(dir, "events", `${name}.ndjson`), ndjson(rows));
  if (network) writeFileSync(join(dir, "network.ndjson"), ndjson(network));
  return SurveySession.load(dir);
}

const toolsIn = (session: SurveySession, scope?: SurveyScope) => scopedSession(session, scope).tools.all().map((t) => t.name);

describe("the trail's start", () => {
  test("is when the trailhead objective completed", () => {
    expect(trailStartOf(run())).toBe(T0 + 1000);
  });

  test("is the trailhead stream's trailStart row first, when the run has one", () => {
    expect(trailStartOf(run({ trailheadStream: [{ timeMs: T0 + 1500, data: { type: "trailStart" } }] }))).toBe(T0 + 1500);
  });

  test("falls back to the first request network capture tagged as the trail's", () => {
    const network = [
      { id: "a", phase: "REQUEST_START", timestampMs: T0 + 400, url: "https://x/a", trailPhase: "trailhead" },
      { id: "b", phase: "REQUEST_START", timestampMs: T0 + 1800, url: "https://x/b", trailPhase: "trail" },
    ];
    expect(trailStartOf(run({ trailhead: false, network }))).toBe(T0 + 1800);
  });

  test("is never reached by a run that failed inside its trailhead", () => {
    expect(trailStartOf(run({ trailheadDone: false, ended: "Ended.Failed" }))).toBe(Number.POSITIVE_INFINITY);
  });

  test("is never a failed trailhead's completion", () => {
    expect(trailStartOf(run({ trailheadResult: FAILURE, ended: "Ended.Failed" }))).toBe(Number.POSITIVE_INFINITY);
  });

  test("can't be told mid-trail, before a marker lands, and reading it never needs the outcome", () => {
    const s = run({ trailheadDone: false, ended: "Ended.Failed", network: [{ id: "a", phase: "REQUEST_START", timestampMs: T0 + 400, url: "https://x/a", trailPhase: "trailhead" }] });
    const live = Object.create(s, {
      final: { value: false },
      summary: {
        get() {
          throw new Error("summary.outcome only exists once the session has finished");
        },
      },
    }) as SurveySession;
    expect(trailStartOf(live)).toBeUndefined();
  });

  test("can't be told for a trail with no trailhead, failed or not", () => {
    expect(trailStartOf(run({ trailhead: false }))).toBeUndefined();
    expect(trailStartOf(run({ trailhead: false, ended: "Ended.Failed" }))).toBeUndefined();
  });
});

describe("a scoped session", () => {
  test("reads the trail by default, the trailhead or the whole run when asked", () => {
    const s = run();
    expect(toolsIn(s)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.Trail)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn"]);
    expect(toolsIn(s, SurveyScope.All)).toEqual(["signIn", "pay"]);
  });

  test("of a run failed in its trailhead holds nothing of the trail", () => {
    const s = run({ trailheadDone: false, ended: "Ended.Failed" });
    expect(toolsIn(s)).toEqual([]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn", "pay"]);
  });

  test("of a run that can't be split is the whole run, in every scope", () => {
    const s = run({ trailhead: false });
    for (const scope of Object.values(SurveyScope)) expect(toolsIn(s, scope)).toEqual(["signIn", "pay"]);
  });

  test("keeps a request in the part network capture tagged it with, whatever its time", () => {
    // Stamped by the device's clock, 5s ahead of the host's: by time alone it would land in the trail.
    const network = [{ id: "a", phase: "REQUEST_START", timestampMs: T0 + 5500, url: "https://x/login", urlPath: "/login", trailPhase: "trailhead", clock: "device" }];
    const s = run({ network });
    const request = s.network.all()[0]!;
    expect(phaseOf(s, request)).toBe(RunPhase.Trailhead);
    expect(scopedSession(s).network.all()).toEqual([]);
    expect(scopedSession(s, SurveyScope.Trailhead).network.all().map((r) => r.path)).toEqual(["/login"]);
  });

  test("keeps the trailhead objective in the trailhead, though its completion is the boundary", () => {
    const s = run();
    expect(scopedSession(s).objectives.all().map((o) => o.prompt)).toEqual([]);
    expect(scopedSession(s, SurveyScope.Trailhead).objectives.all().map((o) => o.prompt)).toEqual(["Sign in"]);
    expect(scopedSession(s).trailblazeLogs.ofType("ObjectiveCompleteLog").all()).toEqual([]);
  });

  test("places a request by when it was sent, however late its response came", () => {
    const network = [
      { id: "a", phase: "REQUEST_START", timestampMs: T0 + 900, url: "https://x/catalog", urlPath: "/catalog" },
      { id: "a", phase: "RESPONSE_END", timestampMs: T0 + 2500, statusCode: 200, durationMs: 1600 },
    ];
    const s = run({ network });
    expect(phaseOf(s, s.network.all()[0]!)).toBe(RunPhase.Trailhead);
  });

  test("puts a device-stamped record on the host's clock with the offset the logs measured", () => {
    // The device runs 5s ahead: a journey it stamped T0+5500 began at T0+500, during sign-in.
    const events = { journeys: [{ timeMs: T0 + 5500, data: { name: "LOGIN" }, clock: "device" }, { timeMs: T0 + 7000, data: { name: "CHECKOUT" }, clock: "device" }] };
    const s = run({ events, deviceAheadMs: 5000 });
    expect(scopedSession(s).stream("journeys").map((e) => (e.data as { name: string }).name)).toEqual(["CHECKOUT"]);
    expect(scopedSession(s, SurveyScope.Trailhead).stream("journeys").map((e) => (e.data as { name: string }).name)).toEqual(["LOGIN"]);
  });

  test("keeps a device-stamped record in every scope when nothing measured the device's clock", () => {
    const events = { journeys: [{ timeMs: T0 + 5500, data: { name: "LOGIN" }, clock: "device" }] };
    const s = run({ events });
    const [journey] = s.stream("journeys");
    expect(phaseOf(s, journey!)).toBeUndefined();
    expect(scopedSession(s).stream("journeys")).toHaveLength(1);
    expect(scopedSession(s, SurveyScope.Trailhead).stream("journeys")).toHaveLength(1);
  });

  test("reads teardown only when asked", () => {
    const trailheadStream = [
      { timeMs: T0 + 1000, data: { type: "trailStart" } },
      { timeMs: T0 + 2500, data: { type: "trailEnd" } },
    ];
    const network = [
      { id: "a", phase: "REQUEST_START", timestampMs: T0 + 2600, url: "https://x/signOut", urlPath: "/signOut", trailPhase: "teardown" },
      { id: "b", phase: "REQUEST_START", timestampMs: T0 + 2700, url: "https://x/untagged", urlPath: "/untagged" },
    ];
    const s = run({ trailheadStream, network, later: [tool(T0 + 2600, "signOut")] });
    expect(s.network.all().map((r) => phaseOf(s, r))).toEqual([RunPhase.Teardown, RunPhase.Teardown]);
    expect(toolsIn(s)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn"]);
    expect(toolsIn(s, SurveyScope.Teardown)).toEqual(["signOut"]);
    expect(toolsIn(s, SurveyScope.All)).toEqual(["signIn", "pay", "signOut"]);
  });

  test("keeps teardown out of the trailhead of a run that failed setting up", () => {
    // Teardown writes `trailEnd` with no `trailStart` when the trailhead failed.
    const trailheadStream = [{ timeMs: T0 + 2500, data: { type: "trailEnd" } }];
    const s = run({ trailheadDone: false, ended: "Ended.Failed", trailheadStream, later: [tool(T0 + 2600, "signOut")] });
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn", "pay"]);
    expect(toolsIn(s, SurveyScope.Teardown)).toEqual(["signOut"]);
    expect(toolsIn(s)).toEqual([]);
  });

  test("starts teardown at the teardown objective when no trailEnd row was written, as on-device", () => {
    const teardown = (ms: number, type: "ObjectiveStartLog" | "ObjectiveCompleteLog") => ({
      ...objective(ms, type, "Teardown"),
      promptStep: { step: "Teardown", isTeardown: true },
    });
    const s = run({ later: [teardown(T0 + 2500, "ObjectiveStartLog"), tool(T0 + 2600, "signOut"), teardown(T0 + 2700, "ObjectiveCompleteLog")] });
    expect(toolsIn(s)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.Teardown)).toEqual(["signOut"]);
    expect(scopedSession(s).objectives.all().map((o) => o.prompt)).toEqual([]);
  });

  // A name with a space reaches the stream filename-safe, as `journeys.buyer_display`.
  for (const [device, suffix] of [["tablet", "tablet"], ["buyer display", "buyer_display"]]) {
    test(`converts a device-stamped record with its own device's offset in a multi-device session (${device})`, () => {
      // The start device runs 5s ahead of the host, the companion 9s.
      const companionTap = { ...tool(T0 + 2100, "companionTap", 9000), deviceName: device };
      const row = (hostMs: number, aheadMs: number, name: string) => ({ timeMs: hostMs + aheadMs, data: { name }, clock: "device" });
      const events = {
        journeys: [row(T0 + 500, 5000, "LOGIN"), row(T0 + 1100, 5000, "CART")],
        [`journeys.${suffix}`]: [row(T0 + 500, 9000, "COMPANION_LOGIN"), row(T0 + 2200, 9000, "COMPANION_CART")],
      };
      const s = run({ events, deviceAheadMs: 5000, later: [companionTap] });
      const names = (scope: SurveyScope, stream: string) => scopedSession(s, scope).stream(stream).map((e) => (e.data as { name: string }).name);
      expect(names(SurveyScope.Trail, "journeys")).toEqual(["CART"]);
      expect(names(SurveyScope.Trailhead, "journeys")).toEqual(["LOGIN"]);
      expect(names(SurveyScope.Trail, `journeys.${suffix}`)).toEqual(["COMPANION_CART"]);
      expect(names(SurveyScope.Trailhead, `journeys.${suffix}`)).toEqual(["COMPANION_LOGIN"]);
    });
  }

  test("places tools and objectives on an unanchored device clock, as an on-device Android run logs them", () => {
    const s = run({ onDevice: true });
    expect(trailStartOf(s)).toBe(T0 + 1000);
    expect(toolsIn(s)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn"]);
    expect(scopedSession(s).objectives.all()).toEqual([]);
  });

  test("compares a companion's device clock with the start device's boundary through the host's", () => {
    // The trailhead stream stamps `trailStart` on the start device's clock (5s ahead); the tablet runs 9s ahead.
    const tabletTap = { ...tool(T0 + 2100, "tabletTap", 9000), deviceName: "tablet" };
    const row = (hostMs: number, aheadMs: number, name: string) => ({ timeMs: hostMs + aheadMs, data: { name }, clock: "device" });
    const s = run({
      deviceAheadMs: 5000,
      later: [tabletTap],
      trailheadStream: [{ timeMs: T0 + 1500 + 5000, clock: "device", data: { type: "trailStart" } }],
      // Raw, TABLET_LOGIN reads 4s past the start device's `trailStart`; on the host it is 300ms before.
      events: { "journeys.tablet": [row(T0 + 1200, 9000, "TABLET_LOGIN"), row(T0 + 2200, 9000, "TABLET_CART")] },
    });
    const names = (scope: SurveyScope) => scopedSession(s, scope).stream("journeys.tablet").map((e) => (e.data as { name: string }).name);
    expect(names(SurveyScope.Trail)).toEqual(["TABLET_CART"]);
    expect(names(SurveyScope.Trailhead)).toEqual(["TABLET_LOGIN"]);
  });

  test("puts every record of a device setup that never finished in it, on an unanchored device clock too", () => {
    const s = run({ setup: "unfinished", trailhead: false, ended: "Ended.Failed", onDevice: true });
    expect(toolsIn(s)).toEqual([]);
    expect(toolsIn(s, SurveyScope.DeviceSetup)).toEqual(["resetApp", "signIn", "pay"]);
  });

  test("puts a later trailhead step, as a second device's sign-in, in the trailhead", () => {
    const later = [
      objective(T0 + 2200, "ObjectiveStartLog", "Sign in on the second device", true),
      tool(T0 + 2300, "signInSecond"),
      objective(T0 + 2400, "ObjectiveCompleteLog", "Sign in on the second device", true),
      tool(T0 + 2500, "refund"),
    ];
    const s = run({ later });
    expect(toolsIn(s)).toEqual(["pay", "refund"]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn", "signInSecond"]);
  });

  test("puts the device setup in a part of its own, before the trailhead", () => {
    const s = run({ setup: true });
    expect(toolsIn(s)).toEqual(["pay"]);
    expect(toolsIn(s, SurveyScope.DeviceSetup)).toEqual(["resetApp"]);
    expect(toolsIn(s, SurveyScope.Trailhead)).toEqual(["signIn"]);
    expect(toolsIn(s, SurveyScope.All)).toEqual(["resetApp", "signIn", "pay"]);
    expect(scopedSession(s, SurveyScope.DeviceSetup).objectives.all().map((o) => o.prompt)).toEqual(["Device setup: reset the app"]);
  });

  test("reads every part a list names", () => {
    const s = run({ setup: true });
    expect(toolsIn(s, [RunPhase.DeviceSetup, RunPhase.Trailhead])).toEqual(["resetApp", "signIn"]);
    expect(toolsIn(s, [RunPhase.Trailhead, RunPhase.Trail])).toEqual(["signIn", "pay"]);
  });

  test("starts the trail when the device setup finished, for a trail with no trailhead", () => {
    const s = run({ setup: true, trailhead: false });
    expect(trailStartOf(s)).toBe(T0 + 60);
    expect(toolsIn(s)).toEqual(["signIn", "pay"]);
    expect(toolsIn(s, SurveyScope.DeviceSetup)).toEqual(["resetApp"]);
  });

  test("of a run that failed in its device setup holds nothing of the trail", () => {
    const s = run({ setup: "unfinished", trailhead: false, ended: "Ended.Failed" });
    expect(trailStartOf(s)).toBe(Number.POSITIVE_INFINITY);
    expect(toolsIn(s)).toEqual([]);
    expect(toolsIn(s, SurveyScope.DeviceSetup)).toEqual(["resetApp", "signIn", "pay"]);
  });

  test("narrows a derived stream's rows read with of()", () => {
    const steps = eventStream({ name: "steps", derive: (session) => session.tools.all().map((t) => ({ timeMs: t.timeMs!, data: { tool: t.name }, from: t })) });
    const s = run();
    expect(steps.of(scopedSession(s)).map((r) => (r.data as { tool: string }).tool)).toEqual(["pay"]);
    expect(steps.of(scopedSession(s, SurveyScope.Trailhead)).map((r) => (r.data as { tool: string }).tool)).toEqual(["signIn"]);
    expect(steps.of(s)).toHaveLength(2);
  });

  test("derives a stream's rows from the whole run, whichever view reads them first", () => {
    // A stateful derivation: each row numbers the tools seen so far.
    const steps = eventStream({ name: "steps", derive: (session) => session.tools.all().map((t, i) => ({ timeMs: t.timeMs!, data: { tool: t.name, n: i + 1 }, from: t })) });
    const s = run();
    const [pay] = steps.of(scopedSession(s));
    expect(pay!.data).toEqual({ tool: "pay", n: 2 });
    expect(steps.of(s)[1]).toBe(pay);
  });

  test("lets a stream declare the part of the run it derives from, as a survey does", () => {
    const toolsStream = (name: string, scope?: SurveyScope) =>
      eventStream({ name, ...(scope ? { scope } : {}), derive: (session) => session.tools.all().map((t) => ({ timeMs: t.timeMs!, data: { tool: t.name }, from: t })) });
    const names = (rows: ReadonlyArray<{ data: unknown }>) => rows.map((r) => (r.data as { tool: string }).tool);
    const s = run({ setup: true });
    const trailOnly = toolsStream("trailOnly", SurveyScope.Trail);
    expect(names(trailOnly.of(s))).toEqual(["pay"]);
    // Every reader gets the stream's rows, and its own scope narrows them further.
    expect(names(trailOnly.of(scopedSession(s, SurveyScope.All)))).toEqual(["pay"]);
    expect(names(trailOnly.of(scopedSession(s, SurveyScope.Trailhead)))).toEqual([]);
    expect(names(toolsStream("setUp", [RunPhase.DeviceSetup, RunPhase.Trailhead]).of(s))).toEqual(["resetApp", "signIn"]);
    expect(names(toolsStream("whole").of(s))).toEqual(["resetApp", "signIn", "pay"]);
  });

  test("hands a scoped stream only its part of the streams it depends on", () => {
    const all = eventStream({ name: "allTools", derive: (session) => session.tools.all().map((t) => ({ timeMs: t.timeMs!, data: { tool: t.name }, from: t })) });
    const trail = eventStream({ name: "trailCopies", scope: SurveyScope.Trail, dependsOn: [all], derive: (session) => all.of(session).map((r) => ({ timeMs: r.timeMs!, data: r.data, from: r })) });
    expect(trail.of(run()).map((r) => (r.data as { tool: string }).tool)).toEqual(["pay"]);
  });

  test("runs the docs' scoped-stream example over the trail's part only", () => {
    // As `surveys.md` writes it.
    const failedRequests = trailblaze.eventStream({
      name: "failed-requests",
      scope: SurveyScope.Trail,
      derive: (session) =>
        session.network.all().filter((r) => (r.status ?? 0) >= 400).map((r) => ({ timeMs: r.startMs, data: { path: r.path, status: r.status }, from: r })),
    });
    const failing = (id: string, ms: number, path: string) => [
      { id, phase: "REQUEST_START", method: "GET", timestampMs: ms, url: `https://x${path}`, urlPath: path },
      { id, phase: "RESPONSE_END", timestampMs: ms + 100, statusCode: 500, durationMs: 100 },
    ];
    const s = run({ network: [...failing("a", T0 + 400, "/catalog"), ...failing("b", T0 + 2200, "/payments")] });
    expect(failedRequests.of(s).map((r) => r.data)).toEqual([{ path: "/payments", status: 500 }]);
  });

  test("refuses a stream with an unknown scope", () => {
    expect(() => eventStream({ name: "bad", scope: "session" as SurveyScope, derive: () => [] })).toThrow(/stream "bad": scope must be "deviceSetup"/);
  });

  test("filters a named stream, and its boundary is read from the whole run", () => {
    const s = run({ trailheadStream: [{ timeMs: T0 + 50, data: { type: "trailheadStart" } }, { timeMs: T0 + 1500, data: { type: "trailStart" } }] });
    const trail = scopedSession(s);
    expect(trail.stream("trailhead").map((e) => e.timeMs)).toEqual([T0 + 1500]);
    expect(trailStartOf(trail)).toBe(T0 + 1500);
    expect(scopedSession(trail, SurveyScope.Trailhead).tools.all().map((t) => t.name)).toEqual(["signIn"]);
  });
});

describe("a survey", () => {
  const signedIn = (scope?: SurveyScope) =>
    withId(survey({ ...(scope ? { scope } : {}), finding: { summary: "Signed in", tools: { name: "signIn" } } }), `signedIn-${scope ?? "default"}`);


  test("finds only what the trail did unless it declares another scope", async () => {
    const report = await runSurveys({ surveys: [signedIn(), signedIn(SurveyScope.Trailhead), signedIn(SurveyScope.All)], sessions: [run()] });
    expect(report.sessions[0]!.findings.map((f) => f.survey).sort()).toEqual(["signedIn-all", "signedIn-trailhead"]);
  });

  test("says in the report which part of each run it read", async () => {
    const both = withId(survey({ scope: [RunPhase.DeviceSetup, RunPhase.Trailhead], finding: { summary: "Set up", tools: {} } }), "setUp");
    const report = await runSurveys({ surveys: [signedIn(), signedIn(SurveyScope.All), both], sessions: [run()] });
    expect(Object.fromEntries(report.surveys.map((s) => [s.id, s.scope]))).toEqual({ "signedIn-default": "trail", "signedIn-all": "all", setUp: "deviceSetup+trailhead" });
    expect(renderSummaryLines(report)[0]).toBe("SURVEYS   signedIn-default (trail), signedIn-all (all), setUp (deviceSetup+trailhead)");
    expect(renderMarkdown(report)).toContain("| signedIn-all | all |");
  });

  test("that warns reads the whole run unless it narrows its scope", async () => {
    const warns = (scope?: SurveyScope) =>
      withId(survey({ warning: true, requiresFinishedSession: true, ...(scope ? { scope } : {}), finding: { summary: "Signed in", tools: { name: "signIn" } } }), `warns-${scope ?? "default"}`);
    const report = await runSurveys({ surveys: [warns(), warns(SurveyScope.Trail)], sessions: [run()] });
    expect(report.sessions[0]!.findings.map((f) => f.survey)).toEqual(["warns-default"]);
    expect(Object.fromEntries(report.surveys.map((s) => [s.id, s.scope]))).toEqual({ "warns-default": "all", "warns-trail": "trail" });
  });

  test("with an unknown scope is refused when it is defined", () => {
    const define = (scope: unknown) => () => survey({ scope: scope as SurveyScope, finding: { summary: "x", tools: {} } });
    expect(define("session")).toThrow(/scope must be "deviceSetup", "trailhead", "trail", "teardown", "all", or a list of phases, not "session"/);
    expect(define([])).toThrow(/scope must list at least one of/);
    expect(define([RunPhase.Trail, "all"])).toThrow(/a scope list holds only "deviceSetup", "trailhead", "trail", "teardown", not "all"/);
  });
});

// The three surveys docs/surveys.md shows under "Trail or trailhead", run over two trails.
describe("the docs example", () => {
  const LOGIN = /\/v1\/auth\/login$/;

  const signsIn = withId(
    trailblaze.survey({
      finding: { summary: "Signed in", network: { method: "POST", path: LOGIN, status: "ok" } },
    }),
    "signsIn",
  );

  const setupSignIn = withId(
    trailblaze.survey({ scope: SurveyScope.Trailhead }, (session) => {
      const login = session.network.find({ method: "POST", path: LOGIN });
      return login && { summary: `The trailhead signed in, in ${login.durationMs} ms`, evidence: [login], data: { durationMs: login.durationMs } };
    }),
    "setupSignIn",
  );

  const everySignIn = withId(
    trailblaze.survey({ scope: SurveyScope.All }, (session) =>
      session.network.filter({ method: "POST", path: LOGIN }).map((login) => ({
        summary: `Signed in during the ${phaseOf(session, login) ?? "run"}`,
        evidence: [login],
        data: { phase: phaseOf(session, login) },
      })),
    ),
    "everySignIn",
  );

  const login = (id: string, ms: number) => [
    { id, phase: "REQUEST_START", method: "POST", timestampMs: ms, url: "https://x/v1/auth/login", urlPath: "/v1/auth/login" },
    { id, phase: "RESPONSE_END", timestampMs: ms + 300, statusCode: 200, durationMs: 300 },
  ];

  const findings = async (session: SurveySession) => {
    const report = await runSurveys({ surveys: [signsIn, setupSignIn, everySignIn], sessions: [session] });
    return report.sessions[0]!.findings.map((f) => [f.survey, f.summary]).sort();
  };

  test("a trail whose trailhead signs in", async () => {
    expect(await findings(run({ network: login("a", T0 + 400) }))).toEqual([
      ["everySignIn", "Signed in during the trailhead"],
      ["setupSignIn", "The trailhead signed in, in 300 ms"],
    ]);
  });

  test("a trail that also signs in again as one of its own steps", async () => {
    expect(await findings(run({ network: [...login("a", T0 + 400), ...login("b", T0 + 2200)] }))).toEqual([
      ["everySignIn", "Signed in during the trail"],
      ["everySignIn", "Signed in during the trailhead"],
      ["setupSignIn", "The trailhead signed in, in 300 ms"],
      ["signsIn", "Signed in"],
    ]);
  });
});

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { execFileSync } from "node:child_process";
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { runSurveyCli } from "./cli-main.js";
import { DERIVED_LOCK, DERIVED_MANIFEST } from "./derived-files.js";
import { LiveSessionReader, runLiveSurvey, runNamedLiveSurvey, type LiveEventSource } from "./live.js";
import { SurveySession } from "./session.js";
import { readStoredSurveys, sessionInputs, writeStoredSurveys } from "./stored.js";
import { sessionFromSnapshot } from "./snapshot/read.js";
import { snapshotOf } from "./snapshot/write.js";
import { envelopeOf, eventStream, withDerivedStreams, withEventStreamVersion, type EventStreamDefinition } from "./stream.js";
import { writeDerivedStreams } from "./stream-write.js";
import { runSurveys } from "./runner.js";
import { survey, withId } from "./survey.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus";
const T0 = 1_700_000_000_000;

/** A finished session whose `events/app.views.ndjson` logs which view the app showed. */
function writeSession(dir: string, views: Array<{ t: number; view: string }>): void {
  mkdirSync(join(dir, "events"), { recursive: true });
  const status = (n: string, t: number, sessionStatus: unknown) =>
    writeFileSync(join(dir, `${n}_TrailblazeSessionStatusChangeLog.json`), JSON.stringify({
      class: `${LOG}.TrailblazeSessionStatusChangeLog`, session: "s", timestamp: new Date(t).toISOString(), sessionStatus,
    }));
  status("000", T0, { class: `${STATUS}.Started`, trailConfig: { id: "t", title: "Views", platform: "ANDROID" }, trailblazeDeviceInfo: { platform: "ANDROID", classifiers: ["android"] } });
  status("001", T0 + 10_000, { class: `${STATUS}.Ended.Succeeded`, durationMs: 10_000 });
  writeFileSync(join(dir, "events", "app.views.ndjson"), views.map(({ t, view }) => JSON.stringify({ timeMs: T0 + t, data: { view } })).join("\n") + "\n");
}

/** One row per view change: a repeat of the view before it is not a new screen. */
const screens = eventStream<{ screen: string }>({
  name: "screens",
  description: "Each screen the app showed, in order",
  derive: (session) => {
    const rows: Array<{ timeMs: number; data: { screen: string }; from: (typeof views)[number] }> = [];
    const views = session.stream<{ view: string }>("app.views");
    for (const e of views) {
      if (rows.at(-1)?.data.screen !== e.data.view) rows.push({ timeMs: e.timeMs!, data: { screen: e.data.view }, from: e });
    }
    return rows;
  },
});

let root: string;
beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "streams-test-"));
});
afterAll(() => rmSync(root, { recursive: true, force: true }));

const fresh = (name: string, views = [{ t: 2, view: "Home" }, { t: 1, view: "Splash" }, { t: 3, view: "Home" }, { t: 4, view: "Cart" }]) => {
  const dir = join(root, name);
  writeSession(dir, views);
  return dir;
};

describe("a derived stream", () => {
  test("gives a survey its rows in time order, each citing the captured record it came from", () => {
    const session = SurveySession.load(fresh("of"));
    const rows = screens.of(session);
    expect(rows.map((r) => r.data.screen)).toEqual(["Splash", "Home", "Cart"]);
    expect(rows.map((r) => r.line)).toEqual([1, 2, 3]);
    expect(rows[0]).toMatchObject({ kind: "event", stream: "screens", file: "events/screens.ndjson", timeMs: T0 + 1 });
    expect(rows[0]!.from.map((r) => ({ file: r.file, line: r.line }))).toEqual([{ file: "events/app.views.ndjson", line: 2 }]);
    expect(screens.of(session)).toBe(rows);
  });

  test("rejects a name that is not a safe file name, and a row with no time", () => {
    expect(() => eventStream({ name: "../x", derive: () => [] })).toThrow(/name must be/);
    // The report reads `screens.json.ndjson` as the `screens` stream, so the name would not round-trip.
    expect(() => eventStream({ name: "screens.json", derive: () => [] })).toThrow(/not ending in '\.json'/);
    const bad = eventStream({ name: "bad", derive: () => [{ timeMs: Number.NaN, data: {} }] });
    expect(() => bad.of(SurveySession.load(fresh("bad")))).toThrow(/row 0 has no numeric timeMs/);
  });

  test("a survey reads it mid-trail from what is captured so far", async () => {
    const views = [{ timeMs: T0 + 1, data: { view: "Splash" } }, { timeMs: T0 + 2, data: { view: "Checkout" } }];
    const source: LiveEventSource = {
      list: async () => ["app.views"],
      query: async (s) => ({ captured: true, complete: true, events: s === "app.views" ? views : [], cursor: views.length }),
    };
    const reachedCheckout = withId(survey({
      find: (session) => {
        const row = screens.of(session).find((r) => r.data.screen === "Checkout");
        return row && { summary: "Reached checkout", evidence: [...row.from], data: { screen: row.data.screen } };
      },
    }), "reachedCheckout");
    const result = await runLiveSurvey(reachedCheckout, new LiveSessionReader(source));
    expect(result.passed).toBe(true);
    expect(result.findings[0]!.data).toEqual({ screen: "Checkout" });
  });

  /** Two views captured so far, the second the checkout. */
  const viewsSoFar: LiveEventSource = {
    list: async () => ["app.views"],
    query: async (s) => {
      const views = [{ timeMs: T0 + 1, data: { view: "Splash" } }, { timeMs: T0 + 2, data: { view: "Checkout" } }];
      return { captured: true, complete: true, events: s === "app.views" ? views : [], cursor: 2 };
    },
  };

  test("a survey reads a stream its file exports by name mid-trail, as after the run", async () => {
    const module = {
      screens,
      reachedCheckout: survey({
        find: (session) => session.stream<{ screen: string }>("screens").filter((e) => e.data.screen === "Checkout").map((e) => ({ summary: "Reached checkout", evidence: [e] })),
      }),
    };
    const outcome = await runNamedLiveSurvey(module, "reachedCheckout", viewsSoFar);
    expect([outcome.passed, outcome.message]).toEqual([true, "Survey reachedCheckout found Reached checkout."]);
  });

  test("a finding on a derived stream waits on what it derives from, not on the stream's own capture", async () => {
    const atCheckout = withId(survey({ finding: { events: { stream: "screens", contains: "Checkout" } } }), "atCheckout");
    const found = await runLiveSurvey(atCheckout, new LiveSessionReader(viewsSoFar), { streams: [screens] });
    expect(found.passed).toBe(true);
    // An absence needs every stream it rests on captured; a derived one counts by its sources.
    const neverCart = withId(survey({ finding: { events: { stream: "screens", contains: "Checkout" }, absent: { events: { stream: "screens", contains: "Cart" } } } }), "neverCart");
    const absent = await runLiveSurvey(neverCart, new LiveSessionReader(viewsSoFar), { streams: [screens] });
    expect([absent.passed, absent.message]).toEqual([true, "Survey neverCart found Matched the survey's finding."]);
  });

  test("a stream that throws mid-trail fails the survey, naming the stream", async () => {
    const broken = eventStream({ name: "broken", derive: () => { throw new Error("no rows today"); } });
    const any = withId(survey({ finding: { events: { stream: "broken" } } }), "any");
    const result = await runLiveSurvey(any, new LiveSessionReader(viewsSoFar), { streams: [broken] });
    expect([result.passed, result.message]).toEqual([false, "Survey any reported an error: stream broken: no rows today."]);
  });

  test("a stream its file exports but a survey never reads cannot fail that survey mid-trail", async () => {
    // Stands in for a stream that reads what only a finished session has, such as its tool calls.
    const afterTheRun = eventStream({ name: "after-the-run", derive: () => { throw new Error("needs a finished session"); } });
    const module = {
      screens,
      afterTheRun,
      sawCheckout: survey({ finding: { events: { stream: "app.views", contains: "Checkout" } } }),
      // Read as an array, as `session.stream` is after the run.
      reachedCheckout: survey({
        find: (session) => {
          const rows = session.stream<{ screen: string }>("screens");
          return rows.map((e) => e.data.screen).includes("Checkout") && rows[0] ? { summary: "Reached checkout", evidence: [rows[0]] } : undefined;
        },
      }),
      anyEvent: survey({ finding: { events: { contains: "Checkout" } } }),
    };
    const declarative = await runNamedLiveSurvey(module, "sawCheckout", viewsSoFar);
    expect([declarative.passed, declarative.message]).toEqual([true, "Survey sawCheckout found Matched the survey's finding."]);
    const handler = await runNamedLiveSurvey(module, "reachedCheckout", viewsSoFar);
    expect([handler.passed, handler.message]).toEqual([true, "Survey reachedCheckout found Reached checkout."]);
    // A query on every stream could match that one's rows, so it is derived, and its failure counts.
    const unfiltered = await runNamedLiveSurvey(module, "anyEvent", viewsSoFar);
    expect([unfiltered.passed, unfiltered.message]).toEqual([false, "Survey anyEvent reported an error: stream after-the-run: needs a finished session."]);
  });
});

describe("mid-trail network clock", () => {
  test("a live request the host moved onto its clock is not marked device clock", async () => {
    const rows = [{ timeMs: T0 + 5_000, data: { id: "r1", phase: "REQUEST_START", url: "https://example.com/a", timestampMs: T0, clock: "device" } }];
    const source: LiveEventSource = {
      list: async () => ["network"],
      query: async (s) => ({ captured: true, complete: true, events: s === "network" ? rows : [], cursor: rows.length }),
    };
    const requests = eventStream({ name: "requests", derive: (session) => session.network.all().map((r) => ({ timeMs: r.timeMs!, data: {}, from: r })) });
    let seen: unknown[] = [];
    const probe = withId(survey({
      find: (session) => {
        seen = requests.of(session).map((r) => [r.timeMs, r.clock]);
        return undefined;
      },
    }), "probe");
    await runLiveSurvey(probe, new LiveSessionReader(source));
    expect(seen).toEqual([[T0 + 5_000, undefined]]);
  });

  test("a live record the host could not move stays device clock, as offline", async () => {
    const rows = [
      { timeMs: T0, clock: "device" as const, data: { id: "r1", phase: "REQUEST_START", url: "https://example.com/a", timestampMs: T0, clock: "device" } },
    ];
    const views = [{ timeMs: T0 + 1, clock: "device" as const, data: { view: "Splash" } }];
    const source: LiveEventSource = {
      list: async () => ["network", "app.views"],
      query: async (s) => {
        const events = s === "network" ? rows : s === "app.views" ? views : [];
        return { captured: true, complete: true, events, cursor: events.length };
      },
    };
    const requests = eventStream({ name: "requests", derive: (session) => session.network.all().map((r) => ({ timeMs: r.timeMs!, data: {}, from: r })) });
    let seen: unknown[] = [];
    const probe = withId(survey({
      find: (session) => {
        seen = [...requests.of(session).map((r) => r.clock), ...screens.of(session).map((r) => r.clock)];
        return undefined;
      },
    }), "probe");
    await runLiveSurvey(probe, new LiveSessionReader(source));
    expect(seen).toEqual(["device", "device"]);
  });
});

describe("writing derived streams into a session", () => {
  const versioned = withEventStreamVersion(screens, "v1");

  test("writes the rows as a {timeMs, data} stream with lineage, and lists it in the manifest", () => {
    const dir = fresh("write");
    const r = writeDerivedStreams(dir, [versioned]);
    expect(r.written).toEqual(["screens"]);
    const lines = readFileSync(join(dir, "events", "screens.ndjson"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    expect(lines[0]).toEqual({ timeMs: T0 + 1, data: { screen: "Splash" }, from: [{ file: "events/app.views.ndjson", line: 2 }] });
    expect(lines.map((l) => l.data.screen)).toEqual(["Splash", "Home", "Cart"]);
    expect(JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.screens).toMatchObject({ version: "v1", rows: 3 });
  });

  test("reads back as an event stream marked derived, leaving captured data and stored results' inputs alone", () => {
    const dir = fresh("visible");
    const before = SurveySession.load(dir);
    const inputs = sessionInputs(dir);
    writeDerivedStreams(dir, [versioned]);
    const after = SurveySession.load(dir);
    expect(after.summary.eventStreams).toEqual(["app.views"]);
    expect(after.stream<{ screen: string }>("screens").map((e) => [e.data.screen, e.derived])).toEqual([["Splash", true], ["Home", true], ["Cart", true]]);
    expect(after.stream("screens")[0]!.from).toEqual([{ kind: "custom", summary: "events/app.views.ndjson:2", file: "events/app.views.ndjson", line: 2 }]);
    // Deriving again ignores what the file holds: the same rows as before it was written.
    expect(screens.of(after).map((r) => r.data.screen)).toEqual(screens.of(before).map((r) => r.data.screen));
    expect(sessionInputs(dir)).toBe(inputs);
  });

  test("leaves a current stream alone, and rewrites it when its code or the session changes", () => {
    const dir = fresh("current");
    writeDerivedStreams(dir, [versioned]);
    expect(writeDerivedStreams(dir, [versioned]).current).toEqual(["screens"]);
    expect(writeDerivedStreams(dir, [withEventStreamVersion(screens, "v2")]).written).toEqual(["screens"]);
    writeFileSync(join(dir, "events", "app.views.ndjson"), JSON.stringify({ timeMs: T0 + 5, data: { view: "Receipt" } }) + "\n");
    expect(writeDerivedStreams(dir, [withEventStreamVersion(screens, "v2")]).written).toEqual(["screens"]);
    expect(readFileSync(join(dir, "events", "screens.ndjson"), "utf8")).toContain("Receipt");
  });

  test("rewrites a file that no longer holds what it wrote, though its code and the session are unchanged", () => {
    const dir = fresh("replaced");
    writeDerivedStreams(dir, [versioned]);
    // Another run, or a write cut short, left other rows under the same manifest entry.
    writeFileSync(join(dir, "events", "screens.ndjson"), JSON.stringify({ timeMs: T0, data: { screen: "Other" } }) + "\n");
    expect(writeDerivedStreams(dir, [versioned]).written).toEqual(["screens"]);
    expect(readFileSync(join(dir, "events", "screens.ndjson"), "utf8")).not.toContain("Other");
    expect(readdirSync(join(dir, "events")).filter((n) => n.endsWith(".tmp"))).toEqual([]);
  });

  test("rewrites a file replaced by a link or a pipe instead of reading through it", () => {
    for (const kind of ["link", "pipe"] as const) {
      const dir = fresh(`special-${kind}`);
      writeDerivedStreams(dir, [versioned]);
      const file = join(dir, "events", "screens.ndjson");
      // Kept outside the session, so the session itself is unchanged and its stream would count as current.
      const copy = join(root, `${kind}-copy.ndjson`);
      renameSync(file, copy);
      // A link to the very bytes it wrote still does not count as the file it wrote.
      if (kind === "link") symlinkSync(copy, file);
      else execFileSync("mkfifo", [file]);
      expect(writeDerivedStreams(dir, [versioned]).written).toEqual(["screens"]);
      expect(lstatSync(file).isFile()).toBe(true);
    }
  });

  test("writes nothing into a session that has not ended, whose capture may still be running", () => {
    const dir = fresh("unfinished");
    rmSync(join(dir, "001_TrailblazeSessionStatusChangeLog.json"));
    const r = writeDerivedStreams(dir, [versioned]);
    expect(r).toMatchObject({ unfinished: true, written: [], errors: [] });
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
  });

  test("a manifest it cannot read is never saved over, so the files it owns keep their owner", () => {
    for (const [name, text] of [["huge", JSON.stringify({ format: 1, streams: { screens: { rows: 3 } }, pad: "x".repeat(1024 * 1024) })], ["garbled", "{not json"]] as const) {
      const dir = fresh(name);
      writeDerivedStreams(dir, [versioned]);
      writeFileSync(join(dir, DERIVED_MANIFEST), text);
      const r = writeDerivedStreams(dir, [versioned, withEventStreamVersion(eventStream({ name: "other", derive: () => [{ timeMs: T0, data: {} }] }), "v1")]);
      expect(r.written).toEqual([]);
      expect(r.errors.map((e) => e.message)).toEqual(["events/derived.json cannot be read, so no derived stream is written; fix or delete it"]);
      expect(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).toBe(text);
      // Nor is the session read, or its results stored, with the streams it owns taken for captured.
      expect(() => SurveySession.load(dir)).toThrow("which event streams were derived is unknown");
      expect(sessionInputs(dir)).toBeUndefined();
    }
  });

  test("writes nothing through an events directory that is a link", () => {
    const dir = fresh("linked");
    const elsewhere = join(root, "linked-target");
    renameSync(join(dir, "events"), elsewhere);
    symlinkSync(elsewhere, join(dir, "events"));
    const r = writeDerivedStreams(dir, [versioned]);
    expect(r.written).toEqual([]);
    expect(r.errors.map((e) => e.stream)).toEqual(["events"]);
    expect(readdirSync(elsewhere)).toEqual(["app.views.ndjson"]);
  });

  test("marks a row derived from device-clock lines as device clock, so the report moves it beside them", () => {
    const dir = fresh("clock");
    writeFileSync(join(dir, "events", "app.views.ndjson"), [
      JSON.stringify({ timeMs: T0 + 1, clock: "device", data: { view: "Splash" } }),
      JSON.stringify({ timeMs: T0 + 2, data: { view: "Home" } }),
    ].join("\n") + "\n");
    writeDerivedStreams(dir, [versioned]);
    const lines = readFileSync(join(dir, "events", "screens.ndjson"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    expect(lines.map((l) => [l.data.screen, l.clock])).toEqual([["Splash", "device"], ["Home", undefined]]);
  });

  test("a row derived from device-clock analytics is marked device clock too", () => {
    const dir = fresh("analytics-clock");
    writeFileSync(join(dir, "events", "app.analytics.ndjson"), JSON.stringify({ timeMs: T0 + 1, clock: "device", data: { name: "Checkout" } }) + "\n");
    const fromAnalytics = withEventStreamVersion(eventStream({ name: "checkouts", derive: (s) => s.analytics.all().map((e) => ({ timeMs: e.timeMs!, data: {}, from: e })) }), "v1");
    writeDerivedStreams(dir, [fromAnalytics]);
    expect(JSON.parse(readFileSync(join(dir, "events", "checkouts.ndjson"), "utf8")).clock).toBe("device");
  });

  test("a row's own clock wins over the records it cites", () => {
    const dir = fresh("explicit-clock");
    writeFileSync(join(dir, "events", "app.views.ndjson"), [
      JSON.stringify({ timeMs: T0 + 1, clock: "device", data: { view: "Splash" } }),
      JSON.stringify({ timeMs: T0 + 2, data: { view: "Home" } }),
    ].join("\n") + "\n");
    const cited = withEventStreamVersion(eventStream({ name: "cited", derive: (s) => {
      const [device, host] = s.stream("app.views");
      return [{ timeMs: device!.timeMs!, clock: "device" as const, data: {}, from: [device!, host!] }, { timeMs: device!.timeMs!, clock: "host" as const, data: {}, from: device! }];
    } }), "v1");
    expect(cited.of(SurveySession.load(dir)).map((r) => r.clock)).toEqual(["device", undefined]);
    writeDerivedStreams(dir, [cited]);
    const lines = readFileSync(join(dir, "events", "cited.ndjson"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    expect(lines.map((l) => l.clock)).toEqual(["device", undefined]);
  });

  test("writes whole milliseconds, which every report reads, when a derivation computes a fraction", () => {
    const dir = fresh("fraction");
    const midpoint = withEventStreamVersion(eventStream({ name: "midpoint", derive: () => [{ timeMs: T0 + 0.5, data: {} }] }), "v1");
    writeDerivedStreams(dir, [midpoint]);
    expect(JSON.parse(readFileSync(join(dir, "events", "midpoint.ndjson"), "utf8")).timeMs).toBe(T0 + 1);
  });

  test("writes the lane's title and each row's view for the report", () => {
    const dir = fresh("view");
    const titled = withEventStreamVersion(eventStream<{ screen: string }>({ ...screens.spec, title: "Screens", row: (d) => ({ label: d.screen }) }), "v1");
    writeDerivedStreams(dir, [titled]);
    const first = JSON.parse(readFileSync(join(dir, "events", "screens.ndjson"), "utf8").split("\n")[0]!);
    expect(first).toMatchObject({ data: { screen: "Splash" }, row: { label: "Splash" } });
    expect(JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.screens.title).toBe("Screens");
  });

  test("writes how the stream's lane folds, so a burst of rows at one instant can stay apart", () => {
    const dir = fresh("fold");
    const kept = withEventStreamVersion(eventStream({ ...screens.spec, lane: { fold: "none" } }), "v1");
    writeDerivedStreams(dir, [kept]);
    expect(JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.screens.lane).toEqual({ fold: "none" });
    expect(() => eventStream({ name: "x", lane: { fold: "sideways" as "none" }, derive: () => [] })).toThrow('lane.fold must be "any", "label" or "none"');
  });

  test("records what each stream was derived from, so the report can say it beside the lane", () => {
    const dir = fresh("sources");
    const visits = eventStream({
      name: "cart-visits",
      dependsOn: [versioned],
      derive: (session) => session.stream<{ screen: string }>("screens").filter((e) => e.data.screen === "Cart").map((e) => ({ timeMs: e.timeMs!, data: {}, from: e })),
    });
    // Cites nothing, so only what it declares names its source.
    const count = eventStream({ name: "screen-count", dependsOn: [versioned], derive: (session) => [{ timeMs: T0, data: { n: versioned.of(session).length } }] });
    writeDerivedStreams(dir, [versioned, withEventStreamVersion(visits, "v1"), withEventStreamVersion(count, "v1")]);
    const streams = JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams;
    expect([streams.screens.derivedFrom, streams["cart-visits"].derivedFrom, streams["screen-count"].derivedFrom]).toEqual([["app.views"], ["screens"], ["screens"]]);
  });

  test("a stream written before its sources were recorded is written again", () => {
    const dir = fresh("sources-old");
    writeDerivedStreams(dir, [versioned]);
    const manifest = JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8"));
    delete manifest.streams.screens.derivedFrom;
    writeFileSync(join(dir, DERIVED_MANIFEST), JSON.stringify(manifest));
    expect(writeDerivedStreams(dir, [versioned]).written).toEqual(["screens"]);
    expect(JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.screens.derivedFrom).toEqual(["app.views"]);
  });

  test("a stream with no rows leaves no file, so the report adds no empty lane", () => {
    const dir = fresh("empty", []);
    expect(writeDerivedStreams(dir, [versioned]).empty).toEqual(["screens"]);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
  });

  test("never overwrites a stream the session captured", () => {
    const captured = withEventStreamVersion(eventStream({ name: "app.views", derive: () => [{ timeMs: T0, data: {} }] }), "v1");
    const dir = fresh("captured");
    const original = readFileSync(join(dir, "events", "app.views.ndjson"), "utf8");
    expect(writeDerivedStreams(dir, [captured]).errors).toEqual([
      { stream: "app.views", message: "events/app.views.ndjson was captured, not derived; rename the stream" },
    ]);
    expect(readFileSync(join(dir, "events", "app.views.ndjson"), "utf8")).toBe(original);
  });

  test("never claims a name a legacy captured file already answers to", () => {
    const dir = fresh("legacy");
    writeFileSync(join(dir, "events", "screens.json.ndjson"), JSON.stringify({ timeMs: T0, data: { captured: true } }) + "\n");
    expect(writeDerivedStreams(dir, [withEventStreamVersion(screens, "v1")]).errors.map((e) => e.message)).toEqual([
      "events/screens.json.ndjson was captured, not derived; rename the stream",
    ]);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
  });

  test("refuses a name Trailblaze captures itself, some only after the run's outcome is logged", () => {
    for (const name of ["crash", "network", "network.pixel", "memory.tablet", "trailhead", "Crash"]) {
      expect(() => eventStream({ name, derive: () => [] })).toThrow(/is a stream Trailblaze captures/);
    }
    expect(() => eventStream({ name: "crashes", derive: () => [] })).not.toThrow();
  });

  test("refuses a name the report shows on a captured stream's lane", () => {
    for (const name of ["checkout.network", "checkout.network.pixel", "my-visible-strings", "app.capture-notices"]) {
      expect(() => eventStream({ name, derive: () => [] })).toThrow(/on the lane of a stream Trailblaze captures/);
    }
    for (const name of ["networking", "checkout.networks", "net.work", "experiment-eval", "app.experiment-eval"]) expect(() => eventStream({ name, derive: () => [] })).not.toThrow();
  });

  test("a row derived from a device-clock network request is marked device clock", () => {
    const dir = fresh("network-clock");
    writeFileSync(join(dir, "network.ndjson"), JSON.stringify({ id: "r1", phase: "REQUEST_START", url: "https://example.com/a", timestampMs: T0, clock: "device" }) + "\n");
    const requests = withEventStreamVersion(eventStream({ name: "requests", derive: (s) => s.network.all().map((r) => ({ timeMs: r.timeMs!, data: {}, from: r })) }), "v1");
    expect(requests.of(SurveySession.load(dir)).map((r) => r.clock)).toEqual(["device"]);
  });

  test("a row derived from a span the device recorded is marked device clock, which converts on the start device's", () => {
    const dir = fresh("trace-clock");
    writeFileSync(join(dir, "trace.json"), JSON.stringify([
      { name: "tap", cat: "driver", ph: "X", ts: T0 * 1000, dur: 10, clock: "device" },
      { name: "tool", cat: "host", ph: "X", ts: T0 * 1000, dur: 10 },
    ]));
    const spans = withEventStreamVersion(eventStream({ name: "spans.tablet", derive: (s) => s.trace.all().map((t) => ({ timeMs: t.timeMs!, data: {}, from: t })) }), "v1");
    const rows = spans.of(SurveySession.load(dir)).map((r) => envelopeOf(r));
    // A span names no device, so the row converts as the trace does: on the device the session
    // started on, not the device a derived name like `spans.tablet` would name.
    expect(rows.map((r) => [r.clock ?? null, r.clockStream ?? null])).toEqual([["device", "trace"], [null, null]]);
  });

  test("a row derived from a stream named for the clock it is on converts on that clock too", () => {
    const dir = fresh("trace-clock-chain");
    writeFileSync(join(dir, "trace.json"), JSON.stringify([{ name: "tap", cat: "driver", ph: "X", ts: T0 * 1000, dur: 10, clock: "device" }]));
    const trace = withEventStreamVersion(eventStream({ name: "trace", derive: (s) => s.trace.all().map((t) => ({ timeMs: t.timeMs!, data: {}, from: t })) }), "v1");
    const spans = withEventStreamVersion(
      eventStream({ name: "spans.tablet", dependsOn: [trace], derive: (s) => s.stream("trace").map((e) => ({ timeMs: e.timeMs!, data: {}, from: e })) }),
      "v1",
    );
    const rows = spans.of(SurveySession.load(dir)).map((r) => envelopeOf(r));
    expect(rows.map((r) => [r.clock ?? null, r.clockStream ?? null])).toEqual([["device", "trace"]]);
  });

  test("treats a name an empty object already answers to as not yet derived", () => {
    const dir = fresh("inherited");
    writeFileSync(join(dir, "events", "constructor.ndjson"), JSON.stringify({ timeMs: T0, data: { captured: true } }) + "\n");
    const named = withEventStreamVersion(eventStream({ name: "constructor", derive: () => [] }), "v1");
    expect(writeDerivedStreams(dir, [named]).errors.map((e) => e.message)).toEqual(["events/constructor.ndjson was captured, not derived; rename the stream"]);
    expect(existsSync(join(dir, "events", "constructor.ndjson"))).toBe(true);
  });

  test("removes a stream no file declares any more, leaving captured streams alone", () => {
    const dir = fresh("removed");
    writeDerivedStreams(dir, [versioned]);
    const r = writeDerivedStreams(dir, []);
    expect(r.removed).toEqual(["screens"]);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
    expect(existsSync(join(dir, DERIVED_MANIFEST))).toBe(false);
    expect(existsSync(join(dir, "events", "app.views.ndjson"))).toBe(true);
  });

  test("a manifest naming a path outside events/ deletes nothing", () => {
    const dir = fresh("traversal");
    writeFileSync(join(dir, "network.ndjson"), "{}\n");
    writeFileSync(join(dir, DERIVED_MANIFEST), JSON.stringify({ format: 1, streams: { "../network": { rows: 1 }, "../events/app.views": { rows: 1 } } }));
    expect(writeDerivedStreams(dir, []).removed).toEqual([]);
    expect(existsSync(join(dir, "network.ndjson"))).toBe(true);
    expect(existsSync(join(dir, "events", "app.views.ndjson"))).toBe(true);
    expect(SurveySession.load(dir).stream("app.views")).toHaveLength(4);
  });

  test("a stream that fails to rederive leaves no out-of-date rows, and is retried next time", () => {
    const dir = fresh("failed");
    writeDerivedStreams(dir, [versioned]);
    const broken = withEventStreamVersion(eventStream({ name: "screens", derive: () => { throw new Error("boom"); } }), "v2");
    expect(writeDerivedStreams(dir, [broken]).errors).toEqual([{ stream: "screens", message: "boom" }]);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
    expect(writeDerivedStreams(dir, [withEventStreamVersion(screens, "v2")]).written).toEqual(["screens"]);
    expect(JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.screens.error).toBeUndefined();
  });

  test("records on the session why a stream failed, so a later reader can tell it from one with no rows", () => {
    const dir = fresh("failed-first");
    const broken = withEventStreamVersion(eventStream({ name: "flags", derive: () => { throw new Error("x".repeat(2000)); } }), "v1");
    expect(writeDerivedStreams(dir, [broken]).errors).toHaveLength(1);
    const entry = JSON.parse(readFileSync(join(dir, DERIVED_MANIFEST), "utf8")).streams.flags;
    expect(entry.rows).toBe(0);
    expect(entry.error).toHaveLength(500);
    expect(entry.error.endsWith("…")).toBe(true);
    // Not current, so the next write tries again.
    expect(writeDerivedStreams(dir, [broken]).errors).toHaveLength(1);
  });

  test("writes only into the sessions the filter accepts", () => {
    const dir = fresh("filtered");
    const r = writeDerivedStreams(dir, [versioned], { where: (s) => s.platform === "IOS" });
    expect(r.excluded).toBe(true);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
  });

  test("a stream derived while the session changed is rewritten next time", () => {
    const dir = fresh("moving");
    const appending = withEventStreamVersion(eventStream<{ screen: string }>({
      name: "screens",
      derive: (session) => {
        const rows = screens.spec.derive(session);
        writeFileSync(join(dir, "events", "app.views.ndjson"), JSON.stringify({ timeMs: T0 + 9, data: { view: "Late" } }) + "\n", { flag: "a" });
        return rows;
      },
    }), "v1");
    expect(writeDerivedStreams(dir, [appending]).written).toEqual(["screens"]);
    expect(writeDerivedStreams(dir, [versioned]).written).toEqual(["screens"]);
    expect(readFileSync(join(dir, "events", "screens.ndjson"), "utf8")).toContain("Late");
  });
});

describe("writing derived streams, with others", () => {
  const versioned = withEventStreamVersion(screens, "v1");

  test("a row names the stream whose device stamped it, so a multi-device report converts it on that device", () => {
    const dir = fresh("clock-owner");
    writeFileSync(join(dir, "events", "app.views.tablet.ndjson"), JSON.stringify({ timeMs: T0 + 1, clock: "device", data: { view: "Cart" } }) + "\n");
    const fromTablet = withEventStreamVersion(eventStream({ name: "tablet-screens", derive: (s) => s.stream("app.views.tablet").map((e) => ({ timeMs: e.timeMs!, data: {}, from: e })) }), "v1");
    writeDerivedStreams(dir, [fromTablet]);
    const line = JSON.parse(readFileSync(join(dir, "events", "tablet-screens.ndjson"), "utf8"));
    expect([line.clock, line.clockStream]).toEqual(["device", "app.views.tablet"]);
  });

  test("waits for another run writing the same session, so neither saves over the other", async () => {
    const dir = fresh("locked");
    const ready = join(root, "locked.ready");
    // Another process holds the lock a moment, as a second `report --surveys` would.
    const holder = Bun.spawn(["bun", "-e", `
      const { withLock } = await import(${JSON.stringify(join(import.meta.dir, "stored.ts"))});
      withLock(${JSON.stringify(join(dir, DERIVED_LOCK))}, () => {
        require("node:fs").writeFileSync(${JSON.stringify(ready)}, "");
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 400);
      });
    `]);
    while (!existsSync(ready)) await Bun.sleep(5);
    const started = Date.now();
    const r = writeDerivedStreams(dir, [versioned]);
    expect(Date.now() - started).toBeGreaterThanOrEqual(200);
    expect(r.written).toEqual(["screens"]);
    expect(await holder.exited).toBe(0);
    // Released, and never part of the session as captured.
    expect(existsSync(join(dir, DERIVED_LOCK))).toBe(false);
  });

  test("a run whose lock another run took over changes no file after that", () => {
    const dir = fresh("taken-over");
    writeDerivedStreams(dir, [versioned]);
    const before = readFileSync(join(dir, "events", "screens.ndjson"), "utf8");
    // Deriving runs while the lock is held; replacing it stands in for a run that judged it stale.
    const slow = withEventStreamVersion(eventStream({ name: "screens", derive: (s) => {
      writeFileSync(join(dir, DERIVED_LOCK), "another run");
      return [{ timeMs: T0, data: { screen: "Late" } }];
    } }), "v2");
    const r = writeDerivedStreams(dir, [slow]);
    expect(r.written).toEqual([]);
    expect(r.errors.map((e) => e.stream)).toEqual([DERIVED_LOCK]);
    expect(readFileSync(join(dir, "events", "screens.ndjson"), "utf8")).toBe(before);
    expect(readFileSync(join(dir, DERIVED_LOCK), "utf8")).toBe("another run");
  });

  test("the lock is not a session input, so holding it changes no stored result", () => {
    const dir = fresh("lock-input");
    const before = sessionInputs(dir);
    writeFileSync(join(dir, DERIVED_LOCK), "token");
    expect(sessionInputs(dir)).toBe(before);
  });

  test("a stream's temporary copy is not a session input, so writing one changes no stored result", () => {
    const dir = fresh("stream-tmp-input");
    mkdirSync(join(dir, "events"), { recursive: true });
    const before = sessionInputs(dir);
    writeFileSync(join(dir, "events", "screens.ndjson.123e4567-e89b-12d3-a456-426614174000.tmp"), "{}\n");
    expect(sessionInputs(dir)).toBe(before);
  });

  test("a survey file that cannot be loaded leaves sessions the filters exclude alone", async () => {
    const dir = fresh("broken-filtered");
    writeDerivedStreams(dir, [versioned]);
    const surveys = join(root, "broken-filtered-surveys");
    mkdirSync(surveys, { recursive: true });
    writeFileSync(join(surveys, "broken.survey.ts"), 'throw new Error("broken on import");\n');
    // The session is Android.
    expect(await runSurveyCli(["--streams", "--quiet", "--surveys", surveys, "--platform", "ios", "--sessions", dir])).toBe(1);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(true);
    // With --target, which sessions it names needs what failed to load: nothing is changed. (A new
    // file, since a module that failed to import is not imported again.)
    writeFileSync(join(surveys, "broken-too.survey.ts"), 'throw new Error("broken on import");\n');
    rmSync(join(surveys, "broken.survey.ts"));
    expect(await runSurveyCli(["--streams", "--quiet", "--surveys", surveys, "--target", "app", "--sessions", dir])).toBe(1);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(true);
  });

  test("a survey file that cannot be loaded removes every stream written before, rather than leave it as current", async () => {
    const dir = fresh("broken-discovery");
    writeDerivedStreams(dir, [versioned]);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(true);
    const surveys = join(root, "broken-surveys");
    mkdirSync(surveys, { recursive: true });
    writeFileSync(join(surveys, "broken.survey.ts"), 'throw new Error("broken on import");\n');
    expect(await runSurveyCli(["--streams", "--quiet", "--surveys", surveys, "--sessions", dir])).toBe(1);
    expect(existsSync(join(dir, "events", "screens.ndjson"))).toBe(false);
    expect(existsSync(join(dir, DERIVED_MANIFEST))).toBe(false);
    expect(readdirSync(join(dir, "events"))).toEqual(["app.views.ndjson"]);
  });
});

describe("chaining streams into a survey", () => {
  /** Built on `screens`: each time the app reached the cart. */
  const cartVisits = (label = "cart") =>
    eventStream<{ at: string }>({
      name: "cart-visits",
      dependsOn: [screens],
      derive: (session) =>
        session
          .stream<{ screen: string }>("screens")
          .filter((e) => e.data.screen === "Cart")
          .map((e) => ({ timeMs: e.timeMs!, data: { at: label }, from: e })),
    });

  /** Finds each cart visit, and labels the batch by whether the run reached the cart. */
  const reachedCart = withId(
    survey({
      find: (session) => session.stream<{ at: string }>("cart-visits").map((e) => ({ summary: `Reached ${e.data.at}`, evidence: [e], data: { at: e.data.at } })),
      label: (batch) => batch.groupBy((f) => f.data.at).map(({ key, findings }) => ({ type: "reached", name: key, findings })),
    }),
    "reachedCart",
  );

  test("a survey finds and labels rows two streams deep, its evidence leading back to the captured record", async () => {
    const dir = fresh("chain");
    const report = await runSurveys({ surveys: [reachedCart], sessions: [dir], streams: [screens, cartVisits()] });
    const findings = report.sessions[0]!.findings;
    expect(findings.map((f) => f.summary)).toEqual(["Reached cart"]);
    expect(report.labels.map((l) => [l.type, l.name, l.runs])).toEqual([["reached", "cart", ["s"]]]);
    // A cart-visit row cites the screens row it came from, which cites the captured view.
    const visit = SurveySession.load(dir);
    const [row] = withDerivedStreams(visit, [screens, cartVisits()]).session.stream("cart-visits") as Array<{ from: Array<{ file: string; line?: number; from?: Array<{ file: string; line?: number }> }> }>;
    expect(row!.from.map((r) => [r.file, r.line])).toEqual([["events/screens.ndjson", 3]]);
    expect(row!.from[0]!.from!.map((r) => [r.file, r.line])).toEqual([["events/app.views.ndjson", 4]]);
  });

  test("re-derives with the code loaded, and reads the written rows only without it", async () => {
    const dir = fresh("rederive");
    writeDerivedStreams(dir, [withEventStreamVersion(screens, "v1"), withEventStreamVersion(cartVisits("old cart"), "v1")]);
    const withCode = await runSurveys({ surveys: [reachedCart], sessions: [dir], streams: [screens, cartVisits("new cart")] });
    expect(withCode.sessions[0]!.findings.map((f) => f.summary)).toEqual(["Reached new cart"]);
    const withoutCode = await runSurveys({ surveys: [reachedCart], sessions: [dir] });
    expect(withoutCode.sessions[0]!.findings.map((f) => f.summary)).toEqual(["Reached old cart"]);
  });

  test("a stream sees only what was captured and the streams it depends on, never a file's derived rows", () => {
    const dir = fresh("pure");
    writeDerivedStreams(dir, [withEventStreamVersion(screens, "v1")]);
    const undeclared = eventStream({ name: "undeclared", derive: (session) => session.events.filter({ stream: "screens" }).map((e) => ({ timeMs: e.timeMs!, data: {} })) });
    expect(undeclared.of(SurveySession.load(dir))).toEqual([]);
  });

  test("a stream that depends on itself fails naming the loop, and a broken stream leaves the session's surveys running", async () => {
    const intoA: EventStreamDefinition[] = [];
    const a = eventStream({ name: "a", dependsOn: intoA, derive: () => [] });
    const b = eventStream({ name: "b", dependsOn: [a], derive: () => [] });
    intoA.push(b);
    const dir = fresh("loop");
    expect(() => a.of(SurveySession.load(dir))).toThrow('stream "a" depends on itself: a → b → a');
    const report = await runSurveys({ surveys: [reachedCart], sessions: [dir], streams: [a, screens, cartVisits()] });
    expect(report.sessions[0]!.errors.map((e) => e.survey)).toEqual(["stream:a"]);
    expect(report.sessions[0]!.findings.map((f) => f.summary)).toEqual(["Reached cart"]);
  });

  test("a chained stream reads the rows of the streams it depends on in a session read from a snapshot", () => {
    const fromSnapshot = sessionFromSnapshot(snapshotOf(SurveySession.load(fresh("snapshot-chain"))));
    expect(cartVisits().of(fromSnapshot).map((r) => r.data.at)).toEqual(["cart"]);
  });

  /** Finds each cart visit, and is run only because `visitLabels` depends on it, so its stored findings can be reused. */
  const visits = withId(survey({ find: (session) => session.stream<{ at: string }>("cart-visits").map((e) => ({ summary: `Reached ${e.data.at}`, evidence: [e] })) }), "visits", "v1");
  const visitLabels = withId(survey({ dependsOn: { visits }, label: ({ dependsOn }) => dependsOn.visits.findings.map((f) => ({ name: f.summary, findings: [f] })) }), "visitLabels", "v1");
  const runVisits = (dir: string, streams: EventStreamDefinition[]) => runSurveys({ surveys: [visitLabels], available: [visits, visitLabels], sessions: [dir], streams });
  const reusedVisits = (report: Awaited<ReturnType<typeof runSurveys>>) => report.surveys.find((s) => s.id === "visits")!.reused;

  test("stored findings over a stream a survey reads by name go stale once that stream changes", async () => {
    const dir = fresh("stale-stream");
    const streams = (label: string, version: string) => [withEventStreamVersion(screens, "v1"), withEventStreamVersion(cartVisits(label), version)];
    writeStoredSurveys(await runVisits(dir, streams("old cart", "v1")));
    expect(reusedVisits(await runVisits(dir, streams("old cart", "v1")))).toBe(1);
    const changed = await runVisits(dir, streams("new cart", "v2"));
    expect(reusedVisits(changed)).toBe(0);
    expect(changed.labels.map((l) => l.name)).toEqual(["Reached new cart"]);
  });

  test("a stream that fails leaves the session's stored findings stale, so a later run surveys it again", async () => {
    const dir = fresh("failed-stream");
    const broken = withEventStreamVersion(eventStream({ name: "broken", derive: () => { throw new Error("no rows today"); } }), "v1");
    const streams = [broken, withEventStreamVersion(screens, "v1"), withEventStreamVersion(cartVisits(), "v1")];
    writeStoredSurveys(await runVisits(dir, streams));
    expect(readStoredSurveys(dir)!.surveys.visits!.errors).toHaveLength(1);
    expect(reusedVisits(await runVisits(dir, streams))).toBe(0);
  });

  test("dependsOn takes only streams", () => {
    expect(() => eventStream({ name: "x", dependsOn: [{} as EventStreamDefinition], derive: () => [] })).toThrow("dependsOn must list streams");
  });
});

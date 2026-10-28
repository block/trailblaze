// Behavioral contract for label phases: a survey's findings across a batch of runs become labels,
// in the order `dependsOn` sets, and running one survey still runs what it needs, reusing stored
// findings when they are current. Exercised over fixture session directories and through the CLI.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, lutimesSync, readFileSync, rmSync, statSync, symlinkSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { SurveySession, loadSurveys, readStoredSurveys, renderMarkdown, renderSummaryLines, runSurveys, survey, writeStoredSurveys, type SurveyDefinition } from "./index.js";
import { withId } from "./survey.js";
import { versionOf } from "./discover.js";
import { sessionInputs } from "./stored.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus";
const SDK = join(import.meta.dir, "..", "..");

let root: string;
let sessionsDir: string;

/** A run whose analytics stream logged one `Journey Complete` per journey given; finished unless `ended` is false. */
function writeSession(
  id: string,
  title: string,
  journeys: string[],
  { platform = "ANDROID", ended = true, parent = sessionsDir }: { platform?: string; ended?: boolean; parent?: string } = {},
): string {
  const dir = join(parent, id);
  mkdirSync(join(dir, "events"), { recursive: true });
  const t0 = 1_700_000_000_000;
  const status = (ms: number, sessionStatus: object) =>
    JSON.stringify({ class: `${LOG}.TrailblazeSessionStatusChangeLog`, session: id, timestamp: new Date(ms).toISOString(), sessionStatus });
  writeFileSync(
    join(dir, "000_TrailblazeSessionStatusChangeLog.json"),
    status(t0, { class: `${STATUS}.Started`, trailConfig: { id: `trail-${id}`, title, platform }, trailblazeDeviceInfo: { platform, classifiers: [platform.toLowerCase()] } }),
  );
  if (ended) writeFileSync(join(dir, "001_TrailblazeSessionStatusChangeLog.json"), status(t0 + 1000, { class: `${STATUS}.Ended.Succeeded`, durationMs: 1000 }));
  writeFileSync(
    join(dir, "events", "app.analytics.ndjson"),
    journeys.map((name, i) => JSON.stringify({ timeMs: t0 + 100 + i, data: { name: "Journey Complete", properties: { journey: name } } })).join("\n") + "\n",
  );
  return dir;
}

let checkout: string;
let refund: string;
let browse: string;

// Stored results are sealed with a key in the Trailblaze state directory; keep this file's own.
const priorHome = process.env.TRAILBLAZE_HOME;

beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "survey-labels-test-"));
  process.env.TRAILBLAZE_HOME = join(root, "trailblaze-home");
  sessionsDir = join(root, "sessions");
  checkout = writeSession("run-checkout", "Checkout with a card", ["take-payment"]);
  refund = writeSession("run-refund", "Refund a sale", ["take-payment", "refund"]);
  browse = writeSession("run-browse", "Browse items", []);
});

afterAll(() => {
  if (priorHome === undefined) delete process.env.TRAILBLAZE_HOME;
  else process.env.TRAILBLAZE_HOME = priorHome;
  rmSync(root, { recursive: true, force: true });
});

/** One finding per completed journey, and one `journey` label per journey across the batch. */
function journeySurvey(calls?: { n: number }) {
  return survey({
    description: "Journeys the run completed",
    find: (session) => {
      if (calls) calls.n += 1;
      return session.analytics.filter({ name: "Journey Complete" }).map((e) => ({
        summary: `Completed ${String(e.properties.journey)}`,
        evidence: [e],
        data: { journey: String(e.properties.journey) },
      }));
    },
    label: (batch) =>
      batch.groupBy((f) => f.data.journey).map(({ key, findings }) => ({ type: "journey", name: key, findings, data: { journey: key } })),
  });
}

/** Label-only: maps journey labels onto the job they belong to. */
function jobSurvey(journeys: SurveyDefinition) {
  return survey({
    description: "Jobs the runs did",
    dependsOn: { journeys },
    label: ({ dependsOn }) =>
      dependsOn.journeys.labels
        .filter((l) => l.name === "take-payment" || l.name === "refund")
        .map((l) => ({ type: "job", name: "handle-money", reasoning: `Completes ${l.name}.`, findings: l.findings })),
  });
}

describe("label phases", () => {
  test("findings become labels across the batch, and each run carries the labels its findings back", async () => {
    const journeys = withId(journeySurvey(), "journeys");
    const report = await runSurveys({ surveys: [journeys], sessions: [checkout, refund, browse] });
    expect(report.labels.map((l) => [l.type, l.name, l.runs])).toEqual([
      ["journey", "take-payment", ["run-checkout", "run-refund"]],
      ["journey", "refund", ["run-refund"]],
    ]);
    expect(report.labels[0]!.findings).toEqual([
      { survey: "journeys", session: "run-checkout", index: 0, summary: "Completed take-payment" },
      { survey: "journeys", session: "run-refund", index: 0, summary: "Completed take-payment" },
    ]);
    // The index names which of the run's findings, so a reference stays exact when summaries repeat.
    expect(report.labels[1]!.findings).toEqual([{ survey: "journeys", session: "run-refund", index: 1, summary: "Completed refund" }]);
    const labelsOf = (id: string) => report.sessions.find((s) => s.session.id === id)!.labels.map((l) => l.name);
    expect(labelsOf("run-refund")).toEqual(["take-payment", "refund"]);
    expect(labelsOf("run-browse")).toEqual([]);
  });

  test("a label phase runs after the surveys in its dependsOn, and reads their labels", async () => {
    const order: string[] = [];
    const base = journeySurvey();
    const journeys = withId(
      survey({ description: "Journeys", find: base.handler, label: (batch) => (order.push("journeys"), base.spec.label!(batch)) }),
      "journeys",
    );
    const inner = jobSurvey(journeys);
    const jobs = withId(survey({ dependsOn: { journeys }, label: (batch) => (order.push("jobs"), inner.spec.label!(batch)) }), "jobs");
    // Listed downstream first: the order comes from `dependsOn`, not from the list.
    const report = await runSurveys({ surveys: [jobs, journeys], sessions: [checkout, refund, browse] });
    expect(order).toEqual(["journeys", "jobs"]);
    const job = report.labels.find((l) => l.survey === "jobs")!;
    expect(job).toMatchObject({ type: "job", name: "handle-money", reasoning: "Completes take-payment.", runs: ["run-checkout", "run-refund"] });
    // Two journey labels name the same job: one label, findings combined.
    expect(job.findings.map((f) => f.summary)).toEqual(["Completed take-payment", "Completed take-payment", "Completed refund"]);
    expect(renderMarkdown(report)).toContain("### job: handle-money");
  });

  test("label phases that form a cycle through dependsOn fail before any session is read, naming the loop", async () => {
    const intoA: Record<string, SurveyDefinition> = {};
    const a = withId(survey({ dependsOn: intoA, label: () => [] }), "a");
    const b = withId(survey({ dependsOn: { a }, label: () => [] }), "b");
    intoA.b = b;
    let read = 0;
    const run = runSurveys({ surveys: [a, b], sessions: [checkout], onSession: () => read++ });
    await expect(run).rejects.toThrow("Survey label phases form a cycle through dependsOn: a → b → a");
    expect(read).toBe(0);
  });

  test("dependsOn on a survey with no label phase is refused when it is defined", () => {
    const journeys = journeySurvey();
    expect(() => survey({ dependsOn: { journeys }, find: { analytics: { name: "x" } } })).toThrow(/dependsOn is for label phases, and this survey has none/);
    // A list is the shape a reader might guess; it names no survey, so it is refused with the right one.
    expect(() => survey({ dependsOn: [journeys] as never, label: () => [] })).toThrow("dependsOn: { journeys }");
  });

  test("the find phase is given once: find, or the older handler or finding, never two", () => {
    const find = () => undefined;
    expect(() => survey({ find }, find)).toThrow(/give the find phase once/);
    expect(() => survey({ find, finding: { analytics: { name: "x" } } })).toThrow(/give the find phase once/);
    // Each spelling alone still defines the same survey.
    for (const def of [survey({ find }), survey({}, find), survey({ find: { analytics: { name: "x" } } }), survey({ finding: { analytics: { name: "x" } } })]) {
      expect(typeof def.handler).toBe("function");
    }
  });

  test("a failed label phase skips every label phase that depends on it", async () => {
    const broken = withId(survey({ label: () => { throw new Error("boom"); } }), "broken");
    const downstream = withId(survey({ dependsOn: { broken }, label: () => [] }), "downstream");
    const report = await runSurveys({ surveys: [broken, downstream], sessions: [checkout] });
    expect(report.labelErrors).toEqual([
      { survey: "broken", message: "label phase failed: boom" },
      { survey: "downstream", message: "label phase skipped: the label phase of broken, which it lists in dependsOn, failed" },
    ]);
  });

  test("a label with no findings behind it is refused", async () => {
    const empty = withId(survey({ label: () => ({ name: "unproven", findings: [] }) }), "empty");
    const report = await runSurveys({ surveys: [empty], sessions: [checkout] });
    expect(report.labels).toEqual([]);
    expect(report.labelErrors[0]!.message).toContain("returned label unproven with no findings");
  });

  test("dependsOn names one survey even when two exports share its spec object", async () => {
    const shared = { label: (batch: { findings: ReadonlyArray<{ summary: string }> }) => batch.findings.map((f) => ({ name: f.summary, findings: [f] })) };
    const from = (summary: string) => (session: { analytics: { filter(q: object): unknown[] } }) =>
      session.analytics.filter({ name: "Journey Complete" }).slice(0, 1).map((e) => ({ summary, evidence: [e] }));
    const a = survey(shared as never, from("from a") as never);
    const b = survey(shared as never, from("from b") as never);
    const down = withId(survey({ dependsOn: { a }, label: ({ dependsOn }) => dependsOn.a.labels.map((l) => ({ name: l.name, findings: l.findings })) }), "down");
    const report = await runSurveys({ surveys: [down], available: [withId(a, "a"), withId(b, "b"), down], sessions: [checkout] });
    expect(report.labelErrors).toEqual([]);
    expect(report.labels.filter((l) => l.survey === "down").map((l) => l.name)).toEqual(["from a"]);
  });

  test("a label citing a finding its label phase was not given is refused", async () => {
    const journeys = withId(journeySurvey(), "journeys");
    const forged = withId(
      survey({
        dependsOn: { journeys },
        label: () => ({ name: "forged", findings: [{ survey: "journeys", session: "run-elsewhere", summary: "Completed take-payment", evidence: [] }] }),
      }),
      "forged",
    );
    const report = await runSurveys({ surveys: [journeys, forged], sessions: [checkout] });
    expect(report.labels.some((l) => l.survey === "forged")).toBe(false);
    expect(report.labelErrors).toEqual([
      { survey: "forged", message: "label phase failed: survey forged returned label forged citing a finding that is not one of the findings its label phase was given" },
    ]);
  });

  test("a label phase sees only the runs its survey applies to", async () => {
    const other = join(root, "scoped");
    const ios = writeSession("run-ios", "Checkout on a phone", ["take-payment"], { platform: "IOS", parent: other });
    const journeys = withId(journeySurvey(), "journeys");
    const seen: string[][] = [];
    const androidJobs = withId(
      survey({
        platforms: ["android"],
        dependsOn: { journeys },
        label: ({ sessions, dependsOn }) => {
          seen.push(sessions.map((r) => r.id), [...new Set(dependsOn.journeys.findings.map((f) => f.session))]);
          return dependsOn.journeys.labels.map((l) => ({ type: "job", name: l.name, findings: l.findings }));
        },
      }),
      "androidJobs",
    );
    const report = await runSurveys({ surveys: [journeys, androidJobs], sessions: [checkout, ios] });
    expect(seen).toEqual([["run-checkout"], ["run-checkout"]]);
    expect(report.labels.filter((l) => l.survey === "androidJobs").map((l) => [l.name, l.runs])).toEqual([["take-payment", ["run-checkout"]]]);
    // The unscoped survey still labels both.
    expect(report.labels.find((l) => l.survey === "journeys")!.runs).toEqual(["run-checkout", "run-ios"]);
  });
});

describe("running one survey", () => {
  test("a survey it depends on runs too, then is reused from stored findings while its version is current", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const sessions = [checkout, refund, browse];
    const logs: string[] = [];

    const first = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions, log: (m) => logs.push(m) });
    expect(calls.n).toBe(3);
    expect(first.surveys.find((s) => s.id === "journeys")).toMatchObject({ neededBy: ["jobs"], reused: 0, version: "v1" });
    expect(logs).toContain("journeys: run because jobs lists it in dependsOn; reused stored findings in 0 session(s), surveyed 3");
    writeStoredSurveys(first);
    expect(readStoredSurveys(refund)!.surveys.journeys).toMatchObject({ version: "v1", findings: [{ summary: "Completed take-payment", version: "v1" }, { summary: "Completed refund" }] });
    expect(readStoredSurveys(refund)!.surveys.jobs!.labels).toMatchObject([{ type: "job", name: "handle-money", runs: ["run-refund"] }]);

    const second = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions, log: (m) => logs.push(m) });
    expect(calls.n).toBe(3);
    expect(second.surveys.find((s) => s.id === "journeys")!.reused).toBe(3);
    expect(second.labels).toEqual(first.labels);
    expect(logs).toContain("journeys: run because jobs lists it in dependsOn; reused stored findings in 3 session(s), surveyed 0");

    // A file added to a session since (screen text extracted afterwards) makes its stored findings stale.
    writeFileSync(join(refund, "visible-strings.ndjson"), "");
    const third = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions });
    expect(calls.n).toBe(4);
    expect(third.surveys.find((s) => s.id === "journeys")!.reused).toBe(2);
    rmSync(join(refund, "visible-strings.ndjson"));

    // So does a file that lands while the session is being surveyed: what is stored was found without it.
    const midRun = withId(survey({
      find: (session) => {
        writeFileSync(join(session.dir, "visible-strings.ndjson"), "");
        return journeys.handler(session);
      },
    }), "journeys", "v1");
    writeStoredSurveys(await runSurveys({ surveys: [midRun], sessions: [refund] }));
    const fourth = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions });
    expect(fourth.surveys.find((s) => s.id === "journeys")!.reused).toBe(2);
    rmSync(join(refund, "visible-strings.ndjson"));

    // A new version makes the stored findings stale.
    const edited = withId(journeys, "journeys", "v2");
    await runSurveys({ surveys: [withId(jobSurvey(edited), "jobs")], available: [edited], sessions });
    expect(calls.n).toBe(9);
  });

  test("only results this machine stored, for this session, unchanged, are reused", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-sealed", "Sealed", ["take-payment"], { parent: join(root, "sealed") });
    const run = () => runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    writeStoredSurveys(await run());
    expect(calls.n).toBe(1);
    await run();
    expect(calls.n).toBe(1);
    const file = join(dir, "surveys.json");
    const sealed = readFileSync(file, "utf8");

    // A session from elsewhere (a downloaded zip) carries results no key here sealed: a forged
    // finding there would otherwise reach whatever renders the report, such as a URL it fetches.
    const forged = JSON.parse(sealed);
    forged.surveys.journeys.findings[0].summary = "Completed refund";
    writeFileSync(file, JSON.stringify(forged));
    await run();
    expect(calls.n).toBe(2);
    delete forged.surveys.journeys.seal;
    writeFileSync(file, JSON.stringify(forged));
    await run();
    expect(calls.n).toBe(3);

    // Sealed here, but for another session: a copy of one session's results is not another's.
    const other = writeSession("run-other", "Other", ["take-payment"], { parent: join(root, "sealed-other") });
    writeFileSync(join(other, "surveys.json"), sealed);
    await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [other] });
    expect(calls.n).toBe(4);
  });

  test("a session edited with its times put back is surveyed again", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-edited", "Edited", ["take-payment"], { parent: join(root, "edited") });
    const run = () => runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    const file = join(dir, "events", "app.analytics.ndjson");
    // A whole second, so putting it back is exact.
    const then = new Date(1_700_000_000_000);
    utimesSync(file, then, then);
    writeStoredSurveys(await run());
    const text = readFileSync(file, "utf8");
    // The same length, so only the contents and the change time differ.
    writeFileSync(file, text.replace("take-payment", "take-pAyment"));
    utimesSync(file, then, then);
    expect(statSync(file).mtimeMs).toBe(then.getTime());
    await run();
    expect(calls.n).toBe(2);
  });

  test("a session handed in already loaded neither reuses nor stores: what it read cannot be known", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-preloaded", "Preloaded", ["take-payment"], { parent: join(root, "preloaded") });
    const loaded = SurveySession.load(dir);
    const report = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [loaded] });
    expect(report.sessions[0]!.findings).toHaveLength(1);
    expect(writeStoredSurveys(report)).toEqual([]);
    // Stored from the directory, then the loaded copy runs again: it surveys rather than reuse.
    writeStoredSurveys(await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] }));
    expect(calls.n).toBe(2);
    await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [loaded] });
    expect(calls.n).toBe(3);
  });

  test("a session holding a link neither reuses nor stores: what it points at can change unseen", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-linked-events", "Linked events", ["take-payment"], { parent: join(root, "linked-events") });
    const outside = join(root, "linked-events-target");
    rmSync(outside, { recursive: true, force: true });
    cpSync(join(dir, "events"), outside, { recursive: true });
    rmSync(join(dir, "events"), { recursive: true });
    symlinkSync(outside, join(dir, "events"));
    const run = () => runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    const unstored: Array<{ path: string; message: string }> = [];
    expect(writeStoredSurveys(await run(), unstored)).toEqual([]);
    expect(unstored).toEqual([]);
    await run();
    expect(calls.n).toBe(2);
  });

  test("a file that lands while a session loads leaves it neither reusing nor storing", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-landing", "Landing", ["take-payment"], { parent: join(root, "landing") });
    const run = () => runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    writeStoredSurveys(await run());
    expect(calls.n).toBe(1);

    const load = SurveySession.load;
    SurveySession.load = (at: string) => {
      writeFileSync(join(at, "visible-strings.ndjson"), "");
      return load.call(SurveySession, at);
    };
    let landed: Awaited<ReturnType<typeof run>>;
    try {
      landed = await run();
    } finally {
      SurveySession.load = load;
    }
    expect(landed.sessions[0]!.inputs).toBeNull();
    expect(landed.surveys.find((s) => s.id === "journeys")!.reused).toBe(0);
    expect(calls.n).toBe(2);
  });

  test("the seal key is made whole, and an empty one left behind is replaced", async () => {
    const prior = process.env.TRAILBLAZE_HOME;
    const home = join(root, "broken-key-home");
    mkdirSync(home, { recursive: true });
    writeFileSync(join(home, "survey-results.key"), "");
    process.env.TRAILBLAZE_HOME = home;
    try {
      const calls = { n: 0 };
      const journeys = withId(journeySurvey(calls), "journeys", "v1");
      const jobs = withId(jobSurvey(journeys), "jobs", "v1");
      const dir = writeSession("run-key", "Key", ["take-payment"], { parent: join(root, "key") });
      const run = () => runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
      writeStoredSurveys(await run());
      expect(readFileSync(join(home, "survey-results.key"))).toHaveLength(32);
      expect(readdirSync(home)).toEqual(["survey-results.key"]);
      await run();
      expect(calls.n).toBe(1);
    } finally {
      process.env.TRAILBLAZE_HOME = prior;
    }
  });

  test("a lock dated in the future was never taken here, and is taken over", async () => {
    const dir = writeSession("run-future-lock", "Future lock", ["take-payment"], { parent: join(root, "future-lock") });
    const lock = join(dir, "surveys.json.lock");
    writeFileSync(lock, "from-a-zip");
    const later = new Date(Date.now() + 3_600_000);
    utimesSync(lock, later, later);
    const started = Date.now();
    writeStoredSurveys(await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [dir] }));
    expect(Date.now() - started).toBeLessThan(10_000);
    expect(readStoredSurveys(dir)!.surveys.journeys!.findings).toHaveLength(1);
  });

  test("a session still running is not stored, so a later run surveys it again", async () => {
    const running = writeSession("run-running", "Still going", ["take-payment"], { ended: false, parent: join(root, "running") });
    const report = await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [running] });
    expect(report.sessions[0]!.findings).toHaveLength(1);
    expect(writeStoredSurveys(report)).toEqual([]);
    expect(existsSync(join(running, "surveys.json"))).toBe(false);
  });

  test("storing writes nothing through a file already in the session", async () => {
    const dir = writeSession("run-planted", "Planted", ["take-payment"], { parent: join(root, "planted") });
    const outside = join(root, "outside.txt");
    writeFileSync(outside, "untouched");
    symlinkSync(outside, join(dir, "surveys.json.tmp"));
    writeStoredSurveys(await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [dir] }));
    expect(readFileSync(outside, "utf8")).toBe("untouched");
    // The planted link is not a file storing writes, so the session holds a link and is not stored.
    expect(readStoredSurveys(dir)).toBeUndefined();
  });

  test("a stored entry the survey was skipped in, or that is malformed, is not reused", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-cache", "Cache", ["take-payment"], { parent: join(root, "cache") });
    const store = (entry: unknown) =>
      writeFileSync(join(dir, "surveys.json"), JSON.stringify({ format: 1, surveys: { journeys: entry } }));

    // Skipped once (its targets were not loaded then); it may apply now, so it runs.
    store({ version: "v1", generatedAt: "", findings: [], labels: [], skipped: true });
    await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    expect(calls.n).toBe(1);

    store({ version: "v1", generatedAt: "", findings: null, labels: [] });
    await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    expect(calls.n).toBe(2);

    // Current in every other way, but a finding in it is not one: surveyed again, never a crash.
    store({ version: "v1", generatedAt: "", inputs: sessionInputs(dir), findings: [{ summary: "x", evidence: [null] }], labels: [] });
    const malformed = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    expect(calls.n).toBe(3);
    expect(malformed.sessions[0]!.errors).toEqual([]);

    writeFileSync(join(dir, "surveys.json"), JSON.stringify({ format: 1, surveys: null }));
    const report = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    expect(calls.n).toBe(4);
    // And the unreadable file is replaced rather than failing the write.
    writeStoredSurveys(report);
    expect(readStoredSurveys(dir)!.surveys.journeys!.findings).toHaveLength(1);
  });

  test("storing waits out a lock another run left behind once it is stale, then releases its own", async () => {
    const dir = writeSession("run-locked", "Locked", ["take-payment"], { parent: join(root, "locked") });
    const lock = join(dir, "surveys.json.lock");
    writeFileSync(lock, "");
    const old = new Date(Date.now() - 60_000);
    utimesSync(lock, old, old);
    writeStoredSurveys(await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [dir] }));
    expect(readStoredSurveys(dir)!.surveys.journeys!.findings).toHaveLength(1);
    expect(existsSync(lock)).toBe(false);
    // Nothing of the lock is left behind: not the stale one, and not this run's own.
    expect(readdirSync(dir).filter((f) => f.startsWith("surveys.json"))).toEqual(["surveys.json"]);
  });

  test("a stale lock that links to a device is taken over without reading through it", async () => {
    const dir = writeSession("run-device-lock", "Device lock", ["take-payment"], { parent: join(root, "device-lock") });
    const lock = join(dir, "surveys.json.lock");
    // Read through, /dev/zero never ends: the run would fill memory rather than store anything.
    symlinkSync("/dev/zero", lock);
    const old = new Date(Date.now() - 60_000);
    lutimesSync(lock, old, old);
    writeStoredSurveys(await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [dir] }));
    expect(readStoredSurveys(dir)!.surveys.journeys!.findings).toHaveLength(1);
    expect(readdirSync(dir).filter((f) => f.startsWith("surveys.json"))).toEqual(["surveys.json"]);
  });

  test("a stored results file that links elsewhere is not read, and storing replaces the link", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const other = writeSession("run-other", "Other", ["take-payment", "refund"], { parent: join(root, "linked") });
    writeStoredSurveys(await runSurveys({ surveys: [journeys], sessions: [other] }));
    const theirs = readFileSync(join(other, "surveys.json"), "utf8");
    const dir = writeSession("run-imported", "Imported", ["refund"], { parent: join(root, "linked") });
    symlinkSync(join(other, "surveys.json"), join(dir, "surveys.json"));
    calls.n = 0;

    const report = await runSurveys({ surveys: [jobs], available: [journeys, jobs], sessions: [dir] });
    expect(calls.n).toBe(1);
    expect(report.sessions[0]!.findings.map((f) => f.summary)).toEqual(["Completed refund"]);
    writeStoredSurveys(report);
    expect(readFileSync(join(other, "surveys.json"), "utf8")).toBe(theirs);
    expect(readStoredSurveys(dir)!.surveys.journeys!.findings.map((f) => f.summary)).toEqual(["Completed refund"]);
  });

  test("stored labels citing findings a later run changed are dropped with their entry", async () => {
    const journeys = withId(journeySurvey(), "journeys", "v1");
    const jobs = withId(jobSurvey(journeys), "jobs", "v1");
    const dir = writeSession("run-relabel", "Relabel", ["take-payment", "refund"], { parent: join(root, "relabel") });
    writeStoredSurveys(await runSurveys({ surveys: [journeys, jobs], sessions: [dir] }));
    expect(readStoredSurveys(dir)!.surveys.jobs!.labels).toHaveLength(1);

    // The same findings again: the labels citing them still hold.
    writeStoredSurveys(await runSurveys({ surveys: [journeys], sessions: [dir] }));
    expect(readStoredSurveys(dir)!.surveys.jobs!.labels).toHaveLength(1);

    // take-payment is gone, so refund's finding moved to position 0 and jobs' references no longer hold.
    writeFileSync(join(dir, "events", "app.analytics.ndjson"), JSON.stringify({ timeMs: 1_700_000_000_100, data: { name: "Journey Complete", properties: { journey: "refund" } } }) + "\n");
    writeStoredSurveys(await runSurveys({ surveys: [journeys], sessions: [dir] }));
    const stored = readStoredSurveys(dir)!.surveys;
    expect(stored.journeys!.findings.map((f) => f.summary)).toEqual(["Completed refund"]);
    expect(stored.jobs).toBeUndefined();
  });

  test("a copy the session filter leaves out does not displace a later copy it keeps", async () => {
    const copy = join(root, "filtered-copies", "run-checkout");
    cpSync(checkout, copy, { recursive: true });
    const report = await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [checkout, copy], where: (s) => s.path === copy });
    expect(report.sessions.map((s) => s.session.path)).toEqual([copy]);
    expect(report.excluded).toEqual([{ id: "run-checkout", path: checkout }]);
  });

  test("a session that cannot store its results does not stop the others", async () => {
    const blocked = writeSession("run-blocked", "Blocked", ["take-payment"], { parent: join(root, "unstorable") });
    const open = writeSession("run-writable", "Writable", ["refund"], { parent: join(root, "unstorable") });
    const report = await runSurveys({ surveys: [withId(journeySurvey(), "journeys", "v1")], sessions: [blocked, open] });
    // A directory where the file goes: nothing can replace it, whoever the run is (CI runs as root,
    // which a read-only directory would not stop).
    mkdirSync(join(blocked, "surveys.json", "occupied"), { recursive: true });

    const failed: Array<{ path: string; message: string }> = [];
    expect(writeStoredSurveys(report, failed)).toEqual([join(open, "surveys.json")]);
    expect(failed.map((f) => f.path)).toEqual([blocked]);
    // Without somewhere to put them, the failures are thrown, once the others are written.
    rmSync(join(open, "surveys.json"));
    expect(() => writeStoredSurveys(report)).toThrow(blocked);
    expect(existsSync(join(open, "surveys.json"))).toBe(true);
  });

  test("a session's fingerprint leaves out only what storing writes, not files under a similar name", () => {
    const dir = writeSession("run-blobs", "Blobs", ["take-payment"], { parent: join(root, "blobs") });
    mkdirSync(join(dir, "surveys.json.blobs"), { recursive: true });
    writeFileSync(join(dir, "surveys.json.blobs", "body.json"), `{"v":1}`);
    const before = sessionInputs(dir);
    writeFileSync(join(dir, "surveys.json"), "{}");
    writeFileSync(join(dir, "surveys.json.lock"), "token");
    writeFileSync(join(dir, "surveys.json.6f1c2a.tmp"), "");
    expect(sessionInputs(dir)).toBe(before);
    writeFileSync(join(dir, "surveys.json.blobs", "body.json"), `{"v":22}`);
    expect(sessionInputs(dir)).not.toBe(before);
  });

  test("a session id that is not a string is not taken: the directory names the session", async () => {
    const dir = writeSession("run-odd-id", "Odd id", ["take-payment"], { parent: join(root, "odd-id") });
    const first = join(dir, "000_TrailblazeSessionStatusChangeLog.json");
    writeFileSync(first, JSON.stringify({ ...JSON.parse(readFileSync(first, "utf8")), session: { toString: null, valueOf: null } }));
    const report = await runSurveys({ surveys: [withId(journeySurvey({ n: 0 }), "journeys", "v1")], sessions: [dir] });
    expect(report.sessions.map((s) => s.session.id)).toEqual(["run-odd-id"]);
    expect(report.sessions[0]!.findings.length).toBeGreaterThan(0);
  });

  test("a second input claiming a session's id but recording it differently is reported, not taken for it", async () => {
    const other = writeSession("run-checkout", "Checkout, recorded elsewhere", ["refund"], { parent: join(root, "claimed") });
    const report = await runSurveys({ surveys: [withId(journeySurvey({ n: 0 }), "journeys", "v1")], sessions: [checkout, other] });
    expect(report.sessions.map((s) => s.session.path)).toEqual([checkout]);
    expect(report.excluded).toEqual([]);
    expect(report.unreadable).toEqual([{ path: other, message: `claims session run-checkout, as ${checkout} does, but records it differently`, conflictsWith: checkout }]);
  });

  test("the same session at two paths is surveyed once, and the second path is reported", async () => {
    const copy = join(root, "copies", "run-checkout");
    cpSync(checkout, copy, { recursive: true });
    const journeys = withId(journeySurvey(), "journeys", "v1");
    const report = await runSurveys({ surveys: [journeys, withId(jobSurvey(journeys), "jobs", "v1")], sessions: [checkout, copy] });
    expect(report.sessions.map((s) => s.session.path)).toEqual([checkout]);
    expect(report.excluded).toEqual([{ id: "run-checkout", path: copy, sameAs: checkout }]);
    expect(report.labels.find((l) => l.survey === "journeys")).toMatchObject({ runs: ["run-checkout"], findings: [{ index: 0 }] });
  });

  test("a failed label phase has its own line in the summary", async () => {
    const broken = withId(survey({ label: () => { throw new Error("boom"); } }), "broken");
    const report = await runSurveys({ surveys: [broken], sessions: [checkout] });
    expect(renderSummaryLines(report)).toContain("LABELS    broken  →  label phase failed: boom");
  });

  test("a survey asked for by name is always surveyed again, never served from storage", async () => {
    const calls = { n: 0 };
    const journeys = withId(journeySurvey(calls), "journeys", "v1");
    writeStoredSurveys(await runSurveys({ surveys: [journeys], sessions: [checkout] }));
    await runSurveys({ surveys: [journeys], sessions: [checkout] });
    expect(calls.n).toBe(2);
  });
});

describe("the CLI", () => {
  const files = () => join(root, "surveys");
  beforeAll(() => {
    mkdirSync(files(), { recursive: true });
    writeFileSync(
      join(files(), "journeys.survey.ts"),
      `import { trailblaze } from "@trailblaze/scripting/surveys";
export const journeys = trailblaze.survey({
  description: "Journeys the run completed",
  find: (session) => session.analytics.filter({ name: "Journey Complete" }).map((e) => ({ summary: "Completed " + String(e.properties.journey), evidence: [e], data: { journey: String(e.properties.journey) } })),
  label: (batch) => batch.groupBy((f) => f.data.journey).map(({ key, findings }) => ({ type: "journey", name: key, findings })),
});
`,
    );
    writeFileSync(
      join(files(), "jobs.survey.ts"),
      `import { trailblaze } from "@trailblaze/scripting/surveys";
import { journeys } from "./journeys.survey.ts";
export const jobs = trailblaze.survey({
  description: "Jobs the runs did",
  dependsOn: { journeys },
  label: ({ dependsOn }) => dependsOn.journeys.labels.filter((l) => l.name === "take-payment").map((l) => ({ type: "job", name: "take-payment", findings: l.findings })),
});
`,
    );
  });

  const cli = (...args: string[]) =>
    spawnSync("bun", [join(import.meta.dir, "cli.ts"), "--surveys", files(), ...args], { cwd: root, encoding: "utf8" });

  test("--survey runs one survey and what it depends on, says so, and --write stores findings and labels in each run", () => {
    const out = cli("--survey", "jobs", "--write", checkout, refund);
    expect(out.status).toBe(0);
    expect(out.stderr).toContain("journeys: run because jobs lists it in dependsOn; reused stored findings in");
    expect(out.stdout).toContain("## Labels");
    expect(out.stdout).toContain("### job: take-payment\n\nFrom **jobs**, 2 runs.");
    expect(out.stdout).toContain("- run-refund — Refund a sale\n  - **journeys** — Completed take-payment");
    expect(existsSync(join(refund, "surveys.json"))).toBe(true);
    const stored = JSON.parse(readFileSync(join(refund, "surveys.json"), "utf8"));
    expect(Object.keys(stored.surveys).sort()).toEqual(["jobs", "journeys"]);
    expect(stored.surveys.journeys.version).toMatch(/^[0-9a-f]{12}$/);

    const again = cli("--survey", "jobs", checkout, refund);
    expect(again.stderr).toContain("journeys: run because jobs lists it in dependsOn; reused stored findings in 2 session(s), surveyed 0");
  });

  test("--out naming a session's own stored results is refused before anything is written", () => {
    const target = join(checkout, "surveys.json");
    const before = existsSync(target) ? readFileSync(target, "utf8") : undefined;
    const out = cli("--survey", "journeys", "--write", "--out", target, checkout);
    expect(out.status).toBe(2);
    expect(out.stderr).toContain("is where session run-checkout stores its survey results");
    expect(existsSync(target) ? readFileSync(target, "utf8") : undefined).toBe(before);

    // The same file reached through a link to the session is the same clash.
    const linked = join(root, "linked-checkout");
    rmSync(linked, { force: true });
    symlinkSync(checkout, linked);
    const viaLink = cli("--survey", "journeys", "--write", "--out", target, linked);
    expect(viaLink.status).toBe(2);
    expect(viaLink.stderr).toContain("stores its survey results");
    expect(existsSync(target) ? readFileSync(target, "utf8") : undefined).toBe(before);

    // So is a copy of the session, left out of the batch as the same session given twice.
    const copy = join(root, "copied-checkout");
    rmSync(copy, { recursive: true, force: true });
    cpSync(checkout, copy, { recursive: true });
    const copyTarget = join(copy, "surveys.json");
    const copyBefore = existsSync(copyTarget) ? readFileSync(copyTarget, "utf8") : undefined;
    const viaCopy = cli("--survey", "journeys", "--write", "--out", copyTarget, checkout, copy);
    expect(viaCopy.status).toBe(2);
    expect(existsSync(copyTarget) ? readFileSync(copyTarget, "utf8") : undefined).toBe(copyBefore);
  });

  test("two recordings of one session fail the run and say which was left out", () => {
    const other = writeSession("run-checkout", "Checkout, recorded elsewhere", ["refund"], { parent: join(root, "claimed-cli") });
    const out = cli("--survey", "journeys", "--format", "none", checkout, other);
    expect(out.status).toBe(1);
    expect(out.stderr).toContain(`Left out ${other}: it claims session run-checkout, as ${checkout} does, but records it differently`);
  });

  test("an unknown --survey name is a usage error that names it", () => {
    const out = cli("--survey", "nope", checkout);
    expect(out.status).toBe(2);
    expect(out.stderr).toContain("No survey named nope");
  });

  test("a survey whose modules changed since this process first read them gets no version", async () => {
    const sdk = JSON.stringify(join(import.meta.dir, "index.js"));
    const dir = join(root, "changed-since");
    mkdirSync(dir, { recursive: true });
    const write = (name: string, text: string) => writeFileSync(join(dir, name), text);
    const edit = (name: string) => writeFileSync(join(dir, name), readFileSync(join(dir, name), "utf8") + "// edited\n");
    const load = async (name: string) => (await loadSurveys([join(dir, name)]))[0]!.definition.version;

    // The same survey loaded again after an edit: the cached module runs, not the file.
    write("again.survey.ts", `import { survey } from ${sdk};\nexport const again = survey({ find: () => [] });\n`);
    expect(await load("again.survey.ts")).toMatch(/^[0-9a-f]{12}$/);
    edit("again.survey.ts");
    expect(await load("again.survey.ts")).toBeUndefined();

    // Loaded again through a link: the same module, by its real path.
    write("linked.survey.ts", `import { survey } from ${sdk};\nexport const linked = survey({ find: () => [] });\n`);
    expect(await load("linked.survey.ts")).toMatch(/^[0-9a-f]{12}$/);
    edit("linked.survey.ts");
    symlinkSync(join(dir, "linked.survey.ts"), join(dir, "link.survey.ts"));
    expect(await load("link.survey.ts")).toBeUndefined();

    // A helper one survey imported, edited before another survey that imports it loads.
    write("helper.ts", `export const rule = 1;\n`);
    for (const name of ["first", "second"]) {
      write(`${name}.survey.ts`, `import { survey } from ${sdk};\nimport { rule } from "./helper";\nexport const ${name} = survey({ find: () => (rule ? [] : []) });\n`);
    }
    expect(await load("first.survey.ts")).toMatch(/^[0-9a-f]{12}$/);
    edit("helper.ts");
    expect(await load("second.survey.ts")).toBeUndefined();

    // A module loaded with require or import() may load after the survey does, whatever the file
    // holds then, so a survey reaching one gets no version from the start.
    write("rules.cjs", `module.exports = 1;\n`);
    write("lazy.cjs", `module.exports = () => require("./rules.cjs");\n`);
    write("lazy.survey.ts", `import { survey } from ${sdk};\nimport lazy from "./lazy.cjs";\nexport const lazily = survey({ find: () => (lazy ? [] : []) });\n`);
    write("dynamic.survey.ts", `import { survey } from ${sdk};\nexport const dynamic = survey({ find: async () => ((await import("./rules.cjs")) ? [] : []) });\n`);
    expect(await load("lazy.survey.ts")).toBeUndefined();
    expect(await load("dynamic.survey.ts")).toBeUndefined();
    // So does a load whose path is computed: no file to hash at all.
    write("computed.survey.ts", `import { survey } from ${sdk};\nconst rules = "./rules.cjs";\nexport const computed = survey({ find: async () => ((await import(rules)) ? [] : []) });\n`);
    expect(await load("computed.survey.ts")).toBeUndefined();

    // A module seen to change stays untrusted once changed back: the edited copy is the one loaded.
    write("eager.survey.ts", `import { survey } from ${sdk};\nimport { rule } from "./eager-rules";\nexport const eager = survey({ find: () => (rule ? [] : []) });\n`);
    write("eager-rules.ts", `export const rule = 1;\n`);
    expect(await load("eager.survey.ts")).toMatch(/^[0-9a-f]{12}$/);
    const rules = readFileSync(join(dir, "eager-rules.ts"), "utf8");
    edit("eager-rules.ts");
    expect(await load("eager.survey.ts")).toBeUndefined();
    write("eager-rules.ts", rules);
    expect(await load("eager.survey.ts")).toBeUndefined();

    // Any later survey reaching the edited helper gets none either; one reaching no changed module keeps its version.
    write("untouched.survey.ts", `import { survey } from ${sdk};\nimport { rule } from "./helper";\nexport const untouched = survey({ find: () => (rule ? [] : []) });\n`);
    expect(await load("untouched.survey.ts")).toBeUndefined();
    write("plain.survey.ts", `import { survey } from ${sdk};\nexport const plain = survey({ find: () => [] });\n`);
    expect(await load("plain.survey.ts")).toMatch(/^[0-9a-f]{12}$/);
  });

  test("an import that would resolve elsewhere now never takes the new file's version in this process", async () => {
    const dir = join(root, "re-resolved");
    mkdirSync(join(dir, "rules"), { recursive: true });
    writeFileSync(join(dir, "rules", "index.ts"), `export const rule = 1;\n`);
    const file = join(dir, "resolved.survey.ts");
    writeFileSync(file, `import { survey } from ${JSON.stringify(join(import.meta.dir, "index.js"))};\nimport { rule } from "./rules";\nexport const resolved = survey({ find: () => (rule ? [] : []) });\n`);
    const [first] = await loadSurveys([file]);
    writeFileSync(join(dir, "rules.ts"), `export const rule = 2;\n`);
    const [second] = await loadSurveys([file]);
    // Never the version a fresh process computes: either the module already linked, or none.
    expect([first!.definition.version, undefined]).toContain(second!.definition.version);
    // A fresh process resolves to the new file.
    const fresh = spawnSync("bun", ["-e", `import { versionOf } from ${JSON.stringify(join(import.meta.dir, "discover.js"))}; console.log(versionOf(${JSON.stringify(file)}));`], { encoding: "utf8" });
    expect(fresh.stdout.trim()).toMatch(/^[0-9a-f]{12}$/);
    expect(fresh.stdout.trim()).not.toBe(first!.definition.version!);
  });

  test("a version follows what runs: a package entry point counts, a type-only import does not", () => {
    const dir = join(root, "runtime-graph");
    mkdirSync(join(dir, "rules"), { recursive: true });
    writeFileSync(join(dir, "graph.survey.ts"), `import type { Shape } from "./types";\nimport helper from "./helper.cjs";\nexport const graph: Shape = helper;\n`);
    writeFileSync(join(dir, "types.ts"), `export type Shape = number;\n`);
    writeFileSync(join(dir, "helper.cjs"), `module.exports = require("./rules");\n`);
    writeFileSync(join(dir, "rules", "package.json"), JSON.stringify({ main: "logic.cjs" }));
    writeFileSync(join(dir, "rules", "logic.cjs"), `module.exports = 1;\n`);
    const before = versionOf(join(dir, "graph.survey.ts"));
    writeFileSync(join(dir, "types.ts"), `export type Shape = string | number;\n`);
    expect(versionOf(join(dir, "graph.survey.ts"))).toBe(before);
    writeFileSync(join(dir, "rules", "logic.cjs"), `module.exports = 2;\n`);
    expect(versionOf(join(dir, "graph.survey.ts"))).not.toBe(before);
  });

  test("each survey file loads with its version: a hash of the file", async () => {
    const loaded = await loadSurveys([files()]);
    expect(loaded.map((l) => [l.exportName, typeof l.definition.version])).toEqual([
      ["jobs", "string"],
      ["journeys", "string"],
    ]);
  });
});

describe("a survey's version", () => {
  test("changes when a local module it imports changes, however the import is written, and not otherwise", () => {
    const dir = join(root, "versioned");
    mkdirSync(join(dir, "shared"), { recursive: true });
    writeFileSync(join(dir, "a.survey.ts"), `import { catalog } from "./catalog";\nimport { lib } from "./lib.js";\nimport "./shared";\nimport helper from "./helper.cjs";\nexport const a = 1;\n`);
    writeFileSync(join(dir, "catalog.ts"), `import { deep } from "./deep.ts";\nexport const catalog = deep;\n`);
    writeFileSync(join(dir, "deep.ts"), `export const deep = 1;\n`);
    writeFileSync(join(dir, "lib.ts"), `export const lib = 1;\n`);
    writeFileSync(join(dir, "shared", "index.ts"), `export {};\n`);
    writeFileSync(join(dir, "helper.cjs"), `module.exports = require("./rules.cjs");\n`);
    writeFileSync(join(dir, "rules.cjs"), `module.exports = 1;\n`);
    writeFileSync(join(dir, "unrelated.ts"), `export const x = 1;\n`);
    const before = versionOf(join(dir, "a.survey.ts"));
    expect(before).toMatch(/^[0-9a-f]{12}$/);

    writeFileSync(join(dir, "unrelated.ts"), `export const x = 2;\n`);
    expect(versionOf(join(dir, "a.survey.ts"))).toBe(before);

    let last = before;
    for (const edited of ["deep.ts", "lib.ts", join("shared", "index.ts"), "rules.cjs"]) {
      writeFileSync(join(dir, edited), readFileSync(join(dir, edited), "utf8") + "// edited\n");
      const now = versionOf(join(dir, "a.survey.ts"));
      expect(now).not.toBe(last);
      last = now;
    }
  });
});

describe("label types", () => {
  test("with no annotations, a label phase reads its own and its dependencies' findings and labels typed", () => {
    const dir = join(root, "typed");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, "typed.ts"),
      `import { survey } from "${join(import.meta.dir, "index.js")}";
const journeys = survey({
  find: () => ({ summary: "x", evidence: [], data: { journey: "pay", id: 7 } }),
  label: (batch) => {
    const id: number = batch.findings[0]!.data.id;
    // @ts-expect-error: a journey is a string.
    const notANumber: number = batch.findings[0]!.data.journey;
    return batch.groupBy((f) => f.data.journey).map(({ key, findings }) => ({ type: "journey", name: key, data: { id: id + notANumber }, findings }));
  },
});
export const jobs = survey({
  dependsOn: { journeys },
  label: ({ dependsOn }) => {
    const ids: number[] = dependsOn.journeys.labels.map((l) => l.data!.id);
    const names: string[] = dependsOn.journeys.findings.map((f) => f.data.journey);
    // @ts-expect-error: a journey label's data has no such field.
    dependsOn.journeys.labels.map((l) => l.data!.missing);
    // @ts-expect-error: a finding's journey is a string.
    const wrong: number[] = dependsOn.journeys.findings.map((f) => f.data.journey);
    // @ts-expect-error: only what dependsOn lists can be read.
    dependsOn.refunds.labels;
    return [{ name: String(ids.length + names.length + wrong.length), findings: [] }];
  },
});
`,
    );
    writeFileSync(
      join(dir, "tsconfig.json"),
      JSON.stringify({ compilerOptions: { target: "ES2022", module: "ESNext", moduleResolution: "Bundler", lib: ["ES2022"], types: ["node"], typeRoots: [join(SDK, "node_modules", "@types")], strict: true, noEmit: true, skipLibCheck: true, allowImportingTsExtensions: true }, files: ["typed.ts"] }),
    );
    const out = spawnSync(join(SDK, "node_modules", ".bin", "tsc"), ["-p", join(dir, "tsconfig.json")], { encoding: "utf8" });
    expect(out.stdout + out.stderr).toBe("");
    expect(out.status).toBe(0);
  }, 60_000);
});

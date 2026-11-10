import { describe, expect, test } from "bun:test";
import { mergeRunWarnings, runWarningsFromDerivedStreams, runWarningsFromSurveys } from "./run-report-warnings";

const surveys = (entries: Record<string, unknown>) => JSON.stringify({ format: 1, surveys: entries });

describe("a run's warnings (runWarningsFromSurveys)", () => {
  test("each finding of a warning survey is a warning; other surveys' findings are not", () => {
    const text = surveys({
      serverErrors: { warning: true, findings: [{ summary: "GET /cart returned 503", evidence: [] }, { summary: "POST /pay returned 500", evidence: [] }] },
      crashes: { warning: true, findings: [{ summary: "App crashed: NullPointerException", evidence: [] }] },
      journeys: { findings: [{ summary: "Checkout journey", evidence: [] }] },
    });
    expect(runWarningsFromSurveys(text)).toEqual([
      { survey: "crashes", summary: "App crashed: NullPointerException" },
      { survey: "serverErrors", summary: "GET /cart returned 503" },
      { survey: "serverErrors", summary: "POST /pay returned 500" },
    ]);
  });

  test("a run no warning survey found anything in has no warnings", () => {
    expect(runWarningsFromSurveys(surveys({ crashes: { warning: true, findings: [] } }))).toBeNull();
    expect(runWarningsFromSurveys(surveys({ journeys: { findings: [{ summary: "Checkout", evidence: [] }] } }))).toBeNull();
  });

  test("content that is not survey results yields no warnings rather than a broken report", () => {
    expect(runWarningsFromSurveys("{not json")).toBeNull();
    expect(runWarningsFromSurveys("[]")).toBeNull();
    expect(runWarningsFromSurveys(JSON.stringify({ surveys: [] }))).toBeNull();
    expect(runWarningsFromSurveys(surveys({ crashes: { warning: "yes", findings: [{ summary: "x" }] } }))).toBeNull();
    expect(runWarningsFromSurveys(surveys({ crashes: { warning: true, findings: [null, { summary: 3 }, { summary: " " }] } }))).toBeNull();
  });

  test("an archive cannot flood the report: warnings are capped in count, and in the length of each survey id and summary", () => {
    const many = Array.from({ length: 80 }, (_, i) => ({ summary: `warning ${i}`, evidence: [] }));
    const warnings = runWarningsFromSurveys(surveys({ noisy: { warning: true, findings: [...many, { summary: "y".repeat(2000), evidence: [] }] } }))!;
    expect(warnings.length).toBe(50);
    const long = runWarningsFromSurveys(surveys({ noisy: { warning: true, findings: [{ summary: "y".repeat(2000), evidence: [] }] } }))!;
    expect(long[0].summary.length).toBe(501);
    const longId = runWarningsFromSurveys(surveys({ ["s".repeat(900_000)]: { warning: true, findings: [{ summary: "x" }, { summary: "y" }] } }))!;
    expect(longId.map((warning) => warning.survey.length)).toEqual([101, 101]);
  });
});

const manifest = (streams: Record<string, unknown>) => JSON.stringify({ format: 1, streams });

describe("a run's warnings for derived streams that failed (runWarningsFromDerivedStreams)", () => {
  test("each stream that failed is a warning naming the stream and its error; streams that derived are not", () => {
    const text = manifest({
      feature: { rows: 0, error: "The flags service answered HTTP 403 for checkout-v2" },
      screens: { version: "1", rows: 12, sha: "abc" },
      empty: { version: "1", rows: 0 },
      screens2: { rows: 0, error: 'stream "screens2": derive must return an array of rows' },
    });
    expect(runWarningsFromDerivedStreams(text)).toEqual([
      { survey: "derived stream feature", summary: "The feature stream failed, so its lane is missing from this report: The flags service answered HTTP 403 for checkout-v2" },
      { survey: "derived stream screens2", summary: "The screens2 stream failed, so its lane is missing from this report: derive must return an array of rows" },
    ]);
  });

  test("a run whose streams all derived, or content that is not a manifest, has no warnings", () => {
    expect(runWarningsFromDerivedStreams(manifest({ screens: { rows: 3 } }))).toBeNull();
    expect(runWarningsFromDerivedStreams("{not json")).toBeNull();
    expect(runWarningsFromDerivedStreams(JSON.stringify({ streams: [] }))).toBeNull();
    expect(runWarningsFromDerivedStreams(manifest({ a: { error: 3 }, c: null }))).toBeNull();
  });

  test("a stream that failed with an empty message is still a warning", () => {
    expect(runWarningsFromDerivedStreams(manifest({ silent: { rows: 0, error: " " } }))).toEqual([
      { survey: "derived stream silent", summary: "The silent stream failed, so its lane is missing from this report: failed without a message" },
    ]);
  });

  test("an archive cannot flood the report: capped in count and length", () => {
    const many = Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`s${String(i).padStart(2, "0")}`, { rows: 0, error: "x".repeat(2000) }]));
    const warnings = runWarningsFromDerivedStreams(manifest(many))!;
    expect(warnings.length).toBe(50);
    expect(warnings[0].summary.length).toBe(501);
  });

  test("survey warnings come first, then stream failures, under one cap", () => {
    const a = { survey: "crashes", summary: "App crashed" };
    const b = { survey: "derived stream feature", summary: "failed" };
    expect(mergeRunWarnings([a], [b])).toEqual([a, b]);
    expect(mergeRunWarnings(null, [b])).toEqual([b]);
    expect(mergeRunWarnings(null, undefined)).toBeNull();
    expect(mergeRunWarnings(Array(40).fill(a), Array(40).fill(b))!.length).toBe(50);
  });
});

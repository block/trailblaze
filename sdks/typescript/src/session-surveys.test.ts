import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import type { SessionEvents } from "./session-events.js";
import { createSessionSurveys, HOST_FUNCTION_SURVEYS_LOAD } from "./session-surveys.js";

const SDK = resolve(import.meta.dir, "..");

/**
 * Bundles a survey file the way the framework does: one IIFE holding the survey and the sandbox
 * surveys runtime, with `@trailblaze/scripting/surveys` aliased to the entry that reads no files.
 */
function bundleSurvey(source: string): { source: string; file: string } {
  const dir = mkdtempSync(join(tmpdir(), "survey-bundle-"));
  const file = join(dir, "checkout.survey.ts");
  writeFileSync(file, source);
  const entry = [
    `import * as surveys from ${JSON.stringify(file)};`,
    `import { runNamedLiveSurvey } from "@trailblaze/scripting/surveys";`,
    `export const runLive = (name, events, options) => runNamedLiveSurvey(surveys, name, events, options);`,
  ].join("\n");
  const out = spawnSync(
    join(SDK, "node_modules/.bin/esbuild"),
    [
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--global-name=__trailblazeSurveyBundle",
      `--alias:@trailblaze/scripting/surveys=${join(SDK, "src/surveys/in-process.ts")}`,
      `--alias:@trailblaze/scripting=${join(SDK, "src/in-process.ts")}`,
      "--sourcefile=survey-entry.ts",
      "--loader=ts",
    ],
    // Piped in, so it resolves from the working directory.
    { input: entry, encoding: "utf8", cwd: dir },
  );
  if (out.status !== 0) throw new Error(out.stderr);
  return { source: out.stdout, file: "trailmaps/shop/surveys/checkout.survey.ts" };
}

function events(rows: Record<string, unknown[]>): SessionEvents {
  return {
    list: async () => Object.keys(rows),
    query: async (stream, options = {}) => {
      const all = (rows[stream] ?? []).map((data, i) => ({ timeMs: i, data }));
      const from = options.after ?? 0;
      const page = all.slice(from, from + (options.limit ?? 100));
      return { captured: stream in rows, complete: true, events: page, cursor: from + page.length, scan: { bytesRead: 0, lines: 0, parsed: 0, oversized: 0, waitedMs: 0 } };
    },
    // Like `query`, it ignores `match`: every row counts.
    count: async (stream, options = {}) => {
      const from = options.after ?? 0;
      const n = Math.max(0, (rows[stream] ?? []).length - from);
      return { captured: stream in rows, complete: true, count: n, cursor: from + n, scan: { bytesRead: 0, lines: n, parsed: n, oversized: 0, waitedMs: 0 } };
    },
    mark: async () => {
      throw new Error("unused");
    },
  };
}

const SURVEY = `
import { trailblaze } from "@trailblaze/scripting/surveys";
export const checkoutDone = trailblaze.survey({ description: "shop.checkout", finding: { analytics: { name: "checkout_done" } } });
export const afterRun = trailblaze.survey({ description: "shop.after", requiresFinishedSession: true, finding: { analytics: { name: "x" } } });
`;

describe("ctx.surveys", () => {
  test("loads the survey's bundle through the host and asserts it over the session's streams", async () => {
    const loads: unknown[] = [];
    const bundle = bundleSurvey(SURVEY);
    const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async (args: unknown) => (loads.push(args), bundle) };
    const surveys = createSessionSurveys(host, events({ analytics: [{ name: "checkout_started" }, { name: "checkout_done" }] })) as Record<string, (o?: object) => Promise<{ passed: boolean; survey: string; findings: Array<{ survey: string; summary: string }> }>>;

    const result = await surveys.checkoutDone!();
    expect(result).toMatchObject({ survey: "checkoutDone", passed: true });
    expect(result.findings.map((f) => [f.survey, f.summary])).toEqual([["checkoutDone", "shop.checkout"]]);
    // A second call reuses the evaluated bundle.
    await surveys.checkoutDone!();
    expect(loads).toEqual([{ name: "checkoutDone" }]);
  });

  test("a survey runs by its export name and hands back the data its finding carries", async () => {
    const source = `
import { trailblaze } from "@trailblaze/scripting/surveys";
export const journeyUploaded = trailblaze.survey({ description: "The checkout journey uploaded" }, (session) => {
  const done = session.analytics.find({ name: "checkout_done" });
  if (!done) return;
  return { summary: "checkout uploaded", evidence: [done], data: { variant: "express", tags: ["first-run"] } };
});
`;
    const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async () => bundleSurvey(source) };
    // A handler may read any stream, so it judges only once every one it could read is captured.
    const surveys = createSessionSurveys(host, events({ analytics: [{ name: "checkout_done" }], network: [] })) as Record<string, () => Promise<{ passed: boolean; survey: string; findings: Array<Record<string, unknown>> }>>;
    const result = await surveys.journeyUploaded!();
    expect(result).toMatchObject({ survey: "journeyUploaded", passed: true });
    expect(result.findings).toEqual([expect.objectContaining({ survey: "journeyUploaded", summary: "checkout uploaded", data: { variant: "express", tags: ["first-run"] } })]);
    expect(result.findings[0]).not.toHaveProperty("feature");
  });

  test("a failed assertion resolves with the reason rather than throwing", async () => {
    const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async () => bundleSurvey(SURVEY) };
    const surveys = createSessionSurveys(host, events({ analytics: [{ name: "checkout_started" }] })) as Record<string, () => Promise<{ passed: boolean; message: string }>>;
    const result = await surveys.checkoutDone!();
    expect(result.passed).toBe(false);
    expect(result.message).toContain('Missed analytics {"name":"checkout_done"}');
  });

  test("a survey that needs the finished session is refused by name", async () => {
    const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async () => bundleSurvey(SURVEY) };
    const surveys = createSessionSurveys(host, events({})) as Record<string, () => Promise<{ passed: boolean; message: string }>>;
    const result = await surveys.afterRun!();
    expect(result.passed).toBe(false);
    expect(result.message).toContain("requiresFinishedSession: true");
  });

  test("a load the host refuses rejects, and the next call asks again", async () => {
    let calls = 0;
    const host = {
      [HOST_FUNCTION_SURVEYS_LOAD]: async () => {
        if (++calls === 1) throw new Error("No survey named checkoutDone");
        return bundleSurvey(SURVEY);
      },
    };
    const surveys = createSessionSurveys(host, events({ analytics: [{ name: "checkout_done" }] })) as Record<string, () => Promise<{ passed: boolean }>>;
    await expect(surveys.checkoutDone!()).rejects.toThrow("No survey named checkoutDone");
    expect((await surveys.checkoutDone!()).passed).toBe(true);
  });

  test("a survey runs as this session: its id on findings, its platform against the survey's scope", async () => {
    const scoped = `
import { trailblaze } from "@trailblaze/scripting/surveys";
export const iosDone = trailblaze.survey({ description: "shop.ios", platforms: ["ios"], finding: { analytics: { name: "checkout_done" } } });
`;
    const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async () => bundleSurvey(scoped) };
    const rows = events({ analytics: [{ name: "checkout_done" }] });
    type Run = () => Promise<{ passed: boolean; message: string; findings: Array<{ session: string }> }>;
    const onIos = createSessionSurveys(host, rows, { sessionId: "s-ios", platform: "ios" }) as Record<string, Run>;
    const ios = await onIos.iosDone!();
    expect(ios.passed).toBe(true);
    expect(ios.findings.map((f) => f.session)).toEqual(["s-ios"]);
    const onAndroid = createSessionSurveys(host, rows, { sessionId: "s-android", platform: "android" }) as Record<string, Run>;
    const android = await onAndroid.iosDone!();
    expect(android.passed).toBe(false);
    expect(android.message).toContain("does not apply here");
  });

  test("a survey scoped to a target applies on that target's apps, from the targets the host sends", async () => {
    const scoped = `
import { trailblaze } from "@trailblaze/scripting/surveys";
export const shopDone = trailblaze.survey({ description: "shop.done", targets: ["shop"], finding: { analytics: { name: "checkout_done" } } });
`;
    const rows = events({ analytics: [{ name: "checkout_done" }] });
    const about = { platform: "android", target: "shop-lite", appId: "com.example.shop" };
    type Run = () => Promise<{ passed: boolean }>;
    const run = async (targets?: Record<string, Record<string, string[]>>) => {
      const host = { [HOST_FUNCTION_SURVEYS_LOAD]: async () => ({ ...bundleSurvey(scoped), ...(targets ? { targets } : {}) }) };
      return (createSessionSurveys(host, rows, about) as Record<string, Run>).shopDone!();
    };
    expect((await run()).passed).toBe(false);
    expect((await run({ shop: { android: ["com.example.shop"] } })).passed).toBe(true);
  });

  test("awaiting the namespace itself does not call a survey named then", async () => {
    const surveys = createSessionSurveys({}, events({}));
    expect(await surveys).toBe(surveys);
  });
});

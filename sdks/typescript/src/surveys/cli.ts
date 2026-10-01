#!/usr/bin/env bun
// Runs surveys over sessions from the command line.
//
//   bun src/surveys/cli.ts --target square                 # from anywhere inside a workspace
//   bun src/surveys/cli.ts --target square ./sessions-dir  # over a downloaded batch instead
//
// Run inside a workspace, it finds the workspace the way the trailblaze CLI does and loads every
// survey it carries: `<config>/surveys/` and each trailmap's `surveys/`. `--surveys` and
// `--trailmaps` replace that with explicit paths. Sessions default to ~/.trailblaze/logs and may be
// session directories, directories of sessions, artifact zips, or directories of zips. With no
// `--out`, the markdown report goes to stdout; with `--out`, JSON goes to the file and a one-line
// per-session summary goes to stdout.

import { parseArgs } from "node:util";
import { writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

import { discoverSessions, loadSurveys, type LoadedSurvey } from "./discover.js";
import { renderMarkdown, renderSummaryLines } from "./report.js";
import { runSurveys } from "./runner.js";
import { surveyFeatures } from "./survey.js";
import { loadTargetCatalog, sessionMatchesTarget, type TargetCatalog } from "./targets.js";
import type { SessionSummary } from "./types.js";
import { findWorkspaceConfigDir, loadWorkspace } from "./workspace.js";

const HELP = `Usage: bun cli.ts [--target <id>] [<sessions>...] [options]

Run inside a workspace, every survey it carries loads: <config>/surveys/ and each trailmap's
surveys/. Sessions default to ~/.trailblaze/logs.

Options:
      --target <id>         Only sessions of this target, and only the surveys that apply to it (repeatable)
  -s, --sessions <path>     Session dir, dir of sessions, session zip, or dir of zips (repeatable)
  -t, --surveys <path>      Instead of the workspace's surveys: a survey file, or directory walked
                            for *.survey.ts (repeatable)
  -o, --out <file>          Write the JSON report here (markdown otherwise goes to stdout)
      --format <fmt>        stdout format: markdown (default without --out), summary (default with --out), json, none
      --no-evidence         Omit evidence lines from the markdown report
      --extract-dir <dir>   Where zips are unpacked (default: a temp dir)
      --feature <id>        Only run surveys for this feature (repeatable)
      --tag <tag>           Only run surveys carrying this tag (repeatable)
      --platform <p>        Only sessions on this platform: android, ios, web, ... (repeatable)
      --device <classifier> Only sessions whose device carries this classifier: iphone, ipad, tablet, ... (repeatable)
      --app-id <id>         Only sessions of this app id (repeatable)
      --trailmaps <path>    Instead of the workspace's trailmaps: a trailmap.yaml or directory walked
                            for them. Each contributes its surveys/, and its app ids place sessions
                            that drove one of a target's apps even if their trail named no target
                            (repeatable)
      --outcome <o>         Only sessions with this outcome: PASSED, FAILED, ... (repeatable)
                            Session filters are ANDed; values within one flag are ORed.
  -q, --quiet               No progress on stderr
  -h, --help
`;

async function main(argv: string[]): Promise<number> {
  const { values, positionals } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: {
      surveys: { type: "string", short: "t", multiple: true },
      sessions: { type: "string", short: "s", multiple: true },
      out: { type: "string", short: "o" },
      format: { type: "string" },
      "no-evidence": { type: "boolean" },
      "extract-dir": { type: "string" },
      feature: { type: "string", multiple: true },
      tag: { type: "string", multiple: true },
      platform: { type: "string", multiple: true },
      device: { type: "string", multiple: true },
      "app-id": { type: "string", multiple: true },
      target: { type: "string", multiple: true },
      trailmaps: { type: "string", multiple: true },
      outcome: { type: "string", multiple: true },
      quiet: { type: "boolean", short: "q" },
      help: { type: "boolean", short: "h" },
    },
  });
  if (values.help) {
    process.stdout.write(HELP);
    return 0;
  }
  const surveyPaths = values.surveys ?? [];
  const trailmapPaths = values.trailmaps ?? [];
  const given = [...(values.sessions ?? []), ...positionals];
  const sessionPaths = given.length > 0 ? given : [join(homedir(), ".trailblaze", "logs")];
  const log = values.quiet ? undefined : (m: string) => process.stderr.write(m + "\n");

  let loaded: LoadedSurvey[];
  let catalog: TargetCatalog | undefined;
  let lookedIn = "the paths given";
  if (surveyPaths.length > 0 || trailmapPaths.length > 0) {
    loaded = await loadSurveys(surveyPaths, log, { trailmaps: trailmapPaths });
    catalog = trailmapPaths.length ? loadTargetCatalog(trailmapPaths, log) : undefined;
  } else {
    const configDir = findWorkspaceConfigDir();
    if (!configDir) {
      process.stderr.write("Not inside a Trailblaze workspace (no trailblaze-config/ or trails/config/ above here); pass --surveys or --trailmaps.\n");
      return 2;
    }
    log?.(`workspace: ${configDir}`);
    const workspace = await loadWorkspace(configDir, log);
    loaded = workspace.surveys;
    catalog = workspace.targets;
    lookedIn = `${join(configDir, "surveys")} or any trailmap's surveys/`;
  }
  let surveys = loaded.map((l) => l.definition);
  // A survey scoped to another target would only report every session as skipped.
  if (values.target?.length) surveys = surveys.filter((c) => !c.spec.targets || c.spec.targets.some((t) => values.target!.includes(t)));
  // A catalog survey's own `feature` is a family name; `--feature refunds` must find the survey that reports it.
  if (values.feature?.length) surveys = surveys.filter((c) => [c.spec.feature, ...surveyFeatures(c.spec)].some((f) => values.feature!.includes(f)));
  if (values.tag?.length) surveys = surveys.filter((c) => c.spec.tags?.some((t) => values.tag!.includes(t)));
  if (surveys.length === 0) {
    process.stderr.write(`No surveys found in ${lookedIn}${values.target?.length ? " for that target" : ""}.\n`);
    return 2;
  }
  log?.(`${surveys.length} survey(s) from ${new Set(loaded.map((l) => l.file)).size} file(s)`);

  const sessions = await discoverSessions(sessionPaths, { extractDir: values["extract-dir"], log });
  if (sessions.length === 0) {
    process.stderr.write("No sessions found.\n");
    return 2;
  }
  log?.(`${sessions.length} session(s)`);

  if (catalog) log?.(`${Object.keys(catalog).length} target(s) from trailmaps: ${Object.keys(catalog).join(", ")}`);

  const where = sessionFilter(catalog, {
    platform: values.platform,
    device: values.device,
    appId: values["app-id"],
    target: values.target,
    outcome: values.outcome,
  });

  const report = await runSurveys({
    surveys,
    sessions,
    where,
    targets: catalog,
    log,
    onSession: log ? ({ index, total, path }) => log(`[${index + 1}/${total}] ${path}`) : undefined,
  });

  if (values.out) writeFileSync(values.out, JSON.stringify(report, null, 2));
  const format = values.format ?? (values.out ? "summary" : "markdown");
  switch (format) {
    case "markdown":
      process.stdout.write(renderMarkdown(report, { evidence: !values["no-evidence"] }) + "\n");
      break;
    case "summary":
      process.stdout.write(renderSummaryLines(report).join("\n") + "\n");
      break;
    case "json":
      process.stdout.write(JSON.stringify(report, null, 2) + "\n");
      break;
    case "none":
      break;
    default:
      process.stderr.write(`Unknown --format ${format}\n`);
      return 2;
  }
  if (report.excluded.length > 0) log?.(`${report.excluded.length} session(s) excluded by filters`);
  if (values.out) log?.(`wrote ${values.out}`);
  const errorCount = report.sessions.reduce((n, s) => n + s.errors.length, 0);
  return errorCount > 0 ? 1 : 0;
}

/** Builds the session predicate from the CLI's filter flags; undefined when none were given. */
function sessionFilter(catalog: TargetCatalog | undefined, f: {
  platform?: string[];
  device?: string[];
  appId?: string[];
  target?: string[];
  outcome?: string[];
}): ((s: SessionSummary) => boolean) | undefined {
  const lower = (xs?: string[]) => xs?.map((x) => x.toLowerCase());
  const platform = lower(f.platform);
  const device = lower(f.device);
  const outcome = f.outcome?.map((o) => o.toUpperCase());
  if (!platform?.length && !device?.length && !f.appId?.length && !f.target?.length && !outcome?.length) return undefined;
  return (s) =>
    (!platform?.length || (s.platform !== undefined && platform.includes(s.platform.toLowerCase()))) &&
    (!device?.length || s.deviceClassifiers.some((c) => device.includes(c.toLowerCase()))) &&
    (!f.appId?.length || (s.appId !== undefined && f.appId.includes(s.appId))) &&
    (!f.target?.length || f.target.some((t) => sessionMatchesTarget(s, t, catalog))) &&
    (!outcome?.length || outcome.includes(s.outcome));
}

process.exitCode = await main(process.argv.slice(2));

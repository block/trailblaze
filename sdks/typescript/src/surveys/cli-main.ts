// The surveys CLI as a function, so the `trailblaze survey` command can run the same code from the
// SDK bundle the framework ships when there is no SDK source tree. `cli.ts` is its command line.

import { parseArgs } from "node:util";
import { realpathSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";

import { discoverSessions, loadSurveyModules, type LoadedEventStream, type LoadedSurvey } from "./discover.js";
import { renderMarkdown, renderSummaryLines } from "./report.js";
import { runSurveys } from "./runner.js";
import { type StreamWriteResult, writeDerivedStreams } from "./stream-write.js";
import { STORED_SURVEYS_FILE, writeStoredSurveys } from "./stored.js";
import { loadTargetCatalog, sessionMatchesTarget, type TargetCatalog } from "./targets.js";
import type { SessionSummary } from "./types.js";
import { findWorkspaceConfigDir, loadWorkspace } from "./workspace.js";

const HELP = `Usage: bun cli.ts [--target <id>] [<sessions>...] [options]

Run inside a workspace, every survey it carries loads: <config>/surveys/ and each trailmap's
surveys/. Sessions default to ~/.trailblaze/logs. Every survey's find phase runs over every
session, then the label phases run over the batch. A survey that a selected survey lists in
\`dependsOn\` runs too, reusing a session's stored findings when they are current.

Options:
      --target <id>         Only sessions of this target, and only the surveys that apply to it (repeatable)
  -s, --sessions <path>     Session dir, dir of sessions, session zip, or dir of zips (repeatable)
  -t, --surveys <path>      Instead of the workspace's surveys: a survey file, or directory walked
                            for *.survey.ts (repeatable)
  -o, --out <file>          Write the JSON report here (markdown otherwise goes to stdout)
      --format <fmt>        stdout format: markdown (default without --out), summary (default with --out), json, none
      --no-evidence         Omit evidence lines from the markdown report
      --extract-dir <dir>   Where zips are unpacked (default: a temp dir)
      --write               Store each session's findings and run-level labels in its surveys.json
      --streams             Run no surveys: write every derived event stream (eventStream()) the surveys' files
                            declare into each session's events/<name>.ndjson, for the report to show
      --allow-stream-errors A stream that fails to derive is reported, but only surveys' own errors
                            make the exit nonzero
      --survey <name>       Only run the survey exported under this name (repeatable)
      --warnings            Only run warning surveys (\`warning: true\`), whose findings mark a run that passed but needs a look
      --allow-none          Finding no survey to run is not an error: exit 0 and say nothing
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

/** The surveys CLI: parses `argv` (no interpreter or script name), runs, and returns the exit code. */
export async function runSurveyCli(argv: string[]): Promise<number> {
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
      write: { type: "boolean" },
      streams: { type: "boolean" },
      "allow-stream-errors": { type: "boolean" },
      survey: { type: "string", multiple: true },
      warnings: { type: "boolean" },
      "allow-none": { type: "boolean" },
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
  let streams: LoadedEventStream[];
  let catalog: TargetCatalog | undefined;
  let lookedIn = "the paths given";
  try {
    if (surveyPaths.length > 0 || trailmapPaths.length > 0) {
      ({ surveys: loaded, streams } = await loadSurveyModules(surveyPaths, log, { trailmaps: trailmapPaths }));
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
      streams = workspace.streams;
      catalog = workspace.targets;
      lookedIn = `${join(configDir, "surveys")} or any trailmap's surveys/`;
    }
  } catch (e) {
    if (!values.streams) throw e;
    // With no current definitions, every stream written before is one nobody vouches for, and the
    // report would show its rows as current. Each can be rebuilt, so all go.
    const why = e instanceof Error ? e.message : String(e);
    // Which sessions a target names takes the trailmaps, which may be what failed to load: none is touched then.
    if (values.target?.length) {
      process.stderr.write(`Could not load the surveys' derived streams, and without them which sessions --target names is unknown, so none is changed: ${why}\n`);
      return 1;
    }
    process.stderr.write(`Could not load the surveys' derived streams, so every one written before is removed: ${why}\n`);
    const only = sessionFilter(undefined, {
      platform: values.platform,
      device: values.device,
      appId: values["app-id"],
      outcome: values.outcome,
    });
    await writeStreams([], sessionPaths, only, values["extract-dir"], log);
    return 1;
  }
  const where = sessionFilter(catalog, {
    platform: values.platform,
    device: values.device,
    appId: values["app-id"],
    target: values.target,
    outcome: values.outcome,
  });
  if (values.streams) return writeStreams(streams, sessionPaths, where, values["extract-dir"], log);
  const available = loaded.map((l) => l.definition);
  let surveys = available;
  const unknown = (values.survey ?? []).filter((name) => !available.some((c) => c.id === name));
  if (unknown.length > 0) {
    process.stderr.write(`No survey named ${unknown.join(", ")} in ${lookedIn}.\n`);
    return 2;
  }
  // A survey scoped to another target would only report every session as skipped.
  if (values.target?.length) surveys = surveys.filter((c) => !c.spec.targets || c.spec.targets.some((t) => values.target!.includes(t)));
  if (values.survey?.length) surveys = surveys.filter((c) => c.id !== undefined && values.survey!.includes(c.id));
  if (values.warnings) surveys = surveys.filter((c) => c.spec.warning === true);
  if (surveys.length === 0) {
    // `trailblaze report` runs the warning surveys of every workspace, most of which have none.
    if (values["allow-none"]) return 0;
    const kind = values.warnings ? "warning surveys" : "surveys";
    // A workspace of streams alone is valid: `report --surveys` has written them, and there is nothing more to run.
    if (!values.warnings && !values.survey?.length && !values.target?.length && streams.length > 0) {
      process.stderr.write(`No surveys in ${lookedIn}, only derived streams; nothing to run.\n`);
      return 0;
    }
    process.stderr.write(`No ${kind} found in ${lookedIn}${values.target?.length ? " for that target" : ""}.\n`);
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

  const report = await runSurveys({
    surveys,
    available,
    sessions,
    where,
    targets: catalog,
    log,
    streams: streams.map((s) => s.definition),
    onSession: log ? ({ index, total, path }) => log(`[${index + 1}/${total}] ${path}`) : undefined,
  });

  // Checked before anything is written: the report would replace a session's stored results, and
  // storing would then discard the report as a file it cannot read.
  // Compared where they land, links followed, so a session reached through a symlink still clashes.
  const out = values.out && canonical(values.out);
  // Every session given, those left out and those that would not load too: the report would destroy
  // their stored results as well.
  const surveyed = [
    ...report.sessions.map((s) => ({ id: s.session.id, path: s.session.path })),
    ...report.excluded,
    ...report.unreadable.map((u) => ({ id: u.path, path: u.path })),
  ];
  const clash = out && surveyed.find((s) => canonical(join(s.path, STORED_SURVEYS_FILE)) === out);
  if (clash) {
    process.stderr.write(`--out ${values.out} is where session ${clash.id} stores its survey results; write the report somewhere else.\n`);
    return 2;
  }
  if (values.out) writeFileSync(values.out, JSON.stringify(report, null, 2));
  // A session that cannot store its results costs only that: the report still prints, and the
  // failure is told after it.
  const unstored: Array<{ path: string; message: string }> = [];
  if (values.write) {
    const written = writeStoredSurveys(report, unstored);
    log?.(`stored findings and labels in surveys.json in ${written.length} session(s)`);
    const running = report.sessions.filter((s) => s.session.endedAtMs === undefined).length;
    if (running > 0) log?.(`${running} session(s) still running, so not stored`);
  }
  // `trailblaze report` has already written the streams and recorded on each session why one failed.
  const counted = (e: { survey: string }) => !(values["allow-stream-errors"] && e.survey.startsWith("stream:"));
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
      // Nothing else shows a survey's failure, so it goes to stderr, `--quiet` or not.
      for (const s of report.sessions) {
        for (const e of s.errors.filter(counted)) process.stderr.write(`Survey ${e.survey} failed on ${s.session.path}: ${e.message}\n`);
      }
      for (const e of report.labelErrors) process.stderr.write(`Labels of survey ${e.survey} failed: ${e.message}\n`);
      // A session that would not load was not surveyed at all. Conflicts are told below either way.
      for (const u of report.unreadable) {
        if (u.conflictsWith === undefined) process.stderr.write(`Could not survey ${u.path}: ${u.message}\n`);
      }
      break;
    default:
      process.stderr.write(`Unknown --format ${format}\n`);
      return 2;
  }
  const filtered = report.excluded.filter((e) => e.sameAs === undefined).length;
  if (filtered > 0) log?.(`${filtered} session(s) excluded by filters`);
  for (const e of report.excluded) {
    if (e.sameAs !== undefined) log?.(`${e.path} is the same session as ${e.sameAs} (${e.id}), so it was surveyed once`);
  }
  if (values.out) log?.(`wrote ${values.out}`);
  for (const u of unstored) process.stderr.write(`Could not store survey results in ${u.path}: ${u.message}\n`);
  // Two recordings of one session: the batch surveyed only one of them, so it is not complete.
  const conflicts = report.unreadable.filter((u) => u.conflictsWith !== undefined);
  for (const u of conflicts) process.stderr.write(`Left out ${u.path}: it ${u.message}\n`);
  const errorCount =
    report.sessions.reduce((n, s) => n + s.errors.filter(counted).length, 0) + report.labelErrors.length + unstored.length + conflicts.length;
  return errorCount > 0 ? 1 : 0;
}

/**
 * `--streams`: writes every stream into every session the filter flags accept, and removes the ones
 * it wrote before that no file declares now. Exits 1 when any failed.
 */
async function writeStreams(
  streams: LoadedEventStream[],
  sessionPaths: string[],
  where: ((s: SessionSummary) => boolean) | undefined,
  extractDir: string | undefined,
  log: ((m: string) => void) | undefined,
): Promise<number> {
  log?.(`${streams.length} derived stream(s)${streams.length ? `: ${streams.map((s) => s.definition.name).join(", ")}` : ""}`);
  const dirs = await discoverSessions(sessionPaths, { extractDir, log });
  if (dirs.length === 0) {
    process.stderr.write("No sessions found.\n");
    return 2;
  }
  const definitions = streams.map((s) => s.definition);
  let failures = 0;
  for (const dir of dirs) {
    let r: StreamWriteResult;
    try {
      r = writeDerivedStreams(dir, definitions, { where });
    } catch (e) {
      process.stderr.write(`Could not read ${dir}: ${e instanceof Error ? e.message : String(e)}\n`);
      failures += 1;
      continue;
    }
    const id = r.session?.id ?? dir;
    if (r.unfinished) log?.(`${id}: has not ended, so no derived stream is written into it`);
    if (r.written.length) log?.(`${id}: wrote ${r.written.map((n) => `events/${n}.ndjson`).join(", ")}`);
    if (r.removed.length) log?.(`${id}: removed ${r.removed.map((n) => `events/${n}.ndjson`).join(", ")}, which no stream declares`);
    for (const e of r.errors) {
      process.stderr.write(`${id}: stream ${e.stream} failed: ${e.message}\n`);
      failures += 1;
    }
  }
  return failures > 0 ? 1 : 0;
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

/** Where [path] lands once links are followed: the file itself when it exists, else its directory's. */
function canonical(path: string): string {
  try {
    return realpathSync(path);
  } catch {
    try {
      return join(realpathSync(dirname(path)), basename(path));
    } catch {
      return resolve(path);
    }
  }
}

// Runs a set of surveys over a set of sessions and assembles the report.

import { hasFindPhase, type SurveyDefinition } from "./survey.js";
import { runSurvey, type SurveyError } from "./run-one.js";
export { compactRecord, runSurvey, type SurveyError } from "./run-one.js";
import { planSurveys, runLabelPhases } from "./labels.js";
import { SurveySession } from "./session.js";
import { currentEntry, readStoredSurveys, sessionInputs, type StoredSurveys } from "./stored.js";
import type { Finding, LabelRecord, SessionSummary } from "./types.js";
import { surveyApplies, type TargetCatalog } from "./scope.js";

export interface SessionResult {
  session: SessionSummary;
  findings: Finding[];
  /** Surveys that threw or reported `ctx.error`. The run continues; the failure is reported, not hidden. */
  errors: SurveyError[];
  /** Surveys skipped because the session's platform, app, or target was outside their spec. */
  skipped: string[];
  /** The run's labels: every label whose findings include one from this session. */
  labels: Array<Pick<LabelRecord, "survey" | "type" | "name">>;
  /**
   * The session's files as it loaded (see `sessionInputs`), which its stored results are kept
   * against. Null when nothing vouches for what the session read: it was handed in already loaded,
   * or it holds a link. Such a session neither reuses stored results nor has its own stored.
   */
  inputs?: string | null;
}

export interface SurveyReport {
  generatedAt: string;
  /** One row per survey, by name. */
  surveys: SurveySummary[];
  sessions: SessionResult[];
  /**
   * Sessions the run could not load, with why. `conflictsWith` names the input that holds the
   * session this one claims to be but records differently: the batch is missing one of them.
   */
  unreadable: Array<{ path: string; message: string; conflictsWith?: string }>;
  /**
   * Sessions that loaded but were left out: by `RunOptions.where`, or, with `sameAs`, because an
   * earlier path holds the same session (a directory and its zip), which is surveyed once.
   */
  excluded: Array<{ id: string; path: string; sameAs?: string }>;
  /** What the label phases concluded across the batch, each label with the findings behind it. */
  labels: LabelRecord[];
  /** Label phases that failed. The run continues; the failure is reported, not hidden. */
  labelErrors: SurveyError[];
}

export interface SurveySummary {
  /** The survey's name: its export name. */
  id: string;
  description?: string;
  sessionsMatched: number;
  sessionIds: string[];
  findings: number;
  /** A hash of the file that defines the survey and its local imports; findings and labels carry it too. */
  version?: string;
  /** Set when the run included this survey only because these selected surveys list it in `dependsOn`. */
  neededBy?: string[];
  /** Sessions whose stored findings were reused rather than surveyed again. Only a survey in `neededBy` reuses. */
  reused?: number;
  labels: number;
}

export interface RunOptions {
  surveys: ReadonlyArray<SurveyDefinition>;
  /** Session directories, or already-loaded sessions. */
  sessions: ReadonlyArray<string | SurveySession>;
  /**
   * Keep only sessions this returns true for, judged on the loaded summary (platform, device
   * classifiers, app id, target, outcome, ...). Excluded sessions are listed in the report.
   */
  where?: (session: SessionSummary) => boolean;
  /**
   * Which app ids each target owns, so a survey scoped with `targets` (and `sessionMatchesTarget`)
   * also catches sessions that drove one of the target's apps without naming the target.
   */
  targets?: TargetCatalog;
  /** Called before each session is processed. */
  onSession?: (info: { index: number; total: number; path: string }) => void;
  /** Receives survey `ctx.log` output and loader warnings. */
  log?: (message: string) => void;
  /**
   * Every loaded survey, so a survey that `dependsOn` names but `surveys` leaves out runs too, under its
   * own name. A survey that came in this way reuses a session's stored findings when they are current.
   */
  available?: ReadonlyArray<SurveyDefinition>;
  /** A session's stored findings. Defaults to its `surveys.json`. */
  stored?: (session: SurveySession) => StoredSurveys | undefined;
}

/**
 * Discovered surveys carry their export name as id. Ones built in code with no id are `survey`,
 * numbered only when several share one name.
 */
function surveyIds(defs: ReadonlyArray<SurveyDefinition>): string[] {
  const preferred = defs.map((d) => d.id ?? "survey");
  const seen = new Map<string, number>();
  for (const id of preferred) seen.set(id, (seen.get(id) ?? 0) + 1);
  const used = new Map<string, number>();
  return preferred.map((id) => {
    if ((seen.get(id) ?? 0) < 2) return id;
    const n = (used.get(id) ?? 0) + 1;
    used.set(id, n);
    return `${id}#${n}`;
  });
}

/**
 * Runs every survey's find phase over every session, then the label phases over the batch, each
 * after the surveys its `dependsOn` lists. Never throws for a survey's failure; throws before reading
 * any session when `dependsOn` names a survey that was not loaded or the label phases form a cycle.
 */
export async function runSurveys(options: RunOptions): Promise<SurveyReport> {
  const ids = surveyIds(options.surveys);
  const plan = planSurveys(options.surveys.map((def, i) => ({ def, id: ids[i]! })), options.available);
  const stored = options.stored ?? ((session: SurveySession) => readStoredSurveys(session.dir));
  const reused = new Map<string, number>();
  const surveyed = new Map<string, number>();
  const report: SurveyReport = {
    generatedAt: new Date().toISOString(),
    surveys: plan.map((p) => ({
      id: p.id,
      ...(p.def.spec.description !== undefined ? { description: p.def.spec.description } : {}),
      sessionsMatched: 0,
      sessionIds: [],
      findings: 0,
      ...(p.def.version !== undefined ? { version: p.def.version } : {}),
      ...(p.selected ? {} : { neededBy: p.neededBy, reused: 0 }),
      labels: 0,
    })),
    sessions: [],
    unreadable: [],
    excluded: [],
    labels: [],
    labelErrors: [],
  };

  const total = options.sessions.length;
  const pathOf = new Map<string, { path: string; recording: string }>();
  for (let index = 0; index < total; index++) {
    const input = options.sessions[index]!;
    const path = typeof input === "string" ? input : input.dir;
    options.onSession?.({ index, total, path });
    let session: SurveySession;
    // Taken before loading and again after: a file that lands in between leaves the session with
    // nothing vouching for what it read.
    let inputs = typeof input === "string" ? (sessionInputs(path) ?? null) : null;
    try {
      session = typeof input === "string" ? SurveySession.load(input) : input;
    } catch (e) {
      report.unreadable.push({ path, message: e instanceof Error ? e.message : String(e) });
      continue;
    }
    if (inputs !== null && sessionInputs(path) !== inputs) inputs = null;
    if (options.where && !options.where(session.summary)) {
      report.excluded.push({ id: session.summary.id, path });
      continue;
    }
    // Twice in one batch, a session would be counted twice and its findings' positions would run on
    // from its first copy's, so a label citing them would name findings its stored results lack.
    // Checked after the filter, so a copy the filter leaves out never displaces one it keeps. The id
    // is the recording's own claim, so only a copy that matches is the same session: one that
    // differs is reported, not taken for it.
    const first = pathOf.get(session.summary.id);
    if (first !== undefined) {
      if (recordingOf(session) === first.recording) {
        report.excluded.push({ id: session.summary.id, path, sameAs: first.path });
      } else {
        report.unreadable.push({ path, message: `claims session ${session.summary.id}, as ${first.path} does, but records it differently`, conflictsWith: first.path });
      }
      continue;
    }
    pathOf.set(session.summary.id, { path, recording: recordingOf(session) });
    const result: SessionResult = { session: session.summary, findings: [], errors: [], skipped: [], labels: [], inputs };
    // Read once, and only when a survey could reuse it.
    let storedHere: StoredSurveys | undefined | null = null;
    for (const p of plan) {
      const { def, id } = p;
      if (!hasFindPhase(def)) continue;
      if (!surveyApplies(def.spec, session.summary, options.targets)) {
        result.skipped.push(id);
        continue;
      }
      if (!p.selected) {
        if (storedHere === null) storedHere = stored(session);
        const entry = currentEntry(storedHere, session.id, id, def.version, inputs ?? undefined);
        if (entry) {
          result.findings.push(...entry.findings.map((f) => ({ ...f, data: f.data ?? {}, survey: id, session: session.id })));
          reused.set(id, (reused.get(id) ?? 0) + 1);
          continue;
        }
        surveyed.set(id, (surveyed.get(id) ?? 0) + 1);
      }
      try {
        result.findings.push(...(await runSurvey(def, session, id, options.log, (e) => result.errors.push(e))));
      } catch (e) {
        const message = e instanceof Error ? e.message : String(e);
        result.errors.push({ survey: id, message });
      }
    }
    report.sessions.push(result);
  }

  const labelled = await runLabelPhases(
    plan,
    report.sessions.flatMap((s) => s.findings),
    report.sessions.map((s) => s.session),
    options.log,
    (def, run) => surveyApplies(def.spec, run, options.targets),
  );
  report.labels = labelled.labels;
  report.labelErrors = labelled.errors;
  for (const s of report.sessions) {
    s.labels = report.labels
      .filter((l) => l.runs.includes(s.session.id))
      .map((l) => (l.type === undefined ? { survey: l.survey, name: l.name } : { survey: l.survey, type: l.type, name: l.name }));
  }

  countBySurvey(report);
  for (const row of report.surveys) {
    if (row.neededBy === undefined) continue;
    row.reused = reused.get(row.id) ?? 0;
    options.log?.(
      `${row.id}: run because ${row.neededBy.join(", ")} list${row.neededBy.length === 1 ? "s" : ""} it in dependsOn; ` +
        `reused stored findings in ${row.reused} session(s), surveyed ${surveyed.get(row.id) ?? 0}`,
    );
  }
  return report;
}

/** What a session recorded, wherever it was read from: its summary and how many records of each kind. */
function recordingOf(session: SurveySession): string {
  const { path: _path, ci: _ci, ...summary } = session.summary;
  const counts = [session.trailblazeLogs, session.network, session.events, session.screenText, session.deviceLog, session.trace].map((r) => r.all().length);
  return JSON.stringify({ summary, counts });
}

/** Fills each survey's row with how many sessions it found something in, and how many findings. */
function countBySurvey(report: SurveyReport): void {
  const byId = new Map(report.surveys.map((s) => [s.id, s]));
  for (const l of report.labels) {
    const row = byId.get(l.survey);
    if (row) row.labels += 1;
  }
  for (const s of report.sessions) {
    const seen = new Set<string>();
    for (const f of s.findings) {
      const row = byId.get(f.survey);
      if (!row) continue;
      row.findings += 1;
      if (!seen.has(f.survey)) {
        seen.add(f.survey);
        row.sessionsMatched += 1;
        row.sessionIds.push(s.session.id);
      }
    }
  }
}

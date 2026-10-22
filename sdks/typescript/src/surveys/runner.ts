// Runs a set of surveys over a set of sessions and assembles the report.

import type { SurveyDefinition } from "./survey.js";
import { runSurvey, type SurveyError } from "./run-one.js";
export { compactRecord, runSurvey, type SurveyError } from "./run-one.js";
import { SurveySession } from "./session.js";
import type { Finding, SessionSummary } from "./types.js";
import { surveyApplies, type TargetCatalog } from "./scope.js";

export interface SessionResult {
  session: SessionSummary;
  findings: Finding[];
  /** Surveys that threw or reported `ctx.error`. The run continues; the failure is reported, not hidden. */
  errors: SurveyError[];
  /** Surveys skipped because the session's platform, app, or target was outside their spec. */
  skipped: string[];
}

export interface SurveyReport {
  generatedAt: string;
  /** One row per survey, by name. */
  surveys: SurveySummary[];
  sessions: SessionResult[];
  /** Sessions the run could not load, with why. */
  unreadable: Array<{ path: string; message: string }>;
  /** Sessions that loaded but were left out by `RunOptions.where`. */
  excluded: Array<{ id: string; path: string }>;
}

export interface SurveySummary {
  /** The survey's name: its export name. */
  id: string;
  description?: string;
  tags?: ReadonlyArray<string>;
  sessionsMatched: number;
  sessionIds: string[];
  findings: number;
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

/** Runs every survey over every session. Never throws for a survey's failure. */
export async function runSurveys(options: RunOptions): Promise<SurveyReport> {
  const ids = surveyIds(options.surveys);
  const report: SurveyReport = {
    generatedAt: new Date().toISOString(),
    surveys: options.surveys.map((c, i) => ({
      id: ids[i]!,
      ...(c.spec.description !== undefined ? { description: c.spec.description } : {}),
      ...(c.spec.tags !== undefined ? { tags: c.spec.tags } : {}),
      sessionsMatched: 0,
      sessionIds: [],
      findings: 0,
    })),
    sessions: [],
    unreadable: [],
    excluded: [],
  };

  const total = options.sessions.length;
  for (let index = 0; index < total; index++) {
    const input = options.sessions[index]!;
    const path = typeof input === "string" ? input : input.dir;
    options.onSession?.({ index, total, path });
    let session: SurveySession;
    try {
      session = typeof input === "string" ? SurveySession.load(input) : input;
    } catch (e) {
      report.unreadable.push({ path, message: e instanceof Error ? e.message : String(e) });
      continue;
    }
    if (options.where && !options.where(session.summary)) {
      report.excluded.push({ id: session.summary.id, path });
      continue;
    }
    const result: SessionResult = { session: session.summary, findings: [], errors: [], skipped: [] };
    for (let i = 0; i < options.surveys.length; i++) {
      const def = options.surveys[i]!;
      const id = ids[i]!;
      if (!surveyApplies(def.spec, session.summary, options.targets)) {
        result.skipped.push(id);
        continue;
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

  countBySurvey(report);
  return report;
}

/** Fills each survey's row with how many sessions it found something in, and how many findings. */
function countBySurvey(report: SurveyReport): void {
  const byId = new Map(report.surveys.map((s) => [s.id, s]));
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

// Runs a set of surveys over a set of sessions and assembles the report.

import { surveyFeatures, validateFinding, type SurveyContext, type SurveyDefinition, type SurveyResult } from "./survey.js";
import { SurveySession } from "./session.js";
import type { SessionRecord, Finding, FindingInput, SessionSummary } from "./types.js";
import { surveyApplies, type TargetCatalog } from "./scope.js";

export interface SessionResult {
  session: SessionSummary;
  findings: Finding[];
  /** Surveys that threw. The run continues; the failure is reported, not hidden. */
  errors: Array<{ survey: string; feature: string; message: string }>;
  /** Surveys skipped because the session's platform, app, or target was outside their spec. */
  skipped: string[];
}

export interface FeatureSummary {
  feature: string;
  /** `feature` unless the findings placed the session on another axis (`job`, `gap`, ...). */
  kind: string;
  description?: string;
  surveys: string[];
  sessionsMatched: number;
  sessionIds: string[];
  findings: number;
}

export interface SurveyReport {
  generatedAt: string;
  surveys: Array<{
    id: string;
    feature: string;
    /** Every feature id the survey can report; `[feature]` unless it declares `footprints`. */
    features: string[];
    kind: string;
    /** A footprint's own `kind`, keyed by feature, where it differs from the survey's. */
    kinds?: Record<string, string>;
    description?: string;
    tags?: ReadonlyArray<string>;
  }>;
  sessions: SessionResult[];
  features: FeatureSummary[];
  /** Sessions the run could not load, with why. */
  unreadable: Array<{ path: string; message: string }>;
  /** Sessions that loaded but were left out by `RunOptions.where`. */
  excluded: Array<{ id: string; path: string }>;
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

/** Strips a record down to the fields a report carries. Bodies, payloads, and hierarchies stay behind. */
export function compactRecord(e: SessionRecord): SessionRecord {
  const out: SessionRecord = { kind: e.kind, summary: e.summary, file: e.file };
  if (e.line !== undefined) out.line = e.line;
  if (e.timeMs !== undefined) out.timeMs = e.timeMs;
  if (e.detail !== undefined) out.detail = e.detail;
  return out;
}

/**
 * Discovered surveys carry their export name as id. Ones built in code fall back to their
 * feature, numbered only when several unnamed surveys share one feature.
 */
/** `{ kinds }` for a footprints survey whose footprints override the survey's kind; nothing otherwise. */
function footprintKinds(spec: SurveyDefinition["spec"]): { kinds?: Record<string, string> } {
  const kinds: Record<string, string> = {};
  for (const [feature, footprint] of Object.entries(spec.footprints ?? {})) if (footprint.kind) kinds[feature] = footprint.kind;
  return Object.keys(kinds).length > 0 ? { kinds } : {};
}

function surveyIds(defs: ReadonlyArray<SurveyDefinition>): string[] {
  const preferred = defs.map((d) => d.id ?? d.spec.feature);
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

function normalizeResult(result: SurveyResult, extra: FindingInput[]): FindingInput[] {
  const out: FindingInput[] = [...extra];
  if (result === undefined || result === null || result === false) return out;
  if (Array.isArray(result)) out.push(...(result as ReadonlyArray<FindingInput>));
  else out.push(result as FindingInput);
  return out;
}

/** Runs one survey over one loaded session. */
export async function runSurvey(def: SurveyDefinition, session: SurveySession, id: string, log?: (m: string) => void): Promise<Finding[]> {
  const found: FindingInput[] = [];
  const ctx: SurveyContext = {
    found(summary, evidence, extra) {
      found.push({ summary, evidence: [...evidence], ...extra });
    },
    log(message) {
      log?.(`[${id}] ${message}`);
    },
  };
  const result = await def.handler(session, ctx);
  return normalizeResult(result, found).map((f) => {
    validateFinding(f, id);
    const finding: Finding = {
      feature: f.feature ?? def.spec.feature,
      kind: f.kind ?? def.spec.kind ?? "feature",
      survey: id,
      session: session.id,
      summary: f.summary,
      evidence: f.evidence.map(compactRecord),
    };
    if (f.confidence) finding.confidence = f.confidence;
    if (f.data) finding.data = f.data;
    return finding;
  });
}

/** Runs every survey over every session. Never throws for a survey's failure. */
export async function runSurveys(options: RunOptions): Promise<SurveyReport> {
  const ids = surveyIds(options.surveys);
  const report: SurveyReport = {
    generatedAt: new Date().toISOString(),
    surveys: options.surveys.map((c, i) => ({
      id: ids[i]!,
      feature: c.spec.feature,
      features: surveyFeatures(c.spec),
      kind: c.spec.kind ?? "feature",
      ...footprintKinds(c.spec),
      description: c.spec.description,
      tags: c.spec.tags,
    })),
    sessions: [],
    features: [],
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
        result.findings.push(...(await runSurvey(def, session, id, options.log)));
      } catch (e) {
        result.errors.push({ survey: id, feature: def.spec.feature, message: e instanceof Error ? e.message : String(e) });
      }
    }
    report.sessions.push(result);
  }

  report.features = summarizeFeatures(report);
  return report;
}

/**
 * One row per feature id any survey declared or reported. Declared features appear even at zero
 * matches, so a report can say "never exercised"; features a handler reported without declaring
 * are added as they appear.
 */
function summarizeFeatures(report: SurveyReport): FeatureSummary[] {
  const byFeature = new Map<string, FeatureSummary>();
  const entryFor = (feature: string, kind: string, description?: string): FeatureSummary => {
    let entry = byFeature.get(feature);
    if (!entry) {
      entry = { feature, kind, description, surveys: [], sessionsMatched: 0, sessionIds: [], findings: 0 };
      byFeature.set(feature, entry);
    }
    entry.description ??= description;
    return entry;
  };
  for (const c of report.surveys) {
    // A many-feature survey's own `feature` is a family name, not a row of the report.
    const own = c.features.length === 1 && c.features[0] === c.feature;
    for (const feature of c.features) {
      // A zero-finding row still needs its footprint's kind, or a never-seen gap reads as a feature.
      const entry = entryFor(feature, c.kinds?.[feature] ?? c.kind, own ? c.description : undefined);
      if (!entry.surveys.includes(c.id)) entry.surveys.push(c.id);
    }
  }
  for (const s of report.sessions) {
    const seen = new Set<string>();
    for (const f of s.findings) {
      const entry = entryFor(f.feature, f.kind);
      if (!entry.surveys.includes(f.survey)) entry.surveys.push(f.survey);
      // A declared row takes the survey's kind until a finding says otherwise.
      if (entry.findings === 0) entry.kind = f.kind;
      entry.findings += 1;
      if (!seen.has(f.feature)) {
        seen.add(f.feature);
        entry.sessionsMatched += 1;
        entry.sessionIds.push(s.session.id);
      }
    }
  }
  return [...byFeature.values()].sort((a, b) => b.sessionsMatched - a.sessionsMatched || a.feature.localeCompare(b.feature));
}

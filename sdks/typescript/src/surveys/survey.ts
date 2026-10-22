// The authoring surface: `survey(spec, handler)`, or `survey(spec)` with a declarative `finding`.
//
// Mirrors `trailblaze.tool`: an author exports the return value of one call per check, the runner
// discovers every such export in the files it is given, and the export name becomes the survey's
// id. Nothing is registered globally; a survey is a plain, inspectable value.

import type { AnalyticsQuery, DeviceLogQuery, EventQuery, NetworkQuery, ObjectiveQuery, ScreenTextQuery, ToolQuery } from "./match.js";
import type { SurveySession } from "./session.js";
import type { FindingInput, SessionRecord, SurveySpec } from "./types.js";

const BRAND: unique symbol = Symbol.for("trailblaze.survey");

/** Passed to a handler alongside the session. `found` is the imperative alternative to returning. */
export interface SurveyContext<D extends object = Record<string, unknown>> {
  /** Record one finding. Equivalent to returning it. */
  found(summary: string, evidence: ReadonlyArray<SessionRecord>, data?: D): void;
  /** Diagnostic output; the runner forwards it to its own log, never into the report. */
  log(message: string): void;
  /**
   * Report a failure as the session's error without throwing, so the findings the handler still
   * returns are kept: for a survey made of independent checks, one of which failed. Optional only
   * so a context built by hand in a test needs no stub; the runner always provides it.
   */
  error?(message: string): void;
}

export type SurveyResult<D extends object = Record<string, unknown>> = FindingInput<D> | ReadonlyArray<FindingInput<D>> | undefined | null | false | void;

export type SurveyHandler<D extends object = Record<string, unknown>> = (session: SurveySession, ctx: SurveyContext<D>) => SurveyResult<D> | Promise<SurveyResult<D>>;

/** What `survey()` returns. Export it; the runner does the rest. */
export interface SurveyDefinition {
  readonly [BRAND]: true;
  readonly spec: SurveySpec;
  readonly handler: SurveyHandler;
  /** Set by discovery from the export name. */
  readonly id?: string;
}

/** How an error names a survey that has no id yet: its description, else nothing. */
function named(spec: SurveySpec | undefined): string {
  return spec?.description ? ` (${spec.description})` : "";
}

function define(spec: SurveySpec, handler: SurveyHandler, id?: string): SurveyDefinition {
  if (!spec || typeof spec !== "object") {
    throw new Error("survey(): the first argument must be a spec object");
  }
  if (typeof handler !== "function") {
    throw new Error(`survey(): second argument must be a handler function${named(spec)}`);
  }
  return Object.freeze({ [BRAND]: true as const, spec, handler, id });
}

/** True when `value` came out of `survey()`. */
export function isSurvey(value: unknown): value is SurveyDefinition {
  return typeof value === "object" && value !== null && (value as { [BRAND]?: unknown })[BRAND] === true;
}

/** Returns a copy of `def` carrying `id`. Discovery uses this to attach the export name. */
export function withId(def: SurveyDefinition, id: string): SurveyDefinition {
  return def.id === id ? def : define(def.spec, def.handler, id);
}

/**
 * The finding a survey reports, declaratively: its `summary` and `data`, and the queries that prove
 * it. Every listed query must match at least one record, and the finding's evidence is the first
 * match of each (or all matches with `evidence: "all"`). Use it when the check is "these things were
 * observed" and nothing needs computing; write a handler when something does.
 */
export interface FindingSpec {
  /** One line for the finding. Defaults to the survey's description. */
  summary?: string;
  /** Carried into the finding's `data`. */
  data?: Record<string, unknown>;
  /** Which matches to attach: the first per query (default) or every one. */
  evidence?: "first" | "all";
  network?: NetworkQuery | ReadonlyArray<NetworkQuery>;
  analytics?: AnalyticsQuery | ReadonlyArray<AnalyticsQuery>;
  events?: EventQuery | ReadonlyArray<EventQuery>;
  tools?: ToolQuery | ReadonlyArray<ToolQuery>;
  objectives?: ObjectiveQuery | ReadonlyArray<ObjectiveQuery>;
  screenText?: ScreenTextQuery | ReadonlyArray<ScreenTextQuery>;
  deviceLog?: DeviceLogQuery | ReadonlyArray<DeviceLogQuery>;
  /**
   * Queries that must NOT match. A check that is "checkout completed with no card decline" lists
   * the decline here.
   */
  absent?: FindingQueries;
}

export type FindingQueries = Pick<FindingSpec, "network" | "analytics" | "events" | "tools" | "objectives" | "screenText" | "deviceLog">;

/** @deprecated Renamed to {@link FindingSpec}. */
export type FootprintSpec = FindingSpec;
/** @deprecated Renamed to {@link FindingQueries}. */
export type FootprintQueries = FindingQueries;

/**
 * [spec]'s declarative finding, under `finding` or its old name `footprint`. Throws when both are
 * set, since only one can be the survey's finding.
 */
export function findingOf(spec: SurveySpec | undefined): FindingSpec | undefined {
  if (spec?.finding !== undefined && spec.footprint !== undefined) {
    throw new Error(`survey()${named(spec)}: sets both \`finding\` and \`footprint\`; \`footprint\` is the old name of \`finding\`, so keep one`);
  }
  return spec?.finding ?? spec?.footprint;
}

const ACCESSORS = ["network", "analytics", "events", "tools", "objectives", "screenText", "deviceLog"] as const;

function list<T>(v: T | ReadonlyArray<T> | undefined): ReadonlyArray<T> {
  if (v === undefined) return [];
  return Array.isArray(v) ? (v as ReadonlyArray<T>) : [v as T];
}

/** Runs every query of `queries`; returns evidence per query, or null as soon as one has no match. */
function evaluate(session: SurveySession, queries: FindingQueries, mode: "first" | "all"): SessionRecord[] | null {
  const evidence: SessionRecord[] = [];
  for (const key of ACCESSORS) {
    for (const q of list(queries[key] as unknown)) {
      const records = session[key] as unknown as { find(q: unknown): SessionRecord | undefined; filter(q: unknown): SessionRecord[] };
      if (mode === "all") {
        const all = records.filter(q);
        if (all.length === 0) return null;
        evidence.push(...all);
      } else {
        const one = records.find(q);
        if (!one) return null;
        evidence.push(one);
      }
    }
  }
  return evidence;
}

/** True when ANY query of `queries` matches a record. `absent` vetoes on one hit, not on all of them. */
function anyMatches(session: SurveySession, queries: FindingQueries): boolean {
  for (const key of ACCESSORS) {
    for (const q of list(queries[key] as unknown)) {
      const records = session[key] as unknown as { find(q: unknown): SessionRecord | undefined };
      if (records.find(q)) return true;
    }
  }
  return false;
}

/** At least one actual query: an empty array under an accessor is no query, and would match every session. */
function hasQueries(finding: FindingSpec): boolean {
  return ACCESSORS.some((k) => list(finding[k] as unknown).length > 0);
}

/**
 * Tests one declarative finding against a session. Returns the evidence when every query matched
 * and nothing under `absent` did, otherwise `undefined`. Handlers use it to mix declarative
 * findings with computed ones.
 */
export function matchFinding(session: SurveySession, finding: FindingSpec): SessionRecord[] | undefined {
  const evidence = evaluate(session, finding, finding.evidence ?? "first");
  if (!evidence) return undefined;
  if (finding.absent && anyMatches(session, finding.absent)) return undefined;
  return evidence;
}

/** @deprecated Renamed to {@link matchFinding}. */
export const matchFootprint = matchFinding;

/** Which of a finding's queries matched, as `accessor {query}` text, for a failure message. */
export interface FindingExplanation {
  matched: string[];
  missed: string[];
  /** `absent` queries that did match, ruling the finding out. */
  vetoedBy: string[];
}

/** @deprecated Renamed to {@link FindingExplanation}. */
export type FootprintExplanation = FindingExplanation;

/** Says query by query why a finding did or did not match a session. */
export function explainFinding(session: SurveySession, finding: FindingSpec): FindingExplanation {
  const out: FindingExplanation = { matched: [], missed: [], vetoedBy: [] };
  const each = (queries: FindingQueries, visit: (label: string, hit: boolean) => void) => {
    for (const key of ACCESSORS) {
      for (const q of list(queries[key] as unknown)) {
        const records = session[key] as unknown as { find(q: unknown): SessionRecord | undefined };
        visit(`${key} ${JSON.stringify(q)}`, records.find(q) !== undefined);
      }
    }
  };
  each(finding, (label, hit) => (hit ? out.matched : out.missed).push(label));
  if (finding.absent) each(finding.absent, (label, hit) => hit && out.vetoedBy.push(label));
  return out;
}

/** @deprecated Renamed to {@link explainFinding}. */
export const explainFootprint = explainFinding;

/** Handlers `survey()` built from a finding, as opposed to ones an author wrote. */
const DECLARATIVE = new WeakSet<SurveyHandler>();

/** Whether [def]'s handler is one `survey()` built from its finding, so its reads are its queries. */
export function isDeclarative(def: SurveyDefinition): boolean {
  return DECLARATIVE.has(def.handler);
}

function declarative(def: SurveyDefinition): SurveyDefinition {
  DECLARATIVE.add(def.handler);
  return def;
}

/**
 * Whether every finding [def] can make proves that something was seen, never that something was
 * not: a finding with no `absent` and no predicate. Such a finding stands even when a capture lost
 * data. A handler or a `where` an author wrote may test for absence (`r.status === undefined` holds
 * on a request whose response was lost) in ways that cannot be read off it, so neither counts.
 */
export function provesOnlyPresence(def: SurveyDefinition): boolean {
  if (!DECLARATIVE.has(def.handler)) return false;
  const finding = findingOf(def.spec);
  return finding !== undefined && finding.absent === undefined && !holdsFunction(finding);
}

function holdsFunction(value: unknown, seen = new Set<unknown>()): boolean {
  if (typeof value === "function") return true;
  if (value === null || typeof value !== "object" || value instanceof RegExp || seen.has(value)) return false;
  seen.add(value);
  return Object.values(value).some((v) => holdsFunction(v, seen));
}

function findingHandler(spec: SurveySpec, finding: FindingSpec): SurveyHandler {
  if (!hasQueries(finding)) {
    throw new Error(`survey()${named(spec)}: the finding has no queries; at least one of ${ACCESSORS.join(", ")} is required`);
  }
  const summary = finding.summary ?? spec.description ?? "Matched the survey's finding";
  // A finding spec written before findings were free-form set `feature`, `kind` or `confidence`
  // on itself; they belong to its finding's `data`, ahead of the spec-level defaults the runner folds in.
  const legacy = Object.fromEntries(
    (["feature", "kind", "confidence"] as const).flatMap((key) => {
      const value = (finding as unknown as Record<string, unknown>)[key];
      return value === undefined ? [] : [[key, value]];
    }),
  );
  const data = Object.keys(legacy).length === 0 ? finding.data : { ...legacy, ...finding.data };
  return (session) => {
    const evidence = matchFinding(session, finding);
    if (!evidence) return undefined;
    return data === undefined ? { summary, evidence } : { summary, evidence, data };
  };
}

/**
 * Defines a survey. With a handler, the handler inspects the session and returns zero or more
 * findings. Without one, `spec.finding` describes the check declaratively. `D` types the `data`
 * the handler's findings carry.
 */
export function survey<D extends object = Record<string, unknown>>(spec: SurveySpec, handler?: SurveyHandler<D>): SurveyDefinition {
  // Read first, so a spec setting both keys fails here even with a handler, not later mid-trail.
  const finding = findingOf(spec);
  if (handler !== undefined) return define(spec, handler as unknown as SurveyHandler);
  if (finding) return declarative(define(spec, findingHandler(spec, finding)));
  throw new Error(`survey()${named(spec)}: needs a handler or a finding`);
}

/**
 * Throws unless `f` is a finding a report can carry: a summary, and at least one record proving it.
 * Here rather than in the runner so a browser page, which cannot load the runner's disk reader,
 * holds its findings to the same rule.
 */
export function validateFinding(f: FindingInput, survey: string): void {
  if (typeof f.summary !== "string" || f.summary.trim() === "") {
    throw new Error(`survey ${survey} returned a finding without a summary`);
  }
  if (!Array.isArray(f.evidence)) {
    throw new Error(`survey ${survey} returned a finding whose evidence is not an array`);
  }
  // A finding is a claim about a session pinned to the records that prove it. One with no records
  // is unfalsifiable, and reads on the report exactly like a proven one.
  if (f.evidence.length === 0) {
    throw new Error(`survey ${survey} returned a finding with no evidence: ${f.summary}`);
  }
}

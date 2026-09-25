// The authoring surface: `survey(spec, handler)`, or `survey(spec)` with a declarative `footprint`.
//
// Mirrors `trailblaze.tool`: an author exports the return value of one call per feature, the
// runner discovers every such export in the files it is given, and the export name becomes the
// survey's id. Nothing is registered globally; a survey is a plain, inspectable value.

import type { AnalyticsQuery, DeviceLogQuery, EventQuery, NetworkQuery, ObjectiveQuery, ScreenTextQuery, ToolQuery } from "./match.js";
import type { SurveySession } from "./session.js";
import type { FindingInput, SessionRecord, SurveySpec } from "./types.js";

const BRAND: unique symbol = Symbol.for("trailblaze.survey");

/** Passed to a handler alongside the session. `found` is the imperative alternative to returning. */
export interface SurveyContext {
  /** Record one finding. Equivalent to returning it. */
  found(summary: string, evidence: ReadonlyArray<SessionRecord>, extra?: Omit<FindingInput, "summary" | "evidence">): void;
  /** Diagnostic output; the runner forwards it to its own log, never into the report. */
  log(message: string): void;
}

export type SurveyResult = FindingInput | ReadonlyArray<FindingInput> | undefined | null | false | void;

export type SurveyHandler = (session: SurveySession, ctx: SurveyContext) => SurveyResult | Promise<SurveyResult>;

/** What `survey()` returns. Export it; the runner does the rest. */
export interface SurveyDefinition {
  readonly [BRAND]: true;
  readonly spec: SurveySpec;
  readonly handler: SurveyHandler;
  /** Set by discovery from the export name. */
  readonly id?: string;
}

function define(spec: SurveySpec, handler: SurveyHandler, id?: string): SurveyDefinition {
  if (!spec || typeof spec.feature !== "string" || spec.feature.trim() === "") {
    throw new Error("survey(): spec.feature is required and must be a non-empty string");
  }
  if (typeof handler !== "function") {
    throw new Error(`survey(): second argument must be a handler function (feature ${spec.feature})`);
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
 * What a feature's footprint looks like, declaratively: every listed query must match at least
 * one record, and the footprint's evidence is the first match of each (or all matches with
 * `evidence: "all"`). Use it when a feature is "these things were observed" and nothing needs
 * computing; write a handler when something does.
 */
export interface FootprintSpec {
  /** One line for the footprint. Defaults to the survey description or the feature id. */
  summary?: string;
  confidence?: FindingInput["confidence"];
  /** Overrides the survey's `kind` for this footprint. */
  kind?: string;
  /** Carried into the footprint's `data`. */
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
   * Queries that must NOT match. A feature that is "checkout completed with no card decline" lists
   * the decline here.
   */
  absent?: FootprintQueries;
}

export type FootprintQueries = Pick<FootprintSpec, "network" | "analytics" | "events" | "tools" | "objectives" | "screenText" | "deviceLog">;

const ACCESSORS = ["network", "analytics", "events", "tools", "objectives", "screenText", "deviceLog"] as const;

function list<T>(v: T | ReadonlyArray<T> | undefined): ReadonlyArray<T> {
  if (v === undefined) return [];
  return Array.isArray(v) ? (v as ReadonlyArray<T>) : [v as T];
}

/** Runs every query of `queries`; returns evidence per query, or null as soon as one has no match. */
function evaluate(session: SurveySession, queries: FootprintQueries, mode: "first" | "all"): SessionRecord[] | null {
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
function anyMatches(session: SurveySession, queries: FootprintQueries): boolean {
  for (const key of ACCESSORS) {
    for (const q of list(queries[key] as unknown)) {
      const records = session[key] as unknown as { find(q: unknown): SessionRecord | undefined };
      if (records.find(q)) return true;
    }
  }
  return false;
}

/** At least one actual query: an empty array under an accessor is no query, and would match every session. */
function hasQueries(footprint: FootprintSpec): boolean {
  return ACCESSORS.some((k) => list(footprint[k] as unknown).length > 0);
}

/**
 * Tests one declarative footprint against a session. Returns the evidence when every query matched
 * and nothing under `absent` did, otherwise `undefined`. Handlers use it to mix declarative
 * footprints with computed ones.
 */
export function matchFootprint(session: SurveySession, footprint: FootprintSpec): SessionRecord[] | undefined {
  const evidence = evaluate(session, footprint, footprint.evidence ?? "first");
  if (!evidence) return undefined;
  if (footprint.absent && anyMatches(session, footprint.absent)) return undefined;
  return evidence;
}

function toFinding(footprint: FootprintSpec, evidence: SessionRecord[], defaults: { feature?: string; summary: string }): FindingInput {
  const out: FindingInput = { summary: footprint.summary ?? defaults.summary, evidence };
  if (defaults.feature !== undefined) out.feature = defaults.feature;
  if (footprint.kind !== undefined) out.kind = footprint.kind;
  if (footprint.confidence !== undefined) out.confidence = footprint.confidence;
  if (footprint.data !== undefined) out.data = footprint.data;
  return out;
}

function footprintHandler(spec: SurveySpec, footprint: FootprintSpec): SurveyHandler {
  if (!hasQueries(footprint)) {
    throw new Error(`survey(): feature ${spec.feature} has a footprint with no queries; at least one of ${ACCESSORS.join(", ")} is required`);
  }
  return (session) => {
    const evidence = matchFootprint(session, footprint);
    if (!evidence) return undefined;
    return toFinding(footprint, evidence, { summary: spec.description ?? spec.feature });
  };
}

function footprintsHandler(spec: SurveySpec, footprints: Readonly<Record<string, FootprintSpec>>): SurveyHandler {
  const entries = Object.entries(footprints);
  if (entries.length === 0) {
    throw new Error(`survey(): ${spec.feature} declares footprints with no entries`);
  }
  for (const [feature, footprint] of entries) {
    if (!hasQueries(footprint)) {
      throw new Error(`survey(): ${spec.feature} footprint ${feature} has no queries; at least one of ${ACCESSORS.join(", ")} is required`);
    }
  }
  return (session) => {
    const out: FindingInput[] = [];
    for (const [feature, footprint] of entries) {
      const evidence = matchFootprint(session, footprint);
      if (evidence) out.push(toFinding(footprint, evidence, { feature, summary: feature }));
    }
    return out;
  };
}

/** The feature ids a survey declares it can report: its `footprints` keys, else `features`, else `feature`. */
export function surveyFeatures(spec: SurveySpec): string[] {
  if (spec.footprints) return Object.keys(spec.footprints);
  if (spec.features) return [...spec.features];
  return [spec.feature];
}

/**
 * Defines a survey. With a handler, the handler inspects the session and returns zero or more
 * footprints. Without one, `spec.footprint` describes the one feature declaratively, or
 * `spec.footprints` describes many features keyed by id and the survey reports each that matches.
 */
export function survey(spec: SurveySpec, handler?: SurveyHandler): SurveyDefinition {
  if (handler !== undefined) return define(spec, handler);
  if (spec?.footprint && spec.footprints) {
    throw new Error(`survey(): ${spec.feature} gives both footprint and footprints; use one`);
  }
  if (spec?.footprints) return define(spec, footprintsHandler(spec, spec.footprints));
  if (spec?.footprint) return define(spec, footprintHandler(spec, spec.footprint));
  throw new Error(`survey(): feature ${spec?.feature} needs a handler, a footprint, or footprints`);
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

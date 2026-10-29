// The authoring surface: `survey({ find, label })`. `find` is a function of one session or a
// declarative finding; `survey(spec, handler)` and `survey({ finding })` are its older spellings.
//
// Mirrors `trailblaze.tool`: an author exports the return value of one call per check, the runner
// discovers every such export in the files it is given, and the export name becomes the survey's
// id. Nothing is registered globally; a survey is a plain, inspectable value.

import type { AnalyticsQuery, DeviceLogQuery, EventQuery, NetworkQuery, ObjectiveQuery, ScreenTextQuery, ToolQuery } from "./match.js";
import type { SurveySession } from "./session.js";
import type { Finding, SurveyFinding, Label, LabelInput, SessionRecord, SessionSummary, SurveySpec } from "./types.js";

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

/** What a survey's `find` returns: one finding, several, or nothing. */
export type FindResult<D extends object = Record<string, unknown>> = SurveyFinding<D> | ReadonlyArray<SurveyFinding<D>> | undefined | null | false | void;

/** @deprecated Use FindResult. */
export type SurveyResult<D extends object = Record<string, unknown>> = FindResult<D>;

export type SurveyHandler<D extends object = Record<string, unknown>> = (session: SurveySession, ctx: SurveyContext<D>) => FindResult<D> | Promise<FindResult<D>>;

/**
 * What `survey()` returns. Export it; the runner does the rest. `D` is the `data` its findings
 * carry and `L` the labels its label phase returns, so another survey's label phase reads both typed.
 */
export interface SurveyDefinition<D extends object = any, L extends LabelInput<object> = any> {
  readonly [BRAND]: true;
  readonly spec: SurveySpec<any, any, any>;
  readonly handler: SurveyHandler;
  /** Set by discovery from the export name. */
  readonly id?: string;
  /** Set by discovery: a hash of the file that defines the survey and its local imports, so editing either re-surveys stored sessions. */
  readonly version?: string;
  /** Types only; never set. Carries `D` and `L` to the readers of this survey's findings and labels. */
  readonly types?: { readonly data: D; readonly label: L };
}

/** The `data` a survey's findings carry. */
export type DataOf<S> = S extends SurveyDefinition<infer D, any> ? D : never;

/** The labels a survey's label phase returns. */
export type LabelOf<S> = S extends SurveyDefinition<any, infer L> ? L : never;

/**
 * A finding as a label phase reads it. `data` is always there: `{}` when the find phase gave none,
 * so reading a field needs no `!`.
 */
export type BatchFinding<D extends object = Record<string, unknown>> = Finding<D> & { readonly data: D };

/** What a label phase reads of one survey it lists in `dependsOn`, typed as that survey declares it. */
export interface Dependency<S extends SurveyDefinition = SurveyDefinition> {
  /** The labels its label phase returned for this batch. */
  readonly labels: ReadonlyArray<Label<LabelOf<S>>>;
  /** Its findings across the batch. */
  readonly findings: ReadonlyArray<BatchFinding<DataOf<S>>>;
}

/** What a label phase sees: the batch, and the surveys it depends on. */
export interface LabelBatch<D extends object = Record<string, unknown>, U extends Record<string, SurveyDefinition> = {}> {
  /** This survey's own findings, across every session of the batch. */
  readonly findings: ReadonlyArray<BatchFinding<D>>;
  /** The sessions of the batch: every one the find phase ran over that this survey applies to. */
  readonly sessions: ReadonlyArray<SessionSummary>;
  /** Each survey in `dependsOn`, under the name it is listed by there. */
  readonly dependsOn: { readonly [K in keyof U]: Dependency<U[K]> };
  /**
   * This survey's findings grouped by `key`, in the order each key first appears: the usual first
   * step of a label phase that emits one label per value.
   */
  groupBy<K extends string>(key: (finding: BatchFinding<D>) => K): Array<{ key: K; findings: BatchFinding<D>[] }>;
  /** Diagnostic output; the runner forwards it to its own log, never into the report. */
  log(message: string): void;
}

export type LabelResult<L extends LabelInput<object> = LabelInput> = L | ReadonlyArray<L> | undefined | null | false | void;

/** How an error names a survey that has no id yet: its description, else nothing. */
function named(spec: SurveySpec<any, any, any> | undefined): string {
  return spec?.description ? ` (${spec.description})` : "";
}

/** Names a survey in an error: its id, else its description, else that it has neither. */
export function nameOf(def: SurveyDefinition): string {
  return def.id ?? (def.spec.description ? `(${def.spec.description})` : "(an unnamed survey)");
}

function define(spec: SurveySpec<any, any, any>, handler: SurveyHandler, id?: string, version?: string): SurveyDefinition {
  if (!spec || typeof spec !== "object") {
    throw new Error("survey(): the first argument must be a spec object");
  }
  if (typeof handler !== "function") {
    throw new Error(`survey(): second argument must be a handler function${named(spec)}`);
  }
  return Object.freeze({ [BRAND]: true as const, spec, handler, id, version });
}

/** True when `value` came out of `survey()`. */
export function isSurvey(value: unknown): value is SurveyDefinition {
  return typeof value === "object" && value !== null && (value as { [BRAND]?: unknown })[BRAND] === true;
}

/**
 * Returns a copy of `def` carrying `id`, and `version` when given. Discovery uses this to attach the
 * export name and the defining file's hash. The copy shares `def`'s spec and handler, which is how
 * a survey listed in another's `dependsOn` is matched to its loaded copy.
 */
export function withId<S extends SurveyDefinition>(def: S, id: string, version: string | undefined = def.version): S {
  return def.id === id && def.version === version ? def : (define(def.spec, def.handler, id, version) as S);
}

/**
 * The finding a survey reports, declaratively: its `summary` and `data`, and the queries that prove
 * it. Every listed query must match at least one record, and the finding's evidence is the first
 * match of each (or all matches with `evidence: "all"`). Use it when the check is "these things were
 * observed" and nothing needs computing; write a handler when something does.
 */
export interface FindingSpec<D extends object = Record<string, unknown>> {
  /** One line for the finding. Defaults to the survey's description. */
  summary?: string;
  /** Carried into the finding's `data`. */
  data?: D;
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

/** [spec]'s declarative finding: `find` when it is one, else the older `finding`. */
export function findingOf(spec: SurveySpec<any, any, any> | undefined): FindingSpec | undefined {
  const find = spec?.find;
  return find !== undefined && typeof find !== "function" ? find : spec?.finding;
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

/** Which of a finding's queries matched, as `accessor {query}` text, for a failure message. */
export interface FindingExplanation {
  matched: string[];
  missed: string[];
  /** `absent` queries that did match, ruling the finding out. */
  vetoedBy: string[];
}

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

function findingHandler(spec: SurveySpec<any, any, any>, finding: FindingSpec): SurveyHandler {
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
 * Marks the handler `survey()` builds for a survey with only a label phase. A registered symbol,
 * like the brand, because a workspace's surveys import the SDK copy extracted beside them while the
 * runner may be another copy.
 */
const LABEL_ONLY: unique symbol = Symbol.for("trailblaze.survey.labelOnly");

/** Whether [def] finds anything per session: false for a survey that has only a label phase. */
export function hasFindPhase(def: SurveyDefinition): boolean {
  return (def.handler as { [LABEL_ONLY]?: unknown })[LABEL_ONLY] !== true;
}

function checkLabelPhase(spec: SurveySpec<any, any, any>): void {
  if (spec.label !== undefined && typeof spec.label !== "function") {
    throw new Error(`survey()${named(spec)}: label must be a function`);
  }
  if (spec.dependsOn === undefined) return;
  const deps = spec.dependsOn as unknown;
  if (typeof deps !== "object" || deps === null || Array.isArray(deps) || Object.values(deps).some((s) => !isSurvey(s))) {
    throw new Error(`survey()${named(spec)}: dependsOn must name surveys, each the value another survey() returned: dependsOn: { journeys }`);
  }
  if (spec.label === undefined) {
    throw new Error(`survey()${named(spec)}: dependsOn is for label phases, and this survey has none; add a label phase or drop dependsOn`);
  }
}

/** The surveys [def] lists in `dependsOn`, by the name it reads each under. */
export function dependenciesOf(def: SurveyDefinition): Array<[name: string, survey: SurveyDefinition]> {
  return Object.entries((def.spec.dependsOn ?? {}) as Record<string, SurveyDefinition>);
}

/**
 * Defines a survey: `find` runs once per session and returns findings, `label` once per batch and
 * turns them into labels. Either may stand alone. `D` types the `data` the findings carry, `L` the
 * labels and `U` the surveys in `dependsOn`; let them be inferred. With `find` written before
 * `label`, `label` reads the findings typed by what `find` returns.
 *
 * `survey(spec, handler)` and `survey({ finding })` are older spellings of `find`, and still work.
 */
export function survey<
  D extends object = Record<string, unknown>,
  L extends LabelInput<object> = LabelInput,
  U extends Record<string, SurveyDefinition> = {},
>(spec: SurveySpec<D, L, U>, handler?: SurveyHandler<D>): SurveyDefinition<D, L> {
  if (!spec || typeof spec !== "object") throw new Error("survey(): the first argument must be a spec object");
  const find = spec.find;
  if (find !== undefined && (handler !== undefined || spec.finding !== undefined)) {
    throw new Error(`survey()${named(spec)}: give the find phase once: as find, or as a handler or finding, not both`);
  }
  if (find !== undefined && (find === null || (typeof find !== "function" && typeof find !== "object"))) {
    throw new Error(`survey()${named(spec)}: find must be a function of the session, or a declarative finding`);
  }
  // Read first, so a spec setting both keys fails here even with a handler, not later mid-trail.
  const finding = findingOf(spec);
  checkLabelPhase(spec);
  const fn = handler ?? (typeof find === "function" ? find : undefined);
  if (fn !== undefined) return define(spec, fn as unknown as SurveyHandler);
  if (finding) return declarative(define(spec, findingHandler(spec, finding)));
  if (spec.label !== undefined) {
    const none = Object.assign((() => undefined) as SurveyHandler, { [LABEL_ONLY]: true });
    return define(spec, none);
  }
  throw new Error(`survey()${named(spec)}: needs a find phase, a label phase, or both`);
}

/**
 * Throws unless `f` is a finding a report can carry: a summary, and at least one record proving it.
 * Here rather than in the runner so a browser page, which cannot load the runner's disk reader,
 * holds its findings to the same rule.
 */
export function validateFinding(f: SurveyFinding, survey: string): void {
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

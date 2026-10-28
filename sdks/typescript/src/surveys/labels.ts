// The label phase: which surveys a run needs, the order their label phases run in, and running them.
//
// No filesystem access, like `run-one.ts`, so anything that already holds a batch's findings can
// label it. Label phases see findings from every survey but labels only from the surveys they list
// in `dependsOn`, which is what lets one survey run alone and still get the answer a full run gives.

import { dependenciesOf, nameOf, type BatchFinding, type Dependency, type LabelBatch, type SurveyDefinition } from "./survey.js";
import type { SurveyError } from "./run-one.js";
import type { Finding, Label, LabelInput, LabelRecord, SessionSummary } from "./types.js";

/** One survey a run includes, and why. */
export interface PlannedSurvey {
  def: SurveyDefinition;
  id: string;
  /** False for a survey the run includes only because a selected survey lists it in `dependsOn`. */
  selected: boolean;
  /** The surveys whose `dependsOn` brought it in. Empty for a selected survey. */
  neededBy: string[];
  /** Ids of the surveys its own `dependsOn` lists, in order. */
  dependsOn: string[];
}

/**
 * The surveys a run needs: the selected ones, then every survey their `dependsOn` lists, transitively.
 * A survey in `dependsOn` is the value its file exports, and discovery hands the runner a copy carrying
 * the export name, so it is matched to that copy by spec, among the selected surveys first and then
 * `available` (every loaded survey). Throws when `dependsOn` names a survey that was never loaded, or
 * when the label phases form a cycle.
 */
export function planSurveys(
  selected: ReadonlyArray<{ def: SurveyDefinition; id: string }>,
  available: ReadonlyArray<SurveyDefinition> = [],
): PlannedSurvey[] {
  const planned: PlannedSurvey[] = selected.map(({ def, id }) => ({ def, id, selected: true, neededBy: [], dependsOn: [] }));
  const bySurvey = new BySurvey<PlannedSurvey>(planned.map((p) => [p.def, p]));
  const loaded = new BySurvey<SurveyDefinition>(available.map((d) => [d, d]));
  for (let i = 0; i < planned.length; i++) {
    const p = planned[i]!;
    for (const [, dep] of dependenciesOf(p.def)) {
      let entry = bySurvey.get(dep);
      if (!entry) {
        const def = loaded.get(dep) ?? dep;
        if (def.id === undefined) {
          throw new Error(`Survey ${p.id} lists ${nameOf(dep)} in dependsOn, but that survey was not loaded. Export it from a survey file the run loads.`);
        }
        entry = { def, id: def.id, selected: false, neededBy: [], dependsOn: [] };
        planned.push(entry);
        bySurvey.set(dep, entry);
      }
      if (!entry.selected && !entry.neededBy.includes(p.id)) entry.neededBy.push(p.id);
      p.dependsOn.push(entry.id);
    }
  }
  labelOrder(planned);
  return planned;
}

/**
 * Values keyed by survey: its spec and handler together, which a `withId` copy keeps, even one made
 * by another copy of this SDK. The spec alone is not enough, since two exports may share one spec
 * object and differ only by handler.
 */
class BySurvey<V> {
  private readonly bySpec = new Map<unknown, Map<unknown, V>>();

  constructor(entries: Iterable<[SurveyDefinition, V]> = []) {
    for (const [def, value] of entries) this.set(def, value);
  }

  get(def: SurveyDefinition | undefined): V | undefined {
    return this.bySpec.get(def?.spec)?.get(def?.handler);
  }

  set(def: SurveyDefinition, value: V): void {
    let byHandler = this.bySpec.get(def.spec);
    if (!byHandler) this.bySpec.set(def.spec, (byHandler = new Map()));
    byHandler.set(def.handler, value);
  }
}

/**
 * The surveys with a label phase, each after every survey its `dependsOn` lists. Declaration order
 * otherwise. Throws on a cycle, naming it.
 */
export function labelOrder(planned: ReadonlyArray<Pick<PlannedSurvey, "def" | "id" | "dependsOn">>): string[] {
  const byId = new Map(planned.map((p) => [p.id, p]));
  const state = new Map<string, "visiting" | "done">();
  const order: string[] = [];
  const stack: string[] = [];
  const visit = (id: string) => {
    const seen = state.get(id);
    if (seen === "done") return;
    if (seen === "visiting") {
      const loop = [...stack.slice(stack.indexOf(id)), id];
      throw new Error(`Survey label phases form a cycle through dependsOn: ${loop.join(" → ")}. Drop one of those dependsOn entries.`);
    }
    state.set(id, "visiting");
    stack.push(id);
    for (const dep of byId.get(id)?.dependsOn ?? []) visit(dep);
    stack.pop();
    state.set(id, "done");
    if (byId.get(id)?.def.spec.label) order.push(id);
  };
  for (const p of planned) visit(p.id);
  return order;
}

/** What the label phases of one batch produced. */
export interface LabelOutcome {
  /** Every label, in the order the label phases ran. */
  labels: LabelRecord[];
  /** Label phases that threw, returned a label that fails validation, or followed one that did. */
  errors: SurveyError[];
}

/**
 * Runs every planned label phase over a batch's findings, each after the surveys it lists in
 * `dependsOn`. With [applies], a label phase sees only the runs its survey applies to (its platforms,
 * app ids and targets), and the findings and labels of its dependencies in those runs.
 */
export async function runLabelPhases(
  planned: ReadonlyArray<PlannedSurvey>,
  findings: ReadonlyArray<Finding>,
  runs: ReadonlyArray<SessionSummary>,
  log?: (message: string) => void,
  applies?: (def: SurveyDefinition, run: SessionSummary) => boolean,
): Promise<LabelOutcome> {
  const byId = new Map(planned.map((p) => [p.id, p]));
  const bySurvey = new BySurvey<PlannedSurvey>(planned.map((p) => [p.def, p]));
  const runIndex = new Map(runs.map((r, i) => [r.id, i]));
  const labelsOf = new Map<string, Label[]>();
  // Each finding's position among its survey's findings in its session: what a label's reference
  // keeps, so it names one finding even when two in a run share a summary.
  const indexOf = new Map<Finding, number>();
  const counts = new Map<string, number>();
  for (const f of findings) {
    const key = `${f.survey}\u0000${f.session}`;
    const n = counts.get(key) ?? 0;
    indexOf.set(f, n);
    counts.set(key, n + 1);
  }
  const failed = new Set<string>();
  const outcome: LabelOutcome = { labels: [], errors: [] };

  for (const id of labelOrder(planned)) {
    const p = byId.get(id)!;
    const broken = p.dependsOn.filter((dep) => failed.has(dep));
    if (broken.length > 0) {
      failed.add(id);
      outcome.errors.push({ survey: id, message: `label phase skipped: the label phase of ${broken.join(", ")}, which it lists in dependsOn, failed` });
      continue;
    }
    const scoped = applies ? new Set(runs.filter((r) => applies(p.def, r)).map((r) => r.id)) : undefined;
    const inScope = (session: string) => scoped === undefined || scoped.has(session);
    const visible = findings.filter((f) => inScope(f.session));
    const findingsOf = (survey: string) => visible.filter((f) => f.survey === survey) as BatchFinding[];
    const labelsIn = (survey: string) =>
      (labelsOf.get(survey) ?? []).flatMap((l) => {
        const kept = l.findings.filter((f) => inScope(f.session));
        if (kept.length === l.findings.length) return [l];
        return kept.length === 0 ? [] : [{ ...l, findings: kept, runs: l.runs.filter(inScope) }];
      });
    const dependsOn: Record<string, Dependency> = {};
    for (const [name, dep] of dependenciesOf(p.def)) {
      const depId = bySurvey.get(dep)!.id;
      dependsOn[name] = { labels: labelsIn(depId) as never, findings: findingsOf(depId) };
    }
    const own = findingsOf(id);
    const batch: LabelBatch<Record<string, unknown>, Record<string, SurveyDefinition>> = {
      findings: own,
      sessions: scoped === undefined ? runs : runs.filter((r) => scoped.has(r.id)),
      dependsOn,
      groupBy: (key) => {
        const groups = new Map<string, BatchFinding[]>();
        for (const f of own) {
          const k = key(f);
          const group = groups.get(k);
          if (group) group.push(f);
          else groups.set(k, [f]);
        }
        return [...groups].map(([k, findings]) => ({ key: k as never, findings }));
      },
      log: (message) => log?.(`[${id}] ${message}`),
    };
    try {
      const result = await p.def.spec.label!(batch as never);
      const cited = new Set<Finding>(visible);
      const labels = merge(normalize(result).map((l) => validated(l, id, cited)), id, p.def.version, runIndex);
      labelsOf.set(id, labels);
      outcome.labels.push(...labels.map((l) => recordOf(l, indexOf)));
    } catch (e) {
      failed.add(id);
      outcome.errors.push({ survey: id, message: `label phase failed: ${e instanceof Error ? e.message : String(e)}` });
    }
  }
  return outcome;
}

function normalize(result: unknown): LabelInput<object>[] {
  if (result === undefined || result === null || result === false) return [];
  return Array.isArray(result) ? [...(result as LabelInput<object>[])] : [result as LabelInput<object>];
}

/**
 * Throws unless `l` is a label a report can carry: a name, and at least one finding proving it,
 * each one of the findings this label phase was given ([cited]).
 */
function validated(l: LabelInput<object>, survey: string, cited: ReadonlySet<Finding>): LabelInput<object> {
  if (typeof l?.name !== "string" || l.name.trim() === "") {
    throw new Error(`survey ${survey} returned a label without a name`);
  }
  if (l.type !== undefined && typeof l.type !== "string") {
    throw new Error(`survey ${survey} returned label ${l.name} whose type is not a string`);
  }
  // As a finding is pinned to the records that prove it, a label is pinned to its findings.
  if (!Array.isArray(l.findings) || l.findings.length === 0) {
    throw new Error(`survey ${survey} returned label ${l.name} with no findings; a label needs the findings that prove it`);
  }
  for (const f of l.findings) {
    if (!cited.has(f as Finding)) {
      throw new Error(`survey ${survey} returned label ${l.name} citing a finding that is not one of the findings its label phase was given`);
    }
  }
  return l;
}

/** One label per type and name, findings combined; the first one's reasoning and data stand. */
function merge(labels: LabelInput<object>[], survey: string, version: string | undefined, runIndex: Map<string, number>): Label[] {
  const byKey = new Map<string, { label: LabelInput<object>; findings: Finding<object>[] }>();
  for (const l of labels) {
    const key = `${l.type ?? ""}\u0000${l.name}`;
    const entry = byKey.get(key);
    if (!entry) byKey.set(key, { label: l, findings: [...new Set(l.findings)] });
    else for (const f of l.findings) if (!entry.findings.includes(f)) entry.findings.push(f);
  }
  return [...byKey.values()].map(({ label, findings }) => {
    const runs = [...new Set(findings.map((f) => f.session))].sort((a, b) => (runIndex.get(a) ?? Infinity) - (runIndex.get(b) ?? Infinity));
    const out = { ...label, findings, survey, runs } as Label;
    if (version !== undefined) out.version = version;
    return out;
  });
}

function recordOf(l: Label, indexOf: ReadonlyMap<Finding, number>): LabelRecord {
  const findings = l.findings.map((f) => ({ survey: f.survey, session: f.session, index: indexOf.get(f as Finding)!, summary: f.summary }));
  const out: LabelRecord = { survey: l.survey, name: l.name, runs: l.runs, findings };
  if (l.version !== undefined) out.version = l.version;
  if (l.type !== undefined) out.type = l.type;
  if (l.reasoning !== undefined) out.reasoning = l.reasoning;
  if (l.data !== undefined) out.data = l.data;
  return out;
}

// Waypoints as surveys. A waypoint names a screen the app can be on; here it is matched after
// the run against every view hierarchy the driver logged, so a report can say which screens a
// session visited, when, and during which step, with the screenshot the driver took at that
// moment as the proof. The same `matchScreen` is pure over one hierarchy, so a live runner can
// call it mid-session on the current screen; nothing here reads a file after loading.
//
// Definitions come from two places: the `*.waypoint.yaml` files a trailmap already carries
// (`loadYamlWaypoints`, one selector dialect per device classifier), and TypeScript `Waypoint`
// values a survey file declares directly. Both compile to the same `ScreenSpec`. A YAML anchor is
// evaluated by the same matcher the drivers use, so it means here what it meant on device;
// a TypeScript anchor is matched full-string.
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { matchesPattern, type MatchDialect } from "../matcher/index.js";
import type { SurveySession } from "./session.js";
import type { FindingInput, LogRecord } from "./types.js";

declare const Bun: { YAML?: { parse(text: string): unknown } } | undefined;

function parseYaml(text: string, file: string): unknown {
  if (typeof Bun === "undefined" || !Bun?.YAML) {
    throw new Error(`Reading ${file} needs the Bun runtime (Bun.YAML); build the waypoints in code instead`);
  }
  return Bun.YAML.parse(text);
}

/** One node of a logged view hierarchy, in the driver log's classic shape (any platform). */
export interface HierarchyNode {
  resourceId?: string | null;
  text?: string | null;
  accessibilityText?: string | null;
  className?: string | null;
  selected?: boolean | null;
  children?: HierarchyNode[] | null;
}

/** One screen the driver saw: the hierarchy plus where and when it was captured. */
export interface Screen {
  hierarchy: HierarchyNode;
  /** Session-relative driver log file the hierarchy came from. */
  file: string;
  /** Screenshot file name in the session directory, when the driver kept one. */
  screenshot?: string;
  /** Where the screenshot was uploaded, when the run kept it on a device farm instead of in the session. */
  screenshotUrl?: string;
  timeMs?: number;
  /** 1-based step of the trail that was running, when it can be told. */
  step?: number;
  /** Short name of the driver action that captured it. */
  action?: string;
}

/**
 * One selector pattern exactly as a `*.waypoint.yaml` wrote it, with the driver dialect it was
 * authored for. Kept as the raw string rather than compiled to a RegExp here because the runtime's
 * fields are **regex-OR-literal**: a pattern that misses as a regex still matches text equal to
 * it. That fallback is the only thing that makes a literal anchor such as `$5.00` work — it
 * compiles fine but, `$` being an end-of-input anchor, can never regex-match anything. Evaluated
 * by the SDK matcher, so a YAML anchor means here what it meant on device.
 */
export interface SelectorPattern {
  pattern: string;
  dialect: MatchDialect;
}

/** A string or RegExp is matched full-string; a `SelectorPattern` goes through the SDK matcher. */
export type FieldMatch = string | RegExp | SelectorPattern;

/**
 * Field-level conditions on one node; every given field must hold. Strings match full-string.
 * Web hierarchies arrive in the same shape: the driver logs a node's ARIA role as `className`
 * and its accessible name as `text`.
 */
export interface NodeSelector {
  resourceId?: FieldMatch;
  /** Matches the node's `text`, or its accessibility label when it has no `text`. */
  text?: FieldMatch;
  accessibilityText?: FieldMatch;
  className?: FieldMatch;
  selected?: boolean;
  /**
   * A direct child matches, as the runtime's `containsChild` reads on the projected trees the
   * drivers log. (The runtime relaxes this to descendants only for dense in-process trees.)
   */
  containsChild?: NodeSelector;
  /**
   * The anchor asks for something the logged hierarchy does not carry (a web `dataTestId` or
   * `headingLevel`), so it can never be confirmed. Matches nothing, and `matchScreen` gives up on
   * the whole spec rather than answering a question it only half checked.
   */
  unmatchable?: true;
}

export interface Anchor {
  description: string;
  selector: NodeSelector;
}

/** What a screen must show, and must not, to be this waypoint. */
export interface ScreenSpec {
  required: Anchor[];
  forbidden: Anchor[];
}

export interface WaypointExample {
  /** Hierarchy capture, absolute path. */
  file?: string;
  /** Reference screenshot, absolute path. */
  image?: string;
  capturedAt?: string;
}

export interface Waypoint {
  id: string;
  description: string;
  /**
   * Device classifier → screen spec, the shape the waypoint format itself uses: `android`, `ios`,
   * `web`, but equally `android-tablet` or `acme-kiosk`, and `all` for every device. A run picks
   * the closest block up its own classifier lineage, so a waypoint may be authored for one device
   * family only. Keyed by classifier and not by platform because a classifier is not always a
   * platform's descendant — a dedicated device can report platform Android yet be its own
   * classifier root, and an `android:` block is not a statement about what its screens look like.
   */
  byClassifier: Record<string, ScreenSpec>;
  example?: WaypointExample;
  /** Where the definition came from: a YAML path or a survey file. */
  source: string;
}

// --- matching --------------------------------------------------------------------------------

/**
 * Full-string match, like the runtime's `Regex.matches`. The guards are lookarounds rather than
 * `^`/`$`: under the `m` flag the Maestro dialect carries, those become per-line anchors and
 * `ok` would accept the second line of `"book\nok"`. `(?<![\s\S])` holds only at input start and
 * `(?![\s\S])` only at input end, whatever the flags.
 */
function full(m: string | RegExp): RegExp {
  const src = typeof m === "string" ? m.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") : m.source;
  return new RegExp(`(?<![\\s\\S])(?:${src})(?![\\s\\S])`, typeof m === "string" ? "" : m.flags.replace("g", ""));
}

function has(value: string | null | undefined, m: FieldMatch | undefined): boolean {
  if (m === undefined) return true;
  if (value == null) return false;
  if (typeof m === "string" || m instanceof RegExp) return full(m).test(value);
  return matchesPattern(m.pattern, value, m.dialect);
}

/**
 * The one string a `text` condition is matched against. Every driver resolves a single value and
 * matches its `textRegex` against that, `text` first and the accessibility label only when there is
 * no `text` (`resolveText`). Trying both fields instead claims screens the runtime never matched:
 * a node with `text: "Other"` and label `"More"` would answer an anchor for `More` here and nothing
 * on device.
 */
function nodeText(node: HierarchyNode): string | null {
  return node.text ?? node.accessibilityText ?? null;
}

export function matchNode(node: HierarchyNode, sel: NodeSelector): boolean {
  if (sel.unmatchable) return false;
  if (!has(node.resourceId, sel.resourceId)) return false;
  if (!has(node.accessibilityText, sel.accessibilityText)) return false;
  if (!has(node.className, sel.className)) return false;
  if (!has(nodeText(node), sel.text)) return false;
  if (sel.selected !== undefined && Boolean(node.selected) !== sel.selected) return false;
  if (sel.containsChild && !(node.children ?? []).some((c) => matchNode(c, sel.containsChild!))) return false;
  return true;
}

/** True when `sel` matches `root` or any descendant. */
export function anyNode(root: HierarchyNode, sel: NodeSelector): boolean {
  if (matchNode(root, sel)) return true;
  for (const c of root.children ?? []) if (anyNode(c, sel)) return true;
  return false;
}

/**
 * Pure: is this hierarchy the waypoint's screen? Usable live on the current screen or post-run.
 *
 * A spec with nothing required matches nothing, rather than every screen the session captured. The
 * waypoint format lets a definition carry only a `forbidden` list or only an `example`, and a
 * finding saying a session was on that screen at every capture is worse than no finding — the same
 * never-wider-than-the-runtime trade the classifier chain makes.
 */
export function matchScreen(spec: ScreenSpec, hierarchy: HierarchyNode): boolean {
  if (spec.required.length === 0) return false;
  // One anchor nobody can check disqualifies the spec, whichever list it is in. A required anchor
  // that matches nothing already sinks the waypoint, but a FORBIDDEN one that matches nothing never
  // excludes anything — so the waypoint would answer for more screens than its author wrote down,
  // and the screen it was told to rule out is exactly the one it would claim.
  if (spec.required.some((a) => unverifiable(a.selector)) || spec.forbidden.some((a) => unverifiable(a.selector))) return false;
  return spec.required.every((a) => anyNode(hierarchy, a.selector)) && !spec.forbidden.some((a) => anyNode(hierarchy, a.selector));
}

/** Does this anchor, or a child condition under it, ask for something no logged hierarchy carries? */
function unverifiable(sel: NodeSelector): boolean {
  return sel.unmatchable === true || (sel.containsChild !== undefined && unverifiable(sel.containsChild));
}

// --- screens of a session ----------------------------------------------------------------------

type DriverLog = { viewHierarchy?: HierarchyNode | null; screenshotFile?: string | null; action?: { class?: string } | null };
/** The runner's `final_screenshot` / `failure_screenshot`, and any snapshot a trail took on purpose. */
type SnapshotLog = { viewHierarchy?: HierarchyNode | null; screenshotFile?: string | null; displayName?: string | null };

/** Options for reading a session's screens. */
export interface ScreenOptions {
  /**
   * Hosts a screenshot artifact URL may come from, such as the CI system or the CDN a device farm
   * uploads results to. `screenshotUrl` starts life as a driver log field, not operator input, so
   * a URL from any other host is dropped: trusting it would let a malformed or compromised log make
   * whatever renders the findings fetch an arbitrary URL and publish the response.
   */
  screenshotHosts?: Iterable<string>;
}

function isTrustedArtifactUrl(v: string, hosts: ReadonlySet<string>): boolean {
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    return false;
  }
  if (u.protocol !== "https:") return false;
  return hosts.has(u.hostname);
}

/** The screenshot's file name in the session directory. Device-farm runs log an artifact URL whose `key` ends in that name. */
export function screenshotName(v: string | null | undefined): string | undefined {
  if (!v) return undefined;
  if (/^https?:\/\//.test(v)) {
    try {
      const u = new URL(v);
      return decodeURIComponent(u.searchParams.get("key") ?? u.pathname).split("/").pop() || undefined;
    } catch {
      return undefined;
    }
  }
  return v.split("/").pop() || undefined;
}

/**
 * Every hierarchy the session logged, in time order, each aligned to the step that was running.
 * Driver logs capture the screen BEFORE each action, so the screen the last action produced is
 * only in the runner's terminal snapshot; snapshots are read too. A snapshot whose hierarchy equals
 * the screen just before it is the same screen, not a second visit, and is dropped.
 */
export function screensOf(session: SurveySession, options: ScreenOptions = {}): Array<Screen & { record: LogRecord }> {
  const hosts = new Set(options.screenshotHosts ?? []);
  const steps = session.objectives.all();
  const stepAt = (t: number | undefined) => {
    if (t === undefined) return undefined;
    const running = steps.find((o) => o.startMs !== undefined && o.startMs <= t && (o.endMs === undefined || t <= o.endMs));
    return (running ?? [...steps].reverse().find((o) => o.startMs !== undefined && o.startMs <= t))?.step;
  };
  const url = (v: string | null | undefined) => (v && isTrustedArtifactUrl(v, hosts) ? v : undefined);
  const captures: Array<{ record: LogRecord; hierarchy: HierarchyNode; screenshotFile?: string | null; action?: string; snapshot: boolean }> = [];
  for (const record of session.logs.filter({ type: "MaestroDriverLog" })) {
    const log = record.log as unknown as DriverLog;
    if (log.viewHierarchy) captures.push({ record, hierarchy: log.viewHierarchy, screenshotFile: log.screenshotFile, action: log.action?.class?.split(".").pop(), snapshot: false });
  }
  for (const record of session.logs.filter({ type: "TrailblazeSnapshotLog" })) {
    const log = record.log as unknown as SnapshotLog;
    if (log.viewHierarchy) captures.push({ record, hierarchy: log.viewHierarchy, screenshotFile: log.screenshotFile, action: log.displayName ?? "snapshot", snapshot: true });
  }
  captures.sort((a, b) => (a.record.timeMs ?? Infinity) - (b.record.timeMs ?? Infinity));
  const out: Array<Screen & { record: LogRecord }> = [];
  for (const c of captures) {
    if (c.snapshot && out.length > 0 && sameHierarchy(out[out.length - 1]!.hierarchy, c.hierarchy)) continue;
    out.push({
      hierarchy: c.hierarchy,
      file: c.record.file,
      screenshot: screenshotName(c.screenshotFile),
      screenshotUrl: url(c.screenshotFile),
      timeMs: c.record.timeMs,
      step: stepAt(c.record.timeMs),
      action: c.action,
      record: c.record,
    });
  }
  return out;
}

function sameHierarchy(a: HierarchyNode, b: HierarchyNode): boolean {
  return a === b || JSON.stringify(a) === JSON.stringify(b);
}

// --- classifier lineage -------------------------------------------------------------------------

/** The implicit lowest-priority ancestor of every classifier: a block keyed `all` fits any device. */
const UNIVERSAL_ROOT = "all";

/** Most-specific-first ancestors of one classifier, by dropping a trailing `-segment` each step. */
function ancestors(classifier: string): string[] {
  const chain: string[] = [];
  let current: string | undefined = classifier;
  while (current !== undefined && !chain.includes(current)) {
    chain.push(current);
    const dash = current.lastIndexOf("-");
    current = dash <= 0 || dash === current.length - 1 ? undefined : current.slice(0, dash);
  }
  return chain;
}

/**
 * The device's resolution chain, most-specific-first: which classifier blocks this device may use,
 * in the order it prefers them. A port of the runtime's `TrailblazeClassifierLineage.resolutionChain`,
 * which is the one primitive every closest-wins lookup in the framework goes through, so a waypoint
 * resolves for a run here exactly as it would have on the device.
 *
 * The compound identity comes first (`[acme, handheld]` → `acme-handheld`, deriving `acme`), then each
 * segment's own lineage so a segment the compound does not end with is still probed, then `all`.
 * What this buys: `android` is NOT on such a device's chain. A dedicated device that reports
 * platform Android would, keyed off the platform, take every `android:` waypoint and report screens
 * it was never on; as its own classifier root, only its own blocks and `all` speak for it.
 *
 * The runtime also consults a registry of explicit parent overrides for families that do not
 * string-derive. There is no equivalent to read here, so such a family resolves to itself and `all`
 * — narrower than the runtime, never wider, which costs a finding rather than inventing one.
 *
 * There is one other port of this in the tree: `classifierLineage` in the report's own
 * `screens/trail-model.js`, which is plain JavaScript in a different module and not importable from
 * here. Three copies is one too many — change this rule in the Kotlin and both of these follow.
 */
export function classifierChain(deviceClassifiers: ReadonlyArray<string>): string[] {
  const segments = deviceClassifiers.map((c) => c.trim().toLowerCase()).filter((c) => c.length > 0);
  if (segments.length === 0) return [];
  const ordered = new Set<string>(ancestors(segments.join("-")));
  for (const segment of [...segments].reverse()) for (const c of ancestors(segment)) ordered.add(c);
  ordered.delete(UNIVERSAL_ROOT);
  return [...ordered, UNIVERSAL_ROOT];
}

/**
 * The chain a finished session resolves waypoints through. Sessions from before device classifiers
 * were logged carry only a platform; treating that as the one classifier keeps them resolving
 * against the `android:` / `ios:` / `web:` blocks that were authored when it was all there was.
 */
export function sessionChain(summary: { deviceClassifiers?: readonly string[]; platform?: string }): string[] {
  const classifiers = summary.deviceClassifiers ?? [];
  return classifierChain(classifiers.length > 0 ? classifiers : [summary.platform ?? ""]);
}

/**
 * The spec a device uses, and the closest classifier that speaks for it, or undefined when none
 * does.
 *
 * `required` and `forbidden` resolve **independently**, each from the closest block declaring a
 * non-empty list — the runtime's rule (`WaypointDefinition.resolveFor`), and the reason it exists:
 * a block may carry only an `example`, and taking that block whole would leave a waypoint with no
 * anchors, inheriting nothing from the family block that has them.
 */
export function resolveSpec(wp: Waypoint, chain: ReadonlyArray<string>): { classifier: string; spec: ScreenSpec } | undefined {
  const classifier = chain.find((c) => wp.byClassifier[c] !== undefined);
  if (classifier === undefined) return undefined;
  const blocks = chain.map((c) => wp.byClassifier[c]).filter((s): s is ScreenSpec => s !== undefined);
  return {
    classifier,
    spec: {
      required: blocks.find((s) => s.required.length > 0)?.required ?? [],
      forbidden: blocks.find((s) => s.forbidden.length > 0)?.forbidden ?? [],
    },
  };
}

/** One finding per waypoint the session's screens matched, with every matching screen as proof. */
export function waypointFindings(session: SurveySession, waypoints: ReadonlyArray<Waypoint>, options: ScreenOptions = {}): FindingInput[] {
  const chain = sessionChain(session.summary);
  const screens = screensOf(session, options);
  if (screens.length === 0) return [];
  const out: FindingInput[] = [];
  for (const wp of waypoints) {
    const resolved = resolveSpec(wp, chain);
    if (!resolved) continue;
    const { classifier, spec } = resolved;
    const hits = screens.filter((s) => matchScreen(spec, s.hierarchy));
    if (hits.length === 0) continue;
    const stepsSeen = [...new Set(hits.map((h) => h.step).filter((s): s is number => s !== undefined))].sort((a, b) => a - b);
    const stepText = stepsSeen.length === 0 ? "" : stepsSeen.length === 1 ? ` during step ${stepsSeen[0]}` : ` during steps ${stepsSeen[0]}–${stepsSeen[stepsSeen.length - 1]}`;
    out.push({
      feature: wp.id,
      kind: "waypoint",
      summary: `On screen ${hits.length} time${hits.length === 1 ? "" : "s"}${stepText}`,
      // `data.screens` below already carries screenshot/step/action per hit; a `detail` here would
      // just duplicate it; the generic evidence renderer only reads kind/summary/file/timeMs anyway.
      evidence: hits.map((h) => ({
        kind: "log" as const,
        summary: `${wp.id} matched${h.step !== undefined ? ` (step ${h.step})` : ""}${h.action ? ` at ${h.action}` : ""}`,
        file: h.file,
        timeMs: h.timeMs,
      })),
      confidence: "high",
      data: {
        classifier,
        platform: session.summary.platform ?? "",
        source: wp.source,
        screens: hits.map(({ file, screenshot, screenshotUrl, timeMs, step, action }) => ({ file, screenshot, screenshotUrl, timeMs, step, action })),
        firstMs: hits[0]!.timeMs,
        lastMs: hits[hits.length - 1]!.timeMs,
      },
    });
  }
  return out;
}

// --- YAML definitions --------------------------------------------------------------------------

type YamlSelector = Record<string, Record<string, unknown> | undefined>;
type YamlAnchor = { description?: string; selector?: YamlSelector };
type YamlVariant = { required?: YamlAnchor[]; forbidden?: YamlAnchor[]; example?: { file?: string; capturedAt?: string } };
type YamlWaypoint = { id: string; description?: string } & Record<string, unknown>;

/** Top-level keys of a waypoint document that are not classifier blocks. */
const RESERVED_KEYS = new Set(["id", "description", "route"]);
// The runtime substitutes the target's app id; a report spans builds, so accept any package.
const APP_ID_PLACEHOLDER = /\{\{\s*target\.appId\s*\}\}/g;

function pattern(v: unknown, dialect: MatchDialect = "native"): SelectorPattern | undefined {
  return typeof v === "string" ? { pattern: v.replace(APP_ID_PLACEHOLDER, "[\\w.]+"), dialect } : undefined;
}

/**
 * One YAML selector (any dialect) → the node conditions it expresses. Throws on a selector no
 * dialect here understands: compiled to nothing it would match every node, and a waypoint whose
 * anchors all match is a waypoint on every screen.
 */
export function selectorFromYaml(sel: YamlSelector): NodeSelector {
  const out: NodeSelector = {};
  const a = sel.androidAccessibility ?? sel.compose;
  const axe = sel.iosAxe;
  const maestro = sel.iosMaestro;
  const web = sel.web;
  // Each branch below assigns the SAME `out` fields (resourceId, text, ...), so a selector naming
  // dialects from more than one platform family would have the later branch silently overwrite the
  // earlier one's conditions rather than combine with it — compiling to a matcher that checks the
  // wrong platform's pattern, which can match wider than the runtime ever would on that device.
  if ([a, axe, maestro, web].filter((d) => d !== undefined).length > 1) {
    throw new Error(`waypoint selector mixes more than one platform's dialect in one anchor (dialects: ${Object.keys(sel).join(", ")}); split it into one anchor per classifier block instead`);
  }
  const detail = a ?? axe ?? maestro ?? web ?? {};
  const assign = (key: keyof NodeSelector, v: unknown, dialect: MatchDialect = "native") => {
    const p = pattern(v, dialect);
    if (p) (out as Record<string, unknown>)[key] = p;
  };
  if (a) {
    assign("resourceId", a.resourceIdRegex);
    assign("text", a.textRegex);
    assign("accessibilityText", a.contentDescriptionRegex);
    assign("className", a.classNameRegex);
  }
  if (axe) {
    // `uniqueId` reaches the runtime resolver as its id pattern, so it is regex-or-literal too.
    assign("resourceId", axe.uniqueId);
    assign("text", axe.labelRegex);
    assign("className", axe.roleRegex);
  }
  if (maestro) {
    assign("resourceId", maestro.resourceIdRegex, "maestro");
    assign("text", maestro.textRegex, "maestro");
    assign("accessibilityText", maestro.accessibilityTextRegex, "maestro");
  }
  if (web) {
    // The driver logs a web node's ARIA role as `className` and its accessible name as `text`.
    if (typeof web.ariaRole === "string") out.className = web.ariaRole;
    assign("text", web.ariaNameRegex);
    // Heading level and data-testid are not in the logged hierarchy — a web node is logged with its
    // ARIA role, its name, and whether it is clickable, and that is all. So an anchor naming either
    // one cannot be confirmed even next to a role and a name: checking only the conditions that ARE
    // logged answers a looser anchor than the one written down, and matches screens the author
    // deliberately narrowed away from.
    if (web.dataTestId !== undefined || web.headingLevel !== undefined) out.unmatchable = true;
  }
  if (typeof detail.isSelected === "boolean") out.selected = detail.isSelected;
  if (sel.containsChild) out.containsChild = selectorFromYaml(sel.containsChild as YamlSelector);
  if (Object.keys(out).length === 0) {
    throw new Error(`waypoint selector has no conditions this matcher understands (dialects: ${Object.keys(sel).join(", ") || "none"})`);
  }
  return out;
}

function anchorsFromYaml(list: YamlAnchor[] | undefined, file: string): Anchor[] {
  return (list ?? [])
    .filter((a) => a.selector)
    .map((a) => {
      try {
        return { description: a.description?.trim() ?? "", selector: selectorFromYaml(a.selector!) };
      } catch (e) {
        throw new Error(`${file}: anchor "${a.description?.trim() ?? ""}": ${(e as Error).message}`);
      }
    });
}

function* walk(dir: string): Generator<string> {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(p);
    else if (entry.name.endsWith(".waypoint.yaml")) yield p;
  }
}

/**
 * The repository a file sits in: the closest ancestor of `from` holding `.git` (a directory, or the
 * file a worktree has instead). Undefined outside one. A survey in a trailmap is loaded from
 * whichever repository staged the trailmap, so this is how its waypoint `source` paths stay
 * relative to the checkout the site links into, wherever that is.
 */
export function repositoryRoot(from: string): string | undefined {
  let dir = from;
  for (;;) {
    if (existsSync(join(dir, ".git"))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return undefined;
    dir = parent;
  }
}

/** `file` relative to its repository root, or `file` as given outside one. */
export function repositoryPath(file: string): string {
  const root = repositoryRoot(dirname(file));
  return root === undefined ? file : relative(root, file);
}

/** Every `*.waypoint.yaml` under `root` (recursively), compiled. `source` is the path relative to `relativeTo`. Needs Bun. */
export function loadYamlWaypoints(root: string, relativeTo = root): Waypoint[] {
  if (!existsSync(root)) return [];
  const out: Waypoint[] = [];
  for (const file of walk(root)) {
    const doc = parseYaml(readFileSync(file, "utf8"), file) as YamlWaypoint;
    if (!doc || typeof doc.id !== "string") continue;
    const byClassifier: Record<string, ScreenSpec> = {};
    let example: WaypointExample | undefined;
    // Every key that is not reserved IS a classifier block, which is the format's own rule — so a
    // waypoint authored for `android-tablet` or `acme-handheld` is read here without a list to extend.
    for (const [key, value] of Object.entries(doc)) {
      if (RESERVED_KEYS.has(key)) continue;
      const p = value as YamlVariant | undefined;
      if (!p || typeof p !== "object") continue;
      byClassifier[key] = { required: anchorsFromYaml(p.required, file), forbidden: anchorsFromYaml(p.forbidden, file) };
      if (p.example?.file && !example) {
        const base = join(dirname(file), p.example.file);
        const image = [".webp", ".png", ".jpg"].map((ext) => base.replace(/\.json$/, ext)).find((f) => existsSync(f));
        example = { file: existsSync(base) ? base : undefined, image, capturedAt: p.example.capturedAt };
      }
    }
    if (Object.keys(byClassifier).length === 0) continue;
    out.push({ id: doc.id, description: (doc.description ?? "").trim(), byClassifier, example, source: relative(relativeTo, file) });
  }
  return out.sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * One waypoint per id, across layers of definitions. A later layer replaces an earlier one
 * outright, which is how a TypeScript waypoint supersedes a YAML definition whose anchors the
 * driver on that platform cannot match; the surviving entry's `source` stays the file that
 * actually decides the match. Two definitions of one id in the SAME layer have no such rule and
 * are an authoring error — matching would report one screen as two.
 */
export function mergeWaypoints(...layers: ReadonlyArray<ReadonlyArray<Waypoint>>): Waypoint[] {
  const byId = new Map<string, Waypoint>();
  for (const layer of layers) {
    const inLayer = new Map<string, string>();
    for (const w of layer) {
      const twin = inLayer.get(w.id);
      if (twin !== undefined) throw new Error(`waypoint id "${w.id}" is defined twice in the same layer: ${twin} and ${w.source}`);
      inLayer.set(w.id, w.source);
      byId.set(w.id, w);
    }
  }
  return [...byId.values()].sort((a, b) => a.id.localeCompare(b.id));
}

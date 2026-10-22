// Pure model for the report's Summary tab: one run's trace steps and event streams as parallel lanes
// on the capture's shared clock. No DOM and no viewer state, so the layout the browser draws is the
// layout the tests pin (run-report-summary.ts owns the DOM).
//
// The board reads top to bottom: each trailhead/step is a BAND, and every lane holds the notes whose
// moment falls inside the band. A band is as tall as its fullest lane needs, so a note's vertical
// position is its moment on the clock, pushed down only when two notes would overlap.

import { childInstants } from './run-report-trace-model';

// ─── Readers for untrusted payload values ─────────────────────────────────────────────────────

export type Json = Record<string, unknown>;

export const MAX_LABEL = 200;
export const MAX_BODY = 6000;

export const isRecord = (value: unknown): value is Json => typeof value === 'object' && value !== null && !Array.isArray(value);
export const num = (value: unknown): number | undefined => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
export const str = (value: unknown): string | undefined => (typeof value === 'string' && value.trim() !== '' ? value : undefined);
export const clip = (value: string, max: number): string => (value.length <= max ? value : `${value.slice(0, max - 1)}…`);

export function scalarText(value: unknown): string | undefined {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return undefined;
}

export function prettyBody(value: unknown): string | undefined {
  let text: string | undefined;
  try {
    text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  } catch {
    return undefined;
  }
  return text ? clip(text, MAX_BODY) : undefined;
}

export function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export function formatMs(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)}s` : `${Math.round(ms)}ms`;
}

/** `m:ss.t` from the run's start. */
export function offset(ms: number): string {
  const tenths = Math.round(Math.max(0, ms) / 100);
  const seconds = (tenths % 600) / 10;
  return `${Math.floor(tenths / 600)}:${seconds.toFixed(1).padStart(4, '0')}`;
}

export function duration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const tenths = Math.round(ms / 100);
  if (tenths < 600) return `${(tenths / 10).toFixed(1)}s`;
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/** Whole kilobytes round up, as a cut body's note counts them. */
export function byteSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function clockTime(ms: number): string {
  const date = new Date(ms);
  const pad = (value: number, width = 2) => String(value).padStart(width, '0');
  return `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}.${pad(date.getUTCMilliseconds(), 3)}`;
}

// ─── Model types ──────────────────────────────────────────────────────────────────────────────

export interface NoteBadge {
  text: string;
  tone?: 'ok' | 'error';
  /** A plain badge worth the eye, such as a heavy payload. */
  strong?: boolean;
}

export interface NoteField {
  k: string;
  v: string;
  href?: string;
}

export interface ScreenBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** One row of a key/value list the card can search, such as a screen's strings. */
export interface NoteEntry {
  k: string;
  v: string;
  /** Rows sharing a group are listed together under it, in first-seen order. */
  group?: string;
  badge?: NoteBadge;
  /** Where the row's element sits on the note's screen, as fractions of its width and height. */
  boxes?: ScreenBox[];
}

export interface StreamNote {
  t: number;
  label: string;
  /** Secondary text drawn muted after the label, such as a tool's selector. */
  detail?: string;
  /** "muted" marks a note that records an absence, such as a capture with no hierarchy. */
  tone?: 'error' | 'muted';
  badges: NoteBadge[];
  fields: NoteField[];
  body?: string;
  /** Unset bodies are highlighted as JSON when they read as JSON, else left plain. */
  bodyLang?: 'json' | 'yaml';
  /** How full the lane's shared scale this note's reading is, 0 to 1, drawn as a bar behind the row. */
  meter?: number;
  entries?: NoteEntry[];
  /** Headings for the entries' two columns, when their names aren't plain from the rows. */
  entryColumns?: [string, string];
  /** The note's own screenshot (a key into the payload's shots), and when it was captured. */
  shot?: string;
  shotT?: number;
  /** Labels folded into one note because they fired in the same instant. */
  members?: string[];
}

export interface StreamLane {
  id: string;
  name: string;
  total: number;
  /** Notes bucketed by step band, in time order. */
  bands: StreamNote[][];
}

export type StepKind = 'trailhead' | 'step' | 'heal' | 'teardown';

export interface StreamStep {
  kind: StepKind;
  number?: number;
  title: string;
  startMs: number;
  endMs: number;
  /** "recovered" marks a recorded attempt that failed and was self-healed. */
  status: 'ok' | 'failed' | 'recovered';
  shot?: string;
}

export interface StreamShot {
  t: number;
  /** A key into the payload's shots. */
  file: string;
  label: string;
  /** Width over height of the screen it shows, when the capture said; devices in one run can differ. */
  aspect?: number;
}

export type DiagnosisStepKind = 'trailhead' | 'step' | 'teardown' | 'end';

export interface DiagnosisStep {
  kind: DiagnosisStepKind;
  number?: number;
  title: string;
  bandIndex: number;
}

export interface DiagnosisProblem {
  step?: DiagnosisStep;
  summary: string;
  /** The tool the step called, when the failure came from a tool nested in it. */
  via?: string;
  /** How the step's self-heal ended, when it ran and failed too. */
  note?: string;
  /** The recorded tool a self-heal tried to get past, which its error text leaves unnamed. */
  tool?: string;
  detail?: string;
}

/** What went wrong in a failed run. A passed run has none: its self-heals show on the board. */
export interface RunDiagnosis {
  problems: DiagnosisProblem[];
  /** Earlier problems left off past the cap; the step list still marks them. */
  hidden: number;
}

export interface StreamsModel {
  startMs: number;
  steps: StreamStep[];
  lanes: StreamLane[];
  shots: StreamShot[];
  /** Screen width over height, for stable thumbnail boxes. */
  aspect: number;
  diagnosis?: RunDiagnosis;
}

export const TOOLS_LANE = 'tools';
export const LLM_LANE = 'llm';
/** Lanes shown until the reader picks; every other stream waits in the "+" menu. */
export const DEFAULT_VISIBLE: readonly string[] = [TOOLS_LANE, LLM_LANE, 'network'];
const MAX_FIELDS = 12;
const MIN_BURST = 3;
const DEFAULT_ASPECT = 9 / 19.5;

// ─── Lane identity ────────────────────────────────────────────────────────────────────────────

const KNOWN_STREAMS: Array<[pattern: RegExp, key: string, name: string]> = [
  [/^memory$/i, 'memory', 'Memory'],
  [/visible-strings/i, 'visible-strings', 'Visible strings'],
  [/capture-notices/i, 'capture-notices', 'Capture notices'],
];

/** `network`, a plugin name ending in `.network`, and device-scoped `network.<device>` streams. */
const NETWORK_STREAM = /^(?:.*\.)?network(?:\.(.+))?$/i;
export const isNetworkLane = (id: string) => id === 'network' || id.startsWith('network.');

/** Any other stream becomes a lane named for its last dotted segment, in words. */
export function laneIdentity(streamName: string): { id: string; name: string } {
  const network = NETWORK_STREAM.exec(streamName);
  if (network) return network[1] ? { id: `network.${network[1]}`, name: `Network (${network[1]})` } : { id: 'network', name: 'Network' };
  for (const [pattern, id, name] of KNOWN_STREAMS) {
    if (pattern.test(streamName)) return { id, name };
  }
  const last = streamName.split('.').filter(Boolean).pop() ?? streamName;
  const words = last.replace(/([a-z])([A-Z])/g, '$1 $2').split(/[-_\s]+/).filter(Boolean);
  const name = words.join(' ').toLowerCase();
  return { id: streamName, name: name ? name[0].toUpperCase() + name.slice(1) : streamName };
}

// ─── Failure wording ──────────────────────────────────────────────────────────────────────────

const MAX_SUMMARY = 200;
const MAX_TARGET = 48;

/** A scripted tool's rethrow of the failure of a tool it called, from the in-process wrapper and the
 * SDK, and the daemon's dispatch around it. Group 2 names the tool called. */
export const NESTED_CALL = /^(?:(?:Host-local|On-device) tool execution failed:\s*)?(?:Error:\s*)?(?:trailblaze\.)?client\.callTool\((['"])(.*?)\1\) (?:tool )?failed:\s*/;

/** Layers Trailblaze wraps around the message that says what went wrong: the tool result type, the
 * recording runner, the agent, nested tool calls, and the runners' `Failed action: <a>. Error: <m>`. */
const WRAPPERS = [
  /^ExceptionThrown:\s*/,
  NESTED_CALL,
  /^Recording failed at [^:]+:\s*/,
  /^Koog strategy graph threw (?:at node '[^']*' |before any node reported a failure, )after \d+ LLM call\(s\):\s*/,
  /^Failed action: .*?\. Error:\s*/,
  /^(?:[\w$]+\.)*\w*(?:Exception|Error):\s*/,
];

const QUOTED_KINDS = new Set(['', 'desc', 'a11y', 'hint', 'labeledBy', 'state', 'pane', 'role', 'error']);
const KIND_ORDER = ['', 'desc', 'a11y', 'hint', 'labeledBy', 'id', 'testTag', 'tag', 'uid', 'state', 'pane', 'role', 'error', 'class'];

const calls = (count: string): string => `${count} LLM call${count === '1' ? '' : 's'}`;

/** A regex's first top-level branch, and its body when one group wraps that branch whole. `\Q...\E`
 * literals and character classes don't count. */
function firstBranch(regex: string): { branch: string; body?: string } {
  let depth = 0;
  let firstClose = -1;
  let stop = regex.length;
  for (let index = 0; index < regex.length; index++) {
    const char = regex[index];
    if (char === '\\') {
      const skip = regex[index + 1] === 'Q' ? regex.indexOf('\\E', index + 2) : index;
      index = skip < 0 ? regex.length : skip + 1;
    } else if (char === '[') {
      const close = regex.indexOf(']', index + 1);
      if (close < 0) break;
      index = close;
    } else if (char === '(') depth++;
    else if (char === ')' && --depth === 0 && firstClose < 0) firstClose = index;
    else if (char === '|' && depth === 0) {
      stop = index;
      break;
    }
  }
  const branch = regex.slice(0, stop);
  return branch.startsWith('(') && firstClose === stop - 1 ? { branch, body: branch.slice(branch.startsWith('(?:') ? 3 : 1, -1) } : { branch };
}

/** A selector value as a reader would name the element: regex anchors and wildcards dropped, and an
 * alternation read as its first, usually human, option. */
function readableValue(value: string): string {
  let regex = value.replace(/^\(\?i\)/, '');
  for (;;) {
    const { branch, body } = firstBranch(regex.replace(/^\^/, '').replace(/(?<!\\)\$$/, '').replace(/^\.\*/, '').replace(/\.\*$/, ''));
    regex = body ?? branch;
    if (body === undefined) break;
  }
  const plain = regex.replace(/\\Q([\s\S]*?)(?:\\E|$)|\\(.)/g, (_, literal: string | undefined, escaped: string | undefined) => literal ?? escaped ?? '').trim();
  return clip(plain || value, MAX_TARGET);
}

/** One element from a selector description such as `id~"note_text_field"` or `"^Review$"`. */
function selectorTarget(description: string): string {
  const parts = [...description.matchAll(/(?:^|,\s*)(?:(\w+)~)?"(.*?)"(?=\s*(?:,|$))/g)].map((match) => ({ kind: match[1] ?? '', value: match[2] }));
  const rank = (kind: string): number => {
    const index = KIND_ORDER.indexOf(kind);
    return index < 0 ? KIND_ORDER.length : index;
  };
  const best = parts.sort((a, b) => rank(a.kind) - rank(b.kind))[0];
  if (!best) return clip(description.trim(), MAX_TARGET);
  const value = readableValue(best.value);
  return QUOTED_KINDS.has(best.kind) ? `"${value}"` : value;
}

/** Maestro's own `Text matching regex: X, Id matching regex: Y` description. */
function maestroTarget(description: string): string {
  const text = /Text matching regex: (.+?)(?:, [A-Z][\w ]*: |$)/.exec(description);
  if (text) return `"${readableValue(text[1])}"`;
  const id = /Id matching regex: (.+?)(?:, [A-Z][\w ]*: |$)/.exec(description);
  return id ? readableValue(id[1]) : clip(description.trim(), MAX_TARGET);
}

const ERROR_RULES: Array<[RegExp, (match: RegExpExecArray) => string]> = [
  [/Max LLM calls limit (?:of (\d+) reached|reached \((\d+)\))/, (match) => `Gave up after ${calls(match[1] ?? match[2])}`],
  [/^Koog strategy graph ended without reporting a status after (\d+) LLM call/, (match) => `Agent stopped without a result after ${calls(match[1])}`],
  [/^Element not found for selector: (.+?)(?: after \d+ms)?(?:\. No fallback|\.?$)/m, (match) => `${selectorTarget(match[1])} not found`],
  [/^Assert visible failed: (.+?) not found within \d+ms/, (match) => `${selectorTarget(match[1])} not visible`],
  [/^Assert not visible failed: (.+?) (?:(?:is )?still visible after|— )/, (match) => `${selectorTarget(match[1])} still visible`],
  [/^(?:Scroll until visible|Copy text) failed: (.+?) not found within \d+ms/, (match) => `${selectorTarget(match[1])} not found`],
  [/^TrailblazeNode selector found no match after \d+ms: (.+)$/m, (match) => `${selectorTarget(match[1])} not found`],
  [/^Element not found: (.+)$/m, (match) => `${maestroTarget(match[1])} not found`],
  [/Timed out waiting for (\d+) ms/, (match) => `Timed out after ${formatMs(Number(match[1]))}`],
];

function firstLine(text: string): string {
  return (text.split('\n').find((line) => line.trim() !== '') ?? '').trim();
}

function cause(text: string): string {
  for (const [pattern, describe] of ERROR_RULES) {
    const match = pattern.exec(text);
    if (match) return describe(match);
  }
  const line = firstLine(text);
  return /^(.*?[^.])\.(?:\s|$)/.exec(line)?.[1] ?? line;
}

/** Clipped at a word break, so a code or trace id is never cut in half. */
function clipWords(text: string, max: number): string {
  if (text.length <= max) return text;
  const cut = text.lastIndexOf(' ', max - 1);
  return `${text.slice(0, cut > max / 2 ? cut : max - 1)}…`;
}

/** What went wrong, in a line or two a reader can take in at a glance, and the tool the step called
 * when the failure came from a tool nested in it. The raw text stays behind the row's Details. */
export function describeError(error: string): { summary: string; via?: string } {
  let text = error.trim();
  let via: string | undefined;
  for (let unwrapped = true; unwrapped;) {
    unwrapped = false;
    for (const wrapper of WRAPPERS) {
      via ??= wrapper === NESTED_CALL ? NESTED_CALL.exec(text)?.[2] : undefined;
      const next = text.replace(wrapper, '');
      if (next !== text) {
        text = next;
        unwrapped = true;
      }
    }
  }
  // A newer daemon has already unwrapped the calls into a `  via a → b` line.
  via ??= /^ {2}via (\S+)/m.exec(text)?.[1];
  const summary = clipWords(cause(text), MAX_SUMMARY);
  const named = via !== undefined && summary.startsWith(via) && !/\w/.test(summary.charAt(via.length));
  return via && summary && !named ? { summary, via } : { summary };
}

// ─── Tool and LLM notes ───────────────────────────────────────────────────────────────────────

interface Action {
  verb: string;
  value?: string;
}

interface TraceEntry {
  label: string;
  tool?: string;
  note?: string;
  ms: number;
  ts: number;
  ok: boolean;
  err?: string;
  screenshotFile?: string;
  objective: boolean;
  trailhead: boolean;
  teardown: boolean;
  selfHeal: boolean;
  selfHealTool?: string;
  selfHealError?: string;
  terminal: boolean;
  /** Logged once the run finished, after any teardown, though it belongs to the trail. */
  endOfRun: boolean;
  shotTs?: number;
  shotEndTs?: number;
  args?: string;
  viewport?: { w: number; h: number };
  llm?: number;
  decision: boolean;
  device?: string;
  /** `ms` is absent for a dispatch that was declared but never ran. */
  children: { label: string; ts?: number; ms?: number; screenshotFile?: string; shotEndTs?: number }[];
}

export function llmNote(entry: TraceEntry, row: Json | undefined): StreamNote {
  const responses = Array.isArray(row?.response) ? row.response.filter(isRecord) : [];
  const callList = responses.flatMap((response) => {
    const tool = str(response.tool);
    if (!tool) return [];
    const raw = str(response.args);
    const parsed = raw === undefined ? undefined : parseJson(raw);
    return [{ tool, raw, args: isRecord(parsed) ? parsed : undefined, reasoning: str(response.reasoning) }];
  });
  const chosen = callList[0] ? action(callList[0].tool, callList[0].args) : undefined;
  const fields: NoteField[] = [];
  const goal = str(row?.instructions);
  if (goal) fields.push({ k: 'Goal', v: clip(goal, 600) });
  if (callList.length > 0) fields.push({ k: 'Tool', v: callList.map((call) => call.tool).join(', ') });
  const model = str(row?.model);
  if (model) fields.push({ k: 'Model', v: model });
  const input = num(row?.inputTokens);
  const output = num(row?.outputTokens);
  if (input !== undefined || output !== undefined) {
    fields.push({ k: 'Tokens', v: `${(input ?? 0).toLocaleString('en-US')} in · ${(output ?? 0).toLocaleString('en-US')} out` });
  }
  const cost = num(row?.totalCost);
  if (cost !== undefined) fields.push({ k: 'Cost', v: `$${cost.toFixed(4)}` });
  fields.push({ k: 'Duration', v: formatMs(entry.ms || num(row?.durationMs) || 0) });
  const outcome = str(row?.outcome);
  if (outcome) fields.push({ k: 'Outcome', v: clip(outcome, 600) });
  if (entry.err) fields.push({ k: 'Error', v: clip(entry.err, 600) });
  const reasoning = callList.flatMap((call) => str(call.args?.reasoning) ?? call.reasoning ?? []);
  if (reasoning.length > 0) fields.push({ k: 'Reasoning', v: clip(reasoning.join('\n\n'), 600) });
  const body = callList.flatMap((call) => (call.args ? JSON.stringify(call.args, null, 2) : call.raw) ?? []).join('\n\n');
  return {
    t: entry.ts,
    label: clip(entry.decision ? 'decision' : chosen?.verb ?? entry.label, MAX_LABEL),
    ...(chosen?.value ? { detail: clip(chosen.value, MAX_LABEL) } : outcome && entry.decision ? { detail: clip(outcome, MAX_LABEL) } : {}),
    ...(entry.ok ? {} : { tone: 'error' as const }),
    badges: [],
    fields,
    ...(body ? { body: clip(body, MAX_BODY), bodyLang: 'json' as const } : {}),
  };
}

export function toolNote(entry: TraceEntry, shot: StreamShot | undefined): StreamNote {
  const args = toolArgs(entry.args);
  const { verb, value } = action(entry.label, args);
  const detail = value ?? (entry.tool && summaryValue(entry.tool));
  const target = (args && selectorOf(entry.label, args)) ?? entry.tool;
  const fields: NoteField[] = [{ k: 'Tool', v: entry.label }];
  if (target) fields.push({ k: 'Target', v: clip(target, 600) });
  if (entry.note) fields.push({ k: 'Reason', v: clip(entry.note, 600) });
  fields.push({ k: 'Duration', v: formatMs(entry.ms) });
  if (entry.device) fields.push({ k: 'Device', v: entry.device });
  if (entry.err) fields.push({ k: 'Error', v: clip(entry.err, 600) });
  if (entry.children.length > 0) fields.push({ k: 'Ran', v: clip(entry.children.map((child) => child.label).join(', '), 600) });
  return {
    t: entry.ts,
    label: clip(verb, MAX_LABEL),
    ...(detail ? { detail: clip(detail, MAX_LABEL) } : {}),
    ...(entry.ok ? {} : { tone: 'error' as const }),
    badges: [],
    fields,
    ...(entry.args ? { body: clip(entry.args, MAX_BODY), bodyLang: 'yaml' as const } : {}),
    ...(shot ? { shot: shot.file, shotT: shot.t } : {}),
  };
}

/** The arguments of a `- toolName:` args block, or undefined when it doesn't read. */
function toolArgs(yaml: string | undefined): Json | undefined {
  if (!yaml) return undefined;
  let parsed: unknown;
  try {
    parsed = readArgsYaml(yaml);
  } catch {
    return undefined;
  }
  const call = Array.isArray(parsed) && isRecord(parsed[0]) ? Object.values(parsed[0])[0] : undefined;
  return isRecord(call) ? call : undefined;
}

const KEY = /^([^\s":][^:]*):(?: (.*))?$/;

/** Reads the YAML a trace row's `args` carries: block maps and lists, plain or double-quoted scalars,
 * and `[]` / `{}`. A full YAML parser would be far heavier than this one shape needs, and anything
 * outside it throws so the row falls back to the trace's own summary. */
export function readArgsYaml(text: string): unknown {
  const lines = text.split('\n').filter((line) => line.trim() !== '').map((line) => {
    const body = line.trimStart();
    return { indent: line.length - body.length, body };
  });
  if (lines.length === 0) return null;
  let at = 0;
  const block = (indent: number): unknown => {
    const list = lines[at].body.startsWith('- ');
    const items: unknown[] = [];
    const map: Json = {};
    while (at < lines.length && lines[at].indent === indent) {
      const line = lines[at];
      if (list !== line.body.startsWith('- ')) throw new Error('mixed block');
      if (list) {
        const item = line.body.slice(2);
        if (KEY.test(item) || item.startsWith('- ')) {
          lines[at] = { indent: indent + 2, body: item };
          items.push(block(indent + 2));
        } else {
          items.push(yamlScalar(item));
          at++;
        }
        continue;
      }
      const [, key, value] = KEY.exec(line.body) ?? [];
      if (key === undefined) throw new Error('not a key');
      at++;
      if (value !== undefined) map[key] = yamlScalar(value);
      else map[key] = at < lines.length && lines[at].indent > indent ? block(lines[at].indent) : null;
    }
    if (at < lines.length && lines[at].indent > indent) throw new Error('stray indent');
    return list ? items : map;
  };
  const value = block(lines[0].indent);
  if (at < lines.length) throw new Error('trailing lines');
  return value;
}

function yamlScalar(text: string): unknown {
  const quotedText = /^"((?:[^"\\]|\\.)*)"$/.exec(text);
  if (quotedText) return quotedText[1].replace(/\\(.)/g, (_, char: string) => (char === 'n' ? '\n' : char === 't' ? '\t' : char));
  if (text === '[]') return [];
  if (text === '{}') return {};
  if (text === '' || text === 'null' || text === '~') return null;
  if (text === 'true' || text === 'false') return text === 'true';
  if (/^[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[-+]?\d+)?$/i.test(text)) return Number(text);
  if (/^["'&*!|>%@`{[]/.test(text)) throw new Error('unsupported scalar');
  return text;
}

const DIALECTS = ['androidAccessibility', 'androidView', 'androidMaestro', 'web', 'compose', 'iosMaestro', 'iosAxe'];
const ROW_FIELDS: Array<[key: string, prefix: string]> = [
  ['textRegex', ''],
  ['text', ''],
  ['labelRegex', ''],
  ['ariaNameRegex', ''],
  ['accessibilityTextRegex', ''],
  ['contentDescriptionRegex', 'desc '],
  ['hintTextRegex', 'hint '],
  ['resourceIdRegex', 'id '],
  ['idRegex', 'id '],
  ['uniqueId', 'id '],
  ['dataTestId', 'testid '],
  ['cssSelector', 'css '],
];

const dialect = (selector: Json): Json => {
  for (const key of DIALECTS) {
    const match = selector[key];
    if (isRecord(match)) return match;
  }
  return selector;
};

/** Trailblaze matches selector text against the whole string, so the anchors add nothing but noise;
 * an escaped `\$` is a literal and stays. */
function unanchor(regex: string): string {
  const start = regex.startsWith('^') ? 1 : 0;
  const end = /(?:^|[^\\])(?:\\\\)*\$$/.test(regex) ? regex.length - 1 : regex.length;
  return regex.slice(start, Math.max(start, end));
}

const regexText = (value: unknown): string | undefined => {
  const text = str(value);
  return text === undefined ? undefined : unanchor(text);
};
const quoted = (value: unknown): string | undefined => {
  const text = scalarText(value);
  return text === undefined ? undefined : `"${text}"`;
};
const words = (value: unknown): string | undefined =>
  Array.isArray(value) ? value.map(scalarText).filter((word) => word !== undefined).join(' ') || undefined : undefined;

/** The selector's most telling value for a one-line row. */
function sel(selector: unknown): string | undefined {
  if (!isRecord(selector)) return undefined;
  const match = dialect(selector);
  const field = ROW_FIELDS.find(([key]) => str(match[key]) !== undefined);
  const value = field ? `${field[1]}${unanchor(String(match[field[0]]))}` : sel(selector.containsChild);
  const index = scalarText(selector.index);
  return value === undefined || index === undefined ? value : `${value} #${index}`;
}

const RELATIONS: Array<[key: string, text: string]> = [
  ['below', 'below'],
  ['above', 'above'],
  ['leftOf', 'left of'],
  ['rightOf', 'right of'],
  ['childOf', 'child of'],
  ['containsChild', 'contains child'],
];
const RELATION_KEYS = new Set([...RELATIONS.map(([key]) => key), 'containsDescendants', 'index']);
const BARE_FIELDS = new Set(['textRegex', 'text', 'labelRegex', 'ariaNameRegex']);
const FIELD_PREFIX: Record<string, string> = {
  resourceIdRegex: 'id~',
  idRegex: 'id~',
  contentDescriptionRegex: 'desc~',
  hintTextRegex: 'hint~',
  accessibilityTextRegex: 'a11y~',
  classNameRegex: 'class~',
  composeTestTagRegex: 'testTag~',
  uniqueId: 'uid=',
};

/** The whole selector, worded like `TrailblazeNodeSelector.description()`. */
function describeSelector(selector: Json): string {
  const match = dialect(selector);
  const parts = Object.entries(match).flatMap(([key, value]): string[] => {
    if (match === selector && RELATION_KEYS.has(key)) return [];
    if (typeof value === 'boolean') {
      const name = key.replace(/^is([A-Z])/, (_, first: string) => first.toLowerCase());
      return [value ? name : `not ${name}`];
    }
    const text = scalarText(value);
    if (text === undefined) return [];
    if (BARE_FIELDS.has(key)) return [`"${text}"`];
    const prefix = FIELD_PREFIX[key];
    if (prefix) return [`${prefix}"${text}"`];
    return [key.endsWith('Regex') ? `${key.slice(0, -5)}~"${text}"` : `${key}=${text}`];
  });
  for (const [key, text] of RELATIONS) {
    const related = selector[key];
    if (isRecord(related)) parts.push(`${text} ${describeSelector(related)}`);
  }
  if (Array.isArray(selector.containsDescendants)) {
    parts.push(`contains descendants [${selector.containsDescendants.filter(isRecord).map(describeSelector).join(', ')}]`);
  }
  const index = scalarText(selector.index);
  if (index !== undefined) parts.push(`index=${index}`);
  return parts.join(', ');
}

function selectorOf(tool: string, args: Json): string | undefined {
  if (tool === 'findSelectorMatches' && Array.isArray(args.selectors)) {
    return args.selectors.filter(isRecord).map(describeSelector).join(' | ') || undefined;
  }
  if (tool === 'block_runIf' && isRecord(args.condition)) {
    const condition = args.condition;
    const inner = isRecord(condition.tool) ? Object.values(condition.tool)[0] : undefined;
    const selector = condition.selector ?? (isRecord(inner) ? inner.nodeSelector ?? inner.selector : undefined);
    return isRecord(selector) ? describeSelector(selector) || undefined : undefined;
  }
  const selector = args.nodeSelector ?? args.selector;
  return isRecord(selector) ? describeSelector(selector) || undefined : undefined;
}

const VERBS: Record<string, [verb: string, value: (args: Json) => string | undefined]> = {
  tapOnElementBySelector: ['tap', (args) => sel(args.nodeSelector)],
  tapOn: ['tap', (args) => sel(args.selector)],
  tapOnPoint: ['tap', (args) => (args.x !== undefined && args.y !== undefined ? `(${scalarText(args.x)}, ${scalarText(args.y)})` : undefined)],
  assertVisibleBySelector: ['assert', (args) => sel(args.nodeSelector)],
  assertVisible: ['assert', (args) => str(args.expectedText)],
  assertNotVisibleBySelector: ['assert not', (args) => sel(args.nodeSelector)],
  assertNotVisibleWithText: ['assert not', (args) => str(args.text)],
  findSelectorMatches: ['find', (args) => (Array.isArray(args.selectors) ? args.selectors.map(sel).filter(Boolean).join(' | ') || undefined : undefined)],
  inputText: ['type', (args) => quoted(args.text)],
  type: ['type', (args) => quoted(args.text)],
  scrollUntilTextIsVisible: ['scroll to', scrollTarget],
  swipe: ['swipe', (args) => str(args.direction)?.toLowerCase()],
  pressKey: ['press', (args) => str(args.keyCode)],
  hideKeyboard: ['hide keyboard', () => undefined],
  clearText: ['clear', () => undefined],
  openUrl: ['open', (args) => str(args.url)],
  block_runIf: ['if', runIfCondition],
  block_dismissIfPresent: ['dismiss', (args) => regexText(args.dialogTextRegex)],
  android_adbShell: ['adb', (args) => words(args.command)],
  exec: ['exec', (args) => words(args.argv)],
  android_sendBroadcast: ['broadcast', (args) => str(args.action)?.split('.').pop()],
  mobile_setClipboard: ['copy', (args) => quoted(args.text)],
  takeSnapshot: ['snapshot', (args) => str(args.screenName)],
  objectiveStatus: ['status', (args) => str(args.status)],
};

/** A tool call worded as the verb and the value it exercises. */
function action(tool: string, args: Json | undefined): Action {
  if (tool === 'mobile_maestro') return maestro(args);
  const known = VERBS[tool];
  if (known) return { verb: known[0], value: args && known[1](args) };
  return { verb: fallbackVerb(tool), value: args && firstValue(args) };
}

/** `textRegex` overrides `text`, and `id` stands in for both. */
function scrollTarget(args: Json): string | undefined {
  const id = str(args.id);
  return quoted(regexText(args.textRegex) ?? str(args.text)) ?? (id === undefined ? undefined : `id ${id}`);
}

function runIfCondition(args: Json): string | undefined {
  const condition = isRecord(args.condition) ? args.condition : {};
  let negated = args.negate === true;
  let value = sel(condition.selector);
  const [tool, innerArgs] = isRecord(condition.tool) ? Object.entries(condition.tool)[0] ?? [] : [];
  if (value === undefined && tool) {
    const inner = action(tool, isRecord(innerArgs) ? innerArgs : undefined);
    if (inner.verb === 'assert' || inner.verb === 'assert not') {
      if (inner.verb === 'assert not') negated = !negated;
      value = inner.value;
    } else {
      value = [inner.verb, inner.value].filter(Boolean).join(' ');
    }
  }
  return value === undefined ? undefined : `${negated ? 'not ' : ''}${value}`;
}

function maestro(args: Json | undefined): Action {
  const steps = Array.isArray(args?.commands) ? args.commands.map(maestroCommand) : [];
  const [first, ...rest] = steps;
  if (!first) return { verb: 'maestro' };
  const value = [first.value, ...rest.map((step) => [step.verb, step.value].filter(Boolean).join(' '))].filter(Boolean).join(' · ');
  return { verb: first.verb, ...(value ? { value } : {}) };
}

function maestroCommand(command: unknown): Action {
  if (!isRecord(command)) return { verb: fallbackVerb(scalarText(command) ?? '') };
  const [name = '', value] = Object.entries(command)[0] ?? [];
  const element = (target: unknown): string | undefined => {
    if (!isRecord(target)) return regexText(target);
    const id = regexText(target.id);
    return regexText(target.text) ?? (id === undefined ? str(target.point) : `id ${id}`);
  };
  const options = isRecord(value) ? value : {};
  switch (name) {
    case 'extendedWaitUntil':
      return options.notVisible !== undefined ? { verb: 'wait not', value: element(options.notVisible) } : { verb: 'wait', value: element(options.visible) };
    case 'inputText':
      return { verb: 'type', value: quoted(value) };
    case 'tapOn':
      return { verb: 'tap', value: element(value) };
    case 'launchApp':
      return { verb: 'launch', value: str(options.appId) ?? str(value) };
    case 'openLink':
      return { verb: 'open', value: str(value) };
    default:
      return { verb: fallbackVerb(name), value: scalarText(value) };
  }
}

/** A tool's verb alone, for lanes that name the tool but don't carry its arguments. */
export function toolVerb(tool: string): string {
  return tool === 'mobile_maestro' ? 'maestro' : VERBS[tool]?.[0] ?? fallbackVerb(tool);
}

/** `android_adbShell` reads as "adb shell": the toolset prefix and platform go, camel case spaces out. */
function fallbackVerb(tool: string): string {
  const verb = tool
    .replace(/^[A-Za-z]+_((ios|android)_)?/, '')
    .replace(/(OnElementBySelector|BySelector|WithText|IfEnabled|IfShown)$/, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase();
  return verb || tool;
}

const QUIET_ARGS = new Set(['reason', 'reasoning', 'ref', 'timeoutMs', 'hideKeyboardAfter']);

function firstValue(args: Json): string | undefined {
  for (const [key, value] of Object.entries(args)) {
    if (QUIET_ARGS.has(key)) continue;
    if (typeof value === 'number' || str(value) !== undefined) return String(value);
    const list = words(value);
    if (list) return list;
  }
  return undefined;
}

/** The trace's own `key: value` selector summary, worded like a row value. */
function summaryValue(tool: string): string {
  const [, key, value] = /^(\w+): ([\s\S]*)$/.exec(tool) ?? [];
  const field = ROW_FIELDS.find(([name]) => name === key);
  return field && value !== undefined ? `${field[1]}${unanchor(value.trim())}` : tool;
}

// ─── Event-stream lanes ───────────────────────────────────────────────────────────────────────

/** How a lane folds same-instant bursts: `any` folds every burst, `label` only runs of one label, and
 * `none` keeps every note. */
export type FoldPolicy = 'any' | 'label' | 'none';

interface BuiltLane {
  notes: StreamNote[];
  /** What the lane header counts, when that isn't one per note. */
  total?: number;
  fold?: FoldPolicy;
}

const LABEL_KEYS = ['name', 'eventName', 'event_name', 'event', 'eventValue', 'message', 'title', 'type', 'kind', 'reason'];
const COLUMN_LABEL_KEYS = ['Event', 'Message', 'Name', 'Screen', 'Title', 'Type'];

export function eventLabel(value: unknown): string | undefined {
  if (typeof value === 'string') return str(value);
  if (Array.isArray(value)) return `${value.length} ${value.length === 1 ? 'item' : 'items'}`;
  if (!isRecord(value)) return scalarText(value);
  // Table-shaped events carry their cells under `columnItems`; the row's name is its event or message.
  const columns = isRecord(value.columnItems) ? value.columnItems : undefined;
  if (columns) {
    for (const key of COLUMN_LABEL_KEYS) {
      const label = str(columns[key]);
      if (label) return label;
    }
  }
  for (const key of LABEL_KEYS) {
    const label = str(value[key]);
    if (label) return label;
  }
  if (isRecord(value.eventType)) return str(value.eventType.type);
  return undefined;
}

/** Scalar entries become fields; nested documents (and JSON embedded in a string) go to the body
 * rather than showing as an escaped wall of text. */
function eventDetail(value: unknown, label: string): { fields: NoteField[]; body?: string } {
  if (!isRecord(value)) {
    const long = typeof value === 'string' && value.length > MAX_LABEL ? clip(value, MAX_BODY) : undefined;
    return { fields: [], body: Array.isArray(value) ? prettyBody(value) : long };
  }
  const source = isRecord(value.columnItems) ? value.columnItems : value;
  const fields: NoteField[] = [];
  const nested: Json = {};
  for (const [k, raw] of Object.entries(source)) {
    const embedded = typeof raw === 'string' && /^\s*[[{]/.test(raw) ? parseJson(raw) : undefined;
    if (embedded !== undefined || (typeof raw === 'object' && raw !== null)) {
      nested[k] = embedded ?? raw;
      continue;
    }
    const v = scalarText(raw);
    if (v === undefined || v === '' || v === label || fields.length === MAX_FIELDS) continue;
    fields.push({ k, v: clip(v, 600) });
  }
  return { fields, body: Object.keys(nested).length > 0 ? prettyBody(nested) : undefined };
}

function parsedEvent(t: number, d: string): StreamNote {
  const parsed = parseJson(d);
  const value = parsed === undefined ? d : parsed;
  const label = clip(eventLabel(value) ?? d, MAX_LABEL);
  return { t, label, badges: [], ...eventDetail(value, label) };
}

/** A row a stream's formatter already produced. */
function rowNote(row: Json): StreamNote | undefined {
  const t = num(row.t);
  const label = str(row.label);
  if (t === undefined || label === undefined) return undefined;
  const badges = Array.isArray(row.badges)
    ? row.badges.filter(isRecord).flatMap((badge): NoteBadge[] => {
      const text = str(badge.text);
      if (!text) return [];
      return [{ text, ...(badge.tone === 'ok' || badge.tone === 'error' ? { tone: badge.tone } : {}) }];
    })
    : [];
  const fields = Array.isArray(row.fields)
    ? row.fields.filter(isRecord).flatMap((field): NoteField[] => {
      const k = str(field.k);
      const v = scalarText(field.v);
      const href = str(field.href);
      return k && v !== undefined ? [{ k, v: clip(v, 600), ...(href && /^https?:\/\//i.test(href) ? { href } : {}) }] : [];
    })
    : [];
  return {
    t,
    label: clip(label, MAX_LABEL),
    ...(row.tone === 'error' ? { tone: 'error' as const } : {}),
    badges,
    fields,
    body: Array.isArray(row.raw) && row.raw.length > 0 ? prettyBody(row.raw.length === 1 ? row.raw[0] : row.raw) : undefined,
  };
}

// --- network ---

/** One HTTP event read off a stream, whatever the producer's envelope: flat `{phase, method, url}`
 * events, or a single-key enum wrapper (`{request: {_0: {...}}}`) around the same fields. */
interface HttpEvent {
  kind: 'request' | 'response' | 'failed';
  id: string;
  method?: string;
  url?: string;
  urlPath?: string;
  status?: number;
  durationMs?: number;
  value: Json;
}

function unwrapEnum(value: unknown): { tag?: string; body: Json } | undefined {
  if (!isRecord(value)) return undefined;
  const keys = Object.keys(value);
  if (keys.length === 1 && isRecord(value[keys[0]])) {
    const inner = value[keys[0]] as Json;
    const body = isRecord(inner._0) ? inner._0 : inner;
    return { tag: keys[0], body };
  }
  return { body: value };
}

function httpEvent(raw: unknown): HttpEvent | undefined {
  const wrapped = unwrapEnum(raw);
  if (!wrapped) return undefined;
  const { tag, body } = wrapped;
  const phase = str(body.phase);
  const id = str(body.id) ?? str(body.requestID) ?? str(body.requestId);
  if (!id) return undefined;
  const status = num(body.statusCode);
  const isResponse = phase === 'RESPONSE_END' || phase === 'FAILED' || /response|finish|end|fail/i.test(tag ?? '') || (!phase && !tag && status !== undefined);
  if (isResponse) {
    const failed = phase === 'FAILED' || /fail|error/i.test(tag ?? '');
    return { kind: failed ? 'failed' : 'response', id, status, durationMs: num(body.durationMs), value: body };
  }
  const method = str(body.method) ?? str(body.httpMethod);
  const url = str(body.url);
  if (!method && !url) return undefined;
  return { kind: 'request', id, method, url, urlPath: str(body.urlPath), value: body };
}

function networkCallNote(t: number, request: HttpEvent | undefined, end: HttpEvent | undefined, device: string | undefined): StreamNote {
  const url = request?.url;
  let parsed: URL | undefined;
  try {
    parsed = url ? new URL(url) : undefined;
  } catch {
    parsed = undefined;
  }
  const endpoint = request?.urlPath ?? (parsed ? `${parsed.pathname}${parsed.search}` : url) ?? 'Response without request';
  const status = end?.status;
  const failed = end?.kind === 'failed' || (status !== undefined && status >= 400);
  const badges: NoteBadge[] = [];
  if (end?.kind === 'failed') badges.push({ text: 'FAILED', tone: 'error' });
  else if (status !== undefined) badges.push({ text: String(status), tone: status >= 400 ? 'error' : 'ok' });
  const ms = end?.durationMs;
  if (ms !== undefined && ms >= 0) badges.push({ text: `${Math.round(ms)}ms` });
  const fields: NoteField[] = [];
  if (url) fields.push({ k: 'URL', v: clip(url, 600) });
  if (device) fields.push({ k: 'Device', v: device });
  const bodyParts = [request?.value, end?.value].filter((value): value is Json => value !== undefined);
  return {
    t,
    label: clip(`${request?.method ?? ''} ${endpoint}`.trim(), MAX_LABEL),
    ...(failed ? { tone: 'error' as const } : {}),
    badges,
    fields,
    ...(bodyParts.length > 0 ? { body: prettyBody(bodyParts.length === 1 ? bodyParts[0] : { request: bodyParts[0], response: bodyParts[1] }) } : {}),
  };
}

/** A formatter's network rows keep both captured events in `raw`. */
function networkRowNote(row: Json, device: string | undefined): StreamNote | undefined {
  const t = num(row.t);
  const raw = Array.isArray(row.raw) ? row.raw.map(httpEvent).filter((event): event is HttpEvent => event !== undefined) : [];
  const request = raw.find((event) => event.kind === 'request');
  if (t === undefined || !request) return rowNote(row);
  return networkCallNote(t, request, raw.find((event) => event !== request), device);
}

/** Capture writes each request and its response as separate lines, keyed by id. Pair them here and
 * pin the note to the request; a stream that doesn't read as HTTP returns undefined. */
function networkNotes(events: Json[], device: string | undefined): StreamNote[] | undefined {
  type Call = { t: number; request?: HttpEvent; end?: HttpEvent };
  const callList: Call[] = [];
  const latest = new Map<string, Call>();
  for (const event of events) {
    const t = num(event.t);
    const value = typeof event.d === 'string' ? parseJson(event.d) : undefined;
    const http = value === undefined ? undefined : httpEvent(value);
    if (t === undefined || !http) return undefined;
    let call = latest.get(http.id);
    // Capture reuses ids across calls, so a second request or end opens a new one.
    if (!call || (http.kind === 'request' ? call.request : call.end)) {
      call = { t };
      callList.push(call);
      latest.set(http.id, call);
    }
    if (http.kind === 'request') {
      call.request = http;
      call.t = t;
    } else {
      call.end = http;
    }
  }
  return callList.length > 0 ? callList.map((call) => networkCallNote(call.t, call.request, call.end, device)) : undefined;
}

// --- memory ---

const SCALE_STEP_MB = 64;
const ANDROID_BEFORE_NOTE = 'Android reads Before after the tool has started, so it can run high or low.';

export function megabytes(kb: number): string {
  return `${(kb / 1024).toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} MB`;
}

function change(kb: number): string {
  const text = megabytes(Math.abs(kb));
  return text === '0.0 MB' ? '±0' : `${kb > 0 ? '+' : '-'}${text}`;
}

/** iOS reports the app's physical footprint, Android its Java heap. */
const usedKb = (row: Json): number | undefined => num(row.footprintKb) ?? num(row.heapUsedKb);

function deviceFields(row: Json): NoteField[] {
  const fields: NoteField[] = [];
  const limit = num(row.heapLimitKb);
  if (limit !== undefined) fields.push({ k: 'Heap limit', v: megabytes(limit) });
  const free = num(row.deviceAvailableKb);
  if (free !== undefined) fields.push({ k: 'Device free', v: megabytes(free) });
  return fields;
}

function readingFields(row: Json): NoteField[] {
  const fields: NoteField[] = [];
  const readMs = num(row.readMs);
  if (readMs !== undefined) fields.push({ k: 'Read time', v: formatMs(readMs) });
  const source = str(row.source);
  if (source) fields.push({ k: 'Source', v: source });
  return fields;
}

/** A row before the lane's scale is known: `kb` is what its bar draws. */
type Reading = { note: StreamNote; kb?: number; row: Json };

/** Undefined when neither side of the tool carried a figure or a restart: there is nothing to draw. */
function toolReading(t: number, before: Json | undefined, after: Json): Reading | undefined {
  const tool = str(after.tool) ?? str(before?.tool);
  const kb = usedKb(after);
  const beforeKb = before ? usedKb(before) : undefined;
  const restarted = num(before?.pid) !== undefined && num(after.pid) !== undefined && num(before?.pid) !== num(after.pid);
  if (kb === undefined && beforeKb === undefined && !restarted) return undefined;
  const delta = kb !== undefined && beforeKb !== undefined ? kb - beforeKb : undefined;
  const fields: NoteField[] = [];
  if (tool) fields.push({ k: 'Tool', v: tool });
  if (beforeKb !== undefined) fields.push({ k: 'Before', v: megabytes(beforeKb) });
  if (kb !== undefined) fields.push({ k: 'After', v: megabytes(kb) });
  if (delta !== undefined) fields.push({ k: 'Change', v: change(delta) });
  const beforePid = num(before?.pid);
  const afterPid = num(after.pid);
  if (beforePid !== undefined && afterPid !== undefined && beforePid !== afterPid) {
    fields.push({ k: 'App restarted', v: `During the tool, pid ${beforePid} → ${afterPid}` });
  }
  fields.push(...deviceFields(after));
  if (before && num(before.heapUsedKb) !== undefined) fields.push({ k: 'Note', v: ANDROID_BEFORE_NOTE });
  const detail = [delta === undefined ? undefined : change(delta), tool && toolVerb(tool)].filter(Boolean).join(' · ');
  return {
    note: { t, label: kb === undefined ? tool ?? 'Tool' : megabytes(kb), ...(detail ? { detail } : {}), badges: [], fields },
    kb,
    row: after,
  };
}

const RESTARTS: Partial<Record<string, string>> = { process_started: 'App started', process_restarted: 'App restarted' };
const ENDS: Partial<Record<string, string>> = { first: 'first reading', final: 'last reading' };

function soloReading(t: number, reason: string, row: Json): Reading | undefined {
  if (reason === 'process_died') return { note: { t, label: 'App not running', tone: 'error', badges: [], fields: deviceFields(row) }, kb: 0, row };
  const kb = usedKb(row);
  const restart = RESTARTS[reason];
  // The app coming back is news even when its memory couldn't be read.
  if (kb === undefined) {
    if (!restart) return undefined;
    const pid = num(row.pid);
    return { note: { t, label: restart, badges: [], fields: [...(pid !== undefined ? [{ k: 'Pid', v: String(pid) }] : []), ...deviceFields(row)] }, kb, row };
  }
  const fields: NoteField[] = [];
  const deltaKb = reason === 'memory_changed' ? num(row.deltaKb) : undefined;
  if (deltaKb !== undefined) fields.push({ k: 'Change', v: change(deltaKb) });
  // A tool still running when capture stopped has no after reading to fold into.
  const tool = reason === 'before_tool' ? str(row.tool) : undefined;
  if (tool) fields.push({ k: 'Tool', v: tool });
  fields.push(...deviceFields(row));
  const detail = restart
    ? megabytes(kb)
    : ENDS[reason] ?? (tool ? `before ${toolVerb(tool)}` : deltaKb !== undefined ? change(deltaKb) : undefined);
  return { note: { t, label: restart ?? megabytes(kb), ...(detail ? { detail } : {}), badges: [], fields }, kb, row };
}

/** A tool's before and after readings fold into one row at its end, so the lane reads as the app's
 * memory over the run rather than pairs of repeats. Each reading draws as a bar on one scale. */
function memoryLane(events: Json[]): BuiltLane | undefined {
  const readings: Reading[] = [];
  const running = new Map<string, { t: number; row: Json }>();
  const toolKey = (row: Json) => str(row.traceId) ?? `tool:${str(row.tool) ?? ''}`;
  // The sampler's own first read can come before the run's first step, and a note there would stretch
  // that step back to it; a read taken around a tool is inside the run.
  let firstRead: { t: number; row: Json } | undefined;
  let firstToolRead: { t: number; row: Json } | undefined;
  let reads = 0;
  for (const event of events) {
    const t = num(event.t);
    const row = typeof event.d === 'string' ? parseJson(event.d) : undefined;
    const reason = isRecord(row) ? str(row.reason) : undefined;
    if (t === undefined || !isRecord(row) || !reason) continue;
    reads++;
    if (!firstRead) firstRead = { t, row };
    if (!firstToolRead && (reason === 'before_tool' || reason === 'after_tool')) firstToolRead = { t, row };
    if (reason === 'before_tool') {
      running.set(toolKey(row), { t, row });
    } else if (reason === 'after_tool') {
      const reading = toolReading(t, running.get(toolKey(row))?.row, row);
      if (reading) readings.push(reading);
      running.delete(toolKey(row));
    } else {
      // A process transition seen at a tool's after-boundary carries its tool and replaces its after reading.
      if (str(row.traceId) ?? str(row.tool)) running.delete(toolKey(row));
      const reading = soloReading(t, reason, row);
      if (reading) readings.push(reading);
    }
  }
  for (const { t, row } of running.values()) {
    const reading = soloReading(t, 'before_tool', row);
    if (reading) readings.push(reading);
  }
  // The sampler ran but measured nothing, as when it watched an app that wasn't the one running.
  // One row says so, rather than a row per read that names the tool and no memory.
  if (readings.length === 0) {
    if (!firstRead) return undefined;
    const fields: NoteField[] = [{ k: 'Reads', v: reads.toLocaleString('en-US') }];
    const app = str(firstRead.row.appId);
    if (app) fields.push({ k: 'App', v: app });
    fields.push(...readingFields(firstRead.row).filter((field) => field.k !== 'Read time'));
    return { notes: [{ t: (firstToolRead ?? firstRead).t, label: 'No readings', detail: 'no read returned a memory figure', tone: 'muted', badges: [], fields }], fold: 'none' };
  }

  let peakKb = 0;
  for (const reading of readings) peakKb = Math.max(peakKb, reading.kb ?? 0);
  const ceilingMb = Math.max(SCALE_STEP_MB, Math.ceil(peakKb / 1024 / SCALE_STEP_MB) * SCALE_STEP_MB);
  const scale: NoteField = { k: 'Scale', v: `0-${ceilingMb.toLocaleString('en-US')} MB` };
  const notes = readings.map(({ note, kb, row }): StreamNote => ({
    ...note,
    fields: [...note.fields, ...(kb === undefined ? [] : [scale]), ...readingFields(row)],
    ...(kb === undefined ? {} : { meter: Math.min(1, kb / 1024 / ceilingMb) }),
  }));
  return { notes, fold: 'none' };
}

// --- capture notices ---

const PLUGIN_PREFIX = /^(?:[a-z][\w-]*\.)+plugin\./i;
const shortStream = (name: string): string => name.replace(PLUGIN_PREFIX, '');

const guideField = (documentation: string | undefined): NoteField[] =>
  documentation ? [{ k: 'Guide', v: clip(documentation, 600), ...(/^https?:\/\//i.test(documentation) ? { href: documentation } : {}) }] : [];

/** Streams the run asked for but didn't record. */
function captureNoticesLane(events: Json[]): BuiltLane | undefined {
  interface Notice { t: number; stream: string; message?: string; documentation?: string; metadataOnly: boolean }
  const notices: Notice[] = [];
  for (const event of events) {
    const t = num(event.t);
    const value = typeof event.d === 'string' ? parseJson(event.d) : undefined;
    const stream = isRecord(value) ? str(value.stream) : undefined;
    if (t === undefined || !isRecord(value) || !stream) return undefined;
    notices.push({ t, stream, message: str(value.message), documentation: str(value.documentation), metadataOnly: value.kind === 'metadata-only' });
  }
  const notes: StreamNote[] = [];
  for (let start = 0; start < notices.length;) {
    let end = start + 1;
    while (end < notices.length && notices[end].t === notices[start].t) end++;
    const run = notices.slice(start, end);
    if (run.length >= MIN_BURST) {
      const entries: NoteEntry[] = run.map((notice) => ({ k: shortStream(notice.stream), v: notice.stream }));
      notes.push({
        t: run[0].t,
        label: `${run.length} streams not captured`,
        detail: clip(entries.map((entry) => entry.k).join(', '), MAX_LABEL),
        badges: [],
        fields: guideField(run[0].documentation),
        entries,
      });
    } else {
      for (const notice of run) {
        notes.push({
          t: notice.t,
          // A metadata-only notice names every stream the session did capture, comma-joined.
          label: notice.metadataOnly ? 'Metadata only' : 'Not captured',
          detail: notice.stream.split(', ').map(shortStream).join(', '),
          badges: [],
          fields: [
            { k: 'Stream', v: notice.stream },
            ...(notice.message ? [{ k: 'Reason', v: clip(notice.message, 600) }] : []),
            ...guideField(notice.documentation),
          ],
        });
      }
    }
    start = end;
  }
  return notes.length > 0 ? { notes, fold: 'none' } : undefined;
}

// ─── Visible strings lane ─────────────────────────────────────────────────────────────────────

const DRAWN = new Set(['text', 'editableText', 'value']);
/** An element's label, which only some drivers keep apart from the text it draws. */
const LABEL = 'contentDescription';
const COUNTED = new Set([...DRAWN, LABEL]);
/** A field's placeholder: drawn while the field is empty, and then the only string it reports. */
const HINT = 'hint';
/** A web heading's role name reads like text but says only what kind of element it is. */
const ROLE = 'roleDescription';
const ACCESSIBILITY_ONLY = 'Accessibility only';
const OFF_SCREEN = 'Off screen';
/** Sources only the iOS AXe driver reports; its status bar carries a help hint on nearly every capture. */
const AXE_SOURCES = new Set(['value', 'state', 'title', 'help', 'customAction']);
/** A phone's status bar (clock, signal, battery) sits in the top 54pt; iOS reports it, and Android's pixels still fit. */
const STATUS_BAR_BOTTOM = 54;
/** An icon button is at most this many points a side. */
const ICON_MAX = 64;
/** A character's average width and least glyph width, and a line's least height, in points at 11pt. */
const MIN_CHAR_WIDTH = 5.5;
const MIN_GLYPH_WIDTH = 4;
const MIN_LINE_HEIGHT = 11;

type Bounds = [left: number, top: number, right: number, bottom: number];
type Counted = { entry: Json; text: string; source: string };
type ScreenGroup = typeof ACCESSIBILITY_ONLY | typeof OFF_SCREEN;
interface ScreenText { text: string; source: string; hints: string[]; boxes: ScreenBox[]; group?: ScreenGroup }

/** `TAP_POINT` and `final_screenshot` read as "Tap point" and "Final screenshot". */
const humanize = (text: string): string =>
  /^[A-Za-z]+(?:_[A-Za-z]+)+$|^[A-Z]+$/.test(text) ? text[0].toUpperCase() + text.slice(1).toLowerCase().replace(/_/g, ' ') : text;
const sourceName = (source: string) => source.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();

function boundsOf(entry: Json): Bounds | undefined {
  const bounds = Array.isArray(entry.bounds) && entry.bounds.length === 4 ? entry.bounds.map(num) : undefined;
  return bounds && bounds.every((edge) => edge !== undefined) ? bounds as Bounds : undefined;
}

/** Whether the capture is a phone's, whose top strip is the status bar; a web page's top is its content. */
const isMobile = (platform: string | undefined): boolean => /^(?:android|ios)$/i.test(platform ?? '');

export function isNoise(entry: Json, width: number | undefined, height: number | undefined, mobile: boolean): boolean {
  const bounds = boundsOf(entry);
  if (!bounds) return false;
  const [left, top, right, bottom] = bounds;
  // The app's own root element names the whole screen.
  if (width !== undefined && height !== undefined && left <= 0 && top <= 0 && right >= width && bottom >= height) return true;
  // iOS keeps a hidden keyboard's keys in the tree with no area.
  if (right <= left || bottom <= top) return true;
  return mobile && top >= 0 && bottom <= STATUS_BAR_BOTTOM;
}

const inside = (inner: Bounds, outer: Bounds) =>
  inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3] &&
  (inner[2] - inner[0]) * (inner[3] - inner[1]) < (outer[2] - outer[0]) * (outer[3] - outer[1]);

/** iOS AXe reports a button's label as drawn text even when it only holds an icon, so geometry has to
 * say which aren't: a box too thin for an 11pt line or for its longest word, an icon-sized box too
 * narrow for the whole label, or a box whose label repeats an element it holds. */
function isSpokenLabel(item: Counted, counted: Counted[]): boolean {
  const { entry, text } = item;
  const bounds = boundsOf(entry);
  if (entry.source !== 'text' || !bounds) return false;
  const boxWidth = bounds[2] - bounds[0];
  const boxHeight = bounds[3] - bounds[1];
  const longestWord = Math.max(...text.split(/\s+/).map((word) => word.length));
  if (boxHeight < MIN_LINE_HEIGHT || longestWord * MIN_GLYPH_WIDTH > boxWidth) return true;
  if (boxWidth <= ICON_MAX && boxHeight <= ICON_MAX && text.length * MIN_CHAR_WIDTH > boxWidth) return true;
  return counted.some((other) => {
    const held = boundsOf(other.entry);
    // A value or hint the element carries shares its ref and its box.
    const sameElement = other === item || (entry.ref !== undefined && other.entry.ref === entry.ref);
    return !sameElement && other.entry.visible !== false && held !== undefined && inside(held, bounds) && text.includes(other.text);
  });
}

/** Bounds share the device size's units, points on iOS and pixels on Android. */
function boxOf(entry: Json, width: number | undefined, height: number | undefined): ScreenBox | undefined {
  const bounds = boundsOf(entry);
  if (entry.visible === false || !bounds || !width || !height) return undefined;
  const [left, top, right, bottom] = bounds;
  return { left: left / width, top: top / height, width: (right - left) / width, height: (bottom - top) / height };
}

/** The strings on the screen, in reading order, with any help, hint, or state text the same element
 * carries: drawn text first, then labels only a screen reader speaks, then strings scrolled away. */
function screenTexts(width: number | undefined, height: number | undefined, strings: Json[], axe: boolean, labelsDrawn: boolean, mobile: boolean, viewport: boolean): ScreenText[] {
  const drawn = (source: string) => DRAWN.has(source) || source === HINT || (labelsDrawn && source === LABEL);
  const hints = new Map<string, string[]>();
  const counted: Counted[] = [];
  // A placeholder with nothing else on its element is what the empty field shows, so it counts;
  // beside typed text or a label it stays supplementary. Without a ref, the element is its box.
  const elementOf = (entry: Json) => str(entry.ref) ?? JSON.stringify(boundsOf(entry) ?? null);
  const labelled = new Set(strings.filter((entry) => COUNTED.has(str(entry.source) ?? '') && str(entry.text)).map(elementOf));
  for (const entry of strings) {
    const text = str(entry.text);
    const source = str(entry.source);
    if (!text || !source || source === ROLE || isNoise(entry, width, height, mobile)) continue;
    if (COUNTED.has(source) || (source === HINT && !labelled.has(elementOf(entry)))) counted.push({ entry, text, source });
    else {
      const ref = str(entry.ref);
      if (ref) hints.set(ref, [...(hints.get(ref) ?? []), text]);
    }
  }
  const texts: ScreenText[] = [];
  const seen = new Set<string>();
  const claimed = new Set<string>();
  const spoken = new Set(axe ? counted.filter((item) => isSpokenLabel(item, counted)) : []);
  // A value goes with an element whose label the geometry demoted, so a scroll bar's "0%" is spoken along with it.
  const spokenRefs = new Set([...spoken].flatMap((item) => (str(item.entry.ref) ? [item.entry.ref] : [])));
  for (const item of counted) if (item.source === 'value' && spokenRefs.has(item.entry.ref)) spoken.add(item);
  const groupOf = (item: Counted): ScreenGroup | undefined =>
    item.entry.visible === false ? OFF_SCREEN : drawn(item.source) && !spoken.has(item) ? undefined : ACCESSIBILITY_ONLY;
  // Earlier groups claim their text first, so a label that is also drawn, or an off-screen copy, doesn't hide it.
  for (const group of [undefined, ACCESSIBILITY_ONLY, OFF_SCREEN] as Array<ScreenGroup | undefined>) {
    for (const item of counted) {
      const { entry, text, source } = item;
      if (groupOf(item) !== group || seen.has(text)) continue;
      seen.add(text);
      const ref = str(entry.ref);
      const own = ref && !claimed.has(ref) ? hints.get(ref) ?? [] : [];
      if (ref) claimed.add(ref);
      // A text drawn twice, such as two "Add" buttons, is one row that points at both.
      const boxes = viewport ? counted.flatMap((other) => (other.text === text ? boxOf(other.entry, width, height) ?? [] : [])) : [];
      texts.push({ text, source, hints: own, boxes, ...(group ? { group } : {}) });
    }
  }
  return texts;
}

interface Capture {
  t: number;
  screen: VisibleStringsScreen;
  strings: Json[];
  texts?: ScreenText[];
  /** Read by iOS's non-AXe rules, where a label is the text the element draws. */
  labelsDrawn?: boolean;
}

/** A capture's image, named as the Strings tab reads it: its screenshot, an image-shaped capture id
 * (lines written before `screenshot` existed), its capture URL, else the still saved for its id. */
function stringsPicture(screen: VisibleStringsScreen, hasShot: (file: string) => boolean): string | undefined {
  const id = str(screen.captureId);
  const url = str(screen.captureUrl);
  const candidates = [str(screen.screenshot), id && /\.(png|webp|jpe?g|gif)$/i.test(id) ? id : undefined, url, url?.split('/').pop(), id ? `${id}.webp` : undefined];
  return candidates.find((file): file is string => file !== undefined && file !== '' && hasShot(file));
}

/** One row per screen: consecutive captures that show the same strings fold into it. */
function visibleStringsLane(screens: VisibleStringsScreen[], platformOf: (screen: VisibleStringsScreen) => string | undefined, captureTime: (screen: VisibleStringsScreen) => number | undefined, shotOf: (screen: VisibleStringsScreen) => { file: string; t?: number } | undefined): BuiltLane | undefined {
  const captures: Capture[] = [];
  for (const screen of screens) {
    const t = captureTime(screen);
    if (t === undefined) continue;
    captures.push({ t, screen, strings: Array.isArray(screen.strings) ? (screen.strings as unknown[]).filter(isRecord) : [] });
  }
  if (captures.length === 0) return undefined;
  captures.sort((a, b) => a.t - b.t);
  // Each capture is read by its own device's rules: a run can pair a phone with a browser.
  const platforms = captures.map((capture) => platformOf(capture.screen)?.toLowerCase());
  // Only the AXe driver files a button's label as drawn text whether or not it draws it.
  const axe = captures.some((capture, i) => platforms[i] === 'ios' && capture.strings.some((entry) => AXE_SOURCES.has(str(entry.source) ?? '')));
  captures.forEach((capture, i) => {
    const platform = platforms[i];
    // Any other iOS driver files an element's label, which is what a static text or button draws, as
    // its content description, and its `text` holds little else; read as accessibility-only, every
    // screen would show no strings at all.
    const labelsDrawn = platform === 'ios' && !axe;
    capture.labelsDrawn = labelsDrawn;
    // A web capture's bounds are page coordinates and the capture records no scroll offset, so on a
    // scrolled page they would miss the viewport-sized screenshot; no box beats a wrong one.
    const viewport = platform !== 'web';
    capture.texts = capture.strings.length === 0
      ? undefined
      : screenTexts(num(capture.screen.deviceWidth), num(capture.screen.deviceHeight), capture.strings, axe, labelsDrawn, isMobile(platform), viewport);
  });
  const foldKey = (capture: Capture): string | undefined =>
    capture.texts ? `${capture.screen.partialCapture === true ? 'p' : ''}${capture.strings.map((entry) => (entry.visible === false ? '0' : '1')).join('')}:${capture.texts.map((text) => `${text.group ?? ''}:${text.text}:${text.hints.join('\u0000')}`).join('\n')}` : undefined;
  const runs: Capture[][] = [];
  for (const capture of captures) {
    const key = foldKey(capture);
    const last = runs[runs.length - 1];
    if (key && last && foldKey(last[0]) === key) last.push(capture);
    else runs.push([capture]);
  }
  const notes = runs.map((run): StreamNote => {
    const capture = run[0];
    const texts = capture.texts;
    const shown = texts?.filter((text) => !text.group) ?? [];
    const fields: NoteField[] = [];
    const actions = [...new Set(run.flatMap((member) => str(member.screen.action) ?? []).map(humanize))];
    if (actions.length > 0) fields.push({ k: actions.length === 1 ? 'Action' : 'Actions', v: actions.join(', ') });
    // The tree may have been cut short: what it holds is real, but some of the screen may be missing.
    const partial = capture.screen.partialCapture === true;
    if (partial) fields.push({ k: 'Capture', v: 'Partial: some strings on screen may be missing' });
    const entries = (texts ?? []).map((text): NoteEntry => ({
      k: text.text,
      v: text.hints.join(' · '),
      ...(text.group ? { group: text.group } : {}),
      ...(text.boxes.length > 0 ? { boxes: text.boxes } : {}),
      ...(text.source === 'text' || text.group === ACCESSIBILITY_ONLY || (capture.labelsDrawn && text.source === LABEL) ? {} : { badge: { text: sourceName(text.source) } }),
    }));
    const shot = shotOf(capture.screen);
    return {
      t: capture.t,
      label: texts ? `${shown.length.toLocaleString('en-US')} ${shown.length === 1 ? 'string' : 'strings'}` : 'No hierarchy',
      ...(shown.length > 0 ? { detail: clip(shown.map((text) => text.text).join(' · '), MAX_LABEL) } : {}),
      ...(texts ? {} : { tone: 'muted' as const }),
      badges: [...(partial ? [{ text: 'Partial' }] : []), ...(run.length > 1 ? [{ text: `×${run.length.toLocaleString('en-US')}` }] : [])],
      fields,
      ...(entries.length > 0 ? { entries, entryColumns: ['String', 'Hint'] as [string, string] } : {}),
      ...(shot ? { shot: shot.file, shotT: shot.t ?? capture.t } : {}),
    };
  });
  return { notes, fold: 'none' };
}

/** A capture's moment: its log timestamp, else the epoch its screenshot's file name ends in. */
export function captureMoment(screen: VisibleStringsScreen): number | undefined {
  const stamp = str(screen.timestamp);
  if (stamp) {
    // Date parsing stops at milliseconds; the log carries microseconds.
    const parsed = Date.parse(stamp.replace(/(\.\d{3})\d+/, '$1'));
    if (Number.isFinite(parsed)) return parsed;
  }
  const named = /_(\d{12,14})\.\w+$/.exec(str(screen.screenshot) ?? str(screen.captureId) ?? '');
  return named ? Number(named[1]) : undefined;
}

// ─── Bursts ───────────────────────────────────────────────────────────────────────────────────

/** Same-instant bursts (a snapshot, a batch flush) would stack into a wall of identical rows; one
 * note that lists its members reads the same. */
export function foldBursts(notes: StreamNote[], policy: FoldPolicy): StreamNote[] {
  if (policy === 'none') return notes;
  const folded: StreamNote[] = [];
  // A failure keeps its own row, so its status and details stay on its card.
  const foldable = (note: StreamNote) => note.tone !== 'error';
  for (let start = 0; start < notes.length;) {
    let end = start + 1;
    if (foldable(notes[start])) {
      while (end < notes.length && notes[end].t === notes[start].t && foldable(notes[end]) && (policy === 'any' || notes[end].label === notes[start].label)) end++;
    }
    const run = notes.slice(start, end);
    if (run.length < MIN_BURST) folded.push(...run);
    else {
      folded.push({
        t: run[0].t,
        ...(policy === 'label'
          ? { label: run[0].label, detail: `×${run.length.toLocaleString('en-US')}` }
          : { label: `${run.length.toLocaleString('en-US')} events`, detail: run[0].label }),
        badges: [],
        fields: [],
        members: run.map((note) => note.label),
      });
    }
    start = end;
  }
  return folded;
}

// ─── The model ────────────────────────────────────────────────────────────────────────────────

export interface SummaryInput {
  session: SessionPayload;
  /** The session's event streams once inflated. */
  events: EventStream[] | null;
  /** Whether the report carries the screenshot stored under this key. */
  hasShot: (file: string) => boolean;
}

/** A record's picture: its screenshot, else the still the report saved for its first capture. */
function pictureOf(record: Json): string | undefined {
  const id = Array.isArray(record.captureIds) ? str(record.captureIds[0]) : undefined;
  return str(record.screenshotFile) ?? (id ? `${id}.webp` : undefined);
}

function traceEntries(value: unknown): TraceEntry[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord).flatMap((entry): TraceEntry[] => {
    const ts = num(entry.ts);
    const label = str(entry.label);
    if (ts === undefined || label === undefined) return [];
    const viewport = isRecord(entry.viewport) ? entry.viewport : undefined;
    const w = num(viewport?.w);
    const h = num(viewport?.h);
    return [{
      label,
      tool: str(entry.tool),
      note: str(entry.note),
      ms: Math.max(0, num(entry.ms) ?? 0),
      ts,
      ok: entry.ok !== false,
      err: str(entry.err),
      screenshotFile: pictureOf(entry),
      objective: entry.objective === true,
      trailhead: entry.trailhead === true,
      teardown: entry.teardown === true,
      selfHeal: entry.selfHeal === true,
      selfHealTool: str(entry.selfHealTool),
      selfHealError: str(entry.selfHealError),
      terminal: entry.terminal === true,
      endOfRun: entry.endOfRun === true,
      shotTs: num(entry.shotTs),
      shotEndTs: num(entry.shotEndTs),
      args: str(entry.args),
      ...(w && h ? { viewport: { w, h } } : {}),
      ...(num(entry.llm) !== undefined ? { llm: num(entry.llm) } : {}),
      decision: entry.decision === true,
      device: str(entry.device),
      children: Array.isArray(entry.children)
        ? entry.children.filter(isRecord).flatMap((child) => {
          const childLabel = str(child.label);
          return childLabel
            ? [{ label: childLabel, ts: num(child.ts), ms: num(child.ms) === undefined ? undefined : Math.max(0, num(child.ms) ?? 0), screenshotFile: pictureOf(child), shotEndTs: num(child.shotEndTs) }]
            : [];
        })
        : [],
    }];
  });
}

const MAX_PROBLEMS = 3;
const NO_ERROR = 'No error recorded';

/** The raw texts behind a problem, each once, for the reader who opens Details. */
function problemDetail(...texts: Array<string | undefined>): string | undefined {
  const unique = [...new Set(texts.filter((text): text is string => !!text))];
  return unique.length > 0 ? clip(unique.join('\n\n'), MAX_BODY) : undefined;
}

function objectiveStepRef(step: StreamStep, bandIndex: number): DiagnosisStep {
  return {
    kind: step.kind === 'trailhead' ? 'trailhead' : step.kind === 'teardown' ? 'teardown' : 'step',
    ...(step.number !== undefined ? { number: step.number } : {}),
    title: step.title,
    bandIndex,
  };
}

/** One problem per failed objective, so each points back at the step band it happened in. A failed
 * self-heal retry leads with what the recording hit, since that is what the heal was getting past. */
function buildDiagnosis(
  objectiveSteps: Array<{ entry: TraceEntry; bandIndex: number }>,
  steps: StreamStep[],
  runFailed: boolean,
  metaError: string | undefined,
  failedRow: { entry: TraceEntry; bandIndex: number } | undefined,
  crashed: boolean,
): RunDiagnosis | undefined {
  if (!runFailed) return undefined;

  const failed = objectiveSteps.filter(({ bandIndex }) => steps[bandIndex].status === 'failed');
  // Cleanup is blamed only when nothing in the trail itself failed, and never over a crash.
  const trailFailing = failed.filter(({ bandIndex }) => steps[bandIndex].kind !== 'teardown');
  const failing = failedRow || trailFailing.length > 0 || crashed ? trailFailing : failed;
  let problems: DiagnosisProblem[];
  if (failedRow) {
    const { summary, via } = describeError(failedRow.entry.err ?? metaError ?? '');
    problems = [{
      step: objectiveStepRef(steps[failedRow.bandIndex], failedRow.bandIndex),
      summary: summary || `${failedRow.entry.label} failed`,
      ...(via ? { via } : {}),
      detail: problemDetail(failedRow.entry.err, metaError),
    }];
  } else if (failing.length > 0) {
    problems = failing.map(({ entry, bandIndex }, index) => {
      const final = index === failing.length - 1;
      const error = entry.err ?? (entry.selfHeal ? entry.selfHealError : undefined) ?? (final ? metaError : undefined);
      // objectiveSteps and steps line up one to one, so a retry's attempt is the band before it.
      const attempt = steps[bandIndex].kind === 'heal' ? objectiveSteps[bandIndex - 1]?.entry : undefined;
      const healError = attempt ? attempt.selfHealError ?? attempt.err : undefined;
      const healed = attempt ?? (entry.err === undefined && entry.selfHeal ? entry : undefined);
      const described = describeError(healError ?? error ?? '');
      const summary = described.summary || NO_ERROR;
      const retry = healError && error ? describeError(error) : undefined;
      return {
        step: objectiveStepRef(steps[bandIndex], bandIndex),
        summary,
        ...(described.via ? { via: described.via } : {}),
        ...(attempt ? {
          note: retry?.summary && (retry.summary !== summary || retry.via !== described.via)
            ? `self-heal failed: ${retry.summary}${retry.via ? ` via ${retry.via}` : ''}`
            : 'self-heal failed',
        } : {}),
        ...(healed?.selfHealError && healed.selfHealTool ? { tool: healed.selfHealTool } : {}),
        detail: problemDetail(healError, error, final ? metaError : undefined),
      };
    });
  } else {
    // Every step passed, some maybe after a self-heal, yet the run failed. Saying so keeps the red
    // status from going unexplained.
    const lastBand = steps.length - 1;
    const { summary, via } = describeError(metaError ?? '');
    problems = [{
      ...(lastBand >= 0 ? { step: { kind: 'end' as const, title: steps[lastBand].title, bandIndex: lastBand } } : {}),
      summary: summary || (objectiveSteps.length > 0 ? 'All steps passed, but the run failed' : 'The run failed'),
      ...(via ? { via } : {}),
      detail: problemDetail(metaError),
    }];
  }

  const unique = problems.filter((problem, index) =>
    !problems.slice(index + 1).some((later) => later.summary === problem.summary && later.via === problem.via && later.note === problem.note));
  const hidden = Math.max(0, unique.length - MAX_PROBLEMS);
  return { problems: unique.slice(hidden), hidden };
}

/** The events a formatter's rows were made from: each row's first raw payload, at the row's time. */
function rowEvents(rows: Json[]): Json[] {
  return rows.flatMap((row): Json[] => {
    const t = num(row.t);
    const payload = Array.isArray(row.raw) ? row.raw[0] : undefined;
    // A payload over the export's size cap is kept as a clipped string, which can't be read back.
    return t !== undefined && isRecord(payload) ? [{ t, d: JSON.stringify(payload) }] : [];
  });
}

/** Build the board from one hydrated session. */
export function buildSummaryModel(input: SummaryInput): StreamsModel {
  const { session } = input;
  const trace = traceEntries(session.trace);
  const platform = str(session.meta?.platform);
  const llmRows = Array.isArray(session.llm) ? (session.llm as unknown[]).filter(isRecord) : [];

  const steps: StreamStep[] = [];
  const tools: StreamNote[] = [];
  const llm: StreamNote[] = [];
  const shots: StreamShot[] = [];
  let number = 0;
  let healPending: StreamStep | undefined;
  let firstFailedRow: { entry: TraceEntry; bandIndex: number } | undefined;
  let aspect: number | undefined;
  const objectiveSteps: Array<{ entry: TraceEntry; bandIndex: number }> = [];
  const seen = new Map<string, StreamShot>();
  const addShot = (file: string | undefined, t: number, label: string, size?: { w?: number; h?: number }): StreamShot | undefined => {
    if (file === undefined || !input.hasShot(file)) return undefined;
    let shot = seen.get(file);
    if (!shot) {
      const shotAspect = size?.w && size.h && size.w > 0 && size.h > 0 ? size.w / size.h : undefined;
      shot = { t, file, label, ...(shotAspect ? { aspect: shotAspect } : {}) };
      seen.set(file, shot);
      shots.push(shot);
    }
    return shot;
  };
  for (const entry of trace) {
    if (entry.viewport && aspect === undefined) aspect = entry.viewport.w / entry.viewport.h;
    // The frame was taken when its record ended: `shotTs` and `ts` are the action's start.
    let shot = addShot(entry.screenshotFile, entry.shotEndTs ?? entry.shotTs ?? (entry.objective ? entry.ts : entry.ts + entry.ms), entry.label, entry.viewport);
    // Older payloads carry no per-dispatch timestamp, so those are spread inside the row's span.
    const instants = childInstants(entry);
    entry.children.forEach((child, k) => {
      const t = child.shotEndTs ?? (child.ts !== undefined ? child.ts + (child.ms ?? 0) : instants[k] ?? entry.ts);
      // A dispatch can run on another device than its row, so its shape is left to its own pixels.
      shot = addShot(child.screenshotFile, t, child.label) ?? shot;
    });
    if (entry.objective) {
      const heal = healPending !== undefined && healPending.title === entry.label && !entry.trailhead && !entry.teardown;
      const kind: StepKind = heal ? 'heal' : entry.teardown ? 'teardown' : entry.trailhead ? 'trailhead' : 'step';
      if (kind === 'step') number++;
      const step: StreamStep = {
        kind,
        ...(kind === 'step' ? { number } : {}),
        title: entry.label,
        startMs: entry.ts,
        endMs: entry.ts,
        status: entry.selfHeal ? 'recovered' : entry.ok ? 'ok' : 'failed',
      };
      // A self-heal retry repeats its step's number, so the two bands read as one step.
      if (kind === 'heal') step.number = number;
      steps.push(step);
      objectiveSteps.push({ entry, bandIndex: steps.length - 1 });
      // Only a self-heal that didn't land is followed by a retry; one that did stands as its own step.
      healPending = entry.selfHeal && !entry.ok ? step : undefined;
      continue;
    }
    // The failure screenshot and the run's error follow any teardown but are the trail's, so a clean
    // teardown never takes the blame.
    const owner = entry.endOfRun ? steps.findLastIndex((candidate) => candidate.kind !== 'teardown') : -1;
    const ownerIndex = owner >= 0 ? owner : Math.max(0, steps.length - 1);
    // A cleanup error never stands in for the trail's failure.
    if (!entry.ok && steps[ownerIndex]?.status !== 'recovered' && steps[ownerIndex]?.kind !== 'teardown') firstFailedRow ??= { entry, bandIndex: ownerIndex };
    if (entry.terminal) continue;
    if (entry.llm !== undefined || entry.decision || entry.tool === 'agent step' || entry.tool?.startsWith('llm · ')) {
      llm.push(llmNote(entry, entry.llm === undefined ? undefined : llmRows[entry.llm]));
    } else {
      tools.push(toolNote(entry, shot));
    }
  }

  const streams = new Map<string, { name: string; total: number; notes: StreamNote[]; fold: FoldPolicy }>();
  if (tools.length > 0) streams.set(TOOLS_LANE, { name: 'Tools', total: tools.length, notes: tools, fold: 'any' });
  if (llm.length > 0) streams.set(LLM_LANE, { name: 'LLM', total: llm.length, notes: llm, fold: 'any' });
  const rawStreams = ((input.events ?? []) as unknown[]).filter(isRecord);
  for (const stream of rawStreams) {
    const streamName = str(stream.name);
    if (!streamName) continue;
    const identity = laneIdentity(streamName);
    // A device-clock event has no offset to the run's clock, so it can't be placed beside the rest.
    const events = (Array.isArray(stream.events) ? stream.events.filter(isRecord) : []).filter((event) => event.clock !== 'device');
    const rows = Array.isArray(stream.rows) ? stream.rows.filter(isRecord) as Json[] : undefined;
    const network = isNetworkLane(identity.id);
    const device = identity.id.startsWith('network.') ? identity.id.slice('network.'.length) : undefined;
    let built: BuiltLane | undefined;
    // A report export keeps a formatted stream as rows with no events, each row's raw payload being
    // the event it was made from, so the lanes that pair or fold events read those the same way.
    const laneEvents = rows ? rowEvents(rows) : events;
    if (identity.id === 'memory') built = memoryLane(laneEvents);
    else if (identity.id === 'capture-notices') built = captureNoticesLane(laneEvents);
    const notes = built?.notes ?? (rows
      ? rows.flatMap((row) => (network ? networkRowNote(row, device) : rowNote(row)) ?? [])
      : (network ? networkNotes(events, device) : undefined) ?? events.flatMap((event) => {
        const t = num(event.t);
        return t === undefined || typeof event.d !== 'string' ? [] : [parsedEvent(t, event.d)];
      }));
    if (notes.length === 0) continue;
    const existing = streams.get(identity.id);
    if (existing) {
      existing.notes.push(...notes);
      existing.total += built?.total ?? notes.length;
    } else {
      streams.set(identity.id, { name: identity.name, total: built?.total ?? notes.length, notes, fold: built?.fold ?? 'any' });
    }
  }

  // The captures' strings ride the payload itself rather than an event stream.
  if (!streams.has('visible-strings') && Array.isArray(session.visibleStrings) && session.visibleStrings.length > 0) {
    const shotTimes = new Map(shots.map((shot) => [shot.file, shot.t]));
    const pictureOfScreen = (screen: VisibleStringsScreen) => stringsPicture(screen, input.hasShot);
    // A browser in a multi-device run is known by its tools; its screenshots are web captures.
    // A batched dispatch can run on another device than its row, so a dispatch counts only its own tool.
    const isWebTool = (label: string) => /^web_/.test(label);
    const webDevices = new Set(trace.flatMap((row) => (row.device && isWebTool(row.label) ? [row.device] : [])));
    const webShots = new Set(trace.flatMap((row) => [
      ...(row.device && webDevices.has(row.device) && row.screenshotFile ? [row.screenshotFile] : []),
      ...(row.children ?? []).flatMap((kid) => (isWebTool(kid.label) && kid.screenshotFile ? [kid.screenshotFile] : [])),
    ]));
    const built = visibleStringsLane(
      session.visibleStrings,
      (screen) => {
        const file = pictureOfScreen(screen);
        return file !== undefined && webShots.has(file) ? 'web' : platform;
      },
      captureMoment,
      (screen) => {
        const file = pictureOfScreen(screen);
        return file === undefined ? undefined : { file, t: shotTimes.get(file) };
      },
    );
    if (built) streams.set('visible-strings', { name: 'Visible strings', total: built.total ?? built.notes.length, notes: built.notes, fold: built.fold ?? 'none' });
    // A capture can hold a screenshot no trace row took (one for an LLM request): it is a screen too.
    for (const screen of session.visibleStrings) {
      const t = captureMoment(screen);
      if (t !== undefined) addShot(pictureOfScreen(screen), t, 'Visible strings', { w: num(screen.deviceWidth), h: num(screen.deviceHeight) });
    }
  }

  // Loops, not spreads: a stream can hold more events than an argument list.
  let earliest = Number.POSITIVE_INFINITY;
  let lastEnd = Number.NEGATIVE_INFINITY;
  for (const entry of trace) {
    earliest = Math.min(earliest, entry.ts);
    lastEnd = Math.max(lastEnd, entry.ts + entry.ms);
    // A run can end inside a folded batch, whose dispatches run past their row's own span.
    for (const child of entry.children) {
      if (child.ms === undefined) continue;
      if (child.ts !== undefined) lastEnd = Math.max(lastEnd, child.ts + child.ms);
      if (child.shotEndTs !== undefined) lastEnd = Math.max(lastEnd, child.shotEndTs);
    }
  }
  // A frame taken as its record ended can land after the record's span.
  for (const shot of shots) lastEnd = Math.max(lastEnd, shot.t);
  for (const stream of streams.values()) {
    for (const note of stream.notes) {
      earliest = Math.min(earliest, note.t);
      lastEnd = Math.max(lastEnd, note.t);
    }
  }
  if (steps.length === 0 && Number.isFinite(earliest)) {
    steps.push({ kind: 'step', number: 1, title: 'Run', startMs: earliest, endMs: earliest, status: 'ok' });
  }
  for (let index = 0; index < steps.length; index++) {
    steps[index].endMs = index + 1 < steps.length ? steps[index + 1].startMs : Math.max(lastEnd, steps[index].startMs);
  }
  // Like the report's failure anchor: a failed run whose objectives all passed failed at its first failed row.
  const runFailed = /^(failed|error)$/i.test(str(session.meta?.status) ?? '');
  if (runFailed) {
    for (let index = 0; index < objectiveSteps.length; index++) {
      const { entry, bandIndex } = objectiveSteps[index];
      // Only an attempt that failed and got no retry stays failed; one that healed in place succeeded.
      if (entry.selfHeal && !entry.ok && steps[objectiveSteps[index + 1]?.bandIndex]?.kind !== 'heal') steps[bandIndex].status = 'failed';
    }
  }
  // A self-heal that got its retry recovered, so it's not an active failure blocking the row below.
  // A failed teardown doesn't explain the trail's failure, and a crash is the run's own diagnosis,
  // not the tolerated tool error before it (as in Theater).
  const trailStepFailed = objectiveSteps.some(({ bandIndex }) => steps[bandIndex].status === 'failed' && steps[bandIndex].kind !== 'teardown');
  const crashed = str(session.meta?.failureCode) === 'app_crashed';
  const failedRow = runFailed && !trailStepFailed && !crashed && firstFailedRow && steps[firstFailedRow.bandIndex]
    ? firstFailedRow
    : undefined;
  if (failedRow) steps[failedRow.bandIndex].status = 'failed';
  // Anything captured before the first objective belongs to the setup it preceded.
  if (steps[0] && earliest < steps[0].startMs) steps[0].startMs = earliest;

  const diagnosis = buildDiagnosis(objectiveSteps, steps, runFailed, str(session.meta?.error), failedRow, crashed);

  shots.sort((a, b) => a.t - b.t);
  for (let index = 0; index < steps.length; index++) {
    const step = steps[index];
    const last = index === steps.length - 1;
    const inBand = shots.filter((shot) => shot.t >= step.startMs && (last || shot.t < step.endMs));
    const shot = inBand[inBand.length - 1];
    if (shot) step.shot = shot.file;
  }

  // Binary search is safe: logs are sorted by timestamp before the trace is extracted, so steps start in order.
  const bandOf = (t: number): number => {
    let low = 0;
    let high = steps.length - 1;
    while (low < high) {
      const mid = Math.ceil((low + high) / 2);
      if (steps[mid].startMs <= t) low = mid;
      else high = mid - 1;
    }
    return low;
  };
  const lanes = [...streams.entries()].map(([id, stream]): StreamLane => {
    const bands: StreamNote[][] = steps.map(() => []);
    const sorted = stream.notes.slice().sort((a, b) => a.t - b.t);
    for (const note of steps.length > 0 ? foldBursts(sorted, stream.fold) : []) bands[bandOf(note.t)].push(note);
    return { id, name: stream.name, total: stream.total, bands };
  });

  return {
    startMs: steps[0]?.startMs ?? 0,
    steps,
    lanes,
    shots,
    aspect: aspect ?? DEFAULT_ASPECT,
    ...(diagnosis ? { diagnosis } : {}),
  };
}

/** The screen a reader would have seen at `t`: the last screenshot captured at or before it, or the
 * first one if nothing had been captured yet. */
export function screenAt(model: StreamsModel, t: number): StreamShot | undefined {
  let found: StreamShot | undefined;
  for (const shot of model.shots) {
    if (shot.t > t) break;
    found = shot;
  }
  return found ?? model.shots[0];
}

/** The band a moment falls in: the last step starting at or before it. */
export function bandAt(model: StreamsModel, t: number): number {
  let band = 0;
  while (band + 1 < model.steps.length && model.steps[band + 1].startMs <= t) band++;
  return band;
}

// ─── Lane arrangement ─────────────────────────────────────────────────────────────────────────

export interface LaneLayoutPreference {
  order: string[];
  visible: Record<string, boolean>;
  /** Pixel widths the reader dragged lanes to. */
  widths: Record<string, number>;
}

export const emptyPreference = (): LaneLayoutPreference => ({ order: [], visible: {}, widths: {} });

/** Saved order first, then lanes the reader has never placed in the default order. */
export function arrangeLanes<T extends { id: string; name: string }>(lanes: T[], preference: LaneLayoutPreference, defaults: readonly string[] = DEFAULT_VISIBLE): T[] {
  const rank = (id: string): number => {
    const saved = preference.order.indexOf(id);
    if (saved !== -1) return saved;
    const preferred = defaults.indexOf(isNetworkLane(id) ? 'network' : id);
    return preference.order.length + (preferred === -1 ? defaults.length : preferred);
  };
  return lanes
    .map((lane, index) => ({ lane, index }))
    .sort((a, b) => rank(a.lane.id) - rank(b.lane.id) || a.lane.name.localeCompare(b.lane.name) || a.index - b.index)
    .map(({ lane }) => lane);
}

export function isLaneVisible(id: string, preference: LaneLayoutPreference, defaults: readonly string[] = DEFAULT_VISIBLE): boolean {
  return preference.visible[id] ?? defaults.includes(isNetworkLane(id) ? 'network' : id);
}

/** The lanes a run opens on when the reader hasn't chosen: the defaults, or, for a run with none of
 * them, its first few lanes so the board is never empty. */
export function defaultLaneIds(lanes: Array<{ id: string }>, defaults: readonly string[] = DEFAULT_VISIBLE): string[] {
  const present = lanes.filter((lane) => defaults.includes(isNetworkLane(lane.id) ? 'network' : lane.id));
  return (present.length > 0 ? present : lanes.slice(0, 3)).map((lane) => lane.id);
}

// ─── Find ─────────────────────────────────────────────────────────────────────────────────────

export const noteKey = (band: number, laneId: string, note: number) => `${band}:${laneId}:${note}`;

export interface NoteMatch {
  band: number;
  /** Index into the lanes searched. */
  lane: number;
  note: number;
}

/** Everything a note's row or card shows, so a find lands where the reader saw the text. */
function noteTexts(note: StreamNote): Array<string | undefined> {
  return [
    note.label,
    note.detail,
    note.body,
    ...(note.members ?? []),
    ...note.badges.map((badge) => badge.text),
    ...note.fields.flatMap((field) => [field.k, field.v]),
    ...(note.entries ?? []).flatMap((entry) => [entry.k, entry.v, entry.group, entry.badge?.text]),
  ];
}

export function noteMatches(note: StreamNote, needle: string): boolean {
  return noteTexts(note).some((text) => text?.toLowerCase().includes(needle));
}

/** Matches step by step, then by time, then left to right across the lanes. */
export function searchNotes(lanes: StreamLane[], query: string): NoteMatch[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const matches: Array<NoteMatch & { t: number }> = [];
  lanes.forEach((lane, laneIndex) => lane.bands.forEach((notes, band) => notes.forEach((note, noteIndex) => {
    if (noteMatches(note, needle)) matches.push({ band, lane: laneIndex, note: noteIndex, t: note.t });
  })));
  matches.sort((a, b) => a.band - b.band || a.t - b.t || a.lane - b.lane || a.note - b.note);
  return matches.map(({ band, lane, note }) => ({ band, lane, note }));
}

export function matchEntries(entries: NoteEntry[], query: string): NoteEntry[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return entries;
  return entries.filter((entry) =>
    [entry.k, entry.v, entry.group, entry.badge?.text].some((text) => text?.toLowerCase().includes(needle)));
}

// ─── Band layout ──────────────────────────────────────────────────────────────────────────────

export interface LayoutMetrics {
  noteHeight: number;
  padding: number;
}

export interface BandLayout {
  height: number;
  /** Top offset of each note, per column, in the column's band order. */
  offsets: number[][];
}

/** Place each note at its moment within the band, then push it below the one before it when they
 * would overlap. Notes at the same moment share one top across lanes, and a later moment in any lane
 * sits below every row an earlier one took, so one clock reads every lane's notes at their moments.
 * The band grows to fit. */
export function layoutBand(step: StreamStep, columns: StreamNote[][], minHeight: number, metrics: LayoutMetrics): BandLayout {
  const { noteHeight, padding } = metrics;
  let base = minHeight;
  for (const notes of columns) base = Math.max(base, notes.length * noteHeight + 2 * padding);
  const span = Math.max(1, step.endMs - step.startMs);
  const usable = Math.max(0, base - 2 * padding - noteHeight);
  const offsets: number[][] = columns.map(() => []);
  const floors = columns.map(() => padding);
  const next = (lane: number) => columns[lane][offsets[lane].length];
  let height = base;
  let lastRow = -Infinity;
  for (;;) {
    let t = Infinity;
    for (let lane = 0; lane < columns.length; lane++) {
      const pending = next(lane);
      if (pending && pending.t < t) t = pending.t;
    }
    if (t === Infinity) break;
    const at = Math.min(1, Math.max(0, (t - step.startMs) / span));
    let top = Math.max(padding + at * usable, lastRow + noteHeight / 2);
    for (let lane = 0; lane < columns.length; lane++) if (next(lane)?.t === t) top = Math.max(top, floors[lane]);
    for (let lane = 0; lane < columns.length; lane++) {
      if (next(lane)?.t !== t) continue;
      let floor = top;
      while (next(lane)?.t === t) {
        offsets[lane].push(floor);
        lastRow = Math.max(lastRow, floor);
        floor += noteHeight;
      }
      floors[lane] = floor;
      height = Math.max(height, floor + padding);
    }
  }
  return { height, offsets };
}

/** The (y, t) anchors of a band: its edges and every placed note, ordered so a later moment never
 * sits above an earlier one. */
function bandAnchors(step: StreamStep, columns: StreamNote[][], layout: BandLayout, metrics: LayoutMetrics): Array<{ y: number; t: number }> {
  const { noteHeight, padding } = metrics;
  // A band too short to inset its edges, like a collapsed one, would otherwise hold both at one y
  // and skip every moment between them.
  const inset = layout.height > 2 * (padding + noteHeight / 2) ? padding + noteHeight / 2 : 0;
  const points = [
    { y: inset, t: step.startMs },
    ...columns.flatMap((notes, lane) =>
      notes.flatMap((note, index) => {
        const top = layout.offsets[lane]?.[index];
        return top === undefined ? [] : [{ y: top + noteHeight / 2, t: note.t }];
      })),
    { y: layout.height - inset, t: step.endMs },
  ].sort((a, b) => a.t - b.t);
  let floor = -Infinity;
  for (const point of points) {
    floor = Math.max(floor, point.y);
    point.y = floor;
  }
  return points;
}

/** The inverse of `layoutBand`: the moment a point `y` px into the band stands for. It interpolates
 * between the band's edges and its placed notes, so a stretch that pushing grew stays on the clock
 * of the notes drawn there. */
export function bandClock(step: StreamStep, columns: StreamNote[][], layout: BandLayout, metrics: LayoutMetrics): (y: number) => number {
  const points = bandAnchors(step, columns, layout, metrics);
  return (y) => {
    if (y <= points[0].y) return points[0].t;
    for (let index = 1; index < points.length; index++) {
      const a = points[index - 1];
      const b = points[index];
      if (y <= b.y) return b.y === a.y ? b.t : a.t + ((b.t - a.t) * (y - a.y)) / (b.y - a.y);
    }
    return points[points.length - 1].t;
  };
}

/** Where a moment sits in a band: `bandClock` run backwards, so a pinned moment's line lands on the
 * notes drawn at it. */
export function bandY(step: StreamStep, columns: StreamNote[][], layout: BandLayout, metrics: LayoutMetrics): (t: number) => number {
  const points = bandAnchors(step, columns, layout, metrics);
  return (t) => {
    if (t <= points[0].t) return points[0].y;
    for (let index = 1; index < points.length; index++) {
      const a = points[index - 1];
      const b = points[index];
      if (t <= b.t) return b.t === a.t ? a.y : a.y + ((b.y - a.y) * (t - a.t)) / (b.t - a.t);
    }
    return points[points.length - 1].y;
  };
}

/** The index range of `offsets` (ascending note tops) that lies within `[top, bottom)`, widened so a
 * note straddling `top` is kept. */
export function noteWindow(offsets: number[], top: number, bottom: number, noteHeight: number): [number, number] {
  const lowerBound = (value: number) => {
    let low = 0;
    let high = offsets.length;
    while (low < high) {
      const mid = (low + high) >> 1;
      if (offsets[mid] < value) low = mid + 1;
      else high = mid;
    }
    return low;
  };
  return [lowerBound(top - noteHeight), lowerBound(bottom)];
}

// ─── Moments ──────────────────────────────────────────────────────────────────────────────────

/** Every distinct moment a note sits at across `lanes`, ascending: what the arrow keys step through. */
export function momentsOf(lanes: StreamLane[]): number[] {
  const times = new Set<number>();
  for (const lane of lanes) for (const notes of lane.bands) for (const note of notes) times.add(note.t);
  return [...times].sort((a, b) => a - b);
}

/** The moment after (`1`) or before (`-1`) `t`, or `t` itself when there is none. */
export function stepMoment(moments: number[], t: number, direction: -1 | 1): number {
  if (moments.length === 0) return t;
  if (direction === 1) {
    for (const moment of moments) if (moment > t) return moment;
    return moments[moments.length - 1];
  }
  for (let index = moments.length - 1; index >= 0; index--) if (moments[index] < t) return moments[index];
  return moments[0];
}

// ─── Placement ────────────────────────────────────────────────────────────────────────────────

const MIN_CARD_WIDTH = 256;

/** Where a `width`-wide card opens: after its note if it fits there, else before it. Too wide for
 * either side, it narrows to the roomier one rather than covering the note. */
export function cardBeside(
  anchor: { left: number; right: number },
  width: number,
  view: { left: number; right: number },
  gap: number,
): { left: number; width: number } {
  const after = view.right - gap - (anchor.right + gap);
  const before = anchor.left - gap - (view.left + gap);
  const fitted = width <= Math.max(after, before) ? width : Math.max(MIN_CARD_WIDTH, after, before);
  return { left: fitted <= after ? anchor.right + gap : Math.max(view.left + gap, anchor.left - gap - fitted), width: fitted };
}

/** The sticky top that centres a `height` px preview on a pointer `pointerY` px into the scroller,
 * kept below the header and above the scroller's bottom. */
export function previewTop(pointerY: number, height: number, headerHeight: number, viewHeight: number): number {
  return Math.max(headerHeight, Math.min(viewHeight - height, pointerY - height / 2));
}

/** The biggest `aspect` (width over height) screen at most `height` px tall that fits `gap` px beside
 * a card within `view`, on whichever side fits it bigger, or on the `away` side, clear of the card's
 * note, when both fit it whole. */
export function screenBeside(
  card: { left: number; width: number },
  view: { left: number; right: number },
  height: number,
  aspect: number,
  gap: number,
  away: 'before' | 'after',
): { left: number; width: number; height: number } | undefined {
  const fit = (room: number) => Math.max(0, Math.min(room, height * aspect));
  const after = fit(view.right - (card.left + card.width + gap));
  const before = fit(card.left - gap - view.left);
  const width = Math.max(after, before);
  if (width === 0) return undefined;
  const side = after === before ? away : after > before ? 'after' : 'before';
  return { left: side === 'after' ? card.left + card.width + gap : card.left - gap - width, width, height: width / aspect };
}

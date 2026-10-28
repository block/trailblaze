import type { MatchDescriptor, ToolContext, TrailblazeNodeSelector } from "@trailblaze/scripting";

/**
 * A single recorded tool-call in the `recording.tools` shape: `{ <toolName>: <argsObject> }`, e.g.
 * `{ tapOnElementBySelector: { nodeSelector, reason } }`.
 */
export type ToolCall = { [toolName: string]: Record<string, unknown> };

/** The condition `runIf` evaluates: a selector presence check or a non-fatal tool dispatch. */
export type RunIfCondition =
  | { selector: TrailblazeNodeSelector }
  | { tool: ToolCall };

export interface RunIfArgs {
  /**
   * The condition to evaluate. `{ selector }` is true when the selector matches at least one node;
   * `{ tool }` is true when the single-key tool-call succeeds and false when it runs and fails; a
   * call that cannot run (unknown tool or argument) fails the step.
   */
  condition: RunIfCondition;
  /** Flip the resolved condition (true becomes false and vice versa). */
  negate?: boolean;
  /** Tool-calls to run, in order, when the (possibly negated) condition is true. */
  then: ToolCall[];
  /** Tool-calls to run, in order, when the condition is false. Omit for a no-op on the false branch. */
  else?: ToolCall[];
}

export function runIfHandler(args: RunIfArgs, ctx: ToolContext): Promise<string> {
  checkRunIf("runIf", args, "runIf", 1);
  return evaluateRunIf(args, ctx);
}

/** How deep conditionals may nest, counting the outermost. */
const MAX_NESTING = 2;

async function evaluateRunIf(args: RunIfArgs, ctx: ToolContext): Promise<string> {
  const matched = await evaluateCondition(args.condition, ctx);
  const conditionTrue = args.negate ? !matched : matched;

  const branch = conditionTrue ? args.then : args.else ?? [];
  // A nested runIf's verdict is folded into this one's result: it runs locally, so it has no tool
  // log entry of its own and a false inner condition would otherwise leave no trace.
  const nested: string[] = [];
  for (const entry of branch) {
    const verdict = await dispatchToolCall(entry, ctx);
    if (verdict !== undefined) nested.push(verdict);
  }

  const summary = `runIf: condition=${conditionTrue} ran=${conditionTrue ? "then" : "else"} (${branch.length} tools)`;
  return nested.length > 0 ? `${summary} nested=[${nested.join("; ")}]` : summary;
}

/** Resolve a [RunIfCondition] to a boolean (before `negate` is applied). */
async function evaluateCondition(condition: RunIfCondition, ctx: ToolContext): Promise<boolean> {
  if ("selector" in condition) {
    const [matches]: MatchDescriptor[][] = await ctx.tools.findSelectorMatches({
      selectors: [condition.selector],
    });
    return matches.length > 0;
  }
  // `tool` form: a failing tool is a `false` verdict, not an error, so a failed assertion or probe
  // doesn't abort the trail. A call that never reached its tool is an authoring error and fails.
  try {
    await dispatchToolCall(condition.tool, ctx);
    return true;
  } catch (e) {
    if (isDispatchFailure(e)) throw e;
    return false;
  }
}

/**
 * True when [e] reports a call that never reached its tool (unknown name, rejected arguments, a
 * cycle) rather than a tool that ran and failed. The framework marks those `dispatchFailed`.
 */
function isDispatchFailure(e: unknown): boolean {
  return typeof e === "object" && e !== null && (e as { dispatchFailed?: unknown }).dispatchFailed === true;
}

/** The name that runs as a local recursion rather than a dispatch. See the file header. */
const CONDITIONAL_NAME = "runIf";

/**
 * Dispatch one single-key tool-call by its recorded name. A nested conditional runs locally (see
 * the file header) and its verdict is returned; any other tool returns `undefined`. The tree was
 * already checked by [checkRunIf].
 */
async function dispatchToolCall(entry: ToolCall, ctx: ToolContext): Promise<string | undefined> {
  const [name, toolArgs] = Object.entries(entry)[0];
  if (name === CONDITIONAL_NAME) {
    return evaluateRunIf(toolArgs as unknown as RunIfArgs, ctx);
  }
  await (ctx.tools as unknown as Record<string, (a: unknown) => Promise<unknown>>)[name](toolArgs);
}

const RUN_IF_KEYS = ["condition", "negate", "then", "else"];
const RUN_IF_REQUIRED_KEYS = ["condition", "then"];

/** A failure in the conditional's shape, naming where it is (e.g. `runIf.then[0].runIf.else`). */
function shapeError(path: string, problem: string): Error {
  return new Error(`${path}: ${problem}`);
}

/**
 * Check a conditional and everything nested in it before any of it runs. [name] is the name key
 * errors report, matching the framework's validator wording; [depth] counts the outermost
 * conditional as 1.
 */
function checkRunIf(name: string, args: unknown, path: string, depth: number): void {
  if (depth > MAX_NESTING) {
    throw shapeError(
      path,
      `conditionals nest at most ${MAX_NESTING} deep. Move this logic into a .ts tool, which can be named and tested.`,
    );
  }
  if (!isPlainObject(args)) throw shapeError(path, `expected an object with condition and then, got ${describe(args)}`);
  checkArgKeys(name, args);
  if (args.negate !== undefined && typeof args.negate !== "boolean") {
    throw shapeError(`${path}.negate`, `expected true or false, got ${describe(args.negate)}`);
  }
  checkCondition(args.condition, `${path}.condition`, depth);
  checkBranch(args.then, `${path}.then`, depth);
  if (args.else !== undefined) checkBranch(args.else, `${path}.else`, depth);
}

function checkCondition(condition: unknown, path: string, depth: number): void {
  const keys = isPlainObject(condition) ? Object.keys(condition) : [];
  if (keys.length !== 1 || (keys[0] !== "selector" && keys[0] !== "tool")) {
    throw shapeError(path, `expected exactly one of { selector } or { tool }, got ${describe(condition)}`);
  }
  const value = (condition as Record<string, unknown>)[keys[0]];
  if (keys[0] === "selector") {
    if (!isPlainObject(value)) throw shapeError(`${path}.selector`, `expected a selector object, got ${describe(value)}`);
    return;
  }
  checkToolCall(value, `${path}.tool`, depth);
}

function checkBranch(branch: unknown, path: string, depth: number): void {
  if (!Array.isArray(branch)) throw shapeError(path, `expected a list of tool calls, got ${describe(branch)}`);
  branch.forEach((entry, i) => checkToolCall(entry, `${path}[${i}]`, depth));
}

/** One `{ <toolName>: <args> }` entry. `<args>` may be omitted (null) for a tool that takes none. */
function checkToolCall(entry: unknown, path: string, depth: number): void {
  const keys = isPlainObject(entry) ? Object.keys(entry) : [];
  if (keys.length !== 1) {
    throw shapeError(path, `expected one tool call shaped { <toolName>: <args> }, got ${describe(entry)}`);
  }
  const name = keys[0];
  const toolArgs = (entry as Record<string, unknown>)[name];
  if (name === CONDITIONAL_NAME) {
    checkRunIf(name, toolArgs, `${path}.${name}`, depth + 1);
    return;
  }
  if (toolArgs !== null && toolArgs !== undefined && !isPlainObject(toolArgs)) {
    throw shapeError(`${path}.${name}`, `expected an arguments object, got ${describe(toolArgs)}`);
  }
}

/**
 * The argument-key check a by-name dispatch gets from the framework, applied to every level so a
 * nested entry (which runs locally and skips the framework's check) fails the same way. A typo
 * such as `els:` fails loudly instead of silently running nothing. Same messages as the
 * framework's validator.
 */
function checkArgKeys(name: string, toolArgs: Record<string, unknown>): void {
  const keys = Object.keys(toolArgs);
  const unknown = keys.filter((k) => !RUN_IF_KEYS.includes(k));
  if (unknown.length > 0) {
    throw new Error(
      `Tool '${name}' was called with unknown argument keys: [${unknown.map((k) => `"${k}"`).join(", ")}]. ` +
        `Expected one of: ${RUN_IF_KEYS.join(", ")}.`,
    );
  }
  const missing = RUN_IF_REQUIRED_KEYS.filter((k) => !keys.includes(k));
  if (missing.length > 0) {
    throw new Error(
      `Tool '${name}' was called without required argument keys: [${missing.map((k) => `"${k}"`).join(", ")}]. ` +
        `Required: ${RUN_IF_REQUIRED_KEYS.join(", ")}.`,
    );
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * The shape of [value] for an error message, never its contents: arguments arrive with memory
 * tokens already resolved, so a value could be a secret, and an escaped or truncated copy would
 * slip past the log's literal secret masking. Object keys are authored names, so they are shown.
 */
function describe(value: unknown): string {
  if (value === undefined || value === null) return "nothing";
  if (Array.isArray(value)) return `a list of ${value.length}`;
  if (isPlainObject(value)) {
    const keys = Object.keys(value);
    return keys.length === 0 ? "an empty object" : `an object with keys [${keys.join(", ")}]`;
  }
  return `a ${typeof value}`;
}

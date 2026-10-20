// `ctx.history`: what ran in this session so far, as the framework recorded it.
//
// The framework writes the history as the run happens — a tool call is in it from the moment it
// starts, and has its outcome the moment it returns — so a read is complete when it's made. That
// is the difference from `ctx.events`, whose captures can lag. The record lives in Kotlin, in the
// process running the trail (the host, or the device for a trail inside a test APK); each read
// is one host-function call. The host returns a flat list linked by ids; this file builds the call
// tree scripts see. Dependency-free: the in-process bundle imports this through `tool-core.ts`.
// The Kotlin side is `xyz.block.trailblaze.history`.

/** Reserved host function behind [ToolHistory]. Mirrors `ToolCallHistoryHostFunctions.kt`. */
export const HOST_FUNCTION_HISTORY_TOOL_CALLS = "trailblaze.history.toolCalls";

/** The parts of a trail run: its setup, the trail itself, and the cleanup after it. */
export type HistorySection = "trailhead" | "trail" | "teardown";

const SECTIONS: readonly HistorySection[] = ["trailhead", "trail", "teardown"];

/** One tool call: a trail step, an action the AI took, or a tool another tool ran. */
export interface ToolCall {
  name: string;
  /**
   * The arguments the tool received, memory tokens resolved. Sensitive values stay tokens like
   * `{{password}}`, and declared sensitive args are masked.
   */
  args: Record<string, unknown>;
  /** The arguments as written, tokens like `{{itemId}}` unresolved. The same as `args` when nothing resolved. */
  rawArgs: Record<string, unknown>;
  /**
   * `null` while the call is still running, which is true of the reading tool itself and every
   * call it runs inside. `message` is the result's text or error, cut to a framework-set length.
   */
  outcome: { success: boolean; message: string | null } | null;
  /** Epoch milliseconds. */
  startedAt: number;
  /** Epoch milliseconds; `null` while running. */
  endedAt: number | null;
  /** The device binding it ran on in a multi-device session, `null` with one device. */
  device: string | null;
  /** The calls this one made, in the order they started. */
  calls: ToolCall[];
}

export interface ToolHistory {
  /**
   * The calls the trail, the AI and the trailhead made, in the order they started, each with the
   * calls it made nested under `calls`. With a section, only that part of the current run: a
   * section that hasn't started yet has no calls.
   */
  toolCalls(section?: HistorySection): Promise<ToolCall[]>;
}

/** A call as the host returns it: flat, linked to its parent by id. */
interface RecordedToolCall extends Omit<ToolCall, "calls"> {
  id: string;
  parentId: string | null;
}

type HostCall = (args: unknown) => Promise<unknown>;

export function createToolHistory(host: unknown): ToolHistory {
  const call = (name: string, args: Record<string, unknown>): Promise<unknown> => {
    const fn = (host as Record<string, HostCall> | undefined)?.[name];
    if (typeof fn !== "function") {
      throw new Error(`ctx.history is unavailable: this runtime has no host function '${name}'.`);
    }
    return fn(args);
  };
  return {
    async toolCalls(section) {
      if (section !== undefined && !SECTIONS.includes(section)) {
        throw new Error(
          `ctx.history.toolCalls: section must be one of ${SECTIONS.map((s) => `"${s}"`).join(", ")}, ` +
            `not ${JSON.stringify(section)}.`,
        );
      }
      const recorded = (await call(HOST_FUNCTION_HISTORY_TOOL_CALLS, section ? { section } : {})) as RecordedToolCall[];
      return toTree(recorded);
    },
  };
}

/**
 * Nests each call under the one it ran inside. A call whose parent isn't in the list (outside the
 * section read, or dropped from a long session) is top-level.
 */
function toTree(recorded: readonly RecordedToolCall[]): ToolCall[] {
  const byId = new Map<string, ToolCall>();
  const roots: ToolCall[] = [];
  for (const call of recorded) {
    const node: ToolCall = {
      name: call.name,
      args: call.args,
      rawArgs: call.rawArgs,
      outcome: call.outcome,
      startedAt: call.startedAt,
      endedAt: call.endedAt,
      device: call.device,
      calls: [],
    };
    byId.set(call.id, node);
    const parent = call.parentId === null ? undefined : byId.get(call.parentId);
    (parent?.calls ?? roots).push(node);
  }
  return roots;
}

/**
 * [calls] as indented text, one line per call, the calls each made indented under it:
 *
 * ```
 * ✓ myapp_addItem(itemId: "item-1")  2.2s
 *   ✓ tapOnElement(text: "Add")  0.7s
 * ✗ assertVisible(text: "item-1")  1.8s  Element not found
 * … teardown()  running
 * ```
 */
export function formatToolCalls(calls: readonly ToolCall[]): string {
  const lines: string[] = [];
  const add = (call: ToolCall, depth: number) => {
    const args = Object.entries(call.args)
      .map(([key, value]) => `${key}: ${JSON.stringify(value)}`)
      .join(", ");
    const mark = call.outcome === null ? "…" : call.outcome.success ? "✓" : "✗";
    const time = call.endedAt === null ? "running" : `${((call.endedAt - call.startedAt) / 1000).toFixed(1)}s`;
    const message = call.outcome && !call.outcome.success && call.outcome.message ? `  ${call.outcome.message}` : "";
    lines.push(`${"  ".repeat(depth)}${mark} ${call.name}(${args})  ${time}${message}`);
    for (const child of call.calls) add(child, depth + 1);
  };
  for (const call of calls) add(call, 0);
  return lines.join("\n");
}

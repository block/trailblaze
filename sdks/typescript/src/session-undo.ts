/** `ctx.session` for typed tools: `addCleanup`, which works in every runtime. */

import type { TrailblazeToolMap, TrailblazeToolMethods } from "./client.js";

/** Hidden Kotlin tool that stores a cleanup call for the session; see [TrailblazeToolSession.addCleanup]. */
export const REGISTER_CLEANUP_TOOL = "trailblaze_registerCleanup";

/** A tool call described, not run: what a cleanup builder's `tools.<tool>(args)` returns. */
export interface TrailblazeToolCall<K extends string = string> {
  readonly tool: K;
  readonly args: unknown;
}

/** The `tools` an `addCleanup` builder gets: `tools.<tool>(args)` describes that call, typed like `ctx.tools`. */
export type TrailblazeCleanupCalls = {
  [K in keyof TrailblazeToolMap & string]: (
    args: TrailblazeToolMap[K] extends { args: infer A } ? A : never,
  ) => TrailblazeToolCall<K>;
};

/** `ctx.session` on a typed tool. */
export interface TrailblazeToolSession {
  /**
   * Adds tool calls to run when the trail ends — after its last step, before the trailhead's
   * teardown — whether it passed, failed or was cancelled. A session that ends without a trail
   * runs them then.
   *
   * ```ts
   * await ctx.session!.addCleanup((tools) => tools.android_adbShell({ command: ["sh", "-c", restore] }));
   * ```
   *
   * Call it right after changing the device, returning the calls that undo exactly what this run
   * changed. **The builder runs now; its arguments are fixed at this call.** Only the calls it
   * returns run at the trail's end, so changing a variable afterwards changes nothing. Trailblaze
   * stores those calls as data, so this works in every runtime, including on-device. Calls in one list run
   * in the order written; separate `addCleanup`s run newest first. A call added more than once,
   * in one list or across several, runs once, at the last point it would have run, so the
   * original state is still restored last. Each one logs like any other step.
   */
  addCleanup(
    build: (tools: TrailblazeCleanupCalls) => TrailblazeToolCall | readonly TrailblazeToolCall[],
  ): Promise<void>;
}

// `tools.<tool>(args)` only describes the call. `then` stays undefined so `tools` is never a thenable.
const cleanupCalls = new Proxy(
  {},
  {
    get: (_, key) =>
      typeof key !== "string" || key === "then" ? undefined : (args: unknown) => ({ tool: key, args: args ?? {} }),
  },
) as TrailblazeCleanupCalls;

// How many cleanup builders are running. A builder is synchronous, so this is only non-zero
// inside one, and `ctx.tools` (see [guardedDuringCleanupBuild]) refuses to run anything then.
let buildingCleanups = 0;

/**
 * `ctx.tools`, refusing every call while an `addCleanup` builder runs: in there, `ctx.tools.x(...)`
 * would run the tool now instead of describing it for the trail's end.
 */
export function guardedDuringCleanupBuild(tools: TrailblazeToolMethods): TrailblazeToolMethods {
  return new Proxy(tools, {
    get(target, key, receiver) {
      if (buildingCleanups > 0 && typeof key === "string") {
        throw new Error(
          `ctx.tools.${key} inside ctx.session.addCleanup((tools) => ...) would run it now. ` +
            `Use the builder's tools.${key}(args), which describes the call for the trail's end.`,
        );
      }
      return Reflect.get(target, key, receiver);
    },
  });
}

function isToolCall(value: unknown): value is TrailblazeToolCall {
  return typeof value === "object" && value != null && typeof (value as { tool?: unknown }).tool === "string" &&
    (value as { tool: string }).tool.length > 0;
}

/** Builds `ctx.session`; [tools] reaches the hidden register tool. */
export function toolSessionFor(tools: TrailblazeToolMethods): TrailblazeToolSession {
  const register = (tools as unknown as Record<string, (args: unknown) => Promise<unknown>>)[REGISTER_CLEANUP_TOOL]!;
  return {
    async addCleanup(build) {
      if (typeof build !== "function") {
        throw new TypeError("ctx.session.addCleanup((tools) => tools.<tool>(args)): pass a function.");
      }
      let built: unknown;
      buildingCleanups++;
      try {
        built = build(cleanupCalls);
      } finally {
        buildingCleanups--;
      }
      if (typeof (built as { then?: unknown } | null)?.then === "function") {
        throw new TypeError("ctx.session.addCleanup: return tools.<tool>(args) right away, not a promise.");
      }
      const calls = Array.isArray(built) ? built : [built];
      // An empty list means this run changed nothing, so there is nothing to undo.
      if (calls.length === 0) return;
      if (!calls.every(isToolCall)) {
        throw new TypeError("ctx.session.addCleanup: return tools.<tool>(args), or a list of them.");
      }
      // A list is one registration, so Trailblaze adds all of it or, if one call is bad, none.
      await register(
        calls.length === 1
          ? { tool: calls[0]!.tool, args: calls[0]!.args }
          : { calls: calls.map((call) => ({ tool: call.tool, args: call.args })) },
      );
    },
  };
}

/**
 * `ctx.session` for a call. A context that sets `session` to `undefined` or `null` on purpose (a
 * tool test standing in for a runtime with no session) gets none. A runtime's context has no
 * `session` key, so it gets `addCleanup`.
 */
export function toolSessionForContext(
  legacyCtx: object | null | undefined,
  tools: TrailblazeToolMethods,
): TrailblazeToolSession | undefined {
  if (legacyCtx != null && Object.prototype.hasOwnProperty.call(legacyCtx, "session") &&
    (legacyCtx as { session?: unknown }).session == null) {
    return undefined;
  }
  return toolSessionFor(tools);
}

// Fixture for `QuickJsTrailheadTeardownTest`: a real SDK-authored trailhead with `teardown`,
// bundled the production on-device way and run through the real QuickJS engine and runner.
// No device calls: the "app" is session memory, so the test drives every branch by setting keys.

import { formatToolCalls, ToolError, trailblaze } from "@trailblaze/scripting";

export const fixture_signIn = trailblaze.trailhead<{ persona: string }>(
  {
    async teardown(args, ctx, trailRun) {
      const created = ctx.memory.get("fixture_lastItemId");
      if (trailRun.outcome !== "passed") return { reusable: false, reason: `trail ${trailRun.outcome}` };
      return { reusable: true, reason: `signed in as ${args.persona}`, state: { leftItems: created ? [created] : [] } };
    },
  },
  async (args, ctx) => {
    const previous = ctx.previousRun;
    if (previous) {
      const reuse = previous.reuse("still signed in", async () => {
        const left = (previous.state?.leftItems as string[] | undefined) ?? [];
        if (ctx.memory.get("fixture_catchUpFails") === "true") throw new Error(`could not remove ${left.join(",")}`);
        return `reused ${previous.sessionId}, removed ${left.join(",")}`;
      });
      if (ctx.memory.get("fixture_swallowCatchUpFailure") !== "true") return reuse;
      // A handler that catches the failed catch-up and carries on as if the setup were fine.
      try {
        return await reuse;
      } catch {
        ctx.memory.set("fixture_carriedOn", "true");
        return "carried on after a failed catch-up";
      }
    }
    return `full setup as ${args.persona}`;
  },
);

/** A trail step that creates something and remembers it for the trailhead's teardown. */
export const fixture_addItem = trailblaze.tool<{ id: string }>(async (args, ctx) => {
  // Committed only if this call succeeds, so `ctx.history` shows it on a passing call alone.
  ctx.memory.set("fixture_lastItemId", args.id);
  if (ctx.memory.get("fixture_addItemThrowsCyclic") === "true") {
    // Error data that can't be serialized, so the dispatcher sends the error without it.
    const data: Record<string, unknown> = {};
    data.self = data;
    throw new ToolError(`could not finish adding ${args.id}`, { data });
  }
  return `added ${args.id}`;
});

/**
 * A trailhead whose teardown picks its cleanup from `ctx.history`: the calls of the trail section
 * only, handed back as `state` so the test can read what teardown saw. Written the older
 * `trailblaze.tool({ trailhead })` way, which still has to work.
 */
export const fixture_historyTrailhead = trailblaze.tool<{ persona: string }>(
  {
    trailhead: {
      async teardown(_args, ctx) {
        const calls = await ctx.history.toolCalls("trail");
        return {
          reusable: false,
          reason: `the trail made ${calls.length} calls`,
          state: {
            calls: calls.map((c) => ({
              name: c.name,
              args: c.args as { [key: string]: string },
              rawArgs: c.rawArgs as { [key: string]: string },
              success: c.outcome?.success ?? null,
              nested: c.calls.length,
              memory: c.memory.map((m) => `${m.op} ${m.key}=${m.value}`),
            })),
            text: formatToolCalls(calls),
          },
        };
      },
    },
  },
  async (args) => `full setup as ${args.persona}`,
);

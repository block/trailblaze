// Fixture for `QuickJsTrailheadTeardownTest`: a real SDK-authored trailhead with `teardown`,
// bundled the production on-device way and run through the real QuickJS engine and runner.
// No device calls: the "app" is session memory, so the test drives every branch by setting keys.

import { ToolError, trailblaze } from "@trailblaze/scripting";

export const fixture_signIn = trailblaze.tool<{ persona: string }>(
  {
    trailhead: {
      to: "fixture/home_signed_in",
      async teardown(args, ctx, trailRun) {
        const created = trailRun.notes.flatMap((n) => (n.created ? [n.created.id] : []));
        if (trailRun.outcome !== "passed") return { reusable: false, reason: `trail ${trailRun.outcome}` };
        return { reusable: true, reason: `signed in as ${args.persona}`, state: { leftItems: created } };
      },
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

/** A trail step that creates something and tells the trailhead's teardown about it. */
export const fixture_addItem = trailblaze.tool<{ id: string }>(async (args, ctx) => {
  ctx.trailhead.note({ created: { kind: "item", id: args.id } });
  if (ctx.memory.get("fixture_addItemThrowsCyclic") === "true") {
    // Error data that can't be serialized, so the dispatcher sends the error without it.
    const data: Record<string, unknown> = {};
    data.self = data;
    throw new ToolError(`could not finish adding ${args.id}`, { data });
  }
  // Results that can't be serialized: a plain value, and an envelope whose structured content is.
  if (ctx.memory.get("fixture_addItemReturns") === "cyclic") {
    const value: Record<string, unknown> = { id: args.id };
    value.self = value;
    return value as unknown as string;
  }
  if (ctx.memory.get("fixture_addItemReturns") === "cyclicEnvelope") {
    const structured: Record<string, unknown> = { id: args.id };
    structured.self = structured;
    return { content: [{ type: "text", text: `added ${args.id}` }], structuredContent: structured } as unknown as string;
  }
  return `added ${args.id}`;
});

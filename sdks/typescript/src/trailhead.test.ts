// Teardown on the typed-tool adapter: what a trailhead's handler sees as `ctx.previousRun`,
// what it reports back on `_meta.trailblaze.trailhead`, and how `trailhead.teardown` is called.

import { describe, expect, test } from "bun:test";

import { trailblaze as inProcess } from "./in-process.js";
import { trailheadToolSpec } from "./tool-core.js";
import { tool, trailhead } from "./tool.js";
import { TRAILHEAD_REPORT_ON_ERROR } from "./trailhead.js";

type Result = { content: unknown[]; _meta?: { trailblaze?: { trailhead?: unknown; memoryDelta?: unknown } } };

const previousRun = { sessionId: "s-1", reason: "signed in, nothing changed", state: { accountId: "a1" } };

function hostClient(calls: { name: string; args: unknown }[] = [], answer: unknown = []) {
  return {
    tools: {},
    host: new Proxy({}, {
      get: (_t, name) => async (args: unknown) => {
        calls.push({ name: String(name), args });
        return answer;
      },
    }),
  } as never;
}

describe("ctx.previousRun in a handler", () => {
  test("receives previousRun from the envelope and reports the reuse on the result", async () => {
    let seen: { sessionId?: string; reason?: string; state?: unknown } | null = null;
    const def = trailhead<{ email: string }>(async (_input, ctx) => {
      const last = ctx.previousRun;
      seen = last && { sessionId: last.sessionId, reason: last.reason, state: last.state };
      await last?.reuse("already signed in");
      return "ok";
    });
    const out = (await def({ email: "x" }, { previousRun } as never, hostClient())) as Result;
    expect(seen).toEqual(previousRun);
    expect(out._meta?.trailblaze?.trailhead).toEqual({
      reuse: { reason: "already signed in", startedAtMs: expect.any(Number), ok: true },
    });
  });

  test("previousRun carries the waypoint the last run's trailhead ended on", async () => {
    let seen: string | undefined;
    const def = trailhead(async (_input, ctx) => {
      seen = ctx.previousRun?.trailheadEndWaypoint;
      return "ok";
    });
    await def({}, { previousRun: { ...previousRun, trailheadEndWaypoint: "app/home" } } as never, hostClient());
    expect(seen).toBe("app/home");
  });

  test("previousRun has no trailheadEndWaypoint when the host checked none", async () => {
    let seen: unknown = "unset";
    const def = trailhead(async (_input, ctx) => {
      seen = ctx.previousRun?.trailheadEndWaypoint;
      return "ok";
    });
    await def({}, { previousRun } as never, hostClient());
    expect(seen).toBeUndefined();
  });

  test("previousRun is null when the host sent none, and the result has no trailhead report", async () => {
    let seen: unknown = "unset";
    const def = tool(async (_input, ctx) => {
      seen = ctx.previousRun;
      return "ok";
    });
    const out = (await def({}, {} as never, hostClient())) as Result;
    expect(seen).toBeNull();
    expect(out._meta?.trailblaze?.trailhead).toBeUndefined();
  });

  test("reuse without a reason is rejected", async () => {
    const def = tool(async (_input, ctx) => {
      await ctx.previousRun!.reuse("");
      return "ok";
    });
    await expect(def({}, { previousRun } as never, hostClient())).rejects.toThrow(/reason/);
  });

  test("reuse runs the catch-up and resolves with its result", async () => {
    const steps: string[] = [];
    const def = tool(async (_input, ctx) => {
      const cleared = await ctx.previousRun!.reuse("on home", async () => {
        steps.push("clear cart");
        return 2;
      });
      return `cleared ${cleared}`;
    });
    const out = (await def({}, { previousRun } as never, hostClient())) as { content: Array<{ text?: string }> };
    expect(steps).toEqual(["clear cart"]);
    expect(JSON.stringify(out)).toContain("cleared 2");
  });

  test("a catch-up that throws fails the trailhead and reports a failed reuse", async () => {
    const def = tool(async (_input, ctx) => {
      await ctx.previousRun!.reuse("on home", async () => {
        throw new Error("cart would not clear");
      });
      return "ok";
    });
    let caught: unknown;
    try {
      await def({}, { previousRun } as never, hostClient());
    } catch (e) {
      caught = e;
    }
    expect((caught as Error).message).toBe("cart would not clear");
    expect((caught as Record<symbol, unknown>)[TRAILHEAD_REPORT_ON_ERROR]).toEqual({
      reuse: { reason: "on home", startedAtMs: expect.any(Number), ok: false, error: "cart would not clear" },
    });
  });

  test("reuse can only be called once", async () => {
    const def = tool(async (_input, ctx) => {
      await ctx.previousRun!.reuse("on home");
      await ctx.previousRun!.reuse("again");
      return "ok";
    });
    await expect(def({}, { previousRun } as never, hostClient())).rejects.toThrow(/already called/);
  });

  test("a reuse the handler made before throwing rides the thrown error", async () => {
    const def = tool(async (_input, ctx) => {
      await ctx.previousRun!.reuse("on home");
      throw new Error("passcode screen never appeared");
    });
    let caught: unknown;
    try {
      await def({}, { previousRun } as never, hostClient());
    } catch (e) {
      caught = e;
    }
    expect((caught as Error).message).toBe("passcode screen never appeared");
    expect((caught as Record<symbol, unknown>)[TRAILHEAD_REPORT_ON_ERROR]).toEqual({
      reuse: { reason: "on home", startedAtMs: expect.any(Number), ok: true },
    });
  });

  test("the reuse report survives a thrown string and a frozen error", async () => {
    for (const thrown of ["sign-in failed", Object.freeze(new Error("sign-in failed"))]) {
      const def = tool(async (_input, ctx) => {
        await ctx.previousRun!.reuse("on home");
        throw thrown;
      });
      let caught: unknown;
      try {
        await def({}, { previousRun } as never, hostClient());
      } catch (e) {
        caught = e;
      }
      expect((caught as Error).message).toBe("sign-in failed");
      expect((caught as Error).cause).toBe(thrown);
      expect((caught as Record<symbol, unknown>)[TRAILHEAD_REPORT_ON_ERROR]).toEqual({
        reuse: { reason: "on home", startedAtMs: expect.any(Number), ok: true },
      });
    }
  });
});

describe("trailhead.teardown", () => {
  test("rides the typed definition and receives the trailhead's args, a ctx, and the trail run", async () => {
    let received: { args?: unknown; outcome?: unknown; previousRun?: unknown } = {};
    const def = tool<{ email: string }>(
      {
        trailhead: {
          dynamic: true,
          teardown: async (args, ctx, trailRun) => {
            received = { args, outcome: trailRun.outcome, previousRun: ctx.previousRun };
            return { reusable: true, reason: "nothing to undo", state: { n: 1 } };
          },
        },
      } as never,
      async () => "ok",
    );
    expect(typeof def.teardown).toBe("function");
    const result = await def.teardown!({ email: "x" }, { previousRun } as never, hostClient(), { outcome: "passed" });
    expect(result).toEqual({ reusable: true, reason: "nothing to undo", state: { n: 1 } });
    expect(received).toEqual({ args: { email: "x" }, outcome: "passed", previousRun: null });
  });

  test("a tool without teardown has no member", () => {
    const def = tool(async () => "ok");
    expect(def.teardown).toBeUndefined();
  });

  test("a result without a reason is rejected", async () => {
    const def = trailhead({ teardown: async () => ({ reusable: true }) as never }, async () => "ok");
    await expect(def.teardown!({}, {} as never, hostClient(), { outcome: "passed" })).rejects.toThrow(/reason/);
  });

  test("an unknown outcome from the host is rejected before teardown runs", async () => {
    let ran = false;
    const def = trailhead({ teardown: async () => { ran = true; return { reusable: false, reason: "x" }; } }, async () => "ok");
    await expect(def.teardown!({}, {} as never, hostClient(), { outcome: "exploded" })).rejects.toThrow(/outcome/);
    expect(ran).toBe(false);
  });

  test("events.query sends the match to the host, with a RegExp as $regex/$flags", async () => {
    const calls: { name: string; args: unknown }[] = [];
    const answer = [{ timeMs: 5, data: { url: "/v1/items" } }];
    let got: unknown;
    const def = trailhead(
      {
        teardown: async (_args, _ctx, trailRun) => {
          got = await trailRun.events.query("network", { match: { method: "POST", url: /\/v1\/items/i }, limit: 5 });
          return { reusable: false, reason: "made items" };
        },
      },
      async () => "ok",
    );
    await def.teardown!({}, {} as never, hostClient(calls, answer), { outcome: "passed" });
    expect(got).toEqual(answer);
    expect(calls).toEqual([
      {
        name: "trailblaze.trailEvents.query",
        args: { stream: "network", match: { method: "POST", url: { $regex: "\\/v1\\/items", $flags: "i" } }, limit: 5 },
      },
    ]);
  });
});

describe("trailblaze.trailhead", () => {
  const teardown = async () => ({ reusable: false, reason: "made items" });

  test("is a typed tool whose spec teardown becomes the definition's teardown", async () => {
    const def = trailhead<{ email: string }>({ supportedPlatforms: ["android"], teardown }, async ({ email }) => `hi ${email}`);
    expect(await def({ email: "x" }, {} as never, hostClient())).toBe("hi x");
    expect(await def.teardown!({}, {} as never, hostClient(), { outcome: "passed" })).toEqual({
      reusable: false,
      reason: "made items",
    });
  });

  test("takes a bare handler, with no teardown member", async () => {
    const def = trailhead(async () => "ok");
    expect(await def({}, {} as never, hostClient())).toBe("ok");
    expect(def.teardown).toBeUndefined();
  });

  test("stands for a tool spec with the trailhead block filled from to and teardown", () => {
    expect(trailheadToolSpec({ supportedPlatforms: ["ios"], to: "app/home", teardown })).toEqual({
      supportedPlatforms: ["ios"],
      trailhead: { to: "app/home", teardown },
    });
    expect(trailheadToolSpec()).toEqual({ trailhead: {} });
  });

  test("rejects a spec with no handler", () => {
    expect(() => (trailhead as (spec: unknown) => unknown)({ to: "app/home" })).toThrow(/handler must be a function/);
  });

  test("the in-process profile has it too", async () => {
    const def = inProcess.trailhead({ teardown }, async () => "ok");
    expect(await def.teardown!({}, {} as never, hostClient(), { outcome: "passed" })).toEqual({
      reusable: false,
      reason: "made items",
    });
  });

  test("trailblaze.tool with a trailhead block still carries teardown", async () => {
    const def = tool({ trailhead: { teardown } }, async () => "ok");
    expect(await def.teardown!({}, {} as never, hostClient(), { outcome: "passed" })).toEqual({
      reusable: false,
      reason: "made items",
    });
  });
});

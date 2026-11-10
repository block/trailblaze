import { describe, expect, test } from "bun:test";

import { guardedDuringCleanupBuild, toolSessionFor, toolSessionForContext } from "./session-undo.js";
import type { TrailblazeToolMethods } from "./client.js";

describe("ctx.session.addCleanup", () => {
  const recordingTools = (calls: unknown[]) =>
    new Proxy({}, { get: (_, name) => async (args: unknown) => { calls.push({ name, args }); } }) as TrailblazeToolMethods;
  type Untyped = (t: Record<string, (args?: unknown) => unknown>) => unknown;
  const add = (session: ReturnType<typeof toolSessionFor>, build: Untyped) =>
    session.addCleanup(build as unknown as Parameters<typeof session.addCleanup>[0]);
  const stored = (tool: string, args: unknown) => ({ name: "trailblaze_registerCleanup", args: { tool, args } });

  test("stores the call the builder returns through the hidden register tool", async () => {
    const calls: unknown[] = [];
    await add(toolSessionFor(recordingTools(calls)), (t) => t.android_adbShell!({ command: ["sh", "-c", "x"] }));
    expect(calls).toEqual([stored("android_adbShell", { command: ["sh", "-c", "x"] })]);
  });

  test("stores a list as one registration, in the order written; no args is {}", async () => {
    const calls: unknown[] = [];
    await add(toolSessionFor(recordingTools(calls)), (t) => [t.first!({ n: 1 }), t.second!()]);
    expect(calls).toEqual([
      { name: "trailblaze_registerCleanup", args: { calls: [{ tool: "first", args: { n: 1 } }, { tool: "second", args: {} }] } },
    ]);
  });

  test("an empty list registers nothing", async () => {
    const calls: unknown[] = [];
    await add(toolSessionFor(recordingTools(calls)), () => []);
    expect(calls).toEqual([]);
  });

  test("the builder only describes calls: it runs nothing, and t is not a thenable", async () => {
    const calls: unknown[] = [];
    let seen: unknown;
    await add(toolSessionFor(recordingTools(calls)), (t) => {
      seen = (t as { then?: unknown }).then;
      const call = t.android_adbShell!({ command: ["x"] });
      expect(calls).toEqual([]);
      return call;
    });
    expect(seen).toBeUndefined();
    expect(calls).toHaveLength(1);
  });

  test("rejects a non-function, an async builder and anything but calls, before storing any", async () => {
    const calls: unknown[] = [];
    const session = toolSessionFor(recordingTools(calls));
    await expect(add(session, "nope" as unknown as Untyped)).rejects.toThrow("pass a function");
    await expect(add(session, async (t) => t.a!({}))).rejects.toThrow("not a promise");
    await expect(add(session, (t) => [t.a!({}), { tool: "" }])).rejects.toThrow("list of them");
    await expect(add(session, () => undefined)).rejects.toThrow("list of them");
    expect(calls).toEqual([]);
  });

  test("ctx.tools refuses to run anything inside a builder, and works again after it", async () => {
    const calls: unknown[] = [];
    const raw = recordingTools(calls);
    const ctxTools = guardedDuringCleanupBuild(raw) as unknown as Record<string, (args: unknown) => Promise<void>>;
    const session = toolSessionFor(raw);
    await expect(add(session, (tools) => {
      void ctxTools.android_adbShell!({ command: ["oops"] });
      return tools.android_adbShell!({ command: ["ok"] });
    })).rejects.toThrow("Use the builder's tools.android_adbShell(args)");
    expect(calls).toEqual([]);
    await ctxTools.android_adbShell!({ command: ["later"] });
    expect(calls).toHaveLength(1);
  });

  test("a context that sets session to undefined gets none; one without the key gets addCleanup", async () => {
    expect(toolSessionForContext({ session: undefined }, recordingTools([]))).toBeUndefined();
    const calls: unknown[] = [];
    await add(toolSessionForContext({}, recordingTools(calls))!, (t) => t.noop!({}));
    expect(calls).toHaveLength(1);
  });
});

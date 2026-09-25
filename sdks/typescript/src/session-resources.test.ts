import { afterEach, describe, expect, test } from "bun:test";

import {
  _clearSessionResourcesForTest,
  _setSessionResourceCleanupTimeoutForTest,
  releaseSessionResources,
  sessionResourcesFor,
} from "./session-resources.js";

afterEach(() => {
  _clearSessionResourcesForTest();
});

describe("session resources", () => {
  test("releases callbacks in reverse registration order and only once", async () => {
    const calls: string[] = [];
    const resources = sessionResourcesFor("session-1");
    resources.registerCleanup(() => { calls.push("first"); });
    resources.registerCleanup(() => { calls.push("second"); });

    await releaseSessionResources("session-1");
    await releaseSessionResources("session-1");

    expect(calls).toEqual(["second", "first"]);
  });

  test("an explicit unregister wins over session release", async () => {
    const calls: string[] = [];
    const unregister = sessionResourcesFor("session-2").registerCleanup(() => {
      calls.push("released");
    });
    unregister();

    await releaseSessionResources("session-2");

    expect(calls).toEqual([]);
  });

  test("drains every callback while reporting only the aggregate failure", async () => {
    const calls: string[] = [];
    const resources = sessionResourcesFor("session-3");
    resources.registerCleanup(() => { calls.push("first"); });
    resources.registerCleanup(() => {
      calls.push("failing");
      throw new Error("opaque-handle-must-not-escape");
    });
    resources.registerCleanup(() => { calls.push("last"); });

    await expect(releaseSessionResources("session-3")).rejects.toThrow(
      "1 session resource cleanup callback(s) failed.",
    );
    expect(calls).toEqual(["last", "failing", "first"]);
  });

  test("passes the finalizer's live tools to cleanup callbacks", async () => {
    const tools = {} as never;
    let received: unknown;
    sessionResourcesFor("session-4").registerCleanup((context) => {
      received = context.tools;
    });

    await releaseSessionResources("session-4", { tools });

    expect(received).toBe(tools);
  });

  test("times out one cleanup and continues draining later registrations", async () => {
    _setSessionResourceCleanupTimeoutForTest(1);
    const calls: string[] = [];
    const resources = sessionResourcesFor("session-5");
    resources.registerCleanup(() => { calls.push("first"); });
    resources.registerCleanup(() => new Promise<void>(() => {}));
    resources.registerCleanup(() => { calls.push("last"); });

    await expect(releaseSessionResources("session-5")).rejects.toThrow(
      "1 session resource cleanup callback(s) failed.",
    );
    expect(calls).toEqual(["last", "first"]);
  });

  test("starts every cleanup before waiting for an earlier one to settle", async () => {
    const calls: string[] = [];
    let releaseBlocked: (() => void) | undefined;
    const blocked = new Promise<void>((resolve) => { releaseBlocked = resolve; });
    const resources = sessionResourcesFor("session-6");
    resources.registerCleanup(() => { calls.push("first"); });
    resources.registerCleanup(async () => {
      calls.push("blocked");
      await blocked;
    });
    resources.registerCleanup(() => { calls.push("last"); });

    const release = releaseSessionResources("session-6");
    await Promise.resolve();
    await Promise.resolve();

    expect(calls).toEqual(["last", "blocked", "first"]);
    releaseBlocked?.();
    await release;
  });
});

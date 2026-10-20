// `ctx.history` on the typed-tool adapter: which host function each read calls, with what, the
// call tree built from the host's flat answer, and the text `formatToolCalls` prints. What the
// host answers is covered on the Kotlin side.

import { describe, expect, test } from "bun:test";

import { formatToolCalls, HOST_FUNCTION_HISTORY_TOOL_CALLS, type ToolCall } from "./history.js";
import { createMockClient, createMockContext } from "./testing.js";
import { tool } from "./tool.js";

function hostClient(calls: { name: string; args: unknown }[], answer: (name: string) => unknown) {
  return {
    tools: {},
    host: new Proxy({}, {
      get: (_t, name) => async (args: unknown) => {
        calls.push({ name: String(name), args });
        return answer(String(name));
      },
    }),
  } as never;
}

/** A call as the host sends it: flat, linked to its parent by id. */
function recorded(id: string, name: string, parentId: string | null, extra: Record<string, unknown> = {}) {
  return {
    id,
    name,
    args: {},
    rawArgs: {},
    outcome: { success: true, message: null },
    startedAt: 1_000,
    endedAt: 2_000,
    parentId,
    device: null,
    sectionId: "s2",
    ...extra,
  };
}

const addItem = recorded("c3", "myapp_addItem", null, { args: { itemId: "i-1" }, rawArgs: { itemId: "{{itemId}}" } });

describe("ctx.history", () => {
  test("toolCalls(section) asks the host for that part of the run by name", async () => {
    const calls: { name: string; args: unknown }[] = [];
    let seen: ToolCall[] = [];
    const def = tool(async (_input, ctx) => {
      seen = await ctx.history.toolCalls("trail");
      return "ok";
    });
    await def({}, {} as never, hostClient(calls, () => [addItem]));
    expect(calls).toEqual([{ name: HOST_FUNCTION_HISTORY_TOOL_CALLS, args: { section: "trail" } }]);
    expect(seen).toEqual([
      {
        name: "myapp_addItem",
        args: { itemId: "i-1" },
        rawArgs: { itemId: "{{itemId}}" },
        outcome: { success: true, message: null },
        startedAt: 1_000,
        endedAt: 2_000,
        device: null,
        calls: [],
      },
    ]);
  });

  test("toolCalls() asks for the whole session", async () => {
    const calls: { name: string; args: unknown }[] = [];
    const def = tool(async (_input, ctx) => {
      await ctx.history.toolCalls();
      return "ok";
    });
    await def({}, {} as never, hostClient(calls, () => []));
    expect(calls).toEqual([{ name: HOST_FUNCTION_HISTORY_TOOL_CALLS, args: {} }]);
  });

  test("each call holds the calls it made; one whose parent wasn't returned is top-level", async () => {
    const flat = [
      recorded("c1", "myapp_addItem", null),
      recorded("c2", "tapOnElement", "c1"),
      recorded("c3", "inputText", "c2"),
      recorded("c4", "assertVisible", null),
      recorded("c9", "orphan", "c0"),
    ];
    let shape: unknown;
    const def = tool(async (_input, ctx) => {
      const nest = (calls: ToolCall[]): unknown => calls.map((c) => ({ [c.name]: nest(c.calls) }));
      shape = nest(await ctx.history.toolCalls());
      return "ok";
    });
    await def({}, {} as never, hostClient([], () => flat));
    expect(shape).toEqual([
      { myapp_addItem: [{ tapOnElement: [{ inputText: [] }] }] },
      { assertVisible: [] },
      { orphan: [] },
    ]);
  });

  test("an unknown section is refused before the host is asked", async () => {
    const calls: { name: string; args: unknown }[] = [];
    const def = tool(async (_input, ctx) => {
      await ctx.history.toolCalls("s2" as never);
      return "ok";
    });
    await expect(def({}, {} as never, hostClient(calls, () => []))).rejects.toThrow(
      'ctx.history.toolCalls: section must be one of "trailhead", "trail", "teardown", not "s2".',
    );
    expect(calls).toEqual([]);
  });

  test("teardown gets ctx.history too", async () => {
    const calls: { name: string; args: unknown }[] = [];
    let names: string[] = [];
    const def = tool(
      {
        trailhead: {
          dynamic: true,
          async teardown(_args, ctx) {
            names = (await ctx.history.toolCalls("trail")).map((c) => c.name);
            return { reusable: false, reason: names.join(",") };
          },
        },
      } as never,
      async () => "ok",
    );
    const result = await def.teardown!({}, {} as never, hostClient(calls, () => [addItem]), {
      outcome: "passed",
      notes: [],
    });
    expect(result.reason).toBe("myapp_addItem");
    expect(calls).toEqual([{ name: HOST_FUNCTION_HISTORY_TOOL_CALLS, args: { section: "trail" } }]);
  });

  test("a runtime without the host function says so when read", async () => {
    const def = tool(async (_input, ctx) => {
      await ctx.history.toolCalls();
      return "ok";
    });
    await expect(def({}, {} as never, { tools: {}, host: {} } as never)).rejects.toThrow(
      /ctx\.history is unavailable/,
    );
  });

  test("a tool's own test stubs the read on the mock client with the host's flat list", async () => {
    const client = createMockClient();
    client.stubHost(HOST_FUNCTION_HISTORY_TOOL_CALLS, {
      value: [recorded("c1", "myapp_addItem", null), recorded("c2", "tapOnElement", "c1")],
    });
    const def = tool(async (_input, ctx) => {
      const [add] = await ctx.history.toolCalls("trail");
      return `${add.name} > ${add.calls.map((c) => c.name).join(",")}`;
    });
    const result = await def({}, createMockContext({ platform: "android" }), client);
    expect(JSON.stringify(result)).toContain("myapp_addItem > tapOnElement");
  });
});

describe("formatToolCalls", () => {
  const node = (name: string, extra: Partial<ToolCall> = {}): ToolCall => ({
    name,
    args: {},
    rawArgs: {},
    outcome: { success: true, message: "done" },
    startedAt: 1_000,
    endedAt: 3_200,
    device: null,
    calls: [],
    ...extra,
  });

  test("prints one line per call, nested calls indented, failures with their message", () => {
    const text = formatToolCalls([
      node("myapp_addItem", {
        args: { itemId: "item-1", count: 2 },
        calls: [node("tapOnElement", { args: { text: "Add" }, endedAt: 1_700 })],
      }),
      node("assertVisible", { args: { text: "item-1" }, outcome: { success: false, message: "Element not found" }, endedAt: 2_800 }),
      node("teardown", { outcome: null, endedAt: null }),
    ]);
    expect(text).toBe(
      [
        '✓ myapp_addItem(itemId: "item-1", count: 2)  2.2s',
        '  ✓ tapOnElement(text: "Add")  0.7s',
        '✗ assertVisible(text: "item-1")  1.8s  Element not found',
        "… teardown()  running",
      ].join("\n"),
    );
  });

  test("prints nothing for no calls", () => {
    expect(formatToolCalls([])).toBe("");
  });
});

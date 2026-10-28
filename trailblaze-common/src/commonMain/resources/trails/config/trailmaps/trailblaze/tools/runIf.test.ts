// Unit tests for the framework `runIf` conditional tool. Drives the typed tool directly (no
// daemon, no device) with the mock client + context from `@trailblaze/scripting/testing`. Run via
// `bun test`.
//
// These pin the branch logic: the resolved condition (selector presence or non-fatal tool dispatch),
// `negate`, which branch runs, the no-op when `else` is absent and the condition is false, and that a
// nested `{ runIf: {...} }` entry runs its own branch without dispatching `runIf` by name
// (a by-name dispatch re-enters this bundle's engine, which the framework refuses). They also pin
// the guard rails: the whole tree is checked before anything runs, nesting stops at 2, and a
// condition that cannot run fails instead of reading as false.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext, createQueuedFindMatchesClient } from "@trailblaze/scripting/testing";
import type { MatchDescriptor } from "@trailblaze/scripting";

import { runIf, type RunIfArgs } from "./runIf";

const MATCH: MatchDescriptor = { indexPath: [0, 1] };
const ctx = () => createMockContext({ platform: "android", device: { driverType: "android-ondevice-accessibility" } });
const toolsOf = (client: { calls: Array<{ tool: string }> }) => client.calls.map((c) => c.tool);

const PRESENT_SELECTOR: RunIfArgs["condition"] = {
  selector: { androidAccessibility: { textRegex: "Confirm" } },
};
const TAP_CONFIRM = { tapOnElementBySelector: { nodeSelector: { androidAccessibility: { textRegex: "Confirm" } } } };
const TAP_CANCEL = { tapOnElementBySelector: { nodeSelector: { androidAccessibility: { textRegex: "Cancel" } } } };

/**
 * A mock client whose [name] calls fail the way the framework reports a call that never reached
 * its tool (unknown name, rejected arguments): an error marked `dispatchFailed`.
 */
function clientWithUnreachableTool(name: string, message: string) {
  const client = createMockClient();
  const tools = new Proxy(client.tools, {
    get: (target, prop) =>
      prop === name
        ? async () => {
            throw Object.assign(new Error(message), { dispatchFailed: true });
          }
        : Reflect.get(target, prop),
  });
  return { ...client, tools } as typeof client;
}

describe("runIf - selector condition", () => {
  test("condition true → runs `then`, skips `else`", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]); // selector present

    const result = await runIf(
      { condition: PRESENT_SELECTOR, then: [TAP_CONFIRM], else: [TAP_CANCEL] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["findSelectorMatches", "tapOnElementBySelector"]);
    expect(result).toContain("condition=true");
    expect(result).toContain("ran=then");
  });

  test("condition false → runs `else`, skips `then`", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[]]); // selector absent

    const result = await runIf(
      { condition: PRESENT_SELECTOR, then: [TAP_CONFIRM], else: [TAP_CANCEL] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["findSelectorMatches", "tapOnElementBySelector"]);
    expect(client.calls[1].args).toEqual(TAP_CANCEL.tapOnElementBySelector);
    expect(result).toContain("condition=false");
    expect(result).toContain("ran=else");
  });

  test("condition false and no `else` → no-op success (only the probe ran)", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[]]); // selector absent

    const result = await runIf({ condition: PRESENT_SELECTOR, then: [TAP_CONFIRM] }, ctx(), client);

    expect(toolsOf(client)).toEqual(["findSelectorMatches"]);
    expect(result).toContain("condition=false");
    expect(result).toContain("(0 tools)");
  });
});

describe("runIf - negate", () => {
  test("negate flips a present selector to false → runs `else`", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]); // selector present

    const result = await runIf(
      { condition: PRESENT_SELECTOR, negate: true, then: [TAP_CONFIRM], else: [TAP_CANCEL] },
      ctx(),
      client,
    );

    expect(client.calls[1].args).toEqual(TAP_CANCEL.tapOnElementBySelector);
    expect(result).toContain("condition=false");
  });

  test("negate flips an absent selector to true → runs `then`", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[]]); // selector absent

    const result = await runIf({ condition: PRESENT_SELECTOR, negate: true, then: [TAP_CONFIRM] }, ctx(), client);

    expect(client.calls[1].args).toEqual(TAP_CONFIRM.tapOnElementBySelector);
    expect(result).toContain("condition=true");
  });
});

describe("runIf - tool condition (non-fatal predicate)", () => {
  test("predicate tool succeeds → condition true → runs `then`", async () => {
    const client = createMockClient();

    const result = await runIf(
      { condition: { tool: { assertVisibleBySelector: { nodeSelector: { androidAccessibility: { textRegex: "Confirm" } } } } }, then: [TAP_CONFIRM] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["assertVisibleBySelector", "tapOnElementBySelector"]);
    expect(result).toContain("condition=true");
  });

  test("predicate tool throws → condition false → runs `else`, predicate failure not propagated", async () => {
    const client = createMockClient();
    client.stub("assertVisibleBySelector", { textContent: "", errorMessage: "not visible" });

    const result = await runIf(
      { condition: { tool: { assertVisibleBySelector: { nodeSelector: { androidAccessibility: { textRegex: "Confirm" } } } } }, then: [TAP_CONFIRM], else: [TAP_CANCEL] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["assertVisibleBySelector", "tapOnElementBySelector"]);
    expect(client.calls[1].args).toEqual(TAP_CANCEL.tapOnElementBySelector);
    expect(result).toContain("condition=false");
  });

  test("a condition tool that cannot run fails the step instead of reading as false", async () => {
    const client = clientWithUnreachableTool(
      "assertVisibelBySelector",
      "client.callTool('assertVisibelBySelector') failed: no tool registered with that name",
    );

    await expect(
      runIf(
        { condition: { tool: { assertVisibelBySelector: {} } }, then: [TAP_CONFIRM], else: [TAP_CANCEL] },
        ctx(),
        client,
      ),
    ).rejects.toThrow("no tool registered with that name");
    expect(toolsOf(client)).toEqual([]);
  });

  test("a nested condition that cannot run fails the outer step too", async () => {
    const client = clientWithUnreachableTool("someProbe", "client.callTool('someProbe') failed: unknown argument keys");
    const nested: RunIfArgs = { condition: { tool: { someProbe: {} } }, then: [] };

    await expect(
      runIf({ condition: { tool: { runIf: nested as unknown as Record<string, unknown> } }, then: [TAP_CONFIRM] }, ctx(), client),
    ).rejects.toThrow("unknown argument keys");
    expect(toolsOf(client)).toEqual([]);
  });
});

describe("runIf - the whole tree is checked before anything runs", () => {
  test("a typo in the branch not taken fails before any tool runs", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]); // would take `then`

    await expect(
      runIf({ condition: PRESENT_SELECTOR, then: [TAP_CONFIRM], else: [{ runIf: { condition: PRESENT_SELECTOR, then: [], els: [] } }] }, ctx(), client),
    ).rejects.toThrow(`Tool 'runIf' was called with unknown argument keys: ["els"]`);
    expect(toolsOf(client)).toEqual([]);
  });

  test.each([
    ["neither form", { selecter: { androidAccessibility: { textRegex: "Confirm" } } }],
    ["both forms", { ...PRESENT_SELECTOR, tool: { someProbe: {} } }],
    ["not an object", "Confirm"],
  ])("a condition with %s fails", async (_label, condition) => {
    const client = createMockClient();

    await expect(
      runIf({ condition, then: [TAP_CONFIRM] } as unknown as RunIfArgs, ctx(), client),
    ).rejects.toThrow("runIf.condition: expected exactly one of { selector } or { tool }");
    expect(toolsOf(client)).toEqual([]);
  });

  test("a branch entry naming two tools fails, naming where it is", async () => {
    const client = createMockClient();

    await expect(
      runIf({ condition: PRESENT_SELECTOR, then: [{ ...TAP_CONFIRM, hideKeyboard: {} }] }, ctx(), client),
    ).rejects.toThrow("runIf.then[0]: expected one tool call shaped { <toolName>: <args> }");
    expect(toolsOf(client)).toEqual([]);
  });

  test("an error names the shape of a bad entry, never its resolved values", async () => {
    const client = createMockClient();
    const secret = 'pin "4921"\nend';

    const failure = await runIf(
      { condition: PRESENT_SELECTOR, then: [{ inputText: { text: secret }, hideKeyboard: {} }] },
      ctx(),
      client,
    ).catch((e: Error) => e);

    expect((failure as Error).message).toContain("got an object with keys [inputText, hideKeyboard]");
    expect((failure as Error).message).not.toContain("4921");
  });

  test("a branch that is not a list fails", async () => {
    const client = createMockClient();

    await expect(
      runIf({ condition: PRESENT_SELECTOR, then: TAP_CONFIRM } as unknown as RunIfArgs, ctx(), client),
    ).rejects.toThrow("runIf.then: expected a list of tool calls");
  });

  test("a negate that is not true or false fails", async () => {
    const client = createMockClient();

    await expect(
      runIf({ condition: PRESENT_SELECTOR, negate: "yes", then: [] } as unknown as RunIfArgs, ctx(), client),
    ).rejects.toThrow("runIf.negate: expected true or false");
  });

  test("a tool that takes no arguments may omit them", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]);

    await runIf(
      { condition: PRESENT_SELECTOR, then: [{ hideKeyboard: null } as unknown as Record<string, Record<string, unknown>>] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["findSelectorMatches", "hideKeyboard"]);
  });
});

describe("runIf - nesting", () => {
  test("a nested `then` entry runs its own branch without dispatching runIf by name", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]); // inner selector present
    const nested: RunIfArgs = { condition: PRESENT_SELECTOR, then: [TAP_CONFIRM] };

    const result = await runIf(
      { condition: { tool: { someProbe: {} } }, then: [{ runIf: nested as unknown as Record<string, unknown> }] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["someProbe", "findSelectorMatches", "tapOnElementBySelector"]);
    expect(client.calls[2].args).toEqual(TAP_CONFIRM.tapOnElementBySelector);
    expect(result).toContain("ran=then");
  });

  test("a nested runIf's verdict is reported in the outer result, even when it ran nothing", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[]]); // inner selector absent
    const nested: RunIfArgs = { condition: PRESENT_SELECTOR, then: [TAP_CONFIRM] };

    const result = await runIf(
      { condition: { tool: { someProbe: {} } }, then: [{ runIf: nested as unknown as Record<string, unknown> }] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["someProbe", "findSelectorMatches"]);
    expect(result).toBe(
      "runIf: condition=true ran=then (1 tools) nested=[runIf: condition=false ran=else (0 tools)]",
    );
  });

  test("a nested entry with an unknown key fails instead of silently running nothing", async () => {
    const client = createMockClient();
    const nested = { condition: PRESENT_SELECTOR, then: [], els: [TAP_CANCEL] };

    await expect(
      runIf({ condition: { tool: { someProbe: {} } }, then: [{ runIf: nested }] }, ctx(), client),
    ).rejects.toThrow(`Tool 'runIf' was called with unknown argument keys: ["els"]`);
    expect(toolsOf(client)).toEqual([]);
  });

  test("a nested entry missing a required key fails", async () => {
    const client = createMockClient();

    await expect(
      runIf(
        { condition: { tool: { someProbe: {} } }, then: [{ runIf: { condition: PRESENT_SELECTOR } }] },
        ctx(),
        client,
      ),
    ).rejects.toThrow(`Tool 'runIf' was called without required argument keys: ["then"]`);
  });

  test("a nested runIf as the condition runs locally too", async () => {
    const client = createQueuedFindMatchesClient();
    client.queueFindMatches([[MATCH]]); // inner selector present
    const nested: RunIfArgs = { condition: PRESENT_SELECTOR, then: [] };

    const result = await runIf(
      { condition: { tool: { runIf: nested as unknown as Record<string, unknown> } }, then: [TAP_CONFIRM] },
      ctx(),
      client,
    );

    expect(toolsOf(client)).toEqual(["findSelectorMatches", "tapOnElementBySelector"]);
    expect(result).toContain("condition=true");
  });

  test("a third level of nesting fails before anything runs, pointing at a .ts tool", async () => {
    const client = createMockClient();
    const third = { condition: PRESENT_SELECTOR, then: [TAP_CONFIRM] };
    const second = { condition: PRESENT_SELECTOR, then: [{ runIf: third }] };

    await expect(
      runIf({ condition: PRESENT_SELECTOR, then: [], else: [{ runIf: second }] }, ctx(), client),
    ).rejects.toThrow("runIf.else[0].runIf.then[0].runIf: conditionals nest at most 2 deep. Move this logic into a .ts tool");
    expect(toolsOf(client)).toEqual([]);
  });

  test("a third level reached through a condition counts too", async () => {
    const client = createMockClient();
    const third = { condition: PRESENT_SELECTOR, then: [] };
    const second = { condition: { tool: { runIf: third } }, then: [] };

    await expect(
      runIf({ condition: { tool: { runIf: second } }, then: [] }, ctx(), client),
    ).rejects.toThrow("conditionals nest at most 2 deep");
  });
});

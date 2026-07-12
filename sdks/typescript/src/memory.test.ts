// Tests for the `TrailblazeMemory` primitive surface — the 8-method API plus the
// internal `[DRAIN_DELTA]` drain the SDK's tool wrapper uses to extract the
// per-invocation diff and stamp it onto the result envelope.
//
// Coverage: read-your-own-writes, snapshot precedence, delete semantics,
// interpolation ({{var}} + ${var}), JSON helpers, and the drain shape that lands on
// the wire as `_meta.trailblaze.memoryDelta` / `_meta.trailblaze.memoryDeletions`.

import { describe, expect, test } from "bun:test";

import { attachMemoryDeltaToResult, createMemory, DRAIN_DELTA, type DrainableMemory } from "./memory.js";

describe("createMemory: snapshot reads", () => {
  test("get returns snapshot values for unmodified keys", () => {
    const m = createMemory({ a: "1", b: "2" });
    expect(m.get("a")).toBe("1");
    expect(m.get("b")).toBe("2");
  });

  test("get returns undefined for absent keys", () => {
    const m = createMemory({ a: "1" });
    expect(m.get("missing")).toBeUndefined();
  });

  test("has reflects snapshot presence", () => {
    const m = createMemory({ a: "1" });
    expect(m.has("a")).toBe(true);
    expect(m.has("b")).toBe(false);
  });

  test("keys returns snapshot keys for an unmodified memory", () => {
    const m = createMemory({ a: "1", b: "2" });
    expect([...m.keys()].sort()).toEqual(["a", "b"]);
  });

  test("undefined snapshot becomes an empty memory (no envelope path)", () => {
    const m = createMemory(undefined);
    expect(m.get("anything")).toBeUndefined();
    expect(m.keys()).toEqual([]);
  });

  test("non-string snapshot values are filtered (defensive against producer-side bugs)", () => {
    // A producer-side bug that leaks a non-string into the snapshot should surface
    // as a missing key, not a silently corrupted value. The TS wire contract is
    // `Record<string, string>`.
    const m = createMemory({ a: "ok", b: 42 as unknown as string, c: null as unknown as string });
    expect(m.get("a")).toBe("ok");
    expect(m.has("b")).toBe(false);
    expect(m.has("c")).toBe(false);
  });
});

describe("createMemory: read-your-own-writes", () => {
  test("set then get reflects the new value in the same invocation", () => {
    const m = createMemory({});
    m.set("k", "v");
    expect(m.get("k")).toBe("v");
  });

  test("set overrides the snapshot value", () => {
    const m = createMemory({ k: "old" });
    m.set("k", "new");
    expect(m.get("k")).toBe("new");
  });

  test("delete makes a snapshot key invisible to get/has/keys", () => {
    const m = createMemory({ k: "v" });
    m.delete("k");
    expect(m.get("k")).toBeUndefined();
    expect(m.has("k")).toBe(false);
    expect(m.keys()).toEqual([]);
  });

  test("delete then set commits the set (delete is shadowed)", () => {
    const m = createMemory({ k: "old" });
    m.delete("k");
    m.set("k", "new");
    expect(m.get("k")).toBe("new");
    expect(m.has("k")).toBe(true);
  });

  test("set then delete commits the deletion", () => {
    const m = createMemory({ k: "old" });
    m.set("k", "interim");
    m.delete("k");
    expect(m.get("k")).toBeUndefined();
  });

  test("keys reflects sets and deletes layered over the snapshot", () => {
    const m = createMemory({ a: "1", b: "2" });
    m.set("c", "3");
    m.delete("a");
    expect([...m.keys()].sort()).toEqual(["b", "c"]);
  });
});

describe("createMemory: interpolate", () => {
  test("substitutes {{var}} tokens", () => {
    const m = createMemory({ name: "Ada" });
    expect(m.interpolate("Hi {{name}}!")).toBe("Hi Ada!");
  });

  test("substitutes ${var} tokens", () => {
    const m = createMemory({ name: "Ada" });
    expect(m.interpolate("Hi ${name}!")).toBe("Hi Ada!");
  });

  test("substitutes both syntaxes in one template", () => {
    const m = createMemory({ first: "Ada", last: "Lovelace" });
    expect(m.interpolate("{{first}} ${last}")).toBe("Ada Lovelace");
  });

  test("unknown tokens are left in place as literals", () => {
    // A typo'd token must arrive at the device/assertion as the visible literal (plus a
    // console diagnostic), not silently blank the string. Known tokens resolve as before.
    const m = createMemory({ known: "V" });
    expect(m.interpolate("{{known}} {{missing}} ${also_missing}")).toBe("V {{missing}} ${also_missing}");
  });

  test("unknown tokens are left in place when memory is empty", () => {
    const m = createMemory({});
    expect(m.interpolate("hi {{missing}} world")).toBe("hi {{missing}} world");
  });

  test("a deleted key's token is left as a literal (deleted = unknown)", () => {
    const m = createMemory({ k: "v" });
    m.delete("k");
    expect(m.interpolate("{{k}}")).toBe("{{k}}");
  });

  test("TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS restores blank substitution, read per call", () => {
    const ENV_VAR = "TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS";
    const prior = process.env[ENV_VAR];
    const m = createMemory({ known: "V" });
    try {
      process.env[ENV_VAR] = "1";
      expect(m.interpolate("{{known}} {{missing}} world")).toBe("V  world");
      process.env[ENV_VAR] = "TRUE"; // case-insensitive
      expect(m.interpolate("{{missing}}")).toBe("");
      // Read per call — flipping it off mid-instance takes effect on the next interpolate.
      delete process.env[ENV_VAR];
      expect(m.interpolate("{{missing}}")).toBe("{{missing}}");
    } finally {
      if (prior === undefined) delete process.env[ENV_VAR];
      else process.env[ENV_VAR] = prior;
    }
  });

  test("respects writes made in this invocation (read-your-own-writes)", () => {
    const m = createMemory({});
    m.set("greeting", "hello");
    expect(m.interpolate("{{greeting}}")).toBe("hello");
  });

  test("does not recursively resolve nested tokens", () => {
    // Single-pass — a resolved value that itself contains a token is returned as-is.
    // Mirrors `AgentMemory.interpolateVariables` semantics on the Kotlin side.
    const m = createMemory({ a: "{{b}}", b: "literal" });
    expect(m.interpolate("{{a}}")).toBe("{{b}}");
  });
});

describe("createMemory: interpolate — the memory. token prefix", () => {
  // {{memory.x}} / ${memory.x} are the scope-qualified spelling of bare {{x}} / ${x}
  // (#4737 phase 1) — same store, prefix stripped at lookup; bare tokens remain fully
  // supported. Tests assert alias-EQUIVALENCE (memory.x behaves exactly like bare x)
  // rather than pinning the unknown-token outcome, so they hold across a change to
  // unknown-token handling (#4731). Mirrors the Kotlin AgentMemoryTest section.

  test("memory-prefixed tokens resolve identically to bare tokens for known keys", () => {
    const m = createMemory({ first: "Ada", last: "Lovelace" });
    expect(m.interpolate("{{memory.first}} ${memory.last}")).toBe(m.interpolate("{{first}} ${last}"));
    expect(m.interpolate("{{memory.first}} ${memory.last}")).toBe("Ada Lovelace");
  });

  test("memory-prefixed unknown token behaves exactly like a bare unknown token", () => {
    // Equivalence is computed against the engine's own bare-token behavior (modulo the
    // token's spelling), so this holds whether unknown tokens blank or are left as literals.
    const m = createMemory({ known: "V" });
    const bare = m.interpolate("[{{nope}}]");
    const prefixed = m.interpolate("[{{memory.nope}}]");
    expect(prefixed).toBe(bare.replace("{{nope}}", "{{memory.nope}}"));
  });

  test("memory-prefixed unknown token behaves like a bare unknown token when memory is empty", () => {
    const m = createMemory({});
    const bare = m.interpolate("[${nope}]");
    const prefixed = m.interpolate("[${memory.nope}]");
    expect(prefixed).toBe(bare.replace("${nope}", "${memory.nope}"));
  });

  test("memory-prefixed lookup respects read-your-own-writes", () => {
    const m = createMemory({});
    m.set("greeting", "hello");
    expect(m.interpolate("{{memory.greeting}}")).toBe("hello");
  });

  test("falls back to a key literally named with the prefix when the bare key is absent", () => {
    // Nothing stops set("memory.foo", …). With no bare `foo`, the literal dotted key is the
    // only candidate and must keep resolving.
    const m = createMemory({ "memory.foo": "literal-value" });
    expect(m.interpolate("{{memory.foo}}")).toBe("literal-value");
  });

  test("prefers the stripped key when both it and the literal dotted key exist", () => {
    // The documented collision rule: prefix-strip wins; the shadowed literal key is reported
    // via a console diagnostic (not asserted — log output isn't part of the contract).
    const m = createMemory({ foo: "stripped-value", "memory.foo": "literal-value" });
    expect(m.interpolate("{{memory.foo}}")).toBe("stripped-value");
    // The bare spelling is unaffected by the collision.
    expect(m.interpolate("{{foo}}")).toBe("stripped-value");
  });

  test("single-pass property holds for memory-prefixed tokens (no re-resolve of resolved values)", () => {
    const m = createMemory({ a: "{{memory.b}}", b: "x" });
    expect(m.interpolate("{{memory.a}}")).toBe("{{memory.b}}");
  });
});

describe("createMemory: setJson / getJson", () => {
  test("round-trips a plain object", () => {
    const m = createMemory({});
    m.setJson("user", { name: "Ada", id: 42 });
    expect(m.getJson<{ name: string; id: number }>("user")).toEqual({ name: "Ada", id: 42 });
  });

  test("round-trips an array", () => {
    const m = createMemory({});
    m.setJson("xs", [1, 2, 3]);
    expect(m.getJson<number[]>("xs")).toEqual([1, 2, 3]);
  });

  test("getJson returns undefined for absent keys", () => {
    const m = createMemory({});
    expect(m.getJson("missing")).toBeUndefined();
  });

  test("getJson returns undefined when the stored value is not JSON (graceful degrade)", () => {
    // A tool that set a plain string via `.set("k", "plain")` and another reader trying
    // `.getJson("k")` shouldn't throw — fall back to undefined so the reader can use
    // `?? defaultValue` cleanly.
    const m = createMemory({});
    m.set("k", "not-json");
    expect(m.getJson("k")).toBeUndefined();
  });

  test("setJson serializes via JSON.stringify; get reads the raw string", () => {
    // Confirms the wire shape — JSON values are stored as their stringified form.
    const m = createMemory({});
    m.setJson("user", { name: "Ada" });
    expect(m.get("user")).toBe('{"name":"Ada"}');
  });

  test("setJson(undefined) routes through delete (JSON.stringify(undefined) is undefined)", () => {
    // `JSON.stringify(undefined)` returns the runtime `undefined`, not the string
    // `"undefined"`. Without the guard, the buffer would carry a non-string value and
    // `has(k)` / `get(k)` would silently disagree. Verify the deletion path fires.
    const m = createMemory({ user: '{"name":"Ada"}' });
    m.setJson("user", undefined);
    expect(m.get("user")).toBeUndefined();
    expect(m.has("user")).toBe(false);
  });

  test("setJson(function) routes through delete (non-serializable)", () => {
    // Functions and symbols share the same `JSON.stringify → undefined` failure mode.
    const m = createMemory({ cb: '"old"' });
    m.setJson("cb", (() => 42) as unknown);
    expect(m.get("cb")).toBeUndefined();
  });

  test("setJson(symbol) routes through delete (non-serializable)", () => {
    const m = createMemory({ s: '"old"' });
    m.setJson("s", Symbol("nope") as unknown);
    expect(m.get("s")).toBeUndefined();
  });
});

describe("createMemory: drain delta (wire shape)", () => {
  function drain(m: ReturnType<typeof createMemory>) {
    return (m as DrainableMemory)[DRAIN_DELTA]();
  }

  test("no writes → empty sets + empty deletions", () => {
    const m = createMemory({ a: "1" });
    expect(drain(m)).toEqual({ sets: {}, deletions: [] });
  });

  test("captures sets only for keys whose value actually changed", () => {
    const m = createMemory({ a: "1" });
    m.set("a", "1"); // same value — no delta
    m.set("b", "new"); // new key — included
    m.set("a", "changed"); // overwrite — included
    expect(drain(m)).toEqual({ sets: { a: "changed", b: "new" }, deletions: [] });
  });

  test("deletions list only includes keys that actually existed in the snapshot", () => {
    // Deleting a key that was never set is a no-op write — including it in the
    // delta would force a no-op host write and clutter the wire envelope.
    const m = createMemory({ a: "1" });
    m.delete("a");
    m.delete("nonexistent");
    expect(drain(m)).toEqual({ sets: {}, deletions: ["a"] });
  });

  test("set after delete commits a set in the delta (deletion shadowed)", () => {
    const m = createMemory({ k: "old" });
    m.delete("k");
    m.set("k", "new");
    expect(drain(m)).toEqual({ sets: { k: "new" }, deletions: [] });
  });

  test("delete after set commits a deletion (set shadowed)", () => {
    const m = createMemory({ k: "old" });
    m.set("k", "interim");
    m.delete("k");
    expect(drain(m)).toEqual({ sets: {}, deletions: ["k"] });
  });

  test("__proto__ as a key is preserved in the delta (null-prototype dict)", () => {
    // Free-form string keys — a tool that writes `"__proto__"` must still flow to the
    // host. With a plain `{}`, assigning `sets["__proto__"] = v` is silently ignored
    // because the property is non-writable on Object.prototype. Using a null-prototype
    // object lets the assignment land as a real own-property the wire serializer emits.
    const m = createMemory({});
    m.set("__proto__", "danger");
    const d = drain(m);
    expect(d.sets["__proto__"]).toBe("danger");
    // Survives JSON serialization (the wire format) — own-property visibility for keys
    // that would otherwise collide with prototype names.
    const serialized = JSON.parse(JSON.stringify(d.sets));
    expect(serialized["__proto__"]).toBe("danger");
  });

  test("other prototype-name keys (toString, hasOwnProperty) survive the delta", () => {
    const m = createMemory({});
    m.set("toString", "t");
    m.set("hasOwnProperty", "h");
    const d = drain(m);
    expect(d.sets["toString"]).toBe("t");
    expect(d.sets["hasOwnProperty"]).toBe("h");
  });
});

describe("attachMemoryDeltaToResult", () => {
  test("no-op when memory is undefined", () => {
    expect(attachMemoryDeltaToResult("ok", undefined)).toBe("ok");
  });

  test("no-op (returns the exact result) when the handler made no writes", () => {
    const m = createMemory({ a: "1" });
    const result = { content: [{ type: "text", text: "hi" }] };
    // Same reference back — a write-free tool keeps its original return shape untouched.
    expect(attachMemoryDeltaToResult(result, m)).toBe(result);
  });

  test("wraps a bare-string return in a content envelope and stamps memoryDelta", () => {
    const m = createMemory({});
    m.set("session_token", "tok-123");
    const out = attachMemoryDeltaToResult("done", m) as {
      content: unknown[];
      _meta: { trailblaze: { memoryDelta: Record<string, string> } };
    };
    expect(out.content).toEqual([{ type: "text", text: "done" }]);
    expect(out._meta.trailblaze.memoryDelta).toEqual({ session_token: "tok-123" });
  });

  test("merges memoryDelta into an object result while preserving existing _meta", () => {
    const m = createMemory({ old: "v" });
    m.set("k", "v2");
    m.delete("old");
    const out = attachMemoryDeltaToResult(
      { content: [{ type: "text", text: "hi" }], _meta: { trailblaze: { existing: "keep" } } },
      m,
    ) as { _meta: { trailblaze: Record<string, unknown> } };
    expect(out._meta.trailblaze.existing).toBe("keep");
    expect(out._meta.trailblaze.memoryDelta).toEqual({ k: "v2" });
    expect(out._meta.trailblaze.memoryDeletions).toEqual(["old"]);
  });

  test("wraps a bare structured object (no content array) instead of bolting _meta directly onto it", () => {
    // Regression: a typed tool's declared TResult can be any plain object, e.g. `{ ok: true }`,
    // with no `content` field. Bolting `_meta` directly onto that object produces a shape the
    // downstream `__normalizeResult` (in-process wrapper) / `normalizeInlineToolResult` (subprocess
    // inline-tool wrapper) do NOT recognize as "already MCP-shaped" — both only pass an object
    // through untouched when it has `Array.isArray(result.content)` — so they'd JSON.stringify the
    // whole thing (including _meta) into a fresh text-only envelope, losing the memoryDelta.
    const m = createMemory({});
    m.set("session_token", "tok-9");
    const out = attachMemoryDeltaToResult({ ok: true }, m) as {
      content: Array<{ type: string; text: string }>;
      structuredContent: unknown;
      _meta: { trailblaze: { memoryDelta: Record<string, string> } };
    };
    // Must have a `content` array so a downstream normalizer's `Array.isArray(content)` check
    // passes it through unchanged.
    expect(Array.isArray(out.content)).toBe(true);
    expect(out._meta.trailblaze.memoryDelta).toEqual({ session_token: "tok-9" });
    // The typed value survives via structuredContent for a composing caller.
    expect(out.structuredContent).toEqual({ ok: true });
  });

  test("does not add structuredContent when wrapping a primitive (only bare objects get it)", () => {
    const m = createMemory({});
    m.set("k", "v");
    const out = attachMemoryDeltaToResult(42, m) as { structuredContent?: unknown };
    expect(out.structuredContent).toBeUndefined();
  });

  test("stamps only memoryDeletions when the delta has no sets", () => {
    // Sibling test to "merges memoryDelta..." above, which exercises sets+deletions together —
    // this pins the deletions-only combination through the full function on its own (the
    // underlying drainDelta shape is covered separately in the "drain delta" describe block, but
    // not previously threaded through attachMemoryDeltaToResult by itself).
    const m = createMemory({ old: "v" });
    m.delete("old");
    const out = attachMemoryDeltaToResult({ content: [{ type: "text", text: "hi" }] }, m) as {
      _meta: { trailblaze: { memoryDelta?: Record<string, string>; memoryDeletions: string[] } };
    };
    expect(out._meta.trailblaze.memoryDeletions).toEqual(["old"]);
    expect(out._meta.trailblaze.memoryDelta).toBeUndefined();
  });

  test("wraps a result whose content key is present but not an array", () => {
    // The wrap condition is `Array.isArray(result.content)`, not merely `"content" in result` —
    // a result shaped like `{ content: "oops" }` must be wrapped (not merged into), since a
    // downstream normalizer's own `Array.isArray(content)` check would also reject it.
    const m = createMemory({});
    m.set("k", "v");
    const out = attachMemoryDeltaToResult({ content: "oops" }, m) as {
      content: Array<{ type: string; text: string }>;
      structuredContent: unknown;
      _meta: { trailblaze: { memoryDelta: Record<string, string> } };
    };
    expect(Array.isArray(out.content)).toBe(true);
    expect(out._meta.trailblaze.memoryDelta).toEqual({ k: "v" });
    expect(out.structuredContent).toEqual({ content: "oops" });
  });
});

describe("createMemory: toJSON", () => {
  test("JSON.stringify on the memory produces a flat snapshot record", () => {
    // Escape hatch for tools that serialize the whole ctx for debugging — pre-primitive
    // they got `ctx.memory` as a plain object and `JSON.stringify(ctx)` rendered the
    // memory inline. The `toJSON` non-interface method preserves that ergonomics.
    const m = createMemory({ a: "1", b: "2" });
    m.set("c", "3");
    m.delete("a");
    const stringified = JSON.parse(JSON.stringify(m));
    expect(stringified).toEqual({ b: "2", c: "3" });
  });
});

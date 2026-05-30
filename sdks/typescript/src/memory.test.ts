// Tests for the `TrailblazeMemory` primitive surface — the 8-method API plus the
// internal `[DRAIN_DELTA]` drain the SDK's tool wrapper uses to extract the
// per-invocation diff and stamp it onto the result envelope.
//
// Coverage: read-your-own-writes, snapshot precedence, delete semantics,
// interpolation ({{var}} + ${var}), JSON helpers, and the drain shape that lands on
// the wire as `_meta.trailblaze.memoryDelta` / `_meta.trailblaze.memoryDeletions`.

import { describe, expect, test } from "bun:test";

import { createMemory, DRAIN_DELTA, type DrainableMemory } from "./memory.js";

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

  test("unknown tokens resolve to empty string", () => {
    const m = createMemory({});
    expect(m.interpolate("hi {{missing}} world")).toBe("hi  world");
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

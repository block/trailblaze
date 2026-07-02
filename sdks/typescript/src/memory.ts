// `ctx.memory` — first-class read/write surface scripted tools use to communicate state
// across tool boundaries (within a session, across handlers, into YAML interpolation).
//
// Wire shape: inbound as `_meta.trailblaze.memory: Record<string, string>` (a snapshot of
// the host's `AgentMemory.variables` at envelope build time). Outbound as
// `_meta.trailblaze.memoryDelta` (sets) + `_meta.trailblaze.memoryDeletions` (keys removed)
// on the tool result's `_meta`. The Kotlin host applies the delta after a successful
// `tools/call`; a handler that throws produces no delta and leaves the host's memory
// untouched.
//
// **Why a synchronous API.** The QuickJS `__trailblazeCallback` channel is async, so a
// naive per-call round-trip would force `Promise<void>` on every memory write — every
// `ctx.memory.set(...)` would have to be awaited. The buffered design batches all writes
// locally and flushes the diff on tool return, so authors write `ctx.memory.set("k", v)`
// without `await` and the work happens in one atomic envelope at the boundary.

/**
 * The scripted-tool memory primitive. Authors read/write through the 8 methods; the SDK
 * tracks the diff against an immutable inbound snapshot and flushes the buffered changes
 * as `memoryDelta` / `memoryDeletions` on the tool result envelope when the handler
 * returns successfully.
 *
 * **Read-your-own-writes.** `get`/`has`/`keys` reflect changes made earlier in the same
 * handler invocation. A `set("k", "v")` immediately followed by `get("k")` returns
 * `"v"`, even though the value hasn't been flushed back to the host yet.
 *
 * **Transactional.** Writes are committed to the host only when the handler returns a
 * non-error result. Both failure shapes — a thrown exception AND an explicit
 * `isError: true` result envelope — leave the host's memory exactly as it was before
 * the call. The host-side guard sits at the dispatch boundary
 * (`SubprocessTrailblazeTool.execute`), so the symmetric rollback is enforced
 * regardless of which failure mode the handler picks.
 */
export interface TrailblazeMemory {
  /** Returns the string value for [key], or undefined if absent. */
  get(key: string): string | undefined;

  /** Sets [key] to [value]. Overwrites any prior value. */
  set(key: string, value: string): void;

  /** Returns true if [key] is set (including by a prior `.set()` in this invocation). */
  has(key: string): boolean;

  /** Snapshot of every currently-visible key. Order is not guaranteed. */
  keys(): readonly string[];

  /** Removes [key]. No-op if absent. */
  delete(key: string): void;

  /**
   * Replaces `{{var}}` and `${var}` tokens in [template] with their current values.
   * Mirrors the Kotlin `AgentMemory.interpolateVariables` semantics: single-pass per
   * pattern, unknown tokens resolve to empty string, the resolved string is NOT
   * re-scanned for tokens that interpolate.
   */
  interpolate(template: string): string;

  /**
   * Convenience for storing a typed JSON value. Serializes with `JSON.stringify` and
   * stores the resulting string under [key]. The type parameter [T] is editor-time only;
   * the runtime accepts any value `JSON.stringify` accepts.
   *
   * **Non-serializable values route through [delete].** `JSON.stringify` returns the
   * runtime `undefined` for top-level `undefined`, functions, and symbols — writing
   * that into the string-only buffer would silently desync `has` / `get` / wire. For
   * those values [setJson] calls [delete] instead, so the host sees an explicit
   * deletion rather than a phantom write. `null` is distinct: `JSON.stringify(null)`
   * is the string `"null"`, which is stored verbatim.
   */
  setJson<T>(key: string, value: T): void;

  /**
   * Convenience for retrieving a typed JSON value. Reads the string at [key] and runs
   * `JSON.parse` on it. Returns undefined when the key is absent OR when the string
   * isn't valid JSON — gracefully degrading rather than throwing keeps tools that fall
   * back to a default value (`getJson<UserInfo>("user") ?? defaultUser`) terse.
   *
   * The type parameter [T] is editor-time only; no runtime schema validation.
   */
  getJson<T = unknown>(key: string): T | undefined;
}

/**
 * Mutable diff captured by a [TrailblazeMemory] across one tool invocation. Flushed by the
 * SDK's tool-wrapper into `_meta.trailblaze.memoryDelta` / `_meta.trailblaze.memoryDeletions`
 * on the result envelope.
 *
 * `sets` keyed on the final value (last-write-wins). `deletions` are tracked separately
 * because a `delete(k)` followed by a `set(k, v)` should commit a set, while a
 * `set(k, v)` followed by `delete(k)` should commit a deletion.
 */
export interface MemoryDelta {
  sets: Record<string, string>;
  deletions: string[];
}

/**
 * Hidden contract: implementations carry an internal drain that the SDK calls after the
 * handler returns to extract the diff. Kept as a Symbol-keyed property to stay invisible
 * on the public `TrailblazeMemory` surface — authors should never see or call this.
 */
export const DRAIN_DELTA: unique symbol = Symbol.for("trailblaze.memory.drainDelta");

/**
 * Wire-shape key names for the `_meta.trailblaze` envelope, shared between inbound parsing
 * (`fromMeta` in [./context.ts]) and outbound delta attachment (`attachMemoryDelta` in
 * [./tool.ts]). Kept here so a rename happens in one place — the Kotlin side mirrors these
 * as `TrailblazeContextEnvelope.META_KEY_MEMORY` / `META_KEY_MEMORY_DELTA` /
 * `META_KEY_MEMORY_DELETIONS`; both sides must change together.
 */
export const META_KEY_TRAILBLAZE = "trailblaze";
export const META_KEY_MEMORY = "memory";
export const META_KEY_MEMORY_DELTA = "memoryDelta";
export const META_KEY_MEMORY_DELETIONS = "memoryDeletions";

/**
 * The shape callers (the tool wrapper) use to extract the diff. Cast a TrailblazeMemory
 * to this interface to reach the symbol-keyed drain.
 */
export interface DrainableMemory extends TrailblazeMemory {
  [DRAIN_DELTA](): MemoryDelta;
}

const INTERPOLATE_PATTERNS: readonly RegExp[] = [
  /\$\{([^}]+)\}/g,
  /\{\{([^}]+)\}\}/g,
];

/**
 * Build a [TrailblazeMemory] backed by an inbound snapshot. The snapshot is captured by
 * value — subsequent mutations to the source object don't affect this memory instance.
 *
 * Reads check the buffer first, then fall back to the snapshot. Writes go to the buffer.
 * Deletions set the buffer entry to the sentinel symbol so a subsequent `get` returns
 * undefined even when the snapshot had a value.
 */
export function createMemory(snapshot: Record<string, string> | undefined): TrailblazeMemory {
  const frozenSnapshot: ReadonlyMap<string, string> = new Map(
    snapshot ? Object.entries(snapshot).filter(
      // Defensive — the snapshot is supposed to be `Record<string, string>` from the
      // Kotlin side, but `fromMeta` receives raw JSON. Skip non-string values rather
      // than coercing so a producer-side bug surfaces as a missing key instead of a
      // silently corrupted value.
      ([, v]) => typeof v === "string",
    ) as Iterable<[string, string]> : [],
  );

  // Sentinel for "deleted in this invocation." Using a unique symbol keeps it
  // distinct from any string a caller might legitimately store.
  const DELETED: unique symbol = Symbol("deleted");
  const buffer = new Map<string, string | typeof DELETED>();

  const get = (key: string): string | undefined => {
    if (buffer.has(key)) {
      const v = buffer.get(key)!;
      return v === DELETED ? undefined : v;
    }
    return frozenSnapshot.get(key);
  };

  const has = (key: string): boolean => {
    if (buffer.has(key)) return buffer.get(key) !== DELETED;
    return frozenSnapshot.has(key);
  };

  const set = (key: string, value: string): void => {
    buffer.set(key, value);
  };

  const del = (key: string): void => {
    // Tracking via the sentinel — NOT `buffer.delete(key)` — so a `delete(k)` after a
    // `set(k, v)` correctly commits a deletion to the host (the snapshot still has the
    // pre-call value; without the sentinel we'd treat "absent from buffer" as
    // "no change" and leave the host's value in place).
    buffer.set(key, DELETED);
  };

  const keys = (): readonly string[] => {
    const visible = new Set<string>(frozenSnapshot.keys());
    buffer.forEach((v, k) => {
      if (v === DELETED) visible.delete(k); else visible.add(k);
    });
    return [...visible];
  };

  const interpolate = (template: string): string => {
    let result = template;
    for (const pattern of INTERPOLATE_PATTERNS) {
      // Fresh regex per use — the `g` flag means the regex object carries `lastIndex`
      // state that would leak across calls. Cheap to recreate; cheaper than the bugs
      // a forgotten `lastIndex = 0` produces.
      const re = new RegExp(pattern.source, "g");
      result = result.replace(re, (_match, name: string) => get(name) ?? "");
    }
    return result;
  };

  const setJson = <T,>(key: string, value: T): void => {
    // `JSON.stringify` returns the runtime `undefined` (not the string `"undefined"`) for
    // top-level `undefined`, functions, and symbols. Without this guard, the buffer would
    // hold a non-string value — `has(k)` would still return `true` but `get(k)` would be
    // `undefined`, and `drainDelta` would silently omit the entry. Route the no-value case
    // through `del` so the host sees an explicit deletion instead of a silent desync.
    const serialized = JSON.stringify(value);
    if (serialized === undefined) {
      del(key);
      return;
    }
    set(key, serialized);
  };

  const getJson = <T,>(key: string): T | undefined => {
    const raw = get(key);
    if (raw === undefined) return undefined;
    try {
      return JSON.parse(raw) as T;
    } catch {
      // A stored string that isn't JSON (e.g. a tool-set plain string later read via
      // getJson). Returning undefined lets callers fall back to a default; throwing
      // would force every consumer to wrap in try/catch for the legitimate case.
      return undefined;
    }
  };

  const drainDelta = (): MemoryDelta => {
    // `Object.create(null)` rather than `{}` so a tool that writes `"__proto__"` (or any
    // other inherited Object.prototype name) lands as a real own-property the delta
    // serializer will emit. With a plain `{}`, assigning `sets["__proto__"] = v` is
    // ignored (the property is non-writable on the prototype) and the write silently
    // disappears from the wire envelope. Memory keys are free-form strings, so the
    // dictionary must tolerate any string a tool might supply.
    const sets: Record<string, string> = Object.create(null) as Record<string, string>;
    const deletions: string[] = [];
    buffer.forEach((v, k) => {
      if (v === DELETED) {
        // Only count as a deletion if the host actually has this key. Deleting a key
        // that was never in the snapshot is a no-op write — emitting it would clutter
        // the wire envelope and force the host to no-op on it.
        if (frozenSnapshot.has(k)) deletions.push(k);
      } else if (frozenSnapshot.get(k) !== v) {
        // Skip sets that match the snapshot exactly — `set("k", currentValue)` is a
        // no-op; including it inflates the delta and forces a redundant host write.
        sets[k] = v;
      }
    });
    return { sets, deletions };
  };

  // `toJSON` is a non-interface escape hatch so `JSON.stringify(ctx.memory)` produces
  // the visible-keys snapshot (matching the pre-primitive behavior when `ctx.memory`
  // was a plain `Record<string, string>`). Useful for ad-hoc debugging and for tools
  // that serialize the whole `ctx` for return-payload inspection (the integration
  // fixture's `trailblazeContextSdk` is one such tool). Not on `TrailblazeMemory` so
  // the public surface stays minimal — authors who want the snapshot reach for
  // `keys()` + per-key `get()`.
  const toJSON = (): Record<string, string> => {
    const out: Record<string, string> = {};
    frozenSnapshot.forEach((v, k) => { out[k] = v; });
    buffer.forEach((v, k) => {
      if (v === DELETED) delete out[k]; else out[k] = v;
    });
    return out;
  };

  const memory: DrainableMemory & { toJSON: () => Record<string, string> } = {
    get,
    set,
    has,
    keys,
    delete: del,
    interpolate,
    setJson,
    getJson,
    [DRAIN_DELTA]: drainDelta,
    toJSON,
  };
  return memory;
}

/**
 * Stamp the memory diff captured by [memory] onto a tool result's `_meta.trailblaze` envelope
 * (`memoryDelta` for sets, `memoryDeletions` for removed keys). The Kotlin host reads these after
 * a successful dispatch and merges them into the shared `AgentMemory`.
 *
 * **Shared by BOTH dispatch paths, which is the point.** Previously only the subprocess/MCP path
 * flushed writes — it wrapped this exact logic inline in `tool.ts`'s `attachMemoryDelta`, draining
 * `ctx.memory`. The in-process / on-device QuickJS path (`defineTypedTool` in `tool-core.ts`)
 * reconstructs a fresh `TrailblazeMemory` from the raw snapshot the Kotlin `QuickJsToolCtxEnvelope`
 * hands it, and had no equivalent flush — so a handler's `ctx.memory.set(...)` was silently dropped
 * and never reached the next tool's `ctx.memory.get(...)`. Both paths now call this so the write is
 * visible regardless of host/device dispatch (Kotlin side: `SubprocessTrailblazeTool` /
 * `QuickJsTrailblazeTool` both apply the resulting `memoryDelta`).
 *
 * No-op when [memory] is undefined, isn't drainable, or the handler made no memory changes —
 * returns [result] untouched so a tool that writes nothing keeps its exact original return shape.
 * When there IS a diff: preserves any `_meta` the handler set explicitly (merging under
 * `trailblaze`), and wraps anything that isn't already an MCP-shaped `{content: [...]}` envelope
 * in one first so the `_meta` has an object to live on.
 *
 * **Why the wrap condition is "has a `content` array", not "is an object".** A typed
 * `trailblaze.tool<I, O>(handler)` on the in-process path can return ANY author-declared `O` —
 * including a plain structured object like `{ ok: true }` with no `content` field. The synthesized
 * in-process wrapper's `__normalizeResult` (and the subprocess inline-tool wrapper's
 * `normalizeInlineToolResult`) only pass an object through untouched when it already carries
 * `Array.isArray(result.content)`; anything else gets `JSON.stringify`'d into a fresh text-only
 * envelope, which would silently swallow a `_meta` bolted onto the raw object here. Wrapping a bare
 * object ourselves — before the downstream normalizer sees it — means it hits the "already has
 * `content`" branch there and passes through with `_meta` intact. The wrap also sets
 * `structuredContent` for object returns so a caller composing this tool via
 * `client.tools.<name>(...)` still unwraps the typed value (mirrors the subprocess synthesizer's
 * `normalizeInlineToolResult`).
 */
export function attachMemoryDeltaToResult(
  result: unknown,
  memory: TrailblazeMemory | undefined,
): unknown {
  if (memory === undefined) return result;
  const drainable = memory as DrainableMemory;
  if (typeof drainable[DRAIN_DELTA] !== "function") return result;
  const delta = drainable[DRAIN_DELTA]();
  const setKeys = Object.keys(delta.sets);
  if (setKeys.length === 0 && delta.deletions.length === 0) return result;
  const trailblaze: Record<string, unknown> = {};
  if (setKeys.length > 0) trailblaze[META_KEY_MEMORY_DELTA] = delta.sets;
  if (delta.deletions.length > 0) trailblaze[META_KEY_MEMORY_DELETIONS] = delta.deletions;

  const hasContentArray =
    typeof result === "object" && result !== null && Array.isArray((result as Record<string, unknown>).content);

  if (!hasContentArray) {
    const wrapped: Record<string, unknown> = {
      content:
        result === undefined || result === null
          ? []
          : [{ type: "text", text: typeof result === "string" ? result : JSON.stringify(result) }],
      _meta: { [META_KEY_TRAILBLAZE]: trailblaze },
    };
    // Bare structured object (not a string/number/etc.) — also carry it as structuredContent so a
    // typed caller unwraps the real value instead of the JSON-stringified text.
    if (typeof result === "object" && result !== null) {
      wrapped.structuredContent = result;
    }
    return wrapped;
  }

  const resultRecord = result as Record<string, unknown>;
  // A handler that set `_meta` to something other than an object (e.g. a string) is authoring a
  // non-standard MCP result — there's no sane merge target, so that value is treated the same as
  // "no `_meta` at all" and replaced outright, rather than preserved-but-ignored. By design, not
  // an oversight: no known author pattern relies on a non-object `_meta`.
  const existingMeta =
    typeof resultRecord["_meta"] === "object" && resultRecord["_meta"] !== null
      ? (resultRecord["_meta"] as Record<string, unknown>)
      : {};
  const existingTrailblaze =
    typeof existingMeta[META_KEY_TRAILBLAZE] === "object" && existingMeta[META_KEY_TRAILBLAZE] !== null
      ? (existingMeta[META_KEY_TRAILBLAZE] as Record<string, unknown>)
      : {};
  const merged: Record<string, unknown> = { ...existingTrailblaze, ...trailblaze };
  return {
    ...resultRecord,
    _meta: { ...existingMeta, [META_KEY_TRAILBLAZE]: merged },
  };
}

// Trailhead teardown: the trailhead's teardown and hand-off surface.
//
// A trailhead tool can define `trailhead.teardown(args, ctx, trailRun)`. Trailblaze calls it
// once after every trailhead invocation that started, however the run ended. When it says the
// setup is reusable, the next run of the same trailhead (identical args, same device) receives that
// result as `ctx.previousRun`; otherwise `ctx.previousRun` is `null`.
//
// Dependency-free on purpose: the in-process / on-device bundle imports this through `tool-core.ts`.
// The Kotlin side of every wire shape here lives in `xyz.block.trailblaze.trailhead` — keep them in
// lockstep.

import type { TrailblazeClient } from "./client.js";

/** How the trail that just ended finished. */
export type TrailOutcome = "passed" | "failed" | "cancelled" | "setup-failed";

/** One thing a tool reported through `ctx.trailhead.note(...)`. */
export interface TrailheadNote {
  /**
   * State this run changed that cleanup can't undo. Any such note makes the run non-reusable: the
   * framework stores `reusable: false` whatever `teardown` returns, naming the note.
   */
  invalidates?: string;
  /** Something cleanup should delete. */
  created?: { kind: string; id: string };
  /** The tool that wrote the note. Filled in by the framework. */
  tool: string;
  /** Epoch milliseconds. Filled in by the framework. */
  timestamp: number;
}

/** What a tool passes to `ctx.trailhead.note(...)`. */
export type TrailheadNoteInput =
  | { invalidates: string }
  | { created: { kind: string; id: string } };

/**
 * Match vocabulary for [TrailEvents.query]: the survey vocabulary minus predicates, because the
 * match runs in Kotlin and a function can't cross over.
 *
 *  - a string, number or boolean matches by equality;
 *  - a `RegExp` is tested against the value's string form (use it for substring matches);
 *  - a nested object matches when every key it names matches (subset match, recursively).
 */
export type EventMatch = { [key: string]: EventMatchValue };
export type EventMatchValue = string | number | boolean | null | RegExp | EventMatch;

/** One record from a session event stream (`events/<stream>.ndjson`). */
export interface SessionEvent {
  /** On the host clock, unless `clock` says otherwise. */
  timeMs: number;
  data: unknown;
  /** `"device"` when `timeMs` is a device stamp with no clock measurement to convert it. */
  clock?: "device";
}

export interface TrailEventsQueryOptions {
  match?: EventMatch;
  /** `"trail"` (default) starts when the trailhead returned; `"trailhead-and-trail"` when it started. */
  window?: "trail" | "trailhead-and-trail";
  /** Default and ceiling are set by the framework. */
  limit?: number;
}

/** Filtered reads of this session's event streams, for this device only. */
export interface TrailEvents {
  /** Streams present in this session, e.g. `"trailhead"`. */
  list(): Promise<string[]>;
  /** Matching records only. Filtered in Kotlin, so a large stream never loads into JavaScript. */
  query(stream: string, options?: TrailEventsQueryOptions): Promise<SessionEvent[]>;
}

/** Third argument to `teardown`. Only `teardown` receives it. */
export interface TrailRun {
  /** `"setup-failed"`: the trailhead threw, so no trail step ran. */
  outcome: TrailOutcome;
  /** What tools reported through `ctx.trailhead.note(...)`, in call order. */
  notes: TrailheadNote[];
  events: TrailEvents;
}

/** Small JSON the trailhead keeps for itself. No secrets; size-capped by the framework. */
export type JsonObject = { [key: string]: JsonValue };
export type JsonValue = string | number | boolean | null | JsonValue[] | JsonObject;

/** What `teardown` returns. */
export interface TeardownResult {
  /**
   * Can the next run reuse this setup? Only `true` reaches the next run, as `ctx.previousRun`.
   * Forced to `false` unless the outcome is `"passed"`, when `teardown` threw or timed out,
   * and when the session holds a persona lease.
   */
  reusable: boolean;
  /** Required. Shown in the report and in the next trailhead's log. */
  reason: string;
  /** Passed on as `ctx.previousRun.state`, e.g. what this run left for the next one to clear. */
  state?: JsonObject;
}

/**
 * `ctx.previousRun`: what the last trail's `teardown` reported about the setup it left behind.
 * A claim to check, not a guarantee: the handler verifies the screen before reusing it. Non-null
 * only in a trailhead's own handler, when the last run of the same trailhead (same code, args,
 * device and driver) returned `reusable: true` and the lane has reuse on. `null` everywhere else.
 */
export interface PreviousRun {
  /** The session whose `teardown` returned this. */
  sessionId: string;
  /** What that `teardown` said. */
  reason: string;
  state?: JsonObject;
  /**
   * Reuse that setup instead of doing a full one. Logs the attempt, then runs [catchUp] (whatever
   * the last run left to finish, such as clearing a cart) and resolves with its result. A throw
   * from [catchUp] fails the trailhead, logged as a failed reuse of [sessionId]'s setup. Without
   * a call the log says "full setup". Call it at most once.
   */
  reuse<T>(reason: string, catchUp?: () => Promise<T>): Promise<T | undefined>;
}

/** `ctx.trailhead`. */
export interface TrailheadContext {
  /** Any tool. Reports state the trailhead's `teardown` must know about. */
  note(note: TrailheadNoteInput): void;
}

/** `trailhead.teardown` on a typed tool spec. */
export type TeardownHandler<TInput, TContext> = (
  args: TInput,
  ctx: TContext,
  trailRun: TrailRun,
) => Promise<TeardownResult>;

/** `_meta.trailblaze.trailhead` on a tool result: what the handler reported through `ctx.trailhead`. */
export const META_KEY_TRAILHEAD = "trailhead";

/** Reserved host functions behind [TrailEvents]. Mirrors `TrailEventsHostFunctions.kt`. */
export const HOST_FUNCTION_TRAIL_EVENTS_LIST = "trailblaze.trailEvents.list";
export const HOST_FUNCTION_TRAIL_EVENTS_QUERY = "trailblaze.trailEvents.query";

/**
 * Marks a thrown value with the notes its tool wrote before throwing, so a tool that created
 * something and then failed still reports it. Read by the in-process dispatcher (`QuickJsToolHost`).
 */
export const TRAILHEAD_REPORT_ON_ERROR: unique symbol = Symbol.for("trailblaze.trailhead.report");

/** What a handler reported, as carried on `_meta.trailblaze.trailhead`. */
export interface TrailheadReport {
  notes?: TrailheadNoteInput[];
  /** Set once `previousRun.reuse` is called. `ok` is absent while the catch-up is still running. */
  reuse?: { reason: string; startedAtMs: number; ok?: boolean; error?: string };
}

/** `ctx.trailhead` and `ctx.previousRun` for one invocation, plus the report they accumulated. */
export interface TrailheadRecorder {
  context: TrailheadContext;
  previousRun: PreviousRun | null;
  /** `undefined` when nothing was reported. */
  report(): TrailheadReport | undefined;
}

/**
 * Build `ctx.trailhead` and `ctx.previousRun` for one invocation. [previousRun] is what the
 * host put on the envelope; everything the handler reports is buffered and flushed onto the
 * result's `_meta`.
 */
export function createTrailheadRecorder(previousRun: unknown): TrailheadRecorder {
  const notes: TrailheadNoteInput[] = [];
  let reuse: TrailheadReport["reuse"];
  const context: TrailheadContext = {
    note(note: TrailheadNoteInput) {
      notes.push(validateNote(note));
    },
  };
  const wire = parsePreviousRun(previousRun);
  return {
    context,
    previousRun: wire === null ? null : {
      ...wire,
      async reuse<T>(reason: string, catchUp?: () => Promise<T>): Promise<T | undefined> {
        if (typeof reason !== "string" || reason.trim().length === 0) {
          throw new TypeError("ctx.previousRun.reuse(reason): reason is required");
        }
        if (reuse !== undefined) throw new Error("ctx.previousRun.reuse: already called");
        const attempt: NonNullable<TrailheadReport["reuse"]> = { reason, startedAtMs: Date.now() };
        reuse = attempt;
        try {
          const result = catchUp === undefined ? undefined : await catchUp();
          attempt.ok = true;
          return result;
        } catch (e) {
          attempt.ok = false;
          attempt.error = String((e as { message?: unknown } | null)?.message ?? e).slice(0, 500);
          throw e;
        }
      },
    },
    report() {
      if (notes.length === 0 && reuse === undefined) return undefined;
      const report: TrailheadReport = {};
      if (notes.length > 0) report.notes = notes.slice();
      if (reuse !== undefined) report.reuse = { ...reuse };
      return report;
    },
  };
}

function validateNote(note: TrailheadNoteInput): TrailheadNoteInput {
  const raw = note as { invalidates?: unknown; created?: { kind?: unknown; id?: unknown } } | null;
  if (raw != null && typeof raw.invalidates === "string" && raw.invalidates.length > 0 && raw.created === undefined) {
    return { invalidates: raw.invalidates };
  }
  if (
    raw != null &&
    raw.invalidates === undefined &&
    raw.created != null &&
    typeof raw.created.kind === "string" &&
    typeof raw.created.id === "string"
  ) {
    return { created: { kind: raw.created.kind, id: raw.created.id } };
  }
  throw new TypeError(
    "ctx.trailhead.note(note): pass exactly one of { invalidates: string } or " +
      "{ created: { kind: string, id: string } }",
  );
}

function parsePreviousRun(value: unknown): Omit<PreviousRun, "reuse"> | null {
  if (value == null || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (typeof v.sessionId !== "string" || typeof v.reason !== "string") return null;
  const state = v.state != null && typeof v.state === "object" && !Array.isArray(v.state)
    ? (v.state as JsonObject)
    : undefined;
  return state === undefined
    ? { sessionId: v.sessionId, reason: v.reason }
    : { sessionId: v.sessionId, reason: v.reason, state };
}

/**
 * Attach [report] to [result] as `_meta.trailblaze.trailhead`, wrapping a non-envelope result the
 * same way `attachMemoryDeltaToResult` does so the downstream normalizer keeps the `_meta`.
 */
export function attachTrailheadReportToResult(result: unknown, report: TrailheadReport | undefined): unknown {
  if (report === undefined) return result;
  const hasContentArray =
    typeof result === "object" && result !== null && Array.isArray((result as Record<string, unknown>).content);
  if (!hasContentArray) {
    const wrapped: Record<string, unknown> = {
      content:
        result === undefined || result === null
          ? []
          : [{ type: "text", text: typeof result === "string" ? result : JSON.stringify(result) }],
      _meta: { trailblaze: { [META_KEY_TRAILHEAD]: report } },
    };
    if (typeof result === "object" && result !== null) wrapped.structuredContent = result;
    return wrapped;
  }
  const record = result as Record<string, unknown>;
  const meta = typeof record._meta === "object" && record._meta !== null
    ? (record._meta as Record<string, unknown>)
    : {};
  const trailblaze = typeof meta.trailblaze === "object" && meta.trailblaze !== null
    ? (meta.trailblaze as Record<string, unknown>)
    : {};
  return { ...record, _meta: { ...meta, trailblaze: { ...trailblaze, [META_KEY_TRAILHEAD]: report } } };
}

/**
 * Mark [error] with [report] so the dispatcher can keep the notes of a tool that threw, and return
 * what to rethrow. A value that can't carry the report (a thrown string, a frozen error) comes back
 * as an `Error` with the same message that carries it, with the original as `cause`.
 */
export function markErrorWithTrailheadReport(error: unknown, report: TrailheadReport | undefined): unknown {
  if (report === undefined) return error;
  if (typeof error === "object" && error !== null && Object.isExtensible(error)) {
    try {
      (error as Record<symbol, unknown>)[TRAILHEAD_REPORT_ON_ERROR] = report;
      return error;
    } catch {
      // Falls through to the wrapper below.
    }
  }
  const message = error instanceof Error ? error.message : String(error);
  const wrapped = new Error(message);
  (wrapped as Error & { cause?: unknown }).cause = error;
  (wrapped as unknown as Record<symbol, unknown>)[TRAILHEAD_REPORT_ON_ERROR] = report;
  return wrapped;
}

/**
 * Build [TrailRun] from the wire payload the host sends (`{ outcome, notes }`). `events` calls
 * back into Kotlin through the reserved host functions.
 */
export function createTrailRun(wire: unknown, client: TrailblazeClient): TrailRun {
  const w = (wire ?? {}) as { outcome?: unknown; notes?: unknown };
  const outcome = (["passed", "failed", "cancelled", "setup-failed"] as const).find((o) => o === w.outcome);
  if (outcome === undefined) {
    throw new Error(`teardown: the host sent an unknown trail outcome: ${String(w.outcome)}`);
  }
  const notes = Array.isArray(w.notes) ? (w.notes as TrailheadNote[]) : [];
  const host = client.host as unknown as Record<string, (args: unknown) => Promise<unknown>>;
  const events: TrailEvents = {
    async list() {
      return (await host[HOST_FUNCTION_TRAIL_EVENTS_LIST]({})) as string[];
    },
    async query(stream: string, options?: TrailEventsQueryOptions) {
      const args: Record<string, unknown> = { stream };
      if (options?.match !== undefined) args.match = encodeMatch(options.match, "trailRun.events.query");
      if (options?.window !== undefined) args.window = options.window;
      if (options?.limit !== undefined) args.limit = options.limit;
      return (await host[HOST_FUNCTION_TRAIL_EVENTS_QUERY](args)) as SessionEvent[];
    },
  };
  return { outcome, notes, events };
}

/** A `RegExp` crosses to Kotlin as `{ "$regex": source, "$flags": flags }`. [caller] names the API in errors. */
export function encodeMatch(match: EventMatch, caller: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(match)) out[key] = encodeMatchValue(value, caller);
  return out;
}

function encodeMatchValue(value: EventMatchValue, caller: string): unknown {
  if (value instanceof RegExp) return { $regex: value.source, $flags: value.flags };
  if (typeof value === "function") {
    throw new TypeError(`${caller}: a match can't hold a function; filter the returned records instead`);
  }
  if (value !== null && typeof value === "object") return encodeMatch(value as EventMatch, caller);
  return value;
}

/**
 * Validate what `teardown` returned. A malformed result is a bug in the trailhead, so it throws
 * and the framework records `reusable: false`.
 */
export function validateTeardownResult(value: unknown): TeardownResult {
  const v = value as Partial<TeardownResult> | null | undefined;
  if (v == null || typeof v !== "object") {
    throw new TypeError("teardown must return { reusable: boolean, reason: string, state? }");
  }
  if (typeof v.reusable !== "boolean") throw new TypeError("teardown result: `reusable` must be a boolean");
  if (typeof v.reason !== "string" || v.reason.trim().length === 0) {
    throw new TypeError("teardown result: `reason` is required");
  }
  if (v.state !== undefined && (v.state === null || typeof v.state !== "object" || Array.isArray(v.state))) {
    throw new TypeError("teardown result: `state` must be a JSON object");
  }
  const out: TeardownResult = { reusable: v.reusable, reason: v.reason };
  if (v.state !== undefined) out.state = v.state;
  return out;
}

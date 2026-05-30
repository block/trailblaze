// `ConditionalAction` — a deterministic "if condition then action [verify postcondition]"
// primitive for app-author-written catalogs of small recoverable / preparatory actions
// (popup dismissal, optional onboarding skips, cart-state branches). The framework
// provides the primitive; app teams pick the tool shape that wraps it (bulk-dismiss
// tool, internal compose into launch, etc.).
//
// Replaces the deleted prompt-pattern classifier (`CONDITIONAL_PATTERNS`) — see
// #3455. That classifier silently misrouted `If "X"` / `If 'X'` / `If it is X`
// phrasings because it was a substring/regex over natural-language prompts; this
// primitive is structural, not prompt-derived, so the failure mode is impossible by
// construction.
//
// ## Architecture — snapshot-then-bulk-evaluate (local predicates)
//
// One [ViewHierarchy] captured per invocation; **all predicates run locally
// against that in-memory snapshot** with no further round-trips. `runConditionalActions`
// filters once (postcondition skip-fast + condition match), then walks the
// applicable entries in order, dispatching each `action` and verifying its
// `postcondition` if declared. Catalog size is irrelevant beyond the local
// filter pass.
//
// ## Phase 2 acquisition path (and its limits)
//
// The host has no single-shot whole-tree snapshot tool today. For Phase 2:
//
//  - Authors build the snapshot via `captureViewHierarchy(client, [...selectors])`
//    (re-exported from `view-hierarchy.ts`).
//  - That helper dispatches one `findMatches` per selector in parallel. **Each
//    `findMatches` callback enters its own nested `runTrailblazeTools` frame on
//    the daemon** (see `MaestroTrailblazeAgent.kt:214` for the wiring) and
//    captures its own view-hierarchy — they do NOT share a single SnapshotCache
//    frame. So for N declared selectors, Phase 2 pays N callbacks AND N view-
//    hierarchy captures. (The cache-sharing claim in `built-in-tools.ts:206-209`
//    applies to the Kotlin-side in-batch loop, NOT this scripting-callback path.)
//  - Parallel dispatch minimizes the wall-clock window between captures (less
//    time for the UI to drift between them), but the snapshot is still **not
//    atomic**. Authors writing predicates against the returned snapshot should
//    tolerate small inter-frame drift; for strictly atomic single-frame
//    semantics, wait for the host-side bulk-snapshot tool in Phase 3+.
//  - `runConditionalActions` requires a `presnapshot` argument. The auto-acquire
//    fallback (`client.snapshot()`) the issue's pseudocode describes lands when
//    the host-side full-tree snapshot tool ships; until then, omitting
//    `presnapshot` throws a clear error pointing at `captureViewHierarchy`.

import type { TrailblazeClient } from "./client.js";
import {
  reCaptureViewHierarchy,
  type ViewHierarchy,
} from "./view-hierarchy.js";

/**
 * One entry in a ConditionalAction catalog. Each entry is a self-contained
 * "if [condition] then [action] [verify postcondition]" rule.
 *
 *  - [id] — stable identifier; surfaces in [ConditionalActionFailedError] and in the
 *    [runConditionalActions] return value's `handled` list. Authors pick the shape
 *    (kebab-case, snake-case, whatever the catalog convention is).
 *    **Uniqueness is a catalog-author obligation, NOT enforced by the framework.**
 *    `runConditionalActions` does not deduplicate or validate ids; if two entries
 *    share an id and both match, the returned `handled` array contains the id
 *    twice and any failure throws with that id ambiguously pointing at either
 *    entry. Author validation (a static analyzer, a startup-time assertion in the
 *    adopting team's tool) is the right layer for the check.
 *  - [description] — human-readable purpose; surfaces in LLM tool schemas when
 *    an adopting team wraps the catalog into a Trailblaze tool.
 *  - [condition] — pure synchronous predicate evaluated against a captured
 *    [ViewHierarchy]. Returning `true` means this entry should run. Coerced to
 *    boolean via `Boolean(...)` at the call site so accidental truthy-but-not-
 *    boolean returns (`1`, `"x"`, etc.) behave per the declared `boolean` type.
 *    A throw from this predicate is caught and re-raised as a
 *    [ConditionalActionFailedError] carrying the entry's [id].
 *  - [action] — async work that mutates device state, typically composing other
 *    Trailblaze tools via the surrounding tool's `client.tools.<name>(...)` /
 *    `ctx.tools.<name>(...)`. **Closure pattern:** the action receives no
 *    arguments; the author closes over their `client` / `ctx` from the
 *    surrounding tool body. This is intentional — the condition runs during
 *    the bulk-filter phase against the captured snapshot, but the action runs
 *    later (per entry, after applicable filtering) and the closure makes it
 *    explicit which scope the side effects fire in. A throw or rejection from
 *    the action is caught and re-raised as a [ConditionalActionFailedError] carrying
 *    the entry's [id].
 *  - [postcondition] — optional pure predicate that does **double duty**:
 *      1. **Before [action]:** if already satisfied, skip the entry entirely
 *         (fast-path short-circuit — covers "popup already dismissed").
 *      2. **After [action]:** if not satisfied, raise
 *         [ConditionalActionFailedError] with the entry's [id]. Surfaces catalog
 *         drift / wrong-screen detection.
 *    Coerced to boolean via `Boolean(...)` at each call site. A throw from
 *    this predicate is caught and re-raised as a [ConditionalActionFailedError].
 */
export interface ConditionalAction {
  readonly id: string;
  readonly description: string;
  readonly condition: (snap: ViewHierarchy) => boolean;
  readonly action: () => Promise<void>;
  readonly postcondition?: (snap: ViewHierarchy) => boolean;
}

/**
 * Thrown when a [ConditionalAction] entry's execution fails. Three causes — all carry
 * the offending entry's [conditionalActionId] so a catch-site can point at the
 * specific catalog entry:
 *
 *  - **`postcondition` returned false after `action` ran** — the original
 *    failure mode the primitive's design centers on. Surfaces catalog drift,
 *    wrong-screen detection, or a race with an un-cataloged modal.
 *  - **`action` threw / rejected** — wrapping the action's failure with the
 *    entry id is more useful than a bare propagation; catch-sites that want
 *    to handle action failures specifically can read [conditionalActionId].
 *  - **`condition` or `postcondition` threw** — predicates are nominally pure
 *    functions returning booleans, but TypeScript can't enforce that at
 *    runtime. A predicate that throws is treated as a catalog-authoring bug;
 *    the throw is wrapped with the entry id so the offending catalog entry is
 *    immediately identifiable.
 *
 * The [cause] property carries the original error (per ES2022 `Error.cause`
 * conventions) when the failure was an underlying throw — `null` when the
 * failure was a `postcondition` returning false (no underlying error).
 */
export class ConditionalActionFailedError extends Error {
  readonly conditionalActionId: string;
  // `cause` declared as a field (not via `override`) because the SDK's
  // baseline TS lib doesn't ship the ES2022 `Error.cause` declaration — the
  // typed `Error` constructor has no `cause` parameter on the lowered lib, so
  // there's nothing to `override`. The field still satisfies the runtime
  // `Error.cause` convention on engines that read it.
  readonly cause: unknown;
  constructor(conditionalActionId: string, message: string, cause: unknown = null) {
    super(message);
    this.name = "ConditionalActionFailedError";
    this.conditionalActionId = conditionalActionId;
    this.cause = cause;
    // V8-only stack-trace pruning. `@types/node` declares this as a static on
    // `Error`, but the SDK deliberately avoids a hard `@types/node` dependency
    // (see `client.ts:380`) so we hand-cast through `unknown`. Other engines
    // (QuickJS on-device) fall back to the standard `Error` stack — cosmetic
    // only; the message + conditionalActionId stay correct everywhere.
    if (typeof (Error as { captureStackTrace?: unknown }).captureStackTrace === "function") {
      (Error as unknown as {
        captureStackTrace: (target: object, ctor: Function) => void;
      }).captureStackTrace(this, ConditionalActionFailedError);
    }
  }
}

/**
 * Walk `conditionalActions` against a captured snapshot and execute the entries that apply.
 *
 *  1. **Acquire the snapshot.** Use `presnapshot` if provided; otherwise throw
 *     — Phase 2 has no auto-acquire path (see file header for the host-side
 *     tool dependency).
 *  2. **Bulk filter.** For each entry: skip if `postcondition` is already
 *     satisfied (fast-path short-circuit), otherwise include if `condition`
 *     matches. Pure local computation regardless of catalog size.
 *  3. **Execute applicable entries in order.** For each: run `action`, then if
 *     a `postcondition` was declared, refresh the snapshot and verify. Any
 *     failure (predicate throw, action throw, postcondition returning false)
 *     raises [ConditionalActionFailedError] carrying the offending entry's id.
 *
 * **Cost in the no-match case:** zero extra round-trips when `presnapshot` is
 * passed (the common case — the caller has already built one via
 * `captureViewHierarchy`). N predicate evaluations are pure local computation
 * regardless of catalog size. Phase 2 has no built-in auto-acquire path, so
 * "otherwise" the call throws — the cost of acquisition lives entirely in
 * `captureViewHierarchy`, which pays one `findMatches` callback per declared
 * selector (see this file's header for the acquisition-path caveat).
 *
 * **Cost in the all-match case:** one round-trip per applicable entry's
 * `action`, plus M `findMatches` callbacks per declared `postcondition` for
 * the post-action verify (where M is the size of the original presnapshot's
 * selector set — the verify refreshes the full set via `reCaptureViewHierarchy`,
 * since the implementation can't introspect which selectors a given
 * `postcondition` will probe).
 *
 * **Verification snapshot** — for entries with a `postcondition`, the verify
 * snapshot is captured against the same set of selectors as `presnapshot` (via
 * `reCaptureViewHierarchy`). The implementation can't statically know which
 * selectors a future `postcondition` will reference, so it reuses the
 * pre-existing list — same coverage as the initial check.
 *
 * @throws [ConditionalActionFailedError] when an entry's `condition` /
 *   `postcondition` predicate throws, `action` throws, or `postcondition`
 *   returns false after action.
 * @throws `Error` when `presnapshot` is undefined (Phase 2 limitation — see
 *   file header).
 */
export async function runConditionalActions(
  client: TrailblazeClient,
  conditionalActions: readonly ConditionalAction[],
  presnapshot?: ViewHierarchy,
): Promise<{ handled: string[] }> {
  if (conditionalActions.length === 0) {
    // Short-circuit before the snapshot check — an empty catalog has nothing
    // to evaluate, so requiring a presnapshot would be needless friction. The
    // caller's expected shape (`{ handled: [] }`) is unambiguous.
    return { handled: [] };
  }

  const snap = presnapshot ?? throwNoSnapshot();

  // Bulk filter: postcondition skip-fast, then condition match. Order matters
  // — checking postcondition FIRST means "already-handled" entries cost zero
  // action dispatches even when their `condition` would otherwise match (the
  // issue's "popup already dismissed" fast-path).
  //
  // `evalPostcondition` / `evalCondition` coerce truthy/falsy returns to strict
  // boolean and wrap any thrown error as a [ConditionalActionFailedError] carrying
  // the entry id — see their kdocs.
  const applicable = conditionalActions.filter(
    (c) => !evalPostcondition(c, snap) && evalCondition(c, snap),
  );
  if (applicable.length === 0) {
    return { handled: [] };
  }

  const handled: string[] = [];
  for (const c of applicable) {
    await runAction(c);
    if (c.postcondition) {
      const verifySnap = await reCaptureViewHierarchy(client, snap);
      if (!evalPostcondition(c, verifySnap)) {
        throw new ConditionalActionFailedError(
          c.id,
          `postcondition not satisfied after action for ${describe(c)}`,
        );
      }
    }
    handled.push(c.id);
  }
  return { handled };
}

/**
 * Format a [ConditionalAction] entry for human-readable error messages — `id` + `description`.
 *
 * Used in [ConditionalActionFailedError] message bodies so a catch-site that logs the
 * failure has enough context to identify the offending entry without cross-referencing the
 * catalog source. Without the description, "conditional X failed" in a 20-entry catalog
 * forces the on-call to grep the source; with the description, the message is self-
 * describing ("conditional 'dismiss-tos-popup' (Close the ToS popup if present) failed").
 *
 * Empty descriptions fall back to `id` alone — keeps the format honest when an entry was
 * authored without a description (the kdoc says it should never be empty, but the framework
 * doesn't enforce that).
 */
function describe(c: ConditionalAction): string {
  if (c.description && c.description.length > 0) {
    return `conditional "${c.id}" (${c.description})`;
  }
  return `conditional "${c.id}"`;
}

/**
 * Evaluate a [ConditionalAction]'s `condition` predicate. Returns a strict boolean
 * (coerces truthy/falsy via `Boolean(...)` since TS can't enforce a `boolean`
 * return at runtime). A throw from the predicate is treated as a catalog bug
 * and re-raised as [ConditionalActionFailedError] so the offending entry id is in
 * the failure path.
 */
function evalCondition(c: ConditionalAction, snap: ViewHierarchy): boolean {
  try {
    return Boolean(c.condition(snap));
  } catch (e) {
    throw new ConditionalActionFailedError(
      c.id,
      `condition predicate threw for ${describe(c)}: ${formatError(e)}`,
      e,
    );
  }
}

/**
 * Evaluate a [ConditionalAction]'s `postcondition` predicate. Returns `false` when
 * the predicate is absent (so callers can use `!evalPostcondition(...)` as
 * "should run"). Same boolean-coercion + throw-wrapping as [evalCondition].
 */
function evalPostcondition(c: ConditionalAction, snap: ViewHierarchy): boolean {
  if (!c.postcondition) return false;
  try {
    return Boolean(c.postcondition(snap));
  } catch (e) {
    throw new ConditionalActionFailedError(
      c.id,
      `postcondition predicate threw for ${describe(c)}: ${formatError(e)}`,
      e,
    );
  }
}

/**
 * Dispatch a [ConditionalAction]'s `action`. Wraps a throw / rejection as
 * [ConditionalActionFailedError] so the entry id is in the failure path — without
 * this, an action throwing from inside `runConditionalActions` would surface a bare
 * `Error` and the catch-site couldn't tell which catalog entry failed.
 */
async function runAction(c: ConditionalAction): Promise<void> {
  try {
    await c.action();
  } catch (e) {
    // If the action itself already threw a ConditionalActionFailedError (e.g. a
    // composed sub-catalog), preserve the inner id rather than overwriting it
    // — the original error is more specific.
    if (e instanceof ConditionalActionFailedError) throw e;
    throw new ConditionalActionFailedError(
      c.id,
      `action threw for ${describe(c)}: ${formatError(e)}`,
      e,
    );
  }
}

function formatError(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}

function throwNoSnapshot(): never {
  // Phase 2 limitation — host-side `client.snapshot()` lands later (#3455 Phase 3+).
  // The error message names the workaround and the constraint so an adopter
  // hitting this knows exactly what to do next.
  throw new Error(
    "runConditionalActions: presnapshot is required in Phase 2. Build one via " +
      "`await captureViewHierarchy(client, [...selectors])` and pass it in, " +
      "where the selectors are the ones your condition / postcondition " +
      "predicates probe. See the `wikipedia_conditional_action_demo` example " +
      "tool in the wikipedia trailmap for an end-to-end usage. The " +
      "auto-acquire fallback lands when the host-side full-tree snapshot " +
      "tool ships.",
  );
}

// Re-export `ViewHierarchy` and `captureViewHierarchy` from this module's
// barrel so that authors who only import `@trailblaze/scripting` still see the
// full ConditionalAction surface in one place — the actual definitions live in
// `view-hierarchy.ts` (split for convergence with future waypoint detection;
// see that file's header).
export {
  captureViewHierarchy,
  type ViewHierarchy,
} from "./view-hierarchy.js";

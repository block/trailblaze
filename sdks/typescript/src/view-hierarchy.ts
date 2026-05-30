// `ViewHierarchy` — sync, locally-evaluated view of a captured device snapshot.
// Defined in its own file (rather than alongside the `ConditionalAction` primitive that
// motivated it) because the issue's "Convergence with waypoint detection" note calls
// out `ViewHierarchy` as the **shared** primitive: both ConditionalAction bulk-evaluation
// and future waypoint resolution should consume the same `visible` / `find` / `findAll`
// shape against one captured snapshot. Splitting now lets the waypoint subsystem
// import directly from here without dragging the ConditionalAction logic into its module
// graph when it lands.
//
// ## Phase 2 acquisition path
//
// The host has no whole-tree snapshot tool today (#3455 Phase 3+). For Phase 2:
//
//  - [captureViewHierarchy] builds a [ViewHierarchy] by pre-resolving a declared
//    list of selectors in parallel via the existing `findMatches` framework tool.
//  - **Each `findMatches` callback enters its own nested `runTrailblazeTools`
//    frame on the daemon** (see `MaestroTrailblazeAgent.kt:214` for the wiring)
//    and captures its own view-hierarchy via the SnapshotCache fallback path.
//    The kdoc claim in `built-in-tools.ts:206-209` about cache sharing applies
//    to the Kotlin in-batch dispatch loop only — it does NOT apply to the
//    scripting-callback path. So for N selectors, Phase 2 pays N callbacks
//    AND N view-hierarchy captures.
//  - Parallel dispatch minimizes the wall-clock window between captures, but
//    the resulting snapshot is **not atomic** — under heavy UI animation, two
//    selectors in the same `captureViewHierarchy` call could reflect different
//    point-in-time states. Authoring predicates against the returned snapshot
//    should tolerate small inter-frame drift; for strictly atomic single-frame
//    semantics, wait for the host-side bulk-snapshot tool that the Phase 3+
//    auto-acquire path will use.
//  - Queries for selectors NOT in the pre-resolved list throw a clear error.
//
// When the host-side full-tree snapshot tool ships (Phase 3+), the [ViewHierarchy]
// interface stays the same — only the implementation changes (the captured tree
// resolves arbitrary selectors without pre-declaration, in one frame, atomically).

import type { TrailblazeClient } from "./client.js";
import type {
  MatchDescriptor,
  TrailblazeNodeSelector,
} from "./generated/selectors.js";

/**
 * Sync, locally-evaluated view of a captured device snapshot. Predicate helpers
 * query resolved matches without further round-trips so a synchronous predicate
 * (e.g. a [ConditionalAction]'s `condition` / `postcondition`) can run inside a
 * `.filter(...)` callback.
 *
 * **Shared with waypoint detection.** Per the #3455 "Convergence with
 * waypoint detection" note, waypoint resolution is the same problem shape (N
 * detector predicates against one snapshot). Whichever subsystem ships first
 * defines the predicate surface; the other consumes it. Adding a method here is
 * a cross-subsystem decision — if a new helper makes sense for both ConditionalAction
 * and waypoint authors, add it; if it's specific to one, prefer a subsystem-local
 * utility that takes a [ViewHierarchy] argument.
 *
 * **Backed by pre-resolved selectors in Phase 2.** [captureViewHierarchy] builds
 * an instance from a declared list of selectors; queries for selectors not in
 * that list throw. When the host-side full-tree snapshot tool ships (Phase 3+),
 * the snapshot can be backed by the captured tree directly and arbitrary
 * selectors will resolve without pre-declaration — the interface won't change,
 * just the implementation.
 *
 * Naming note: TypeScript-side `ViewHierarchy` is a sync data carrier with
 * predicate helpers. The Kotlin side's `ViewHierarchyTreeNode` is the actual
 * captured tree. The shared word "ViewHierarchy" is intentional (both represent
 * the same captured state); the shape differs because the consumer needs differ.
 */
export interface ViewHierarchy {
  /** Returns `true` if at least one node in the snapshot matches `selector`. */
  visible(selector: TrailblazeNodeSelector): boolean;
  /** Returns the first matching node, or `null` if none match. */
  find(selector: TrailblazeNodeSelector): MatchDescriptor | null;
  /** Returns every matching node — empty array if none. */
  findAll(selector: TrailblazeNodeSelector): MatchDescriptor[];
}

/**
 * Internal marker — pre-resolved selector keys carried alongside a [ViewHierarchy]
 * so consumers (e.g. `runConditionalActions` for post-action verify snapshots) can
 * refresh against the same selector set without threading the list through every
 * call site. Read by [reCaptureViewHierarchy]; populated by [captureViewHierarchy].
 *
 * File-scoped (`unique symbol`) rather than exported — downstream consumers
 * shouldn't construct a registry-tagged snapshot manually; the only supported
 * path is through [captureViewHierarchy].
 */
const SELECTOR_REGISTRY: unique symbol = Symbol("trailblaze.viewHierarchy.selectorRegistry");

interface SnapshotWithRegistry extends ViewHierarchy {
  [SELECTOR_REGISTRY]: readonly TrailblazeNodeSelector[];
}

/**
 * Pre-resolve a declared list of selectors against the live device and return a
 * sync [ViewHierarchy] whose `visible` / `find` / `findAll` queries serve from
 * in-memory results.
 *
 * Each selector is dispatched via `client.tools.findMatches({ selector })` in
 * parallel. **Each callback re-captures the view hierarchy** (see this file's
 * header) — Phase 2 has no single-frame multi-selector capture path. Parallel
 * dispatch keeps the wall-clock window small, but the result is not strictly
 * atomic; predicates should tolerate small inter-frame drift. Calling
 * `snap.visible(selectorNotInList)` throws — the snapshot only knows about
 * selectors it pre-resolved.
 *
 * **Selector identity.** The lookup key is the JSON serialization of the
 * selector object — `{ androidAccessibility: { textRegex: "Submit" } }` and a
 * separately-constructed `selectors.androidAccessibility({ textRegex: "Submit" })`
 * resolve to the same key because the factory produces an identical literal
 * shape. Field order matters to `JSON.stringify`; if two selectors differ only
 * in key order they'll be treated as distinct (a no-op in practice — TS object
 * literals serialize in insertion order and authors rarely construct the same
 * selector twice with shuffled keys, but worth noting).
 *
 * **Immutability.** The returned [ViewHierarchy] takes ownership of an
 * internal selector list (copied from the caller-provided array) and its
 * resolved [MatchDescriptor] arrays (copied per-selector by `findAll`). Mutating
 * the caller's `selectors` array after this call doesn't affect the snapshot,
 * and mutating the array returned by `findAll(...)` doesn't affect later
 * `findAll(...)` calls or the post-action verify refresh.
 *
 * Returns a [ViewHierarchy] with the internal [SELECTOR_REGISTRY] marker
 * attached so consumers can refresh against the same selector set via
 * [reCaptureViewHierarchy].
 *
 * @throws `Error` if any underlying `findMatches` call fails. Surfaces the
 *   daemon's error message verbatim with the failing selector for diagnosis.
 */
export async function captureViewHierarchy(
  client: TrailblazeClient,
  selectors: readonly TrailblazeNodeSelector[],
): Promise<ViewHierarchy> {
  // Defensive copy of the caller's array so a later mutation by the caller
  // can't alter the snapshot's registry. Slice (not freeze) so the internal
  // copy can still be passed by reference to `reCaptureViewHierarchy` without
  // cross-boundary mutation risk — nothing inside the SDK writes to it.
  const ownedSelectors: readonly TrailblazeNodeSelector[] = selectors.slice();
  const resolved = await resolveSelectors(client, ownedSelectors);
  return buildSnapshot(resolved, ownedSelectors);
}

/**
 * Refresh a snapshot built by [captureViewHierarchy] against the same selector
 * set. Used by `runConditionalActions` to capture the post-action verify snapshot
 * without the caller having to thread the original selector list through.
 *
 * Phase 2 — only snapshots built by [captureViewHierarchy] can be refreshed
 * (they carry the internal [SELECTOR_REGISTRY] marker). A custom [ViewHierarchy]
 * (e.g. a unit-test fake) won't have the marker; this function throws with a
 * clear message in that case.
 *
 * Internal — exported only so `conditional-action.ts` can consume it. Not re-exported
 * from `index.ts`.
 *
 * @throws `Error` when the snapshot wasn't built via [captureViewHierarchy].
 */
export async function reCaptureViewHierarchy(
  client: TrailblazeClient,
  base: ViewHierarchy,
): Promise<ViewHierarchy> {
  const tagged = base as Partial<SnapshotWithRegistry>;
  const selectors = tagged[SELECTOR_REGISTRY];
  if (!selectors) {
    throw new Error(
      "reCaptureViewHierarchy: cannot refresh this snapshot — it wasn't built " +
        "via captureViewHierarchy(). The refresh path is only used by callers " +
        "that need a verify snapshot AFTER a state-mutating action (e.g. " +
        "runConditionalActions's postcondition check). Either build the snapshot via " +
        "captureViewHierarchy(client, [...selectors]) so the framework can " +
        "refresh it, or skip postcondition-style verification.",
    );
  }
  return captureViewHierarchy(client, selectors);
}

async function resolveSelectors(
  client: TrailblazeClient,
  selectors: readonly TrailblazeNodeSelector[],
): Promise<Map<string, MatchDescriptor[]>> {
  // Parallel dispatch — each `findMatches` callback re-captures the view
  // hierarchy in its own `runTrailblazeTools` frame (see file header), so we
  // can't avoid N captures. What parallelism DOES buy is a shorter wall-clock
  // window between the first and last capture, which minimizes the chance of
  // inter-frame UI drift across the resulting snapshot. Sequential dispatch
  // would widen that window without reducing cost.
  const entries = await Promise.all(
    selectors.map(async (selector) => {
      const key = selectorKey(selector);
      const matches = await client.tools.findMatches({ selector });
      return [key, matches] as const;
    }),
  );
  const map = new Map<string, MatchDescriptor[]>();
  for (const [key, matches] of entries) {
    map.set(key, matches);
  }
  return map;
}

function buildSnapshot(
  resolved: Map<string, MatchDescriptor[]>,
  selectors: readonly TrailblazeNodeSelector[],
): SnapshotWithRegistry {
  const matchesFor = (selector: TrailblazeNodeSelector): MatchDescriptor[] => {
    const key = selectorKey(selector);
    const matches = resolved.get(key);
    if (matches === undefined) {
      throw new Error(
        `ViewHierarchy: selector ${JSON.stringify(selector)} was not pre-resolved. ` +
          "Add it to the selectors list passed to captureViewHierarchy(client, [...]).",
      );
    }
    return matches;
  };

  const snap: SnapshotWithRegistry = {
    visible(selector) {
      return matchesFor(selector).length > 0;
    },
    find(selector) {
      const matches = matchesFor(selector);
      return matches.length > 0 ? matches[0]! : null;
    },
    findAll(selector) {
      // Defensive copy of the internal MatchDescriptor array — if a caller
      // mutated the returned array, subsequent `findAll(...)` calls (or the
      // post-action verify refresh) could read corrupted state. The slice is
      // cheap (N MatchDescriptors per call) and the snapshot's immutability
      // is the stable mental model for predicate authors.
      return matchesFor(selector).slice();
    },
    [SELECTOR_REGISTRY]: selectors,
  };
  return snap;
}

function selectorKey(selector: TrailblazeNodeSelector): string {
  // Stable identity for the selector. Field-order-sensitive, which is fine —
  // authors construct each catalog entry once and reuse the same literal shape
  // across condition / postcondition / pre-resolution list. See
  // [captureViewHierarchy]'s "Selector identity" note.
  return JSON.stringify(selector);
}

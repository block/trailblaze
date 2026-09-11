// `ViewHierarchy` — sync, locally-evaluated view of a captured device snapshot.
// Defined in its own file (rather than alongside the `ConditionalAction` primitive that
// motivated it) because the issue's "Convergence with waypoint detection" note calls
// out `ViewHierarchy` as the **shared** primitive: both ConditionalAction bulk-evaluation
// and future waypoint resolution should consume the same `visible` / `find` / `findAll`
// shape against one captured snapshot. Splitting now lets the waypoint subsystem
// import directly from here without dragging the ConditionalAction logic into its module
// graph when it lands.
//
// ## Acquisition path
//
//  - [captureViewHierarchy] builds a [ViewHierarchy] by pre-resolving a declared
//    list of selectors via ONE `findSelectorMatches` framework call, which
//    takes a single view-hierarchy capture on the host and resolves every
//    selector against that one tree.
//  - **The snapshot is atomic**: one capture, so every selector's answer
//    describes the same instant of the same screen. Predicates can compare two
//    selectors' results without worrying about inter-frame UI drift.
//  - **One capture, not N.** The earlier shape dispatched N parallel
//    `findMatches` calls, and each callback enters its own nested
//    `runTrailblazeTools` frame on the daemon (see `MaestroTrailblazeAgent.kt:214`)
//    and captures its own hierarchy via the SnapshotCache fallback path — so
//    parallelism narrowed the drift window but still paid N multi-second
//    captures. The kdoc claim in `built-in-tools.ts` about in-batch cache
//    sharing applies to the Kotlin dispatch loop only, never to the
//    scripting-callback path; batching had to become explicit in the tool.
//  - Queries for selectors NOT in the pre-resolved list throw a clear error.
//
// Pre-declaration is what remains of the Phase 2 shape. The host still has no
// tool that ships the whole tree to the script (#3455 Phase 3+), so selectors
// are resolved host-side and only their matches cross the boundary. When that
// tool lands the [ViewHierarchy] interface stays the same — arbitrary selectors
// would resolve without pre-declaration, and the atomicity this already has
// would come from holding the tree locally instead.

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
 * **Backed by pre-resolved selectors.** [captureViewHierarchy] builds an
 * instance from a declared list of selectors, resolved host-side against one
 * capture; queries for selectors not in that list throw. When the host-side
 * full-tree snapshot tool ships (#3455 Phase 3+), the snapshot can be backed by
 * the captured tree directly and arbitrary selectors will resolve without
 * pre-declaration — the interface won't change, just the implementation.
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
 * Every selector is resolved by ONE `client.tools.findSelectorMatches(...)`
 * call, which takes a single host-side view-hierarchy capture — so the snapshot
 * costs one capture regardless of how many selectors it carries, and it is
 * atomic: all answers describe the same instant of the same screen. Calling
 * `snap.visible(selectorNotInList)` throws — the snapshot only knows about
 * selectors it pre-resolved. An empty selector list makes no device call.
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
 * @throws `Error` if the single `findSelectorMatches` call fails, surfacing the
 *   daemon's message verbatim. There is no "failing selector" to name — one call
 *   answers all of them, and it fails only when the question could not be asked at
 *   all (no tree captured, or an empty answer that came out of a known-partial
 *   capture and so cannot be trusted as absence). A selector that simply matches
 *   nothing is NOT an error: it resolves to an empty array, and `visible` returns
 *   `false`.
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
  const map = new Map<string, MatchDescriptor[]>();
  // No selectors declared means no device round trip at all. `findSelectorMatches` rejects an
  // empty list (a capture with nothing to ask of it is a caller bug there), but an empty snapshot
  // is legitimate here — a catalog whose entries carry no selectors — and it answers every query
  // with the "not pre-resolved" error either way.
  if (selectors.length === 0) return map;

  // ONE call, one host-side capture, every selector resolved against that same tree. This is
  // what makes the snapshot atomic as well as cheap: see the file header for why N parallel
  // `findMatches` calls could not be deduplicated by the host's snapshot cache.
  const matchesPerSelector = await client.tools.findSelectorMatches({
    selectors: [...selectors],
  });
  if (matchesPerSelector.length !== selectors.length) {
    // The tool's contract is index alignment. A mismatch means the wire shape changed without
    // this caller being updated — fail loudly rather than silently mis-attributing one
    // selector's matches to another, which would surface as an inexplicably wrong predicate.
    throw new Error(
      `captureViewHierarchy: findSelectorMatches returned ${matchesPerSelector.length} ` +
        `result(s) for ${selectors.length} selector(s); results are index-aligned to the ` +
        "selectors, so this is a framework/SDK version mismatch.",
    );
  }
  selectors.forEach((selector, index) => {
    map.set(selectorKey(selector), matchesPerSelector[index]!);
  });
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

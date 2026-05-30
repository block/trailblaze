// PORT of `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNode.kt`.
//
// SOURCE OF TRUTH is the Kotlin file above. This TS port is part of Phase 3 of #3455 —
// it lets the TS-side `ViewHierarchy` walk a captured tree locally rather than paying
// one host callback per selector. Parity with the Kotlin matcher is enforced by a
// JVM-side test that runs each fixture through both implementations; any drift fails
// CI loudly. See `:trailblaze-models:jvmTest --tests MatcherParityTest` (lands in a
// later PR alongside this one).
//
// Rules of engagement for editing this file:
//   1. The Kotlin file is authoritative. Behavior changes start there.
//   2. Any change here without a matching Kotlin change will fail the parity test.
//   3. Comments here are condensed; the Kotlin file carries the full rationale.

import {
  hasIdentifiableProperties,
  isInteractive,
  type DriverNodeDetail,
} from "./driver-node-detail.js";

/**
 * Universal tree node for Trailblaze view hierarchies across all drivers.
 *
 * The common surface is deliberately minimal: tree structure, identity, and bounds.
 * Everything meaningful for element matching lives in [driverDetail], which is
 * strongly typed per driver via [DriverNodeDetail].
 *
 * See the Kotlin source for the design rationale (avoiding lowest-common-denominator
 * normalization across platforms).
 */
export interface TrailblazeNode {
  /** Auto-assigned ID within a single tree capture. Not stable across captures. */
  readonly nodeId: number;
  /**
   * Stable content-hashed ref for this element (e.g., "y778"). Null until the
   * compact-element-list builder runs. See Kotlin doc for the hashing rules.
   */
  readonly ref?: string | null;
  /**
   * Child nodes in the tree.
   *
   * **Optional on the wire.** The Kotlin side declares
   * `children: List<TrailblazeNode> = emptyList()` and the shared
   * `TrailblazeJson` config leaves `encodeDefaults` off, so leaf nodes omit
   * the `children` field entirely from serialized JSON. All walkers in this
   * file treat absent `children` as an empty list — read sites guard with
   * `?? []` rather than assuming the field is populated.
   */
  readonly children?: readonly TrailblazeNode[];
  /** Screen-coordinate bounding rectangle. Present on every platform. */
  readonly bounds?: Bounds | null;
  /** Driver-specific properties — this is where all the platform-native richness lives. */
  readonly driverDetail: DriverNodeDetail;
}

/** Screen-coordinate bounding rectangle. */
export interface Bounds {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

// ----- Bounds helpers ----- ---------------------------------------------------

export function boundsWidth(b: Bounds): number {
  return b.right - b.left;
}

export function boundsHeight(b: Bounds): number {
  return b.bottom - b.top;
}

export function boundsCenterX(b: Bounds): number {
  // Integer division on the Kotlin side; mirror via Math.trunc to keep parity for
  // odd widths. `(left + right) / 2` in Kotlin truncates toward zero for Int; in
  // JS the same expression returns a float, so the explicit trunc is load-bearing
  // for parity fixtures that compare exact int centers.
  return Math.trunc((b.left + b.right) / 2);
}

export function boundsCenterY(b: Bounds): number {
  return Math.trunc((b.top + b.bottom) / 2);
}

/** Returns true if `outer` fully contains `inner`. */
export function boundsContains(outer: Bounds, inner: Bounds): boolean {
  return (
    outer.left <= inner.left &&
    outer.top <= inner.top &&
    outer.right >= inner.right &&
    outer.bottom >= inner.bottom
  );
}

/** Returns true if point (x, y) is within `b`. */
export function boundsContainsPoint(b: Bounds, x: number, y: number): boolean {
  return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
}

/** Returns true if `a` overlaps `b` (strict — touching edges don't count). */
export function boundsIntersects(a: Bounds, b: Bounds): boolean {
  return (
    a.left < b.right &&
    a.right > b.left &&
    a.top < b.bottom &&
    a.bottom > b.top
  );
}

// ----- Tree helpers ----------------------------------------------------------

/**
 * Returns a copy of this tree with refs populated from a nodeId→ref mapping.
 * Mirrors `TrailblazeNode.withRefs(refMapping)` on the Kotlin side.
 */
export function withRefs(
  node: TrailblazeNode,
  refMapping: ReadonlyMap<number, string>,
): TrailblazeNode {
  return {
    ...node,
    ref: refMapping.get(node.nodeId) ?? node.ref ?? null,
    children: (node.children ?? []).map((c) => withRefs(c, refMapping)),
  };
}

/**
 * Flattens this node and all descendants into a single array (pre-order DFS).
 * Mirrors `TrailblazeNode.aggregate()`.
 */
export function aggregate(node: TrailblazeNode): TrailblazeNode[] {
  const result: TrailblazeNode[] = [node];
  for (const child of node.children ?? []) {
    aggregateInto(child, result);
  }
  return result;
}

function aggregateInto(node: TrailblazeNode, accumulator: TrailblazeNode[]): void {
  accumulator.push(node);
  for (const child of node.children ?? []) {
    aggregateInto(child, accumulator);
  }
}

/**
 * Returns the center point of this node's bounds, or null if bounds are unknown.
 * Mirrors `TrailblazeNode.centerPoint()`.
 */
export function centerPoint(node: TrailblazeNode): { x: number; y: number } | null {
  if (!node.bounds) return null;
  return { x: boundsCenterX(node.bounds), y: boundsCenterY(node.bounds) };
}

/**
 * Finds the first node matching `predicate` via DFS, or null.
 * Mirrors `TrailblazeNode.findFirst(predicate)`.
 */
export function findFirst(
  node: TrailblazeNode,
  predicate: (n: TrailblazeNode) => boolean,
): TrailblazeNode | null {
  if (predicate(node)) return node;
  for (const child of node.children ?? []) {
    const hit = findFirst(child, predicate);
    if (hit !== null) return hit;
  }
  return null;
}

/**
 * Finds all nodes matching `predicate` in the tree. Mirrors `TrailblazeNode.findAll(predicate)`.
 *
 * Named `findAllNodes` to avoid the obvious confusion with the SDK's `ViewHierarchy.findAll`
 * method on the public snapshot interface — they have different signatures and live at
 * different layers (this one is a tree walker; that one is a selector-resolution accessor).
 */
export function findAllNodes(
  node: TrailblazeNode,
  predicate: (n: TrailblazeNode) => boolean,
): TrailblazeNode[] {
  const results: TrailblazeNode[] = [];
  findAllInto(node, predicate, results);
  return results;
}

function findAllInto(
  node: TrailblazeNode,
  predicate: (n: TrailblazeNode) => boolean,
  accumulator: TrailblazeNode[],
): void {
  if (predicate(node)) accumulator.push(node);
  for (const child of node.children ?? []) {
    findAllInto(child, predicate, accumulator);
  }
}

/**
 * Hit-tests the tree at (x, y) and returns the frontmost node whose bounds contain
 * the point, or null if no node contains it. Mirrors `TrailblazeNode.hitTest(x, y)`.
 *
 * Priority order (see Kotlin source for the rationale):
 *   1. Interactive nodes first (the OS routes touches to them).
 *   2. Identifiable nodes (have a stable property) over propertyless containers.
 *   3. Smallest area wins among ties.
 *
 * Implementation: linear single-pass min finder that mirrors Kotlin's
 * `minWithOrNull(compareByDescending { ... }.thenByDescending { ... }.thenBy { ... })`
 * exactly — O(N) rather than O(N log N) sort, and ties resolve in
 * first-encountered order independently of engine sort stability (V8/Bun
 * happen to be stable, but anchoring the contract here removes the
 * cross-engine concern entirely).
 */
export function hitTest(
  root: TrailblazeNode,
  x: number,
  y: number,
): TrailblazeNode | null {
  let winner: TrailblazeNode | null = null;
  let winnerKey: HitTestKey | null = null;
  for (const node of aggregate(root)) {
    if (node.bounds == null || !boundsContainsPoint(node.bounds, x, y)) continue;
    const key = hitTestKey(node);
    if (winnerKey == null || compareHitTestKeys(key, winnerKey) < 0) {
      winner = node;
      winnerKey = key;
    }
  }
  return winner;
}

/**
 * Sort key for [hitTest]. `interactive` and `identifiable` are descending
 * (true beats false), `area` is ascending. We store interactive/identifiable
 * as integers so the comparator is plain arithmetic — `0 - 1 = -1` means
 * "left wins" under ascending ordering.
 */
interface HitTestKey {
  readonly interactiveRank: number; // 0 for true, 1 for false (descending = lower wins)
  readonly identifiableRank: number; // 0 for true, 1 for false (descending = lower wins)
  readonly area: number;
}

function hitTestKey(node: TrailblazeNode): HitTestKey {
  return {
    interactiveRank: isInteractive(node.driverDetail) ? 0 : 1,
    identifiableRank: hasIdentifiableProperties(node.driverDetail) ? 0 : 1,
    area: boundsWidth(node.bounds!) * boundsHeight(node.bounds!),
  };
}

function compareHitTestKeys(a: HitTestKey, b: HitTestKey): number {
  if (a.interactiveRank !== b.interactiveRank) return a.interactiveRank - b.interactiveRank;
  if (a.identifiableRank !== b.identifiableRank) return a.identifiableRank - b.identifiableRank;
  return a.area - b.area;
}

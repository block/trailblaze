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
  resolveText,
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
 * Hit-tests the tree at (x, y) and returns the node a touch there would act on, or null
 * if no node contains the point. Mirrors `TrailblazeNode.hitTest(x, y)`.
 *
 * Models real touch dispatch (see the Kotlin source for the full rationale):
 *   1. Pick the frontmost element containing the point — real area over degenerate
 *      (zero-area) nodes, then identifiable over propertyless, then smallest area, ties
 *      going to a *labeled* descendant when the tied nodes sit on one ancestor chain
 *      (unless the tied ancestor is itself interactive — a control captured with bounds
 *      identical to its child keeps its own touch; a label-less descendant never
 *      displaces its ancestor), sibling ties to the first node in document order.
 *   2. If it isn't interactive, climb *its own ancestors* to the first interactive one that
 *      also contains the point, stopping at any ancestor that encloses more than one piece
 *      of text (a control owns its icon and label; a list does not own its rows).
 *
 * Implementation: single DFS carrying the current ancestor path, so step 2 never leaves
 * the picked element's chain and the whole thing stays O(N).
 */
export function hitTest(
  root: TrailblazeNode,
  x: number,
  y: number,
): TrailblazeNode | null {
  const ancestors: TrailblazeNode[] = [];
  // A record rather than plain `let`s: TS narrows a `let` captured by a closure to its
  // initializer type, which would make the post-walk read of `best` a `never`.
  const best = {
    node: null as TrailblazeNode | null,
    degenerate: true,
    identifiable: false,
    area: Number.POSITIVE_INFINITY,
    ancestors: [] as TrailblazeNode[],
  };

  const visit = (node: TrailblazeNode): void => {
    if (node.bounds != null && boundsContainsPoint(node.bounds, x, y)) {
      const area = boundsWidth(node.bounds) * boundsHeight(node.bounds);
      const degenerate = area <= 0;
      const identifiable = hasIdentifiableProperties(node.driverDetail);
      // Capture for narrowing: `best.node` stays `TrailblazeNode | null` inside the
      // nested ternary, but a const local narrows past the null check.
      const bestNode = best.node;
      const labeled = ownLabel(node.driverDetail) != null;
      const wins =
        bestNode == null
          ? true
          : degenerate !== best.degenerate
            ? !degenerate
            : identifiable !== best.identifiable
              ? identifiable
              : area !== best.area
                ? area < best.area
                : // Exact-area tie on one ancestor chain goes to a *labeled* descendant
                  // (real hit-testing returns the deepest element; a label-less
                  // structural descendant would only weaken the selector) — unless the
                  // tied ancestor is itself interactive: the control keeps its own touch.
                  // Sibling ties keep the first node in document order.
                  labeled &&
                  !isInteractive(bestNode.driverDetail) &&
                  ancestors.some((ancestor) => ancestor === bestNode);
      if (wins) {
        best.node = node;
        best.degenerate = degenerate;
        best.identifiable = identifiable;
        best.area = area;
        best.ancestors = [...ancestors];
      }
    }
    ancestors.push(node);
    for (const child of node.children ?? []) visit(child);
    ancestors.pop();
  };
  visit(root);

  const target = best.node;
  if (target == null) return null;
  if (isInteractive(target.driverDetail)) return target;
  // `best.ancestors` is root-first, so iterate backwards to walk outward from the node.
  for (let i = best.ancestors.length - 1; i >= 0; i--) {
    const ancestor = best.ancestors[i]!;
    if (countLabels(ancestor, 2) >= 2) break;
    const bounds = ancestor.bounds;
    if (bounds == null) continue;
    if (boundsContainsPoint(bounds, x, y) && isInteractive(ancestor.driverDetail)) return ancestor;
  }
  return target;
}

/** Counts label-bearing nodes in this subtree, stopping as soon as `limit` is reached. */
function countLabels(node: TrailblazeNode, limit: number): number {
  let count = ownLabel(node.driverDetail) != null ? 1 : 0;
  for (const child of node.children ?? []) {
    if (count >= limit) return count;
    count += countLabels(child, limit - count);
  }
  return count;
}

/**
 * The node's own visible or spoken text, or null when it carries none. Mirrors the Kotlin
 * `ownLabel()`: blank-aware at every step of each variant's fallback chain — NOT delegated
 * to `resolveText()`, whose `??` chain stops at a blank `text` (captures serialize `text`
 * as "" on nodes labeled only via accessibilityText) and would report such a node as
 * unlabeled instead of falling back.
 *
 * Deliberately excludes `hintText`: a hint is a prompt for *absent* content, not content
 * the node carries. An empty Search EditText (`text = ""`, `hintText = "Search"`) wrapping
 * its static TextView("Search") must not read as labeled here, or the climb-stop counts
 * two labels, treats the control as a container, and strands the tap on the static child
 * instead of the editable field.
 */
function ownLabel(detail: DriverNodeDetail): string | null {
  switch (detail.class) {
    case "androidAccessibility":
      return firstNonBlank(detail.text, detail.contentDescription);
    case "androidMaestro":
      return firstNonBlank(detail.text, detail.accessibilityText);
    case "iosMaestro":
      return firstNonBlank(detail.text, detail.accessibilityText);
    case "iosAxe":
      // resolveText is already blank-aware per step for this variant.
      return resolveText(detail);
    case "compose":
      return firstNonBlank(detail.editableText, detail.text, detail.contentDescription);
    case "web":
      return firstNonBlank(detail.ariaName);
  }
}

function firstNonBlank(...candidates: Array<string | null | undefined>): string | null {
  for (const candidate of candidates) {
    if (candidate != null && candidate.trim().length > 0) return candidate;
  }
  return null;
}

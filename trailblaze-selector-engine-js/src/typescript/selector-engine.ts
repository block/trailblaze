// Typed TypeScript boundary over the Kotlin/JS selector engine bundle
// (build/dist/trailblaze-selector-engine.min.js, built by `bundleSelectorEngine`).
//
// The bundle installs three string-in/string-out functions at
// `globalThis.TrailblazeSelectorEngine` (see src/jsMain/.../SelectorEngineJs.kt — `@JsExport`
// cannot cross data classes, so the raw surface is JSON strings). This wrapper is what
// consumers import instead: typed functions whose input/output shapes come from the
// GENERATED bindings in `sdks/typescript/src/generated/selectors.ts` — the same
// Kotlin-canonical codegen the scripted-tool SDK uses — never a hand-written mirror.
// `TrailblazeSelectorAnalysis` & friends are emitted from the Kotlin source-of-truth file
// `trailblaze-models/.../api/TrailblazeSelectorAnalysis.kt`; regenerate with
// `./gradlew :trailblaze-models:generateSelectorsTs` after changing it.
import type {
  TrailblazeNodeSelector,
  TrailblazeSelectorAnalysis,
  TrailblazeSelectorResolution,
  TrailblazeSelectorTapResolution,
} from "../../../sdks/typescript/src/generated/selectors";

/**
 * A captured view hierarchy as it appears in the session log: a serialized `TrailblazeNode`
 * tree (polymorphic `driverDetail` under the `"class"` discriminator). Deliberately opaque —
 * the report treats hierarchies as pass-through payloads, and the engine deserializes with
 * the same kotlinx-serialization model the daemon wrote them with, so nothing useful is
 * gained (and drift is risked) by hand-declaring the shape here.
 */
export type HierarchyTreeJson = string | object;

/** The raw `@JsExport` surface the bundle installs at `globalThis.TrailblazeSelectorEngine`. */
interface RawSelectorEngine {
  computeSelectorAnalysis(treeJson: string, targetNodeId: string): string;
  resolveTapTarget(treeJson: string, x: number, y: number): string;
  resolveSelector(treeJson: string, selectorJson: string): string;
}

/** Typed selector-engine surface. All computation runs in the compiled Kotlin bundle. */
export interface SelectorEngine {
  /**
   * Ranked selector suggestions for one node of the tree — each option carries the selector
   * (in the exact shape the recorder writes into a trail), the strategy that produced it,
   * match count + matching node ids, the tap-time center, and whether tapping that center
   * would really hit the target node.
   */
  computeSelectorAnalysis(tree: HierarchyTreeJson, nodeId: number | string): TrailblazeSelectorAnalysis;
  /**
   * Resolves a tap coordinate to its target node + best selector with round-trip
   * verification — the same call the daemon's recorder makes for every recorded tap.
   */
  resolveTapTarget(tree: HierarchyTreeJson, x: number, y: number): TrailblazeSelectorTapResolution;
  /** Resolves an arbitrary selector against the tree — "test this selector". */
  resolveSelector(tree: HierarchyTreeJson, selector: TrailblazeNodeSelector): TrailblazeSelectorResolution;
}

function asTreeJson(tree: HierarchyTreeJson): string {
  return typeof tree === "string" ? tree : JSON.stringify(tree);
}

/**
 * Returns the typed engine when the Kotlin/JS bundle has been evaluated (it installs itself
 * at `globalThis.TrailblazeSelectorEngine`), or null when it hasn't — e.g. a report built
 * without the engine payload. Callers hide the selector-suggestions UI on null. Every entry
 * point is validated up front so a partial/incompatible global reads as "engine absent"
 * rather than throwing on the first call to a missing function.
 */
export function loadSelectorEngine(host: object = globalThis): SelectorEngine | null {
  const raw = (host as { TrailblazeSelectorEngine?: RawSelectorEngine }).TrailblazeSelectorEngine;
  if (
    raw == null ||
    typeof raw.computeSelectorAnalysis !== "function" ||
    typeof raw.resolveTapTarget !== "function" ||
    typeof raw.resolveSelector !== "function"
  ) {
    return null;
  }
  return {
    computeSelectorAnalysis: (tree, nodeId) =>
      JSON.parse(raw.computeSelectorAnalysis(asTreeJson(tree), String(nodeId))) as TrailblazeSelectorAnalysis,
    // Math.trunc: the Kotlin side takes Ints, and browser coordinates are often fractional —
    // truncation (toward zero) matches Kotlin Int conversion and the parity tests' arithmetic.
    resolveTapTarget: (tree, x, y) =>
      JSON.parse(raw.resolveTapTarget(asTreeJson(tree), Math.trunc(x), Math.trunc(y))) as TrailblazeSelectorTapResolution,
    resolveSelector: (tree, selector) =>
      JSON.parse(raw.resolveSelector(asTreeJson(tree), JSON.stringify(selector))) as TrailblazeSelectorResolution,
  };
}

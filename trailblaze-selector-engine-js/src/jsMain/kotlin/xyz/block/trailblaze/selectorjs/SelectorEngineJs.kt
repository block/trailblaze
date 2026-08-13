@file:OptIn(ExperimentalJsExport::class)

package xyz.block.trailblaze.selectorjs

import xyz.block.trailblaze.api.TrailblazeSelectorAnalyzer

/**
 * `@JsExport` boundary over the daemon's selector engine for the interactive report's UI
 * Inspector. Pure forwarding: all logic — including the string-in/string-out JSON boundary —
 * lives in `TrailblazeSelectorAnalyzer` (commonMain of `:trailblaze-models`, compiled into
 * this bundle from the same source files the daemon runs on the JVM).
 *
 * Everything is JSON strings because `@JsExport` cannot cross data classes on a stable
 * surface. The TypeScript side types both directions with the generated bindings
 * (`sdks/typescript/src/generated/selectors.ts`) via the wrapper in
 * `src/typescript/selector-engine.ts`.
 */

/**
 * Ranked selector suggestions for the node with id [targetNodeId] in [treeJson]
 * (session-log `TrailblazeNode` JSON). Returns `TrailblazeSelectorAnalysis` JSON.
 */
@JsExport
fun computeSelectorAnalysis(treeJson: String, targetNodeId: String): String =
  TrailblazeSelectorAnalyzer.computeSelectorAnalysisJson(treeJson, targetNodeId)

/**
 * Resolves a tap at ([x], [y]) to its target node + best selector with round-trip
 * verification — the exact call the daemon's recorder makes on every recorded tap.
 * Returns `TrailblazeSelectorTapResolution` JSON.
 */
@JsExport
fun resolveTapTarget(treeJson: String, x: Int, y: Int): String =
  TrailblazeSelectorAnalyzer.resolveTapTargetJson(treeJson, x, y)

/**
 * Resolves an arbitrary selector ([selectorJson], `TrailblazeNodeSelector` JSON) against
 * the tree — the inspector's "test this selector". Returns `TrailblazeSelectorResolution` JSON.
 */
@JsExport
fun resolveSelector(treeJson: String, selectorJson: String): String =
  TrailblazeSelectorAnalyzer.resolveSelectorJson(treeJson, selectorJson)

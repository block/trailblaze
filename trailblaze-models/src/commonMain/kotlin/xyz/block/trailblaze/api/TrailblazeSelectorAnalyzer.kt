package xyz.block.trailblaze.api

import kotlinx.serialization.json.Json

/**
 * Selector analysis for a captured [TrailblazeNode] tree: ranked selector suggestions with
 * match counts and tap verification for any node, tap-coordinate resolution, and ad-hoc
 * selector resolution. This is the engine behind the interactive report's UI Inspector —
 * compiled to JavaScript by `:trailblaze-selector-engine-js` — and it is deliberately the
 * exact logic the daemon records with ([TrailblazeNodeSelectorGenerator] /
 * [TrailblazeNodeSelectorResolver]), so what the inspector suggests is what the recorder
 * writes.
 *
 * The `*Json` entry points are the string-in/string-out boundary shared verbatim by every
 * consumer: the JVM golden test and the Kotlin/JS `@JsExport` shim both call them, which is
 * what lets the cross-platform parity fixtures byte-compare their outputs. Input trees are
 * session-log `TrailblazeNode` JSON (polymorphic `driverDetail` under the `"class"`
 * discriminator — `TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR`); outputs are the
 * [TrailblazeSelectorAnalysis] family, typed on the TS side by the generated
 * `selectors.ts` bindings.
 *
 * The resolve-and-verify semantics mirror `InspectTrailblazeNodeSelectorHelper` in
 * `:trailblaze-ui` (the Kotlin/Wasm inspector): beyond match counting, each option's
 * resolved center is **hit-tested** against the tree so an overlapping child that would
 * intercept the tap is caught ([TrailblazeNode.hitTest]).
 */
internal object TrailblazeSelectorAnalyzer {

  /** Decodes session-log `TrailblazeNode` JSON: `"class"` discriminator, lenient, unknown-tolerant. */
  private val wireJson = Json {
    classDiscriminator = "class"
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Output encoding matching the generated TS types: non-nullable fields always present
   * (required in TS), nullable fields absent when null (optional in TS). See the contract
   * note in `TrailblazeSelectorAnalysis.kt`.
   */
  private val outJson = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  // --- Typed core ---

  /** Ranked selector options for [target], mirroring the Wasm inspector's analysis surface. */
  fun analyze(root: TrailblazeNode, target: TrailblazeNode): TrailblazeSelectorAnalysis {
    val namedSelectors = TrailblazeNodeSelectorGenerator.findAllValidSelectors(
      root = root,
      target = target,
      maxResults = 8,
    )
    val structural = TrailblazeNodeSelectorGenerator.findBestStructuralSelector(
      root = root,
      target = target,
    )

    val options = namedSelectors.map { named ->
      resolveAndVerify(root, named.selector, target)
        .copy(strategy = named.strategy, isBest = named.isBest)
    } + resolveAndVerify(root, structural.selector, target)
      // Structural is an alternative, never the "best" default — mirrors the Wasm inspector.
      .copy(strategy = structural.strategy, isBest = false)

    return TrailblazeSelectorAnalysis(options = options)
  }

  /** The recorder's tap resolution ([TrailblazeNodeSelectorGenerator.resolveFromTap]) as a DTO. */
  fun resolveTap(root: TrailblazeNode, x: Int, y: Int): TrailblazeSelectorTapResolution {
    val resolution = TrailblazeNodeSelectorGenerator.resolveFromTap(root, x, y)
      ?: return TrailblazeSelectorTapResolution(error = "no node at ($x, $y)")
    return TrailblazeSelectorTapResolution(
      targetNodeId = resolution.targetNode.nodeId,
      selector = resolution.selector,
      resolvedCenterX = resolution.resolvedCenter?.first,
      resolvedCenterY = resolution.resolvedCenter?.second,
      roundTripValid = resolution.roundTripValid,
    )
  }

  /** Resolves an arbitrary [selector] against the tree — the inspector's "test this selector". */
  fun resolveSelector(root: TrailblazeNode, selector: TrailblazeNodeSelector): TrailblazeSelectorResolution {
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    val matchingIds = when (result) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node.nodeId)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes.map { it.nodeId }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
    }
    val resolvedCenter = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    return TrailblazeSelectorResolution(
      matchCount = matchingIds.size,
      matchingNodeIds = matchingIds,
      resolvedCenterX = resolvedCenter?.first,
      resolvedCenterY = resolvedCenter?.second,
    )
  }

  /**
   * Resolves [selector] and verifies it would tap [target]: resolve, take the tap-time center,
   * then hit-test that center to find the frontmost node — an overlapping child that would
   * intercept the tap makes `hitsTarget` false even when the selector uniquely matches.
   */
  private fun resolveAndVerify(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TrailblazeNode,
  ): TrailblazeSelectorOption {
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    val matchingIds = when (result) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node.nodeId)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes.map { it.nodeId }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch ->
        return TrailblazeSelectorOption(selector = selector)
    }

    val resolvedCenter = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    val hitsTarget = resolvedCenter?.let { (x, y) -> root.hitTest(x, y)?.nodeId == target.nodeId } ?: false

    return TrailblazeSelectorOption(
      selector = selector,
      matchCount = matchingIds.size,
      matchingNodeIds = matchingIds,
      resolvedCenterX = resolvedCenter?.first,
      resolvedCenterY = resolvedCenter?.second,
      hitsTarget = hitsTarget,
    )
  }

  // --- String-in/string-out boundary (shared by the JVM tests and the Kotlin/JS shim) ---

  /** [analyze] over JSON: tree JSON + node id in, [TrailblazeSelectorAnalysis] JSON out. */
  fun computeSelectorAnalysisJson(treeJson: String, targetNodeId: String): String {
    return try {
      val root = wireJson.decodeFromString(TrailblazeNode.serializer(), treeJson)
      val nodeId = targetNodeId.toLong()
      val target = root.aggregate().find { it.nodeId == nodeId }
        ?: return outJson.encodeToString(
          TrailblazeSelectorAnalysis.serializer(),
          TrailblazeSelectorAnalysis(error = "node $targetNodeId not found in tree"),
        )
      outJson.encodeToString(TrailblazeSelectorAnalysis.serializer(), analyze(root, target))
    } catch (e: Throwable) {
      outJson.encodeToString(
        TrailblazeSelectorAnalysis.serializer(),
        TrailblazeSelectorAnalysis(error = e.message ?: e.toString()),
      )
    }
  }

  /** [resolveTap] over JSON: tree JSON + coordinates in, [TrailblazeSelectorTapResolution] JSON out. */
  fun resolveTapTargetJson(treeJson: String, x: Int, y: Int): String {
    return try {
      val root = wireJson.decodeFromString(TrailblazeNode.serializer(), treeJson)
      outJson.encodeToString(TrailblazeSelectorTapResolution.serializer(), resolveTap(root, x, y))
    } catch (e: Throwable) {
      outJson.encodeToString(
        TrailblazeSelectorTapResolution.serializer(),
        TrailblazeSelectorTapResolution(error = e.message ?: e.toString()),
      )
    }
  }

  /** [resolveSelector] over JSON: tree JSON + selector JSON in, [TrailblazeSelectorResolution] JSON out. */
  fun resolveSelectorJson(treeJson: String, selectorJson: String): String {
    return try {
      val root = wireJson.decodeFromString(TrailblazeNode.serializer(), treeJson)
      val selector = wireJson.decodeFromString(TrailblazeNodeSelector.serializer(), selectorJson)
      outJson.encodeToString(TrailblazeSelectorResolution.serializer(), resolveSelector(root, selector))
    } catch (e: Throwable) {
      outJson.encodeToString(
        TrailblazeSelectorResolution.serializer(),
        TrailblazeSelectorResolution(error = e.message ?: e.toString()),
      )
    }
  }
}

package xyz.block.trailblaze.ui

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.util.Console

private val selectorJson = Json {
  prettyPrint = true
  encodeDefaults = false
}

/**
 * Multiplatform helper for computing TrailblazeNode selector options for a given node.
 * Uses [TrailblazeNodeSelectorGenerator] to find all valid selectors and serializes them.
 *
 * All dependencies are in commonMain, so this works on JVM, Android, and WASM.
 * Uses JSON serialization (universally available) for the selector display.
 * On JVM, the caller can optionally supply a YAML serializer for richer output.
 */
object InspectTrailblazeNodeSelectorHelper {

  /**
   * Full resolution result for a selector: match count, matching node IDs,
   * the resolved center point, and whether that center would actually tap the target.
   */
  private data class SelectorResolutionInfo(
    val matchCount: Int,
    val matchingNodeIds: List<Long>,
    val resolvedCenter: Pair<Int, Int>?,
    val hitsTarget: Boolean,
  )

  /**
   * Resolves a selector against the tree and verifies it would tap the correct element.
   *
   * This goes beyond a simple bounds check: it performs a full **hit test** at the
   * resolved coordinates to find the frontmost (deepest/smallest) node at that point.
   * If a child element overlaps the resolved center, the hit test catches it — the
   * child would intercept the tap, not the target.
   *
   * This gives true confidence that a selector would tap what you expect at playback.
   */
  private fun resolveAndVerify(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TrailblazeNode,
  ): SelectorResolutionInfo {
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    val (matchCount, matchingIds) = when (result) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch ->
        1 to listOf(result.node.nodeId)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches ->
        result.nodes.size to result.nodes.map { it.nodeId }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch ->
        return SelectorResolutionInfo(0, emptyList(), null, false)
    }

    // Get the center point that would be used at tap time
    val resolvedCenter = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
      ?: return SelectorResolutionInfo(matchCount, matchingIds, null, false)

    // Hit test: find the frontmost node at the resolved coordinates.
    // This catches cases where a child element overlaps the center point and
    // would intercept the tap instead of the target.
    val frontmostNode = root.hitTest(resolvedCenter.first, resolvedCenter.second)
    val hitsTarget = frontmostNode?.nodeId == target.nodeId

    return SelectorResolutionInfo(matchCount, matchingIds, resolvedCenter, hitsTarget)
  }

  /**
   * Creates a function that computes selector analysis for a given target [TrailblazeNode].
   *
   * @param root The root of the TrailblazeNode tree
   * @param selectorSerializer Optional custom serializer (e.g., YAML on JVM). Falls back to JSON.
   * @return A function that computes [TrailblazeNodeSelectorAnalysisResult] for a target node
   */
  fun createSelectorComputeFunction(
    root: TrailblazeNode,
    selectorSerializer: ((TrailblazeNodeSelector) -> String)? = null,
  ): (TrailblazeNode) -> TrailblazeNodeSelectorAnalysisResult {
    val serialize =
      selectorSerializer
        ?: { selector ->
          selectorJson.encodeToString(TrailblazeNodeSelector.serializer(), selector)
        }

    return lambda@{ targetNode ->
      try {
        // Find the target node in the root hierarchy by nodeId
        val targetInRoot = root.aggregate().find { it.nodeId == targetNode.nodeId }

        if (targetInRoot == null) {
          return@lambda TrailblazeNodeSelectorAnalysisResult(emptyList(), null)
        }

        // Find all valid content-based selectors with strategy names
        val namedSelectors =
          TrailblazeNodeSelectorGenerator.findAllValidSelectors(
            root = root,
            target = targetInRoot,
            maxResults = 8,
          )

        // Also find the best structural selector (content-free)
        val structuralSelector =
          TrailblazeNodeSelectorGenerator.findBestStructuralSelector(
            root = root,
            target = targetInRoot,
          )

        // Serialize each selector, resolve against the tree, and verify tap coordinates
        val selectorOptions =
          namedSelectors.map { named ->
            val info = resolveAndVerify(root, named.selector, targetInRoot)
            TrailblazeNodeSelectorOptionDisplay(
              yamlSelector = serialize(named.selector),
              strategy = named.strategy,
              isBest = named.isBest,
              matchCount = info.matchCount,
              matchingNodeIds = info.matchingNodeIds,
              resolvedCenter = info.resolvedCenter,
              hitsTarget = info.hitsTarget,
            )
          }

        // Add structural selector (always included as a separate option)
        val structInfo = resolveAndVerify(root, structuralSelector.selector, targetInRoot)
        val allOptions =
          selectorOptions +
            TrailblazeNodeSelectorOptionDisplay(
              yamlSelector = serialize(structuralSelector.selector),
              strategy = structuralSelector.strategy,
              isBest = false, // Structural is an alternative, not the "best" default
              matchCount = structInfo.matchCount,
              matchingNodeIds = structInfo.matchingNodeIds,
              resolvedCenter = structInfo.resolvedCenter,
              hitsTarget = structInfo.hitsTarget,
            )

        val bestYaml = allOptions.firstOrNull { it.isBest }?.yamlSelector

        TrailblazeNodeSelectorAnalysisResult(
          selectorOptions = allOptions,
          bestSelectorYaml = bestYaml,
        )
      } catch (e: Exception) {
        Console.log(
          "Error computing TrailblazeNode selectors for node ${targetNode.nodeId}: ${e.message}"
        )
        e.printStackTrace()
        TrailblazeNodeSelectorAnalysisResult(emptyList(), null)
      }
    }
  }
}

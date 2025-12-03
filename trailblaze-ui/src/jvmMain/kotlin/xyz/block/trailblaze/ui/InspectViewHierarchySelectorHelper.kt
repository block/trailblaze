package xyz.block.trailblaze.ui

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.TapSelectorV2
import xyz.block.trailblaze.viewmatcher.models.PropertyUniqueness
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * JVM-specific helper for computing selector options for a given node.
 * This uses TapSelectorV2 which is only available in the trailblaze-common module.
 */
object InspectViewHierarchySelectorHelper {

  /**
   * Creates a function that computes selector analysis (options + uniqueness) for a given target node.
   *
   * @param root The root of the view hierarchy (unfiltered original)
   * @param deviceWidth Device width in pixels
   * @param deviceHeight Device height in pixels
   * @param platform The device platform (defaults to ANDROID)
   * @return A function that computes selector analysis for a target node
   */
  fun createSelectorComputeFunction(
    root: ViewHierarchyTreeNode,
    deviceWidth: Int,
    deviceHeight: Int,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  ): (ViewHierarchyTreeNode) -> SelectorAnalysisResult {
    return lambda@ { targetNode ->
      try {
        // IMPORTANT: Find the target node in the root hierarchy by nodeId
        // The UI may pass a node from the filtered hierarchy, but we need to use
        // the node from the unfiltered root hierarchy for selector computation to work correctly
        val targetInRoot = root.aggregate().find { it.nodeId == targetNode.nodeId }

        if (targetInRoot == null) {
          return@lambda SelectorAnalysisResult(emptyList(), null)
        }

        // Compute property uniqueness (fast - single pass through hierarchy)
        val uniqueness = PropertyUniqueness.analyzePropertyUniqueness(
          target = targetInRoot,
          root = root,
        )

        val uniquenessDisplay = PropertyUniquenessDisplay(
          text = uniqueness.text,
          textIsUnique = uniqueness.textIsUnique,
          textOccurrences = uniqueness.textOccurrences,
          textMatchingNodeIds = uniqueness.textMatchingNodeIds,
          id = uniqueness.id,
          idIsUnique = uniqueness.idIsUnique,
          idOccurrences = uniqueness.idOccurrences,
          idMatchingNodeIds = uniqueness.idMatchingNodeIds,
        )

        // Compute the "best" selector that would be used in production
        val bestSelector = TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode(
          root = root,
          target = targetInRoot,
          trailblazeDevicePlatform = platform,
          widthPixels = deviceWidth,
          heightPixels = deviceHeight,
          spatialHints = null,
        )

        // Use UI-optimized selector search for interactive performance
        // This skips expensive strategies and simplified variants for speed
        val selectorOptions = TapSelectorV2.findValidSelectorsForUI(
          root = root,
          target = targetInRoot, // Use the node from root hierarchy
          trailblazeDevicePlatform = platform,
          widthPixels = deviceWidth,
          heightPixels = deviceHeight,
          spatialHints = null, // We don't have LLM hints in the UI inspector
          maxResults = 5, // Limit results to prevent excessive computation
        )

        // Convert to display format with YAML and mark which one is the "best"
        val selectorOptionsDisplay = selectorOptions.map { option ->
          val yamlString = TrailblazeYaml.defaultYamlInstance.encodeToString(
            xyz.block.trailblaze.api.TrailblazeElementSelector.serializer(),
            option.selector
          )

          SelectorOptionDisplay(
            yamlSelector = yamlString,
            strategy = option.strategy,
            isSimplified = option.isSimplified,
            isBest = option.selector == bestSelector // Mark if this matches the production default
          )
        }

        SelectorAnalysisResult(
          selectorOptions = selectorOptionsDisplay,
          propertyUniqueness = uniquenessDisplay,
        )
      } catch (e: Exception) {
        // If anything goes wrong, return empty result
        println("Error computing selectors for node ${targetNode.nodeId}: ${e.message}")
        e.printStackTrace()
        SelectorAnalysisResult(emptyList(), null)
      }
    }
  }
}

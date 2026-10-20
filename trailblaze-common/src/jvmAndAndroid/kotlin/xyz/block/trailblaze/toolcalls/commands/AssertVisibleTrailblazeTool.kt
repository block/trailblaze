package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.describe
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2.findBestTrailblazeElementSelectorForTargetNodeWithStrategy

/**
 * Asserts an element is visible by its stable hash ref from the snapshot output.
 *
 * Mirrors [TapTrailblazeTool] — the ref (e.g. `y778`) is the same content-hashed
 * id the user sees in compact snapshot output. Resolution goes through the
 * pre-applied [TrailblazeNode.ref] field, then delegates to
 * [AssertVisibleBySelectorTrailblazeTool] which handles the node-selector-vs-Maestro
 * dispatch mode switching internally.
 *
 * Not recordable, like [TapTrailblazeTool]: a ref only means something on the screen that produced
 * it, so a trail must record the [AssertVisibleBySelectorTrailblazeTool] this expands into. When a
 * direct `trailblaze tool assertVisible ref=…` call's top-level log was recordable it won the
 * recorder's top-level filter, the saved trail held `assertVisible: {ref: …}`, and the replay
 * failed with "Element ref not found".
 */
@Serializable
@TrailblazeToolClass("assertVisible", isVerification = true, isRecordable = false)
@LLMDescription(
  "Assert an element is visible by its snapshot ref (e.g. y778 from [y778] \"Network & internet\"). " +
    "Checks the current snapshot only; it does not wait for an element to appear. The element's " +
    "text is checked too, so assert the element that shows the value you are verifying, and not " +
    "one whose text changes from run to run (a timestamp, an order number): replay would fail on " +
    "it, so assert a stable label beside it instead.",
)
data class AssertVisibleTrailblazeTool(
  @param:LLMDescription("Element ref from the snapshot, e.g. 'y778'.")
  val ref: String,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val screenState = executionContext.screenState
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: No screen state available",
        tool = this,
      )

    val tree = screenState.trailblazeNodeTree
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: No element tree available. Use 'snapshot' first.",
        tool = this,
      )

    // Find the node by its pre-applied ref field (set during compact element list generation).
    // Same lookup pattern as TapTrailblazeTool — do NOT recompute hashes via ElementRef.resolveRef
    // here: its traversal doesn't skip offscreen/dedup the same way and will mismatch on iOS.
    val targetNode = tree.findFirst { it.ref == ref }
      ?: throw TrailblazeToolExecutionException(
        // See TapTrailblazeTool for why we dropped the "use 'snapshot'" pointer.
        message = "assertVisible: Element ref '$ref' not found on current screen. " +
          "The screen has changed since this ref was last visible. " +
          "Use a ref from the current view hierarchy instead.",
        tool = this,
      )

    val center = targetNode.centerPoint()
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: Element ref '$ref' found but has no bounds.",
        tool = this,
      )

    Console.log(
      "### assertVisible: Resolved '$ref' → ${targetNode.describe()} at (${center.first}, ${center.second})",
    )

    // The node the assert records. A ref that shows text of its own records itself: the assertion
    // is about what it says, and the hit-test at its center can settle on a child label instead (a
    // cell's "Model Name" inside "Model Name, iPhone 17"). A ref with no text of its own records
    // what a touch at its center lands on, like `tap` ([selectorSourceNode]), unless that shows no
    // text either: then the label inside it, which is the text the ref was shown with. A direct
    // child's label comes first, as the snapshot shows it, so a nested badge isn't picked over it.
    // A label showing a remembered secret is never picked: its selector would spell the secret.
    val sourceNode by lazy {
      if (TrailblazeNodeSelectorGenerator.showsText(targetNode)) return@lazy targetNode
      val touched = selectorSourceNode(tree, targetNode, center.first, center.second)
      if (TrailblazeNodeSelectorGenerator.showsText(touched)) return@lazy touched
      val secrets = executionContext.memory.sensitiveValues()
      val safeLabel = { node: TrailblazeNode ->
        TrailblazeNodeSelectorGenerator.showsText(node) && !node.showsAny(secrets)
      }
      targetNode.children.firstOrNull(safeLabel)
        ?: targetNode.findFirst { it !== targetNode && safeLabel(it) }
        ?: touched
    }
    // Every recorded selector also requires the text of the element it matches
    // ([TrailblazeNodeSelectorGenerator.withMatchedElementText]): the minimizer keeps only what
    // uniqueness needs, so without this an element with a unique id would assert presence only.
    // Lazy because the ANDROID Maestro path below records a TapSelectorV2 selector and never reads it.
    val recordedSelector by lazy {
      tree.withMatchedElementTextUnlessSensitive(
        TrailblazeNodeSelectorGenerator.findBestSelector(tree, sourceNode),
        executionContext.memory,
      )
    }

    // Accessibility-driver path: emit nodeSelector-only, mirroring [TapTrailblazeTool]'s
    // forward-only recording shape (see that file for the full rationale, including why
    // [DriverNodeDetail.AndroidView] joins it here). Without the AndroidView case, an
    // in-process View tree falls to the view-hierarchy lookup below and throws — there is no
    // Maestro [ViewHierarchyTreeNode] to map the ref onto.
    if (tree.driverDetail is DriverNodeDetail.AndroidAccessibility ||
      tree.driverDetail is DriverNodeDetail.AndroidView
    ) {
      val accessibilityNodeSelector = recordedSelector
      return listOf(
        AssertVisibleBySelectorTrailblazeTool(
          reason = reasoning,
          nodeSelector = accessibilityNodeSelector,
        ),
      )
    }

    // Generate a rich TrailblazeNodeSelector where possible; AssertVisibleBySelectorTrailblazeTool
    // dispatches via nodeSelector or a Maestro-lowered projection of it based on NodeSelectorMode
    // internally. For a ref with no text of its own, [selectorSourceNode] resolves the frontmost
    // node at the coordinates that the ref could actually have meant — the same round-trip validation as
    // TapTrailblazeTool (see TrailblazeNode.hitTest for tiebreaker logic, and SelectorSourceNode
    // for the occlusion case it overrides). It always answers, because a hit-test at the ref's
    // own center cannot miss: bounds contain their own center point. The `?: null` that used to
    // guard this and defer to the TapSelectorV2 projection below was therefore already dead.
    // Skipped on ANDROID: recordedNodeSelectorForMaestroPath below always records the
    // TapSelectorV2-derived selector there, so the modern generation would be dead work.
    val nodeSelector = if (screenState.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      null
    } else {
      try {
        recordedSelector
      } catch (e: Exception) {
        Console.log(
          "WARNING: TrailblazeNodeSelector generation failed, falling back to legacy selector: ${e.message}",
        )
        null
      }
    }

    // AssertVisibleBySelectorTrailblazeTool no longer carries the legacy TrailblazeElementSelector
    // field, so TapSelectorV2's output is converted to nodeSelector shape before storage; replay
    // lowers it back to a Maestro selector via `lowerToMaestroSelector`. The selector-source
    // choice lives in [recordedNodeSelectorForMaestroPath] (shared with TapTrailblazeTool) —
    // an assert recorded against a container-shaped modern selector would pass vacuously. The
    // view-hierarchy lookup and TapSelectorV2 only run when that selector is recorded, so neither
    // can fail an assert that records the modern selector.
    return listOf(
      AssertVisibleBySelectorTrailblazeTool(
        reason = reasoning,
        nodeSelector = recordedNodeSelectorForMaestroPath(
          platform = screenState.trailblazeDevicePlatform,
          modernNodeSelector = nodeSelector,
          legacyAsNodeSelector = {
            val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) { node ->
              node.centerPoint?.let {
                val (cx, cy) = it.split(",").map { s -> s.toInt() }
                cx == center.first && cy == center.second
              } ?: false
            } ?: throw TrailblazeToolExecutionException(
              message = "assertVisible: Could not map ref '$ref' to a view-hierarchy node for selector generation.",
              tool = this,
            )
            findBestTrailblazeElementSelectorForTargetNodeWithStrategy(
              root = screenState.viewHierarchy,
              target = matchingNode,
              trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
              widthPixels = screenState.deviceWidth,
              heightPixels = screenState.deviceHeight,
              spatialHints = null,
            ).selector.toTrailblazeNodeSelector(screenState.trailblazeDevicePlatform)
              .let { tree.withMatchedElementTextUnlessSensitive(it, executionContext.memory) }
          },
        ),
      ),
    )
  }
}

/**
 * [selector] with the text of the element it matches pinned
 * ([TrailblazeNodeSelectorGenerator.withMatchedElementText]), unless that element shows a value the
 * trail remembered as sensitive. Then it is left as is, like a password field: a recording is a
 * file anyone can read, and the resolved selector also reaches logs and tool results.
 */
private fun TrailblazeNode.withMatchedElementTextUnlessSensitive(
  selector: TrailblazeNodeSelector,
  memory: AgentMemory,
): TrailblazeNodeSelector {
  val secrets = memory.sensitiveValues()
  if (secrets.isNotEmpty()) {
    val matched = (TrailblazeNodeSelectorResolver.resolve(this, selector) as? TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
      ?.node ?: return selector
    if (matched.showsAny(secrets)) return selector
  }
  return TrailblazeNodeSelectorGenerator.withMatchedElementText(this, selector)
}

/**
 * Every value the trail remembered as sensitive, including an arg that copied one: the arg keeps
 * its copy after the memory key it came from is deleted.
 */
private fun AgentMemory.sensitiveValues(): List<String> {
  val keys = sensitiveKeys.mapNotNull { variables[it] }
  val args = sensitiveArgNames.flatMap { name -> args[name]?.scalars().orEmpty() }
  return (keys + args).filter { it.isNotEmpty() }
}

/** Every string, number or boolean inside [this], so an object arg's fields are checked one by one. */
private fun JsonElement.scalars(): List<String> = when (this) {
  is JsonPrimitive -> listOfNotNull(contentOrNull)
  is JsonArray -> flatMap { it.scalars() }
  is JsonObject -> values.flatMap { it.scalars() }
}

private fun TrailblazeNode.showsAny(secrets: List<String>): Boolean =
  secrets.isNotEmpty() && driverDetail.textCandidates().any { text -> secrets.any { it in text } }

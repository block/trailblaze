package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
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
  "Assert an element is visible on screen by its ref ID from the snapshot. Use the " +
    "short hash ref shown in square brackets (e.g., y778 from [y778] \"Network & internet\"). " +
    "These refs are stable across captures of the same screen. Optionally pass " +
    "`expectedText` to also verify the element's rendered text — use it whenever the case " +
    "asks to verify a specific value (e.g. \"verify the checkout button shows \$5.00\", " +
    "\"expect status to be Active\") instead of just confirming the element exists.",
)
data class AssertVisibleTrailblazeTool(
  @param:LLMDescription("The element ref from the snapshot (e.g., 'y778')")
  val ref: String,
  @param:LLMDescription(
    "Optional. When set, asserts the resolved element's rendered text equals this value " +
      "after whitespace trimming (case-sensitive). Pass the stable rendered text verbatim — " +
      "e.g. \"Charge \$5.00\", not \"the checkout button\". Exclude volatile state that " +
      "changes run-to-run (live item counts like \"3 items\", timestamps, quantities); pin " +
      "only the part that stays constant. Leave null when only the element's presence matters.",
  )
  val expectedText: String? = null,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    // Strip volatile state (e.g. live item counts) out of the captured expectedText before it
    // becomes a strict EXACT pin. A captured "Review sale\n3 items" would fail replay whenever
    // the live count differs; emitting the stable head with PREFIX keeps the value-pin without
    // the brittleness. No volatile token → forward verbatim as EXACT (byte-identical to before).
    val resolvedText = VolatileTextDetector.resolve(expectedText)

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

    // The selector the assert records. A presence check describes what a touch at the
    // ref's center lands on, like `tap` ([selectorSourceNode]). A text check is a claim about
    // THIS ref's text, so it records the ref's own node: the hit-test can settle on a child label
    // under the center (a cell's "Model Name" inside "Model Name, iPhone 17") and the text check
    // would then read the child. Lazy because the ANDROID Maestro path below records a
    // TapSelectorV2 selector and never reads it.
    //
    // A text check also skips selectors that pick the ref out by `containsChild` /
    // `containsDescendants`: the text post-pass reads those children instead of the node itself
    // (that is what a hand-written one means), so a row whose own text repeats, told apart by a
    // child, would be checked against the child. When every unique selector needs a child
    // anchor, it records the best one anyway and the failure lists the fields it compared.
    val recordedSelector by lazy {
      if (resolvedText.expectedText == null) {
        TrailblazeNodeSelectorGenerator.findBestSelector(
          tree,
          selectorSourceNode(tree, targetNode, center.first, center.second),
        )
      } else {
        TrailblazeNodeSelectorGenerator.findAllValidSelectors(tree, targetNode, maxResults = Int.MAX_VALUE)
          .map { it.selector }
          .firstOrNull { it.containsChild == null && it.containsDescendants.isNullOrEmpty() }
          ?: TrailblazeNodeSelectorGenerator.findBestSelector(tree, targetNode)
      }
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
          expectedText = resolvedText.expectedText,
          textMatchMode = resolvedText.mode,
        ),
      )
    }

    // Generate a rich TrailblazeNodeSelector where possible; AssertVisibleBySelectorTrailblazeTool
    // dispatches via nodeSelector or a Maestro-lowered projection of it based on NodeSelectorMode
    // internally. For a presence check, [selectorSourceNode] resolves the frontmost node at the
    // coordinates that the ref could actually have meant — the same round-trip validation as
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
          },
        ),
        expectedText = resolvedText.expectedText,
        textMatchMode = resolvedText.mode,
      ),
    )
  }
}

package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.describe
import xyz.block.trailblaze.util.Console

/**
 * Picks the node a recorded selector describes for a ref-based tool (`tap`, `assertVisible`)
 * acting at ([x], [y]) inside [target], the node the ref resolved to.
 *
 * The whole-tree [TrailblazeNode.hitTest] is the default source: it climbs from the leaf under
 * the point to the control that owns it, so a ref on a label records the clickable wrapper the
 * OS routes the touch to. It knows nothing about z-order, though. When a screen keeps an
 * occluded layer in the tree (a checkout bar left underneath the cart sheet that replaced it),
 * the smaller occluded label wins the whole-tree contest and the recording describes an element
 * the user never saw; replay then taps the wrong thing.
 *
 * A whole-tree winner that shares no ancestor chain with [target] is exactly that case (or a
 * sibling overlapping the target, which the step did not mean either). The point is then
 * re-resolved with [TrailblazeNode.hitTestWithin], which confines the frontmost-element contest
 * to the target's own subtree while still climbing the target's real ancestors — so the
 * occluded layer loses the point and a ref on a label still records its clickable wrapper.
 *
 * A winner that *is* on the target's chain — an ancestor the climb reached, or a descendant
 * under the point — is kept untouched. That keeps this guard to the one case it is about:
 * overlapping nodes the ref has no relationship to.
 */
internal fun selectorSourceNode(
  tree: TrailblazeNode,
  target: TrailblazeNode,
  x: Int,
  y: Int,
): TrailblazeNode {
  val wholeTreeHit = tree.hitTest(x, y) ?: return target
  val onTargetChain = wholeTreeHit === target ||
    target.findFirst { it === wholeTreeHit } != null ||
    wholeTreeHit.findFirst { it === target } != null
  if (onTargetChain) return wholeTreeHit
  val scopedHit = tree.hitTestWithin(target, x, y) ?: target
  // [tap-divergence] log: the same tag the tool-level divergence log uses, because this is the
  // other way a recorded selector stops describing what the step named. Without it the
  // occlusion is invisible — the tool-level log only fires when the recorded node's ref
  // differs from the target's, and the node this picks usually IS the target.
  Console.log(
    "[tap-divergence] occluded hit discarded at ($x,$y): " +
      "off-chain=${wholeTreeHit.describe()} (ref=${wholeTreeHit.ref}) " +
      "target=${target.describe()} (ref=${target.ref}) " +
      "recording=${scopedHit.describe()}",
  )
  return scopedHit
}

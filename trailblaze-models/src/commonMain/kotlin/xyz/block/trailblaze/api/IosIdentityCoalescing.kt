package xyz.block.trailblaze.api

/**
 * iOS splits a control's *interactivity* from its *identity* far more often than Android.
 *
 * The canonical case is an overflow button: a clickable `ButtonView` (whose `accessibilityText`
 * is a VoiceOver label like "More") wraps a single non-interactive `UIImageView` child that
 * carries the app's `accessibilityIdentifier` — surfaced here as
 * [DriverNodeDetail.IosMaestro.resourceId], e.g. `ellipsis-horizontal`. The tappable node has no
 * id of its own, and the id-bearing node isn't clickable, so neither node alone is a usable,
 * identified, tappable element:
 *  - the compact view renders the button as an unlabeled/label-only line with no `[id=…]`, and
 *  - the id-only icon child is (correctly) gated out of the lean default view.
 *
 * [effectiveIosResourceId] coalesces the two: it returns the stable identifier to *attribute to*
 * a node so the compact list, ref hashing, and selector generation all agree on one identity.
 *
 * Returns, in order:
 *  1. the node's OWN `resourceId` when present;
 *  2. for a **clickable** control with no `resourceId` of its own, the `resourceId` of a **lone**
 *     identifying descendant reached without crossing another clickable boundary (a nested
 *     clickable node owns its own subtree, so we stop there); or
 *  3. `null`.
 *
 * Only clickable controls hoist, so this enriches lines that already render in the compact view —
 * it never surfaces new elements, leaving the lean default view (and the `ALL_ELEMENTS` gate for
 * id-only, non-interactive leaves) unchanged. When more than one distinct descendant id is found
 * the result is ambiguous and this returns `null` rather than guess.
 */
internal fun effectiveIosResourceId(node: TrailblazeNode): String? {
  val detail = node.driverDetail as? DriverNodeDetail.IosMaestro ?: return null
  detail.resourceId?.takeIf { it.isNotBlank() }?.let { return it }
  if (!detail.clickable) return null
  return hoistLoneDescendantResourceId(node)
}

/**
 * Collects the `resourceId`s of [node]'s descendants without descending past another clickable
 * control (which owns its own identity), and returns the single distinct id if there is exactly
 * one. More than one → ambiguous → `null`.
 */
private fun hoistLoneDescendantResourceId(node: TrailblazeNode): String? {
  val found = LinkedHashSet<String>()
  fun visit(current: TrailblazeNode, isRoot: Boolean) {
    val detail = current.driverDetail as? DriverNodeDetail.IosMaestro ?: return
    if (!isRoot && detail.clickable) return
    if (!isRoot) detail.resourceId?.takeIf { it.isNotBlank() }?.let { found.add(it) }
    for (child in current.children) visit(child, isRoot = false)
  }
  visit(node, isRoot = true)
  return found.singleOrNull()
}

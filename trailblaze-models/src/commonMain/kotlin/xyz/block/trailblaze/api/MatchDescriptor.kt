package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

/**
 * Lightweight identity + position record describing one match returned by the
 * `findMatches` tool.
 *
 * Designed for scripted-tool authors who want to ask "is this element visible?",
 * "is the selector unambiguous?", and "where is the match on screen?" without
 * pulling the entire view subtree across the wire. The descriptor intentionally
 * omits any reference to the matched node's children — carrying the subtree
 * would defeat the snapshot-caching ROI, and opaque node handles would leak
 * driver implementation details into the typed authoring surface.
 *
 * ## Bounds reuse
 *
 * Reuses [TrailblazeNode.Bounds] (`left` / `top` / `right` / `bottom` with
 * computed `width` / `height` / `centerX` / `centerY`) rather than introducing
 * a separate `Rect` type — every selector resolution path already speaks this
 * shape, and the resolver hands back `TrailblazeNode` instances whose `bounds`
 * field is the same type.
 *
 * ## Cross-driver field semantics
 *
 * - [matchedText] is the matched node's best-available text — per-driver
 *   `resolveText()` for Android/iOS/Compose, `ariaName` for Web. Null when the
 *   driver detail carried no text-shaped property.
 * - [accessibilityId] is the accessibility-label / content-description / aria-
 *   descriptor on the matched node, when the driver exposes one.
 * - [resourceId] is the Android `resourceId` (or its iOS / Compose / Web
 *   analogue: `accessibilityIdentifier`, Compose `testTag`, web `data-testid`)
 *   when the driver exposes one.
 *
 * Drivers that don't expose a given property leave the corresponding field
 * null — scripted authors should not assume any field is populated.
 */
@Serializable
data class MatchDescriptor(
  /**
   * Child-index path from the hierarchy root to this match.
   *
   * `[]` is the root, `[0, 2, 1, 4]` means "child 0 of root → child 2 → child 1
   * → child 4." Lets a caller re-identify a specific match against **the same
   * captured tree** without re-running the selector.
   *
   * ## Lifetime — frame-scoped, not durable
   *
   * The path is positional, so it is only stable for the lifetime of one
   * captured view-hierarchy snapshot. Any change to the tree shape between
   * capture and use — siblings added or removed, a RecyclerView item recycled,
   * a parent node re-mounting after a state change — invalidates the path:
   * the same physical pixels are now reached by a different index sequence.
   *
   * Treat descriptors as "immediate hand-offs to act on in this tool body"
   * rather than long-lived references. Re-querying via [findMatches] is the
   * right pattern after any device-mutating action, even if the matched
   * element is logically the same. For longer-lived identity, prefer
   * [accessibilityId] / [resourceId] when the driver populates them.
   */
  val indexPath: List<Int>,
  /**
   * Bounding rectangle of the matched node, in device pixels. `null` when the
   * driver couldn't compute bounds for the node (some Playwright nodes from
   * the parsed ARIA tree lack DOM-bounds enrichment; some Compose nodes
   * before first layout, etc.). Callers must treat `null` as "no coordinates
   * available" rather than tapping the origin — defaulting an unknown bounds
   * to `(0, 0, 0, 0)` would let a scripted tool accidentally tap the
   * top-left corner of the screen.
   */
  val bounds: TrailblazeNode.Bounds? = null,
  /**
   * Best-available text on the matched node, when the driver exposes one.
   * Per-driver: `text ?: hintText ?: contentDescription` on Android,
   * `text ?: hintText ?: accessibilityText` on iOS Maestro, `ariaName` on Web,
   * etc. Null when the matched node carried no text-shaped property.
   */
  val matchedText: String? = null,
  /**
   * Accessibility label / content description on the matched node, when the
   * driver exposes one. Maps to `contentDescription` on Android accessibility,
   * `accessibilityText` on Android/iOS Maestro, `uniqueId` on iOS AXe,
   * `contentDescription` on Compose, `ariaDescriptor` on Web. Null otherwise.
   */
  val accessibilityId: String? = null,
  /**
   * Stable identifier on the matched node, when the driver exposes one. Maps
   * to `resourceId` on Android, `accessibilityIdentifier` on iOS, `testTag` on
   * Compose, `data-testid` on Web. Null otherwise.
   */
  val resourceId: String? = null,
)

/**
 * Build a [MatchDescriptor] from this node, paired with the [root] of the tree
 * the node was matched in. Used by `FindMatchesTrailblazeTool` and tests —
 * anywhere the resolver hands back a [TrailblazeNode] and the caller wants the
 * lightweight descriptor instead.
 *
 * Returns null when this node is not actually present in [root]'s subtree
 * (a programming error: the resolver wouldn't return a node from outside the
 * input tree). Callers should treat null as "skip this match" rather than
 * propagating an exception, since it indicates the snapshot moved between
 * resolution and descriptor construction.
 *
 * Cross-driver field extraction follows the per-driver kdoc on
 * [MatchDescriptor]'s fields — Android `text/hintText/contentDescription`
 * becomes `matchedText`, Compose `testTag` becomes `resourceId`, etc.
 *
 * Receiver-extension shape (vs a free-standing builder) follows the existing
 * convention for [TrailblazeNode] helpers — see also [describe], [withRefs],
 * [findFirst].
 */
fun TrailblazeNode.toMatchDescriptor(root: TrailblazeNode): MatchDescriptor? {
  val path = MatchDescriptorBuilder.indexPathOf(root, this) ?: return null
  val (matchedText, accessibilityId, resourceId) =
    MatchDescriptorBuilder.extractIdentity(this.driverDetail)
  // Preserve a null `bounds` rather than fabricating `(0, 0, 0, 0)` — some
  // drivers (Playwright parsed-ARIA before DOM enrichment, Compose pre-layout)
  // legitimately produce nodes without bounds, and a fake origin rectangle
  // would let a scripted tool tap the top-left corner thinking the element
  // was real.
  return MatchDescriptor(
    indexPath = path,
    bounds = this.bounds,
    matchedText = matchedText,
    accessibilityId = accessibilityId,
    resourceId = resourceId,
  )
}

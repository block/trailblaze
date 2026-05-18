package xyz.block.trailblaze.waypoint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

/**
 * Pins the fail-closed behavior for templated waypoints when the matcher is invoked
 * without a [TargetTemplateContext] (or with one that doesn't resolve a `{{target.appId}}`
 * placeholder).
 *
 * The motivating bug: a `forbidden` selector with an unresolved placeholder would silently
 * fail to match anything — the forbidden check would pass, and a waypoint that the author
 * intended to flag as "blocked" could be reported as matched. The matcher now detects any
 * survived placeholder after expansion and short-circuits the whole definition with
 * [WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE], so missing target context
 * surfaces loudly instead of as a false-positive match.
 */
class WaypointMatcherTest {

  @Test
  fun `templated required selector with null target skips with UNRESOLVED_TARGET_TEMPLATE`() {
    val def = waypoint(
      id = "required-template",
      required = listOf(templated("required-thing")),
    )
    val tree = treeWithResourceIds(listOf("com.example.test:id/required-thing"))

    val result = WaypointMatcher.match(def, tree, target = null)

    assertFalse(result.matched)
    assertEquals(WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE, result.skipped)
    // The skip is structural — no per-entry match info is meaningful.
    assertTrue(result.matchedRequired.isEmpty())
    assertTrue(result.missingRequired.isEmpty())
    assertTrue(result.presentForbidden.isEmpty())
  }

  @Test
  fun `templated forbidden-only selector with null target skips (the bug Copilot caught)`() {
    // The waypoint's `required` is a plain literal selector that does match the screen, AND
    // `forbidden` is templated. Without the fail-closed skip, the forbidden check would
    // evaluate the literal `{{target.appId}}` regex (never matches anything), forbidden
    // would pass, and the waypoint would be reported as matched even though the author's
    // intent (block when the forbidden element is present) can't actually be evaluated.
    val def = waypoint(
      id = "forbidden-template",
      required = listOf(literalText("Visible heading")),
      forbidden = listOf(templated("blocked-thing")),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Visible heading"),
        ),
        // Note: a node DOES carry the resourceId the forbidden tries to flag. Without
        // expansion, the literal-placeholder regex never matches this node either, so the
        // forbidden check would silently pass. Fail-closed must short-circuit before this
        // false-positive resolution can happen.
        TrailblazeNode(
          nodeId = 3,
          driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example.test:id/blocked-thing"),
        ),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    val result = WaypointMatcher.match(def, tree, target = null)

    assertFalse(result.matched, "must not match — the forbidden side is untestable without target context")
    assertEquals(WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE, result.skipped)
  }

  @Test
  fun `templated selectors match normally when target is supplied`() {
    val def = waypoint(
      id = "happy-path",
      required = listOf(templated("required-thing")),
      forbidden = listOf(templated("blocked-thing")),
    )
    val tree = treeWithResourceIds(listOf("com.example.test:id/required-thing"))

    val result = WaypointMatcher.match(
      def,
      tree,
      target = TargetTemplateContext(appId = "com.example.test"),
    )

    assertTrue(result.matched)
    assertNull(result.skipped)
    assertEquals(1, result.matchedRequired.size)
    assertTrue(result.presentForbidden.isEmpty())
  }

  @Test
  fun `placeholder buried in a spatial sub-selector also triggers fail-closed`() {
    // The placeholder isn't in the top-level driver match or in containsChild — it lives
    // inside a `below` spatial anchor. `containsUnresolvedPlaceholder` has to recurse
    // through every spatial/hierarchy field to catch this; if a future refactor drops the
    // `below`/`above`/`leftOf`/`rightOf`/`childOf` recursion, this test fails loud.
    val def = WaypointDefinition(
      id = "spatial-template",
      required = listOf(
        WaypointSelectorEntry(
          selector = TrailblazeNodeSelector(
            androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^Anchor$"),
            below = TrailblazeNodeSelector(
              androidAccessibility = DriverNodeMatch.AndroidAccessibility(
                resourceIdRegex = "^{{target.appId}}:id/buried$",
              ),
            ),
          ),
        ),
      ),
    )
    val tree = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Anchor"),
        ),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    val result = WaypointMatcher.match(def, tree, target = null)

    assertFalse(result.matched)
    assertEquals(WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE, result.skipped)
  }

  @Test
  fun `literal-only waypoint is unaffected by a null target`() {
    val def = waypoint(id = "literal-only", required = listOf(literalText("Welcome")))
    val tree = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Welcome"),
        ),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    val result = WaypointMatcher.match(def, tree, target = null)

    assertTrue(result.matched)
    assertNull(result.skipped, "literal selectors must NOT be fail-closed by the template check")
  }

  // --- fixtures ---

  private fun waypoint(
    id: String,
    required: List<WaypointSelectorEntry> = emptyList(),
    forbidden: List<WaypointSelectorEntry> = emptyList(),
  ): WaypointDefinition = WaypointDefinition(id = id, required = required, forbidden = forbidden)

  private fun templated(suffix: String): WaypointSelectorEntry = WaypointSelectorEntry(
    selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        resourceIdRegex = "^{{target.appId}}:id/$suffix$",
      ),
    ),
  )

  private fun literalText(text: String): WaypointSelectorEntry = WaypointSelectorEntry(
    selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^$text$"),
    ),
  )

  private fun treeWithResourceIds(resourceIds: List<String>): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    children = resourceIds.mapIndexed { i, rid ->
      TrailblazeNode(
        nodeId = (i + 2).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = rid),
      )
    },
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
  )
}

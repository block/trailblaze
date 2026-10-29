package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.cli.WaypointMigrateTrailCommand.SelectorAnchor

/**
 * Pins the anchor refinement that decides which target-dialect node a migrated selector is built
 * from — and, when nothing matches, that the tool is skipped rather than pointed at a neighbour.
 *
 * How strictly an anchor matches is the interesting part: the anchors are text as RECORDED, so the
 * right strictness is whatever the dialect it was recorded in applied, case and whole-string alike,
 * not whatever this function finds convenient.
 */
class WaypointMigrateTrailCommandAnchorMatchTest {

  private val command = WaypointMigrateTrailCommand()

  private fun axeNode(id: Long, label: String, left: Int, top: Int) = TrailblazeNode(
    nodeId = id,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = label),
    bounds = TrailblazeNode.Bounds(left = left, top = top, right = left + 100, bottom = top + 40),
  )

  private fun anchor(pattern: String, maestro: Boolean, id: Boolean = false) =
    SelectorAnchor(pattern, maestroSemantics = maestro, isIdentifier = id)

  private fun screen(vararg children: TrailblazeNode) = TrailblazeNode(
    nodeId = 0,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXApplication"),
    bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
    children = children.toList(),
  )

  @Test
  fun `a Maestro-recorded anchor matches a differently-cased label`() {
    // Maestro's matcher is case-insensitive, so `search` was a legitimate recording for a node
    // labeled `Search` and still resolves on the source tree. Matching case-sensitively here
    // scored the target node zero and skipped the tool as "intent lost".
    val search = axeNode(1, "Search", left = 10, top = 10)

    val match = command.findAnchorMatchingNode(
      tree = screen(search),
      anchors = listOf(anchor("search", maestro = true)),
      tapX = 60,
      tapY = 30,
    )

    assertEquals(search, match)
  }

  @Test
  fun `a natively-recorded anchor keeps its case`() {
    // The native resolvers match case-sensitively, so a dialect that resolves that way must not
    // have its recorded intent widened here — `search` naming `Search` would be a new match the
    // recording never made.
    val match = command.findAnchorMatchingNode(
      tree = screen(axeNode(1, "Search", left = 10, top = 10)),
      anchors = listOf(anchor("search", maestro = false)),
      tapX = 60,
      tapY = 30,
    )

    assertNull(match)
  }

  @Test
  fun `case insensitivity does not override the anchor count`() {
    // Widening case must not let a one-anchor node beat a two-anchor node: the score still ranks
    // first, proximity only breaks ties.
    val nearButWeaker = axeNode(1, "search", left = 10, top = 10)
    val farButStronger = TrailblazeNode(
      nodeId = 2,
      driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = "Search", uniqueId = "field_id"),
      bounds = TrailblazeNode.Bounds(left = 10, top = 600, right = 110, bottom = 640),
    )

    val match = command.findAnchorMatchingNode(
      tree = screen(nearButWeaker, farButStronger),
      anchors = listOf(anchor("search", maestro = true), anchor("field_id", maestro = true, id = true)),
      tapX = 60,
      tapY = 30,
    )

    assertEquals(farButStronger, match)
  }

  @Test
  fun `no node carrying the recorded text means no match, so the caller skips`() {
    val match = command.findAnchorMatchingNode(
      tree = screen(axeNode(1, "Contacts", left = 10, top = 10)),
      anchors = listOf(anchor("Search", maestro = true)),
      tapX = 60,
      tapY = 30,
    )

    assertNull(match)
  }

  @Test
  fun `an anchor matches the whole value, so ok does not anchor a nearer book`() {
    // Native resolution full-matches `textRegex: ok`, so `book` was never a match for it. Scoring
    // a substring hit gave both nodes one anchor and proximity picked the wrong one.
    val ok = axeNode(1, "ok", left = 10, top = 600)
    val book = axeNode(2, "book", left = 10, top = 10)

    val match = command.findAnchorMatchingNode(
      tree = screen(ok, book),
      anchors = listOf(anchor("ok", maestro = false)),
      tapX = 60,
      tapY = 30,
    )

    assertEquals(ok, match)
  }

  @Test
  fun `a price anchors through the literal fallback`() {
    // `$5.00` never regex-matches (the leading `$` is an end anchor); the runtime falls back to
    // equality, and so must the anchor, or every price-anchored tool is skipped.
    val price = axeNode(1, "$5.00", left = 10, top = 10)

    val match = command.findAnchorMatchingNode(
      tree = screen(price),
      anchors = listOf(anchor("$5.00", maestro = false)),
      tapX = 60,
      tapY = 30,
    )

    assertEquals(price, match)
  }

  @Test
  fun `every text field on a recorded leaf is an anchor, not just the first`() {
    // The Maestro lowering keeps one text field per leaf, so a leaf naming both text and
    // content description anchored on text alone, and a nearer node carrying only that text won.
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Pay",
        contentDescriptionRegex = "Pay with card",
      ),
    )

    val anchors = command.collectSelectorAnchors(selector)

    assertEquals(
      listOf(anchor("Pay", maestro = false), anchor("Pay with card", maestro = false)),
      anchors,
    )
    val textOnly = axeNode(1, "Pay", left = 10, top = 10)
    val both = TrailblazeNode(
      nodeId = 2,
      driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = "Pay", value = "Pay with card"),
      bounds = TrailblazeNode.Bounds(left = 10, top = 600, right = 110, bottom = 640),
    )
    assertEquals(both, command.findAnchorMatchingNode(screen(textOnly, both), anchors, tapX = 60, tapY = 30))
  }

  @Test
  fun `an anchor carries the semantics of the leaf it was recorded on`() {
    val selector = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Done"),
      containsChild = TrailblazeNodeSelector(iosAxe = DriverNodeMatch.IosAxe(labelRegex = "Close")),
    )

    assertEquals(
      listOf(anchor("Done", maestro = true), anchor("Close", maestro = false)),
      command.collectSelectorAnchors(selector),
    )
  }

  @Test
  fun `a Maestro id anchor also matches the id after its package prefix`() {
    // Maestro matches `idRegex: save` against `com.app:id/save` by its suffix too, so a lowered
    // id resolves on the source tree; the anchor has to name the same node on the target.
    val save = TrailblazeNode(
      nodeId = 1,
      driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.app:id/save"),
      bounds = TrailblazeNode.Bounds(left = 10, top = 10, right = 110, bottom = 50),
    )

    assertEquals(save, command.findAnchorMatchingNode(screen(save), listOf(anchor("save", maestro = true, id = true)), 60, 30))
    assertNull(command.findAnchorMatchingNode(screen(save), listOf(anchor("save", maestro = false, id = true)), 60, 30))
  }

  @Test
  fun `a Maestro text anchor reads newlines as spaces`() {
    val twoLines = axeNode(1, "Line one\nLine two", left = 10, top = 10)

    assertEquals(
      twoLines,
      command.findAnchorMatchingNode(screen(twoLines), listOf(anchor("Line one Line two", maestro = true)), 60, 30),
    )
    assertNull(
      command.findAnchorMatchingNode(screen(twoLines), listOf(anchor("Line one Line two", maestro = false)), 60, 30),
    )
  }

  @Test
  fun `text and id anchors score against their own kind of value`() {
    // An id anchor naming a node's label is not a match: Maestro's id filter never reads text.
    val labelled = axeNode(1, "save", left = 10, top = 10)

    assertNull(command.findAnchorMatchingNode(screen(labelled), listOf(anchor("save", maestro = true, id = true)), 60, 30))
  }

  @Test
  fun `a lowered selector gives Maestro text and id anchors`() {
    val lowered = TrailblazeElementSelector(
      containsChild = TrailblazeElementSelector(textRegex = "Charge", idRegex = "checkout_button_title"),
    )

    assertEquals(
      listOf(anchor("Charge", maestro = true), anchor("checkout_button_title", maestro = true, id = true)),
      command.collectLoweredSelectorAnchors(lowered),
    )
  }
}

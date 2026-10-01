package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ExtractedString
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.VisibleStringSource
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class TrailblazeLogVisibleStringsTest {

  private val session = SessionId("s")
  private val at = Instant.parse("2026-09-25T09:21:37Z")

  private val screen = TrailblazeNode(
    nodeId = 0,
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
    children = listOf(
      TrailblazeNode(
        nodeId = 1,
        ref = "a1",
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Pay"),
        bounds = TrailblazeNode.Bounds(24, 180, 320, 224),
      ),
      TrailblazeNode(
        nodeId = 2,
        ref = "a2",
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Terms"),
        bounds = TrailblazeNode.Bounds(0, 2000, 200, 2050),
      ),
    ),
  )

  private fun driverLog(
    tree: TrailblazeNode?,
    strings: List<ExtractedString>? = null,
    screenshotFile: String? = "s_1790367715162.png",
  ) = TrailblazeLog.AgentDriverLog(
    viewHierarchy = ViewHierarchyTreeNode(),
    trailblazeNodeTree = tree,
    screenshotFile = screenshotFile,
    action = AgentDriverAction.TapPoint(x = 10, y = 20),
    visibleStrings = strings,
    durationMs = 0,
    session = session,
    timestamp = at,
    deviceWidth = 400,
    deviceHeight = 800,
  )

  @Test
  fun `a capture log gets the strings its tree shows, with corners and whether each was on screen`() {
    val log = driverLog(screen).withVisibleStrings() as TrailblazeLog.AgentDriverLog

    assertEquals(
      listOf(
        Triple("Pay", listOf(24, 180, 320, 224), true),
        Triple("Terms", listOf(0, 2000, 200, 2050), false),
      ),
      log.visibleStrings!!.map { Triple(it.text, it.bounds, it.visible) },
    )
  }

  @Test
  fun `a log that already carries strings is left alone, so the device and host do not both extract`() {
    val recorded = listOf(ExtractedString(text = "From the device", source = VisibleStringSource.TEXT))
    val log = driverLog(screen, strings = recorded)

    assertSame(log, log.withVisibleStrings())
  }

  @Test
  fun `a capture with no tree has nothing to read, which is not the same as a screen with no text`() {
    val log = driverLog(tree = null).withVisibleStrings() as TrailblazeLog.AgentDriverLog
    assertNull(log.visibleStrings)

    val blank = driverLog(TrailblazeNode(nodeId = 0, driverDetail = DriverNodeDetail.AndroidAccessibility()))
    assertEquals(emptyList(), (blank.withVisibleStrings() as TrailblazeLog.AgentDriverLog).visibleStrings)
  }

  @Test
  fun `a capture with no screenshot is named once, as it is emitted, and keeps that name`() {
    val named = driverLog(screen, screenshotFile = null).withVisibleStrings() as TrailblazeLog.AgentDriverLog
    val id = assertNotNull(named.captureId)
    assertEquals(listOf("Pay", "Terms"), named.visibleStrings!!.map { it.text })
    // The host calling it again on the device's log keeps the device's name.
    assertSame(named, named.withVisibleStrings())
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeLog.serializer(),
      TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), named),
    ) as TrailblazeLog.AgentDriverLog
    assertEquals(id, decoded.captureId)
    // Two captures in the same millisecond still get different names.
    assertNotEquals(id, (driverLog(screen, screenshotFile = null).withVisibleStrings() as TrailblazeLog.AgentDriverLog).captureId)
  }

  @Test
  fun `a screenshot names its capture, and a capture with nothing to read gets no name`() {
    assertNull((driverLog(screen).withVisibleStrings() as TrailblazeLog.AgentDriverLog).captureId)
    assertNull((driverLog(tree = null, screenshotFile = null).withVisibleStrings() as TrailblazeLog.AgentDriverLog).captureId)
  }

  @Test
  fun `a log that is not a screen capture passes through untouched`() {
    val status = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Unknown,
      session = session,
      timestamp = at,
    )
    assertSame(status, status.withVisibleStrings())
  }

  @Test
  fun `the strings survive the session JSON every transport and zip carries`() {
    val log = driverLog(screen).withVisibleStrings()
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeLog.serializer(),
      TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log),
    ) as TrailblazeLog.AgentDriverLog

    assertEquals((log as TrailblazeLog.AgentDriverLog).visibleStrings, decoded.visibleStrings)
  }

  @Test
  fun `a web page's iframe content is read with the page's own strings, repeats once`() {
    val frame = TrailblazeNode(
      nodeId = 0,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(
        TrailblazeNode(
          nodeId = 1,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Card number"),
          bounds = TrailblazeNode.Bounds(24, 300, 320, 344),
        ),
        TrailblazeNode(
          nodeId = 2,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Pay"),
          bounds = TrailblazeNode.Bounds(24, 400, 320, 444),
        ),
      ),
    )
    val log = driverLog(screen).copy(frameTrees = listOf(frame))

    // In reading order across page and frame, and the frame's "Pay" is the page's repeated.
    assertEquals(listOf("Pay", "Card number", "Terms"), log.visibleStringsOrExtracted!!.map { it.text })
    assertEquals(
      listOf("Pay", "Card number", "Terms"),
      (log.withVisibleStrings() as TrailblazeLog.AgentDriverLog).visibleStrings!!.map { it.text },
    )
  }

  @Test
  fun `an older log without the field still yields its strings when read`() {
    assertEquals(listOf("Pay", "Terms"), driverLog(screen).visibleStringsOrExtracted!!.map { it.text })
    assertNull(driverLog(tree = null).visibleStringsOrExtracted)
  }
}

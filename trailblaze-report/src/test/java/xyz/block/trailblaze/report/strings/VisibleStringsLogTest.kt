package xyz.block.trailblaze.report.strings

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenTextReader
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.withVisibleStrings
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.yaml.TrailConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisibleStringsLogTest {

  private val session = SessionId("2026_09_09_test")
  private var clock = Instant.parse("2026-09-09T17:04:11Z")

  private fun screen(vararg labels: String): TrailblazeNode = screenAt(top = 0, *labels)

  /** [screen] with every element pushed down by [top], as a scroll would move it. */
  private fun screenAt(top: Int, vararg labels: String): TrailblazeNode = TrailblazeNode(
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
    children = labels.mapIndexed { index, label ->
      TrailblazeNode(
        nodeId = index.toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = label),
        bounds = TrailblazeNode.Bounds(0, top + index * 100, 200, top + index * 100 + 50),
      )
    },
  )

  private fun driverLog(
    screenshot: String?,
    tree: TrailblazeNode?,
    action: AgentDriverAction = AgentDriverAction.BackPress,
  ): TrailblazeLog.AgentDriverLog {
    clock = clock.plus(kotlin.time.Duration.parse("1s"))
    return TrailblazeLog.AgentDriverLog(
      viewHierarchy = null,
      trailblazeNodeTree = tree,
      screenshotFile = screenshot,
      action = action,
      durationMs = 10,
      session = session,
      timestamp = clock,
      deviceHeight = 1920,
      deviceWidth = 1080,
    )
  }

  private fun render(
    logs: List<TrailblazeLog>,
    collapseRepeats: Boolean = true,
    readers: Map<String, ScreenTextReader>? = null,
  ): List<JsonObject> =
    VisibleStringsLog.render(
      session,
      sessionInfo = null,
      logs = logs,
      collapseRepeats = collapseRepeats,
      readerFor = readers?.let { byScreenshot -> { screenshot, _, _ -> byScreenshot.getValue(screenshot) } },
    )
      .orEmpty()
      .trim()
      .lines()
      .filter { it.isNotBlank() }
      .map { Json.parseToJsonElement(it).jsonObject }

  private fun JsonObject.strings(): List<String> =
    this["strings"]?.jsonArray?.map { it.jsonObject.getValue("text").jsonPrimitive.content } ?: emptyList()

  /** An iOS screen with an icon-only button and a button that draws its label. */
  private val iosHome = TrailblazeNode(
    nodeId = 100,
    driverDetail = DriverNodeDetail.IosAxe(type = "Application"),
    bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
    children = listOf(
      TrailblazeNode(
        nodeId = 101,
        driverDetail = DriverNodeDetail.IosAxe(label = "Scan", type = "Button"),
        bounds = TrailblazeNode.Bounds(600, 200, 720, 320),
      ),
      TrailblazeNode(
        nodeId = 102,
        driverDetail = DriverNodeDetail.IosAxe(label = "Pay", type = "Button"),
        bounds = TrailblazeNode.Bounds(540, 1600, 1040, 1740),
      ),
    ),
  )

  private fun JsonObject.sourceOf(text: String): String? = this["strings"]?.jsonArray
    ?.map { it.jsonObject }
    ?.firstOrNull { it.getValue("text").jsonPrimitive.content == text }
    ?.getValue("source")?.jsonPrimitive?.content

  @Test
  fun `a capture OCR read is marked checked, and one it could not read is not`() {
    val read = ScreenTextReader { boxes -> boxes.map { if (it.top == 1600) listOf("Pay") else emptyList() } }
    val lines = render(
      listOf(
        driverLog("ios-read.png", iosHome).withVisibleStrings(),
        driverLog("ios-unreadable.png", iosHome).withVisibleStrings(),
        driverLog("android.png", screen("Checkout")).withVisibleStrings(),
      ),
      collapseRepeats = false,
      readers = mapOf("ios-read.png" to read, "ios-unreadable.png" to ScreenTextReader { null }, "android.png" to read),
    ).drop(1)

    val (checked, unchecked, android) = lines
    assertEquals("true", checked["ocrChecked"]?.jsonPrimitive?.content)
    assertEquals("contentDescription", checked.sourceOf("Scan"))
    assertEquals("text", checked.sourceOf("Pay"))
    assertNull(unchecked["ocrChecked"])
    assertEquals("text", unchecked.sourceOf("Scan"))
    assertNull(android["ocrChecked"])
  }

  @Test
  fun `every capture becomes a line keyed by its screenshot`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", screen("Payment complete")),
      ),
    )

    assertEquals(listOf("run", "screen", "screen"), lines.map { it.getValue("kind").jsonPrimitive.content })
    assertEquals(
      listOf("shot-a.png", "shot-b.png"),
      lines.drop(1).map { it.getValue("captureId").jsonPrimitive.content },
    )
    assertEquals(listOf(listOf("Checkout"), listOf("Payment complete")), lines.drop(1).map { it.strings() })
  }

  @Test
  fun `captures are ordered by when they happened, not by list order`() {
    val first = driverLog("first.png", screen("One"))
    val second = driverLog("second.png", screen("Two"))

    val lines = render(listOf(second, first)).drop(1)

    assertEquals(listOf("first.png", "second.png"), lines.map { it.getValue("captureId").jsonPrimitive.content })
  }

  /** The screenshot is the key; a step counter or the log's class name would be a second one. */
  @Test
  fun `a screen line names its screenshot and nothing else to join on`() {
    val line = render(listOf(driverLog("shot-a.png", screen("Checkout")))).last()

    assertNull(line["stepIndex"])
    assertNull(line["logType"])
  }

  /** A driver log and the LLM request made on the same screen can name one image; that is one capture. */
  @Test
  fun `two logs naming the same screenshot are one line`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-a.png", screen("Checkout", "Tip")),
        driverLog("shot-b.png", screen("Done")),
      ),
    ).drop(1)

    assertEquals(listOf("shot-a.png", "shot-b.png"), lines.map { it.getValue("captureId").jsonPrimitive.content })
    assertEquals(listOf("Checkout"), lines[0].strings())
  }

  @Test
  fun `bounds are the corners of the element, as the tree stores them`() {
    val line = render(listOf(driverLog("shot-a.png", screen("Checkout", "Pay")))).last()

    assertEquals(
      listOf(listOf(0, 0, 200, 50), listOf(0, 100, 200, 150)),
      line.getValue("strings").jsonArray.map { s -> s.jsonObject.getValue("bounds").jsonArray.map { it.jsonPrimitive.content.toInt() } },
    )
  }

  /** Emission fills the strings in; the export writes what the log carries rather than re-reading the tree. */
  @Test
  fun `the strings a log carries are the ones written`() {
    val recorded = listOf(xyz.block.trailblaze.api.ExtractedString(text = "Recorded on device", source = xyz.block.trailblaze.api.VisibleStringSource.TEXT))
    val line = render(listOf(driverLog("shot-a.png", screen("Stale tree")).copy(visibleStrings = recorded))).last()

    assertEquals(listOf("Recorded on device"), line.strings())
  }

  /** Volatility is judged by whoever reads the file, so a better rule reaches files already written. */
  @Test
  fun `a string carries no volatile judgement`() {
    val line = render(listOf(driverLog("shot-a.png", screen("9:41")))).last()

    assertNull(line.getValue("strings").jsonArray.single().jsonObject["volatile"])
  }

  @Test
  fun `a capture repeating the previous screen keeps its place and drops its payload`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", screen("Checkout")),
      ),
    ).drop(1)

    assertNull(lines[0]["repeatOf"])
    assertEquals(listOf("Checkout"), lines[0].strings())
    assertEquals("shot-a.png", lines[1].getValue("repeatOf").jsonPrimitive.content)
    assertEquals(emptyList(), lines[1].strings())
  }

  @Test
  fun `a screen seen three times points every repeat at the capture that still holds the strings`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", screen("Checkout")),
        driverLog("shot-c.png", screen("Checkout")),
      ),
    ).drop(1)

    assertEquals(
      listOf("shot-a.png", "shot-a.png"),
      lines.drop(1).map { it.getValue("repeatOf").jsonPrimitive.content },
    )
    assertEquals(listOf("Checkout"), lines[0].strings())
  }

  @Test
  fun `the same words at a new position are not a repeat, so a reader never copies stale bounds`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screenAt(top = 0, "Checkout")),
        driverLog("shot-b.png", screenAt(top = 400, "Checkout")),
      ),
    ).drop(1)

    assertTrue(lines.all { it["repeatOf"] == null })
    assertEquals(
      listOf("[0,400,200,450]"),
      lines[1].getValue("strings").jsonArray.map { it.jsonObject.getValue("bounds").toString() },
    )
    assertEquals(lines[0]["screenContentHash"], lines[1]["screenContentHash"])
  }

  @Test
  fun `the same words scrolled offscreen are not a repeat, so a reader never copies stale visibility`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screenAt(top = 0, "Checkout")),
        driverLog("shot-b.png", screenAt(top = 4000, "Checkout")),
      ),
    ).drop(1)

    assertTrue(lines.all { it["repeatOf"] == null })
    assertEquals(
      listOf(true, false),
      lines.map { line ->
        line.getValue("strings").jsonArray.single().jsonObject["visible"]?.jsonPrimitive?.content?.toBoolean() ?: true
      },
    )
  }

  @Test
  fun `--all keeps the strings on a repeated screen`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", screen("Checkout")),
      ),
      collapseRepeats = false,
    ).drop(1)

    assertEquals(listOf(listOf("Checkout"), listOf("Checkout")), lines.map { it.strings() })
    assertTrue(lines.all { it["repeatOf"] == null })
  }

  @Test
  fun `two screens saying different things get different screen content hashes`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", screen("Refund")),
      ),
    ).drop(1)

    assertTrue(
      lines[0].getValue("screenContentHash").jsonPrimitive.content !=
        lines[1].getValue("screenContentHash").jsonPrimitive.content,
    )
    assertNull(lines[0]["screenId"])
  }

  @Test
  fun `a capture with no screenshot is a line named by the id it was stamped with`() {
    val stamped = driverLog(screenshot = null, tree = screen("Signed in")).withVisibleStrings() as TrailblazeLog.AgentDriverLog
    val lines = render(listOf(stamped, driverLog("shot-a.png", screen("Checkout")))).drop(1)

    assertEquals(listOf(stamped.captureId!!, "shot-a.png"), lines.map { it.getValue("captureId").jsonPrimitive.content })
    assertEquals(listOf("Signed in"), lines[0].strings())
    assertNull(lines[0]["screenshot"])
    assertNull(lines[0]["captureUrl"])
    assertEquals("false", lines[0].getValue("screenshotIsAnnotated").jsonPrimitive.content)
  }

  @Test
  fun `a capture with no screenshot names its video frame, and one with a screenshot does not`() {
    val stamped = driverLog(screenshot = null, tree = screen("Signed in")).withVisibleStrings() as TrailblazeLog.AgentDriverLog
    val id = stamped.captureId!!
    val lines = VisibleStringsLog.render(
      session,
      sessionInfo = null,
      logs = listOf(stamped, driverLog("shot-a.png", screen("Checkout"))),
      frames = mapOf(id to "$id.webp", "shot-a.png" to "shot-a.png.webp"),
    )!!.trim().lines().drop(1).map { Json.parseToJsonElement(it).jsonObject }

    assertEquals("$id.webp", lines[0].getValue("frame").jsonPrimitive.content)
    assertNull(lines[1]["frame"])
  }

  /** A log recorded before capture ids existed, with no screenshot, has nothing to be named by. */
  @Test
  fun `a capture with neither a screenshot nor an id is not a line`() {
    val lines = render(
      listOf(
        driverLog(screenshot = null, tree = screen("Invisible")),
        driverLog("shot-a.png", screen("Checkout")),
      ),
    )

    assertEquals(1, lines.count { it.getValue("kind").jsonPrimitive.content == "screen" })
  }

  @Test
  fun `a session with nothing to read produces no file`() {
    assertNull(VisibleStringsLog.render(session, sessionInfo = null, logs = emptyList()))
  }

  @Test
  fun `a host run labels its locale from the trail config, since only android measures one`() {
    val info = SessionInfo(
      sessionId = session,
      latestStatus = SessionStatus.Ended.Succeeded(durationMs = 1),
      timestamp = clock,
      durationMs = 1,
      trailFilePath = "checkout.trail.yaml",
      hasRecordedSteps = false,
      trailConfig = TrailConfig(locale = "es"),
    )

    val header = VisibleStringsLog.render(session, info, listOf(driverLog("shot-a.png", screen("Checkout"))))
      .orEmpty()
      .lines()
      .first { it.isNotBlank() }
      .let { Json.parseToJsonElement(it).jsonObject }

    assertEquals("es", header.getValue("locale").jsonPrimitive.content)
  }

  @Test
  fun `the header names the run so two files can be told apart`() {
    val header = render(listOf(driverLog("shot-a.png", screen("Checkout")))).first()

    assertEquals("run", header.getValue("kind").jsonPrimitive.content)
    assertEquals(session.value, header.getValue("session").jsonPrimitive.content)
    assertEquals(VisibleStringsLog.FORMAT_VERSION, header.getValue("v").jsonPrimitive.content.toInt())
  }

  @Test
  fun `a farm capture is keyed by its durable filename, with the expiring url kept beside it`() {
    val url = "https://s3.example.com/artifacts/job/2026_09_09_1704_1757437451123.png?expires=1755710000"

    val line = render(listOf(driverLog(url, screen("Checkout")))).last()

    assertEquals("2026_09_09_1704_1757437451123.png", line.getValue("captureId").jsonPrimitive.content)
    assertEquals("2026_09_09_1704_1757437451123.png", line.getValue("screenshot").jsonPrimitive.content)
    assertEquals(url, line.getValue("captureUrl").jsonPrimitive.content)
  }

  @Test
  fun `a local capture is already a bare filename and gains no url`() {
    val line = render(listOf(driverLog("shot-a.png", screen("Checkout")))).last()

    assertEquals("shot-a.png", line.getValue("captureId").jsonPrimitive.content)
    assertNull(line["captureUrl"])
  }

  /**
   * The farm also publishes download links whose path is bare `/`, with the whole object key in a
   * query parameter. Reading the last path segment yields nothing there, which would collapse
   * every capture in the run onto one empty id.
   */
  @Test
  fun `a farm url carrying its key in the query still resolves to the image name`() {
    val url = "https://lambda.example.com/?bucket=artifacts&key=runs%2Fjob%2Flogs%2Fshot-a.png" +
      "&X-Amz-Credential=redacted%2F20260909%2Fus-west-2%2Fs3%2Faws4_request"

    val line = render(listOf(driverLog(url, screen("Checkout")))).last()

    assertEquals("shot-a.png", line.getValue("captureId").jsonPrimitive.content)
    assertEquals(url, line.getValue("captureUrl").jsonPrimitive.content)
  }

  /** Decoding before dropping the query would fold the escaped object path into the name. */
  @Test
  fun `an escaped object path in a signed url does not become part of the name`() {
    val url = "https://s3.example.com/runs%2Fjob%2Fshot-a.png?X-Amz-Credential=redacted%2Fus-west-2%2Fs3"

    val line = render(listOf(driverLog(url, screen("Checkout")))).last()

    assertEquals("shot-a.png", line.getValue("captureId").jsonPrimitive.content)
  }

  /**
   * `download` is the last path segment here and would be the same for every capture in the run,
   * which merges them all onto one line in a diff. A long id at least still names one image.
   */
  @Test
  fun `a reference with no image name in it is kept whole rather than reduced to a shared segment`() {
    val line = render(listOf(driverLog("https://example.com/download?id=42", screen("Checkout")))).last()

    assertEquals("https://example.com/download?id=42", line.getValue("captureId").jsonPrimitive.content)
  }

  /** A local reference already is the filename, so the fallback returns it unchanged. */
  @Test
  fun `a local filename with an unrecognized extension survives the fallback intact`() {
    val line = render(listOf(driverLog("shot-a.heic", screen("Checkout")))).last()

    assertEquals("shot-a.heic", line.getValue("captureId").jsonPrimitive.content)
  }

  /**
   * Host runs scale the screenshot bytes down while the tree keeps native coordinates, so without
   * the device's own dimensions a reader cannot map [ExtractedString.bounds] onto the image.
   */
  @Test
  fun `each capture carries the coordinate space its bounds are in`() {
    val line = render(listOf(driverLog("shot-a.png", screen("Checkout")))).last()

    assertEquals(1080, line.getValue("deviceWidth").jsonPrimitive.content.toInt())
    assertEquals(1920, line.getValue("deviceHeight").jsonPrimitive.content.toInt())
  }

  @Test
  fun `a truncated android capture is marked so a diff cannot read it as deleted text`() {
    val truncated = driverLog("shot-a.png", screen("Checkout")).copy(
      captureCoverage = xyz.block.trailblaze.api.CaptureCoverage(
        contentNodes = 3,
        zeroBoundsContentNodes = 0,
        horizontalCoverage = 0.2,
        verticalCoverage = 0.9,
        looksTruncated = true,
        reason = "content jammed against one edge",
      ),
    )

    val line = render(listOf(truncated)).last()

    assertTrue(line.getValue("partialCapture").jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `a driver that cannot assess coverage says nothing rather than claiming completeness`() {
    val line = render(listOf(driverLog("shot-a.png", screen("Checkout")))).last()

    assertNull(line["partialCapture"])
  }

  /**
   * iOS keeps the screenshot and drops the tree when `describe-ui` fails. Unmarked, that files as
   * a screen with no text, and a diff against a healthy run reports every string as deleted.
   */
  @Test
  fun `a capture whose hierarchy failed is marked partial, not filed as a screen with no text`() {
    val line = render(listOf(driverLog("shot-a.png", tree = null))).last()

    assertEquals(emptyList(), line.strings())
    assertTrue(line.getValue("partialCapture").jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `a hierarchy failure does not claim to repeat the one before it`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", tree = null),
        driverLog("shot-b.png", tree = null),
      ),
    ).drop(1)

    assertNull(lines[0]["repeatOf"])
    assertNull(lines[1]["repeatOf"])
  }

  @Test
  fun `a screen that genuinely has no text does not repeat a hierarchy failure`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", tree = null),
        driverLog("shot-b.png", screen()),
      ),
    ).drop(1)

    assertNull(lines[1]["repeatOf"])
    assertNull(lines[1]["partialCapture"])
  }

  @Test
  fun `a hierarchy failure keeps its place so the screens after it still line up`() {
    val lines = render(
      listOf(
        driverLog("shot-a.png", screen("Checkout")),
        driverLog("shot-b.png", tree = null),
        driverLog("shot-c.png", screen("Payment complete")),
      ),
    ).drop(1)

    assertEquals(listOf("shot-a.png", "shot-b.png", "shot-c.png"), lines.map { it.getValue("captureId").jsonPrimitive.content })
    assertEquals(listOf("Payment complete"), lines[2].strings())
  }
}

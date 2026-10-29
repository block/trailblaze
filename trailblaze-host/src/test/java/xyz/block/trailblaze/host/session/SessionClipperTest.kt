package xyz.block.trailblaze.host.session

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.capture.CaptureMetadata
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.events.AttachmentRef
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSync
import xyz.block.trailblaze.logs.model.DeviceClockTrack
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Clips a session directory fixture built from the real log and metadata types, and reads back what
 * the copy holds. The window throughout is [WINDOW_START]..[WINDOW_END] on the host clock with 500ms
 * of padding, and the device's clock, where there is one, runs 2s ahead of the host's.
 */
class SessionClipperTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val session by lazy { tmp.newFolder("session") }
  private val out by lazy { File(tmp.root, "out") }
  private val sessionId = SessionId("clip-test")

  private fun clipper(
    sliceVideo: (File, Long, Long, File) -> Boolean = { _, _, _, _ -> error("no video should be cut") },
    videoDurationMs: (File) -> Long? = { error("no video should be probed") },
  ) = SessionClipper(sliceVideo, videoDurationMs, paddingMs = PADDING)

  private fun clip(clipper: SessionClipper = clipper()) = clipper.clip(session, WINDOW_START, WINDOW_END, out)

  @Test
  fun `logs are kept from the window's start to the padding past its end, and the session status logs always`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeLog("002", plainLog(WINDOW_START - 1))
    writeLog("003", plainLog(WINDOW_START))
    writeLog("004", plainLog(WINDOW_START + 5_000))
    writeLog("005", plainLog(WINDOW_END + PADDING))
    writeLog("006", plainLog(WINDOW_END + PADDING + 1))
    writeLog("007", status(SessionStatus.Ended.Succeeded(durationMs = 60_000), T + 60_000))

    clip()

    assertEquals(
      listOf(
        "001_TrailblazeSessionStatusChangeLog.json",
        "003_AgentDriverLog.json",
        "004_AgentDriverLog.json",
        "005_AgentDriverLog.json",
        "007_TrailblazeSessionStatusChangeLog.json",
      ),
      logNames(out),
    )
  }

  @Test
  fun `the session status logs are restamped to the window, its start and its end`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T, clock = TrailblazeClockDomain.DEVICE))
    writeLog("002", plainLog(WINDOW_START + 1_000))
    writeLog("003", status(SessionStatus.Ended.Succeeded(durationMs = 60_000), T + 60_000))

    clip()

    val started = readLog(out, "001_TrailblazeSessionStatusChangeLog.json")
    val ended = readLog(out, "003_TrailblazeSessionStatusChangeLog.json") as TrailblazeLog.TrailblazeSessionStatusChangeLog
    assertEquals(WINDOW_START, started.timestamp.toEpochMilliseconds())
    assertEquals(null, started.clock)
    assertEquals(WINDOW_END, ended.timestamp.toEpochMilliseconds())
    assertEquals(WINDOW_END - WINDOW_START, (ended.sessionStatus as SessionStatus.Ended.Succeeded).durationMs)
  }

  @Test
  fun `a succeeded end that a later failure replaced still reads as failed once both are clamped to the window`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeLog("002", status(SessionStatus.Ended.Succeeded(durationMs = 60_000), T + 60_000))
    writeLog("003", status(SessionStatus.Ended.Failed(durationMs = 60_000, exceptionMessage = "Capture failed"), T + 60_001))

    clip()

    val clipped = LogsRepo(tmp.root, watchFileSystem = false).getSessionInfoDirect(SessionId(out.name))!!
    assertIs<SessionStatus.Ended.Failed>(clipped.latestStatus)
  }

  @Test
  fun `a window that starts where it ends still reads as the session's end, not as running`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeLog("002", status(SessionStatus.Ended.Failed(durationMs = 60_000, exceptionMessage = "Capture failed"), T + 60_000))

    clipper().clip(session, WINDOW_START, WINDOW_START, out)

    val clipped = LogsRepo(tmp.root, watchFileSystem = false).getSessionInfoDirect(SessionId(out.name))!!
    assertIs<SessionStatus.Ended.Failed>(clipped.latestStatus)
  }

  @Test
  fun `the started log drops the plan of every pass, so the report lists only the steps the window ran`() {
    val plan = "trail:\n  - step: Open the menu\n  - step: Open the menu\n  - step: Open the menu"
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID).copy(rawYaml = plan), T))

    clip()

    val started = readLog(out, "001_TrailblazeSessionStatusChangeLog.json") as TrailblazeLog.TrailblazeSessionStatusChangeLog
    assertEquals(null, (started.sessionStatus as SessionStatus.Started).rawYaml)
  }

  @Test
  fun `a tool catalog logged before the window is kept when a kept request offered it`() {
    fun catalog(id: String) = TrailblazeLog.TrailblazeToolCatalogLog(
      toolCatalogId = id,
      toolOptions = emptyList(),
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(T),
    )
    writeLog("001", catalog("offered"))
    writeLog("002", catalog("unused"))
    File(session, "003_TrailblazeLlmRequestLog.json").writeText(
      """{"class":"xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog",""" +
        """"timestamp":"${Instant.fromEpochMilliseconds(WINDOW_START + 1_000)}","toolCatalogId":"offered"}""",
    )

    clip()

    assertEquals(listOf("001_TrailblazeToolCatalogLog.json", "003_TrailblazeLlmRequestLog.json"), logNames(out))
    // Stamped inside the window, so the clip's span still starts where the window does.
    assertEquals(WINDOW_START, readLog(out, "001_TrailblazeToolCatalogLog.json").timestamp.toEpochMilliseconds())
  }

  @Test
  fun `device-stamped logs are compared on the host clock, through the tool logs' anchors`() {
    val skew = 3_000L
    // The device's clock trails the host's by 3s: a stamp is 3s early, and the host receives the log
    // right after the tool finished.
    fun deviceTool(hostStartMs: Long) = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
      toolName = "tapOn",
      successful = true,
      traceId = null,
      durationMs = 50,
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(hostStartMs - skew),
      clock = TrailblazeClockDomain.DEVICE,
      hostReceivedAt = Instant.fromEpochMilliseconds(hostStartMs + 50),
    )
    writeLog("001", deviceTool(WINDOW_START + 2_000))
    writeLog("002", deviceTool(WINDOW_END + 10_000))
    // Raw stamp 3s before the window opens, but host time inside it.
    writeLog("003", plainLog(WINDOW_START + 1_000 - skew, clock = TrailblazeClockDomain.DEVICE, hostReceivedAt = WINDOW_START + 1_000))

    clip()

    assertEquals(listOf("001_TrailblazeToolLog.json", "003_AgentDriverLog.json"), logNames(out))
  }

  @Test
  fun `a screenshot is copied only when a kept log names it`() {
    writeLog("001", plainLog(WINDOW_START + 1_000, screenshot = "kept.png"))
    writeLog("002", plainLog(WINDOW_START + 2_000, captureId = "frame-7"))
    writeLog("003", plainLog(WINDOW_END + 10_000, screenshot = "outside.png"))
    listOf("kept.png", "frame-7.webp", "outside.png", "orphan.png").forEach { File(session, it).writeText(it) }

    clip()

    assertEquals(listOf("frame-7.webp", "kept.png"), out.listFiles().orEmpty().map { it.name }.filter { !it.endsWith(".json") }.sorted())
    assertEquals("kept.png", File(out, "kept.png").readText())
  }

  @Test
  fun `event lines are kept by their time on the host clock, device-stamped ones converted with the clock samples`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    val attachment = """{"${AttachmentRef.MARKER_FIELD}":true,"path":"attachments/in.wav","mimeType":"audio/wav","sizeBytes":3}"""
    // The network capture is the session's root `network.ndjson`; it keeps no line from before the window.
    writeLines(
      "network.ndjson",
      """{"timestampMs":${T + 9_000},"url":"before","body":{"blobPath":"blobs/before.bin"}}""",
      """{"timestampMs":${WINDOW_START + 2_000},"url":"inside","body":{"blobPath":"blobs/inside.bin"}}""",
      """{"timestampMs":${WINDOW_END + PADDING},"url":"padded","body":{"blobPath":"blobs/padded.bin"}}""",
      """{"timestampMs":${WINDOW_END + PADDING + 1},"url":"after","body":{"blobPath":"blobs/after.bin"}}""",
    )
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START + 200},"clock":"device","data":{"note":"before once converted, so carried in"}}""",
      """{"timeMs":${WINDOW_START + 2_000 + 2_000},"clock":"device","data":{"note":"inside","clip":$attachment}}""",
      """{"timeMs":${WINDOW_END + 2_000},"clock":"device","data":{"note":"converted into the window"}}""",
      """{"timeMs":${WINDOW_START + 3_000},"data":{"note":"a host stamp is not shifted"}}""",
      """{"timeMs":${WINDOW_END + 50_000},"data":{"note":"after"}}""",
    )
    writeLines("events/idle.ndjson", """{"timeMs":${T + 1},"data":{"note":"nothing in the window"}}""")
    listOf("before", "inside", "padded", "after").forEach { File(session, "blobs/$it.bin").apply { parentFile.mkdirs() }.writeText(it) }
    File(session, "attachments").mkdirs()
    File(session, "attachments/in.wav").writeText("wav")
    File(session, "attachments/out.wav").writeText("out")

    clip()

    assertEquals(
      listOf("inside", "padded"),
      File(out, "network.ndjson").readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("url").jsonPrimitive.content },
    )
    assertEquals(
      listOf("before once converted, so carried in", "inside", "converted into the window", "a host stamp is not shifted"),
      notes(File(out, "events/plugin.ndjson")),
    )
    assertEquals(listOf("nothing in the window"), notes(File(out, "events/idle.ndjson")))
    assertEquals(
      listOf("attachments/in.wav", "blobs/inside.bin", "blobs/padded.bin"),
      listOf("attachments/in.wav", "blobs/inside.bin", "blobs/padded.bin").filter { File(out, it).isFile },
    )
    assertEquals(
      emptyList(),
      listOf("blobs/before.bin", "blobs/after.bin", "attachments/out.wav").filter { File(out, it).exists() },
    )
  }

  @Test
  fun `device-stamped events fall back to the offset of the session's log anchors without clock samples`() {
    val skew = 3_000L
    writeLog(
      "001",
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
        toolName = "tapOn",
        successful = true,
        traceId = null,
        durationMs = 0,
        session = sessionId,
        timestamp = Instant.fromEpochMilliseconds(WINDOW_START + 1_000 - skew),
        clock = TrailblazeClockDomain.DEVICE,
        hostReceivedAt = Instant.fromEpochMilliseconds(WINDOW_START + 1_000),
      ),
    )
    // The anchor says the host is 3s ahead of the device, so a device stamp 2s before the window is 1s
    // inside it, and one 2s before its end is 1s past its padding.
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START - 2_000},"clock":"device","data":{"note":"inside once converted"}}""",
      """{"timeMs":${WINDOW_END - 2_000},"clock":"device","data":{"note":"outside once converted"}}""",
    )

    clip()

    assertEquals(listOf("inside once converted"), notes(File(out, "events/plugin.ndjson")))
  }

  @Test
  fun `root ndjson files are filtered by time, and the files derived from the whole session are left out`() {
    writeLines(
      "app_events.ndjson",
      """{"timeMs":${WINDOW_START - 5_000},"data":{"note":"before"}}""",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"note":"inside"}}""",
    )
    writeLines("visible-strings.ndjson", """{"timeMs":${WINDOW_START + 1_000},"data":{"note":"derived"}}""")

    clip()

    assertEquals(listOf("inside"), notes(File(out, "app_events.ndjson")))
    assertFalse(File(out, "visible-strings.ndjson").exists())
  }

  @Test
  fun `android device log lines are kept by the device clock, a line with no time going with the one before it`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    // The window on the device clock is T+11.5s..T+22.5s; on the host's it would be T+9.5s..T+20.5s.
    File(session, "device.log").writeText(
      """
      ${logcat(T + 10_000)} D Tag: before the window opens on the device clock
      ${logcat(T + 12_000)} D Tag: inside, first line
          at com.example.Frame.one(Frame.kt:1)
      ${logcat(T + 22_400)} E Tag: inside, at the padded end
          at com.example.Frame.two(Frame.kt:2)
      ${logcat(T + 23_000)} D Tag: after the window closes on the device clock
          at com.example.Frame.three(Frame.kt:3)
      """.trimIndent() + "\n",
    )

    clip()

    assertEquals(
      listOf(
        "inside, first line",
        "at com.example.Frame.one(Frame.kt:1)",
        "inside, at the padded end",
        "at com.example.Frame.two(Frame.kt:2)",
      ),
      File(out, "device.log").readLines().map { it.substringAfter("Tag: ", it.trim()) },
    )
  }

  @Test
  fun `a browser's console log, stamped in the host's local time with no offset, is kept by that time`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.WEB), T))
    fun webLine(epochMs: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
      .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
    File(session, "device.log").writeText(
      listOf(
        "${webLine(WINDOW_START - 1_000)} [log] before",
        "${webLine(WINDOW_START + 1_000)} [error] inside",
        "${webLine(WINDOW_END + 1_000)} [log] after",
      ).joinToString("\n", postfix = "\n"),
    )

    clip()

    assertEquals(listOf("inside"), File(out, "device.log").readLines().map { it.substringAfter("] ") })
  }

  @Test
  fun `a browser's console line in the hour a fall-back repeats is read as the moment after the line before it`() {
    val zone = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
    try {
      // The second 01:30 of 2026-11-01 in Los Angeles, an hour after the first.
      val secondHalfHour = java.time.Instant.parse("2026-11-01T09:30:00Z").toEpochMilli()
      // The session started at the first 01:29, so its first 01:30 is a pass an hour before the window.
      writeLog("001", status(started(TrailblazeDevicePlatform.WEB), secondHalfHour - 3_660_000))
      File(session, "device.log").writeText(
        listOf("01:30:00.000 [log] first", "01:59:00.000 [log] later", "01:30:00.000 [error] inside", "01:45:00.000 [log] after")
          .joinToString("\n", postfix = "\n") { "2026-11-01 $it" },
      )

      clipper().clip(session, secondHalfHour - 60_000, secondHalfHour + 60_000, out)

      assertEquals(listOf("inside"), File(out, "device.log").readLines().map { it.substringAfter("] ") })
    } finally {
      java.util.TimeZone.setDefault(zone)
    }
  }

  @Test
  fun `a browser session that starts in the hour a fall-back repeats reads its first console line in that hour`() {
    // The second 01:30 of 2026-11-01 in Los Angeles, and the second 01:40 of Lord Howe's half-hour fall-back.
    val cases = listOf(
      Triple("America/Los_Angeles", "2026-11-01T09:30:00Z", "2026-11-01 01:30:00.000"),
      Triple("Australia/Lord_Howe", "2027-04-03T15:10:00Z", "2027-04-04 01:40:00.000"),
    )
    val zone = java.util.TimeZone.getDefault()
    try {
      for ((name, moment, stamp) in cases) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(name))
        session.deleteRecursively()
        session.mkdirs()
        out.deleteRecursively()
        val atMs = java.time.Instant.parse(moment).toEpochMilli()
        // The session started a minute before the line.
        writeLog("001", status(started(TrailblazeDevicePlatform.WEB), atMs - 60_000))
        File(session, "device.log").writeText("$stamp [error] inside\n")

        clipper().clip(session, atMs - 60_000, atMs + 60_000, out)

        assertEquals(listOf("inside"), File(out, "device.log").readLines().map { it.substringAfter("] ") }, name)
      }
    } finally {
      java.util.TimeZone.setDefault(zone)
    }
  }

  @Test
  fun `an ios device log is on the host clock and is not shifted`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.IOS), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    File(session, "device.log").writeText(
      listOf(
        "${iosLine(WINDOW_START - 1_000)}  App[1]: before",
        "${iosLine(WINDOW_START + 1_000)}  App[1]: inside",
        "${iosLine(WINDOW_END + 1_000)}  App[1]: after",
      ).joinToString("\n", postfix = "\n"),
    )

    clip()

    assertEquals(listOf("inside"), File(out, "device.log").readLines().map { it.substringAfter("App[1]: ") })
  }

  @Test
  fun `trace spans are kept when they overlap the window on the host clock, device spans converted, and untimed events always`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    fun span(name: String, startMs: Long, durMs: Long = 5, device: Boolean = false): String {
      val clock = if (device) ",\"clock\":\"device\"" else ""
      return """{"name":"$name","ph":"X","ts":${startMs * 1000},"dur":${durMs * 1000}$clock}"""
    }
    File(session, "trace.json").writeText(
      "[" + listOf(
        """{"name":"process_name","ph":"M","args":{"name":"host"}}""",
        span("before", WINDOW_START - 5_000),
        // A tool span that started before the window and ran into it parents the spans inside.
        span("running into", WINDOW_START - 5_000, durMs = 5_000),
        span("inside", WINDOW_START + 1_000),
        span("device inside", WINDOW_START + 1_000 + 2_000, device = true),
        span("device early", WINDOW_START - 1_000 + 2_000, device = true),
        span("after", WINDOW_END + 5_000),
      ).joinToString(",") + "]",
    )

    clip()

    val names = Json.parseToJsonElement(File(out, "trace.json").readText()).jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    assertEquals(listOf("process_name", "running into", "inside", "device inside"), names)
  }

  @Test
  fun `a recording is cut to the window at the original's frame-to-clock scale`() {
    // A 10s span of the session whose file holds 5s of footage: file time runs at half of clock time.
    writeCaptureMetadata(CaptureArtifact(File(session, "video.mp4"), CaptureType.VIDEO, startTimestampMs = T, endTimestampMs = T + 10_000))
    File(session, "video.mp4").writeText("original")
    val cuts = mutableListOf<Triple<String, Long, Long>>()
    val clipper = clipper(
      sliceVideo = { source, offsetMs, durationMs, output ->
        cuts += Triple(source.name, offsetMs, durationMs)
        output.writeText("clipped")
        true
      },
      videoDurationMs = { file -> if (file.parentFile == out) 2_000L else 5_000L },
    )

    // Window T+3.5s..T+6.5s, padded to T+3s..T+7s.
    clipper.clip(session, T + 3_500, T + 6_500, out)

    assertEquals(listOf(Triple("video.mp4", 1_500L, 2_000L)), cuts)
    val video = readCaptureMetadata().artifacts.single()
    assertEquals(T + 3_000, video.startTimestampMs)
    assertEquals(T + 7_000, video.endTimestampMs)
    assertEquals("clipped", File(out, "video.mp4").readText())
    // Time 0 of the new file is the new start: the original's file time at that clock time.
    val originalFileTimeAtNewStartMs = (video.startTimestampMs - T) * 5_000 / 10_000
    assertEquals(1_500, originalFileTimeAtNewStartMs)
    // And the new file's span is its own length on the same scale.
    assertEquals(2_000 * 10_000 / 5_000, video.endTimestampMs!! - video.startTimestampMs)
  }

  @Test
  fun `a recording with no end is measured by its file, and a cut that fails or misses the window is left out`() {
    writeCaptureMetadata(
      CaptureArtifact(File(session, "video.mp4"), CaptureType.VIDEO, startTimestampMs = T, endTimestampMs = null),
      CaptureArtifact(File(session, "video-late.webm"), CaptureType.VIDEO_WEBM, startTimestampMs = T + 100_000, endTimestampMs = T + 110_000),
      CaptureArtifact(File(session, "video-broken.webm"), CaptureType.VIDEO_WEBM, startTimestampMs = T, endTimestampMs = T + 30_000),
    )
    listOf("video.mp4", "video-late.webm", "video-broken.webm").forEach { File(session, it).writeText(it) }
    val cuts = mutableListOf<String>()
    val clipper = clipper(
      sliceVideo = { source, offsetMs, durationMs, output ->
        cuts += "${source.name}@$offsetMs+$durationMs"
        if (source.name == "video-broken.webm") false else output.writeText("clipped").let { true }
      },
      videoDurationMs = { file -> if (file.parentFile == out) 3_000L else 30_000L },
    )

    clipper.clip(session, WINDOW_START, WINDOW_END, out)

    // video.mp4 has no end, so its 30s file spans T..T+30s: the 1:1 scale puts its cut at 9.5s.
    assertEquals(listOf("video.mp4@9500+11000", "video-broken.webm@9500+11000"), cuts)
    val video = readCaptureMetadata().artifacts.single()
    assertEquals("video.mp4", video.filename)
    assertEquals(T + 9_500, video.startTimestampMs)
    assertEquals(T + 9_500 + 3_000, video.endTimestampMs)
    assertFalse(File(out, "video-broken.webm").exists())
    assertFalse(File(out, "video-late.webm").exists())
  }

  @Test
  fun `a recording that links to a file outside the session is neither probed nor cut`() {
    val outside = tmp.newFolder("outside").apply { File(this, "other.mp4").writeText("someone else's footage") }
    java.nio.file.Files.createSymbolicLink(File(session, "video.mp4").toPath(), File(outside, "other.mp4").toPath())
    writeCaptureMetadata(CaptureArtifact(File(session, "video.mp4"), CaptureType.VIDEO, startTimestampMs = T, endTimestampMs = T + 30_000))

    clip()

    assertFalse(File(out, "video.mp4").exists())
  }

  @Test
  fun `a device log that links to a file outside the session is left out`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    val outside = tmp.newFolder("outside").apply { File(this, "other.log").writeText(logcat(WINDOW_START + 1_000) + " someone else's log\n") }
    java.nio.file.Files.createSymbolicLink(File(session, "device.log").toPath(), File(outside, "other.log").toPath())

    clip()

    assertFalse(File(out, "device.log").exists())
  }

  @Test
  fun `capture metadata that links to another session's is not read`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeLines("device.log", logcat(WINDOW_START + 1_000) + " inside")
    val outside = tmp.newFolder("outside")
    CaptureMetadata.write(outside, listOf(CaptureArtifact(File(outside, "device.log"), CaptureType.LOGCAT, startTimestampMs = T, endTimestampMs = T + 60_000, deviceName = "buyer")))
    java.nio.file.Files.createSymbolicLink(File(session, CaptureMetadata.FILENAME).toPath(), File(outside, CaptureMetadata.FILENAME).toPath())

    clip()

    assertTrue(File(out, "device.log").exists())
    assertFalse(File(out, CaptureMetadata.FILENAME).exists())
  }

  @Test
  fun `a recording whose length can't be measured is left out rather than cut on a guessed scale`() {
    writeCaptureMetadata(CaptureArtifact(File(session, "video.mp4"), CaptureType.VIDEO, startTimestampMs = T, endTimestampMs = T + 30_000))
    File(session, "video.mp4").writeText("original")

    clip(clipper(videoDurationMs = { null }))

    assertFalse(File(out, "video.mp4").exists())
  }

  @Test
  fun `a logcat artifact is clamped to the window on the device clock, and an artifact whose file was left out is dropped`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    File(session, "device.log").writeText("${logcat(T + 12_000)} D Tag: inside\n")
    File(session, "memory.csv").writeText("samples")
    writeCaptureMetadata(
      CaptureArtifact(File(session, "device.log"), CaptureType.LOGCAT, startTimestampMs = T, endTimestampMs = T + 60_000),
      CaptureArtifact(File(session, "memory.csv"), CaptureType.MEMORY, startTimestampMs = T, endTimestampMs = T + 60_000),
    )

    clip()

    val artifact = readCaptureMetadata().artifacts.single()
    assertEquals("device.log", artifact.filename)
    assertEquals(WINDOW_START - PADDING + 2_000, artifact.startTimestampMs)
    assertEquals(WINDOW_END + PADDING + 2_000, artifact.endTimestampMs)
    assertFalse(File(out, "memory.csv").exists())
  }

  @Test
  fun `a span that runs past the window's edges is cut to them, on its own clock`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    // The device clock steps 2s ahead between the span's start and the window's.
    val stepped = listOf(
      DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5),
      DeviceClockSample(hostBeforeMs = WINDOW_START + 4_995, hostAfterMs = WINDOW_START + 5_005, deviceMs = WINDOW_START + 7_000),
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(DeviceClockSync(devices = listOf(DeviceClockTrack("device-1", samples = stepped)))))
    File(session, "trace.json").writeText(
      """[{"name":"host","ph":"X","ts":${(WINDOW_START - 5_000) * 1000},"dur":${30_000 * 1000}},""" +
        """{"name":"device","ph":"X","ts":${(T + 1_000) * 1000},"dur":${15_000 * 1000},"clock":"device"}]""",
    )

    clip()

    val trace = Json.parseToJsonElement(File(out, "trace.json").readText()).jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
    val span = { name: String -> trace.getValue(name).let { it.getValue("ts").jsonPrimitive.long to it.getValue("dur").jsonPrimitive.long } }
    val from = WINDOW_START - PADDING
    val to = WINDOW_END + PADDING
    assertEquals(from * 1000 to (to - from) * 1000, span("host"))
    // Still device work, starting at the device time past the step that reads as the window's start.
    assertEquals((from + 2_000) * 1000 to (T + 16_000 - from) * 1000, span("device"))
    assertEquals("device", trace.getValue("device").getValue("clock").jsonPrimitive.content)
  }

  @Test
  fun `each event stream keeps its last event before the window, stamped at the window's start`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeDeviceClock(deviceAheadMs = 2_000)
    // Streams that log once at launch, long before the pass, and one that logs through it.
    writeLines(
      "events/flags.ndjson",
      """{"timeMs":${T + 100},"data":{"note":"flags at launch"}}""",
      """{"timeMs":${T + 2_000 + 2_000},"clock":"device","data":{"note":"flags refreshed"}}""",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"note":"flags in the pass"}}""",
    )
    writeLines("events/app.ndjson", """{"timeMs":${T + 200},"data":{"note":"app info"}}""")

    clip()

    assertEquals(listOf("flags refreshed", "flags in the pass"), notes(File(out, "events/flags.ndjson")))
    assertEquals(listOf("app info"), notes(File(out, "events/app.ndjson")))
    val carried = Json.parseToJsonElement(File(out, "events/flags.ndjson").readLines().first()).jsonObject
    assertEquals(WINDOW_START - PADDING, carried.getValue("timeMs").jsonPrimitive.long)
    assertEquals(null, carried["clock"])
  }

  @Test
  fun `a network exchange or a crash from before the window is not carried into it`() {
    writeLines(
      "events/network.browser.ndjson",
      """{"timestampMs":${T + 100},"phase":"RESPONSE_END","statusCode":500,"blobPath":"blobs/old.json"}""",
    )
    writeLines("events/crash.ndjson", """{"timeMs":${T + 100},"data":{"note":"crashed in an earlier pass"}}""")
    File(session, "blobs").mkdirs()
    File(session, "blobs/old.json").writeText("{}")

    clip()

    assertFalse(File(out, "events/network.browser.ndjson").exists())
    assertFalse(File(out, "events/crash.ndjson").exists())
    assertFalse(File(out, "blobs/old.json").exists())
  }

  @Test
  fun `an event with no time is kept, with the files it refers to`() {
    writeLines("events/plugin.ndjson", """{"data":{"note":"untimed","blob":{"blobPath":"blobs/untimed.bin"}}}""")
    File(session, "blobs").mkdirs()
    File(session, "blobs/untimed.bin").writeText("untimed")

    clip()

    assertEquals(listOf("untimed"), notes(File(out, "events/plugin.ndjson")))
    assertEquals("untimed", File(out, "blobs/untimed.bin").readText())
  }

  @Test
  fun `a file an event refers to is copied only from inside the session`() {
    val outside = tmp.newFolder("outside").apply { File(this, "secret.bin").writeText("secret") }
    java.nio.file.Files.createSymbolicLink(File(session, "link").toPath(), outside.toPath())
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"note":"linked","body":{"blobPath":"link/secret.bin"}}}""",
    )

    clip()

    assertFalse(File(out, "link/secret.bin").exists())
  }

  @Test
  fun `a reference to a file the clip itself filters or leaves out does not bring it back whole`() {
    writeLines("network.ndjson", """{"timestampMs":${T + 100},"url":"earlier pass"}""")
    writeLines("recording.trail.yaml", "whole session")
    File(session, "take.wav").writeText("wav")
    File(session, "events").mkdirs()
    File(session, "events/voice.wav").writeText("events wav")
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"body":{"blobPath":"network.ndjson"}}}""",
      """{"timeMs":${WINDOW_START + 2_000},"data":{"body":{"blobPath":"blobs/../recording.trail.yaml"}}}""",
      """{"timeMs":${WINDOW_START + 3_000},"data":{"clip":{"${AttachmentRef.MARKER_FIELD}":true,"path":"take.wav"}}}""",
      """{"timeMs":${WINDOW_START + 4_000},"data":{"clip":{"${AttachmentRef.MARKER_FIELD}":true,"path":"events/voice.wav"}}}""",
    )

    clip()

    assertFalse(File(out, "network.ndjson").exists())
    assertFalse(File(out, "recording.trail.yaml").exists())
    assertEquals("wav", File(out, "take.wav").readText())
    assertEquals("events wav", File(out, "events/voice.wav").readText(), "only a stream under events/ is the clip's own")
  }

  @Test
  fun `a stream, trace or log that links to a file outside the session is left out`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    val outside = tmp.newFolder("outside")
    val untimed = """{"data":{"note":"outside"}}""" + "\n"
    File(outside, "plugin.ndjson").writeText(untimed)
    File(outside, "network.ndjson").writeText(untimed)
    File(outside, "trace.json").writeText("""[{"name":"outside","ph":"M"}]""")
    File(outside, "002_TrailblazeSessionStatusChangeLog.json").writeText(File(session, "001_TrailblazeSessionStatusChangeLog.json").readText())
    File(outside, "events").mkdirs()
    File(outside, "events/flags.ndjson").writeText(untimed)
    File(session, "events").mkdirs()
    val link = { name: String, target: String -> java.nio.file.Files.createSymbolicLink(File(session, name).toPath(), File(outside, target).toPath()) }
    link("events/plugin.ndjson", "plugin.ndjson")
    link("network.ndjson", "network.ndjson")
    link("trace.json", "trace.json")
    link("002_TrailblazeSessionStatusChangeLog.json", "002_TrailblazeSessionStatusChangeLog.json")
    val linkedDir = tmp.newFolder("linked-dir-session")
    java.nio.file.Files.createSymbolicLink(File(linkedDir, "events").toPath(), File(outside, "events").toPath())

    clip()
    SessionClipper(paddingMs = PADDING).clip(linkedDir, WINDOW_START, WINDOW_END, File(tmp.root, "out-linked-dir"))

    assertEquals(listOf("001_TrailblazeSessionStatusChangeLog.json"), out.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".json") })
    assertFalse(File(out, "events/plugin.ndjson").exists())
    assertFalse(File(out, "network.ndjson").exists())
    assertFalse(File(tmp.root, "out-linked-dir/events/flags.ndjson").exists())
  }

  @Test
  fun `a line whose envelope time isn't a number is placed by its legacy time`() {
    writeLines(
      "network.ndjson",
      """{"timeMs":null,"timestampMs":${T + 1_000},"url":"before"}""",
      """{"timeMs":"soon","timestampMs":${WINDOW_START + 1_000},"url":"inside"}""",
    )

    clip()

    assertEquals(
      listOf("inside"),
      File(out, "network.ndjson").readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("url").jsonPrimitive.content },
    )
  }

  @Test
  fun `a reference no file system can name is skipped, and the rest of the clip still made`() {
    File(session, "blobs").mkdirs()
    File(session, "blobs/body.json").writeText("{}")
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"body":{"blobPath":"blobs/bad\u0000.json"}}}""",
      """{"timeMs":${WINDOW_START + 2_000},"data":{"body":{"blobPath":"blobs/body.json"}}}""",
    )

    clip()

    assertEquals("{}", File(out, "blobs/body.json").readText())
  }

  @Test
  fun `a file an event refers to through a link inside the session is copied to the path it refers to`() {
    File(session, "blobs").mkdirs()
    File(session, "blobs/take.wav").writeText("wav")
    File(session, "attachments").mkdirs()
    java.nio.file.Files.createSymbolicLink(File(session, "attachments/take.wav").toPath(), File(session, "blobs/take.wav").toPath())
    writeLines(
      "events/plugin.ndjson",
      """{"timeMs":${WINDOW_START + 1_000},"data":{"clip":{"${AttachmentRef.MARKER_FIELD}":true,"path":"attachments/take.wav"}}}""",
    )

    clip()

    assertEquals("wav", File(out, "attachments/take.wav").readText())
  }

  @Test
  fun `a device's stream falls back to that device's log anchors, not the lowest of every device's`() {
    // Without clock samples, each device's tool logs say how far its clock is from the host's.
    fun anchor(device: String, hostMs: Long, deviceAheadMs: Long) = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
      toolName = "tapOn",
      successful = true,
      traceId = null,
      durationMs = 0,
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(hostMs + deviceAheadMs),
      clock = TrailblazeClockDomain.DEVICE,
      hostReceivedAt = Instant.fromEpochMilliseconds(hostMs),
      deviceName = device,
    )
    writeLog("001", anchor("seller", WINDOW_START + 1_000, deviceAheadMs = 5_000))
    writeLog("002", anchor("buyer", WINDOW_START + 2_000, deviceAheadMs = -2_000))
    writeLines(
      "events/plugin.buyer.ndjson",
      """{"timeMs":${WINDOW_START + 1_000 - 2_000},"clock":"device","data":{"note":"buyer inside"}}""",
    )

    clip()

    assertEquals(listOf("buyer inside"), notes(File(out, "events/plugin.buyer.ndjson")))
  }

  @Test
  fun `a device's root network capture is put on that device's clock`() {
    val sample = { aheadMs: Long -> DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + aheadMs) }
    val sync = DeviceClockSync(
      devices = listOf(
        DeviceClockTrack("emulator-1", samples = listOf(sample(0)), deviceName = "seller"),
        DeviceClockTrack("emulator-2", samples = listOf(sample(3_000)), deviceName = "buyer"),
      ),
      startDeviceId = "emulator-1",
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(sync))
    writeLines(
      "network.buyer.ndjson",
      """{"timestampMs":${WINDOW_END - 100 + 3_000},"clock":"device","url":"buyer inside"}""",
    )

    clip()

    assertEquals(
      listOf("buyer inside"),
      File(out, "network.buyer.ndjson").readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("url").jsonPrimitive.content },
    )
  }

  @Test
  fun `a companion device's logcat is clamped on that device's clock, found through the capture metadata`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    val sample = { aheadMs: Long -> DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + aheadMs) }
    val sync = DeviceClockSync(
      devices = listOf(
        DeviceClockTrack("emulator-1", samples = listOf(sample(0)), deviceName = "seller"),
        DeviceClockTrack("emulator-2", samples = listOf(sample(3_000)), deviceName = "buyer"),
      ),
      startDeviceId = "emulator-1",
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(sync))
    // A second binding of `buyer` takes the `-2` name, so only the metadata says whose log it is.
    File(session, "device-buyer-2.log").writeText("${logcat(WINDOW_START + 1_000 + 3_000)} D Tag: inside\n")
    writeCaptureMetadata(
      CaptureArtifact(File(session, "device-buyer-2.log"), CaptureType.LOGCAT, startTimestampMs = T, endTimestampMs = T + 60_000, deviceName = "buyer"),
    )

    clip()

    val artifact = readCaptureMetadata().artifacts.single()
    assertEquals(WINDOW_START - PADDING + 3_000, artifact.startTimestampMs)
    assertEquals(WINDOW_END + PADDING + 3_000, artifact.endTimestampMs)
    assertEquals(1, File(out, "device-buyer-2.log").readLines().size)
  }

  @Test
  fun `a companion with no clock samples is clamped on its own log anchors, not the start device's clock`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    writeLog("002", deviceAnchor("seller", WINDOW_START + 1_000, deviceAheadMs = 5_000))
    writeLog("003", deviceAnchor("buyer", WINDOW_START + 1_000, deviceAheadMs = -2_000))
    val sample = DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + 5_000)
    val sync = DeviceClockSync(
      devices = listOf(DeviceClockTrack("emulator-1", samples = listOf(sample), deviceName = "seller"), DeviceClockTrack("emulator-2", deviceName = "buyer")),
      startDeviceId = "emulator-1",
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(sync))
    File(session, "device-buyer.log").writeText("${logcat(WINDOW_START + 1_000 - 2_000)} D Tag: inside\n")
    writeCaptureMetadata(
      CaptureArtifact(File(session, "device-buyer.log"), CaptureType.LOGCAT, startTimestampMs = T, endTimestampMs = T + 60_000, deviceName = "buyer"),
    )

    clip()

    assertEquals(WINDOW_START - PADDING - 2_000, readCaptureMetadata().artifacts.single().startTimestampMs)
    assertEquals(1, File(out, "device-buyer.log").readLines().size)
  }

  @Test
  fun `a companion name bound to a second device is clamped on that device's clock, not the first's`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    val sample = { aheadMs: Long -> DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + aheadMs) }
    val sync = DeviceClockSync(
      devices = listOf(
        DeviceClockTrack("emulator-1", samples = listOf(sample(0)), deviceName = "seller"),
        DeviceClockTrack("emulator-2", samples = listOf(sample(3_000)), deviceName = "buyer"),
        DeviceClockTrack("emulator-3", samples = listOf(sample(-1_000)), deviceName = "buyer"),
      ),
      startDeviceId = "emulator-1",
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(sync))
    File(session, "device-buyer-2.log").writeText("${logcat(WINDOW_START + 1_000 - 1_000)} D Tag: inside\n")
    writeCaptureMetadata(
      CaptureArtifact(
        File(session, "device-buyer-2.log"), CaptureType.LOGCAT, startTimestampMs = T, endTimestampMs = T + 60_000,
        deviceName = "buyer", deviceId = "emulator-3",
      ),
    )

    clip()

    assertEquals(WINDOW_START - PADDING - 1_000, readCaptureMetadata().artifacts.single().startTimestampMs)
  }

  @Test
  fun `a companion log named only by its file is clamped on the clock of the device whose name the file spells`() {
    writeLog("001", status(started(TrailblazeDevicePlatform.ANDROID), T))
    val sample = { aheadMs: Long -> DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + aheadMs) }
    val sync = DeviceClockSync(
      devices = listOf(
        DeviceClockTrack("emulator-1", samples = listOf(sample(0)), deviceName = "seller"),
        DeviceClockTrack("emulator-2", samples = listOf(sample(3_000)), deviceName = "buyer display"),
      ),
      startDeviceId = "emulator-1",
    )
    File(session, DeviceClockSync.FILE_NAME).writeText(DeviceClockSync.encode(sync))
    File(session, "device-buyer_display.log").writeText(
      "${logcat(WINDOW_START - PADDING + 1_000)} D Tag: before\n${logcat(WINDOW_END + PADDING + 2_000)} D Tag: inside\n",
    )

    clip()

    assertEquals(listOf("inside"), File(out, "device-buyer_display.log").readLines().map { it.substringAfterLast(' ') })
  }

  /** A tool log [device] stamped on its own clock, [deviceAheadMs] off the host's, that the host got at [hostMs]. */
  private fun deviceAnchor(device: String, hostMs: Long, deviceAheadMs: Long) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
    toolName = "tapOn",
    successful = true,
    traceId = null,
    durationMs = 0,
    session = sessionId,
    timestamp = Instant.fromEpochMilliseconds(hostMs + deviceAheadMs),
    clock = TrailblazeClockDomain.DEVICE,
    hostReceivedAt = Instant.fromEpochMilliseconds(hostMs),
    deviceName = device,
  )

  @Test
  fun `metadata with no time in it is copied whole, and what the whole session made is left out`() {
    writeDeviceClock(deviceAheadMs = 2_000)
    File(session, "recording.trail.yaml").writeText("trail: []")
    File(session, "in-process-scripted-tools").mkdirs()
    File(session, "in-process-scripted-tools/tool.js").writeText("// tool")
    File(session, "inline-script-tools").mkdirs()
    File(session, "inline-script-tools/inline.js").writeText("// inline")
    File(session, "capture_debug.txt").writeText("debug")
    File(session, "session_result.json").writeText("{}")
    File(session, "notes.txt").writeText("notes")
    File(session, "other").mkdirs()
    File(session, "other/file.txt").writeText("other")

    clip()

    assertEquals(
      listOf("in-process-scripted-tools", "inline-script-tools", DeviceClockSync.FILE_NAME).sorted(),
      out.listFiles().orEmpty().map { it.name }.sorted(),
    )
    assertEquals("// tool", File(out, "in-process-scripted-tools/tool.js").readText())
    assertEquals("// inline", File(out, "inline-script-tools/inline.js").readText())
    assertEquals(File(session, DeviceClockSync.FILE_NAME).readText(), File(out, DeviceClockSync.FILE_NAME).readText())
  }

  @Test
  fun `a device's own tool log in the window is named by the handover before it, even one before the window`() {
    fun tool(name: String, hostMs: Long, device: String?, deviceStamped: Boolean, successful: Boolean = true) = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(toolName = name),
      toolName = name,
      successful = successful,
      traceId = null,
      durationMs = 50,
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(hostMs),
      clock = if (deviceStamped) TrailblazeClockDomain.DEVICE else null,
      hostReceivedAt = if (deviceStamped) Instant.fromEpochMilliseconds(hostMs + 50) else null,
      deviceName = device,
    )
    writeLog("001", tool("switchDevice", T + 1_000, "buyer", deviceStamped = false))
    writeLog("002", tool("switchDevice", T + 2_000, "seller", deviceStamped = false, successful = false))
    writeLog("003", tool("tapOn", WINDOW_START + 1_000, null, deviceStamped = true))
    writeLog("004", tool("switchDevice", WINDOW_START + 3_000, "seller", deviceStamped = false))
    writeLog("005", tool("tapOn", WINDOW_START + 5_000, null, deviceStamped = true))

    clip()

    val devices = listOf("003", "005").map { (readLog(out, "${it}_TrailblazeToolLog.json") as TrailblazeLog.TrailblazeToolLog).deviceName }
    assertEquals(listOf("buyer", "seller"), devices)
  }

  @Test
  fun `a window that ends before it starts and an output directory that exists are refused`() {
    assertFailsWith<IllegalArgumentException> { clipper().clip(session, WINDOW_END, WINDOW_START, out) }
    out.mkdirs()
    assertFailsWith<IllegalArgumentException> { clipper().clip(session, WINDOW_START, WINDOW_END, out) }
  }

  private fun writeLog(prefix: String, log: TrailblazeLog) {
    File(session, "${prefix}_${log::class.simpleName}.json")
      .writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
  }

  private fun plainLog(
    atMs: Long,
    screenshot: String? = null,
    captureId: String? = null,
    clock: TrailblazeClockDomain? = null,
    hostReceivedAt: Long? = null,
  ) = TrailblazeLog.AgentDriverLog(
    viewHierarchy = null,
    screenshotFile = screenshot,
    action = AgentDriverAction.BackPress,
    captureId = captureId,
    durationMs = 10,
    session = sessionId,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    deviceHeight = 1920,
    deviceWidth = 1080,
    clock = clock,
    hostReceivedAt = hostReceivedAt?.let { Instant.fromEpochMilliseconds(it) },
  )

  private fun status(status: SessionStatus, atMs: Long, clock: TrailblazeClockDomain? = null) =
    TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = status,
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(atMs),
      clock = clock,
    )

  private fun started(platform: TrailblazeDevicePlatform): SessionStatus.Started {
    val deviceId = TrailblazeDeviceId("device-1", platform)
    return SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "method",
      testClassName = "class",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = deviceId,
        trailblazeDriverType = if (platform == TrailblazeDevicePlatform.ANDROID) {
          TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
        } else {
          TrailblazeDriverType.IOS_HOST
        },
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      trailblazeDeviceId = deviceId,
    )
  }

  /** A device whose clock runs [deviceAheadMs] ahead of the host's, measured once at the session's start. */
  private fun writeDeviceClock(deviceAheadMs: Long) {
    val sample = DeviceClockSample(hostBeforeMs = T, hostAfterMs = T + 10, deviceMs = T + 5 + deviceAheadMs)
    File(session, DeviceClockSync.FILE_NAME)
      .writeText(DeviceClockSync.encode(DeviceClockSync(devices = listOf(DeviceClockTrack("device-1", samples = listOf(sample))))))
  }

  private fun writeLines(relativePath: String, vararg lines: String) {
    File(session, relativePath).apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
  }

  private fun writeCaptureMetadata(vararg artifacts: CaptureArtifact) = CaptureMetadata.write(session, artifacts.toList())

  private fun readCaptureMetadata(): CaptureMetadata =
    TrailblazeJsonInstance.decodeFromString(CaptureMetadata.serializer(), File(out, CaptureMetadata.FILENAME).readText())

  private fun logNames(dir: File) = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith("Log.json") }.sorted()

  private fun readLog(dir: File, name: String) =
    TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), File(dir, name).readText())

  private fun notes(file: File) =
    file.readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("data").jsonObject.getValue("note").jsonPrimitive.content }

  private fun logcat(epochMs: Long) = "%d.%03d  1234  1234".format(epochMs / 1000, epochMs % 1000)

  private fun iosLine(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxx")
      .format(java.time.Instant.ofEpochMilli(epochMs).atOffset(java.time.ZoneOffset.UTC))

  private companion object {
    const val T = 1_700_000_000_000L
    const val WINDOW_START = T + 10_000
    const val WINDOW_END = T + 20_000
    const val PADDING = 500L
  }
}

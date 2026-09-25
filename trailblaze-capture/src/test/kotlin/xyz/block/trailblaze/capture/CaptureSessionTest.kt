package xyz.block.trailblaze.capture

import java.io.File
import java.nio.file.Files
import xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture
import xyz.block.trailblaze.capture.logcat.IosLogCapture
import xyz.block.trailblaze.capture.memory.MemoryCapture
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.IosVideoCapture
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.events.SessionEvents
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the cross-product of capture flags × platforms in [CaptureSession.fromOptions].
 *
 * Uses reflection to peek at the streams list since it's a private val — happy to switch to
 * an exposed test seam if this becomes a maintenance problem.
 */
class CaptureSessionTest {

  @Test
  fun `stopAll indexes a crash only after the app-scoped log stream stops`() {
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      val logStream = FakeLogStream()
      val session = CaptureSession(
        streams = listOf(logStream),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", "com.example.store")
      val crashEvents = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson")
      assertFalse(crashEvents.exists())

      session.stopAll()

      assertTrue(logStream.stopped)
      assertTrue(crashEvents.isFile)
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `stopAll ignores crash lines from an unscoped log stream`() {
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      val session = CaptureSession(
        streams = listOf(FakeLogStream(isAppScoped = false)),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", appId = null)
      session.stopAll()

      assertFalse(File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").exists())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `the recording is listed in capture_metadata so the report can find it`() {
    // The report reads `capture_metadata.json`, not the directory: a recording on disk that isn't
    // listed there is invisible to it.
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      val stream = FakeRecordingStream(sessionDir)
      val session = CaptureSession(
        streams = listOf(stream),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", "com.example.store")
      val artifacts = session.stopAll()

      assertEquals(listOf(CaptureType.VIDEO_WEBM), artifacts.map { it.type })
      val metadata = File(sessionDir, "capture_metadata.json").readText()
      assertContains(metadata, "VIDEO_WEBM")
      assertContains(metadata, "video.webm")
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a stream that captured nothing writes no capture_metadata`() {
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      val stream = FakeRecordingStream(sessionDir, recorded = false)
      val session = CaptureSession(
        streams = listOf(stream),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", "com.example.store")

      assertTrue(session.stopAll().isEmpty())
      assertFalse(File(sessionDir, "capture_metadata.json").exists())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  private fun streamsOf(session: CaptureSession): List<CaptureStream> {
    val field = CaptureSession::class.java.getDeclaredField("streams").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field.get(session) as List<CaptureStream>
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureLogcat × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureLogcat true on ANDROID adds AndroidLogcatCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is AndroidLogcatCapture })
    assertTrue(streams.none { it is IosLogCapture })
  }

  @Test
  fun `captureLogcat true on IOS does NOT add AndroidLogcatCapture`() {
    val session = CaptureSession.fromOptions(
      // Explicitly disable iOS logs and memory (both on by default) to isolate the logcat-on-iOS case.
      CaptureOptions(captureVideo = false, captureLogcat = true, captureIosLogs = false, captureMemory = false),
      TrailblazeDevicePlatform.IOS,
    )
    // captureLogcat is Android-only — on iOS with no other streams enabled, fromOptions
    // should refuse to build a session at all.
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureIosLogs × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureIosLogs true on IOS adds IosLogCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureIosLogs = true),
      TrailblazeDevicePlatform.IOS,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is IosLogCapture })
    assertTrue(streams.none { it is AndroidLogcatCapture })
  }

  @Test
  fun `captureIosLogs true on ANDROID does NOT add IosLogCapture`() {
    val session = CaptureSession.fromOptions(
      // Explicitly disable logcat (now on by default) to isolate the iosLogs-on-Android case.
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = true, captureMemory = false),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureMemory × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureMemory true on ANDROID adds MemoryCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false, captureMemory = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    assertTrue(streamsOf(session).single() is MemoryCapture)
  }

  @Test
  fun `captureMemory true on IOS adds MemoryCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false, captureMemory = true),
      TrailblazeDevicePlatform.IOS,
    )
    assertNotNull(session)
    assertTrue(streamsOf(session).single() is MemoryCapture)
  }

  @Test
  fun `captureMemory has no stream on WEB`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false, captureMemory = true),
      TrailblazeDevicePlatform.WEB,
    )
    assertNull(session, "a browser has no app process to sample")
  }

  @Test
  fun `default options on ANDROID sample memory alongside logcat`() {
    val session = CaptureSession.fromOptions(CaptureOptions(), TrailblazeDevicePlatform.ANDROID)
    assertNotNull(session)
    val streams = streamsOf(session).map { it::class.simpleName!! }
    assertContains(streams, "AndroidLogcatCapture")
    assertContains(streams, "MemoryCapture")
  }

  @Test
  fun `onToolCall reaches only the streams that sample around tool calls`() {
    val aware = RecordingToolCallStream()
    val plain = object : CaptureStream {
      override val type = CaptureType.VIDEO
      override fun start(sessionDir: java.io.File, deviceId: String, appId: String?) = Unit
      override fun stop(options: CaptureOptions): CaptureArtifact? = null
    }
    val session = CaptureSession(listOf(plain, aware), CaptureOptions())
    session.onToolCall(ToolCallPhase.BEFORE, "tapOnElement", "tool-abc")
    session.onToolCall(ToolCallPhase.AFTER, "tapOnElement", "tool-abc")
    assertEquals(
      listOf("BEFORE tapOnElement tool-abc", "AFTER tapOnElement tool-abc"),
      aware.calls,
      "the dispatch's trace reaches the stream, or a row it writes cannot be joined to the tool's logs",
    )
  }

  @Test
  fun `a stream that throws on a tool call does not stop the others from hearing it`() {
    val throwing = object : CaptureStream, ToolCallAwareCaptureStream {
      override val type = CaptureType.MEMORY
      override fun start(sessionDir: java.io.File, deviceId: String, appId: String?) = Unit
      override fun stop(options: CaptureOptions): CaptureArtifact? = null
      override fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String?): Unit = error("probe exploded")
    }
    val aware = RecordingToolCallStream()
    CaptureSession(listOf(throwing, aware), CaptureOptions()).onToolCall(ToolCallPhase.BEFORE, "launchApp")
    assertEquals(listOf("BEFORE launchApp null"), aware.calls)
  }

  private class RecordingToolCallStream : CaptureStream, ToolCallAwareCaptureStream {
    val calls = mutableListOf<String>()
    override val type = CaptureType.MEMORY
    override fun start(sessionDir: java.io.File, deviceId: String, appId: String?) = Unit
    override fun stop(options: CaptureOptions): CaptureArtifact? = null
    override fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String?) {
      calls += "${phase.name} $toolName $traceId"
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureVideo × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureVideo true on ANDROID adds AndroidVideoCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is AndroidVideoCapture })
  }

  @Test
  fun `captureVideo true on IOS adds IosVideoCapture`() {
    val session = CaptureSession.fromOptions(
      // Disable the log streams (now on by default) so this isolates the video-on-iOS case.
      CaptureOptions(captureVideo = true, captureLogcat = false, captureIosLogs = false),
      TrailblazeDevicePlatform.IOS,
    )
    assertNotNull(session)
    assertTrue(streamsOf(session).any { it is IosVideoCapture })
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Multiple flags + WEB / null platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `Android with video plus logcat enabled produces both streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session).map { it::class.simpleName!! }
    assertContains(streams, "AndroidVideoCapture")
    assertContains(streams, "AndroidLogcatCapture")
  }

  @Test
  fun `WEB platform with captureVideo on adds the screencast video stream only`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true, captureIosLogs = true),
      TrailblazeDevicePlatform.WEB,
    )
    // logcat / iOS logs don't apply to web; only the screencast video stream is wired. It records
    // from the live CDP screencast and falls back to Playwright's setRecordVideoDir recorder when
    // no feed is registered for the device (see WebScreencastVideoCapture).
    assertNotNull(session)
    val streams = streamsOf(session).map { it::class.simpleName!! }
    assertContains(streams, "WebScreencastVideoCapture")
    kotlin.test.assertEquals(1, streams.size, "WEB only registers the screencast video stream")
  }

  @Test
  fun `WEB platform with captureVideo off skips all streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = true, captureIosLogs = true),
      TrailblazeDevicePlatform.WEB,
    )
    assertNull(session)
  }

  @Test
  fun `null platform skips all platform-gated streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true),
      platform = null,
    )
    assertNull(session)
  }

  @Test
  fun `all flags off returns null regardless of platform`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false, captureMemory = false),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNull(session)
  }

  @Test
  fun `an empty recording is deleted and never listed as an artifact`() {
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      // A recorder's output file exists from the moment it is spawned, so a stream can hand back
      // a finalized-looking artifact with nothing in it. Listed, it becomes a clip the report
      // cannot decode and a dead file in the session zip.
      val session = CaptureSession(
        streams = listOf(FakeRecordingStream(sessionDir, empty = true)),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", appId = null)
      val artifacts = session.stopAll()

      assertTrue(artifacts.isEmpty(), "an empty recording is not an artifact")
      assertFalse(File(sessionDir, "video.webm").exists(), "the empty file is removed, not shipped")
      assertFalse(
        File(sessionDir, "capture_metadata.json").exists(),
        "a session whose only stream captured nothing writes no metadata",
      )
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a recording with bytes in it is listed and kept`() {
    val sessionDir = Files.createTempDirectory("capture-session-test").toFile()
    try {
      val session = CaptureSession(
        streams = listOf(FakeRecordingStream(sessionDir)),
        options = CaptureOptions.NONE,
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      session.startAll(sessionDir, "emulator-5554", appId = null)
      val artifacts = session.stopAll()

      assertEquals(1, artifacts.size, "a recording that captured frames survives the empty-file guard")
      assertTrue(File(sessionDir, "video.webm").isFile)
      assertContains(File(sessionDir, "capture_metadata.json").readText(), "video.webm")
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  private class FakeLogStream(
    override val isAppScoped: Boolean = true,
  ) : CaptureStream, AppScopedCaptureStream {
    override val type = CaptureType.LOGCAT
    private lateinit var outputFile: File
    var stopped = false
      private set

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      outputFile = File(sessionDir, "device.log").apply {
        writeText("1772846522.234  123  123 E AndroidRuntime: FATAL EXCEPTION: main")
      }
    }

    override fun stop(options: CaptureOptions): CaptureArtifact {
      stopped = true
      return CaptureArtifact(
        file = outputFile,
        type = type,
        startTimestampMs = 1772846522234L,
        endTimestampMs = 1772846522234L,
      )
    }
  }

  /** A video-shaped stream that writes a WebM recording, as Android does. */
  private class FakeRecordingStream(
    sessionDir: File,
    private val recorded: Boolean = true,
    /** Finalize the file but write no bytes to it — a recorder whose device fed it nothing. */
    private val empty: Boolean = false,
  ) : CaptureStream {
    override val type = CaptureType.VIDEO
    private val webm = File(sessionDir, "video.webm")

    override fun start(sessionDir: File, deviceId: String, appId: String?) = Unit

    override fun stop(options: CaptureOptions): CaptureArtifact? {
      if (!recorded) return null
      if (empty) webm.createNewFile() else webm.writeText("webm")
      return CaptureArtifact(
        file = webm,
        type = CaptureType.VIDEO_WEBM,
        startTimestampMs = 1772846522234L,
        endTimestampMs = 1772846525234L,
      )
    }
  }
}

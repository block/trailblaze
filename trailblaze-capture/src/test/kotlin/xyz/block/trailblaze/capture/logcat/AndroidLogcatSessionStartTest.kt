package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a session's own lines begin in a device log its recording started before the session. */
class AndroidLogcatSessionStartTest {

  private val dir = Files.createTempDirectory("logcat-session-start").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `a crash adb delivered after the mark was taken is still ahead of the session`() {
    val beforeMark = "1772846521.000  123  123 I MyApp : connecting\n"
    // Logged before the marker, but still in flight from adb when the mark was taken.
    val lateCrash = "1772846521.500  123  123 E AndroidRuntime: FATAL EXCEPTION: main\n"
    val marker = "1772846522.000  999  999 I Trailblaze: trailblaze-session-start-abc\n"
    val log = File(dir, "device.log").apply {
      writeText(beforeMark + lateCrash + marker + "1772846523.000  123  123 I MyApp : step 1\n")
    }

    assertEquals(
      (beforeMark + lateCrash).toByteArray().size.toLong(),
      AndroidLogcatCapture.markerLineOffset(log, from = beforeMark.toByteArray().size.toLong(), marker = "trailblaze-session-start-abc"),
    )
  }

  @Test
  fun `a log without the marker has no boundary of its own`() {
    val log = File(dir, "device.log").apply { writeText("1772846521.000  123  123 I MyApp : connecting\n") }

    assertNull(AndroidLogcatCapture.markerLineOffset(log, from = 0, marker = "trailblaze-session-start-abc"))
  }

  @Test
  fun `a stream that ended mid-line reopens from the last line it wrote in full`() {
    val log = File(dir, "device.log").apply {
      writeText(
        "1772846521.000  123  123 I MyApp : connecting\n" +
          "1772846522.250  123  123 I MyApp : still connecting\n" +
          // Cut off when the connection was replaced.
          "  --------- beginning of cra",
      )
    }

    assertEquals(1772846522250, AndroidLogcatCapture.lastEpochMs(log))
  }

  @Test
  fun `a stream that wrote nothing reopens from where it started`() {
    assertNull(AndroidLogcatCapture.lastEpochMs(File(dir, "device.log").apply { writeText("") }))
  }

  @Test
  fun `a marker the stream delivers is seen, and one it never delivers is not`() {
    val log = File(dir, "device.log").apply { writeText("1772846521.000  123  123 I MyApp : connecting\n") }
    val writer = Thread {
      Thread.sleep(100)
      log.appendText("1772846522.000  999  999 I Trailblaze: trailblaze-session-start-abc\n")
    }.apply { start() }

    assertTrue(AndroidLogcatCapture.awaitMarker(log, from = 0, marker = "trailblaze-session-start-abc", timeoutMs = 5_000))
    writer.join()
    assertFalse(AndroidLogcatCapture.awaitMarker(log, from = 0, marker = "trailblaze-session-start-xyz", timeoutMs = 100))
  }
}

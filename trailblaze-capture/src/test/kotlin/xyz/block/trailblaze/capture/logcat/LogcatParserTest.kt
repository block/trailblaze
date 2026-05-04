package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFails

class LogcatParserTest {

  @Test
  fun `isDeviceLogFile recognizes canonical and legacy filenames`() {
    assertTrue(LogcatParser.isDeviceLogFile("device.log"))
    assertTrue(LogcatParser.isDeviceLogFile("logcat.txt"))
    assertTrue(LogcatParser.isDeviceLogFile("session_logcat_2026.log"))
    assertTrue(LogcatParser.isDeviceLogFile("system_log_dump.txt"))
    assertTrue(LogcatParser.isDeviceLogFile("DEVICE.LOG")) // lowercased internally
    assertEquals(false, LogcatParser.isDeviceLogFile("video.mp4"))
    assertEquals(false, LogcatParser.isDeviceLogFile("metadata.json"))
  }

  @Test
  fun `parseLine extracts Android epoch milliseconds`() {
    val line = "1772846521.234  5432  5432 D MyTag : hello"
    val parsed = LogcatParser.parseLine(line)
    assertEquals(1772846521234L, parsed.epochMs)
    assertEquals(line, parsed.text)
  }

  @Test
  fun `parseLine handles indented Android lines (leading whitespace)`() {
    val line = "    1772846521.234  5432  5432 D MyTag : continued line"
    val parsed = LogcatParser.parseLine(line)
    assertEquals(1772846521234L, parsed.epochMs)
  }

  @Test
  fun `parseLine extracts iOS datetime with 6-digit fraction`() {
    val line = "2026-03-10 14:23:45.678901-0700  MyApp[12345]: hi"
    val parsed = LogcatParser.parseLine(line)
    // 2026-03-10 14:23:45.678901-0700 == 2026-03-10 21:23:45.678 UTC
    // Just sanity-check that we got a non-null epoch and the millis tail is preserved.
    val epoch = parsed.epochMs
    assertTrue(epoch != null)
    assertEquals(678L, epoch % 1000)
  }

  @Test
  fun `parseLine extracts iOS datetime with 3-digit fraction (xcrun trims trailing zeros)`() {
    val line = "2026-03-10 14:23:45.123-0700  MyApp[12345]: hi"
    val parsed = LogcatParser.parseLine(line)
    val epoch = parsed.epochMs
    assertTrue(epoch != null)
    assertEquals(123L, epoch % 1000)
  }

  @Test
  fun `parseLine extracts iOS datetime with 9-digit fraction`() {
    val line = "2026-03-10 14:23:45.123456789-0700  MyApp[12345]: hi"
    val parsed = LogcatParser.parseLine(line)
    assertTrue(parsed.epochMs != null)
  }

  @Test
  fun `parseLine returns null epoch for unparseable lines`() {
    val parsed = LogcatParser.parseLine("--------- beginning of main")
    assertNull(parsed.epochMs)
    assertEquals("--------- beginning of main", parsed.text)
  }

  @Test
  fun `parseFile returns empty list for missing or empty files`() {
    val tempDir = Files.createTempDirectory("logcat-parser-test").toFile()
    try {
      val missing = File(tempDir, "missing.log")
      assertEquals(emptyList(), LogcatParser.parseFile(missing))

      val empty = File(tempDir, "empty.log").apply { writeText("") }
      assertEquals(emptyList(), LogcatParser.parseFile(empty))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `sliceByTimeRange returns lines in window plus padding`() {
    val tempDir = Files.createTempDirectory("logcat-parser-test").toFile()
    try {
      val file = File(tempDir, "device.log").apply {
        writeText(
          listOf(
            "1772846520.000  1  1 D : before window",
            "1772846521.234  1  1 D : in window",
            "1772846522.000  1  1 D : in window edge",
            "1772846530.000  1  1 D : after window",
          ).joinToString("\n"),
        )
      }
      val result = LogcatParser.sliceByTimeRange(
        file,
        startMs = 1772846521000L,
        endMs = 1772846522000L,
        paddingMs = 0,
      )
      assertEquals(2, result.size)
      assertTrue(result[0].text.contains("in window"))
      assertTrue(result[1].text.contains("in window edge"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `sliceByTimeRange honors padding`() {
    val tempDir = Files.createTempDirectory("logcat-parser-test").toFile()
    try {
      val file = File(tempDir, "device.log").apply {
        writeText(
          listOf(
            "1772846520.500  1  1 D : 500ms before",
            "1772846521.234  1  1 D : in window",
            "1772846522.500  1  1 D : 500ms after",
          ).joinToString("\n"),
        )
      }
      val result = LogcatParser.sliceByTimeRange(
        file,
        startMs = 1772846521000L,
        endMs = 1772846522000L,
        paddingMs = 500,
      )
      // All three lines fall inside the [start - 500, end + 500] window.
      assertEquals(3, result.size)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `sliceByTimeRange rejects reversed window`() {
    val tempDir = Files.createTempDirectory("logcat-parser-test").toFile()
    try {
      val file = File(tempDir, "device.log").apply {
        writeText("1772846521.234  1  1 D : line\n")
      }
      assertFails {
        LogcatParser.sliceByTimeRange(file, startMs = 1000, endMs = 100)
      }
    } finally {
      tempDir.deleteRecursively()
    }
  }
}

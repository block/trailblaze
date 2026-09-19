package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.events.SessionEvent
import xyz.block.trailblaze.events.SessionEvents

class CrashEventArtifactWriterTest {

  @Test
  fun `Android fatal exception becomes a timestamped crash artifact with log reference`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846521.000  123  123 I MyApp : opening checkout
          1772846522.234  123  123 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.234  123  123 E AndroidRuntime: Process: com.example.store, PID: 123
          1772846522.234  123  123 E AndroidRuntime: java.lang.IllegalStateException: broken
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)

      val artifact = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson")
      assertTrue(artifact.isFile, "expected a structured crash event artifact")
      val envelope = Json.decodeFromString(SessionEvent.serializer(), artifact.readText())
      assertEquals(1772846522234L, envelope.timeMs)
      val data = envelope.data.jsonObject
      assertEquals("fatal_exception", data["kind"]?.jsonPrimitive?.content)
      assertEquals("android", data["platform"]?.jsonPrimitive?.content)
      assertEquals("device.log", data["source"]?.jsonObject?.get("path")?.jsonPrimitive?.content)
      assertEquals(2, data["source"]?.jsonObject?.get("line")?.jsonPrimitive?.content?.toInt())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `every advertised crash signature is classified with its timestamp`() {
    val iosTimestamp = OffsetDateTime.parse("2026-03-10T14:23:45.678-07:00").toInstant().toEpochMilli()
    val cases = listOf(
      SignatureCase(
        platform = TrailblazeDevicePlatform.ANDROID,
        line = "1772846522.234  123  123 F libc : Fatal signal 11 (SIGSEGV), code 1",
        expectedKind = "native_crash",
        expectedTimeMs = 1772846522234L,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: " +
          "*** Terminating app due to uncaught exception 'NSInvalidArgumentException'",
        expectedKind = "uncaught_exception",
        expectedTimeMs = iosTimestamp,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: Fatal error: invalid state",
        expectedKind = "fatal_error",
        expectedTimeMs = iosTimestamp,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: terminated due to signal 9",
        expectedKind = "terminated_by_signal",
        expectedTimeMs = iosTimestamp,
      ),
    )

    cases.forEach { case ->
      val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
      try {
        val deviceLog = File(sessionDir, "device.log").apply { writeText(case.line) }

        CrashEventArtifactWriter.write(sessionDir, deviceLog, case.platform)

        val artifact = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson")
        val event = Json.decodeFromString(SessionEvent.serializer(), artifact.readText())
        val data = event.data.jsonObject
        assertEquals(case.expectedTimeMs, event.timeMs)
        assertEquals(case.expectedKind, data["kind"]?.jsonPrimitive?.content)
        assertEquals(case.platform.name.lowercase(), data["platform"]?.jsonPrimitive?.content)
      } finally {
        sessionDir.deleteRecursively()
      }
    }
  }

  @Test
  fun `ordinary errors do not manufacture a crash event`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText("1772846521.000  123  123 E MyApp : request failed with an Exception")
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)

      assertFalse(File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").exists())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `crash event index is capped without dropping the hundredth event`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          (1..101).joinToString("\n") { sequence ->
            "1772846521.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main ($sequence)"
          },
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)

      val artifact = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson")
      assertEquals(100, artifact.readLines().size)
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  private data class SignatureCase(
    val platform: TrailblazeDevicePlatform,
    val line: String,
    val expectedKind: String,
    val expectedTimeMs: Long,
  )
}

package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
      assertEquals("FATAL EXCEPTION: main", data["summary"]?.jsonPrimitive?.content)
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
        expectedSummary = "Fatal signal 11 (SIGSEGV), code 1",
        expectedTimeMs = 1772846522234L,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: " +
          "*** Terminating app due to uncaught exception 'NSInvalidArgumentException'",
        expectedKind = "uncaught_exception",
        expectedSummary = "Terminating app due to uncaught exception 'NSInvalidArgumentException'",
        expectedTimeMs = iosTimestamp,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: Fatal error: invalid state",
        expectedKind = "fatal_error",
        expectedSummary = "Fatal error: invalid state",
        expectedTimeMs = iosTimestamp,
      ),
      SignatureCase(
        platform = TrailblazeDevicePlatform.IOS,
        line = "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: terminated due to signal 9",
        expectedKind = "terminated_by_signal",
        expectedSummary = "terminated due to signal 9",
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
        // From the signature on: the process prefix and iOS's `*** ` marker are dropped.
        assertEquals(case.expectedSummary, data["summary"]?.jsonPrimitive?.content)
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
  fun `the app's own lines that only resemble a crash do not fail its run`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846521.000  123  123 W MyApp : reported a non-fatal exception to the crash service
          1772846521.100  123  123 I MyApp : FATAL EXCEPTION: main was handled by the retry loop
          1772846521.200  123  123 E MyApp : fatal error: config missing, using defaults
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)
      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS)

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

  @Test
  fun `a session that recorded crashes fails naming the first crash, with the app_crashed code and the crash itself`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846521.000  123  123 I MyApp : opening checkout
          1772846522.234  123  123 E AndroidRuntime: FATAL EXCEPTION: main
          1772846523.000  456  456 F libc : Fatal signal 11 (SIGSEGV), code 1
          """.trimIndent(),
        )
      }
      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)

      val failure = assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
      assertEquals(
        "App crashed: FATAL EXCEPTION: main (device.log line 2, 2 crash events in total)",
        failure.message,
      )
      assertEquals("app_crashed", failure.payload["code"]?.jsonPrimitive?.content)
      assertEquals(2, failure.payload["crashCount"]?.jsonPrimitive?.content?.toInt())
      val firstCrash = assertNotNull(failure.payload["firstCrash"]?.jsonObject)
      assertEquals("fatal_exception", firstCrash["kind"]?.jsonPrimitive?.content)
      assertEquals("FATAL EXCEPTION: main", firstCrash["summary"]?.jsonPrimitive?.content)
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a session that recorded no crash has no crash failure`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText("1772846521.000  123  123 E MyApp : request failed with an Exception")
      }
      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID)

      assertNull(CrashEventArtifactWriter.failure(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS extension's crash beside the app does not fail the app's run`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      // The app and its widget extension share the install directory, so both reach device.log.
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          2026-10-08 15:12:04.836 Df Wallet[21054:7b06b] [com.apple.chrono:widget] reloadAllTimelines() - reloaded all timelines
          2026-10-08 15:12:04.837 Df WalletWidget[21057:7b0ac] (libswiftCore.dylib) Fatal error: Attempted to read an unowned reference but object 0x10f0e0ba0 was already destroyed
          2026-10-08 15:12:04.993 Df Wallet[21054:7b06b] (libMobileGestalt.dylib) No persisted cache on this platform.
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "Wallet")

      assertFalse(File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").exists())
      assertNull(CrashEventArtifactWriter.failure(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `the iOS app's own crash still fails its run when an extension also crashed`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          2026-10-08 15:12:04.837 Df WalletWidget[21057:7b0ac] (libswiftCore.dylib) Fatal error: widget state
          2026-10-08 15:12:05.100 Df Wallet[21054:7b06b] (libswiftCore.dylib) Fatal error: app state
          """.trimIndent(),
        )
      }

      // Process names compare case-insensitively, like the log predicate's [c] clauses.
      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "wallet")

      val failure = assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
      assertEquals(1, failure.payload["crashCount"]?.jsonPrimitive?.content?.toInt())
      val source = assertNotNull(failure.payload["firstCrash"]?.jsonObject?.get("source")?.jsonObject)
      assertEquals(2, source["line"]?.jsonPrimitive?.content?.toInt())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS crash on a continuation line belongs to the record above it`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          2026-10-08 15:12:04.837 Df WalletWidget[21057:7b0ac] (libswiftCore.dylib) widget timeline failed:
              Fatal error: widget state
          2026-10-08 15:12:05.100 Df Wallet[21054:7b06b] (libswiftCore.dylib) app state check failed:
              Fatal error: app state
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "Wallet")

      val failure = assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
      assertEquals(1, failure.payload["crashCount"]?.jsonPrimitive?.content?.toInt())
      val source = assertNotNull(failure.payload["firstCrash"]?.jsonObject?.get("source")?.jsonObject)
      assertEquals(4, source["line"]?.jsonPrimitive?.content?.toInt())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS crash line with no process to attribute it to still counts`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText("    Fatal error: continued from the previous line")
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "Wallet")

      assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS app whose name starts with a short word keeps its crash in either log style`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          2026-03-10 14:23:45.678-0700  My App[12345]: Fatal error: plain style
          2026-10-08 15:12:04.837 Df My App[1:2a] (libswiftCore.dylib) Fatal error: compact style
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "My App")

      val failure = assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
      assertEquals(2, failure.payload["crashCount"]?.jsonPrimitive?.content?.toInt())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS process column names its process in both log stream line styles`() {
    val compact = assertNotNull(
      CrashEventArtifactWriter.iosProcessColumnOf(
        "2026-10-08 15:12:04.837 Df WalletWidget[21057:7b0ac] (libswiftCore.dylib) Fatal error: x",
      ),
    )
    assertTrue(CrashEventArtifactWriter.iosColumnNames(compact, "WalletWidget"))
    assertFalse(CrashEventArtifactWriter.iosColumnNames(compact, "Wallet"))

    val plain = assertNotNull(
      CrashEventArtifactWriter.iosProcessColumnOf("2026-03-10 14:23:45.678-0700  ExampleApp[12345]: Fatal error: x"),
    )
    assertTrue(CrashEventArtifactWriter.iosColumnNames(plain, "exampleapp"))
    assertFalse(CrashEventArtifactWriter.iosColumnNames(plain, "Example"))

    val spaced = assertNotNull(
      CrashEventArtifactWriter.iosProcessColumnOf("2026-10-08 15:12:04.837 E  Wallet Beta[1:2a] Fatal error: x"),
    )
    assertTrue(CrashEventArtifactWriter.iosColumnNames(spaced, "Wallet Beta"))
    assertFalse(CrashEventArtifactWriter.iosColumnNames(spaced, "Wallet"))

    assertNull(CrashEventArtifactWriter.iosProcessColumnOf("    Fatal error: continued from the previous line"))
  }

  private data class SignatureCase(
    val platform: TrailblazeDevicePlatform,
    val line: String,
    val expectedKind: String,
    val expectedSummary: String,
    val expectedTimeMs: Long,
  )
}

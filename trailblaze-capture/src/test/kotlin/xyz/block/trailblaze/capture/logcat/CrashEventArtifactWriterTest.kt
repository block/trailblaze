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
  fun `another Android app's crash in the shared logcat does not fail the app's run`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      // A Java crash is named by the next AndroidRuntime line, a native one by crash_dump's tombstone.
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846522.234  777  777 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.234  777  777 E AndroidRuntime: Process: com.other.launcher, PID: 777
          1772846522.234  777  777 E AndroidRuntime: java.lang.IllegalStateException: launcher
          1772846523.000  888  891 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 891 (Thread-2)
          1772846523.100  900  900 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
          1772846523.100  900  900 F DEBUG   : pid: 888, tid: 891, name: Thread-2  >>> com.other.camera <<<
          1772846524.000  123  123 I MyApp : checkout opened
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = "com.example.store")

      assertFalse(File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").exists())
      assertNull(CrashEventArtifactWriter.failure(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `the Android app's own crashes still fail its run beside other processes' crashes`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846522.000  777  777 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.100  123  123 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.100  777  777 E AndroidRuntime: Process: com.other.launcher, PID: 777
          1772846522.200  123  123 E AndroidRuntime: Process: com.example.store:remote, PID: 123
          1772846523.000  124  130 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 130 (hwuiTask0), pid 124 (m.example.store)
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = "com.example.store")

      val failure = assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
      assertEquals(2, failure.payload["crashCount"]?.jsonPrimitive?.content?.toInt())
      val lines = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").readLines().map { line ->
        Json.decodeFromString(SessionEvent.serializer(), line).data.jsonObject
      }
      assertEquals(
        listOf(2, 5),
        lines.map { it["source"]?.jsonObject?.get("line")?.jsonPrimitive?.content?.toInt() },
        "the app's Java crash (its :remote process) and its native crash, in log order",
      )
      assertEquals(listOf("fatal_exception", "native_crash"), lines.map { it["kind"]?.jsonPrimitive?.content })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a libc comm that does not fit the app leaves the native crash to its tombstone`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      // 124 is the app under a renamed comm; 888 is another app under its truncated one; 999 never gets a tombstone.
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846523.000  124  130 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 130 (hwuiTask0), pid 124 (hwuiTask0)
          1772846523.100  900  900 F DEBUG   : pid: 124, tid: 130, name: hwuiTask0  >>> com.example.store <<<
          1772846524.000  888  891 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 891 (Thread-2), pid 888 (ample.camera.ui)
          ${(1..500).joinToString("\n          ") { "1772846524.${"%03d".format(it)}  555  555 I OtherApp: Fatal signal 11 received from remote peer, line $it" }}
          1772846525.100  901  901 F DEBUG   : pid: 888, tid: 891, name: Thread-2  >>> com.other.camera.ui <<<
          1772846526.000  999  999 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 999 (main), pid 999 (other.service)
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = "com.example.store")

      val lines = File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").readLines().map { line ->
        Json.decodeFromString(SessionEvent.serializer(), line).data.jsonObject
      }
      assertEquals(
        listOf(1, 505),
        lines.map { it["source"]?.jsonObject?.get("line")?.jsonPrimitive?.content?.toInt() },
        "the app's crash by its tombstone and the crash no tombstone names; the other app's is excluded " +
          "by a tombstone 500 records later, and another tag's 'Fatal signal' is not a crash",
      )
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an Android crash whose process is never named still counts`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846522.000  777  777 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.100  777  777 E AndroidRuntime: java.lang.IllegalStateException: no Process line this time
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = "com.example.store")

      assertNotNull(CrashEventArtifactWriter.failure(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a crash signature matches only the runtime's own banner, however the tag is padded`() {
    val android = TrailblazeDevicePlatform.ANDROID
    val ios = TrailblazeDevicePlatform.IOS
    val crashes = mapOf(
      "1772846522.234  123  123 E AndroidRuntime: FATAL EXCEPTION: main" to "fatal_exception",
      "1772846522.234  123  123 E AndroidRuntime   : FATAL EXCEPTION: AsyncTask #1" to "fatal_exception",
      "1772846522.234  123  123 F libc : Fatal signal 11 (SIGSEGV), code 1" to "native_crash",
      "1772846522.234  123  123 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 130" to "native_crash",
    )
    crashes.forEach { (line, kind) ->
      assertEquals(kind, CrashEventArtifactWriter.signatureFor(line, android)?.first, line)
    }
    val notCrashes = listOf(
      // The banner's words under another tag: an app logging about a crash, not crashing.
      "1772846521.100  123  123 I MyApp : FATAL EXCEPTION: main was handled by the retry loop",
      "1772846524.001  555  555 I OtherApp: Fatal signal 11 received from remote peer",
      // The right tag, but not the banner.
      "1772846522.234  123  123 E AndroidRuntime: Process: com.example.store, PID: 123",
      "1772846522.234  123  123 W AndroidRuntime: non-FATAL EXCEPTION reported",
      "1772846522.234  123  123 F libc : Fatal signal handler installed",
      // A libc line whose signal number is missing is not a crash record.
      "1772846522.234  123  123 F libc : Fatal signal",
    )
    notCrashes.forEach { line ->
      assertNull(CrashEventArtifactWriter.signatureFor(line, android), line)
      assertNull(CrashEventArtifactWriter.signatureFor(line, ios), "iOS signatures must not fire on Android lines: $line")
    }

    val iosCrashes = mapOf(
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: *** Terminating app due to uncaught exception 'NSRangeException'" to
        "uncaught_exception",
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: terminating app due to UNCAUGHT EXCEPTION" to "uncaught_exception",
      "2026-10-08 15:12:04.837 Df Wallet[21054:7b06b] (libswiftCore.dylib) Fatal error: Index out of range" to "fatal_error",
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: terminated due to signal 9" to "terminated_by_signal",
      "    Fatal error: continued from the previous line" to "fatal_error",
    )
    iosCrashes.forEach { (line, kind) ->
      assertEquals(kind, CrashEventArtifactWriter.signatureFor(line, ios)?.first, line)
    }
    val iosNotCrashes = listOf(
      // Swift's banner is capitalised; a lowercase "fatal error" is an app's own log line.
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: fatal error: config missing, using defaults",
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: Fatal errors are reported to the crash service",
      "2026-03-10 14:23:45.678-0700  ExampleApp[12345]: the request was terminated due to a timeout",
    )
    iosNotCrashes.forEach { line ->
      assertNull(CrashEventArtifactWriter.signatureFor(line, ios), line)
    }
  }

  @Test
  fun `an Android record is parsed from the epoch format only`() {
    val padded = assertNotNull(
      CrashEventArtifactWriter.androidRecordOf("1772846523.100  900  901 F DEBUG   : pid: 888, tid: 891, name: Thread-2  >>> com.other.camera <<<"),
    )
    assertEquals(CrashEventArtifactWriter.AndroidRecord(pid = "900", tag = "DEBUG", message = "pid: 888, tid: 891, name: Thread-2  >>> com.other.camera <<<"), padded)
    val unpadded = assertNotNull(
      CrashEventArtifactWriter.androidRecordOf("1772846522.234  123  123 E AndroidRuntime: Process: com.example.store, PID: 123"),
    )
    assertEquals(CrashEventArtifactWriter.AndroidRecord(pid = "123", tag = "AndroidRuntime", message = "Process: com.example.store, PID: 123"), unpadded)

    // Stack frames, `threadtime` lines, and a tag with no message are not records.
    assertNull(CrashEventArtifactWriter.androidRecordOf("    at com.example.store.Checkout.open(Checkout.kt:12)"))
    assertNull(CrashEventArtifactWriter.androidRecordOf("10-08 15:12:04.837   123   123 E AndroidRuntime: FATAL EXCEPTION: main"))
    assertNull(CrashEventArtifactWriter.androidRecordOf("1772846522.234  123  123 E AndroidRuntime:"))
  }

  @Test
  fun `an Android crash is attributed by the runtime's Process line or the tombstone's pid line`() {
    fun attributionOf(line: String) = CrashEventArtifactWriter.androidRecordOf(line)?.let(CrashEventArtifactWriter::androidAttributionOf)

    // AndroidRuntime names the process only in the crashing process's own record.
    assertEquals("123" to "com.example.store", attributionOf("1772846522.234  123  123 E AndroidRuntime: Process: com.example.store, PID: 123"))
    assertEquals("123" to "com.example.store:remote", attributionOf("1772846522.234  123  125 E AndroidRuntime: Process: com.example.store:remote, PID: 123"))
    assertNull(
      attributionOf("1772846522.234  777  777 E AndroidRuntime: Process: com.other.launcher, PID: 123"),
      "another process's record does not name pid 123's crash",
    )
    assertNull(attributionOf("1772846522.234  123  123 E AndroidRuntime: java.lang.IllegalStateException: Process: x, PID: 123"))

    // crash_dump's tombstone names the crashing pid, which is not crash_dump's own.
    assertEquals(
      "888" to "com.other.camera",
      attributionOf("1772846523.100  900  900 F DEBUG   : pid: 888, tid: 891, name: Thread-2  >>> com.other.camera <<<"),
    )
    assertEquals(
      "124" to "com.example.store:remote",
      attributionOf("1772846523.100  900  900 F DEBUG   : pid: 124, tid: 130, name: hwuiTask0  >>> com.example.store:remote <<<"),
    )
    assertNull(attributionOf("1772846523.100  900  900 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"))
    assertNull(attributionOf("1772846523.100  900  900 F DEBUG   : Cmdline: com.example.store"))

    // libc's comm is truncated to 15 characters, so it cannot name the process.
    assertNull(attributionOf("1772846523.000  124  130 F libc    : Fatal signal 6 (SIGABRT) in tid 130 (hwuiTask0), pid 124 (m.example.store)"))
  }

  @Test
  fun `the app's Android processes are its package and that package's suffixed processes`() {
    assertTrue(CrashEventArtifactWriter.androidProcessIsApp(process = "com.example.store", appProcessName = "com.example.store"))
    assertTrue(CrashEventArtifactWriter.androidProcessIsApp(process = "com.example.store:remote", appProcessName = "com.example.store"))
    assertFalse(CrashEventArtifactWriter.androidProcessIsApp(process = "com.example.storefront", appProcessName = "com.example.store"))
    assertFalse(CrashEventArtifactWriter.androidProcessIsApp(process = "com.example", appProcessName = "com.example.store"))
    assertFalse(CrashEventArtifactWriter.androidProcessIsApp(process = "com.other:com.example.store", appProcessName = "com.example.store"))
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

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
  fun `a crash logged before the session started stays in the log but is not indexed`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      // Logged while the device connected, then the runner recovered.
      val connectPhase = """
        1772846521.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main
        1772846521.000  123  123 E AndroidRuntime: Process: com.example.store, PID: 123

      """.trimIndent()
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(connectPhase + "1772846525.000  456  456 I MyApp : opening checkout\n")
      }

      CrashEventArtifactWriter.write(
        sessionDir,
        deviceLog,
        TrailblazeDevicePlatform.ANDROID,
        appProcessName = "com.example.store",
        bytesBeforeSessionStart = connectPhase.toByteArray().size.toLong(),
      )

      assertEquals(emptyList(), crashes(sessionDir))
      assertTrue(deviceLog.readText().contains("FATAL EXCEPTION"), "the connect's crash is still in the log")
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `crashes before the session started do not crowd out the session's own`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      // More crash lines while the device connected than the index keeps.
      val connectPhase = (1..150).joinToString("") {
        "1772846521.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main\n" +
          "1772846521.000  123  123 E AndroidRuntime: Process: com.example.store, PID: 123\n"
      }
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          connectPhase +
            "1772846525.000  456  456 E AndroidRuntime: FATAL EXCEPTION: main\n" +
            "1772846525.000  456  456 E AndroidRuntime: Process: com.example.store, PID: 456\n",
        )
      }

      CrashEventArtifactWriter.write(
        sessionDir,
        deviceLog,
        TrailblazeDevicePlatform.ANDROID,
        appProcessName = "com.example.store",
        bytesBeforeSessionStart = connectPhase.toByteArray().size.toLong(),
      )

      assertEquals(1, crashes(sessionDir).size, "the trail's own crash is still indexed")
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a crash after the session started is indexed`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val connectPhase = "1772846521.000  123  123 I MyApp : connecting\n"
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          connectPhase +
            "1772846525.000  456  456 E AndroidRuntime: FATAL EXCEPTION: main\n" +
            "1772846525.000  456  456 E AndroidRuntime: Process: com.example.store, PID: 456\n",
        )
      }

      CrashEventArtifactWriter.write(
        sessionDir,
        deviceLog,
        TrailblazeDevicePlatform.ANDROID,
        appProcessName = "com.example.store",
        bytesBeforeSessionStart = connectPhase.toByteArray().size.toLong(),
      )

      assertEquals(1, crashes(sessionDir).size)
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
  fun `the app's own lines that only resemble a crash are not indexed`() {
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
  fun `an Android crash keeps the records its process logs after it, under its tag, as detail`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val frames = (1..10).joinToString("\n") { "1772846522.234  123  123 E AndroidRuntime: \tat com.example.store.Frame$it.run(Frame.kt:$it)" }
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          |1772846522.234  123  123 E AndroidRuntime: FATAL EXCEPTION: main
          |1772846522.234  777  777 E AndroidRuntime: Process: com.other.launcher, PID: 777
          |1772846522.234  123  123 I MyApp : unrelated line from the same process
          |1772846522.234  123  123 E AndroidRuntime: Process: com.example.store, PID: 123
          |1772846522.234  123  123 E AndroidRuntime: java.lang.IllegalStateException: Debug menu crash
          |$frames
          """.trimMargin(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = "com.example.store")

      val data = Json.decodeFromString(
        SessionEvent.serializer(),
        File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").readText(),
      ).data.jsonObject
      val detail = assertNotNull(data["detail"]?.jsonArray).map { it.jsonPrimitive.content }
      assertEquals(
        listOf(
          "Process: com.example.store, PID: 123",
          "java.lang.IllegalStateException: Debug menu crash",
        ),
        detail.take(2),
        "only the crashing process's records under its tag, in log order",
      )
      assertTrue(detail.size < 12, "detail keeps the first frames, not the whole stack: $detail")
      assertTrue(detail.drop(2).all { it.startsWith("at com.example.store.Frame") })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an iOS crash carries no detail`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText("2026-03-10 14:23:45.678-0700  ExampleApp[12345]: Fatal error: Debug Menu Test Crash")
      }
      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.IOS, appProcessName = "ExampleApp")

      val data = Json.decodeFromString(
        SessionEvent.serializer(),
        File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").readText(),
      ).data.jsonObject
      assertNull(data["detail"])
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `another Android app's crash in the shared logcat is not indexed for the app`() {
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
      assertEquals(emptyList(), crashes(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `the Android app's own crashes are indexed beside other processes' crashes`() {
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

      val lines = crashes(sessionDir)
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
  fun `a shell-launched tool's crash is not recorded, with or without the app's name`() {
    // A screen recorder's server, started from the shell, failing to configure its encoder: its
    // AndroidRuntime record carries only `PID:`, never a `Process:` line.
    val log =
      """
      1793674533.548  3863  3863 D AndroidRuntime: >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<
      1793674533.586  3863  3863 D AndroidRuntime: Calling main entry com.genymobile.scrcpy.Server
      1793674533.693  3863  3876 E scrcpy  : Capture/encoding error: java.lang.IllegalArgumentException: null
      1793674533.696  3863  3876 E AndroidRuntime: FATAL EXCEPTION: video
      1793674533.696  3863  3876 E AndroidRuntime: PID: 3863
      1793674533.696  3863  3876 E AndroidRuntime: java.lang.IllegalArgumentException
      1793674533.697  3863  3876 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 3876 (video), pid 3863 (app_process)
      """.trimIndent()
    for (appProcessName in listOf(null, "com.example.store")) {
      val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
      try {
        val deviceLog = File(sessionDir, "device.log").apply { writeText(log) }

        CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = appProcessName)

        assertEquals(emptyList(), crashes(sessionDir), "app name: $appProcessName")
      } finally {
        sessionDir.deleteRecursively()
      }
    }
  }

  @Test
  fun `the app's own crash is still recorded beside a shell-launched tool's crash`() {
    val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
    try {
      val deviceLog = File(sessionDir, "device.log").apply {
        writeText(
          """
          1772846521.000  3863  3863 D AndroidRuntime: Calling main entry com.android.commands.am.Am
          1772846522.000  3863  3876 E AndroidRuntime: FATAL EXCEPTION: main
          1772846522.000  3863  3876 E AndroidRuntime: PID: 3863
          1772846523.000   123   123 E AndroidRuntime: FATAL EXCEPTION: main
          1772846523.000   123   123 E AndroidRuntime: java.lang.IllegalStateException: the app's own
          """.trimIndent(),
        )
      }

      CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = null)

      val lines = crashes(sessionDir)
      assertEquals(listOf(4), lines.map { it["source"]?.jsonObject?.get("line")?.jsonPrimitive?.content?.toInt() })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `an app crash in a pid a finished shell-launched tool used is still recorded`() {
    val toolExited = "1772846521.000  3863  3863 D AndroidRuntime: Calling main entry com.android.commands.am.Am"
    val cases = mapOf(
      // ActivityManager starting the app in the pid ends the tool's claim on it.
      "started in the pid" to (
        null to """
        $toolExited
        1772846522.000   600   620 I ActivityManager: Start proc 3863:com.example.store/u0a123 for activity
        1772846523.000  3863  3863 E AndroidRuntime: FATAL EXCEPTION: main
        """.trimIndent()
      ),
      // With no start record, the crash's own Process line names the app.
      "named by its crash" to (
        "com.example.store" to """
        $toolExited
        1772846523.000  3863  3863 E AndroidRuntime: FATAL EXCEPTION: main
        1772846523.000  3863  3863 E AndroidRuntime: Process: com.example.store, PID: 3863
        """.trimIndent()
      ),
    )
    for ((case, input) in cases) {
      val (appProcessName, log) = input
      val sessionDir = Files.createTempDirectory("crash-event-test").toFile()
      try {
        val deviceLog = File(sessionDir, "device.log").apply { writeText(log) }

        CrashEventArtifactWriter.write(sessionDir, deviceLog, TrailblazeDevicePlatform.ANDROID, appProcessName = appProcessName)

        assertEquals(1, crashes(sessionDir).size, case)
      } finally {
        sessionDir.deleteRecursively()
      }
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

      assertEquals(1, crashes(sessionDir).size)
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
  fun `an iOS extension's crash beside the app is not indexed for the app`() {
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
      assertEquals(emptyList(), crashes(sessionDir))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `the iOS app's own crash is indexed when an extension also crashed`() {
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

      val source = assertNotNull(crashes(sessionDir).single()["source"]?.jsonObject)
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

      val source = assertNotNull(crashes(sessionDir).single()["source"]?.jsonObject)
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

      assertEquals(1, crashes(sessionDir).size)
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

      assertEquals(2, crashes(sessionDir).size)
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

  /** The crash records [CrashEventArtifactWriter.write] indexed for [sessionDir], in log order; none without an index. */
  private fun crashes(sessionDir: File): List<JsonObject> =
    File(sessionDir, "${SessionEvents.DIR_NAME}/crash.ndjson").takeIf { it.isFile }?.readLines().orEmpty()
      .filter { it.isNotBlank() }
      .map { Json.decodeFromString(SessionEvent.serializer(), it).data.jsonObject }

  private data class SignatureCase(
    val platform: TrailblazeDevicePlatform,
    val line: String,
    val expectedKind: String,
    val expectedSummary: String,
    val expectedTimeMs: Long,
  )
}

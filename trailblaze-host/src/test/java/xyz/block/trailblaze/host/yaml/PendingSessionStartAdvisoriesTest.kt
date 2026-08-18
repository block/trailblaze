package xyz.block.trailblaze.host.yaml

import java.io.File
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.resolveDaemonRunTargetApp
import xyz.block.trailblaze.ui.unresolvedDeclaredTargetWarning

/**
 * Pins that a run whose `config.target` names a target this installation doesn't carry produces a
 * SESSION LOG record of the retargeting — not just console/progress output. The daemon raises the
 * warning while assembling the run, before any session exists, so it rides
 * `DesktopAppRunYamlParams.sessionStartAdvisories` and lands via [PendingSessionStartAdvisories]
 * once the session id is known.
 *
 * Exercises the real seams end-to-end minus the runner's callback glue (`DesktopYamlRunner` isn't
 * hermetically constructable — it needs the full `TrailblazeDeviceManager` graph): the resolution
 * announcement ([resolveDaemonRunTargetApp]) builds the production warning text
 * ([unresolvedDeclaredTargetWarning]), which drains through a REAL [LogsRepo] disk round-trip.
 */
class PendingSessionStartAdvisoriesTest {

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private fun withLogsRepo(block: (LogsRepo) -> Unit) {
    val logsDir = Files.createTempDirectory("session-start-advisories").toFile()
    try {
      block(LogsRepo(logsDir = logsDir, watchFileSystem = false))
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `an unresolvable config target leaves a session log entry naming declared and fallback targets`() {
    withLogsRepo { logsRepo ->
      val sessionId = SessionId("retargeted-run")

      // Same wiring shape as TrailblazeDesktopApp.handleCliRunRequest: the resolution announcement
      // builds the production warning and defers it as a session-start advisory.
      val advisories = mutableListOf<String>()
      resolveDaemonRunTargetApp(
        configTarget = "otherapp",
        callerWorkspaceDir = null,
        findTargetById = { null },
        resolveForCallerCwd = { target("beta") },
        onDeclaredTargetUnresolved = { declared, fallback ->
          advisories += unresolvedDeclaredTargetWarning(declared, fallback)
        },
      )
      PendingSessionStartAdvisories(advisories, logsRepo::saveLogToDisk).logTo(sessionId)

      val logged = logsRepo.getLogsForSession(sessionId)
        .filterIsInstance<TrailblazeLog.TrailblazeProgressLog>()
      assertEquals(1, logged.size, "exactly one advisory log entry for the retargeted run")
      val advisory = logged.single()
      assertEquals(PendingSessionStartAdvisories.EVENT_TYPE, advisory.eventType)
      assertEquals(sessionId, advisory.session)
      assertTrue(
        advisory.description.contains("'otherapp'"),
        "the session log record must name the declared target: ${advisory.description}",
      )
      assertTrue(
        advisory.description.contains("'beta'"),
        "…and the fallback actually used: ${advisory.description}",
      )
    }
  }

  @Test
  fun `advisories drain once even when the session-started callback fires again`() {
    // The runner's captureSessionStarted legitimately fires more than once per run (idempotent
    // capture wiring; finally-block backstop for the branches that only learn the session id
    // late). Duplicate advisory entries would read as the warning having fired twice.
    withLogsRepo { logsRepo ->
      val sessionId = SessionId("double-fire-run")
      val pending = PendingSessionStartAdvisories(listOf("advisory"), logsRepo::saveLogToDisk)

      pending.logTo(sessionId)
      pending.logTo(sessionId)

      val logged = logsRepo.getLogsForSession(sessionId)
        .filterIsInstance<TrailblazeLog.TrailblazeProgressLog>()
      assertEquals(1, logged.size, "a re-fired session-started callback must not duplicate advisories")
    }
  }

  @Test
  fun `an advisory in the 001 slot does not hide the session from the device matcher`() {
    // The Maestro host path fires onSessionStarted BEFORE its Started status log is written, so
    // with an advisory present, 001_ is a TrailblazeProgressLog. The runner's cancellation
    // fallback must still recognize the session as this device's: it used to probe the exact
    // filename 001_TrailblazeSessionStatusChangeLog.json, miss, and skip
    // finalizeHostSessionResources for exactly the failed runs an advisory marks. The matcher
    // decides on PARSED logs (never filenames — farm pulls and the CI reshaper name files
    // differently), so the assertion round-trips through the repo's filename-agnostic parser.
    withLogsRepo { logsRepo ->
      val sessionId = SessionId("advisory-first-run")
      val deviceId = TrailblazeDeviceId(
        instanceId = "emulator-5554",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      )

      PendingSessionStartAdvisories(listOf("advisory"), logsRepo::saveLogToDisk).logTo(sessionId)
      logsRepo.saveLogToDisk(
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = null,
            hasRecordedSteps = false,
            testMethodName = "advisoryFirstRun",
            testClassName = "AdvisoryFirstRunTest",
            trailblazeDeviceInfo = TrailblazeDeviceInfo(
              trailblazeDeviceId = deviceId,
              trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
              widthPixels = 1080,
              heightPixels = 2400,
            ),
            trailblazeDeviceId = deviceId,
          ),
          session = sessionId,
          timestamp = Instant.fromEpochMilliseconds(0),
        ),
      )

      val summary = logsRepo.getSessionInfoSummary(sessionId)
      assertTrue(
        DesktopYamlRunner.sessionBelongsToDevice(summary, "emulator-5554"),
        "the Started log must match its device even when an advisory occupies the 001_ slot",
      )
      assertFalse(
        DesktopYamlRunner.sessionBelongsToDevice(summary, "some-other-device"),
        "a session started on another device must not match",
      )
    }
  }

  @Test
  fun `an advisory sorts ahead of the session logs it warns about`() {
    // Both drain sites write the same advisory — captureSessionStarted at session start, the
    // runner's finally after the terminal status. Stamping at drain time put the finally-path
    // copy after the Ended card, so the failure path buried the warning at the bottom of the
    // session. Session logs render in timestamp order, so pin that a late drain still sorts first.
    withLogsRepo { logsRepo ->
      val sessionId = SessionId("late-drain-run")
      val pending = PendingSessionStartAdvisories(listOf("advisory"), logsRepo::saveLogToDisk)

      logsRepo.saveLogToDisk(
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Failed(durationMs = 5_000, exceptionMessage = "boom"),
          session = sessionId,
          timestamp = Clock.System.now(),
        ),
      )
      pending.logTo(sessionId)

      val ordered = logsRepo.getLogsForSession(sessionId)
      assertTrue(
        ordered.first() is TrailblazeLog.TrailblazeProgressLog,
        "the advisory must sort ahead of the terminal status, not trail it: " +
          ordered.map { it::class.simpleName },
      )
    }
  }

  @Test
  fun `no advisories writes nothing and never creates a session directory`() {
    // The common case (config.target resolved, or none declared) must leave zero trace: a session
    // directory materialized by an empty drain would surface as a phantom session.
    withLogsRepo { logsRepo ->
      val sessionId = SessionId("clean-run")

      PendingSessionStartAdvisories(emptyList(), logsRepo::saveLogToDisk).logTo(sessionId)

      assertFalse(
        File(logsRepo.logsDir, sessionId.value).exists(),
        "an empty advisory drain must not create the session directory",
      )
    }
  }
}

package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * A daemon run cancelled mid-trail (the CLI's Ctrl-C cancels the run's job) must leave its session
 * with a terminal status, not just its Started log — otherwise reports and Trail Runner show it as
 * running forever.
 */
class HostCancelledRunEndsSessionTest {

  private val logsDir: File = Files.createTempDirectory("host-cancelled-run-").toFile()

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("web-1", TrailblazeDevicePlatform.WEB),
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    widthPixels = 1280,
    heightPixels = 800,
  )

  private val loggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { deviceInfo },
    logsDir = logsDir,
  )

  @AfterTest
  fun cleanUp() {
    logsDir.deleteRecursively()
  }

  @Test
  fun `cancelling the run job ends the session as cancelled`() {
    val status = cancelRunOnceStarted { awaitCancellation() }

    assertIs<SessionStatus.Ended.Cancelled>(status)
  }

  @Test
  fun `a cancel that lands after the run succeeded keeps it succeeded`() {
    val status = cancelRunOnceStarted { session ->
      loggingRule.endSession(session, isSuccess = true)
      awaitCancellation()
    }

    assertIs<SessionStatus.Ended.Succeeded>(status)
  }

  @Test
  fun `a Trail Runner stop keeps the cancellation it already wrote`() {
    // Trail Runner's Stop writes its own Cancelled before cancelling the job.
    val status = cancelRunOnceStarted { session ->
      loggingRule.endSession(
        session,
        SessionStatus.Ended.Cancelled(durationMs = 0L, cancellationMessage = "stopped from Trail Runner"),
      )
      awaitCancellation()
    }

    assertEquals("stopped from Trail Runner", assertIs<SessionStatus.Ended.Cancelled>(status).cancellationMessage)
  }

  @Test
  fun `a run whose caller owns the session end writes no end on cancel`() {
    val status = cancelRunOnceStarted(sendSessionEndLog = false, expectedEnds = 0) { awaitCancellation() }

    assertIs<SessionStatus.Started>(status)
  }

  /** Runs a trail session whose body is [body], cancels its job the way the daemon does, and reads the status back. */
  private fun cancelRunOnceStarted(
    sendSessionEndLog: Boolean = true,
    expectedEnds: Int = 1,
    body: suspend (TrailblazeSession) -> Unit,
  ): SessionStatus? = runBlocking {
    val started = CompletableDeferred<TrailblazeSession>()
    val run = async(Dispatchers.Default) {
      TrailblazeHostYamlRunner.executeTrailSession(
        loggingRule = loggingRule,
        overrideSessionId = null,
        testName = "cancelled run",
        deviceLabel = "playwright-native:web-1",
        sendSessionEndLog = sendSessionEndLog,
        onProgressMessage = {},
        screenshotProvider = { error("no screen in this test") },
      ) { session ->
        // What every runner logs first; a cancelled session used to end with only this.
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = null,
              trailFilePath = null,
              hasRecordedSteps = true,
              testMethodName = "run",
              testClassName = "HostCancelledRunEndsSessionTest",
              trailblazeDeviceInfo = deviceInfo,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
        started.complete(session)
        body(session)
        session.sessionId
      }
    }
    val session = started.await()
    run.cancelAndJoin()
    val ended = loggingRule.logsRepo.getSessionInfoSummary(session.sessionId)?.latestStatus
    assertEquals(
      expectedEnds,
      loggingRule.logsRepo.getLogsForSession(session.sessionId)
        .count { (it as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus is SessionStatus.Ended },
      "terminal statuses written",
    )
    ended
  }
}

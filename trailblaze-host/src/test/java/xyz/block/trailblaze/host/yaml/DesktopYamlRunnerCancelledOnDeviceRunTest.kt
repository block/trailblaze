package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancelling a trail the device runs by itself stops the device's run before the cancellation
 * finishes ([DesktopYamlRunner.stoppingOnDeviceRunWhenCancelled]); cancelling only the host's wait
 * would leave the device running under the next trail. The stopped session then ends cancelled.
 */
class DesktopYamlRunnerCancelledOnDeviceRunTest {

  @Test
  fun `a cancelled run is stopped on the device before its cancellation completes`() = runTest {
    val started = CompletableDeferred<Unit>()
    var stopped = false

    val run = async {
      DesktopYamlRunner.stoppingOnDeviceRunWhenCancelled(stopOnDevice = { stopped = true }) {
        started.complete(Unit)
        awaitCancellation()
      }
    }
    started.await()
    run.cancelAndJoin()

    assertTrue(stopped)
  }

  @Test
  fun `a run that finishes is not stopped on the device`() = runTest {
    var stopped = false

    val result = DesktopYamlRunner.stoppingOnDeviceRunWhenCancelled(stopOnDevice = { stopped = true }) { "done" }

    assertEquals("done", result)
    assertFalse(stopped)
  }

  @Test
  fun `a stop that fails still lets the cancellation through`() = runTest {
    val started = CompletableDeferred<Unit>()

    val run = async {
      DesktopYamlRunner.stoppingOnDeviceRunWhenCancelled(stopOnDevice = { error("adb gone") }) {
        started.complete(Unit)
        awaitCancellation()
      }
    }
    started.await()
    run.cancelAndJoin()

    assertTrue(run.isCancelled)
  }

  @Test
  fun `a session whose runner was stopped ends cancelled, and one that ended keeps its end`() {
    val logsRepo = LogsRepo(logsDir = Files.createTempDirectory("stopped-session").toFile(), watchFileSystem = false)
    val stopped = SessionId("stopped_session")
    val succeeded = SessionId("succeeded_session")
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        session = succeeded,
        timestamp = Clock.System.now(),
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5L),
      ),
    )

    DesktopYamlRunner.endStoppedOnDeviceSession(logsRepo, stopped)
    DesktopYamlRunner.endStoppedOnDeviceSession(logsRepo, succeeded)

    val end = logsRepo.persistedEndStatus(stopped)
    assertTrue(end is SessionStatus.Ended.Cancelled && end.cancellationMessage == DesktopYamlRunner.STOPPED_ON_DEVICE_MESSAGE, "$end")
    assertTrue(logsRepo.persistedEndStatus(succeeded) is SessionStatus.Ended.Succeeded)
  }
}

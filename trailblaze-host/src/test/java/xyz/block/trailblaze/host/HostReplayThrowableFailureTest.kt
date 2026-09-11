package xyz.block.trailblaze.host

import java.nio.file.Files
import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus

class HostReplayThrowableFailureTest {

  private val screenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 390
      override val deviceHeight: Int = 844
      override val viewHierarchy: ViewHierarchyTreeNode
        get() = error("No hierarchy needed for this test")
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  @Test
  fun `a throwable without a message ends the host session with its class name`() {
    val injectedFailure = ServiceConfigurationError(null)

    val result = runReplay(injectedFailure)

    assertSame(injectedFailure, result.thrown)
    assertEquals(2, result.snapshotCaptureCount, "the runner must capture the failure after the test snapshot")
    assertContains(result.progress.joinToString("\n"), ServiceConfigurationError::class.java.simpleName)
    assertContains(result.status.exceptionStackTrace.orEmpty(), ServiceConfigurationError::class.java.name)
  }

  @Test
  fun `a throwable message is persisted in the failed session status`() {
    val failureMessage = "Snapshot provider failed"
    val injectedFailure = ServiceConfigurationError(failureMessage)

    val result = runReplay(injectedFailure)

    assertSame(injectedFailure, result.thrown)
    assertEquals(failureMessage, result.status.exceptionMessage)
  }

  private fun runReplay(injectedFailure: ServiceConfigurationError): ReplayFailureResult {
    val logsDir = Files.createTempDirectory("host-replay-throwable-").toFile()
    try {
      val loggingRule =
        HostTrailblazeLoggingRule(
          trailblazeDeviceInfoProvider = {
            TrailblazeDeviceInfo(
              trailblazeDeviceId =
                TrailblazeDeviceId("simulated-ios", TrailblazeDevicePlatform.IOS),
              trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
              widthPixels = 390,
              heightPixels = 844,
            )
          },
          logsDir = logsDir,
        )
      val progress = mutableListOf<String>()
      var sessionId: SessionId? = null
      var snapshotCaptureCount = 0
      val snapshotProvider: () -> ScreenState = {
        snapshotCaptureCount++
        screenState
      }

      val thrown =
        assertFails {
          runBlocking {
            TrailblazeHostYamlRunner.executeTrailSession(
              loggingRule = loggingRule,
              overrideSessionId = null,
              testName = "host replay throwable",
              deviceLabel = "ios-host:simulated-ios",
              sendSessionEndLog = true,
              onProgressMessage = { progress += it },
              screenshotProvider = snapshotProvider,
            ) { session ->
              sessionId = session.sessionId
              snapshotProvider()
              throw injectedFailure
            }
          }
        }

      val status =
        assertIs<SessionStatus.Ended.Failed>(
          loggingRule.logsRepo.getSessionInfoSummary(checkNotNull(sessionId))?.latestStatus
        )
      return ReplayFailureResult(thrown, snapshotCaptureCount, progress, status)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  private data class ReplayFailureResult(
    val thrown: Throwable,
    val snapshotCaptureCount: Int,
    val progress: List<String>,
    val status: SessionStatus.Ended.Failed,
  )
}

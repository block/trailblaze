package xyz.block.trailblaze.rules

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Tests for the consolidated [TrailblazeLoggingRule.captureFailureScreenshot] — the
 * single entry point shared by all run methods (JUnit hook, on-device RPC, host CLI).
 * Regressions here would silently break failure-screenshot capture across every path,
 * so cover the behavioral branches directly.
 */
class TrailblazeLoggingRuleTest {

  @Test
  fun `captureFailureScreenshot(session) invokes provider AND emits a snapshot log`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    var invocations = 0
    rule.failureScreenStateProvider = {
      invocations++
      FakeScreenState
    }

    rule.captureFailureScreenshot(session())

    assertEquals(1, invocations, "provider should be invoked exactly once")
    val snapshotLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
    assertEquals(
      1,
      snapshotLogs.size,
      "expected exactly one TrailblazeSnapshotLog to be emitted; got ${captured.map { it::class.simpleName }}",
    )
    assertEquals(
      "failure_screenshot",
      snapshotLogs.single().displayName,
      "snapshot log's displayName must match the contract consumers filter on",
    )
  }

  @Test
  fun `captureFailureScreenshot(session) no-ops when provider is unwired`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    // failureScreenStateProvider is intentionally left un-initialized.

    // Should return cleanly — no UninitializedPropertyAccessException, no throw.
    // The isInitialized guard is the only thing preventing an exception here; if
    // this test passes it means the guard is present.
    rule.captureFailureScreenshot(session())

    assertTrue(
      captured.none { it is TrailblazeLog.TrailblazeSnapshotLog },
      "no snapshot log should be emitted when provider is unwired",
    )
  }

  @Test
  fun `captureFailureScreenshot(session, provider) swallows provider exceptions`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))

    // Using the explicit-provider overload to avoid coupling to the field state.
    // If the try/catch is removed or narrowed, this test will propagate the throw
    // and fail.
    rule.captureFailureScreenshot(session()) {
      throw RuntimeException("simulated driver failure")
    }

    assertTrue(
      captured.none { it is TrailblazeLog.TrailblazeSnapshotLog },
      "no snapshot log should be emitted when the provider throws",
    )
  }

  @Test
  fun `captureFailureScreenshot(session) swallows provider exceptions via the wired path`() {
    val rule = TestLoggingRule()
    rule.failureScreenStateProvider = { throw IllegalStateException("simulated wiring failure") }

    // Same contract as the explicit-provider overload — verifies both paths share
    // the same exception containment.
    rule.captureFailureScreenshot(session())
  }

  // -- Fixtures --

  private fun session(): TrailblazeSession = TrailblazeSession(
    sessionId = SessionId("test_session"),
    startTime = Clock.System.now(),
  )

  /**
   * Concrete [TrailblazeLoggingRule] with a fast-fail [logsBaseUrl] so the
   * `isServerAvailable` HTTP ping returns connection-refused immediately instead
   * of waiting for the 2 s timeout. Disk writes are no-ops by default. Callers
   * can inject an [additionalLogEmitter] to capture emitted logs.
   */
  private class TestLoggingRule(
    additionalLogEmitter: LogEmitter? = null,
  ) : TrailblazeLoggingRule(
    logsBaseUrl = "http://127.0.0.1:1",
    additionalLogEmitter = additionalLogEmitter,
  ) {
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
  }

  private object FakeScreenState : ScreenState {
    // Non-null bytes are required: TrailblazeLogger.logScreenState short-circuits on
    // null screenshotBytes, which would suppress the TrailblazeSnapshotLog emission.
    // Use a PNG magic-number prefix so ImageFormatDetector.detectFormat returns PNG
    // and the generated filename is valid.
    override val screenshotBytes: ByteArray = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "FrameLayout",
    )
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}

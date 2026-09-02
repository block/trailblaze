package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.File
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.NoOpPageManager
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins that a `--no-logging` run writes no session files, on the two host rules the runner's
 * Playwright-native and local-device (Maestro / iOS-host) paths build. `BasePlaywrightElectronTest`
 * takes the same parameter but isn't covered here: its constructor eagerly launches the Electron
 * app and connects over CDP, so there is no way to build one without a real app on the machine.
 *
 * Why this matters: `--no-logging` is documented as "no session files are written", but the flag
 * only reaches [HostTrailblazeLoggingRule] — which installs the no-op logger and the read-only
 * `LogsRepo` — if each rule builder forwards it. Every path already suppressed trace export via
 * `executeTrailSession`; only the Compose path forwarded the flag to its rule, so the other paths
 * ran with a real logger and left session directories on disk.
 *
 * The with-logging control cases write through the rule's own disk-write path rather than through
 * `loggingRule.logger`: the emitter's logging branch probes for a running log server first, so a
 * developer machine with a live Trailblaze daemon would upload instead of writing, and the control
 * would read as a pass for the wrong reason. The no-logging cases go through the logger too — that
 * branch returns before the probe, so it stays deterministic either way.
 */
class HostRuleNoLoggingTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val sessionId = SessionId("no-logging-test-session")

  private val webDeviceId = TrailblazeDeviceId(
    instanceId = "no-logging-test-web",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
  )

  private val iosDeviceId = TrailblazeDeviceId(
    instanceId = "no-logging-test-ios",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
  )

  /** Throws on first use; no test here reaches the agent loop. */
  private val throwingLlmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = error("no LLM in this test")
  }

  private fun aLog() = TrailblazeLog.TrailblazeProgressLog(
    eventType = "ExecutionStarted",
    description = "a step ran",
    session = sessionId,
    timestamp = Clock.System.now(),
  )

  private fun playwrightRule(logsDir: File, noLogging: Boolean): HostTrailblazeLoggingRule =
    BasePlaywrightNativeTest(
      dynamicLlmClient = throwingLlmClient,
      trailblazeDeviceId = webDeviceId,
      // Adopting a stub manager keeps the constructor from launching a real browser.
      existingBrowserManager = NoOpPageManager(),
      logsDir = logsDir,
      noLogging = noLogging,
    ).loggingRule

  private fun hostRule(logsDir: File, noLogging: Boolean): HostTrailblazeLoggingRule =
    object : BaseHostTrailblazeTest(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      dynamicLlmClient = throwingLlmClient,
      // Explicit, so constructing the test doesn't go looking for a connected device.
      explicitDeviceId = iosDeviceId,
      logsDir = logsDir,
      noLogging = noLogging,
    ) {
      override fun ensureTargetAppIsStopped() = Unit
    }.hostLoggingRule

  /** What the rule leaves on disk after being asked to write a log and hand out a session dir. */
  private fun sessionArtifacts(rule: HostTrailblazeLoggingRule, logsDir: File): Boolean {
    rule.logsRepo.saveLogToDisk(aLog())
    // Every capture site (web network / console / video, and the agent's own sessionDirProvider)
    // asks the repo for the session directory, which creates it unless the repo is read-only.
    rule.logsRepo.getSessionDir(sessionId)
    return File(logsDir, sessionId.value).exists()
  }

  /**
   * Asserts both halves of the flag, which fail independently: the no-op emitter (nothing is even
   * offered for persistence) and the read-only `LogsRepo` (nothing reaches disk).
   *
   * The emitter half is observed through [HostTrailblazeLoggingRule.withLogObserver] rather than
   * through disk, because the read-only repo would swallow the write anyway and make a disk-only
   * assertion pass no matter what the emitter does. Observers fire before the emitter's
   * server-availability probe, and the no-logging branch returns ahead of both, so this stays
   * offline.
   */
  private fun assertWritesNothing(rule: HostTrailblazeLoggingRule, logsDir: File) {
    var emitted = false
    rule.withLogObserver({ emitted = true }) {
      rule.logger.log(
        TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now()),
        aLog(),
      )
    }
    assertThat(emitted).isFalse()
    assertThat(sessionArtifacts(rule, logsDir)).isFalse()
  }

  @Test
  fun `a no-logging Playwright-native run writes no session directory`() {
    val logsDir = tempFolder.newFolder("web-no-logging")
    assertWritesNothing(playwrightRule(logsDir, noLogging = true), logsDir)
  }

  @Test
  fun `a logging Playwright-native run writes its session directory`() {
    // Anti-vacuity companion: without this, the test above would still pass if the rule never
    // wrote anywhere near `logsDir` — or wrote nothing at all.
    val logsDir = tempFolder.newFolder("web-logging")
    assertThat(sessionArtifacts(playwrightRule(logsDir, noLogging = false), logsDir)).isTrue()
  }

  @Test
  fun `a no-logging local-device run writes no session directory`() {
    val logsDir = tempFolder.newFolder("host-no-logging")
    assertWritesNothing(hostRule(logsDir, noLogging = true), logsDir)
  }

  @Test
  fun `a logging local-device run writes its session directory`() {
    val logsDir = tempFolder.newFolder("host-logging")
    assertThat(sessionArtifacts(hostRule(logsDir, noLogging = false), logsDir)).isTrue()
  }
}

package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * Faithful, `bun`-backed coverage of the MCP `initialize` handshake bound — the fix for the
 * daemon-wide wedge (build 3366). Complements [McpSubprocessSessionConnectCleanupTest], which
 * proves the same mechanism against a POSIX `sleep` and so runs even without `bun`; here the
 * hung server is a real `bun` process (`fixture-hangs.js`) that holds its stdio like a genuine
 * MCP server would but never answers `initialize`.
 *
 * Why the `bun` fixture is the stronger guard: `sleep 30` exits on its own after 30s, so even a
 * fully-broken watchdog would eventually let `connect` fail (masking the bug as merely "slow").
 * `fixture-hangs.js` never exits, so the watchdog is the *only* thing that can unblock
 * `connect` — a regression turns these tests into a hang, not a slow pass.
 *
 * Skipped entirely when `bun` isn't on PATH (there's no way to exercise the real spawn path
 * without it); the POSIX-based tests keep the mechanism covered in that case.
 */
class McpSubprocessHandshakeTimeoutTest {

  private val hangingFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture-hangs.js")) {
      "Missing /mcp-fixture/fixture-hangs.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val healthyFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture.js")) {
      "Missing /mcp-fixture/fixture.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("session_handshake_timeout"),
  )

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("handshake", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  /**
   * The real production shape: a `bun` server that opens its stdio but never answers
   * `initialize`. [McpSubprocessSession.connect] must bound the handshake, force-destroy the
   * subprocess, and surface an attributable [McpSubprocessHandshakeTimeoutException] — never
   * park (the subprocess never exits on its own, so parking would be forever).
   */
  @Test fun `connect times out and kills a bun server that never answers initialize`() {
    assumeTrue("bun must be on PATH to spawn the hanging fixture", runtimeAvailable())
    runBlocking {
      val spawned = McpSubprocessSpawner.spawn(
        config = McpServerConfig(script = hangingFixture.absolutePath),
        context = context,
        anchor = hangingFixture.parentFile,
      )
      val capture = StderrCapture()

      val elapsedMs = measureTimeMillis {
        assertFailure {
          McpSubprocessSession.connect(
            spawnedProcess = spawned,
            stderrCapture = capture,
            handshakeTimeoutMillis = 1_000,
          )
        }.isInstanceOf(McpSubprocessHandshakeTimeoutException::class)
      }

      // Bounded on the 1s handshake window + teardown ladder — nowhere near "forever", which is
      // what an unbounded connect against a never-exiting subprocess would take.
      assertThat(elapsedMs).isLessThan(15_000L)
      // The watchdog force-destroyed the subprocess — no orphaned `bun` left behind.
      assertThat(spawned.process.isAlive).isEqualTo(false)
      // Teardown closed the stderr capture on the way out.
      assertThat(capture.isClosed).isEqualTo(true)
    }
  }

  /**
   * Session-startup composition: when one `mcp_servers` entry's handshake hangs, the launcher
   * fails fast (rather than wedging the daemon) and registers nothing — no partial tool set from
   * the healthy sibling slips into the repo. The healthy server is listed first so it fully
   * connects before the hanging one times out, exercising the abort-after-partial-start path.
   */
  @Test fun `launchAll fails fast and registers nothing when a later handshake times out`() {
    assumeTrue("bun must be on PATH to spawn the fixtures", runtimeAvailable())
    val repo = TrailblazeToolRepo(
      DynamicTrailblazeToolSet(name = "handshake-timeout-test", toolClasses = emptySet()),
    )
    val logDir = Files.createTempDirectory("launcher-handshake-timeout-test").toFile()
    try {
      runBlocking {
        assertFailure {
          McpSubprocessRuntimeLauncher.launchAll(
            mcpServers = listOf(
              McpServerConfig(script = healthyFixture.absolutePath),
              McpServerConfig(script = hangingFixture.absolutePath),
            ),
            deviceInfo = deviceInfo,
            config = TrailblazeConfig.DEFAULT,
            sessionId = SessionId("launcher_handshake_timeout"),
            sessionLogDir = logDir,
            toolRepo = repo,
            handshakeTimeoutMillis = 1_000,
          )
        }.isInstanceOf(McpSubprocessHandshakeTimeoutException::class)
      }
      // Fail-fast is atomic: the healthy sibling's tools were staged but never committed, so the
      // repo is left exactly as it started rather than half-populated.
      assertThat(repo.getCurrentToolDescriptors().size).isEqualTo(0)
    } finally {
      logDir.deleteRecursively()
    }
  }

  private fun runtimeAvailable(): Boolean = try {
    BunRuntimeDetector.cached
    true
  } catch (_: NoBunRuntimeException) {
    false
  }
}

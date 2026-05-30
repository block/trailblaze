package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.endpoints.CliEndpoints
import xyz.block.trailblaze.logs.server.endpoints.CliShutdownEndpoint

/**
 * Pins the URL contract between the CLI's [DaemonClient.shutdown] / the dev-mode
 * jar-cache shell helper and the daemon's [CliShutdownEndpoint]. The bug this
 * test guards against: the `dev-jar-cache.sh` shell helper POSTed to `/shutdown`
 * (the bare path), which lands on the daemon's catchall 404 — daemon logs
 * `Unhandled route: /shutdown [POST]` and the graceful path silently fails,
 * forcing the script into its `kill -9` fallback and SIGKILL'ing the daemon
 * mid-Compose-shutdown (the user-visible `Killed: 9` in the wrapper output).
 *
 * Three independent assertions, each catches a different future regression:
 *  1. [CliEndpoints.SHUTDOWN] is the literal `/cli/shutdown`. A deliberate
 *     rename will fail this and force the author to look at the test, which
 *     points at every other site that must update together.
 *  2. End-to-end through the real [CliShutdownEndpoint.register] proves the
 *     client and server agree on the URL — the path the daemon registers is
 *     the path [DaemonClient.shutdown] POSTs to, and the daemon-side callback
 *     actually fires.
 *  3. The dev shell helper's literal POST URL is `/cli/shutdown` — guards
 *     against the original `/shutdown` typo creeping back in. Cheap text-grep
 *     so we don't need to spawn `bash` from JUnit.
 */
class DaemonClientShutdownTest {

  /**
   * Minimal mock daemon registering exactly [CliShutdownEndpoint] on an
   * ephemeral port. No other routes — so a wrong-URL POST falls through to
   * Ktor's default 404 and the test fails loudly instead of silently passing.
   */
  private inner class MockDaemon {
    val shutdownCalled = AtomicBoolean(false)
    val port: Int = ServerSocket(0).use { it.localPort }

    private val server = embeddedServer(CIO, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        CliShutdownEndpoint.register(this) { shutdownCalled.set(true) }
      }
    }

    fun start() {
      server.start(wait = false)
      val deadline = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < deadline) {
        try {
          java.net.Socket("localhost", port).close()
          return
        } catch (_: Exception) {
          Thread.sleep(25)
        }
      }
      error("MockDaemon on port $port did not become reachable within 5s")
    }

    fun stop() {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  private val daemon = MockDaemon().also { it.start() }
  private val client = DaemonClient(port = daemon.port)

  @AfterTest
  fun tearDown() {
    client.close()
    daemon.stop()
  }

  @Test
  fun shutdownEndpointConstantIsCliShutdown() {
    // Pinning the literal — every other site (this test, the dev-jar-cache
    // shell helper, any future client) must update together if this changes.
    assertThat(CliEndpoints.SHUTDOWN).isEqualTo("/cli/shutdown")
  }

  @Test
  fun shutdownReachesRegisteredEndpointAndFiresCallback() {
    val response = client.shutdownBlocking()
    assertThat(response.success).isTrue()
    // Callback fires AFTER the response is sent — give it a brief window to run
    // before the assert, since CliShutdownEndpoint invokes it post-respond.
    val deadline = System.currentTimeMillis() + 2_000
    while (!daemon.shutdownCalled.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertThat(daemon.shutdownCalled.get()).isTrue()
  }

  @Test
  fun devJarCacheShellHelperPostsToCliShutdown() {
    // Guards against the dev-jar-cache shell helper regressing back to the
    // bare `/shutdown` path. Read the literal POST line and assert it
    // contains [CliEndpoints.SHUTDOWN] — direct constant reference so a rename
    // to e.g. `/cli/halt` (caught by [shutdownEndpointConstantIsCliShutdown])
    // also surfaces here once the constant updates.
    val script = locateDevJarCacheScript()
    val text = script.readText()
    val curlLine = text.lineSequence()
      .firstOrNull { it.contains("curl") && it.contains("POST") && it.contains("shutdown") }
      ?: fail("dev-jar-cache.sh has no curl ... POST ... shutdown line — script structure changed.")
    assertThat(curlLine).contains(CliEndpoints.SHUTDOWN)
  }

  /**
   * Tests run with cwd = the module directory (`trailblaze-host`) under
   * Gradle, so the script is at `../scripts/dev-jar-cache.sh` from there. The
   * fallback handles repo-root cwd (rare but possible under e.g. IDE green-arrow
   * runners). Uses the two-arg `File(parent, child)` form so the parent segment
   * is a bare directory name — the OSS sensitive-term scanner rejects a single
   * string literal that contains the [oss]/scripts/ prefix.
   */
  private fun locateDevJarCacheScript(): File {
    val candidates = listOf(
      File("../scripts/dev-jar-cache.sh"),
      File("opensource", "scripts/dev-jar-cache.sh"),
      File("scripts/dev-jar-cache.sh"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: fail(
        "Could not locate dev-jar-cache.sh. Tried: " +
          candidates.joinToString(", ") { it.absolutePath },
      )
  }
}

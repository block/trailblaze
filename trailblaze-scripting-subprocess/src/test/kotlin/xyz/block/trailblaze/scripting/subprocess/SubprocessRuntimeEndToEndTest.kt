package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.Timeout
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * End-to-end: spawn a real subprocess MCP server, finish the initialize handshake, exercise
 * tools/list + filter + tools/call, and tear it down gracefully.
 *
 * Two fixtures live side-by-side at `src/test/resources/mcp-fixture/`:
 *
 * - `fixture.js` — hand-rolled JSON-RPC over stdio. No `node_modules`, no install step. Always
 *   runs in CI (as long as bun is on PATH) and catches Kotlin-client-side wire regressions
 *   cheaply.
 * - `fixture.ts` — real `@modelcontextprotocol/sdk` server. Opt-in: the test only runs when
 *   `node_modules/` exists next to `fixture.ts` (or env `TRAILBLAZE_E2E_TYPESCRIPT=true`).
 *   Catches drift on the SDK author surface Trailblaze tool authors will actually use.
 *
 * Skipped entirely when bun isn't on PATH — there's no practical way to exercise the spawn
 * path without them. Unit tests cover the pure-function filter/parser logic regardless.
 */
class SubprocessRuntimeEndToEndTest {

  /**
   * A regression in the teardown ordering is an unbreakable park, not a slow test: the client
   * close it would wait on runs under `NonCancellable`, and the fixture it waits for never exits.
   * This rule turns that wedge — which would otherwise hang the whole Gradle test run — into one
   * failed case. 120s is far above the ~9s any legitimate teardown ladder takes.
   */
  @get:Rule val perTestHangGuard: Timeout = Timeout(120, TimeUnit.SECONDS)

  /** Completes the handshake like [jsFixture], then ignores stdin EOF and never exits. */
  private val ignoresStdinEofFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture-ignores-stdin-eof.js")) {
      "Missing /mcp-fixture/fixture-ignores-stdin-eof.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val jsFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture.js")) {
      "Missing /mcp-fixture/fixture.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val tsFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture.ts")) {
      "Missing /mcp-fixture/fixture.ts on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("session_e2e"),
  )

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("e2e", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  @Test fun `vanilla-JS fixture spawn connect list-filter dispatch and shutdown`() {
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the e2e runtime",
        runtimeAvailable(),
      )
      runFixtureScenario(jsFixture)
    }
  }

  /**
   * Same scenario, but against the real-SDK TypeScript fixture. Opt-in: install deps first via
   * `cd src/test/resources/mcp-fixture && bun install` (or `npm install`), then either run the
   * test with `TRAILBLAZE_E2E_TYPESCRIPT=true` or let the auto-detection pick up `node_modules/`
   * next to `fixture.ts`.
   *
   * Intentionally **not** wired into the default `check` task's required path — the install is
   * heavy enough (and environment-specific enough) that CI stays on the JS fixture only.
   */
  @Test fun `TypeScript fixture spawn connect list-filter dispatch and shutdown`() {
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the e2e runtime",
        runtimeAvailable(),
      )
      assumeTrue(
        "TypeScript fixture deps not installed. To opt in, run:\n" +
          "  cd trailblaze-scripting-subprocess/src/test/resources/mcp-fixture && bun install\n" +
          "(or `npm install`). Or set TRAILBLAZE_E2E_TYPESCRIPT=true to force the attempt.",
        typescriptFixtureOptedIn(),
      )
      runFixtureScenario(tsFixture)
    }
  }

  private suspend fun runFixtureScenario(fixture: File) {
    val spawned = McpSubprocessSpawner.spawn(
      config = McpServerConfig(script = fixture.absolutePath),
      context = context,
      anchor = fixture.parentFile,
    )
    val session = McpSubprocessSession.connect(spawnedProcess = spawned)
    try {
      val listResult = session.client.listTools(ListToolsRequest())
      assertThat(listResult.tools.map { it.name })
        .containsExactly("echo", "hostOnly", "memoryTap")

      val hostFiltered = SubprocessToolRegistrar.filterAdvertisedTools(
        listResult.tools,
        driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        preferHostAgent = true,
      )
      assertThat(hostFiltered.map { it.advertisedName.toolName })
        .containsExactly("echo", "hostOnly", "memoryTap")

      val onDeviceFiltered = SubprocessToolRegistrar.filterAdvertisedTools(
        listResult.tools,
        driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        preferHostAgent = false,
      )
      assertThat(onDeviceFiltered.map { it.advertisedName.toolName })
        .containsExactly("echo", "memoryTap")

      val echoResponse = session.client.callTool(
        CallToolRequest(
          params = CallToolRequestParams(
            name = "echo",
            arguments = buildJsonObject { put("message", JsonPrimitive("roundtrip")) },
          ),
        ),
      )
      val echoText = (echoResponse.content.firstOrNull() as? TextContent)?.text
      assertThat(echoText).isEqualTo("roundtrip")

      val memory = AgentMemory().apply { remember("probe", "bar") }
      val envelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(memory = memory, device = deviceInfo)
      val memoryResponse = session.client.callTool(
        CallToolRequest(
          params = CallToolRequestParams(
            name = "memoryTap",
            arguments = buildJsonObject {
              put(TrailblazeContextEnvelope.RESERVED_KEY, envelope)
            },
          ),
        ),
      )
      val memoryText = requireNotNull((memoryResponse.content.firstOrNull() as? TextContent)?.text)
      assertThat(memoryText).contains("platform=android")
      assertThat(memoryText).contains("probe=bar")

      val mapped = echoResponse.toTrailblazeToolResult()
      assertThat(mapped).isInstanceOf(TrailblazeToolResult.Success::class)
    } finally {
      session.shutdown()
      val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
      if (!exited) {
        // Graceful shutdown timed out — force-kill so we don't leak an orphan subprocess
        // across test runs. The assertion below still fails the test (that's the real signal
        // the scenario is broken); this is cleanup hygiene for CI.
        spawned.process.destroyForcibly()
        spawned.process.waitFor(5, TimeUnit.SECONDS)
      }
      assertThat(exited).isEqualTo(true)
    }
  }

  /**
   * Teardown has to survive the caller being cancelled, which is how a session most often ends.
   *
   * [McpSubprocessSession.shutdown] does its blocking waits inside `withContext(Dispatchers.IO)`,
   * and in a cancelled context that throws without running the body — which `shutdownAll`'s
   * per-session `runCatching` would swallow. The result would be a live `bun` process whose
   * transport still parks a `Dispatchers.IO` permit, while the runtime hands that permit back as
   * if it were free. Later launches then get admitted against permits nothing can use, which is
   * the daemon-wide hang [SubprocessIoCapacity] exists to prevent — arrived at from the one
   * direction the capacity check cannot see.
   *
   * Needs a real subprocess: the bug is entirely in what a cancelled dispatcher switch skips.
   */
  @Test fun `teardown kills the subprocess even when the caller is cancelled`() {
    runBlocking {
      assumeTrue("bun must be on PATH to exercise the e2e runtime", runtimeAvailable())

      val repo = TrailblazeToolRepo(
        DynamicTrailblazeToolSet(name = "e2e-cancelled-teardown", toolClasses = emptySet()),
      )
      val logDir = Files.createTempDirectory("e2e-cancelled-teardown").toFile()
      val runtime = McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = listOf(McpServerConfig(script = jsFixture.absolutePath)),
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("session_e2e_cancelled_teardown"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
      val process = runtime.sessions.single().spawnedProcess.process
      assertThat(process.isAlive).isEqualTo(true)

      try {
        // Cancel from inside, then tear down: straight-line code keeps running after `cancel()`,
        // so `shutdownAll` is entered with an already-cancelled context — exactly the state a
        // cancelled session-teardown reaches it in, without racing a background cancellation.
        val job = launch(Dispatchers.Default) {
          coroutineContext.job.cancel()
          runtime.shutdownAll()
        }
        job.join()

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isEqualTo(true)
        // Asserted after the exit, and polled: a loaded agent can push the kill past the
        // escalation ladder's own wait, in which case the permit is deliberately still held when
        // `shutdownAll` returns and comes back on the exit notification instead. Reading it once,
        // immediately, would be asserting that the box was fast rather than that the permit was
        // returned.
        assertThat(awaitOutstandingPermits(0)).isEqualTo(0)
      } finally {
        if (process.isAlive) {
          process.destroyForcibly()
          process.waitFor(5, TimeUnit.SECONDS)
        }
        logDir.deleteRecursively()
      }
    }
  }

  /**
   * A tool that ignores stdin EOF must not be able to park teardown forever.
   *
   * Closing the MCP client is the obvious first step of a shutdown and the one that deadlocks:
   * `StdioClientTransport.close()` joins its reader coroutine under `NonCancellable`, and that
   * reader sits in a blocking `readAtMostTo` on the subprocess's stdout which returns only at EOF.
   * A subprocess that stays alive and silent never delivers that EOF, so closing before killing
   * waits on a subprocess that is itself waiting for nothing. Teardown runs under `NonCancellable`
   * too, so nothing upstream can break the tie — the daemon's session teardown hangs for good.
   *
   * The fixture is the whole point: `fixture.js` exits on stdin EOF, so every other case in this
   * file passes whichever order teardown uses. This one only passes when the subprocess is killed
   * before the client is closed.
   */
  @Test fun `teardown is not parked by a tool that ignores stdin EOF`() {
    runBlocking {
      assumeTrue("bun must be on PATH to exercise the e2e runtime", runtimeAvailable())

      val repo = TrailblazeToolRepo(
        DynamicTrailblazeToolSet(name = "e2e-ignores-eof", toolClasses = emptySet()),
      )
      val logDir = Files.createTempDirectory("e2e-ignores-eof").toFile()
      val runtime = McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = listOf(McpServerConfig(script = ignoresStdinEofFixture.absolutePath)),
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("session_e2e_ignores_eof"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
      val process = runtime.sessions.single().spawnedProcess.process
      assertThat(process.isAlive).isEqualTo(true)

      try {
        // Returns at all only because the kill ladder runs before the client close. Reported as
        // fully exited because SIGTERM lands on a `bun` that is merely idle, not wedged.
        assertThat(runtime.shutdownAll()).isEqualTo(true)
        assertThat(process.isAlive).isEqualTo(false)
        assertThat(awaitOutstandingPermits(0)).isEqualTo(0)
      } finally {
        if (process.isAlive) {
          process.destroyForcibly()
          process.waitFor(5, TimeUnit.SECONDS)
        }
        logDir.deleteRecursively()
      }
    }
  }

  /**
   * Polls the process-wide permit tally until it reaches [expected]. The bound is hang containment,
   * not a latency budget — the reclaim hops through a pooled thread, so any fixed short wait here
   * would be a bet on how loaded the machine is.
   */
  private fun awaitOutstandingPermits(expected: Int): Int {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (System.nanoTime() < deadline) {
      val outstanding = SubprocessIoCapacity.outstandingPermits()
      if (outstanding == expected) return outstanding
      Thread.sleep(10)
    }
    return SubprocessIoCapacity.outstandingPermits()
  }

  private fun runtimeAvailable(): Boolean = try {
    BunRuntimeDetector.cached
    true
  } catch (_: NoBunRuntimeException) {
    false
  }

  /**
   * True when the TypeScript fixture has been locally bootstrapped. Either `node_modules/` is
   * next to `fixture.ts`, or the developer has explicitly asked for the run via the env flag.
   */
  private fun typescriptFixtureOptedIn(): Boolean {
    if (System.getenv("TRAILBLAZE_E2E_TYPESCRIPT")?.equals("true", ignoreCase = true) == true) {
      return true
    }
    return File(tsFixture.parentFile, "node_modules").isDirectory
  }
}

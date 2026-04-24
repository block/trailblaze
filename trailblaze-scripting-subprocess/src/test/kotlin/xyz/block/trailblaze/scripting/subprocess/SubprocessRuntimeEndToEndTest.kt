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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * End-to-end: spawn a real subprocess MCP server, finish the initialize handshake, exercise
 * tools/list + filter + tools/call, and tear it down gracefully.
 *
 * Two fixtures live side-by-side at `src/test/resources/mcp-fixture/`:
 *
 * - `fixture.js` — hand-rolled JSON-RPC over stdio. No `node_modules`, no install step. Always
 *   runs in CI (as long as bun/tsx is on PATH) and catches Kotlin-client-side wire regressions
 *   cheaply.
 * - `fixture.ts` — real `@modelcontextprotocol/sdk` server. Opt-in: the test only runs when
 *   `node_modules/` exists next to `fixture.ts` (or env `TRAILBLAZE_E2E_TYPESCRIPT=true`).
 *   Catches drift on the SDK author surface Trailblaze tool authors will actually use.
 *
 * Skipped entirely when bun/tsx isn't on PATH — there's no practical way to exercise the spawn
 * path without them. Unit tests cover the pure-function filter/parser logic regardless.
 */
class SubprocessRuntimeEndToEndTest {

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
        "bun or tsx must be on PATH to exercise the e2e runtime",
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
        "bun or tsx must be on PATH to exercise the e2e runtime",
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

  private fun runtimeAvailable(): Boolean = try {
    NodeRuntimeDetector.cached
    true
  } catch (_: NoCompatibleTsRuntimeException) {
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

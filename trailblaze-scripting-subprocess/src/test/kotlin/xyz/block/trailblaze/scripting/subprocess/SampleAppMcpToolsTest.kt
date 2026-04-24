package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.endsWith
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * CI coverage for the **reference** subprocess MCP tools shipped with the
 * android-sample-app example (`examples/android-sample-app/trailblaze-config/mcp/tools.ts`).
 *
 * `SubprocessRuntimeEndToEndTest` exercises the runtime via dedicated `fixture.js` / `fixture.ts`
 * under this module's own test resources — those catch regressions in the runtime, not in the
 * public-facing example. This test plugs the same spawn/handshake/dispatch pipeline into the
 * actual `tools.ts` a Trailblaze user would copy, so CI notices the moment the reference
 * example stops working.
 *
 * Gating mirrors `fixture.ts` in the sibling test: skip when the runtime (bun/tsx) isn't on
 * PATH or when `node_modules/` hasn't been installed. The Gradle `installSampleAppMcpTools`
 * task tries `bun install` then `npm install` in the sample-app's `mcp/` directory, so CI
 * with either runtime installed runs through — elsewhere the test skips cleanly instead of
 * blocking the build.
 */
class SampleAppMcpToolsTest {

  /**
   * Absolute path to the committed sample-app `tools.ts`. Injected by Gradle via a
   * `systemProperty(...)` on `tasks.test`, so the value is stable regardless of the JVM's
   * working directory — Gradle / IntelliJ / CLI runners all see the same path. Set at
   * build-configuration time in `build.gradle.kts`.
   */
  private val sampleAppToolsTs: File by lazy {
    val path = checkNotNull(System.getProperty("trailblaze.sampleApp.mcp.toolsTs")) {
      "trailblaze.sampleApp.mcp.toolsTs system property not set — this test must run via " +
        "the Gradle `test` task (which configures the property). If you're running from an " +
        "IDE, point the run-configuration's working directory at the module's projectDir, " +
        "or invoke via Gradle instead."
    }
    File(path)
  }

  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("sample_app_mcp_tools_test"),
  )

  @Test fun `sample-app tools dot ts spawns and advertises the documented tool set`() {
    runBlocking {
      // Runtime gating: bun/tsx absence is a legitimate "skip" even in CI — we can't
      // install a TS runtime from inside a JUnit test. CI agents are expected to have
      // one on PATH; when they do, the test must run (not silently skip).
      assumeTrue(
        "bun or tsx must be on PATH to exercise the sample-app MCP tool e2e path",
        runtimeAvailable(),
      )
      // Deps gating: in CI the install task either succeeded (node_modules exists, pass)
      // or failed (node_modules missing, fail LOUD) — a silent skip here would defeat the
      // whole point of wiring this test into CI. On dev machines without bun/npm the
      // install task logs a warning and the test skips cleanly. `CI=true` is the standard
      // env var virtually every CI system sets; opt-out via
      // `TRAILBLAZE_SAMPLE_APP_MCP_TEST=skip` for the rare "CI agent is intentionally
      // missing the runtime and we don't want red builds for it yet" case.
      val depsInstalled = sampleAppDepsInstalled()
      if (ciRequiresDepsInstalled()) {
        assertTrue(
          "CI requires sample-app MCP tool deps to be installed. The " +
            "`installSampleAppMcpTools` Gradle task should have done this — check the " +
            "Gradle log for warnings. Expected node_modules at: " +
            "${File(sampleAppToolsTs.parentFile, "node_modules").absolutePath}",
          depsInstalled,
        )
      } else {
        assumeTrue(
          "Sample-app MCP tool deps not installed. Run from the repo root:\n" +
            "  cd examples/android-sample-app/trailblaze-config/mcp && bun install\n" +
            "(or `npm install`). CI installs these automatically via the " +
            "`installSampleAppMcpTools` Gradle task.",
          depsInstalled,
        )
      }

      val spawned = McpSubprocessSpawner.spawn(
        config = McpServerConfig(script = sampleAppToolsTs.absolutePath),
        context = context,
        anchor = sampleAppToolsTs.parentFile,
      )
      val session = McpSubprocessSession.connect(spawnedProcess = spawned)
      try {
        // The two tools the README documents. If someone adds / removes / renames one,
        // this test fails loud — forcing the doc + YAML to move together.
        val listResult = session.client.listTools(ListToolsRequest())
        assertThat(listResult.tools.map { it.name })
          .containsExactlyInAnyOrder("generateTestUser", "currentEpochMillis")

        // generateTestUser round-trip. Parse the JSON the tool returns — substring checks
        // on `@example.com` would accept a name like `user@example.com Smith` which the
        // real tool would never produce; parsing + endsWith on the structured `email`
        // field makes the assertion actually match the shape `tools.ts` documents.
        val userResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "generateTestUser",
              arguments = JsonObject(emptyMap()),
            ),
          ),
        )
        val userText = checkNotNull((userResponse.content.firstOrNull() as? TextContent)?.text) {
          "generateTestUser returned no text content — response: $userResponse"
        }
        val userJson = Json.parseToJsonElement(userText).jsonObject
        val name = userJson["name"]?.jsonPrimitive?.content
        val email = userJson["email"]?.jsonPrimitive?.content
        assertThat(name).isNotNull()
        assertThat(email).isNotNull().endsWith("@example.com")

        // currentEpochMillis round-trip. Minimum sanity: the value parses as a long and
        // falls inside a window that won't be true for stale cached bytes. The subprocess
        // generates a fresh timestamp on each call; a real regression would return a
        // non-numeric payload or fail to respond at all.
        val timeResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "currentEpochMillis",
              arguments = JsonObject(emptyMap()),
            ),
          ),
        )
        val timeText = checkNotNull((timeResponse.content.firstOrNull() as? TextContent)?.text) {
          "currentEpochMillis returned no text content — response: $timeResponse"
        }
        val millis = checkNotNull(timeText.toLongOrNull()) {
          "currentEpochMillis text didn't parse as a long: $timeText"
        }
        // Loose bound: 2020-01-01 ≤ result ≤ now + 1h. Keeps the test stable across clock
        // skew but catches the "returned 0" / "returned garbage" failure modes. assertk
        // surfaces this as a clean assertion failure (not IllegalStateException) so the
        // JUnit report reads as "assertion failed," not "unexpected error."
        val lowerBound = 1_577_836_800_000L // 2020-01-01T00:00:00Z
        val upperBound = System.currentTimeMillis() + 3_600_000L
        assertThat(millis).isBetween(lowerBound, upperBound)
      } finally {
        session.shutdown()
        val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
        if (!exited) {
          // Force-kill so the test run doesn't leak a subprocess, and assert the graceful
          // path worked — a silent force-kill would mask a real regression in
          // `session.shutdown()`. The assertion below fails the test in that case.
          spawned.process.destroyForcibly()
          spawned.process.waitFor(5, TimeUnit.SECONDS)
        }
        assertThat(exited).isEqualTo(true)
      }
    }
  }

  private fun runtimeAvailable(): Boolean = try {
    NodeRuntimeDetector.cached
    true
  } catch (_: NoCompatibleTsRuntimeException) {
    false
  }

  private fun sampleAppDepsInstalled(): Boolean =
    File(sampleAppToolsTs.parentFile, "node_modules").isDirectory

  /**
   * Whether the deps-installed gate should `assertTrue` (fail loud) instead of `assumeTrue`
   * (skip silently). True when `CI=true` — the standard env var virtually every CI system
   * sets — so the test that was added to catch bitrot actually catches bitrot instead of
   * skipping on a broken environment.
   *
   * Two env vars, deliberately narrow:
   * - `CI=true` (standard) — upgrade the gate to a hard assertion.
   * - `TRAILBLAZE_SAMPLE_APP_MCP_TEST=skip` — escape hatch that forces the gate back to
   *   a skip, for the "CI agent doesn't have bun/npm and we don't want red builds for it
   *   yet" case. **Only the literal value `skip` is recognized**; any other value is
   *   ignored and the `CI` check decides. Keeps the interface unambiguous — there's no
   *   guessing at whether `=required` / `=strict` / `=on` do anything.
   */
  private fun ciRequiresDepsInstalled(): Boolean {
    if (System.getenv("TRAILBLAZE_SAMPLE_APP_MCP_TEST")?.equals("skip", ignoreCase = true) == true) {
      return false
    }
    return System.getenv("CI")?.equals("true", ignoreCase = true) == true
  }
}

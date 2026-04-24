package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class McpSubprocessSpawnerTest {

  private val tmpDir = Files.createTempDirectory("mcp-spawner-test").toFile()
  private val scriptFile = File(tmpDir, "fixture.ts").apply { writeText("// no-op fixture\n") }
  private val bunRuntime = NodeRuntime.Bun(File("/opt/homebrew/bin/bun"))
  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("session_test"),
  )

  @AfterTest fun cleanup() {
    tmpDir.deleteRecursively()
  }

  @Test fun `resolveScriptPath accepts absolute path`() {
    val resolved = McpSubprocessSpawner.resolveScriptPath(scriptFile.absolutePath)
    assertThat(resolved).isEqualTo(scriptFile.absoluteFile)
  }

  @Test fun `resolveScriptPath resolves relative against anchor`() {
    val resolved = McpSubprocessSpawner.resolveScriptPath("fixture.ts", anchor = tmpDir)
    assertThat(resolved).isEqualTo(scriptFile.absoluteFile)
  }

  @Test fun `resolveScriptPath errors on missing file with helpful message`() {
    assertFailure {
      McpSubprocessSpawner.resolveScriptPath("does-not-exist.ts", anchor = tmpDir)
    }.messageContains("mcp_servers script not found")
  }

  @Test fun `resolveScriptPath rejects blank path`() {
    assertFailure {
      McpSubprocessSpawner.resolveScriptPath("")
    }.messageContains("must be non-blank")
  }

  @Test fun `configure sets argv cwd and TRAILBLAZE env vars`() {
    val config = McpServerConfig(script = scriptFile.absolutePath)
    val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)

    assertThat(configured.scriptFile).isEqualTo(scriptFile.absoluteFile)
    assertThat(configured.builder.command().toList()).isEqualTo(
      listOf("/opt/homebrew/bin/bun", "run", scriptFile.absolutePath),
    )
    assertThat(configured.builder.directory()).isEqualTo(scriptFile.absoluteFile.parentFile)

    val env = configured.builder.environment()
    assertThat(env["TRAILBLAZE_DEVICE_PLATFORM"]).isEqualTo("ANDROID")
    assertThat(env["TRAILBLAZE_DEVICE_DRIVER"]).isEqualTo("android-ondevice-accessibility")
    assertThat(env["TRAILBLAZE_DEVICE_WIDTH_PX"]).isEqualTo("1080")
    assertThat(env["TRAILBLAZE_DEVICE_HEIGHT_PX"]).isEqualTo("2400")
    assertThat(env["TRAILBLAZE_SESSION_ID"]).isEqualTo("session_test")
    assertThat(env["TRAILBLAZE_TOOLSET_FILE"]).isEqualTo(scriptFile.absolutePath)
    // Default path: daemon uses 30s dispatch timeout, subprocess fetch timeout is 32s
    // (30 + 2s buffer) so the daemon is normally the one that returns a structured error
    // rather than the client aborting first.
    assertThat(env["TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS"]).isEqualTo("32000")
  }

  @Test fun `resolveClientFetchTimeoutMs defaults to 32s and honors daemon override plus buffer`() {
    // The whole point of threading this through is to keep the client's abort above the
    // daemon's callback timeout. A regression that dropped the buffer or ignored the system
    // property would let the client abort first and swallow the daemon's structured error.
    System.clearProperty("trailblaze.callback.timeoutMs")
    assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(32_000L)

    try {
      System.setProperty("trailblaze.callback.timeoutMs", "60000")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(62_000L)

      // Non-positive / non-numeric overrides silently fall back to the default — same
      // "silently-default on typo" tradeoff as the daemon-side resolveTimeoutMs.
      System.setProperty("trailblaze.callback.timeoutMs", "-1")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(32_000L)

      System.setProperty("trailblaze.callback.timeoutMs", "abc")
      assertThat(McpSubprocessSpawner.resolveClientFetchTimeoutMs()).isEqualTo(32_000L)
    } finally {
      System.clearProperty("trailblaze.callback.timeoutMs")
    }
  }

  @Test fun `configure propagates daemon callback timeout override to TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS`() {
    // End-to-end through configure() — proves the resolver's value actually lands in the
    // spawned process's environment, not just the resolver's return value in isolation.
    System.setProperty("trailblaze.callback.timeoutMs", "45000")
    try {
      val config = McpServerConfig(script = scriptFile.absolutePath)
      val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)
      assertThat(configured.builder.environment()["TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS"])
        .isEqualTo("47000")
    } finally {
      System.clearProperty("trailblaze.callback.timeoutMs")
    }
  }

  @Test fun `configure inherits parent environment`() {
    val config = McpServerConfig(script = scriptFile.absolutePath)
    val configured = McpSubprocessSpawner.configure(config, context, bunRuntime)
    val env = configured.builder.environment()

    // PATH is practically universal in inherited env; sanity-checks the inheritance path.
    assertThat(env.keys).contains("PATH")
  }

  @Test fun `configure with tsx runtime produces the tsx argv shape`() {
    val tsxRuntime = NodeRuntime.Tsx(File("/usr/local/bin/tsx"))
    val config = McpServerConfig(script = scriptFile.absolutePath)
    val configured = McpSubprocessSpawner.configure(config, context, tsxRuntime)

    assertThat(configured.builder.command().toList()).isEqualTo(
      listOf("/usr/local/bin/tsx", scriptFile.absolutePath),
    )
  }

  @Test fun `configure rejects command-only entries`() {
    val config = McpServerConfig(command = "python")
    assertFailure {
      McpSubprocessSpawner.configure(config, context, bunRuntime)
    }.messageContains("command: entries not yet supported")
  }
}

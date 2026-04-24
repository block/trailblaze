package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
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
import kotlin.test.Test

/**
 * Unit coverage for [McpSubprocessRuntimeLauncher]. The happy-path spawn + handshake + register
 * pipeline is exercised end-to-end in [SubprocessRuntimeEndToEndTest]; here we focus on the
 * wiring-layer decisions that don't need a real subprocess:
 *
 *  - empty `mcp_servers:` is a cheap no-op (no file handles, no stderr dir created),
 *  - `command:`-only entries are skipped (schema-reserved, not runtime-implemented).
 */
class McpSubprocessRuntimeLauncherTest {

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("unit", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private fun emptyRepo(): TrailblazeToolRepo =
    TrailblazeToolRepo(DynamicTrailblazeToolSet(name = "launcher-test-empty", toolClasses = emptySet()))

  @Test fun `empty mcpServers returns an empty runtime with no side effects`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-empty-test").toFile()

    val runtime = runBlocking {
      McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = emptyList(),
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("launcher_test_empty"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
    }

    assertThat(runtime.sessions).isEmpty()
    // No spawn = no stderr log files created. Establishes the "zero overhead when unused"
    // contract for targets that don't declare mcp_servers.
    assertThat(subprocessStderrFiles(logDir)).isEmpty()

    runBlocking { runtime.shutdownAll() }
    logDir.deleteRecursively()
  }

  @Test fun `command-only entries skip spawn and return empty runtime`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-command-skip-test").toFile()

    // `command:` is schema-reserved but not implemented in this landing — authors might
    // legitimately declare it expecting the follow-up landing, and the launcher should skip it
    // without failing the whole session.
    val commandOnly = listOf(
      McpServerConfig(command = "python", args = listOf("-m", "mcp_example")),
    )

    val runtime = runBlocking {
      McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = commandOnly,
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("launcher_test_cmd"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
    }

    assertThat(runtime.sessions).isEmpty()
    assertThat(subprocessStderrFiles(logDir)).isEmpty()
    // Repo stays untouched — the skipped entry contributes nothing to the registry.
    assertThat(repo.getCurrentToolDescriptors().size).isEqualTo(0)

    runBlocking { runtime.shutdownAll() }
    logDir.deleteRecursively()
  }

  private fun subprocessStderrFiles(logDir: File): List<File> =
    logDir.listFiles()?.filter { it.name.startsWith("subprocess_stderr_") && it.name.endsWith(".log") }.orEmpty()
}

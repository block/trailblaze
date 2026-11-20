package xyz.block.trailblaze.cli

import picocli.CommandLine
import xyz.block.trailblaze.host.install.DeviceInstallResponse
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceInstallCommandTest {

  private val dir: File = createTempDirectory("device-install").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  /** What the daemon was asked to install: device and path. */
  private val asked = mutableListOf<Pair<String, String>>()

  private fun install(vararg args: String): Int {
    val command = DeviceInstallCommand().also {
      it.startDaemon = { true }
      it.installOnDaemon = { _, device, path -> asked += device to path; DeviceInstallResponse(true, "installed") }
    }
    return CommandLine(command).execute(*args)
  }

  @Test
  fun `a relative app path is the caller's, not the daemon's`() {
    File(dir, "app-debug.apk").writeText("apk")
    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { install("app-debug.apk", "--device", "android/emulator-5554") }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertEquals(listOf("android/emulator-5554" to File(dir, "app-debug.apk").absolutePath), asked)
  }

  @Test
  fun `a file that isn't there is refused without asking the daemon`() {
    val exit = CliCallerContext.withCallerCwd(dir.toPath()) { install("missing.apk", "--device", "android/emulator-5554") }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(asked.isEmpty())
  }
}

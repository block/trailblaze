package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailCommandDeviceClassifierMisuseTest {

  private fun runCommand(configure: TrailCommand.() -> Unit): Pair<Int, String> {
    val trailFile = File.createTempFile("trail-device-classifier-misuse-", ".trail.yaml").apply {
      deleteOnExit()
      writeText("config: {}\ntrail:\n  - step: noop\n")
    }
    val command = TrailCommand().apply {
      trailFiles = listOf(trailFile)
      verbose = true
      configure()
    }
    return captureStderr { command.call() }
  }

  @Test
  fun `malformed classifier exits before device resolution`() {
    val (exit, stderr) = runCommand { deviceClassifier = "ios--iphone" }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("hyphen-separated key" in stderr)
  }

  @Test
  fun `classifier plus all-devices is rejected`() {
    val (exit, stderr) = runCommand {
      deviceClassifier = "ios-iphone-es"
      allDevices = true
    }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("single-device run" in stderr)
  }

  @Test
  fun `classifier plus several named devices is rejected`() {
    val (exit, stderr) = runCommand {
      deviceClassifier = "ios-iphone-es"
      devices = listOf("ios/one", "ios/two")
    }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("single-device run" in stderr)
  }
}

package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * Guards the on-device APK-SHA marker write against the quoting regression that disabled server
 * reuse. [AndroidHostAdbUtils.execAdbShellCommand] space-joins argv into a single device-shell
 * command, so the prior `sh -c "<cmd> '…' > path"` wrapper re-split into a bare `sh -c <cmd>` —
 * the SHA argument was dropped, a blank line was written, and the empty marker made
 * `isInstalledApkUpToDate` always false, forcing a reinstall + relaunch on every run.
 */
class PrecompiledApkInstallerTest {

  @Test
  fun `marker write prints the sha directly so it survives the argv space-join`() {
    assertThat(PrecompiledApkInstaller.shaMarkerWriteArgs("deadbeefcafe").joinToString(" "))
      .isEqualTo("printf %s deadbeefcafe > /data/local/tmp/trailblaze-runner-sha.txt")
  }

  @Test
  fun `marker write does not wrap in sh -c (the shape that silently wrote a blank marker)`() {
    assertThat(PrecompiledApkInstaller.shaMarkerWriteArgs("abc123").first()).isEqualTo("printf")
  }
}

package xyz.block.trailblaze.device

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the raw-argv contract of [buildShellCpFallbackCommands], the command plan behind
 * `writeFileToDevice`'s on-device shell-cp fallback.
 *
 * The load-bearing property is the ABSENCE of shell quoting: the on-device transport
 * (UiAutomation → `Runtime.exec`) execs tokens with no shell interpreter, so a `shellEscape()`'d
 * path would carry literal `'` characters and the `cp` would silently fail (no exit-code channel
 * on that transport). Same rule `AdbShellTrailblazeToolTest` pins for `joinCommandRawArgv`.
 */
class ShellCpFallbackCommandsTest {

  @Test
  fun `plan is mkdir of the destination parent then cp, with raw unquoted tokens`() {
    val plan = buildShellCpFallbackCommands(
      stagingPath = "/storage/emulated/0/Android/data/com.example.test/files/setup.json",
      devicePath = "/storage/emulated/0/Download/setup.json",
    )

    assertEquals(
      listOf(
        listOf("mkdir", "-p", "/storage/emulated/0/Download"),
        listOf(
          "cp",
          "/storage/emulated/0/Android/data/com.example.test/files/setup.json",
          "/storage/emulated/0/Download/setup.json",
        ),
      ),
      plan,
    )
  }

  @Test
  fun `no token ever carries shell quoting`() {
    val plan = buildShellCpFallbackCommands(
      stagingPath = "/data/staging/file.bin",
      devicePath = "/storage/emulated/0/Download/file.bin",
    )

    val tokens = plan.flatten()
    assertEquals(emptyList(), tokens.filter { it.contains("'") || it.contains("\"") })
  }

  @Test
  fun `destination without a parent directory skips the mkdir step`() {
    val plan = buildShellCpFallbackCommands(stagingPath = "/tmp/x", devicePath = "file-at-root")

    assertEquals(listOf(listOf("cp", "/tmp/x", "file-at-root")), plan)
  }
}

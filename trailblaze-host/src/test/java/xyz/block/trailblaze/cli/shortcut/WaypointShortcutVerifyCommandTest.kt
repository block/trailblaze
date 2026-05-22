package xyz.block.trailblaze.cli.shortcut

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * Tests pinning the internal helpers `WaypointShortcutVerifyCommand` uses to build the
 * generated trail YAML. The empirical-replay subprocess invocation itself isn't unit-
 * tested here (it shells out to `./trailblaze trail` against a live device), but the
 * pure helpers — `extractToolsBlock` and `reindent` — drive what the inner trail
 * actually executes, and a bug there silently changes the shape of every replay.
 */
class WaypointShortcutVerifyCommandTest {

  @Test
  fun `extractToolsBlock returns the suffix following the tools header line`() {
    val cmd = WaypointShortcutVerifyCommand()
    val yaml = """
      id: auto-foo
      shortcut:
        from: pack/from
        to: pack/to
      tools:
        - tapOnElementBySelector:
            reason: ""
            nodeSelector:
              androidAccessibility:
                resourceIdRegex: "^com\.example:id/btn$"
    """.trimIndent()
    val extracted = cmd.extractToolsBlock(yaml)
    assertTrue(extracted!!.contains("- tapOnElementBySelector:"), "extracted block must start with the tool entry: `$extracted`")
    assertTrue(extracted.contains("resourceIdRegex: \"^com\\.example:id/btn\$\""))
    assertTrue(!extracted.contains("shortcut:"), "must NOT include earlier blocks")
  }

  @Test
  fun `extractToolsBlock returns null when no top-level tools line exists`() {
    val cmd = WaypointShortcutVerifyCommand()
    val yaml = """
      id: auto-foo
      description: "test"
      shortcut:
        from: pack/from
        to: pack/to
    """.trimIndent()
    assertNull(cmd.extractToolsBlock(yaml))
  }

  @Test
  fun `extractToolsBlock drops trailing blank lines`() {
    val cmd = WaypointShortcutVerifyCommand()
    val yaml = "id: x\ntools:\n  - pressBackButton: {}\n\n\n"
    val extracted = cmd.extractToolsBlock(yaml)
    assertEquals("  - pressBackButton: {}", extracted, "trailing blank lines must be trimmed")
  }

  @Test
  fun `call() returns USAGE when cfg validate fails on a blank shortcut from field`() {
    // Pin the new cfg.validate() call: a parseable shortcut yaml whose `from:` is
    // blank deserializes successfully (the field is present as an empty string), but
    // the runtime's validate() rejects it with a clear diagnostic. Without
    // cfg.validate(), the verify command would proceed and the error would only
    // surface deep in the inner trail run with a less actionable message.
    val tmpDir = createTempDirectory(prefix = "verify-validate-test-").toFile()
    try {
      val yamlFile = File(tmpDir, "bad.shortcut.yaml")
      yamlFile.writeText(
        """
        id: auto-bad
        description: "blank from"
        shortcut:
          from: ""
          to: "pack/to"
        parameters: []
        tools:
          - pressBackButton: {}
        """.trimIndent(),
      )
      val cmd = WaypointShortcutVerifyCommand()
      cmd.yamlPath = yamlFile
      cmd.deviceId = "emulator-test"
      cmd.trailOut = File(tmpDir, "out.trail.yaml")
      val exit = cmd.call()
      assertEquals(CommandLine.ExitCode.USAGE, exit, "blank `from:` must return USAGE; got $exit")
      assertNotEquals(0, exit, "cfg.validate failure must NOT report success")
    } finally {
      tmpDir.deleteRecursively()
    }
  }

  @Test
  fun `reindent prefixes every non-blank line, leaves blanks blank`() {
    val cmd = WaypointShortcutVerifyCommand()
    val input = "  - tapOnElementBySelector:\n      reason: \"\"\n\n  - pressBackButton: {}"
    val out = cmd.reindent(input, "    ")
    val lines = out.lines()
    assertEquals("      - tapOnElementBySelector:", lines[0])
    assertEquals("          reason: \"\"", lines[1])
    assertEquals("", lines[2], "blank lines must NOT have trailing whitespace")
    assertEquals("      - pressBackButton: {}", lines[3])
  }

  @Test
  fun `runSubprocessWithTimeout returns the subprocess exit value on a clean exit`() {
    // Pin the happy path: a quick subprocess that exits with a specific code is
    // reported back unchanged. Uses `sh -c 'exit 7'` so we don't depend on `./trailblaze`.
    val cmd = WaypointShortcutVerifyCommand()
    val exit = cmd.runSubprocessWithTimeout(listOf("sh", "-c", "exit 7"), timeoutSecs = 30)
    assertEquals(7, exit, "clean-exit subprocess must report its own exit code, not the timeout sentinel")
  }

  @Test
  fun `runSubprocessWithTimeout returns TIMEOUT_EXIT_CODE when the subprocess exceeds the budget`() {
    // Pin the load-bearing timeout path: a wedged subprocess gets destroy()ed (and,
    // if it doesn't respond, destroyForcibly()ed) and we return the distinct
    // TIMEOUT_EXIT_CODE sentinel so the outer bootstrap can distinguish "exhausted
    // budget" from a normal non-zero exit. Without this test, a refactor that
    // accidentally returns `process.exitValue()` on the timeout path (which on a
    // destroyed process is typically 143 = SIGTERM, NOT 124) would silently regress
    // the contract the bootstrap depends on.
    val cmd = WaypointShortcutVerifyCommand()
    val exit = cmd.runSubprocessWithTimeout(listOf("sleep", "30"), timeoutSecs = 1)
    assertEquals(
      WaypointShortcutVerifyCommand.TIMEOUT_EXIT_CODE, exit,
      "wedged subprocess must return the typed timeout sentinel, not the destroyed-exit signal",
    )
  }
}

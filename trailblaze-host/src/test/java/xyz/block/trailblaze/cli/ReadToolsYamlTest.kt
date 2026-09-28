package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `trailblaze tool --yaml` takes the tool list inline, from a file, or from standard input. */
class ReadToolsYamlTest {

  @get:Rule
  val tempFolder: TemporaryFolder = TemporaryFolder()

  private val steps = "- tap:\n    ref: k973\n- pressKey:\n    keyCode: HOME\n"
  private val noStdin: () -> String = { error("standard input must not be read") }

  @Test
  fun `a relative path is read from the caller's directory`() {
    val callerDir = tempFolder.newFolder("caller")
    callerDir.resolve("steps.yaml").writeText(steps)

    assertEquals(steps, readToolsYaml("steps.yaml", callerDir.toPath(), noStdin))
  }

  @Test
  fun `a dash reads standard input`() {
    assertEquals(steps, readToolsYaml("-", tempFolder.root.toPath()) { steps })
  }

  @Test
  fun `inline yaml is sent as written`() {
    assertEquals(steps, readToolsYaml(steps, tempFolder.root.toPath(), noStdin))
    assertEquals("- tap: {ref: k973}", readToolsYaml("- tap: {ref: k973}", tempFolder.root.toPath(), noStdin))
  }

  @Test
  fun `a yaml path that names no file is rejected with the path`() {
    val error = assertFailsWith<IllegalArgumentException> {
      readToolsYaml("stpes.yaml", tempFolder.root.toPath(), noStdin)
    }
    assertTrue("stpes.yaml" in error.message.orEmpty(), "error should name the path, got: ${error.message}")
  }

  @Test
  fun `a blank file or standard input is rejected by name`() {
    tempFolder.root.resolve("steps.yaml").writeText("\n  \n")

    val fromFile = assertFailsWith<IllegalArgumentException> {
      readToolsYaml("steps.yaml", tempFolder.root.toPath(), noStdin)
    }
    assertEquals("steps.yaml is empty", fromFile.message)
    val fromStdin = assertFailsWith<IllegalArgumentException> {
      readToolsYaml("-", tempFolder.root.toPath()) { "\n" }
    }
    assertEquals("No tools on standard input", fromStdin.message)
  }

  @Test
  fun `a missing file fails the command before it reaches a device`() {
    val cmd = ToolCommand().apply {
      yaml = tempFolder.root.resolve("missing.yaml").path
      step = "test"
    }
    val (exit, stderr) = captureStderr { cmd.call() }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("No such file" in stderr, "stderr should say the file is missing, got: <<$stderr>>")
  }
}

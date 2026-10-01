package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ToolboxHeadlessOptionTest {

  @Test
  fun `toolbox accepts the --headless flag that tool and snapshot take`() {
    val command = ToolboxCommand()
    CommandLine(command).parseArgs("-d", "web", "--headless=true")
    assertEquals("web", command.device)
  }

  /** A flag set shared across commands only works if every command reads it the same way. */
  @Test
  fun `toolbox accepts exactly the --headless spellings snapshot accepts`() {
    for (spelling in listOf("--headless=true", "--headless=false", "--headless")) {
      assertEquals(
        parses(SnapshotCommand(), "-d", "web", spelling),
        parses(ToolboxCommand(), "-d", "web", spelling),
        "toolbox and snapshot disagree on '$spelling'",
      )
    }
  }

  @Test
  fun `toolbox help does not advertise --headless`() {
    assertFalse(CommandLine(ToolboxCommand()).usageMessage.contains("--headless"))
  }

  private fun parses(command: Any, vararg args: String): Boolean =
    try {
      CommandLine(command).parseArgs(*args)
      true
    } catch (_: CommandLine.ParameterException) {
      false
    }
}

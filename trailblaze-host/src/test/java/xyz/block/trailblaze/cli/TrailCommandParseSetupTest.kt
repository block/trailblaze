package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import picocli.CommandLine
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.yaml.TrailblazeYaml

/** Pins `--device-setup`: a non-empty list of single-key tool calls, kept in order, as JSON. */
class TrailCommandParseSetupTest {

  @Test
  fun `keeps the tool calls in order`() {
    val calls = TrailCommand.parseSetup(
      """
      - sample_setAppearance: { appearance: dark }
      - mobile_setDeviceLocale:
          locale: ja-JP
      """.trimIndent(),
      "dark.yaml",
    )
    assertEquals(listOf("sample_setAppearance", "mobile_setDeviceLocale"), calls.map { it.jsonObject.keys.single() })
  }

  @Test
  fun `the JSON it seeds decodes back into tool calls`() {
    val json = TrailCommand.parseSetup("- wait: { seconds: 1 }", "x.yaml").toString()
    assertEquals(1, TrailblazeYaml.Default.decodeTools(json).size)
  }

  @Test
  fun `takes the list as inline JSON as well as a file`() {
    val json = TrailCommand.parseSetupOption("""[{"wait": {"seconds": 1}}]""")
    assertEquals(1, TrailblazeYaml.Default.decodeTools(json).size)
    val file = kotlin.io.path.createTempFile(suffix = ".tools.yaml").toFile().apply {
      writeText("- wait: { seconds: 1 }")
      deleteOnExit()
    }
    assertEquals(json, TrailCommand.parseSetupOption(file.path))
    assertFailsWith<IllegalArgumentException> { TrailCommand.parseSetupOption("missing.tools.yaml") }
  }

  @Test
  fun `rejects anything but a non-empty list of single-key calls`() {
    listOf(
      "",
      "[]",
      "appearance: dark",
      "- appearance",
      "- { a: {}, b: {} }",
      "- a: [1, 2]",
      "- a: {",
    ).forEach { text ->
      assertFailsWith<IllegalArgumentException>(text) { TrailCommand.parseSetup(text, "bad.yaml") }
    }
  }

  @Test
  fun `takes the run's device setup as --device-setup`() {
    val cmd = TrailCommand().also { CommandLine(it).parseArgs("--device-setup", "dark.tools.yaml", "any.trail.yaml") }
    assertEquals("dark.tools.yaml", cmd.deviceSetup)
    assertFailsWith<CommandLine.UnmatchedArgumentException> {
      CommandLine(TrailCommand()).parseArgs("--setup", "dark.tools.yaml", "any.trail.yaml")
    }
  }
}

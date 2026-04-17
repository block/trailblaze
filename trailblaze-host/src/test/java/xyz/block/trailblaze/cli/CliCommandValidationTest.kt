package xyz.block.trailblaze.cli

import java.io.File
import org.junit.Test
import picocli.CommandLine
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.docs.Scenario
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for CLI command validation logic.
 *
 * These tests verify picocli flag parsing and validation rules for
 * [BlazeCommand] and [ConfigCommand] without connecting to a daemon
 * or device. Only the pure-validation code paths (those that return
 * before accessing the uninitialized `parent` field) are exercised.
 */
class CliCommandValidationTest {

  // ---------------------------------------------------------------------------
  // BlazeCommand validation
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Execute tools directly with a natural language objective",
    commands =
      [
        "trailblaze tool tap ref=p386 --device=android -o \"Tap the Sign In button\""
      ],
    description =
      "Use `trailblaze tool` with `--yaml` for direct tool execution. The -o flag provides a natural language objective so the step is recorded with context for self-healing replays.",
    category = "Direct Tool Execution",
  )
  @Test
  fun `blaze without goal returns USAGE`() {
    val cmd = BlazeCommand()
    cmd.goalWords = emptyList()

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Scenario(
    title = "Save session as trail file",
    commands =
      [
        "trailblaze blaze --save trails/test.trail.yaml",
        "trailblaze blaze --save trails/test.trail.yaml --setup 1-3",
        "trailblaze blaze --save trails/test.trail.yaml --no-setup",
      ],
    description =
      "The --save flag writes the session to a trail file. Use --setup to mark leading steps as setup, or --no-setup to mark none. One of --setup or --no-setup is required with --save.",
    category = "Trail Management",
  )
  @Test
  fun `blaze -- setup without save returns USAGE`() {
    val cmd = BlazeCommand()
    cmd.setup = "1-3"
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `blaze -- no-setup without save returns USAGE`() {
    val cmd = BlazeCommand()
    cmd.noSetup = true
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `blaze -- setup and no-setup together returns USAGE`() {
    val cmd = BlazeCommand()
    cmd.savePath = "trail.yaml"
    cmd.setup = "1-3"
    cmd.noSetup = true

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `blaze -- setup without save takes precedence over mutual exclusion check`() {
    // When --setup is provided without --save, the "requires --save" check
    // fires first, before the mutual-exclusion check with --no-setup.
    val cmd = BlazeCommand()
    cmd.setup = "1-3"
    cmd.noSetup = true
    cmd.savePath = null

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  // ---------------------------------------------------------------------------
  // checkSetupComplete — top-level function
  // ---------------------------------------------------------------------------

  @Test
  fun `checkSetupComplete returns null when target is configured`() {
    // Ensure a target is set so setup check passes.
    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = "testapp") }

    val result = checkSetupComplete()

    assertNull(result)
  }

  @Test
  fun `checkSetupComplete returns error when target is not configured`() {
    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = null) }

    val result = checkSetupComplete()

    assertNotNull(result)
  }

  // ---------------------------------------------------------------------------
  // ConfigTargetCommand.applyTarget validation
  // ---------------------------------------------------------------------------

  @Test
  fun `config target with invalid characters returns USAGE`() {
    val cmd = ConfigTargetCommand()
    cmd.targetId = "my-app"

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config target with uppercase normalizes and returns OK`() {
    val cmd = ConfigTargetCommand()
    cmd.targetId = "Myapp"

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config target with valid alphanumeric ID returns OK`() {
    val cmd = ConfigTargetCommand()
    cmd.targetId = "myapp"

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config target none returns OK`() {
    val cmd = ConfigTargetCommand()
    cmd.targetId = "none"

    val exitCode = cmd.call()

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  // ---------------------------------------------------------------------------
  // ConfigCommand.executeConfig validation
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Configure CLI settings",
    commands =
      [
        "trailblaze config llm anthropic/claude-sonnet-4-6",
        "trailblaze config ai-fallback true",
        "trailblaze config agent MULTI_AGENT_V3",
      ],
    description =
      "Read or write CLI configuration keys. Valid keys: llm, ai-fallback, agent, android-driver, ios-driver, mode, device, target. Values are validated before persisting.",
    category = "Configuration",
  )
  @Test
  fun `config executeConfig with unknown key returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("nonexistent-key", null)

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with invalid llm value returns USAGE`() {
    val cmd = ConfigCommand()

    // "llm" expects "provider/model" format; a bare string is invalid.
    val exitCode = cmd.executeConfig("llm", "invalid-no-slash")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with empty llm provider returns USAGE`() {
    val cmd = ConfigCommand()

    // "/model" has an empty provider.
    val exitCode = cmd.executeConfig("llm", "/gpt-4")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with empty llm model returns USAGE`() {
    val cmd = ConfigCommand()

    // "openai/" has an empty model.
    val exitCode = cmd.executeConfig("llm", "openai/")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with invalid boolean for ai-fallback returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("ai-fallback", "maybe")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with valid llm value returns OK`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "openai/gpt-4-1")

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config executeConfig with llm none disables LLM`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "none")

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config executeConfig with llm NONE disables LLM case-insensitive`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("llm", "NONE")

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config executeConfig with valid ai-fallback value returns OK`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("ai-fallback", "true")

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config executeConfig reading known key returns OK`() {
    val cmd = ConfigCommand()

    // Reading a key (no value) should succeed.
    val exitCode = cmd.executeConfig("llm", null)

    assertEquals(CommandLine.ExitCode.OK, exitCode)
  }

  @Test
  fun `config executeConfig with invalid agent value returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("agent", "NONEXISTENT_AGENT")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with invalid android-driver returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("android-driver", "NONEXISTENT_DRIVER")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  @Test
  fun `config executeConfig with invalid ios-driver returns USAGE`() {
    val cmd = ConfigCommand()

    val exitCode = cmd.executeConfig("ios-driver", "ONDEVICE")

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
  }

  // ---------------------------------------------------------------------------
  // Picocli flag parsing via CommandLine
  // ---------------------------------------------------------------------------

  @Scenario(
    title = "Verify a UI assertion without executing actions",
    commands =
      ["trailblaze blaze --verify Check the login button is visible"],
    description =
      "The --verify flag runs observation-only: the agent checks whether a condition holds on the current screen without tapping or typing.",
    category = "Direct Tool Execution",
  )
  @Test
  fun `picocli parses blaze verify flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--verify", "Check", "the", "button", "is", "visible")

    assertEquals(true, cmd.verify)
    assertEquals(listOf("Check", "the", "button", "is", "visible"), cmd.goalWords)
  }

  @Test
  fun `picocli parses blaze save flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--save", "my-trail.yaml")

    assertEquals("my-trail.yaml", cmd.savePath)
  }

  @Test
  fun `picocli parses blaze setup flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--save", "trail.yaml", "--setup", "1-3")

    assertEquals("trail.yaml", cmd.savePath)
    assertEquals("1-3", cmd.setup)
  }

  @Test
  fun `picocli parses blaze no-setup flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--save", "trail.yaml", "--no-setup")

    assertEquals("trail.yaml", cmd.savePath)
    assertEquals(true, cmd.noSetup)
  }

  @Scenario(
    title = "Specify a target app for the session",
    commands =
      ["trailblaze blaze --target myapp Tap login"],
    description =
      "The --target flag selects which app configuration to use, enabling target-specific tools and launch behavior.",
    category = "Configuration",
  )
  @Test
  fun `picocli parses blaze target flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--target", "myapp", "Tap", "login")

    assertEquals("myapp", cmd.target)
    assertEquals(listOf("Tap", "login"), cmd.goalWords)
  }

  @Test
  fun `picocli parses blaze device short flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("-d", "ANDROID", "Tap", "login")

    assertEquals("ANDROID", cmd.device)
  }

  @Test
  fun `picocli parses blaze context flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--context", "Already on home screen", "Tap", "login")

    assertEquals("Already on home screen", cmd.context)
  }

  @Test
  fun `picocli parses blaze verbose short flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("-v", "Tap", "login")

    assertEquals(true, cmd.verbose)
  }

  @Test
  fun `picocli parses blaze fast flag`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs("--fast", "Tap", "login")

    assertEquals(true, cmd.fast)
    assertEquals(listOf("Tap", "login"), cmd.goalWords)
  }

  @Test
  fun `picocli parses blaze with no arguments (zero arity)`() {
    val cmd = BlazeCommand()
    CommandLine(cmd).parseArgs()

    assertEquals(emptyList(), cmd.goalWords)
    assertNull(cmd.savePath)
    assertNull(cmd.setup)
    assertEquals(false, cmd.noSetup)
    assertEquals(false, cmd.verify)
    assertEquals(false, cmd.verbose)
    assertEquals(false, cmd.fast)
    assertNull(cmd.device)
    assertNull(cmd.context)
    assertNull(cmd.target)
  }

  @Scenario(
    title = "Set or list the target app",
    commands = ["trailblaze config target myapp", "trailblaze config target"],
    description =
      "The target subcommand sets the active app target when given an ID, or lists available targets when called without arguments.",
    category = "Configuration",
  )
  @Test
  fun `picocli parses config target subcommand with ID`() {
    val cmd = ConfigCommand()
    val cl = CommandLine(cmd)
    cl.parseArgs("target", "myapp")

    val targetCmd = cl.subcommands["target"]!!.getCommand<ConfigTargetCommand>()
    assertEquals("myapp", targetCmd.targetId)
  }

  @Test
  fun `picocli parses config target subcommand with no arg`() {
    val cmd = ConfigCommand()
    val cl = CommandLine(cmd)
    cl.parseArgs("target")

    val targetCmd = cl.subcommands["target"]!!.getCommand<ConfigTargetCommand>()
    assertNull(targetCmd.targetId)
  }

  @Test
  fun `picocli rejects --target flag on config command`() {
    val cmd = ConfigCommand()
    assertFailsWith<CommandLine.UnmatchedArgumentException> {
      CommandLine(cmd).parseArgs("--target", "myapp")
    }
  }

  // ---------------------------------------------------------------------------
  // parseStepRange
  // ---------------------------------------------------------------------------

  @Test
  fun `parseStepRange single index`() {
    val cmd = BlazeCommand()
    assertEquals(setOf(2), cmd.parseStepRange("2", 5))
  }

  @Test
  fun `parseStepRange range`() {
    val cmd = BlazeCommand()
    assertEquals(setOf(1, 2, 3), cmd.parseStepRange("1-3", 5))
  }

  @Test
  fun `parseStepRange comma-separated`() {
    val cmd = BlazeCommand()
    assertEquals(setOf(1, 3, 5), cmd.parseStepRange("1,3,5", 5))
  }

  @Test
  fun `parseStepRange mixed ranges and indices`() {
    val cmd = BlazeCommand()
    assertEquals(setOf(1, 2, 3, 5), cmd.parseStepRange("1-3,5", 5))
  }

  @Test
  fun `parseStepRange out of bounds returns null`() {
    val cmd = BlazeCommand()
    assertNull(cmd.parseStepRange("0-3", 5))
    assertNull(cmd.parseStepRange("1-6", 5))
  }

  @Test
  fun `parseStepRange invalid format returns null`() {
    val cmd = BlazeCommand()
    assertNull(cmd.parseStepRange("abc", 5))
    assertNull(cmd.parseStepRange("1-2-3", 5))
    assertNull(cmd.parseStepRange("", 5))
  }

  @Test
  fun `parseStepRange reversed range returns null`() {
    val cmd = BlazeCommand()
    assertNull(cmd.parseStepRange("3-1", 5))
  }

  // ---------------------------------------------------------------------------
  // restructureWithSetup
  // ---------------------------------------------------------------------------

  @Test
  fun `restructureWithSetup splits steps into setup and trail`() {
    val cmd = BlazeCommand()
    val yaml =
      """
      - prompts:
          - step: Login to the app
          - step: Navigate to settings
          - step: Change the name
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, setOf(1))

    assert(result.contains("# setup (trailhead)")) { "Should have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
    assert(result.contains("Login to the app")) { "Setup step should be present" }
    assert(result.contains("Navigate to settings")) { "Trail step should be present" }
    assert(result.contains("Change the name")) { "Trail step should be present" }
  }

  @Test
  fun `restructureWithSetup preserves config header`() {
    val cmd = BlazeCommand()
    val yaml =
      """
      - config:
          title: My test
      - prompts:
          - step: Step one
          - step: Step two
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, setOf(1))

    assert(result.contains("title: My test")) { "Config should be preserved" }
    assert(result.contains("# setup (trailhead)")) { "Should have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
  }

  @Test
  fun `restructureWithSetup with no setup indices puts all in trail`() {
    val cmd = BlazeCommand()
    val yaml =
      """
      - prompts:
          - step: Step one
          - step: Step two
      """
        .trimIndent()

    val result = cmd.restructureWithSetup(yaml, emptySet())

    assert(!result.contains("# setup (trailhead)")) { "Should not have setup section" }
    assert(result.contains("# trail")) { "Should have trail section" }
    assert(result.contains("Step one")) { "Step should be present" }
    assert(result.contains("Step two")) { "Step should be present" }
  }

  // ---------------------------------------------------------------------------
  // CONFIG_KEYS consistency
  // ---------------------------------------------------------------------------

  @Test
  fun `all CONFIG_KEYS are recognized by executeConfig`() {
    val cmd = ConfigCommand()
    for (key in CONFIG_KEYS.keys) {
      // Reading any valid key should return OK (not USAGE for unknown key)
      val exitCode = cmd.executeConfig(key, null)
      assertEquals(
        CommandLine.ExitCode.OK,
        exitCode,
        "Config key '$key' should be readable but executeConfig returned $exitCode",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // GroupedCommandListRenderer consistency
  // ---------------------------------------------------------------------------

  @Test
  fun `help renderer groups cover all registered subcommands`() {
    val cliCommand = TrailblazeCliCommand(
      appProvider = { throw UnsupportedOperationException() },
      configProvider = { throw UnsupportedOperationException() },
    )
    val commandLine = CommandLine(cliCommand)
    // Only check visible (non-hidden) subcommands — hidden commands are intentionally
    // excluded from help output and don't need to appear in the grouped renderer.
    val visibleNames = commandLine.subcommands
      .filter { (_, cmd) -> !cmd.commandSpec.usageMessage().hidden() }
      .keys.toSet()

    // Render help to check all visible commands appear
    val helpText = commandLine.usageMessage
    for (name in visibleNames) {
      assert(helpText.contains(name)) {
        "Registered subcommand '$name' not found in help output. " +
          "Update GroupedCommandListRenderer to include it."
      }
    }
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — flat values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs flat string`() {
    val result = KeyValueParser.parse(listOf("ref=Sign In"))
    assertEquals(mapOf("ref" to "Sign In"), result)
  }

  @Test
  fun `parseKeyValuePairs flat integer`() {
    val result = KeyValueParser.parse(listOf("x=100"))
    assertEquals(mapOf("x" to 100), result)
  }

  @Test
  fun `parseKeyValuePairs flat boolean`() {
    val result = KeyValueParser.parse(listOf("longPress=true"))
    assertEquals(mapOf("longPress" to true), result)
  }

  @Test
  fun `parseKeyValuePairs flat double`() {
    val result = KeyValueParser.parse(listOf("scale=1.5"))
    assertEquals(mapOf("scale" to 1.5), result)
  }

  @Test
  fun `parseKeyValuePairs strips surrounding quotes`() {
    val result = KeyValueParser.parse(listOf("""ref="Sign In""""))
    assertEquals(mapOf("ref" to "Sign In"), result)
  }

  @Test
  fun `parseKeyValuePairs multiple flat pairs`() {
    val result = KeyValueParser.parse(listOf("ref=Sign In", "longPress=false"))
    assertEquals(mapOf("ref" to "Sign In", "longPress" to false), result)
  }

  @Test
  fun `parseKeyValuePairs rejects invalid entries without equals`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      KeyValueParser.parse(listOf("noEquals", "ref=OK"))
    }
    assertNotNull(ex.message)
    assertEquals(true, ex.message!!.contains("noEquals"))
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — dot-notation nesting
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs dot-notation one level`() {
    val result = KeyValueParser.parse(listOf("selector.textRegex=Contacts"))
    assertEquals(mapOf("selector" to mapOf("textRegex" to "Contacts")), result)
  }

  @Test
  fun `parseKeyValuePairs dot-notation two levels`() {
    val result = KeyValueParser.parse(
      listOf("selector.childOf.textRegex=Shortcuts"),
    )
    assertEquals(
      mapOf("selector" to mapOf("childOf" to mapOf("textRegex" to "Shortcuts"))),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs dot-notation multiple keys merge`() {
    val result = KeyValueParser.parse(
      listOf("selector.textRegex=Contacts", "selector.idRegex=contacts_btn"),
    )
    assertEquals(
      mapOf("selector" to mapOf("textRegex" to "Contacts", "idRegex" to "contacts_btn")),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs dot-notation with flat keys`() {
    val result = KeyValueParser.parse(
      listOf("selector.textRegex=Contacts", "longPress=false"),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf("textRegex" to "Contacts"),
        "longPress" to false,
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — JSON object values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs JSON object value`() {
    val result = KeyValueParser.parse(
      listOf("""selector={"textRegex":"Contacts","idRegex":"contacts_btn"}"""),
    )
    assertEquals(
      mapOf("selector" to mapOf("textRegex" to "Contacts", "idRegex" to "contacts_btn")),
      result,
    )
  }

  @Test
  fun `parseKeyValuePairs nested JSON object`() {
    val result = KeyValueParser.parse(
      listOf("""selector={"textRegex":"OK","childOf":{"textRegex":"Dialog"}}"""),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf(
          "textRegex" to "OK",
          "childOf" to mapOf("textRegex" to "Dialog"),
        ),
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — JSON array values
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs JSON array of strings`() {
    val result = KeyValueParser.parse(listOf("""traits=["BUTTON","HEADING"]"""))
    assertEquals(mapOf("traits" to listOf("BUTTON", "HEADING")), result)
  }

  @Test
  fun `parseKeyValuePairs JSON array of objects`() {
    val result = KeyValueParser.parse(
      listOf("""items=[{"textRegex":"A"},{"textRegex":"B"}]"""),
    )
    assertEquals(
      mapOf("items" to listOf(mapOf("textRegex" to "A"), mapOf("textRegex" to "B"))),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — indexed list notation
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs indexed list of objects`() {
    val result = KeyValueParser.parse(
      listOf(
        "selector.containsDescendants[0].textRegex=Item 1",
        "selector.containsDescendants[0].idRegex=id_1",
        "selector.containsDescendants[1].textRegex=Item 2",
      ),
    )
    assertEquals(
      mapOf(
        "selector" to mapOf(
          "containsDescendants" to listOf(
            mapOf("textRegex" to "Item 1", "idRegex" to "id_1"),
            mapOf("textRegex" to "Item 2"),
          ),
        ),
      ),
      result,
    )
  }

  // ---------------------------------------------------------------------------
  // parseKeyValuePairs — invalid JSON falls back to string
  // ---------------------------------------------------------------------------

  @Test
  fun `parseKeyValuePairs malformed JSON treated as string`() {
    val result = KeyValueParser.parse(listOf("value={not json"))
    assertEquals(mapOf("value" to "{not json"), result)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — flat args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml empty args`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", emptyMap())
    assertEquals("- tapOnElement: {}", yaml)
  }

  @Test
  fun `buildToolYaml flat string arg`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", mapOf("ref" to "Sign In"))
    assertEquals("- tapOnElement:\n    ref: \"Sign In\"", yaml)
  }

  @Test
  fun `buildToolYaml flat int arg`() {
    val yaml = ToolYamlBuilder.build("tapOnPoint", mapOf("x" to 100, "y" to 200))
    assertEquals("- tapOnPoint:\n    x: 100\n    y: 200", yaml)
  }

  @Test
  fun `buildToolYaml flat boolean arg`() {
    val yaml = ToolYamlBuilder.build("tapOnElement", mapOf("ref" to "OK", "longPress" to true))
    assertEquals("- tapOnElement:\n    ref: \"OK\"\n    longPress: true", yaml)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — nested args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml nested selector`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf("textRegex" to "Contacts"),
      "longPress" to false,
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `buildToolYaml deeply nested selector`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf(
        "textRegex" to "Contacts",
        "childOf" to mapOf(
          "containsChild" to mapOf("textRegex" to "Shortcuts"),
        ),
      ),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |      childOf:
      |        containsChild:
      |          textRegex: "Shortcuts"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // buildToolYaml — list args
  // ---------------------------------------------------------------------------

  @Test
  fun `buildToolYaml list of strings`() {
    val args: Map<String, Any> = mapOf("traits" to listOf("BUTTON", "HEADING"))
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    traits:
      |      - "BUTTON"
      |      - "HEADING"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `buildToolYaml list of objects`() {
    val args: Map<String, Any> = mapOf(
      "selector" to mapOf(
        "containsDescendants" to listOf(
          mapOf("textRegex" to "A"),
          mapOf("textRegex" to "B"),
        ),
      ),
    )
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    selector:
      |      containsDescendants:
      |        - textRegex: "A"
      |        - textRegex: "B"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // End-to-end: parseKeyValuePairs → buildToolYaml
  // ---------------------------------------------------------------------------

  @Test
  fun `end-to-end flat tool invocation`() {
    val args = KeyValueParser.parse(listOf("""ref=Sign In""", "longPress=false"))
    val yaml = ToolYamlBuilder.build("tapOnElement", args)
    assertEquals("- tapOnElement:\n    ref: \"Sign In\"\n    longPress: false", yaml)
  }

  @Test
  fun `end-to-end nested selector via dot-notation`() {
    val args = KeyValueParser.parse(
      listOf(
        "selector.textRegex=Contacts",
        "selector.childOf.containsChild.textRegex=Shortcuts",
        "longPress=false",
      ),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "Contacts"
      |      childOf:
      |        containsChild:
      |          textRegex: "Shortcuts"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `end-to-end JSON selector value`() {
    val args = KeyValueParser.parse(
      listOf("""selector={"textRegex":"OK","below":{"textRegex":"Email"}}""", "longPress=false"),
    )
    val yaml = ToolYamlBuilder.build("tapOnBySelector", args)
    val expected = """
      |- tapOnBySelector:
      |    selector:
      |      textRegex: "OK"
      |      below:
      |        textRegex: "Email"
      |    longPress: false
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  @Test
  fun `end-to-end JSON array value`() {
    val args = KeyValueParser.parse(
      listOf("""containsDescendants=[{"textRegex":"A"},{"textRegex":"B"}]"""),
    )
    val yaml = ToolYamlBuilder.build("assertVisible", args)
    val expected = """
      |- assertVisible:
      |    containsDescendants:
      |      - textRegex: "A"
      |      - textRegex: "B"
    """.trimMargin()
    assertEquals(expected, yaml)
  }

  // ---------------------------------------------------------------------------
  // deriveTestName — companion object helper on TrailCommand
  // ---------------------------------------------------------------------------

  @Test
  fun `deriveTestName returns parent dir for trail yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns parent dir for recording trail yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/recording.trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns parent dir for blaze yaml`() {
    assertEquals(
      "test-counter",
      TrailCommand.deriveTestName(File("/path/to/test-counter/blaze.yaml")),
    )
  }

  @Test
  fun `deriveTestName returns filename without extension for custom name`() {
    assertEquals(
      "my-custom-test.trail",
      TrailCommand.deriveTestName(File("/path/to/my-custom-test.trail.yaml")),
    )
  }

  @Test
  fun `deriveTestName handles root trail file gracefully`() {
    // File("/trail.yaml").absoluteFile.parentFile is "/", whose name is "".
    // The method should not crash; it may return "" or fall back to baseName.
    val result = TrailCommand.deriveTestName(File("/trail.yaml"))
    assertNotNull(result)
  }

  // ---------------------------------------------------------------------------
  // resolveTargetDevice — private helper on TrailCommand
  // ---------------------------------------------------------------------------

  /** Builds a test device summary with the given driver type and instance ID. */
  private fun device(
    driverType: TrailblazeDriverType,
    instanceId: String,
  ) =
    TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = driverType,
      instanceId = instanceId,
      description = "$driverType - $instanceId",
    )

  /** Reflectively invokes the private resolveTargetDevice on a TrailCommand with the given device spec. */
  private fun callResolveTargetDevice(
    deviceSpec: String?,
    allDevices: List<TrailblazeConnectedDeviceSummary>,
    trailDriverType: TrailblazeDriverType? = null,
    trailPlatform: TrailblazeDevicePlatform? = null,
  ): TrailblazeConnectedDeviceSummary? {
    val cmd = TrailCommand()
    val method =
      TrailCommand::class.java.getDeclaredMethod(
        "resolveTargetDevice",
        List::class.java,
        TrailblazeDriverType::class.java,
        TrailblazeDevicePlatform::class.java,
        String::class.java,
      )
    method.isAccessible = true
    return method.invoke(cmd, allDevices, trailDriverType, trailPlatform, deviceSpec)
      as? TrailblazeConnectedDeviceSummary
  }

  private val testDevices =
    listOf(
      device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"),
      device(TrailblazeDriverType.IOS_HOST, "iphone-15-sim"),
      device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "playwright-browser"),
    )

  @Test
  fun `resolveTargetDevice exact instance-id match`() {
    val result = callResolveTargetDevice("emulator-5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, result.trailblazeDriverType)
  }

  @Test
  fun `resolveTargetDevice platform-prefixed instance-id`() {
    val result = callResolveTargetDevice("android/emulator-5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, result.trailblazeDriverType)
  }

  @Test
  fun `resolveTargetDevice platform-only auto-selects first for that platform`() {
    val result = callResolveTargetDevice("android", testDevices)

    assertNotNull(result)
    assertEquals(TrailblazeDevicePlatform.ANDROID, result.platform)
  }

  @Test
  fun `resolveTargetDevice web platform-only prefers PLAYWRIGHT_NATIVE`() {
    val devices =
      listOf(
        device(TrailblazeDriverType.PLAYWRIGHT_ELECTRON, "electron-1"),
        device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "native-browser"),
      )
    val result = callResolveTargetDevice("web", devices)

    assertNotNull(result)
    assertEquals("native-browser", result.trailblazeDeviceId.instanceId)
    assertEquals(TrailblazeDriverType.PLAYWRIGHT_NATIVE, result.trailblazeDriverType)
  }

  @Test
  fun `resolveTargetDevice driver match when no deviceSpec`() {
    val result =
      callResolveTargetDevice(
        deviceSpec = null,
        allDevices = testDevices,
        trailDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      )

    assertNotNull(result)
    assertEquals("playwright-browser", result.trailblazeDeviceId.instanceId)
  }

  @Test
  fun `resolveTargetDevice platform match when no deviceSpec and no driver`() {
    val result =
      callResolveTargetDevice(
        deviceSpec = null,
        allDevices = testDevices,
        trailPlatform = TrailblazeDevicePlatform.WEB,
      )

    assertNotNull(result)
    assertEquals(TrailblazeDevicePlatform.WEB, result.platform)
  }

  @Test
  fun `resolveTargetDevice first available when no deviceSpec, no driver, no platform`() {
    val result = callResolveTargetDevice(deviceSpec = null, allDevices = testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
  }

  @Test
  fun `resolveTargetDevice returns null when instance-id not found`() {
    val result = callResolveTargetDevice("nonexistent-device", testDevices)

    assertNull(result)
  }

  @Test
  fun `resolveTargetDevice returns null when platform has no devices`() {
    val androidOnly = listOf(device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"))
    val result = callResolveTargetDevice("ios", androidOnly)

    assertNull(result)
  }

  @Test
  fun `resolveTargetDevice returns null when driver not found`() {
    val androidOnly = listOf(device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, "emulator-5554"))
    val result =
      callResolveTargetDevice(
        deviceSpec = null,
        allDevices = androidOnly,
        trailDriverType = TrailblazeDriverType.IOS_HOST,
      )

    assertNull(result)
  }

  @Test
  fun `resolveTargetDevice partial instance-id match`() {
    val result = callResolveTargetDevice("5554", testDevices)

    assertNotNull(result)
    assertEquals("emulator-5554", result.trailblazeDeviceId.instanceId)
  }
}

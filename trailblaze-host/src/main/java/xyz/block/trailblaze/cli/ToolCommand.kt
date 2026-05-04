package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Invoke one or more tools by name with key=value arguments.
 *
 * Examples:
 *   trailblaze tool -d android/emulator-5554 tap ref=p386 -o "Tap the Sign In button"
 *   trailblaze tool -d ios/SIM-UUID inputText text="hello" -o "Type hello"
 *   trailblaze tool -d android tap --yaml "- tap:\n    ref: p386" -o "Tap sign in"
 */
@Command(
  name = "tool",
  mixinStandardHelpOptions = true,
  description = ["Run a Trailblaze tool by name (e.g., tap, inputText)"],
)
class ToolCommand : Callable<Int> {

  @Parameters(
    index = "0",
    description = ["Tool name (e.g., web_click, tap)"],
    arity = "0..1",
  )
  var toolName: String? = null

  @Parameters(
    index = "1..*",
    description = ["Tool arguments as key=value pairs (e.g., ref=\"Sign In\")"],
    arity = "0..*",
  )
  var argPairs: List<String> = emptyList()

  @Option(
    names = ["--objective", "-o"],
    required = true,
    description = [
      "Natural language intent — describe what, not how.",
      "If the UI changes, Trailblaze uses this to retry the step with AI.",
      "'Navigate to Settings' survives a redesign; 'tap button at 200,400' does not.",
    ],
  )
  lateinit var objective: String

  @Option(
    names = ["--yaml"],
    description = ["Raw YAML tool sequence (multiple tools in one call)"],
  )
  var yaml: String? = null

  @Option(
    names = ["-d", "--device"],
    required = true,
    description = ["Device: platform (android, ios, web) or platform/id. Required."],
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @Option(
    names = ["--no-screenshots", "--text-only"],
    description = [
      "Skip screenshots — the LLM only sees the textual view hierarchy, no vision " +
        "tokens, and disk logging of screenshots is skipped too. Faster and cheaper " +
        "for short objectives where the visual layout doesn't matter; some tasks need " +
        "vision and will degrade without it."
    ],
  )
  var noScreenshots: Boolean = false

  @Option(
    names = ["--target"],
    description = [
      "Target app ID, saved as the default for future commands. " +
        "List available targets with `trailblaze toolbox` (no args)."
    ],
  )
  var target: String? = null

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    applyBlazeTarget(target)
    val deviceArg = device
    if (deviceArg.isNullOrBlank()) {
      Console.error("Error: --device is required for this command.")
      return CommandLine.ExitCode.USAGE
    }
    return cliReusableWithDevice(
      verbose = verbose,
      device = deviceArg,
      sessionScope = cliDeviceSessionScope(deviceArg),
      webHeadless = headlessOption.resolve(),
    ) { client ->
      val toolsYaml = if (yaml != null) {
        yaml!!
      } else if (toolName != null) {
        val args = try {
          KeyValueParser.parse(argPairs)
        } catch (e: IllegalArgumentException) {
          Console.error("Error: ${e.message}")
          return@cliReusableWithDevice CommandLine.ExitCode.USAGE
        }
        ToolYamlBuilder.build(toolName!!, args)
      } else {
        Console.error("Error: Either a tool name or --yaml must be provided.")
        return@cliReusableWithDevice CommandLine.ExitCode.USAGE
      }
      val arguments = mutableMapOf<String, Any?>("objective" to objective, "tools" to toolsYaml)
      if (noScreenshots) arguments["fast"] = true
      val result = client.callTool("blaze", arguments)
      // Enhance "Unknown tool" / "not valid for the current device/target" errors with
      // CLI-specific guidance. Both surface as plain text in result.content; matching on the
      // marker phrases keeps us decoupled from the rest of the markdown formatting.
      val rejectionMarkers = listOf("Unknown tool", "not valid for the current device/target")
      if (rejectionMarkers.any { result.content.contains(it) }) {
        Console.error(result.content.replace(Regex("\\*\\*.*?\\*\\*\\s*—\\s*"), ""))
        Console.info("Tip: Run 'trailblaze toolbox --device <platform> --target <target>' to see what's available.")
        return@cliReusableWithDevice CommandLine.ExitCode.SOFTWARE
      }
      formatBlazeResultAgent(result)
      blazeExitCode(result)
    }
  }
}

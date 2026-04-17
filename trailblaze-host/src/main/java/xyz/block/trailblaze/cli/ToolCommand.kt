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
 *   trailblaze tool tapOnElement ref="Sign In" -o "Tap the Sign In button"
 *   trailblaze tool inputText text="hello" -o "Type hello"
 *   trailblaze tool tapOnElement --yaml "- tapOnElement:\n    ref: Sign In" -o "Tap sign in"
 */
@Command(
  name = "tool",
  mixinStandardHelpOptions = true,
  description = ["Run a Trailblaze tool by name (e.g., tapOnElement, inputText)"],
)
class ToolCommand : Callable<Int> {

  @Parameters(
    index = "0",
    description = ["Tool name (e.g., web_click, tapOnElement)"],
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
    description = ["Device: platform (android, ios, web) or platform/id"],
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @Option(
    names = ["--fast"],
    description = ["Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens sent to LLM), and skip disk logging. Also enabled by BLAZE_FAST=1 env var."],
  )
  var fast: Boolean = System.getenv("BLAZE_FAST") == "1"

  @Option(
    names = ["--target"],
    description = ["Target app ID. Saved for future commands."],
  )
  var target: String? = null

  override fun call(): Int {
    applyBlazeTarget(target)
    return cliWithDevice(verbose, device) { client ->
      val toolsYaml = if (yaml != null) {
        yaml!!
      } else if (toolName != null) {
        val args = try {
          KeyValueParser.parse(argPairs)
        } catch (e: IllegalArgumentException) {
          Console.error("Error: ${e.message}")
          return@cliWithDevice CommandLine.ExitCode.USAGE
        }
        ToolYamlBuilder.build(toolName!!, args)
      } else {
        Console.error("Error: Either a tool name or --yaml must be provided.")
        return@cliWithDevice CommandLine.ExitCode.USAGE
      }
      val arguments = mutableMapOf<String, Any?>("objective" to objective, "tools" to toolsYaml)
      if (fast) arguments["fast"] = true
      val result = client.callTool("blaze", arguments)
      // Enhance "Unknown tool" errors with CLI-specific guidance
      if (result.content.contains("Unknown tool")) {
        Console.error(result.content.replace(Regex("\\*\\*.*?\\*\\*\\s*—\\s*"), ""))
        Console.info("Tip: Run 'trailblaze toolbox' to see available tools.")
        return@cliWithDevice CommandLine.ExitCode.SOFTWARE
      }
      formatBlazeResultAgent(result)
      blazeExitCode(result)
    }
  }
}

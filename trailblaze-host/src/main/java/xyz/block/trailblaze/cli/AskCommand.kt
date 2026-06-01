package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Ask a question about what's currently visible on a connected device's screen.
 *
 * Connects to the running Trailblaze daemon via MCP and calls the `ask` tool.
 * The daemon must be running (`trailblaze app --headless` or the desktop app).
 *
 * Examples:
 *   trailblaze ask -d android/emulator-5554 "What's the current balance?"
 *   trailblaze ask -d ios/SIM-UUID "What buttons are visible?"
 *   trailblaze ask -d ANDROID "What screen is this?"
 */
@Command(
  name = "ask",
  mixinStandardHelpOptions = true,
  description = ["Ask a question about what's on screen (uses AI vision, no actions taken)"]
)
class AskCommand : Callable<Int> {

  @Parameters(
    description = ["Question about the screen (e.g., 'What's the current balance?')"],
    arity = "1..*",
  )
  lateinit var questionWords: List<String>

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION]
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output (show daemon logs, MCP calls)"]
  )
  var verbose: Boolean = false

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Trim to match `tool` / `step`'s normalization: whitespace-only positional
    // (`trailblaze ask "   "`) records as empty and trips `require-steps` the
    // same way a missing step would. Without this, the gate would let blank
    // questions through here while rejecting them on `tool`.
    val question = questionWords.joinToString(" ").trim()
    requireStepIfConfigured(question, verb = "ask")?.let { return it }
    return cliReusableWithDevice(
      verbose = verbose,
      device = device,
      webHeadless = headlessOption.resolve(),
      verb = "Ask",
    ) { client ->
      val isNewDevice = !client.hasExistingDevice
      val result = client.callTool("ask", mapOf("question" to question))

      formatAskResult(result)
      // Show Trailblaze session ID after the first action in a new session
      if (isNewDevice && result.isSuccess) {
        client.getTrailblazeSessionId()?.let { Console.info("Session: $it") }
      }

      val hasError = result.isError || try {
        val json = Json.parseToJsonElement(result.content).jsonObject
        !json["error"]?.jsonPrimitive?.content.isNullOrBlank()
      } catch (_: Exception) {
        false
      }
      if (hasError) TrailblazeExitCode.INFRA_FAILED.code else TrailblazeExitCode.SUCCESS.code
    }
  }

  private fun formatAskResult(result: CliMcpClient.ToolResult) = formatAskResultAgent(result)
}

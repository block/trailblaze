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
 *   trailblaze ask "What's the current balance?"
 *   trailblaze ask "What buttons are visible?"
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
    description = ["Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). " +
      "Switches the daemon's active device for all clients. Required for multi-device workflows."]
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output (show daemon logs, MCP calls)"]
  )
  var verbose: Boolean = false

  override fun call(): Int = cliWithDevice(verbose, device) { client ->
    val isNewDevice = !client.hasExistingDevice
    val question = questionWords.joinToString(" ")
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
    if (hasError) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK
  }

  private fun formatAskResult(result: CliMcpClient.ToolResult) = formatAskResultAgent(result)
}

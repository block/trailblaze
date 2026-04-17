package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable

/**
 * Verify an assertion about screen state without taking an action.
 *
 * Exit code 0 if the assertion passes, 1 if it fails.
 *
 * Examples:
 *   trailblaze verify "The Sign In button is visible"
 *   trailblaze verify "The balance shows $50.00"
 */
@Command(
  name = "verify",
  mixinStandardHelpOptions = true,
  description = ["Check a condition on screen and pass/fail (exit code 0/1, ideal for CI)"],
)
class VerifyCommand : Callable<Int> {

  @Parameters(
    description = ["Assertion to verify (e.g., 'The Sign In button is visible')"],
    arity = "1..*",
  )
  lateinit var assertionWords: List<String>

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

  override fun call(): Int {
    val assertion = assertionWords.joinToString(" ")
    return cliWithDevice(verbose, device) { client ->
      val arguments = mutableMapOf<String, Any?>("objective" to assertion, "hint" to "VERIFY")
      if (fast) arguments["fast"] = true
      val result = client.callTool("blaze", arguments)
      formatVerifyResultAgent(result)
    }
  }
}

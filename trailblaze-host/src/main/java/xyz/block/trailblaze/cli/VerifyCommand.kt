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
 *   trailblaze verify -d android/emulator-5554 "The Sign In button is visible"
 *   trailblaze verify -d ios/SIM-UUID "The balance shows $50.00"
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
    description = ["Device: platform (android, ios, web) or platform/id. Defaults to \$TRAILBLAZE_DEVICE."],
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

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Trim to match `tool` / `step`'s normalization: whitespace-only positional
    // (`trailblaze verify "   "`) records as empty and trips `require-steps` the
    // same way a missing step would.
    val assertion = assertionWords.joinToString(" ").trim()
    requireStepIfConfigured(assertion, verb = "verify")?.let { return it }
    return cliReusableWithDevice(
      verbose = verbose,
      device = device,
      webHeadless = headlessOption.resolve(),
    ) { client ->
      val arguments = mutableMapOf<String, Any?>("objective" to assertion, "hint" to "VERIFY")
      if (noScreenshots) arguments["fast"] = true
      val result = client.callTool("step", arguments)
      formatVerifyResultAgent(result)
    }
  }
}

package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Picocli parsing + `require-steps` enforcement on the per-tool natural-language
 * description.
 *
 * The flag rename keeps `-o` / `--objective` as deprecated aliases of the new
 * canonical `-s` / `--step` so existing scripts and authored docs keep working
 * for one release while we transition. The aliases land in the same `step`
 * variable; the enforcement helper does not care which spelling was used.
 */
class ToolCommandStepFlagTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
  }

  private fun isolateAppDataDir() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
  }

  @Test
  fun `-s short form parses into step`() {
    val command = ToolCommand()
    CommandLine(command).parseArgs("-s", "Sign in to the app", "tap", "ref=p33")
    assertEquals("Sign in to the app", command.step)
    assertEquals("tap", command.toolName)
  }

  @Test
  fun `--step long form parses into step`() {
    val command = ToolCommand()
    CommandLine(command).parseArgs("--step", "Sign in", "tap")
    assertEquals("Sign in", command.step)
  }

  @Test
  fun `deprecated --objective alias still parses into step`() {
    val command = ToolCommand()
    CommandLine(command).parseArgs("--objective", "Sign in", "tap")
    assertEquals("Sign in", command.step)
  }

  @Test
  fun `deprecated -o alias still parses into step`() {
    val command = ToolCommand()
    CommandLine(command).parseArgs("-o", "Sign in", "tap")
    assertEquals("Sign in", command.step)
  }

  @Test
  fun `step is optional - no flag means null step`() {
    // Phase 1: tire-kicking authors run `trailblaze tool tap ref=p33` without
    // any per-step description. Picocli MUST accept this — a regression that
    // re-adds `required = true` would fail here with MissingParameterException.
    val command = ToolCommand()
    CommandLine(command).parseArgs("tap", "ref=p33")
    assertNull(command.step)
  }

  @Test
  fun `requireStepIfConfigured returns null when gate is off`() {
    isolateAppDataDir()
    // Default config has requireSteps = false.
    assertNull(requireStepIfConfigured(step = null, verb = "tool"))
    assertNull(requireStepIfConfigured(step = "", verb = "tool"))
    assertNull(requireStepIfConfigured(step = "  ", verb = "tool"))
  }

  @Test
  fun `requireStepIfConfigured returns null when step is present even with gate on`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    assertNull(requireStepIfConfigured(step = "Sign in to the app", verb = "tool"))
  }

  @Test
  fun `tool reports missing-tool error before missing-step when both are missing`() {
    // Regression guard for Codex/Copilot review feedback on PR #3489: with
    // `require-steps=true`, running `trailblaze tool` (no toolName, no --yaml,
    // no -s) used to error with "missing -s/--step" because the helper ran
    // before the local tool-vs-yaml validation. The primary misuse is the
    // missing tool — that's the message the user needs.
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val command = ToolCommand()
    CommandLine(command).parseArgs() // no tool, no --yaml, no -s
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    // The actual stderr text is captured by call()'s Console.error path; we
    // don't assert on it here because routing Console output through a buffer
    // would couple this test to the Console implementation. The exit code
    // alone is the contract — what matters is that we returned BEFORE binding
    // a device, which the helper-first ordering would have prevented.
  }

  @Test
  fun `requireStepIfConfigured returns MISUSE when gate is on and step is blank`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val nullStep = requireStepIfConfigured(step = null, verb = "tool")
    assertNotNull(nullStep)
    assertEquals(TrailblazeExitCode.MISUSE.code, nullStep)

    val blankStep = requireStepIfConfigured(step = "", verb = "tool")
    assertNotNull(blankStep)
    assertEquals(TrailblazeExitCode.MISUSE.code, blankStep)

    val whitespaceStep = requireStepIfConfigured(step = "   ", verb = "tool")
    assertNotNull(whitespaceStep)
    assertEquals(TrailblazeExitCode.MISUSE.code, whitespaceStep)
  }

  // ---------------------------------------------------------------------------
  // requireStepsEnabled — single read site used by both [requireStepIfConfigured]
  // and the `device connect` OOBE upsell. Pin its contract here so the two
  // callsites can't drift; addresses lead-dev finding #4 (config gate
  // duplication) by enforcing one helper for both.
  // ---------------------------------------------------------------------------

  @Test
  fun `requireStepsEnabled reads the persisted gate flag`() {
    isolateAppDataDir()
    assertFalse(requireStepsEnabled()) // default
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    assertTrue(requireStepsEnabled())
    CliConfigHelper.updateConfig { it.copy(requireSteps = false) }
    assertFalse(requireStepsEnabled())
  }

  // ---------------------------------------------------------------------------
  // Verb-routing coverage. The PR added requireStepIfConfigured calls to four
  // commands (tool / blaze / ask / verify). Only `tool` had test coverage in
  // the initial PR; lead-dev finding #3 flagged that the other three calls
  // were dead code as far as the test suite knew. These tests pin the
  // helper's behavior under each verb so a future refactor that drops one of
  // the call sites breaks loudly.
  // ---------------------------------------------------------------------------

  @Test
  fun `requireStepIfConfigured under step verb gates the same way`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    assertEquals(TrailblazeExitCode.MISUSE.code, requireStepIfConfigured(step = "", verb = "step"))
    assertNull(requireStepIfConfigured(step = "Open settings", verb = "step"))
  }

  @Test
  fun `requireStepIfConfigured under ask verb gates the same way`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    assertEquals(TrailblazeExitCode.MISUSE.code, requireStepIfConfigured(step = "  ", verb = "ask"))
    assertNull(requireStepIfConfigured(step = "What is on screen?", verb = "ask"))
  }

  @Test
  fun `requireStepIfConfigured under verify verb gates the same way`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    assertEquals(TrailblazeExitCode.MISUSE.code, requireStepIfConfigured(step = null, verb = "verify"))
    assertNull(requireStepIfConfigured(step = "Sign-in button is visible", verb = "verify"))
  }

  // ---------------------------------------------------------------------------
  // `tool --yaml` only path — addresses lead-dev finding #7. The non-yaml
  // path is exercised by `tool reports missing-tool error before missing-step
  // when both are missing` above; this covers the symmetric yaml-only
  // invocation under the gate.
  // ---------------------------------------------------------------------------

  @Test
  fun `tool --yaml only with no step errors with require-steps on`() {
    // `tool --yaml '- tap: {ref: p33}'` passes the toolName/yaml pre-check
    // (yaml is present), so the next thing the gate sees is the empty step.
    // Result: MISUSE from requireStepIfConfigured, BEFORE device binding.
    // Without this test, a regression that re-orders the gate check past
    // device-bind on the yaml branch would slip through.
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val command = ToolCommand()
    CommandLine(command).parseArgs("--yaml", "- tap:\n    ref: p33")
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `tool --yaml with blank string is treated like missing --yaml`() {
    // Regression for lead-dev round 2 finding #2: `--yaml ""` previously
    // passed the null-check and would have built an empty `tools` arg for the
    // daemon. The pre-flight now uses `isNullOrBlank()` so an empty/whitespace
    // yaml routes through the same MISUSE as missing yaml. Gate is off so
    // `require-steps` can't be the thing that fires here — the empty-yaml
    // branch has to be what catches it.
    isolateAppDataDir() // require-steps stays default (false)
    val command = ToolCommand()
    CommandLine(command).parseArgs("--yaml", "   ")
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `tool with malformed key=value pair errors before device binding`() {
    // Regression for lead-dev round 2 finding #5: the pre-flight KeyValueParser
    // catches typos like `tap ref` (missing `=`) and exits MISUSE before any
    // daemon work. Without this test, a refactor that moves the parse back
    // inside the wrapper would silently re-introduce the failure mode where
    // a typo turns into a device-bind error.
    isolateAppDataDir() // gate stays off
    val command = ToolCommand()
    CommandLine(command).parseArgs("tap", "ref") // no `=`
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // Full-flow integration tests for blaze / ask / verify under
  // `require-steps=true`. The earlier verb-routing tests only call the helper
  // directly; these go through `command.call()` so a future refactor that
  // drops the helper invocation from a command's body breaks loudly. Each
  // command picks the smallest possible parseArgs() shape that picocli will
  // accept yet still leaves the natural-language input blank — that's where
  // the gate has to fire before device binding.
  // ---------------------------------------------------------------------------

  @Test
  fun `step with blank description errors before device binding when require-steps on`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val command = StepCommand()
    // arity="0..*" lets picocli accept zero positionals; the existing
    // `step.isEmpty()` branch in StepCommand would catch this even with the
    // gate off, but the test pins the gate path as well.
    CommandLine(command).parseArgs()
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `ask with whitespace-only question errors before device binding when require-steps on`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val command = AskCommand()
    // arity="1..*" requires ≥1 positional; whitespace passes picocli but the
    // command's own `.trim()` reduces it to empty and the gate fires.
    CommandLine(command).parseArgs("   ")
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `verify with whitespace-only assertion errors before device binding when require-steps on`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig { it.copy(requireSteps = true) }
    val command = VerifyCommand()
    CommandLine(command).parseArgs("   ")
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }
}

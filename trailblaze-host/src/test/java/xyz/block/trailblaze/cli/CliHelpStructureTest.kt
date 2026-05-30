package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins the *structure* of the rendered top-level `trailblaze --help` output:
 *
 *  - The `Drive:` group is first and contains the device-driving primitives
 *    (`snapshot`, `tool`, `toolbox`) — not `blaze`/`ask`/`verify`.
 *  - The `Built-in agent:` group is last and contains `blaze`/`ask`/`verify`,
 *    with the LLM-dependency note rendered alongside it.
 *  - There is no `Users:` / `Agents:` footer — the index doesn't second-guess
 *    the audience.
 *
 * Why pin these as tests instead of relying on `CliHelpBaselineTest`: that test
 * snapshots picocli's *default* renderer output, which has no grouped sections.
 * The grouped renderer ([GroupedCommandListRenderer]) is what users actually see
 * via `./trailblaze --help`, and a regression in it (group renamed, group order
 * shuffled, LLM note dropped) wouldn't show up in the baseline file. These
 * assertions guard the contract directly.
 *
 * Isolates `trailblaze.appdata.dir` so the test never touches the developer's
 * real settings file — defensive, even though this version of the renderer no
 * longer reads config.
 */
class CliHelpStructureTest {

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

  /**
   * Builds a fresh root CommandLine with the GroupedCommandListRenderer wired up — same
   * setup `TrailblazeCli.run` / `executeForDaemon` / `TrailblazeCliCommand.call()` use.
   */
  private fun renderedHelp(): String {
    val cl = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked during help wiring") },
        configProvider = { error("configProvider must not be invoked during help wiring") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    cl.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer()
    return cl.usageMessage
  }

  /**
   * Matches the prefix `GroupedCommandListRenderer` prints for each command row:
   * 2 leading spaces + the command name + a separator (space for single-name commands,
   * `,` for commands rendered with a picocli alias like `step, blaze`). Using the
   * formatted column prefix avoids false positives from descriptions that contain the
   * same letters (e.g. a description mentioning `step by step` would not match because
   * the description column wraps with more than two leading spaces).
   */
  private fun hasRow(text: String, cmd: String): Boolean =
    "  $cmd " in text || "  $cmd," in text

  @Test
  fun `Drive group lists the device-driving primitives only`() {
    isolateAppDataDir()
    val help = renderedHelp()
    val driveIdx = help.indexOf("Drive:")
    val trailIdx = help.indexOf("Trail:")
    assertTrue(driveIdx in 0 until trailIdx, "Drive: must come before Trail:. Got:\n$help")

    val driveSection = help.substring(driveIdx, trailIdx)
    for (cmd in listOf("snapshot", "tool", "toolbox")) {
      assertTrue(hasRow(driveSection, cmd), "`$cmd` row should appear under Drive. Got:\n$driveSection")
    }
    // The AI commands belong in `Built-in agent:`, not `Drive:` — pinning this catches a
    // future regression that moves them back to the top group.
    for (cmd in listOf("step", "ask", "verify")) {
      assertTrue(
        !hasRow(driveSection, cmd),
        "`$cmd` row must NOT appear under Drive:; it belongs in Built-in agent:. Got:\n$driveSection",
      )
    }
  }

  @Test
  fun `Built-in agent group is last and lists step ask verify with LLM note`() {
    isolateAppDataDir()
    val help = renderedHelp()
    val groupIdx = help.indexOf(GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME)
    assertTrue(
      groupIdx >= 0,
      "Help must contain the `${GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME}` heading. Got:\n$help",
    )
    // Every other named group should appear *before* the Built-in agent group.
    for (earlier in listOf("Drive:", "Trail:", "Setup:")) {
      val idx = help.indexOf(earlier)
      assertTrue(
        idx in 0 until groupIdx,
        "`$earlier` should appear before `${GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME}`. Got:\n$help",
      )
    }
    val agentSection = help.substring(groupIdx)
    for (cmd in listOf("step", "ask", "verify")) {
      assertTrue(hasRow(agentSection, cmd), "`$cmd` row should appear under Built-in agent:. Got:\n$agentSection")
    }
    // The LLM-dependency note is anchored to this group — its absence means OSS users with
    // no LLM see commands without the "requires LLM" callout, which is the cliff we're
    // trying to avoid.
    assertTrue(
      GroupedCommandListRenderer.BUILT_IN_AGENT_LLM_NOTE in agentSection,
      "LLM-dependency note must be rendered under Built-in agent:. Got:\n$agentSection",
    )
  }

  @Test
  fun `help emits no Users or Agents footer`() {
    // The renderer used to split selected examples into `Users:` and `Agents:` footers,
    // but the CLI serves both audiences with the same primitives — splitting suggested
    // two surfaces when there's only one. This test pins the absence so a future re-
    // introduction of either heading shows up loudly.
    isolateAppDataDir()
    val help = renderedHelp()
    assertTrue("Users:" !in help, "Help must not contain a Users: heading. Got:\n$help")
    assertTrue("Agents:" !in help, "Help must not contain an Agents: heading. Got:\n$help")
  }

  @Test
  fun `Built-in agent group entries all resolve to registered subcommands`() {
    // Pins the contract between the literal command names in the renderer's
    // `Built-in agent:` group and the `@Command(name = …)` strings on StepCommand /
    // AskCommand / VerifyCommand. If either side is renamed without updating the other,
    // the renderer would silently skip the entry. This test catches that drift at CI
    // time instead of in production.
    //
    // `blaze` is also checked — it remains a picocli alias of `step`, so the subcommand
    // map should resolve both keys to the same child.
    val cl = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked during help wiring") },
        configProvider = { error("configProvider must not be invoked during help wiring") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    for (name in listOf("step", "blaze", "ask", "verify")) {
      assertNotNull(
        cl.subcommands[name],
        "Built-in agent group references `$name`, which has no matching @Command(name = ...) or alias.",
      )
    }
  }
}

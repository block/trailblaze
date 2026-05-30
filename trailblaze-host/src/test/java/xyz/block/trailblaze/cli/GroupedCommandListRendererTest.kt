package xyz.block.trailblaze.cli

import java.util.concurrent.Callable
import org.junit.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the alias-dedupe behaviour of [GroupedCommandListRenderer].
 *
 * Picocli's [CommandLine.Help.subcommands] exposes one map entry per alias, all pointing
 * at the same [CommandLine.Help] instance. Without dedupe, an aliased command (e.g.
 * `@Command(name = "run", aliases = ["trail"])`) would slip through the renderer's
 * group lookup (which keys by the canonical name) AND show up in the "Other:" tail
 * (which iterates every leftover key). The result on `trailblaze --help` would be
 * `run, trail` appearing under "Other:" instead of under "Trail:" — a regression
 * caught by manual smoke during PR #3375.
 *
 * The renderer routes through [canonicalSubcommands] now, which drops alias keys.
 * This test wires up the real renderer against a synthetic command tree (so the test
 * isn't coupled to TrailblazeCliCommand's exact subcommand list) and asserts:
 *
 *  - the aliased command renders under its group's heading (keyed by the canonical
 *    name), not under "Other:",
 *  - the alias name does not surface as a separate entry anywhere in the rendered
 *    listing.
 *
 * Uses `run` / `ask` as the synthetic command names because they're in the real
 * "Trail:" and "Built-in agent:" groups of [GroupedCommandListRenderer.groups] — the
 * test needs at least one name from a real group to exercise the group-lookup path.
 */
class GroupedCommandListRendererTest {

  @Command(
    name = "run",
    aliases = ["trail"],
    description = ["Synthetic aliased command for the renderer test."],
  )
  private class FakeRunWithAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(
    name = "ask",
    description = ["Synthetic non-aliased command for the renderer test."],
  )
  private class FakeAskNoAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(
    name = "fakeroot",
    subcommands = [FakeRunWithAlias::class, FakeAskNoAlias::class],
  )
  private class FakeRoot : Callable<Int> {
    override fun call(): Int = 0
  }

  private fun renderHelp(): String {
    val cli = CommandLine(FakeRoot())
    cli.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer()
    return cli.usageMessage
  }

  @Test
  fun `aliased command renders under its canonical-name group, not under 'Other'`() {
    val output = renderHelp()

    // The aliased command MUST appear under "Trail:" (the group that contains "run"),
    // keyed by the canonical name on its own line. The previous broken behaviour
    // rendered `run, trail` under "Other:".
    val trailGroupIndex = output.indexOf("Trail:")
    val otherGroupIndex = output.indexOf("Other:")
    assertTrue(trailGroupIndex >= 0, "Expected 'Trail:' group header in:\n$output")

    val runEntryIndex = output.indexOf("\n  run ")
    assertTrue(
      runEntryIndex > trailGroupIndex,
      "Expected `run` entry under 'Trail:' group, got rendered output:\n$output",
    )
    if (otherGroupIndex >= 0) {
      assertTrue(
        runEntryIndex < otherGroupIndex,
        "Expected `run` entry to come BEFORE 'Other:' group, got:\n$output",
      )
    }
  }

  @Test
  fun `alias name does not surface as a separate entry`() {
    val output = renderHelp()
    // The alias `trail` must not appear as a standalone entry — neither prefixed by
    // two spaces (the entry indent) nor as a `run, trail` rendering. Picocli's default
    // grouped-list renderer would show `run, trail`; the custom renderer prints the
    // canonical name only.
    assertFalse(
      output.contains("\n  trail "),
      "Alias 'trail' should not be its own listing entry. Got:\n$output",
    )
    assertFalse(
      output.contains("run, trail"),
      "GroupedCommandListRenderer should print only the canonical name, not the alias. Got:\n$output",
    )
  }

  @Test
  fun `non-aliased command still renders correctly under its group`() {
    val output = renderHelp()
    // `ask` is in the Built-in agent group (was "Blaze:" pre-refactor — the rename to
    // "Built-in agent:" landed alongside the AI-commands restructure that moved
    // blaze/ask/verify to a dedicated bottom section with an LLM-dependency note).
    val agentGroupIndex = output.indexOf(GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME)
    val askEntryIndex = output.indexOf("\n  ask ")
    assertTrue(
      agentGroupIndex >= 0,
      "Expected '${GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME}' group header in:\n$output",
    )
    assertTrue(
      askEntryIndex > agentGroupIndex,
      "Expected `ask` entry under '${GroupedCommandListRenderer.BUILT_IN_AGENT_GROUP_NAME}' group, got:\n$output",
    )
  }

  // The above test uses `FakeAskNoAlias` (no aliases) alongside the aliased `FakeRunWithAlias`,
  // so the non-aliased pass-through through `canonicalSubcommands()` is implicitly covered.
  // This second `run`-shaped variant pins the explicit case: a command whose canonical name is
  // `run` and that has NO aliases must still land under "Trail:" — guarding against a future
  // refactor that accidentally couples canonical-name resolution to the presence of aliases.

  @Command(
    name = "run",
    description = ["Synthetic non-aliased run-shaped command for the renderer test."],
  )
  private class FakeRunNoAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(name = "fakeroot-no-alias", subcommands = [FakeRunNoAlias::class])
  private class FakeRootNoAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Test
  fun `non-aliased 'run' command still renders under 'Trail' group`() {
    val cli = CommandLine(FakeRootNoAlias())
    cli.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer()
    val output = cli.usageMessage

    val trailGroupIndex = output.indexOf("Trail:")
    val runEntryIndex = output.indexOf("\n  run ")
    val otherGroupIndex = output.indexOf("Other:")
    assertTrue(trailGroupIndex >= 0, "Expected 'Trail:' group header in:\n$output")
    assertTrue(
      runEntryIndex > trailGroupIndex,
      "Expected non-aliased `run` entry under 'Trail:' group, got:\n$output",
    )
    if (otherGroupIndex >= 0) {
      assertTrue(
        runEntryIndex < otherGroupIndex,
        "Expected `run` entry to come BEFORE 'Other:' group, got:\n$output",
      )
    }
  }
}

package xyz.block.trailblaze.cli

import java.util.concurrent.Callable
import org.junit.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the contract of [canonicalSubcommands] — the helper that re-keys picocli's
 * subcommand map by canonical name so aliased commands surface exactly once under
 * the name from `@Command(name = …)`.
 *
 * The helper is load-bearing for three call sites — `GroupedCommandListRenderer`,
 * `CliHelpBaselineTest`, and `CliDocsGenerator` — so this is the single place that
 * directly tests the dedupe contract instead of relying on transitive coverage.
 *
 * Covers:
 *  - empty subcommands → empty result
 *  - single non-aliased command → returned under its canonical name
 *  - aliased command (picocli stores under canonical + each alias key, all pointing
 *    at the same child) → deduped under canonical name only
 *  - insertion order from the source map is preserved
 *  - both the `CommandLine` and `CommandLine.Help` overloads behave identically
 */
class PicocliCanonicalSubcommandsTest {

  @Command(name = "leaf")
  private class LeafNoAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(name = "canonical", aliases = ["alpha", "beta"])
  private class LeafMultiAlias : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(name = "first")
  private class LeafFirst : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(name = "second")
  private class LeafSecond : Callable<Int> {
    override fun call(): Int = 0
  }

  @Command(name = "root")
  private class EmptyRoot : Callable<Int> {
    override fun call(): Int = 0
  }

  @Test
  fun `empty subcommands returns empty map`() {
    val root = CommandLine(EmptyRoot())
    assertEquals(emptyMap(), root.canonicalSubcommands())
  }

  @Test
  fun `single non-aliased command is keyed by its canonical name`() {
    val root =
      CommandLine(EmptyRoot()).apply {
        addSubcommand(LeafNoAlias())
      }
    val result = root.canonicalSubcommands()
    assertEquals(setOf("leaf"), result.keys)
    assertSame(root.subcommands["leaf"], result["leaf"])
  }

  @Test
  fun `aliased command surfaces once keyed by canonical name`() {
    val root =
      CommandLine(EmptyRoot()).apply {
        addSubcommand(LeafMultiAlias())
      }

    // Sanity: picocli's raw map exposes the canonical AND every alias as separate
    // keys, all pointing at the *same* CommandLine. The dedupe must reduce that to
    // a single entry under the canonical name.
    val raw = root.subcommands
    assertTrue(
      raw.keys.containsAll(setOf("canonical", "alpha", "beta")),
      "Expected picocli to expose canonical + every alias key, got: ${raw.keys}",
    )
    val canonicalChild = raw["canonical"]!!
    assertSame(canonicalChild, raw["alpha"])
    assertSame(canonicalChild, raw["beta"])

    val deduped = root.canonicalSubcommands()
    assertEquals(setOf("canonical"), deduped.keys)
    assertSame(canonicalChild, deduped["canonical"])
  }

  @Test
  fun `insertion order from the source map is preserved`() {
    // Picocli's subcommand map is a LinkedHashMap-style structure keyed by
    // registration order. The dedupe builds a fresh LinkedHashMap; verify the
    // helper doesn't accidentally re-order entries by canonical name.
    val root =
      CommandLine(EmptyRoot()).apply {
        addSubcommand(LeafFirst())
        addSubcommand(LeafSecond())
      }
    val canonicalOrder = root.canonicalSubcommands().keys.toList()
    val rawOrder = root.subcommands.keys.toList().filter { it == "first" || it == "second" }
    assertEquals(rawOrder, canonicalOrder)
  }

  @Test
  fun `CommandLine_Help overload matches CommandLine overload`() {
    val root =
      CommandLine(EmptyRoot()).apply {
        addSubcommand(LeafMultiAlias())
        addSubcommand(LeafNoAlias())
      }
    val viaCommandLine = root.canonicalSubcommands().keys.toList()
    val viaHelp = CommandLine.Help(root.commandSpec).canonicalSubcommands().keys.toList()
    // Both overloads must agree on which canonical names survive.
    assertEquals(viaCommandLine, viaHelp)
    // And both must agree it's exactly the two canonical names (not the aliases).
    assertEquals(listOf("canonical", "leaf"), viaCommandLine)
  }
}

package xyz.block.trailblaze.cli

import org.junit.Test
import picocli.CommandLine
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract of [TrailCommand.wasInvokedViaTrailAlias] — the helper that decides
 * whether the one-line stderr deprecation warning fires.
 *
 * The helper walks from the injected `@Spec(SELF)` command spec up to the root
 * [CommandLine] and inspects [picocli.CommandLine.ParseResult.originalArgs] for the
 * first non-option token. Three failure modes the test guards against:
 *
 *  1. Returning `true` when the user typed the canonical name `run` — would produce a
 *     spurious deprecation warning on every invocation of the new command name.
 *  2. Returning `false` when the user typed the alias `trail` — would silently drop the
 *     warning and erode the deprecation signal.
 *  3. Mis-reading the args when an option happens to precede the subcommand (e.g.
 *     `trailblaze -v trail …`, or even `trailblaze --device foo trail` once such a
 *     top-level option exists). The "first non-option token" heuristic is what makes
 *     the helper robust to those shapes.
 *
 * The tests dispatch through the *root* [TrailblazeCliCommand], not a leaf-level
 * `CommandLine(TrailCommand())`, so picocli's real subcommand resolver populates the
 * parse-result chain that the helper walks. Lambdas passed for `appProvider` /
 * `configProvider` throw on access — the alias-detection path must not touch them.
 */
class TrailCommandAliasWarningTest {

  /** Parses `args` against a fresh root CLI tree and returns the matched [TrailCommand]. */
  private fun parseAndGetTrailCommand(vararg args: String): TrailCommand {
    val cliRoot =
      CommandLine(
        TrailblazeCliCommand(
          appProvider = { error("appProvider must not be invoked during alias-detection") },
          configProvider = { error("configProvider must not be invoked during alias-detection") },
        ),
      ).setCaseInsensitiveEnumValuesAllowed(true)
    val parseResult = cliRoot.parseArgs(*args)
    val sub = parseResult.subcommand()
    assertNotNull(sub, "Expected ${args.toList()} to match a subcommand")
    val userObject = sub.commandSpec().userObject()
    assertTrue(
      userObject is TrailCommand,
      "Expected ${args.toList()} to dispatch to TrailCommand, got ${userObject::class}",
    )
    return userObject
  }

  @Test
  fun `'run' canonical name does NOT trigger the deprecation warning`() {
    val cmd = parseAndGetTrailCommand("run", "any.trail.yaml")
    assertFalse(cmd.wasInvokedViaTrailAlias())
  }

  @Test
  fun `'trail' alias triggers the deprecation warning`() {
    val cmd = parseAndGetTrailCommand("trail", "any.trail.yaml")
    assertTrue(cmd.wasInvokedViaTrailAlias())
  }

  @Test
  fun `'trail' with leading top-level options still triggers the deprecation warning`() {
    // Even when top-level option flags precede the subcommand name (the `--all` flag is
    // currently the only such option on TrailblazeCliCommand — see `showAll` field), the
    // "first non-option token" filter has to skip the flag and land on `trail`. Today
    // this is mostly theoretical, but the helper's whole point is to be robust against
    // future top-level flags shadowing the detection.
    val cmd = parseAndGetTrailCommand("--all", "trail", "any.trail.yaml")
    assertTrue(cmd.wasInvokedViaTrailAlias())
  }

  @Test
  fun `'run' with a positional path that contains the word 'trail' does NOT misfire`() {
    // A positional file path like `flows/trail/login.trail.yaml` must not be mistaken
    // for the alias — the first non-option token is the subcommand name `run`, not the
    // file path that comes after it.
    val cmd = parseAndGetTrailCommand("run", "flows/trail/login.trail.yaml")
    assertFalse(cmd.wasInvokedViaTrailAlias())
  }
}

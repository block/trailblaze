package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the one rule that keeps internal diagnostics out of a user's terminal: a command marked
 * [QuietUnlessVerbose] must not print a [Console.log] line unless the user asked for detail.
 *
 * This is the systemic version of a defect that had been fixed one site at a time. A typo'd tool
 * name used to answer with three machine lines above the two sentences that mattered:
 *
 * ```
 * [platformConfigResourceSource] Layering user config from: …
 * TrailblazeSerializationInitializer: discovered 2 YAML-defined tools …
 * [ToolCommand] fast-path rejected 'tpaOn' (not in local registry)
 * Unknown tool: tpaOn.
 * Tip: Run 'trailblaze toolbox …' to see what's available.
 * ```
 *
 * Every one of those lines was already on the internal channel; what leaked was the *timing* —
 * quiet mode was switched on deep inside the daemon-connection helpers, so any path that returned
 * before connecting printed its diagnostics. Asserting on `[ToolCommand]` specifically would pin
 * one site again. These tests pin the policy instead: the dispatch hook that applies it, and the
 * set of real commands that carry the marker.
 */
class UserFacingOutputPolicyTest {

  private var quietModeOnEntry = false

  /**
   * Two of these tests assert what the policy does to process-global quiet mode, so they have to
   * own it rather than assume a starting value. Sharing a JVM with the rest of the host suite,
   * they do not get a loud one: the daemon-connection helpers in [CliInfrastructure] enable quiet
   * mode and deliberately never restore it — the real CLI exits moments later — so any earlier
   * test that drove a command through them leaves the process quiet for good. Running this class
   * alone hides that entirely.
   */
  @BeforeTest
  fun startFromALoudJvm() {
    quietModeOnEntry = Console.isQuietMode()
    Console.disableQuietMode()
  }

  /** Hand the JVM back exactly as found, so this class does not become the next one's surprise. */
  @AfterTest
  fun restoreQuietMode() {
    if (quietModeOnEntry) Console.enableQuietMode() else Console.disableQuietMode()
  }

  /**
   * A command whose output shape is the one under test: an internal breadcrumb on [Console.log],
   * a user-facing sentence on [Console.error]. Registered as an instance, so picocli never has to
   * construct it and it can stay private to this test.
   */
  @Command(name = "marked", mixinStandardHelpOptions = true)
  private class MarkedCommand : Callable<Int>, QuietUnlessVerbose {
    @Option(names = ["-v", "--verbose"])
    var verbose: Boolean = false

    override val verboseRequested: Boolean get() = verbose

    override fun call(): Int {
      Console.log(INTERNAL_LINE)
      Console.error(USER_LINE)
      return TrailblazeExitCode.MISUSE.code
    }
  }

  /** The same shape without the marker — the commands whose primary output IS [Console.log]. */
  @Command(name = "unmarked", mixinStandardHelpOptions = true)
  private class UnmarkedCommand : Callable<Int> {
    override fun call(): Int {
      Console.log(INTERNAL_LINE)
      return TrailblazeExitCode.SUCCESS.code
    }
  }

  @Command(name = "root")
  private class RootCommand : Callable<Int> {
    override fun call(): Int = TrailblazeExitCode.SUCCESS.code
  }

  private fun fixtureCommandLine(): CommandLine = CommandLine(RootCommand())
    .addSubcommand("marked", MarkedCommand())
    .addSubcommand("unmarked", UnmarkedCommand())
    .also { installPerToolHelpExecutionStrategy(it) }

  /** Runs [args] through the real execution strategy and returns what the user would have seen. */
  private fun runFixture(vararg args: String): CapturedConsole<Int> = captureConsole {
    fixtureCommandLine().execute(*args)
  }

  @Test
  fun `a marked command keeps its internal breadcrumb off the terminal`() {
    val captured = runFixture("marked")

    assertFalse(
      INTERNAL_LINE in captured.out || INTERNAL_LINE in captured.err,
      "internal breadcrumb must not reach either user stream: out=${captured.out} err=${captured.err}",
    )
    assertTrue(
      USER_LINE in captured.err,
      "the sentence written for the user must still land on stderr: ${captured.err}",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, captured.result, "the verdict must be unchanged")
  }

  @Test
  fun `--verbose hands the breadcrumb back`() {
    // The breadcrumb is the answer to "which layer rejected me?", so `-v` has to restore it —
    // a policy that silenced it unconditionally would delete the diagnostic rather than defer it.
    val captured = runFixture("marked", "--verbose")

    assertTrue(
      INTERNAL_LINE in captured.out,
      "--verbose must restore the internal channel: ${captured.out}",
    )
    assertTrue(USER_LINE in captured.err, "the user line is unaffected by -v: ${captured.err}")
  }

  @Test
  fun `an unmarked command still prints on the internal channel`() {
    // The policy is opt-in for a reason: `report`, `check` and `compile` publish their primary
    // output through Console.log (the `file://…` line a report run exists to produce). A blanket
    // quiet-by-default would silence the thing the user ran the command for.
    val captured = runFixture("unmarked")

    assertTrue(
      INTERNAL_LINE in captured.out,
      "a command that never opted in must be left alone: ${captured.out}",
    )
  }

  @Test
  fun `the policy does not decide how loud the rest of the JVM is`() {
    // Quiet mode is process-global. The daemon's /cli/exec fast path dispatches many commands
    // through one JVM, and so does this test suite: a command that left quiet mode on would
    // silence every later Console.log in that JVM, turning unrelated assertions into
    // order-dependent flakes. Found in review — `tool --help` really did leak it.
    assertFalse(Console.isQuietMode(), "precondition: this test starts from a loud JVM")

    runFixture("marked")

    assertFalse(Console.isQuietMode(), "quiet mode must not outlive the command that asked for it")
  }

  @Test
  fun `an already-quiet caller is left quiet`() {
    // The restore returns the prior state rather than assuming it was off — a caller that had
    // already quieted the process (STDIO transport, a `run` in progress) keeps its setting.
    // Driven through the policy directly: `captureConsole` lifts quiet mode for its own block, so
    // routing this through it would assert on the harness's restore instead of the policy's.
    val parseResult = fixtureCommandLine().parseArgs("marked")
    Console.enableQuietMode()
    try {
      withUserFacingOutputPolicy(parseResult) {}
      assertTrue(Console.isQuietMode(), "a caller's own quiet mode must survive the command")
    } finally {
      Console.disableQuietMode()
    }
  }

  @Test
  fun `per-tool help is inside the policy, not around it`() {
    // `tool <name> --help` is the one dispatch that doesn't go through picocli's RunLast: it
    // resolves the tool through the daemon, which loads the registry and is every bit as chatty
    // as the run path. Asserted on the scope rather than by rendering, because rendering wants a
    // live daemon. ToolHelpRenderer writes only via Console.info/error, so quieting costs nothing.
    val parseResult = rootCommandLine().parseArgs("tool", "tap", "--help")
    assertEquals("tap", perToolHelpToolName(parseResult), "precondition: this is the help branch")

    var quietDuringDispatch = false
    withUserFacingOutputPolicy(parseResult) { quietDuringDispatch = Console.isQuietMode() }

    assertTrue(quietDuringDispatch, "the help branch must dispatch with the internal channel closed")
    assertFalse(Console.isQuietMode(), "and must restore it afterwards")
  }

  @Test
  fun `every real subcommand with a --verbose flag opts into the policy`() {
    // The sweep, kept honest. A `-v/--verbose` flag is the command telling the user it has two
    // volume levels; without the marker the flag only controls what the *daemon* helpers print,
    // and everything before them leaks. A new command that declares the flag and forgets the
    // marker fails here rather than shipping the leak.
    val unmarked = verboseCommandsWithoutPolicy(rootCommandLine())

    assertEquals(
      EXEMPT_FROM_POLICY,
      unmarked,
      "commands declaring -v/--verbose must implement QuietUnlessVerbose, or be listed as exempt " +
        "with a reason next to EXEMPT_FROM_POLICY",
    )
  }

  private fun rootCommandLine(): CommandLine = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked while inspecting the command tree") },
      configProvider = { error("configProvider must not be invoked while inspecting the command tree") },
    ),
  )

  /** Names, in `parent child` form, of every command that declares `--verbose` without the marker. */
  private fun verboseCommandsWithoutPolicy(commandLine: CommandLine, prefix: String = ""): Set<String> =
    commandLine.subcommands.values.flatMapTo(mutableSetOf()) { sub ->
      val spec = sub.commandSpec
      val name = (prefix + " " + spec.name()).trim()
      val offender = if (spec.findOption("--verbose") != null && spec.userObject() !is QuietUnlessVerbose) {
        setOf(name)
      } else {
        emptySet()
      }
      offender + verboseCommandsWithoutPolicy(sub, name)
    }

  companion object {
    private const val INTERNAL_LINE = "[UserFacingOutputPolicyTest] internal breadcrumb"
    private const val USER_LINE = "Unknown tool: tpaOn."

    /**
     * Commands whose `--verbose` deliberately does NOT mean "be quiet otherwise".
     *
     * `report` publishes its result — the report's `file://` path — through [Console.log], and
     * scopes its own quiet windows with `Console.runQuiet` around the noisy generator phases.
     * Closing the channel for the whole command would delete the output the command exists to
     * produce.
     */
    private val EXEMPT_FROM_POLICY = setOf("report")
  }
}

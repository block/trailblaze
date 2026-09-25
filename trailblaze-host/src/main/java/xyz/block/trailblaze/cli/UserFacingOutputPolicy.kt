package xyz.block.trailblaze.cli

import picocli.CommandLine
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runQuiet

/**
 * Marks a subcommand whose terminal output is meant for a person, so [Console.log] must not reach
 * it unless the user asked for detail with `-v`/`--verbose`.
 *
 * ## The policy
 *
 * [Console.log] is the internal channel and [Console.info] / [Console.error] are the user's. The
 * codebase already follows that split — of the ~2,100 `Console.log` calls in the framework, some
 * 590 open with an internal tag (`[ToolCommand] …`, `TrailblazeSerializationInitializer: …`) and
 * are plainly diagnostics, not prose. What was missing is the guarantee that the internal channel
 * is actually closed while a user-facing command runs.
 *
 * ## Why the per-command opt-in was not enough
 *
 * Quiet mode used to be switched on inside the daemon-connection helpers ([cliWithDaemon] and
 * friends), which is the *last* thing a command does. Everything before that — config layering,
 * serializer init, argument validation — ran with the internal channel wide open. So a command
 * that connected printed clean output, and a command that rejected bad input first printed its
 * diagnostics to the user:
 *
 * ```
 * [platformConfigResourceSource] Layering user config from: /Users/…/trails/config
 * TrailblazeSerializationInitializer: discovered 2 YAML-defined tools (sample: eraseText, pressBack…)
 * [ToolCommand] fast-path rejected 'tpaOn' (not in local registry)
 * Unknown tool: tpaOn.
 * Tip: Run 'trailblaze toolbox --device <platform> --target <target>' to see what's available.
 * ```
 *
 * Two good sentences under three lines of machine output — and worse on the error paths than the
 * success paths, which is backwards. [ToolCommand.emitUnknownToolEnvelope] used to document its
 * breadcrumb as "verbose-only, doesn't reach stderr", which was true of the intent and false of the
 * behavior, because nothing had enabled quiet mode by the time it ran.
 *
 * Applying the policy at dispatch instead fixes every such site at once, including ones not
 * written yet, without touching a single `Console.log` call. The late switches in the connection
 * helpers stay: they still cover the internal entry points that pass `verbose = false` directly
 * and never go through a subcommand. Those switches go through [quietUnlessVerbose], so they
 * restore the channel too.
 */
internal interface QuietUnlessVerbose {
  /** This command's own `-v`/`--verbose` flag. */
  val verboseRequested: Boolean
}

/**
 * Runs [body] — the dispatch of one subcommand — with the internal output channel closed if that
 * subcommand is [QuietUnlessVerbose] and the user did not ask for detail. The channel is put back
 * the way it was found afterwards.
 *
 * Called from the execution strategy, which picocli invokes after it has bound option values onto
 * the command instance, so [QuietUnlessVerbose.verboseRequested] is readable here. Both dispatch
 * branches run inside this scope: per-tool help resolves the tool through the daemon and would
 * otherwise print the same registry chatter above the help text that this policy strips from the
 * run path, and it writes only through [Console.info] / [Console.error], so nothing a reader wants
 * is lost.
 *
 * Picocli's own usage help renders *inside* the dispatch (`RunLast` calls `printHelpIfRequested`
 * from `execute`), so `--help` on a marked command runs with quiet mode on. That is harmless —
 * picocli writes help through the `CommandLine`'s own `PrintWriter`, not [Console]. The one
 * dispatch that never reaches here is a parse error, which picocli routes to the parameter
 * exception handler instead.
 */
internal fun <T> withUserFacingOutputPolicy(parseResult: CommandLine.ParseResult, body: () -> T): T {
  val command = leafParseResult(parseResult).commandSpec().userObject() as? QuietUnlessVerbose
  return if (command == null) body() else quietUnlessVerbose(command.verboseRequested, body)
}

/**
 * Runs [body] with the internal [Console.log] channel closed unless [verbose], then puts the
 * channel back the way it was found.
 *
 * The restore is the whole point. Quiet mode is one process-global flag, so an entry point that
 * only switches it on decides how loud the rest of the JVM will be. That is harmless in a one-shot
 * CLI process which exits moments later, and wrong wherever the JVM outlives the command — the
 * daemon, or the shared test JVM, where one command silences `Console.log` for every test scheduled
 * after it and the failure is charged to whichever class ran next.
 *
 * An already-quiet caller stays quiet: [runQuiet] restores the prior state rather than assuming it
 * was off, so a command that is both marked [QuietUnlessVerbose] and calls a connection helper is
 * covered twice without the inner scope undoing the outer one.
 */
internal inline fun <T> quietUnlessVerbose(verbose: Boolean, body: () -> T): T =
  if (verbose) body() else Console.runQuiet(body)

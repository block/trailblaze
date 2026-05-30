package xyz.block.trailblaze.cli

import picocli.CommandLine

/**
 * Routes `trailblaze tool <name> --help` to per-tool documentation instead of the
 * generic wrapper-options banner picocli would otherwise print.
 *
 * Picocli's default execution strategy ([CommandLine.RunLast]) calls
 * `printHelpIfRequested` very early — before `Callable<Int>.call()` runs — so a flag
 * marked `usageHelp = true` (the standard `--help` from `mixinStandardHelpOptions`)
 * unconditionally short-circuits to the command's static usage. That's the right
 * behavior for almost every subcommand, but it leaves `tool <name> --help` showing
 * the wrapper's options ("Usage: trailblaze tool [-hvV] …") rather than the args of
 * the named tool — the #1 UX wart the OSS skill currently has to teach around.
 *
 * Strategy: wrap the default [CommandLine.RunLast] so we get a chance to inspect the
 * parsed result before help printing happens. If the deepest parsed subcommand is a
 * [ToolCommand] AND `--help` was requested AND a `<toolName>` positional was supplied,
 * we delegate to [ToolHelpRenderer] (same content as `toolbox --name <tool>`) and
 * return its exit code. In every other case — including bare `tool --help` with no
 * `<toolName>` — control falls through to [CommandLine.RunLast] and prints the
 * standard wrapper banner as before.
 *
 * Installed in [TrailblazeCli.run] and [TrailblazeCli.executeForDaemon] alongside the
 * other CommandLine wiring (grouped sections, exception handlers).
 */
internal fun installPerToolHelpExecutionStrategy(commandLine: CommandLine) {
  val defaultStrategy = CommandLine.RunLast()
  commandLine.executionStrategy = CommandLine.IExecutionStrategy { parseResult ->
    val toolName = perToolHelpToolName(parseResult)
    if (toolName != null) return@IExecutionStrategy ToolHelpRenderer.renderHelp(toolName)
    defaultStrategy.execute(parseResult)
  }
}

/**
 * Returns the `<toolName>` positional from a `trailblaze tool <name> --help` parse
 * result, or `null` if the invocation is anything else (different subcommand, no
 * `--help`, or `--help` without a `<toolName>`). Exposed as `internal` so a unit
 * test can pin the routing decision without invoking [ToolHelpRenderer] (which would
 * try to spin up a real daemon).
 */
internal fun perToolHelpToolName(parseResult: CommandLine.ParseResult): String? {
  val leaf = leafParseResult(parseResult)
  val toolCommand = leaf.commandSpec().userObject() as? ToolCommand ?: return null
  if (!leaf.isUsageHelpRequested) return null
  // Reject blank/whitespace-only positional values — `trailblaze tool '' --help` should
  // fall through to the wrapper-options banner rather than divert to the daemon with
  // `name=""` and surface a confusing "tool not found" response.
  return toolCommand.toolName?.takeUnless { it.isBlank() }
}

/** Walk the parsed subcommand chain to its deepest entry. */
private fun leafParseResult(root: CommandLine.ParseResult): CommandLine.ParseResult {
  var current = root
  while (current.subcommand() != null) {
    current = current.subcommand()
  }
  return current
}

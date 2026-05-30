package xyz.block.trailblaze.cli

import picocli.CommandLine

/**
 * Returns this [CommandLine]'s subcommands, deduped and re-keyed by canonical command
 * name.
 *
 * Picocli's [CommandLine.getSubcommands] / [CommandLine.Help.subcommands] expose one
 * map entry per registered name on the subcommand — both the canonical
 * `@Command(name = …)` value AND each `aliases = […]` entry — pointing at the same
 * child. Worse, the iteration-order key picocli stores under may be either the
 * canonical name OR the alias (observed empirically with `@Command(name = "run",
 * aliases = ["trail"])`, where lookups of `subcommands["run"]` returned `null` while
 * `subcommands["trail"]` resolved). That makes a simple filter or `[name]` lookup
 * unreliable for "give me this subcommand by its canonical name."
 *
 * This helper iterates every entry and re-keys by the child's canonical name via
 * `putIfAbsent`, so each logical subcommand ends up exactly once under its canonical
 * name. Insertion order from the original map is preserved.
 *
 * Used by [GroupedCommandListRenderer]'s grouped `--help` rendering, the
 * `CliHelpBaselineTest`'s subcommand walk, and [xyz.block.trailblaze.docs.CliDocsGenerator]'s
 * CLI.md generation — three sites that all need "one row per logical subcommand,
 * keyed by canonical name."
 */
fun CommandLine.canonicalSubcommands(): Map<String, CommandLine> =
  LinkedHashMap<String, CommandLine>().also { byCanonical ->
    for ((_, child) in subcommands) {
      byCanonical.putIfAbsent(child.commandName, child)
    }
  }

/**
 * [CommandLine.Help] counterpart of [canonicalSubcommands]. See that overload for the
 * full rationale — same re-keying, applied to the [CommandLine.Help.subcommands] map
 * the picocli renderer pipeline exposes.
 */
fun CommandLine.Help.canonicalSubcommands(): Map<String, CommandLine.Help> =
  LinkedHashMap<String, CommandLine.Help>().also { byCanonical ->
    for ((_, child) in subcommands()) {
      byCanonical.putIfAbsent(child.commandSpec().name(), child)
    }
  }

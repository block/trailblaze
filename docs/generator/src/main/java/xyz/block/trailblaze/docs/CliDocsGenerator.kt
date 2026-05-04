package xyz.block.trailblaze.docs

import java.io.File
import picocli.CommandLine
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.Model.PositionalParamSpec
import xyz.block.trailblaze.cli.CONFIG_KEYS
import xyz.block.trailblaze.cli.TrailblazeCliCommand
import xyz.block.trailblaze.util.Console

/**
 * Generates markdown documentation for the Trailblaze CLI.
 *
 * Uses Picocli's Model API to extract command metadata and generates
 * a single CLI.md file with all command documentation. Everything is
 * auto-generated from the Picocli annotations and config key registry.
 *
 * The CLI.md file is written to the main docs folder (not generated/)
 * since it's a primary user-facing document.
 */
class CliDocsGenerator(
  private val docsDir: File,
) {

  fun generate() {
    // Create a mock TrailblazeCliCommand for documentation purposes
    // We use empty providers since we only need the command metadata
    val mainCommand = TrailblazeCliCommand(
      appProvider = { throw UnsupportedOperationException("Doc generation only") },
      configProvider = { throw UnsupportedOperationException("Doc generation only") },
    )

    val commandLine = CommandLine(mainCommand)
      .setCaseInsensitiveEnumValuesAllowed(true)

    // Generate single CLI.md page with all commands
    generateCliPage(commandLine)

    Console.log("CLI documentation generated: ${File(docsDir, "CLI.md").absolutePath}")
  }

  private fun generateCliPage(commandLine: CommandLine) {
    val spec = commandLine.commandSpec
    val content = buildString {
      appendLine("# Trailblaze CLI")
      appendLine()
      appendLine(spec.usageMessage().description().joinToString(" ").stripPicocli())
      appendLine()
      appendLine("## Usage")
      appendLine()
      appendLine("```")
      appendLine("trailblaze [OPTIONS] [COMMAND]")
      appendLine("```")
      appendLine()

      appendDeviceClaimsConcepts()

      // Global options
      appendLine("## Global Options")
      appendLine()
      generateOptionsTable(spec.options())
      appendLine()

      // Commands summary table. Skip subcommands marked `hidden = true` — they're
      // intentionally absent from `trailblaze --help` (and the GroupedCommandListRenderer
      // filters them too); leaking them into the public CLI.md mirror would defeat the
      // hidden flag. Same filter is applied to the detailed-section pass below.
      val visibleSubcommands = commandLine.subcommands
        .filterValues { !it.commandSpec.usageMessage().hidden() }
      appendLine("## Commands")
      appendLine()
      appendLine("| Command | Description |")
      appendLine("|---------|-------------|")
      visibleSubcommands.forEach { (name, subCommand) ->
        val desc = subCommand.commandSpec.usageMessage().description().firstOrNull()
          ?.stripPicocli() ?: ""
        appendLine("| `$name` | $desc |")
      }
      appendLine()

      // Detailed documentation for each command (also hidden-filtered).
      visibleSubcommands.forEach { (name, subCommand) ->
        generateCommandSection("trailblaze", name, subCommand)
      }

      appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
    }

    File(docsDir, "CLI.md").writeText(content)
  }

  /**
   * Hand-authored "Concepts" block. Lives in the generator (not a separate
   * markdown file) so it sits at the top of the generated CLI.md alongside
   * the Picocli-derived command tables and can never drift out of sync with
   * the generated content.
   */
  private fun StringBuilder.appendDeviceClaimsConcepts() {
    appendLine("## Device Claims & Sessions")
    appendLine()
    appendLine("Trailblaze tracks device ownership per MCP session so two CLI workflows on")
    appendLine("the same machine cannot accidentally drive the same device at the same time.")
    appendLine("Understanding the model up front saves debugging time when a command")
    appendLine("unexpectedly returns `Error: Device <id> is already in use by another MCP session`.")
    appendLine()
    appendLine("### Two execution models")
    appendLine()
    appendLine("- **One-shot commands** — `ask`, `verify`, `snapshot`, `tool`. Each invocation")
    appendLine("  opens a fresh MCP session, binds the requested device, runs once, and tears")
    appendLine("  the session down. Different-device parallel one-shots are fully isolated.")
    appendLine("- **Reusable workflows** — `blaze`, `blaze --save`, `session start/info/save/recording/stop/end/artifacts/delete`,")
    appendLine("  `device connect`. These persist an MCP session under `/tmp/trailblaze-cli-session-{port}[-scope]`")
    appendLine("  so follow-up commands can reattach. `blaze --save` is the canonical reason —")
    appendLine("  each `blaze` invocation records steps into a per-device scoped session that")
    appendLine("  `blaze --save` later exports as a trail YAML.")
    appendLine()
    appendLine("### Device-claim conflicts (yield-unless-busy)")
    appendLine()
    appendLine("Device-binding commands try to claim the requested device on the daemon.")
    appendLine("If another MCP session already holds the claim, the daemon decides:")
    appendLine()
    appendLine("- **Prior holder is idle** → the new command silently displaces it and proceeds.")
    appendLine("  Idle means \"no MCP tool call currently executing on that session.\"")
    appendLine("- **Prior holder is mid-tool-call** → the new command fails with a `Device …")
    appendLine("  is busy.` block naming the holder, the running tool, and how long it has been")
    appendLine("  running. Wait for it to finish, or stop the holder before retrying.")
    appendLine()
    appendLine("Same-session re-claims are always allowed, so a `blaze` workflow that keeps")
    appendLine("calling into its own scope never trips on this — only cross-session contention")
    appendLine("with a busy holder does.")
    appendLine()
    appendLine("### When a `blaze` scope leaks across commands")
    appendLine()
    appendLine("`blaze --device android \"…\"` opens a `blaze-android` scoped MCP session that")
    appendLine("stays alive on the daemon after the CLI exits, holding the device claim until")
    appendLine("`blaze --save` (or another `blaze --device android`) reattaches. The session")
    appendLine("is idle while it waits, so a subsequent one-shot like `ask --device android`")
    appendLine("just yields and proceeds — the leaked scope no longer blocks unrelated commands.")
    appendLine("If you want to clear it explicitly, `trailblaze app --stop` recycles the daemon")
    appendLine("and drops all in-memory sessions.")
    appendLine()
    appendLine("Note: `session stop` ends the **global** CLI session created by `session start`.")
    appendLine("It does not reap device-scoped `blaze` sessions; use `app --stop` for those.")
    appendLine()
  }

  private fun StringBuilder.generateCommandSection(
    parentPath: String,
    name: String,
    commandLine: CommandLine,
  ) {
    val spec = commandLine.commandSpec
    val fullPath = "$parentPath $name"

    appendLine("---")
    appendLine()
    appendLine("### `$fullPath`")
    appendLine()
    appendLine(spec.usageMessage().description().joinToString(" ").stripPicocli())
    appendLine()

    // Synopsis — show subcommand usage patterns if present (hidden-filtered).
    val visibleSubcommandsHere = commandLine.subcommands
      .filterValues { !it.commandSpec.usageMessage().hidden() }
    val hasSubcommands = visibleSubcommandsHere.isNotEmpty()
    appendLine("**Synopsis:**")
    appendLine()
    appendLine("```")
    if (hasSubcommands) {
      // Show each usage pattern: base command, subcommands
      append(fullPath)
      if (spec.options().any { !it.hidden() }) append(" [OPTIONS]")
      spec.positionalParameters().forEach { param ->
        val paramName = param.paramLabel() ?: param.descriptionKey() ?: "ARG"
        if (param.required()) append(" <$paramName>") else append(" [<$paramName>]")
      }
      appendLine()
      visibleSubcommandsHere.forEach { (subName, _) ->
        appendLine("$fullPath $subName")
      }
    } else {
      append(fullPath)
      if (spec.options().any { !it.hidden() }) append(" [OPTIONS]")
      spec.positionalParameters().forEach { param ->
        val paramName = param.paramLabel() ?: param.descriptionKey() ?: "ARG"
        if (param.required()) append(" <$paramName>") else append(" [<$paramName>]")
      }
      appendLine()
    }
    appendLine("```")
    appendLine()

    // Positional parameters
    val positionals = spec.positionalParameters()
    if (positionals.isNotEmpty()) {
      appendLine("**Arguments:**")
      appendLine()
      generatePositionalsTable(positionals)
      appendLine()
    }

    // Options
    val options = spec.options().filter { !it.hidden() }
    if (options.isNotEmpty()) {
      appendLine("**Options:**")
      appendLine()
      generateOptionsTable(options)
      appendLine()
    }

    // Config-specific: auto-generate config keys table and examples
    if (name == "config") {
      generateConfigKeysSection()
    }

    // Recurse into subcommands, hidden-filtered (e.g. `desktop snapshot` shouldn't
    // surface in the public CLI.md just because a hidden parent transitively includes
    // a non-hidden child).
    if (hasSubcommands) {
      commandLine.subcommands
        .filterValues { !it.commandSpec.usageMessage().hidden() }
        .forEach { (subName, subCommand) ->
          generateCommandSection(fullPath, subName, subCommand)
        }
    }
  }

  private fun StringBuilder.generateConfigKeysSection() {
    appendLine("**Config Keys:**")
    appendLine()
    appendLine("| Key | Description | Valid Values |")
    appendLine("|-----|-------------|-------------|")
    CONFIG_KEYS.values.forEach { configKey ->
      val escapedValues = configKey.validValues.replace("|", "\\|")
      appendLine("| `${configKey.name}` | ${configKey.description} | $escapedValues |")
    }
    appendLine()

    // Hand-crafted examples that show common usage patterns.
    // These reference CONFIG_KEYS names but use realistic values.
    appendLine("**Examples:**")
    appendLine()
    appendLine("```bash")
    appendLine("trailblaze config                                    # Show all settings")
    appendLine("trailblaze config llm                                # Show current LLM provider/model")
    appendLine("trailblaze config llm anthropic/claude-sonnet-4-6    # Set both provider + model")
    appendLine("trailblaze config llm-provider openai                # Set provider only")
    appendLine("trailblaze config llm-model gpt-4-1                  # Set model only")
    appendLine("trailblaze config agent MULTI_AGENT_V3               # Set agent implementation")
    appendLine("trailblaze config models                             # List available LLM models")
    appendLine("trailblaze config agents                             # List agent implementations")
    appendLine("trailblaze config drivers                            # List driver types")
    appendLine("```")
    appendLine()
  }

  private fun StringBuilder.generateOptionsTable(options: List<OptionSpec>) {
    val filteredOptions = options.filter { !it.hidden() }
    if (filteredOptions.isEmpty()) return

    appendLine("| Option | Description | Default |")
    appendLine("|--------|-------------|---------|")
    filteredOptions.forEach { option ->
      val names = option.names().joinToString(", ") { "`$it`" }
      val desc = option.description().joinToString(" ").stripPicocli().replace("|", "\\|")
      val default = if (option.defaultValue() != null && option.defaultValue().isNotEmpty()) {
        "`${option.defaultValue()}`"
      } else {
        "-"
      }
      appendLine("| $names | $desc | $default |")
    }
  }

  private fun StringBuilder.generatePositionalsTable(positionals: List<PositionalParamSpec>) {
    appendLine("| Argument | Description | Required |")
    appendLine("|----------|-------------|----------|")
    positionals.forEach { param ->
      val name = param.paramLabel() ?: param.descriptionKey() ?: "ARG"
      val desc = param.description().joinToString(" ").stripPicocli().replace("|", "\\|")
      val required = if (param.required()) "Yes" else "No"
      appendLine("| `<$name>` | $desc | $required |")
    }
  }

  /** Strip Picocli format placeholders like %n from description strings. */
  private fun String.stripPicocli(): String = replace("%n", " ").trim()
}

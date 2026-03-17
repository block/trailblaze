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

      // Global options
      appendLine("## Global Options")
      appendLine()
      generateOptionsTable(spec.options())
      appendLine()

      // Commands summary table
      appendLine("## Commands")
      appendLine()
      appendLine("| Command | Description |")
      appendLine("|---------|-------------|")
      commandLine.subcommands.forEach { (name, subCommand) ->
        val desc = subCommand.commandSpec.usageMessage().description().firstOrNull()
          ?.stripPicocli() ?: ""
        appendLine("| `$name` | $desc |")
      }
      appendLine()

      // Detailed documentation for each command
      commandLine.subcommands.forEach { (name, subCommand) ->
        generateCommandSection("trailblaze", name, subCommand)
      }

      appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
    }

    File(docsDir, "CLI.md").writeText(content)
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

    // Synopsis — show subcommand usage patterns if present
    val hasSubcommands = commandLine.subcommands.isNotEmpty()
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
      commandLine.subcommands.forEach { (subName, _) ->
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

    // Recurse into subcommands
    if (hasSubcommands) {
      commandLine.subcommands.forEach { (subName, subCommand) ->
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
    appendLine("trailblaze config agent TWO_TIER_AGENT               # Set agent implementation")
    appendLine("trailblaze config set-of-mark false                  # Disable Set of Mark")
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

package xyz.block.trailblaze.docs

import java.io.File
import picocli.CommandLine
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.Model.PositionalParamSpec
import xyz.block.trailblaze.cli.TrailblazeCliCommand

/**
 * Generates markdown documentation for the Trailblaze CLI.
 *
 * Uses Picocli's Model API to extract command metadata and generates
 * a single CLI.md file with all command documentation. Everything is
 * auto-generated from the Picocli annotations - no manual examples.
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

    println("CLI documentation generated: ${File(docsDir, "CLI.md").absolutePath}")
  }

  private fun generateCliPage(commandLine: CommandLine) {
    val spec = commandLine.commandSpec
    val content = buildString {
      appendLine("# Trailblaze CLI")
      appendLine()
      appendLine("${spec.usageMessage().description().joinToString(" ")}")
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
        val desc = subCommand.commandSpec.usageMessage().description().firstOrNull() ?: ""
        appendLine("| `$name` | $desc |")
      }
      appendLine()
      
      // Detailed documentation for each command
      commandLine.subcommands.forEach { (name, subCommand) ->
        generateCommandSection(name, subCommand)
      }
      
      appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
    }

    File(docsDir, "CLI.md").writeText(content)
  }

  private fun StringBuilder.generateCommandSection(name: String, commandLine: CommandLine) {
    val spec = commandLine.commandSpec
    
    appendLine("---")
    appendLine()
    appendLine("### `trailblaze $name`")
    appendLine()
    appendLine("${spec.usageMessage().description().joinToString(" ")}")
    appendLine()
    
    // Synopsis
    appendLine("**Synopsis:**")
    appendLine()
    appendLine("```")
    append("trailblaze $name")
    if (spec.options().any { !it.hidden() }) {
      append(" [OPTIONS]")
    }
    spec.positionalParameters().forEach { param ->
      val paramName = param.paramLabel() ?: param.descriptionKey() ?: "ARG"
      if (param.required()) {
        append(" <$paramName>")
      } else {
        append(" [<$paramName>]")
      }
    }
    appendLine()
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
  }

  private fun StringBuilder.generateOptionsTable(options: List<OptionSpec>) {
    val filteredOptions = options.filter { !it.hidden() }
    if (filteredOptions.isEmpty()) return

    appendLine("| Option | Description | Default |")
    appendLine("|--------|-------------|---------|")
    filteredOptions.forEach { option ->
      val names = option.names().joinToString(", ") { "`$it`" }
      val desc = option.description().joinToString(" ").replace("|", "\\|")
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
      val desc = param.description().joinToString(" ").replace("|", "\\|")
      val required = if (param.required()) "Yes" else "No"
      appendLine("| `<$name>` | $desc | $required |")
    }
  }
}

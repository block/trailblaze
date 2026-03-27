package xyz.block.trailblaze.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import picocli.CommandLine

private val json = Json { prettyPrint = true }

@Serializable
data class CommandDescription(
  val name: String,
  val summary: String,
  val commands: List<CommandDescription> = emptyList(),
)

/**
 * Returns JSON describing the CLI's command tree for `sq` CLI integration.
 *
 * The `sq` CLI calls `trailblaze --describe-commands` to discover subcommands
 * and display them in `sq trailblaze` help output. This walks picocli's
 * [CommandLine] model so the output stays in sync with the actual commands.
 *
 * @see <a href="https://dev-guides.sqprod.co/docs/tools/sq-cli/guides/integration#subcommands">sq CLI integration guide</a>
 */
fun CommandLine.describeCommands(): String =
  json.encodeToString(
    CommandDescription(
      name = commandSpec.name(),
      summary = commandSpec.usageMessage().description().joinToString(" "),
      commands = subcommands.values
        .filterNot { it.commandSpec.usageMessage().hidden() }
        .distinctBy { it.commandSpec.name() }
        .map { it.toCommandDescription() },
    )
  )

private fun CommandLine.toCommandDescription(): CommandDescription {
  val spec = commandSpec
  return CommandDescription(
    name = spec.name(),
    summary = spec.usageMessage().description().joinToString(" "),
    commands = spec.subcommands().values
      .filterNot { it.commandSpec.usageMessage().hidden() }
      .distinctBy { it.commandSpec.name() }
      .map { it.toCommandDescription() },
  )
}

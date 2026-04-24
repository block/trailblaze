package xyz.block.trailblaze.maestro

import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import kotlin.io.path.Path

object MaestroYamlParser {

  /**
   * Writes the given YAML to a temporary file and parses it with Maestro's official YAML parser
   * ([YamlCommandReader]). Accepts either a full Maestro flow (with `appId:`/`---` header) or a
   * bare commands list — in the bare case a synthetic header is prepended so Maestro's parser
   * accepts it.
   *
   * Any [ApplyConfigurationCommand] in the parsed output is always filtered, regardless of
   * whether it came from a caller-supplied config header or the synthetic one: Trailblaze owns
   * app-launch and lifecycle via its own tools and has no use for Maestro's `applyConfiguration`
   * step.
   */
  fun parseYaml(yaml: String, appId: String = "trailblaze"): List<Command> {
    val yamlWithConfig = if (yaml.lines().contains("---")) {
      yaml
    } else {
      """
appId: $appId
---
$yaml
      """.trimIndent()
    }
    return parseYamlToCommandsUsingMaestroImpl(yamlWithConfig)
      .filterNot { it is ApplyConfigurationCommand }
  }

  private fun parseYamlToCommandsUsingMaestroImpl(yamlString: String): List<Command> {
    val tempFlowFile = File.createTempFile("maestro", ".yaml").apply {
      writeText(yamlString)
    }
    val commands: List<MaestroCommand> = YamlCommandReader.readCommands(Path(tempFlowFile.absolutePath))
    tempFlowFile.delete()
    return commands.mapNotNull { it.asCommand() }
  }
}

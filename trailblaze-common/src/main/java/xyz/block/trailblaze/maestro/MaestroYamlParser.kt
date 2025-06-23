package xyz.block.trailblaze.maestro

import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import kotlin.io.path.Path

object MaestroYamlParser {

  /**
   * Will write the given YAML to a temporary file and parse it with Maestro's official YAML parser.
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
    return parseYamlToCommandsUsingMaestroImpl(
      yamlWithConfig,
    )
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

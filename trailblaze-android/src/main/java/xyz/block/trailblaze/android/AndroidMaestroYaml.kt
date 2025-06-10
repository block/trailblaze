package xyz.block.trailblaze.android

import android.content.Context
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.yaml.YamlCommandReader
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import java.io.File
import kotlin.io.path.Path

object AndroidMaestroYaml {

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
      withInstrumentation { this.context },
    )
  }

  private fun parseYamlToCommandsUsingMaestroImpl(yamlString: String, context: Context): List<Command> {
    val tempFlowFile = File(context.dataDir, "flow_${System.currentTimeMillis()}.yaml").apply {
      println("Writing Flow yaml=$yamlString")
      writeText(yamlString)
    }
    println("Running Flow: ${tempFlowFile.canonicalPath} ${tempFlowFile.readText()}")
    val commands: List<MaestroCommand> = YamlCommandReader.readCommands(Path(tempFlowFile.absolutePath))
    tempFlowFile.delete()
    return commands.mapNotNull { it.asCommand() }
  }
}

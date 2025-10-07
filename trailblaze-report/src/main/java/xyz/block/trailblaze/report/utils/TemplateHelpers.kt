package xyz.block.trailblaze.report.utils

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.utils.Ext.asMaestroCommand
import xyz.block.trailblaze.yaml.TrailblazeYaml

object TemplateHelpers {

  @JvmStatic
  fun asMaestroYaml(maestroCommandJson: JsonObject): String = MaestroYamlSerializer.toYaml(listOf(maestroCommandJson.asMaestroCommand()!!), false)

  @JvmStatic
  fun asTrailblazeYaml(toolName: String, trailblazeTool: TrailblazeTool): String = TrailblazeYaml.toolToYaml(
    toolName = toolName,
    trailblazeTool = trailblazeTool,
  )
}

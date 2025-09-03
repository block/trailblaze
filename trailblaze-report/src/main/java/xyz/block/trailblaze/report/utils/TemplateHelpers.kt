package xyz.block.trailblaze.report.utils

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.utils.Ext.asMaestroCommand
import xyz.block.trailblaze.yaml.TrailblazeYaml

// Wrapper for Freemarker template helpers
object TemplateHelpers {
  @JvmStatic
  fun asCommandJson(trailblazeToolLog: TrailblazeLog.TrailblazeToolLog): String = buildString {
    appendLine(trailblazeToolLog.trailblazeTool.toString())
  }

  @JvmStatic
  fun asCommandJson(delegatingTrailblazeToolLog: TrailblazeLog.DelegatingTrailblazeToolLog): String = buildString {
    appendLine(delegatingTrailblazeToolLog.trailblazeTool.toString())
    appendLine()
    appendLine("Delegated to:")
    delegatingTrailblazeToolLog.executableTools.forEach { executableTool ->
      appendLine(executableTool.toString())
    }
  }

  @JvmStatic
  fun debugString(maestroDriverLog: TrailblazeLog.MaestroDriverLog): String = buildString {
    appendLine(TrailblazeJsonInstance.encodeToString(maestroDriverLog.action))
  }

  @JvmStatic
  fun asMaestroYaml(maestroCommandJson: JsonObject): String = MaestroYamlSerializer.toYaml(listOf(maestroCommandJson.asMaestroCommand()!!), false)

  @JvmStatic
  fun asTrailblazeYaml(toolName: String, trailblazeTool: TrailblazeTool): String = TrailblazeYaml.toolToYaml(
    toolName = toolName,
    trailblazeTool = trailblazeTool,
  )

  @JvmStatic
  fun asMaestroYaml(maestroCommandLog: TrailblazeLog.MaestroCommandLog): String = asMaestroYaml(maestroCommandLog.maestroCommandJsonObj)
}

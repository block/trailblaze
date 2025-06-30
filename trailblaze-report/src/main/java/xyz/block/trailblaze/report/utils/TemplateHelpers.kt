package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeSerializer
import xyz.block.trailblaze.utils.Ext.asMaestroCommand

// Wrapper for Freemarker template helpers
object TemplateHelpers {
  @JvmStatic
  fun asCommandJson(trailblazeToolLog: TrailblazeToolLog): String = buildString {
    appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(trailblazeToolLog.command))
  }

  @JvmStatic
  fun asCommandJson(delegatingTrailblazeToolLog: TrailblazeLog.DelegatingTrailblazeToolLog): String = buildString {
    appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(delegatingTrailblazeToolLog.command))
    appendLine()
    appendLine("Delegated to:")
    delegatingTrailblazeToolLog.executableTools.forEach { executableTool ->
      appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(executableTool))
    }
  }

  @JvmStatic
  fun debugString(maestroDriverLog: TrailblazeLog.MaestroDriverLog): String = buildString {
    appendLine(TrailblazeJsonInstance.encodeToString(maestroDriverLog.action))
  }

  @JvmStatic
  fun asMaestroYaml(maestroCommandLog: TrailblazeLog.MaestroCommandLog): String = MaestroYamlSerializer.toYaml(listOf(maestroCommandLog.maestroCommandJsonObj.asMaestroCommand()!!), false)
}

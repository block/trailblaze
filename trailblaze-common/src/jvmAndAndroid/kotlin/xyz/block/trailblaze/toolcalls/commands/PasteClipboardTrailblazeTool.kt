package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.PasteTextCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(name = "pasteClipboard")
@LLMDescription("Pastes the current clipboard contents into the focused text field.")
data object PasteClipboardTrailblazeTool : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val maestroTrailblazeAgent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "pasteClipboard requires a Maestro agent but none is available."
      )
    return maestroTrailblazeAgent.runMaestroCommands(
      maestroCommands = listOf(PasteTextCommand()),
      traceId = toolExecutionContext.traceId,
    )
  }
}

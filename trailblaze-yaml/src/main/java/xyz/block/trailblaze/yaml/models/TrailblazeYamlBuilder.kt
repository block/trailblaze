package xyz.block.trailblaze.yaml.models

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.MaestroCommandList
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

class TrailblazeYamlBuilder {

  private val recordings = mutableListOf<TrailYamlItem>()

  fun prompt(
    text: String,
    recordable: Boolean = true,
    recording: List<TrailblazeTool>? = null,
  ) = apply {
    val newStep = TrailYamlItem.PromptsTrailItem.PromptStep(
      step = text,
      recordable = recordable,
      recording = recording.toToolRecording(),
    )
    when (val lastItem = recordings.last()) {
      is TrailYamlItem.PromptsTrailItem -> {
        // Add the new prompt to the existing prompts trail item for cleaner yaml syntax
        recordings.removeAt(recordings.lastIndex)
        val newPromptsTrailItem = TrailYamlItem.PromptsTrailItem(
          lastItem.promptSteps.plus(newStep),
        )
        recordings.add(newPromptsTrailItem)
      }
      else -> {
        recordings.add(
          TrailYamlItem.PromptsTrailItem(listOf(newStep)),
        )
      }
    }
  }

  fun tools(
    tools: List<TrailblazeTool>,
  ) = apply {
    recordings.add(
      TrailYamlItem.ToolTrailItem(
        tools = tools.map { TrailblazeToolYamlWrapper.fromTrailblazeTool(it) },
      ),
    )
  }

  fun maestro(
    commands: List<Command>,
  ) = apply {
    recordings.add(
      TrailYamlItem.MaestroTrailItem(
        MaestroCommandList(maestroCommands = commands),
      ),
    )
  }

  fun build() = recordings

  // Helper functions
  private fun List<TrailblazeTool>?.toToolRecording(): ToolRecording? = this?.let {
    ToolRecording(
      this.map { tool ->
        TrailblazeToolYamlWrapper.fromTrailblazeTool(tool)
      },
    )
  }
}

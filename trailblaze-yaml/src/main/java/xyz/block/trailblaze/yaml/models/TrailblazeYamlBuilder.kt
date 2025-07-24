package xyz.block.trailblaze.yaml.models

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.MaestroCommandList
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.MaestroTrailItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem.ToolTrailItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

class TrailblazeYamlBuilder {

  private val recordings = mutableListOf<TrailYamlItem>()

  fun prompt(
    text: String,
    recordable: Boolean = true,
    recording: List<TrailblazeTool>? = null,
  ) = apply {
    val newStep = PromptsTrailItem.PromptStep(
      step = text,
      recordable = recordable,
      recording = recording.toToolRecording(),
    )
    // Null return means this is the first item in the yaml so just add a new prompts trail item
    when (val lastItem = recordings.lastOrNull()) {
      is PromptsTrailItem -> {
        // Add the new prompt to the existing prompts trail item for cleaner yaml syntax
        recordings.removeAt(recordings.lastIndex)
        val newPromptsTrailItem = lastItem.copy(
          promptSteps = lastItem.promptSteps.plus(newStep),
        )
        recordings.add(newPromptsTrailItem)
      }
      else -> {
        recordings.add(
          PromptsTrailItem(listOf(newStep)),
        )
      }
    }
  }

  fun tools(
    tools: List<TrailblazeTool>,
  ) = apply {
    val newTools = tools.map { TrailblazeToolYamlWrapper.fromTrailblazeTool(it) }
    // Null return means this is the first item in the yaml so just add a new tool trail item
    when (val lastItem = recordings.lastOrNull()) {
      is ToolTrailItem -> {
        recordings.removeAt(recordings.lastIndex)
        val newToolTrailItem = lastItem.copy(
          tools = lastItem.tools.plus(newTools),
        )
        recordings.add(newToolTrailItem)
      }
      else -> recordings.add(ToolTrailItem(newTools))
    }
  }

  fun maestro(
    commands: List<Command>,
  ) = apply {
    // Null return means this is the first item in the yaml so just add a new tool trail item
    when (val lastItem = recordings.lastOrNull()) {
      is MaestroTrailItem -> {
        recordings.removeAt(recordings.lastIndex)
        val newToolTrailItem = lastItem.copy(
          // This is gross, can we fix this nested copy?
          maestro = lastItem.maestro.copy(
            maestroCommands = lastItem.maestro.maestroCommands.plus(commands),
          ),
        )
        recordings.add(newToolTrailItem)
      }
      else -> recordings.add(MaestroTrailItem(MaestroCommandList(commands)))
    }
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

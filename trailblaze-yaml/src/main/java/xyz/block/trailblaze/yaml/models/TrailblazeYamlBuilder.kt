package xyz.block.trailblaze.yaml.models

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.models.TrailYamlItem.PromptsTrailItem.PromptStep.ToolRecording

class TrailblazeYamlBuilder {

  private val recordings = mutableListOf<TrailYamlItem>()

  fun prompt(
    text: String,
    recordable: Boolean = true,
    recording: List<TrailblazeTool>? = null,
  ) = apply {
    recordings.add(
      TrailYamlItem.PromptsTrailItem(
        TrailYamlItem.PromptsTrailItem.PromptStep(
          text = text,
          recordable = recordable,
          recording = recording?.let {
            ToolRecording(
              it.map {
                TrailblazeToolYamlWrapper.fromTrailblazeTool(it)
              },
            )
          },
        ),
      ),
    )
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
}

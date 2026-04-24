package xyz.block.trailblaze.yaml.models

import maestro.orchestra.Command
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.fromTrailblazeTool

class TrailblazeYamlBuilder {

  private val recordings = mutableListOf<TrailYamlItem>()

  fun config(
    context: String? = null,
    id: String? = null,
    title: String? = null,
    description: String? = null,
    priority: String? = null,
    source: TrailSource? = null,
    metadata: Map<String, String>? = null,
    target: String? = null,
    driver: String? = null,
    platform: String? = null,
  ) = apply {
    recordings.add(
      TrailYamlItem.ConfigTrailItem(
        TrailConfig(
          context = context,
          id = id,
          title = title,
          source = source,
          description = description,
          priority = priority,
          metadata = metadata,
          target = target,
          driver = driver,
          platform = platform,
        ),
      ),
    )
  }

  fun prompt(
    text: String,
    recordable: Boolean = true,
    recording: List<TrailblazeTool>? = null,
  ) = apply {
    addPromptToList(
      DirectionStep(
        step = text,
        recordable = recordable,
        recording = recording.toToolRecording(),
      ),
    )
  }

  fun verify(
    text: String,
    recordable: Boolean = true,
    recording: List<TrailblazeTool>? = null,
  ) = apply {
    addPromptToList(
      VerificationStep(
        verify = text,
        recordable = recordable,
        recording = recording.toToolRecording(),
      ),
    )
  }

  fun tools(
    tools: List<TrailblazeTool>,
  ) = apply {
    val newTools = tools.map { fromTrailblazeTool(it) }
    // Null return means this is the first item in the yaml so just add a new tool trail item
    when (val lastItem = recordings.lastOrNull()) {
      is TrailYamlItem.ToolTrailItem -> {
        recordings.removeAt(recordings.lastIndex)
        val newToolTrailItem = lastItem.copy(
          tools = lastItem.tools.plus(newTools),
        )
        recordings.add(newToolTrailItem)
      }

      else -> recordings.add(TrailYamlItem.ToolTrailItem(newTools))
    }
  }

  fun maestro(
    commands: List<Command>,
  ) = tools(
    listOf(
      MaestroTrailblazeTool(
        yaml = MaestroYamlSerializer.toYaml(commands, includeConfiguration = false),
      ),
    ),
  )

  fun build() = recordings

  // Helper functions
  private fun List<TrailblazeTool>?.toToolRecording(): ToolRecording? = this?.let {
    ToolRecording(
      this.map { tool ->
        fromTrailblazeTool(tool)
      },
    )
  }

  private fun addPromptToList(newStep: PromptStep) {
    // Null return means this is the first item in the yaml so just add a new prompts trail item
    when (val lastItem = recordings.lastOrNull()) {
      is TrailYamlItem.PromptsTrailItem -> {
        // Add the new prompt to the existing prompts trail item for cleaner yaml syntax
        recordings.removeAt(recordings.lastIndex)
        val newPromptsTrailItem = lastItem.copy(
          promptSteps = lastItem.promptSteps.plus(newStep),
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
}

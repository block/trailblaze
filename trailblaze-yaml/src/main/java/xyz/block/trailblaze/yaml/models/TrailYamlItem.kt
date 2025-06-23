package xyz.block.trailblaze.yaml.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents the top level items in a trail yaml.
 */
@Serializable
sealed interface TrailYamlItem {
  /**
   * "prompt"
   *
   * This is used to represent a prompt step in the trail.
   * It can contain a text prompt and an optional recording of tools used in that step.
   */
  @Serializable
  data class PromptsTrailItem(val promptStep: PromptStep) : TrailYamlItem {
    @Serializable
    data class PromptStep(
      val text: String,
      val recordable: Boolean = true,
      val recording: ToolRecording? = null,
    ) {
      @Serializable
      data class ToolRecording(
        val tools: List<@Contextual TrailblazeToolYamlWrapper>,
      )
    }
  }

  /**
   * tools
   *
   * This is used to represent a list of static tools used in the trail.
   */
  @Serializable
  data class ToolTrailItem(
    val tools: List<@Contextual TrailblazeToolYamlWrapper>,
  ) : TrailYamlItem

  /**
   * maestro
   *
   * This is used to represent a list of Maestro commands in the trail.
   */
  @Serializable
  data class MaestroTrailItem(
    @Contextual
    val maestro: MaestroCommandList,
  ) : TrailYamlItem

  companion object {
    val KEYWORD_PROMPT = "prompt"
    val KEYWORD_TOOLS = "tools"
    val KEYWORD_MAESTRO = "maestro"
  }
}

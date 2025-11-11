package xyz.block.trailblaze.yaml

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
  data class PromptsTrailItem(val promptSteps: List<PromptStep>) : TrailYamlItem

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
    val KEYWORD_PROMPTS = "prompts"
    val KEYWORD_TOOLS = "tools"
    val KEYWORD_MAESTRO = "maestro"
    val KEYWORD_CONFIG = "config"
  }

  /**
   *  config
   *
   *  This is used to represent additional test context and metadata.
   *  Use this to provide test data that will be added to the system prompt,
   *  as well as metadata like test ID, title, description, priority, and tags.
   */
  @Serializable
  data class ConfigTrailItem(val config: TrailConfig) : TrailYamlItem
}

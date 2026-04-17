package xyz.block.trailblaze.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema for per-tool `.yaml` files in `trailblaze-config/tools/`.
 *
 * Each file registers one custom tool by mapping its name to a fully qualified class name, enabling
 * reflection-based discovery without requiring companion classes to enumerate tool classes.
 *
 * Example:
 * ```yaml
 * id: my_custom_tool
 * class: com.example.tools.MyCustomTrailblazeTool
 * ```
 */
@Serializable
data class ToolYamlConfig(
  val id: String,
  @SerialName("class") val toolClass: String,
)

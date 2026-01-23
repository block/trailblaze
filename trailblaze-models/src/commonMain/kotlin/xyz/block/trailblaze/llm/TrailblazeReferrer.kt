package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * Represents the source/context from which a test run was initiated.
 * This is used for analytics and tracking purposes.
 *
 * Predefined referrers are provided in the companion object. Custom referrers
 * can be created using the constructor for extension in other modules.
 */
@Serializable
data class TrailblazeReferrer(
  val id: String,
  val display: String,
) {
  companion object {
    val MCP = TrailblazeReferrer(id = "mcp", display = "MCP")
    val SESSION_TAB_RETRY = TrailblazeReferrer(id = "session_tab_retry", display = "Session Tab Retry")
    val YAML_TAB = TrailblazeReferrer(id = "yaml_tab", display = "Yaml Tab")
  }
}

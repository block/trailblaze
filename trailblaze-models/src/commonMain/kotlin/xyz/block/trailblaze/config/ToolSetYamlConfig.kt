package xyz.block.trailblaze.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema for `.toolset.yaml` files — a named group of tools with optional
 * platform/driver constraints.
 *
 * Example:
 * ```yaml
 * id: test_user_setup
 * description: "Tools that provision throwaway users for sign-up and login flows"
 * tools:
 *   - testUser_create
 *   - testUser_createWithPreferences
 * ```
 */
@Serializable
data class ToolSetYamlConfig(
  val id: String,
  val description: String = "",
  val platforms: List<String>? = null,
  val drivers: List<String>? = null,
  @SerialName("always_enabled") val alwaysEnabled: Boolean = false,
  val tools: List<String> = emptyList(),
)

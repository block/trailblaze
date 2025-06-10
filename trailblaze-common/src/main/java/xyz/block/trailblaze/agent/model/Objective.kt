package xyz.block.trailblaze.agent.model

import kotlinx.serialization.Serializable

/**
 * Represents a single objective assigned to the agent.
 */
@Serializable
data class Objective(
  val description: String,
  val id: String,
)

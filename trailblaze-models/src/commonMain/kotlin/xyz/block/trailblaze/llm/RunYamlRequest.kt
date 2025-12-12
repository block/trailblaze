package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Model used to send an HTTP request to the Test client.
 */
@Serializable
data class RunYamlRequest(
  val testName: String,
  val yaml: String,
  val trailFilePath: String?,
  val targetAppName: String?,
  val useRecordedSteps: Boolean,
  val trailblazeLlmModel: TrailblazeLlmModel,
  val config: TrailblazeConfig,
)

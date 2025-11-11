package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * Model used to send an HTTP request to the Test client.
 */
@Serializable
data class RunYamlRequest(
  val testName: String,
  val yaml: String,
  val targetAppName: String?,
  val useRecordedSteps: Boolean,
  val trailblazeLlmModel: TrailblazeLlmModel,
)

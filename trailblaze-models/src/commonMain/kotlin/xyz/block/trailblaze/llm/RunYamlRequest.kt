package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.DynamicLlmConfig

/**
 * Model used to send an HTTP request to the Test client.
 */
@Serializable
data class RunYamlRequest(
  val testName: String,
  val yaml: String,
  val useRecordedSteps: Boolean,
  val dynamicLlmConfig: DynamicLlmConfig,
)

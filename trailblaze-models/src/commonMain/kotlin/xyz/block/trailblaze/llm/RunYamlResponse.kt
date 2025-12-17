package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * Response from a YAML test execution request.
 */
@Serializable
data class RunYamlResponse(
  /**
   * Human-readable message about the execution status.
   */
  val message: String,

  /**
   * The session ID for this test execution.
   * Can be used to track the test progress or cancel it.
   */
  val sessionId: String,
)

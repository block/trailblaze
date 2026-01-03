package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Response from a YAML test execution request.
 */
@Serializable
data class RunYamlResponse(
  /**
   * The session ID for this test execution.
   * Can be used to track the test progress or cancel it.
   */
  val sessionId: SessionId,
)

package xyz.block.trailblaze.model

import xyz.block.trailblaze.llm.RunYamlRequest

/**
 * Result of a trail execution.
 */
sealed class TrailExecutionResult {
  /** Trail completed successfully. */
  data object Success : TrailExecutionResult()
  
  /** Trail failed with an error. */
  data class Failed(val errorMessage: String?) : TrailExecutionResult()
  
  /** Trail was cancelled. */
  data object Cancelled : TrailExecutionResult()
}

class DesktopAppRunYamlParams(
  val forceStopTargetApp: Boolean,
  val runYamlRequest: RunYamlRequest,
  val targetTestApp: TrailblazeHostAppTarget?,
  val onProgressMessage: (String) -> Unit,
  val onConnectionStatus: (DeviceConnectionStatus) -> Unit,
  val additionalInstrumentationArgs: Map<String, String>,
  /** Called when the trail execution completes (success, failure, or cancellation). */
  val onComplete: ((TrailExecutionResult) -> Unit)? = null,
)

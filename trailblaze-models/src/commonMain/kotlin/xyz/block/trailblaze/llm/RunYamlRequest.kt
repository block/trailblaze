package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Model used to send an HTTP request to the Test client to execute a YAML test.
 * Returns a RunYamlResponse indicating execution status and session information.
 */
@Serializable
data class RunYamlRequest(
  val testName: String,
  val yaml: String,
  val trailFilePath: String?,
  val targetAppName: String?,
  val useRecordedSteps: Boolean,
  val trailblazeDeviceId: TrailblazeDeviceId,
  val trailblazeLlmModel: TrailblazeLlmModel,
  val config: TrailblazeConfig,
  val referrer: TrailblazeReferrer,
) : RpcRequest<RunYamlResponse> {
}

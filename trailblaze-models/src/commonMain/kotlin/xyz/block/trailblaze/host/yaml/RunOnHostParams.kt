package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

class RunOnHostParams(
  val targetTestApp: TrailblazeHostAppTarget?,
  val runYamlRequest: RunYamlRequest,
  val device: TrailblazeConnectedDeviceSummary,
  val forceStopTargetApp: Boolean,
  val additionalInstrumentationArgs: () -> Map<String, String>,
  val onProgressMessage: (String) -> Unit,
  /** RPC port for Compose driver connections. */
  val composeRpcPort: Int = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT,
  /**
   * The source/context from which this run was initiated.
   * Used for analytics and to determine behavior (e.g., MCP keeps drivers alive between calls).
   */
  val referrer: TrailblazeReferrer,
) {

  val trailblazeDevicePlatform: TrailblazeDevicePlatform = device.platform

  val trailblazeDriverType: TrailblazeDriverType = device.trailblazeDriverType
}
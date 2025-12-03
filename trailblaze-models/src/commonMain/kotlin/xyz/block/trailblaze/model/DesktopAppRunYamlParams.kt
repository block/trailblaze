package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.llm.RunYamlRequest

class DesktopAppRunYamlParams(
  val device: TrailblazeConnectedDeviceSummary,
  val forceStopTargetApp: Boolean,
  val runYamlRequest: RunYamlRequest,
  val targetTestApp: TrailblazeHostAppTarget?,
  val onProgressMessage: (String) -> Unit,
  val onConnectionStatus: (DeviceConnectionStatus) -> Unit,
  val additionalInstrumentationArgs: (suspend () -> Map<String, String>),
)

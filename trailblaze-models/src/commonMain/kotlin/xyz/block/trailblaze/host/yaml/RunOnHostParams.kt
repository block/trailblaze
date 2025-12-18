package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

class RunOnHostParams(
  val targetTestApp: TrailblazeHostAppTarget?,
  val runYamlRequest: RunYamlRequest,
  val device: TrailblazeConnectedDeviceSummary,
  val forceStopTargetApp: Boolean,
  val additionalInstrumentationArgs: () -> Map<String, String>,
  val onProgressMessage: (String) -> Unit,
) {

  val trailblazeDevicePlatform: TrailblazeDevicePlatform = device.platform

  val trailblazeDriverType = when (trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_HOST
    TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
    TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.WEB_PLAYWRIGHT_HOST
  }
}
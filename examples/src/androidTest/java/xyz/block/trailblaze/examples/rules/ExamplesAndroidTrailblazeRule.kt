package xyz.block.trailblaze.examples.rules

import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig

class ExamplesAndroidTrailblazeRule(
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  customToolClasses: CustomTrailblazeTools = CustomTrailblazeTools(
    registeredAppSpecificLlmTools = setOf(),
    config = config,
    driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
  ),
) : AndroidTrailblazeRule(
  config = config,
  customToolClasses = customToolClasses,
)

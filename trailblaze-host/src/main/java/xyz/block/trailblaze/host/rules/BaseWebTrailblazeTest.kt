package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDeviceType
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest

abstract class BaseWebTrailblazeTest :
  BaseHostTrailblazeTest(
    platform = TrailblazeDeviceType.WEB,
  )

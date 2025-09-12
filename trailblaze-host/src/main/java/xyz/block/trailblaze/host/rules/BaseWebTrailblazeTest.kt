package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDriverType

abstract class BaseWebTrailblazeTest :
  BaseHostTrailblazeTest(
    trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT,
  )

package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDriverType

abstract class BaseAndroidHostTrailblazeTest :
  BaseHostTrailblazeTest(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
    setOfMarkEnabled = true,
    systemPromptTemplate = null,
    trailblazeToolSet = null,
    customToolClasses = setOf(),
  )

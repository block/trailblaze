package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.reflect.KClass

abstract class AndroidOnDeviceTrailblazeToolSet(
  name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("Add a @TrailblazeToolSetClass annotation"),
  toolClasses: Set<KClass<out TrailblazeTool>>,
) : TrailblazeToolSet(
  name = name,
  toolClasses = toolClasses,
  supportedDriverTypes = TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES,
)

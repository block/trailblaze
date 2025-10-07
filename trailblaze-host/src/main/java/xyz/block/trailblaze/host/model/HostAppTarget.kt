package xyz.block.trailblaze.host.model

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

abstract class HostAppTarget(
  val name: String,
  val driverType: TrailblazeDriverType,
  /** Registered with the LLM */
  val initialCustomToolClasses: Set<KClass<out TrailblazeTool>>,
  /** Needed for serialization/deserialization */
  val allCustomToolClasses: Set<KClass<out TrailblazeTool>>,
) {
  abstract fun getAppId(): String?
}

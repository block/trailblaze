package xyz.block.trailblaze.host

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * A target that declares only what the connect-binding rules read: its id, and whether it wraps the
 * base iOS driver in its own subclass. Square iOS is the only real target that sets the latter, so
 * without this the rules could only be tested one way round.
 */
internal class FakeHostAppTarget(
  id: String,
  override val hasCustomIosDriver: Boolean = false,
) : TrailblazeHostAppTarget(id = id, displayName = id) {
  override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()
  override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    emptySet()
}

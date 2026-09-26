package xyz.block.trailblaze.host.networkcapture

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * What a session's target tells capture: no target means there is nothing to capture, while a
 * named target that cannot be resolved must stay distinguishable so capture can refuse it loudly.
 */
class AndroidCaptureTargetAppIdsTest {

  private object DeclaredTarget :
    TrailblazeHostAppTarget(id = "declared", displayName = "Declared") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.app.dev", "com.example.app")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /** A real target that runs on another platform only, as a web-only target would. */
  private object OtherPlatformTarget :
    TrailblazeHostAppTarget(id = "other-platform", displayName = "Other platform") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val targets =
    listOf(DeclaredTarget, OtherPlatformTarget, TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget)

  private fun appIdsFor(targetId: String?): List<String>? =
    androidCaptureTargetAppIds(targetId, TrailblazeDevicePlatform.ANDROID) { id ->
      targets.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

  @Test
  fun `a session with no target has nothing to capture`() {
    assertNull(appIdsFor(null))
    assertNull(appIdsFor(" "))
  }

  @Test
  fun `the neutral default target counts as no target`() {
    assertNull(appIdsFor(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id))
  }

  @Test
  fun `a named target yields every app id it may run under`() {
    assertEquals(listOf("com.example.app.dev", "com.example.app"), appIdsFor("declared"))
  }

  @Test
  fun `a named target that does not resolve yields an empty list, not null`() {
    assertEquals(emptyList(), appIdsFor("not-installed-here"))
  }

  @Test
  fun `a named target with no app for this platform is refused, not skipped`() {
    assertEquals(emptyList(), appIdsFor("other-platform"))
  }
}

package xyz.block.trailblaze.host.yaml

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.deviceCaptureTargetAppIds
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.unresolvedTargetForDevice
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * What a `trailblaze run` tells network capture about its target. A trail with no target has
 * nothing to capture; a trail whose declared target did not resolve must stay a named target that
 * capture refuses, whatever fallback the run dropped to.
 */
class DesktopYamlRunnerCaptureTargetAppIdsTest {

  private object WorkspaceTarget :
    TrailblazeHostAppTarget(id = "workspace", displayName = "Workspace") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.workspace")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val android = TrailblazeDevicePlatform.ANDROID

  @Test
  fun `a trail with no target has nothing to capture`() {
    assertNull(deviceCaptureTargetAppIds(null, null, android))
    assertNull(
      deviceCaptureTargetAppIds(
        TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        null,
        android,
      )
    )
  }

  @Test
  fun `a resolved target yields its app ids`() {
    assertEquals(
      listOf("com.example.workspace"),
      deviceCaptureTargetAppIds(WorkspaceTarget, null, android),
    )
  }

  @Test
  fun `an unresolved declared target is refused even when the fallback is the neutral default`() {
    assertEquals(
      emptyList(),
      deviceCaptureTargetAppIds(
        TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        "not-installed-here",
        android,
      ),
    )
  }

  @Test
  fun `a multi-device member keeps its own target's ids when the session target did not resolve`() {
    assertEquals(
      listOf("com.example.workspace"),
      deviceCaptureTargetAppIds(
        targetTestApp = WorkspaceTarget,
        unresolvedDeclaredTarget =
          unresolvedTargetForDevice(
            sessionUnresolvedTarget = "not-installed-here",
            deviceHasOwnTarget = true,
          ),
        platform = android,
      ),
    )
  }

  @Test
  fun `an unresolved declared target does not take on the fallback's app ids`() {
    assertEquals(
      emptyList(),
      deviceCaptureTargetAppIds(WorkspaceTarget, "not-installed-here", android),
    )
  }
}

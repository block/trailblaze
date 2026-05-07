package xyz.block.trailblaze.model

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins [ResolvedTarget]'s contract: the small but throwing [ResolvedTarget.appId] getter is
 * the load-bearing piece — call sites depend on it failing loudly when a target declares no
 * app ids for the active platform, and on platform resolution flowing through the bundled
 * [TrailblazeDeviceId] rather than a separately-passed enum.
 */
class ResolvedTargetTest {

  private object FakeMultiAppIdTarget :
    TrailblazeHostAppTarget(id = "fake-multi", displayName = "Fake Multi") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> listOf("com.example.fake.android.primary", "com.example.fake.android.fallback")
        TrailblazeDevicePlatform.IOS -> listOf("com.example.fake.ios")
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private object FakeNoAppIdsTarget :
    TrailblazeHostAppTarget(id = "fake-none", displayName = "Fake None") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private object FakeEmptyAppIdsTarget :
    TrailblazeHostAppTarget(id = "fake-empty", displayName = "Fake Empty") {
    // Distinct from FakeNoAppIdsTarget: returns an empty list rather than null. Both are treated
    // the same way by `appId` (throws) and `appIds` (empty list), but the boundary is worth
    // pinning so a future refactor that collapses null and empty into different outcomes fails
    // these tests rather than silently changing behavior.
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private fun deviceId(platform: TrailblazeDevicePlatform) =
    TrailblazeDeviceId(instanceId = "test-device", trailblazeDevicePlatform = platform)

  @Test
  fun `platform pulls from deviceId`() {
    val resolved = ResolvedTarget(FakeMultiAppIdTarget, deviceId(TrailblazeDevicePlatform.IOS))
    assertEquals(TrailblazeDevicePlatform.IOS, resolved.platform)
  }

  @Test
  fun `id pass-through to target id`() {
    val resolved = ResolvedTarget(FakeMultiAppIdTarget, deviceId(TrailblazeDevicePlatform.ANDROID))
    assertEquals("fake-multi", resolved.id)
  }

  @Test
  fun `appId returns the first id for the active platform`() {
    val androidResolved = ResolvedTarget(FakeMultiAppIdTarget, deviceId(TrailblazeDevicePlatform.ANDROID))
    assertEquals("com.example.fake.android.primary", androidResolved.appId)

    val iosResolved = ResolvedTarget(FakeMultiAppIdTarget, deviceId(TrailblazeDevicePlatform.IOS))
    assertEquals("com.example.fake.ios", iosResolved.appId)
  }

  @Test
  fun `appIds returns the full ordered list for the active platform`() {
    val resolved = ResolvedTarget(FakeMultiAppIdTarget, deviceId(TrailblazeDevicePlatform.ANDROID))
    // The abstract method's return type is List<String>?, so ordering is contractual at the
    // type system rather than incidental through `LinkedHashSet`. This test pins that the
    // declared YAML/Kotlin order survives all the way to the consumer.
    assertEquals(
      listOf("com.example.fake.android.primary", "com.example.fake.android.fallback"),
      resolved.appIds,
    )
  }

  @Test
  fun `appIds is empty when target returns null for the platform`() {
    val resolved = ResolvedTarget(FakeNoAppIdsTarget, deviceId(TrailblazeDevicePlatform.IOS))
    assertEquals(emptyList(), resolved.appIds)
  }

  @Test
  fun `appIds is empty AND appId throws when target returns an empty list for the platform`() {
    // Pins the null-vs-empty-set boundary independently from the null-returning fixture above.
    // Both shapes funnel into the same observable behavior today (empty appIds, throwing appId).
    // A refactor that diverged them — say, treating empty-set as "platform supported but
    // intentionally no ids, return a sentinel value" — would silently change behavior; this
    // test makes that change visible.
    val resolved = ResolvedTarget(FakeEmptyAppIdsTarget, deviceId(TrailblazeDevicePlatform.IOS))
    assertEquals(emptyList(), resolved.appIds)
    assertFailsWith<IllegalStateException> { resolved.appId }
  }

  @Test
  fun `appId throws with target id, platform, and YAML path when no app ids are configured`() {
    val resolved = ResolvedTarget(FakeNoAppIdsTarget, deviceId(TrailblazeDevicePlatform.IOS))
    val thrown = assertFailsWith<IllegalStateException> { resolved.appId }
    val msg = thrown.message ?: ""
    // All three are load-bearing parts of the diagnostic — the target id and platform tell the
    // oncaller WHICH config to look at, and the YAML path tells them WHERE. Dropping any one of
    // them in a refactor would leave a substantially less actionable error, so each is asserted
    // independently rather than collapsed into a single "contains both" check.
    assertTrue(msg.contains("fake-none"), "Error must name the target id; got: $msg")
    assertTrue(msg.contains("IOS"), "Error must name the platform; got: $msg")
    assertTrue(
      msg.contains("trailblaze-config/targets/fake-none.yaml"),
      "Error must surface the YAML path so the oncaller knows where to look; got: $msg",
    )
    assertTrue(
      msg.contains("platforms.ios.app_ids"),
      "Error must point at the specific YAML field that's missing; got: $msg",
    )
  }
}

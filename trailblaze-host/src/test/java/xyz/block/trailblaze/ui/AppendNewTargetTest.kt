package xyz.block.trailblaze.ui

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeDeviceManager.AppendDecision

/**
 * Pins the contract of [TrailblazeDeviceManager.appendNewTarget] - the pure decision behind
 * live target registration (`registerNewTarget`):
 *
 * - **Net-new id appends** ([AppendDecision.Appended]): a target present in the fresh
 *   discovery but not the current set is added; the OTHER current entries survive by object
 *   identity (never re-resolved instances), which is what keeps a live append safe for
 *   in-flight runs holding a reference to their already-resolved target.
 * - **Already-present id is REPLACED with the fresh instance** ([AppendDecision.Appended]):
 *   an Edit Target save changes the on-disk manifest, and serving the stale registered
 *   snapshot made an edited target read "Not installed on any connected device" until a
 *   daemon restart. In-flight runs keep their captured reference to the replaced instance
 *   (nothing mutates it), so the swap is safe.
 * - **Registered id absent from fresh discovery** ([AppendDecision.AlreadyPresent]) - there is
 *   nothing newer to swap in, so the existing instance is returned (idempotent success).
 * - **Id neither registered nor in fresh discovery** ([AppendDecision.NotDiscovered]) - the
 *   caller falls back to the restart-required flow rather than fabricating a target.
 */
class AppendNewTargetTest {

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `net-new target appends and preserves existing entries by identity`() {
    val existingA = target("aaa")
    val existingB = target("bbb")
    val current = setOf(existingA, existingB)
    // Fresh discovery re-resolves everything: new instances for existing ids + the new target.
    val freshNew = target("ccc")
    val fresh = setOf(target("aaa"), target("bbb"), freshNew)

    val result = assertIs<AppendDecision.Appended>(
      TrailblazeDeviceManager.appendNewTarget(current, fresh, "ccc"),
    )
    assertSame(freshNew, result.newTarget)
    assertEquals(setOf("aaa", "bbb", "ccc"), result.updatedTargets.map { it.id }.toSet())
    // Existing entries must be the SAME objects, not fresh re-resolutions.
    assertTrue(result.updatedTargets.any { it === existingA })
    assertTrue(result.updatedTargets.any { it === existingB })
  }

  @Test
  fun `re-registered id replaces the stale instance with the fresh resolution`() {
    val stale = target("aaa")
    val bystander = target("bbb")
    // Fresh discovery re-resolved a DIFFERENT instance for the same id (Edit Target save
    // changed the on-disk manifest - e.g. new android app_ids).
    val freshReResolved = target("aaa")

    val result = assertIs<AppendDecision.Appended>(
      TrailblazeDeviceManager.appendNewTarget(
        setOf(stale, bystander),
        setOf(freshReResolved, target("bbb")),
        "aaa",
      ),
    )
    // The fresh instance wins - this is what makes an Edit Target save visible without restart.
    assertSame(freshReResolved, result.newTarget)
    assertEquals(setOf("aaa", "bbb"), result.updatedTargets.map { it.id }.toSet())
    assertTrue(result.updatedTargets.any { it === freshReResolved })
    assertTrue(result.updatedTargets.none { it === stale })
    // Untouched entries still survive by identity.
    assertTrue(result.updatedTargets.any { it === bystander })
  }

  @Test
  fun `registered id absent from fresh discovery returns the existing instance`() {
    val existing = target("aaa")

    val result = assertIs<AppendDecision.AlreadyPresent>(
      TrailblazeDeviceManager.appendNewTarget(setOf(existing), setOf(target("bbb")), "aaa"),
    )
    // Nothing newer to swap in - the existing (in-flight-safe) instance is returned.
    assertSame(existing, result.existing)
  }

  @Test
  fun `id absent from fresh discovery is NotDiscovered`() {
    val current = setOf(target("aaa"))
    val fresh = setOf(target("aaa"))

    assertIs<AppendDecision.NotDiscovered>(
      TrailblazeDeviceManager.appendNewTarget(current, fresh, "ccc"),
    )
  }

  @Test
  fun `append into empty current set works`() {
    val freshNew = target("ccc")

    val result = assertIs<AppendDecision.Appended>(
      TrailblazeDeviceManager.appendNewTarget(emptySet(), setOf(freshNew), "ccc"),
    )
    assertEquals(setOf(freshNew), result.updatedTargets)
  }
}

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
 * Pins the additive-only contract of [TrailblazeDeviceManager.appendNewTarget] — the pure
 * decision behind live target registration (`registerNewTarget`):
 *
 * - **Net-new id appends** ([AppendDecision.Appended]): a target present in the fresh
 *   discovery but not the current set is added; the current set's entries survive by object
 *   identity (never re-resolved instances), which is what keeps a live append safe for
 *   in-flight runs holding a reference to their already-resolved target.
 * - **Already-present id is idempotent** ([AppendDecision.AlreadyPresent]) and returns the
 *   EXISTING instance, even when fresh discovery produced a new instance for the same id (e.g.
 *   the target's YAML was also edited on disk) — mutation of existing targets stays on the
 *   restart-required flow, and a concurrent/double register still resolves the target rather
 *   than a spurious null.
 * - **Id absent from fresh discovery** ([AppendDecision.NotDiscovered]) — the caller falls
 *   back to the restart-required flow rather than fabricating a target.
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
  fun `already-present id is idempotent and returns the existing instance`() {
    val existing = target("aaa")
    // Fresh discovery re-resolved a DIFFERENT instance for the same id (e.g. YAML edited on disk).
    val freshReResolved = target("aaa")

    val result = assertIs<AppendDecision.AlreadyPresent>(
      TrailblazeDeviceManager.appendNewTarget(setOf(existing), setOf(freshReResolved), "aaa"),
    )
    // The existing (in-flight-safe) instance is returned, not the fresh re-resolution.
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

package xyz.block.trailblaze.ui

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the desktop-reactivity contract of live target registration (Block 4): the live target
 * set is a [MutableStateFlow], so [TrailblazeDeviceManager.casAppendNewTarget] — the CAS-append
 * loop behind `registerNewTarget` — EMITS the appended target to collectors (the desktop
 * composables' `collectAsState`), which is what makes a newly-created target appear in the picker
 * without a daemon restart.
 *
 * Tested against a real [MutableStateFlow] rather than a constructed [TrailblazeDeviceManager]:
 * the manager has ~14 constructor dependencies (LogsRepo, icon providers, run-yaml lambda,
 * analytics, …) that make direct construction impractical — the same reason [AppendNewTargetTest]
 * pins the pure [TrailblazeDeviceManager.appendNewTarget] decision in isolation.
 */
class RegisterNewTargetHolderTest {

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
  fun `appended target is emitted to a collector of the live StateFlow`() = runTest {
    val existing = target("aaa")
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(existing))
    val freshNew = target("ccc")

    val emissions = mutableListOf<Set<TrailblazeHostAppTarget>>()
    val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
      holder.collect { emissions.add(it) }
    }

    val resolved = TrailblazeDeviceManager.casAppendNewTarget(
      holder = holder,
      fresh = setOf(target("aaa"), freshNew),
      targetId = "ccc",
    )

    // The collector (a stand-in for a composable's collectAsState) sees the appended target,
    // i.e. the flow emitted a fresh snapshot rather than staying frozen on the startup set.
    assertSame(freshNew, resolved)
    assertEquals(setOf("aaa"), emissions.first().map { it.id }.toSet())
    assertEquals(setOf("aaa", "ccc"), emissions.last().map { it.id }.toSet())
    collector.cancel()
  }

  @Test
  fun `append preserves existing entries by identity`() {
    val existing = target("aaa")
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(existing))
    val freshNew = target("ccc")

    TrailblazeDeviceManager.casAppendNewTarget(holder, setOf(target("aaa"), freshNew), "ccc")

    // Existing entry survives as the SAME object (never a fresh re-resolution), so an in-flight
    // run holding a reference to its resolved target stays consistent across a live append.
    assertSame(existing, holder.value.first { it.id == "aaa" })
    assertSame(freshNew, holder.value.first { it.id == "ccc" })
  }

  @Test
  fun `already-present id is idempotent and does not re-emit to collectors`() = runTest {
    val existing = target("aaa")
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(existing))
    val emissions = mutableListOf<Set<TrailblazeHostAppTarget>>()
    val collector = launch(UnconfinedTestDispatcher(testScheduler)) { holder.collect { emissions.add(it) } }
    // Fresh discovery re-resolved a different instance for the same id (e.g. its YAML was edited).
    val freshReResolved = target("aaa")

    val resolved = TrailblazeDeviceManager.casAppendNewTarget(holder, setOf(freshReResolved), "aaa")

    assertSame(existing, resolved)
    // A collector (a composable) sees ONLY the initial value — no second snapshot is emitted, so
    // the picker doesn't needlessly recompose; mutation of existing targets stays on the restart flow.
    assertEquals(1, emissions.size)
    collector.cancel()
  }

  @Test
  fun `id absent from fresh discovery returns null and does not re-emit to collectors`() = runTest {
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(target("aaa")))
    val emissions = mutableListOf<Set<TrailblazeHostAppTarget>>()
    val collector = launch(UnconfinedTestDispatcher(testScheduler)) { holder.collect { emissions.add(it) } }

    val resolved = TrailblazeDeviceManager.casAppendNewTarget(holder, setOf(target("aaa")), "ccc")

    assertNull(resolved)
    // No snapshot emitted for a target that can't go live — the collector sees only the initial value.
    assertEquals(1, emissions.size)
    collector.cancel()
  }
}

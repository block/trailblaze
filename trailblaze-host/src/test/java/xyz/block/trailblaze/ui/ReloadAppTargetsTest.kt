package xyz.block.trailblaze.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the workspace-switch contract of [TrailblazeDeviceManager.swapAppTargets] — the whole-set
 * reload behind `reloadAppTargets`. Switching workspaces must REPLACE the live target set (the
 * previous workspace's targets are not this workspace's), and must leave it alone whenever the
 * reload can't legitimately claim the set: no provider, or discovery threw. Either case degrades to
 * the old set plus the drift nudge rather than to an empty picker. Overlapping switches must run
 * their discovery passes one at a time, because a pass rewrites process-global tool overlays as
 * well as returning a set.
 *
 * Tested against a real [MutableStateFlow] rather than a constructed [TrailblazeDeviceManager],
 * for the same reason [RegisterNewTargetHolderTest] is: the manager has ~14 constructor
 * dependencies that make direct construction impractical.
 */
class ReloadAppTargetsTest {

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
  fun `reload adds the new workspace's targets and drops the previous workspace's`() = runTest {
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(target("shared"), target("oldOnly")))
    val newOnly = target("newOnly")

    val result = TrailblazeDeviceManager.swapAppTargets(
      holder = holder,
      provider = { setOf(target("shared"), newOnly) },
      lock = Mutex(),
    )

    assertEquals(setOf("shared", "newOnly"), result?.map { it.id }?.toSet())
    assertEquals(setOf("shared", "newOnly"), holder.value.map { it.id }.toSet())
    assertSame(newOnly, holder.value.first { it.id == "newOnly" })
  }

  @Test
  fun `reload emits the new set to a collector of the live StateFlow`() = runTest {
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(target("oldOnly")))
    val emissions = mutableListOf<Set<TrailblazeHostAppTarget>>()
    val collector = launch(UnconfinedTestDispatcher(testScheduler)) { holder.collect { emissions.add(it) } }

    TrailblazeDeviceManager.swapAppTargets(
      holder = holder,
      provider = { setOf(target("newOnly")) },
      lock = Mutex(),
    )

    // The collector (a stand-in for a composable's collectAsState) sees the switch, i.e. the
    // picker updates without a daemon restart.
    assertEquals(setOf("oldOnly"), emissions.first().map { it.id }.toSet())
    assertEquals(setOf("newOnly"), emissions.last().map { it.id }.toSet())
    collector.cancel()
  }

  @Test
  fun `a throwing discovery leaves the live set untouched`() = runTest {
    val existing = target("aaa")
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(existing))

    val result = TrailblazeDeviceManager.swapAppTargets(
      holder = holder,
      provider = { error("workspace yaml is malformed") },
      lock = Mutex(),
    )

    assertNull(result)
    assertSame(existing, holder.value.single())
  }

  @Test
  fun `no discovery provider leaves the live set untouched`() = runTest {
    val existing = target("aaa")
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(setOf(existing))

    assertNull(TrailblazeDeviceManager.swapAppTargets(holder, provider = null, lock = Mutex()))
    assertSame(existing, holder.value.single())
  }

  @Test
  fun `overlapping switches never run two discovery passes at once`() = runTest {
    // Discovery replaces the process-global tool and toolset overlays on its way to returning a
    // set, so two passes running at once can leave one workspace's overlays paired with another
    // workspace's targets. Whoever holds the lock must be the only one inside `provider`.
    val lock = Mutex()
    val inFlight = AtomicInteger()
    val peakInFlight = AtomicInteger()
    val holder = MutableStateFlow<Set<TrailblazeHostAppTarget>>(emptySet())

    fun discoveryPass(id: String): () -> Set<TrailblazeHostAppTarget> = {
      val now = inFlight.incrementAndGet()
      peakInFlight.getAndUpdate { maxOf(it, now) }
      // A real pass touches disk and may spawn the scripted-tool analyzer. Standing still for a
      // beat on a real thread gives an unserialized pass a wide window to overlap with the others.
      Thread.sleep(50)
      inFlight.decrementAndGet()
      setOf(target(id))
    }

    withContext(Dispatchers.Default) {
      listOf("workspaceA", "workspaceB", "workspaceC")
        .map { id -> launch { TrailblazeDeviceManager.swapAppTargets(holder, discoveryPass(id), lock) } }
        .joinAll()
    }

    assertEquals(1, peakInFlight.get())
    // Whichever pass ran last owns the live set outright, rather than a blend of the three.
    assertEquals(1, holder.value.size)
  }
}

package xyz.block.trailblaze.logs.server

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * A probe's first answer can be temporary — the bare platform after a failed probe, or a measured
 * `android-phone` when the distribution override missed. These pin that a later answer reaches the
 * session's gate, only while none of the session's calls are running, and that a failed apply is
 * retried rather than lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDeviceClassificationsTest {

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val otherDevice = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)
  private val platformOnly = classifiers("android")
  private val phone = classifiers("android", "phone")
  private val rig = classifiers("android", "phone", "rig")

  @Test
  fun `a changed answer is applied once the running call finishes`() = runTest {
    val answers = ArrayDeque(listOf(phone, rig))
    val classifications = SessionDeviceClassifications(
      probe = { answers.removeFirstOrNull() ?: rig },
      recheckGapsMs = listOf(30_000L),
    )
    val gate = RuntimeRebuildGate()
    val changes = AtomicInteger(0)
    val finishCall = CompletableDeferred<Unit>()

    val first = classifications.resolve("s", device, backgroundScope, gate, onChanged = { changes.incrementAndGet() })
    assertEquals(phone, first)
    launch { gate.call { finishCall.await() } }

    advanceTimeBy(30_001L)
    runCurrent()
    // The re-check saw the new answer, but a call is running: nothing is swapped under it.
    assertEquals(phone, classifications.known("s", device))
    assertEquals(0, changes.get())

    finishCall.complete(Unit)
    runCurrent()
    assertEquals(rig, classifications.known("s", device))
    assertEquals(1, changes.get())
  }

  @Test
  fun `an unchanged answer reports nothing`() = runTest {
    val probes = AtomicInteger(0)
    val classifications = SessionDeviceClassifications(
      probe = { probes.incrementAndGet(); phone },
      recheckGapsMs = listOf(30_000L, 30_000L),
    )
    val gate = RuntimeRebuildGate()
    val changes = AtomicInteger(0)

    classifications.resolve("s", device, backgroundScope, gate, onChanged = { changes.incrementAndGet() })
    // Resolving again for the same device is answered from the entry, without a probe.
    classifications.resolve("s", device, backgroundScope, gate, onChanged = { changes.incrementAndGet() })
    advanceTimeBy(60_001L)
    runCurrent()

    assertEquals(3, probes.get(), "one probe, then one per re-check")
    assertEquals(0, changes.get())
  }

  @Test
  fun `a failed apply keeps the previous answer and is retried`() = runTest {
    val answers = ArrayDeque(listOf(phone, rig))
    val classifications = SessionDeviceClassifications(
      probe = { answers.removeFirstOrNull() ?: rig },
      recheckGapsMs = listOf(30_000L),
    )
    val applies = AtomicInteger(0)
    classifications.resolve(
      "s",
      device,
      backgroundScope,
      RuntimeRebuildGate(),
      onChanged = { if (applies.incrementAndGet() == 1) error("subprocess failed to launch") },
    )

    advanceTimeBy(30_001L)
    runCurrent()
    assertEquals(phone, classifications.known("s", device), "the gate keeps what the runtime was built with")

    // Past the schedule, a change that never applied keeps being re-checked.
    advanceTimeBy(30_001L)
    runCurrent()
    assertEquals(rig, classifications.known("s", device))
    assertEquals(2, applies.get())
  }

  @Test
  fun `a failed apply is applied again when the device goes back to the previous answer`() = runTest {
    // The failed apply already changed the session's tools, so the old answer must be re-applied.
    val answers = ArrayDeque(listOf(phone, rig, phone))
    val classifications = SessionDeviceClassifications(
      probe = { answers.removeFirstOrNull() ?: phone },
      recheckGapsMs = listOf(30_000L),
    )
    val applies = AtomicInteger(0)
    classifications.resolve(
      "s",
      device,
      backgroundScope,
      RuntimeRebuildGate(),
      onChanged = { if (applies.incrementAndGet() == 1) error("tools failed to register") },
    )

    advanceTimeBy(2 * 30_000L + 1)
    runCurrent()

    assertEquals(phone, classifications.known("s", device))
    assertEquals(2, applies.get())
  }

  @Test
  fun `binding a device again ends its earlier re-checks`() = runTest {
    val probes = AtomicInteger(0)
    val classifications = SessionDeviceClassifications(
      probe = { id -> if (id == device) probes.incrementAndGet(); if (id == device) phone else rig },
      recheckGapsMs = listOf(30_000L),
    )
    val gate = RuntimeRebuildGate()
    classifications.resolve("s", device, backgroundScope, gate, onChanged = {})
    classifications.resolve("s", otherDevice, backgroundScope, gate, onChanged = {})
    classifications.resolve("s", device, backgroundScope, gate, onChanged = {})

    advanceTimeBy(30_001L)
    runCurrent()

    assertEquals(3, probes.get(), "two probes, then one re-check from the latest binding only")
  }

  @Test
  fun `a bare-platform answer keeps being re-checked after the schedule`() = runTest {
    // A device unreachable for the whole schedule must still get its gated tools once it answers.
    val answers = ArrayDeque(listOf(platformOnly, platformOnly, platformOnly, platformOnly, rig))
    val classifications = SessionDeviceClassifications(
      probe = { answers.removeFirstOrNull() ?: rig },
      recheckGapsMs = listOf(30_000L, 30_000L),
    )
    val changes = AtomicInteger(0)
    classifications.resolve("s", device, backgroundScope, RuntimeRebuildGate(), onChanged = { changes.incrementAndGet() })

    advanceTimeBy(4 * 30_000L + 1)
    runCurrent()

    assertEquals(rig, classifications.known("s", device))
    assertEquals(1, changes.get())
  }

  @Test
  fun `a settled answer stops re-checking after the schedule`() = runTest {
    val probes = AtomicInteger(0)
    val classifications = SessionDeviceClassifications(
      probe = { probes.incrementAndGet(); phone },
      recheckGapsMs = listOf(30_000L),
    )
    classifications.resolve("s", device, backgroundScope, RuntimeRebuildGate(), onChanged = {})

    advanceTimeBy(10 * 30_000L)
    runCurrent()

    assertEquals(2, probes.get(), "the first probe and one re-check")
  }

  @Test
  fun `binding another device ends the previous device's re-checks`() = runTest {
    val classifications = SessionDeviceClassifications(
      probe = { id -> if (id == device) phone else rig },
      recheckGapsMs = listOf(30_000L),
    )
    val gate = RuntimeRebuildGate()
    val changes = AtomicInteger(0)
    classifications.resolve("s", device, backgroundScope, gate, onChanged = { changes.incrementAndGet() })
    classifications.resolve("s", otherDevice, backgroundScope, gate, onChanged = { changes.incrementAndGet() })

    advanceTimeBy(30_001L)
    runCurrent()

    assertNull(classifications.known("s", device))
    assertEquals(rig, classifications.known("s", otherDevice))
    assertEquals(0, changes.get())
  }

  @Test
  fun `a forgotten session is never reported`() = runTest {
    val answers = ArrayDeque(listOf(phone, rig))
    val classifications = SessionDeviceClassifications(
      probe = { answers.removeFirstOrNull() ?: rig },
      recheckGapsMs = listOf(30_000L),
    )
    val changes = AtomicInteger(0)
    classifications.resolve("s", device, backgroundScope, RuntimeRebuildGate(), onChanged = { changes.incrementAndGet() })
    classifications.forget("s")

    advanceTimeBy(30_001L)
    runCurrent()

    assertNull(classifications.known("s", device))
    assertEquals(0, changes.get())
  }

  private fun classifiers(vararg segments: String) = segments.map(::TrailblazeDeviceClassifier)
}

package xyz.block.trailblaze.capture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryChangeDetectorTest {

  /** An Android sample whose heap used (size − free) is [heapUsedKb]; PSS is deliberately noise here. */
  private fun app(heapUsedKb: Long, pssKb: Long = 50_000) = AppMemorySample(
    totalPssKb = pssKb, totalRssKb = null, totalSwapPssKb = null, javaHeapKb = null, nativeHeapKb = null,
    codeKb = null, stackKb = null, graphicsKb = null, privateOtherKb = null, systemKb = null,
    views = null, viewRootImpls = null, appContexts = null, activities = null,
    javaHeapSizeKb = heapUsedKb + 1_000, javaHeapFreeKb = 1_000,
  )

  private fun device(availableKb: Long) = DeviceMemorySample(memTotalKb = 2_000_000, memAvailableKb = availableKb)

  private fun snap(pid: Int?, app: AppMemorySample?, device: DeviceMemorySample? = device(500_000)) =
    MemorySnapshot(timeMs = 0, appId = "com.example", pid = pid, app = app, device = device)

  @Test
  fun `the first reading is always emitted`() {
    assertEquals("first", MemoryChangeDetector.changeReason(null, snap(null, null)))
  }

  @Test
  fun `process lifecycle changes are emitted regardless of sizes`() {
    val running = snap(10, app(20_000))
    val gone = snap(null, null)
    assertEquals("process_started", MemoryChangeDetector.changeReason(gone, running))
    assertEquals("process_died", MemoryChangeDetector.changeReason(running, gone))
    assertEquals("process_restarted", MemoryChangeDetector.changeReason(running, snap(11, app(20_000))))
  }

  @Test
  fun `heap movement under the threshold is silent and at the threshold is emitted`() {
    val base = snap(10, app(20_000))
    assertNull(MemoryChangeDetector.changeReason(base, snap(10, app(20_000 + MemoryChangeDetector.USED_THRESHOLD_KB - 1))))
    assertEquals("memory_changed", MemoryChangeDetector.changeReason(base, snap(10, app(20_000 + MemoryChangeDetector.USED_THRESHOLD_KB))))
    assertEquals("memory_changed", MemoryChangeDetector.changeReason(base, snap(10, app(20_000 - MemoryChangeDetector.USED_THRESHOLD_KB))))
  }

  @Test
  fun `only the heap counts - PSS and device-wide memory moving on their own stay silent`() {
    val base = snap(10, app(20_000, pssKb = 50_000), device(500_000))
    assertNull(MemoryChangeDetector.changeReason(base, snap(10, app(20_000, pssKb = 90_000), device(100_000))))
  }

  @Test
  fun `identical readings are silent`() {
    val a = snap(10, app(20_000))
    assertNull(MemoryChangeDetector.changeReason(a, a.copy(timeMs = 2_000)))
  }

  @Test
  fun `same-process memory becoming readable again is a change, but losing it stays silent`() {
    val readable = snap(10, app(20_000))
    val unreadable = snap(10, null)
    assertEquals("memory_changed", MemoryChangeDetector.changeReason(unreadable, readable))
    assertNull(MemoryChangeDetector.changeReason(readable, unreadable))
    assertNull(MemoryChangeDetector.changeReason(unreadable, snap(10, null)))
  }
}

class MemoryChangeDetectorIosTest {

  private fun ios(footprintKb: Long) = MemorySnapshot(
    timeMs = 0, appId = "com.example", pid = 5950, app = null, device = null,
    iosApp = IosAppMemorySample(footprintKb = footprintKb, rssKb = null, mallocKb = null, categories = emptyMap()),
  )

  @Test
  fun `an iOS footprint change is judged by the same threshold as the Android heap`() {
    val base = ios(20_480)
    assertNull(MemoryChangeDetector.changeReason(base, ios(20_480 + MemoryChangeDetector.USED_THRESHOLD_KB - 1)))
    assertEquals("memory_changed", MemoryChangeDetector.changeReason(base, ios(20_480 + MemoryChangeDetector.USED_THRESHOLD_KB)))
  }

  @Test
  fun `an iOS app dying is a process event`() {
    assertEquals("process_died", MemoryChangeDetector.changeReason(ios(20_480), ios(20_480).copy(pid = null, iosApp = null)))
  }
}

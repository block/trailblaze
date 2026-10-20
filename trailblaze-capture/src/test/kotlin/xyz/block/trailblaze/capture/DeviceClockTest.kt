package xyz.block.trailblaze.capture

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile

class DeviceClockTest {

  private fun reader(vararg reads: () -> DeviceClockSample): () -> DeviceClockSample {
    val queue = ArrayDeque(reads.toList())
    return { queue.removeFirst()() }
  }

  @Test
  fun `measure keeps the read with the shortest round trip`() {
    val tight = DeviceClockSample(hostBeforeMs = 2_000, hostAfterMs = 2_004, deviceMs = 7_002)
    val measurement =
      DeviceClock.measure(
        "emulator-5554",
        burst = 3,
        read =
          reader(
            { DeviceClockSample(1_000, 1_080, 6_040) },
            { tight },
            { DeviceClockSample(3_000, 3_030, 8_015) },
          ),
      )
    val measured = assertIs<DeviceClock.Measurement.Measured>(measurement)
    assertEquals(tight, measured.best)
    assertEquals(3, measured.reads.size)
    assertEquals(5_000L, measured.best.deviceAheadMs)
  }

  @Test
  fun `a failed read ends the burst and keeps the reads before it, not an offset of 0`() {
    val good = DeviceClockSample(1_000, 1_020, 6_010)
    var reads = 0
    val measurement =
      DeviceClock.measure(
        "emulator-5554",
        burst = 5,
        read = {
          reads++
          if (reads == 1) good else error("adb: device offline")
        },
      )
    val measured = assertIs<DeviceClock.Measurement.Measured>(measurement)
    assertEquals(good, measured.best)
    assertEquals(2, reads, "a wedged device must not cost every read of the burst")
  }

  @Test
  fun `the burst stops taking reads once its budget has passed`() {
    var clock = 0L
    var reads = 0
    val measurement =
      DeviceClock.measure(
        "emulator-5554",
        burst = 5,
        budgetMs = 100,
        nowMs = { clock },
        read = {
          reads++
          clock += 60
          DeviceClockSample(clock - 10, clock, clock + 5_000)
        },
      )
    assertIs<DeviceClock.Measurement.Measured>(measurement)
    assertEquals(2, reads)
  }

  @Test
  fun `the measurement fails, naming why, only when every read does`() {
    val measurement =
      DeviceClock.measure("emulator-5554", burst = 2, read = { error("adb: device offline") })
    val failed = assertIs<DeviceClock.Measurement.Failed>(measurement)
    assertTrue(failed.error.contains("device offline"), failed.error)
    assertTrue(failed.error.contains("emulator-5554"), failed.error)
  }
}

class DeviceClockSamplerTest {

  private fun tempDir() = Files.createTempDirectory("device-clock-sampler").toFile()

  @Test
  fun `a measured offset is recorded as a sample`() {
    val dir = tempDir()
    val sample = DeviceClockSample(1_000, 1_010, 6_005)
    DeviceClockSampler(dir, "emulator-5554") { DeviceClock.Measurement.Measured(sample, listOf(sample)) }
      .sampleOnce()
    val track = DeviceClockSyncFile.read(dir)!!.track("emulator-5554")!!
    assertEquals(listOf(sample), track.samples)
    assertEquals(emptyList(), track.failures)
  }

  @Test
  fun `a failed measurement is recorded as a failure, with no sample`() {
    val dir = tempDir()
    DeviceClockSampler(dir, "emulator-5554") { DeviceClock.Measurement.Failed("adb: offline") }
      .sampleOnce()
    val track = DeviceClockSyncFile.read(dir)!!.devices.single()
    assertEquals(emptyList(), track.samples)
    assertEquals("adb: offline", track.failures.single().error)
    assertEquals(null, DeviceClockSyncFile.read(dir)!!.track(), "a failure is never a usable track")
  }

  @Test
  fun `a run is bracketed by a sample at start and one at stop`() {
    val dir = tempDir()
    var calls = 0L
    val sampler =
      DeviceClockSampler(dir, "emulator-5554", intervalMs = 60_000) {
        calls++
        val sample = DeviceClockSample(calls, calls, calls + 5_000)
        DeviceClock.Measurement.Measured(sample, listOf(sample))
      }
    sampler.start()
    // The start sample runs on the sampler's thread; stop() waits for it before its own.
    sampler.stop()
    assertEquals(2, DeviceClockSyncFile.read(dir)!!.track()!!.samples.size)
  }

  @Test
  fun `a read stuck past its timeout blocks the next read on that device until it returns`() {
    val reads = DeviceClock.AbandonedReads()
    val release = CountDownLatch(1)
    val workerDone = CountDownLatch(1)
    // Times out (null) while its worker is still parked, as a stalled adb socket read does.
    val first =
      reads.guard<String>("emulator-5554") { onFinished ->
        thread(isDaemon = true) {
          release.await()
          onFinished()
          workerDone.countDown()
        }
        null
      }
    assertEquals(null, first)
    assertFailsWith<IllegalStateException> { reads.guard("emulator-5554") { "1700000000000" } }
    assertEquals("1700000000000", reads.guard("emulator-5556") { "1700000000000" }, "other devices still read")

    release.countDown()
    workerDone.await()
    assertEquals("1700000000000", reads.guard("emulator-5554") { "1700000000000" })
  }

  @Test
  fun `overlapping reads that finish are never blocked`() {
    val reads = DeviceClock.AbandonedReads()
    assertEquals(null, reads.guard<String>("emulator-5554") { onFinished -> onFinished(); null })
    val inner = reads.guard("emulator-5554") { onFinished ->
      reads.guard("emulator-5554") { "2" }.also { onFinished() }
    }
    assertEquals("2", inner)
  }
}

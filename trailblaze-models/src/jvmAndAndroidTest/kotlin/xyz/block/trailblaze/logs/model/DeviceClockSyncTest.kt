package xyz.block.trailblaze.logs.model

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The conversion rules are pinned cross-language by `device-clock-parity-fixtures.json`. */
class DeviceClockSyncTest {

  private val start = DeviceClockSample(hostBeforeMs = 1_000, hostAfterMs = 1_010, deviceMs = 6_005)
  private val end = DeviceClockSample(hostBeforeMs = 61_000, hostAfterMs = 61_021, deviceMs = 66_111)

  @Test
  fun `a sample's offset is against the midpoint of its round trip`() {
    assertEquals(1_005L, start.hostMidMs)
    assertEquals(5_000L, start.deviceAheadMs)
    assertEquals(61_010L, end.hostMidMs, "an odd round trip truncates")
    assertEquals(5_101L, end.deviceAheadMs)
  }

  @Test
  fun `tightest is the shortest round trip, first on a tie`() {
    val a = DeviceClockSample(0, 8, 0)
    val b = DeviceClockSample(10, 18, 10)
    assertEquals(a, DeviceClockSample.tightest(listOf(DeviceClockSample(0, 30, 0), a, b)))
    assertNull(DeviceClockSample.tightest(emptyList()))
  }

  @Test
  fun `offsets clamp outside the sampled range and interpolate inside it`() {
    val track = DeviceClockTrack("emulator-5554", samples = listOf(end, start))
    assertEquals(5_000L, track.deviceAheadMsAt(0))
    assertEquals(5_101L, track.deviceAheadMsAt(1_000_000))
    assertEquals(5_051L, track.deviceAheadMsAt(36_058), "halfway, rounded half up")
    assertEquals(31_007L, track.toHostMs(36_058))
    assertNull(DeviceClockTrack("emulator-5554").toHostMs(36_058))
  }

  @Test
  fun `an unattributed record converts only when one device was sampled`() {
    val one = DeviceClockSync(listOf(DeviceClockTrack("a", listOf(start))))
    assertEquals(5_000L, one.toHostMs(10_000))
    assertEquals(5_000L, one.toHostMs(10_000, deviceId = "unknown"))

    val two = one.copy(devices = one.devices + DeviceClockTrack("b", listOf(DeviceClockSample(1_000, 1_004, 702))))
    assertNull(two.toHostMs(10_000))
    assertEquals(10_300L, two.toHostMs(10_000, deviceId = "b"))
  }

  @Test
  fun `the file round-trips and survives concurrent appends from several devices`() {
    val dir = Files.createTempDirectory("device-clock").toFile()
    val threads =
      listOf("a", "b").map { id ->
        thread { repeat(20) { DeviceClockSyncFile.appendSample(dir, id, DeviceClockSample(it.toLong(), it.toLong(), 0)) } }
      }
    threads.forEach { it.join() }
    DeviceClockSyncFile.appendFailure(dir, "a", DeviceClockReadFailure(5, "offline"))

    val sync = DeviceClockSyncFile.read(dir)!!
    assertEquals(listOf(20, 20), sync.devices.sortedBy { it.deviceId }.map { it.samples.size })
    assertEquals("offline", sync.track("a")!!.failures.single().error)
    assertEquals(sync, DeviceClockSync.decodeOrNull(DeviceClockSync.encode(sync)))
  }

  @Test
  fun `naming devices keeps their samples and lets each stream find its own device`() {
    val dir = Files.createTempDirectory("device-clock").toFile()
    DeviceClockSyncFile.appendSample(dir, "emulator-5554", start)
    DeviceClockSyncFile.nameDevices(dir, "emulator-5554", mapOf("emulator-5554" to "seller", "emulator-5556" to "buyer"))
    DeviceClockSyncFile.appendSample(dir, "emulator-5556", DeviceClockSample(1_000, 1_004, 702))

    val sync = DeviceClockSyncFile.read(dir)!!
    assertEquals("emulator-5554", sync.startDeviceId)
    assertEquals(listOf(start), sync.devices.single { it.deviceName == "seller" }.samples)
    assertEquals(10_300L, sync.toHostMsForStream(10_000, "network.buyer"))
    assertEquals(5_000L, sync.toHostMsForStream(10_000, "network.seller"))
    assertEquals(5_000L, sync.toHostMs(10_000), "a record naming no device came from the start device")
  }

  @Test
  fun `a damaged or absent file reads as not measured`() {
    val dir = Files.createTempDirectory("device-clock").toFile()
    assertNull(DeviceClockSyncFile.read(dir))
    File(dir, DeviceClockSync.FILE_NAME).writeText("{not json")
    assertNull(DeviceClockSyncFile.read(dir))
    // Valid JSON, but past the cap: never loaded.
    File(dir, DeviceClockSync.FILE_NAME).writeText("{" + " ".repeat(DeviceClockSyncFile.MAX_BYTES.toInt()) + "}")
    assertNull(DeviceClockSyncFile.read(dir))
  }

  @Test
  fun `a file past the cap is never rewritten as empty`() {
    val dir = Files.createTempDirectory("device-clock").toFile()
    val file = File(dir, DeviceClockSync.FILE_NAME)
    val oversized = "{\"devices\":[]}" + " ".repeat(DeviceClockSyncFile.MAX_BYTES.toInt())
    file.writeText(oversized)
    assertFailsWith<IllegalStateException> {
      DeviceClockSyncFile.appendSample(dir, "emulator-5554", DeviceClockSample(1, 1, 1))
    }
    assertEquals(oversized.length.toLong(), file.length(), "the existing file is left as it is")
  }

  @Test
  fun `the clock file is never mistaken for a session log`() {
    // Log discovery takes any `*.json` whose name starts with a hex digit.
    val first = DeviceClockSync.FILE_NAME.first()
    assertFalse(first.isDigit() || first.lowercaseChar() in 'a'..'f', DeviceClockSync.FILE_NAME)
  }
}

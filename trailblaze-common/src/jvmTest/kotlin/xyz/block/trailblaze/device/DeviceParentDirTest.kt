package xyz.block.trailblaze.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceParentDirTest {

  @Test
  fun `the parent of a device path keeps its forward slashes on every host`() {
    assertEquals("/sdcard/Download/trailblaze", deviceParentDir("/sdcard/Download/trailblaze/a b.txt"))
  }

  @Test
  fun `a file at the device root or with no directory has no parent to create`() {
    assertNull(deviceParentDir("/a.txt"))
    assertNull(deviceParentDir("a.txt"))
  }
}

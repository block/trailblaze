package xyz.block.trailblaze.host.screenstate

import java.io.File
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.util.CoreSimulatorTempFiles

class CaptureAxeScreenshotTest {

  @Test
  fun `the capture target is where CoreSimulator can write, and is removed afterwards`() {
    var target: File? = null
    val bytes = byteArrayOf(1, 2, 3)

    val result = captureAxeScreenshot("SIM") { udid, path ->
      assertEquals("SIM", udid)
      target = File(path)
      target!!.writeBytes(bytes)
      AxeCli.Result(0, "", "")
    }

    assertContentEquals(bytes, result)
    val probe = CoreSimulatorTempFiles.createTempFile("probe-", ".png")
    try {
      assertEquals(probe.parentFile.canonicalFile, target!!.parentFile.canonicalFile)
    } finally {
      probe.delete()
    }
    assertFalse(target!!.exists())
  }

  @Test
  fun `a failed capture returns null and removes the target`() {
    var target: File? = null

    val result = captureAxeScreenshot("SIM") { _, path ->
      target = File(path)
      AxeCli.Result(1, "", "simctl failed")
    }

    assertNull(result)
    assertFalse(target!!.exists())
  }
}

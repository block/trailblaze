package xyz.block.trailblaze.cli

import picocli.CommandLine
import xyz.block.trailblaze.host.lease.DeviceLeaseClient
import xyz.block.trailblaze.host.lease.LeasableDeviceStatus
import xyz.block.trailblaze.host.lease.PairedDevicePools
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevicePairCommandTest {

  private val dir: File = createTempDirectory("device-pools").toFile()
  private val paired = PairedDevicePools(File(dir, PairedDevicePools.FILE_NAME))

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun lender(reachable: Boolean = true, lending: Boolean = true) = { url: String ->
    object : DeviceLeaseClient(url) {
      override fun devices(): List<LeasableDeviceStatus> {
        if (!reachable) throw IOException("connection refused")
        if (!lending) throw IllegalArgumentException("Unexpected JSON token at offset 0: <html>")
        return listOf(LeasableDeviceStatus("android/emulator-5554", TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, leased = false))
      }
    }
  }

  private fun pair(vararg args: String, reachable: Boolean = true, lending: Boolean = true): Int {
    val command = DevicePairCommand().also {
      it.paired = paired
      it.clientFor = lender(reachable, lending)
    }
    return CommandLine(command).execute(*args)
  }

  private fun unpair(url: String): Int =
    CommandLine(DeviceUnpairCommand().also { it.paired = paired }).execute(url)

  @Test
  fun `pairing with a lending daemon saves it`() {
    assertEquals(TrailblazeExitCode.SUCCESS.code, pair("http://box:31900/"))
    assertEquals(listOf("http://box:31900"), paired.urls())
  }

  @Test
  fun `pairing twice keeps one entry`() {
    pair("http://box:31900")
    assertEquals(TrailblazeExitCode.SUCCESS.code, pair("http://box:31900"))
    assertEquals(listOf("http://box:31900"), paired.urls())
  }

  @Test
  fun `a daemon that can't be reached is not saved`() {
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, pair("http://box:31900", reachable = false))
    assertEquals(emptyList(), paired.urls())
  }

  @Test
  fun `a server that answers but is not a lending daemon is not saved`() {
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, pair("http://box:8080", lending = false))
    assertEquals(emptyList(), paired.urls())
  }

  @Test
  fun `something that is not a daemon url is refused`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, pair("box:31900"))
    assertEquals(TrailblazeExitCode.MISUSE.code, pair("http://box:31900/leases"))
    assertEquals(emptyList(), paired.urls())
  }

  @Test
  fun `unpairing removes only that daemon`() {
    pair("http://a:31900")
    pair("http://b:31900")
    assertEquals(TrailblazeExitCode.SUCCESS.code, unpair("http://a:31900/"))
    assertEquals(listOf("http://b:31900"), paired.urls())
    assertEquals(TrailblazeExitCode.MISUSE.code, unpair("http://a:31900"))
  }

  @Test
  fun `urls are normalized or rejected`() {
    assertEquals("https://box", PairedDevicePools.normalize(" https://box/ "))
    assertNull(PairedDevicePools.normalize("ftp://box:1"))
    assertNull(PairedDevicePools.normalize("http://box:1?x=1"))
  }
}

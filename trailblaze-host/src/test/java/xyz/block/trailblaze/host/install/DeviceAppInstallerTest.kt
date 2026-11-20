package xyz.block.trailblaze.host.install

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.MobileDeviceUtils.InstallResult
import xyz.block.trailblaze.host.lease.RemoteInstallReply
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAppInstallerTest {

  private val ownPhone = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val borrowedPhone = TrailblazeDeviceId("emulator-5554@pool:31900", TrailblazeDevicePlatform.ANDROID)
  private val borrowedSimulator = TrailblazeDeviceId("8F2C-SIM@pool:31900", TrailblazeDevicePlatform.IOS)

  private val dir = createTempDirectory("installer-test").toFile()

  @AfterTest fun cleanUp() { dir.deleteRecursively() }

  private val installedHere = mutableListOf<Pair<TrailblazeDeviceId, File>>()

  /** What reached a lender: the device, the uploaded file's name, its zip entries if a zip, and whether it existed. */
  private val sent = mutableListOf<Sent>()
  private data class Sent(val device: TrailblazeDeviceId, val name: String, val entries: List<String>, val file: File)

  private var lenderThrows: Exception? = null

  private val installer = DeviceAppInstaller(
    isBorrowed = { it.instanceId.contains('@') },
    installOnLender = { device, file ->
      lenderThrows?.let { throw it }
      val entries = if (file.extension == "zip") ZipFile(file).use { zip -> zip.entries().toList().map { it.name } } else emptyList()
      sent += Sent(device, file.name, entries, file)
      RemoteInstallReply(success = true, message = "installed on the lender")
    },
    installHere = { device, file ->
      assertEquals(device, usedNow, "an own device is in use for the whole install")
      installedHere += device to file
      InstallResult(true, "installed here")
    },
    inUse = { device, install ->
      val busy = busyReason
      if (busy != null) {
        Result.failure(IllegalStateException(busy))
      } else {
        usedNow = device
        try { Result.success(install()) } finally { usedNow = null }
      }
    },
  )

  /** The own device in use right now, as the lease book would see it. */
  private var usedNow: TrailblazeDeviceId? = null

  /** Set when a borrower is using this machine's own device. */
  private var busyReason: String? = null

  private fun apk() = File(dir, "app.apk").apply { writeText("apk") }

  @Test
  fun `this machine's own device installs here and sends nothing`() = runBlocking {
    val apk = apk()
    assertEquals(InstallResult(true, "installed here"), installer.install(ownPhone, apk))
    assertEquals(listOf(ownPhone to apk), installedHere)
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `this machine's own device that a borrower is using is not installed on`() = runBlocking {
    busyReason = "Device android/emulator-5554 is busy.\n  Held by: bob (borrowed from this machine)"
    assertEquals(InstallResult(false, busyReason!!), installer.install(ownPhone, apk()))
    assertTrue(installedHere.isEmpty())
  }

  @Test
  fun `a borrowed device's app goes to its lender as it is`() = runBlocking {
    assertEquals(InstallResult(true, "installed on the lender"), installer.install(borrowedPhone, apk()))
    assertEquals(borrowedPhone to "app.apk", sent.single().let { it.device to it.name })
    assertTrue(installedHere.isEmpty())
  }

  @Test
  fun `an app bundle goes to the lender zipped, with its links, and the zip is not kept`() = runBlocking {
    val app = File(dir, "Sample.app").apply { mkdirs() }
    File(app, "Info.plist").writeText("plist")
    File(app, "Frameworks").mkdirs()
    Files.createSymbolicLink(File(app, "Current").toPath(), File("Frameworks").toPath())
    assertEquals(InstallResult(true, "installed on the lender"), installer.install(borrowedSimulator, app))
    val upload = sent.single()
    assertTrue(upload.name.endsWith(".zip"))
    assertEquals(setOf("Sample.app/", "Sample.app/Info.plist", "Sample.app/Frameworks/", "Sample.app/Current"), upload.entries.toSet())
    assertFalse(upload.file.exists())
  }

  @Test
  fun `a file the device can't install is refused before it is sent`() = runBlocking {
    val ipa = File(dir, "App.ipa").apply { writeText("ipa") }
    val result = installer.install(borrowedSimulator, ipa)
    assertFalse(result.success)
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a lender that can't be reached or took the device back fails the install with its reason`() = runBlocking {
    lenderThrows = IllegalStateException("android/emulator-5554@pool:31900 is no longer lent to this machine")
    assertEquals(
      InstallResult(false, "android/emulator-5554@pool:31900 is no longer lent to this machine"),
      installer.install(borrowedPhone, apk()),
    )
  }

  @Test
  fun `any other failure sending to the lender fails the install with its reason`() = runBlocking {
    lenderThrows = IllegalArgumentException("unexpected reply")
    assertEquals(InstallResult(false, "unexpected reply"), installer.install(borrowedPhone, apk()))
  }
}

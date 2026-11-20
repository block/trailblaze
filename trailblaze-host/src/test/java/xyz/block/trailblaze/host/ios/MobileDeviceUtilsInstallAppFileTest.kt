package xyz.block.trailblaze.host.ios

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An app archive may come from another machine, so nothing in it may reach outside where it is unpacked. */
class MobileDeviceUtilsInstallAppFileTest {

  private val dir = createTempDirectory("install-app-file").toFile()
  private val simulator = TrailblazeDeviceId("8F2C-SIM", TrailblazeDevicePlatform.IOS)

  @AfterTest fun cleanUp() { dir.deleteRecursively() }

  @Test
  fun `a symlink in an app archive is neither followed to find the app nor deleted through`() = runBlocking {
    // Elsewhere on this machine: a file someone cares about, and an app bundle.
    val outside = File(dir, "outside").apply { mkdirs() }
    val precious = File(outside, "keep.txt").apply { writeText("keep") }
    File(outside, "Other.app").mkdirs()
    // The archive holds only a link to it.
    val staging = File(dir, "staging").apply { mkdirs() }
    Files.createSymbolicLink(File(staging, "link").toPath(), outside.toPath())
    val zip = File(dir, "App.zip")
    val zipped = ProcessBuilder("zip", "-qry", zip.absolutePath, "link").directory(staging).start().waitFor()
    assertEquals(0, zipped)

    val result = MobileDeviceUtils.installAppFile(simulator, zip)

    assertEquals(MobileDeviceUtils.InstallResult(false, "No .app bundle found in App.zip."), result)
    assertTrue(precious.exists(), "the link's target is untouched")
    assertTrue(File(outside, "Other.app").isDirectory)
  }

  @Test
  fun `an archive with two entries of one name is extracted without a prompt`() {
    // unzip asks "replace?" on the second entry unless told not to, and nothing answers it.
    val zip = File(dir, "Dup.zip").apply { writeBytes(storedZip("readme.txt" to "first", "readme.txt" to "second")) }

    val result = CompletableFuture.supplyAsync { runBlocking { MobileDeviceUtils.installAppFile(simulator, zip) } }
      .get(60, TimeUnit.SECONDS)

    assertEquals(MobileDeviceUtils.InstallResult(false, "No .app bundle found in Dup.zip."), result)
  }

  /** A zip of uncompressed [entries], written by hand: java.util.zip refuses a repeated name. */
  private fun storedZip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    fun ByteArrayOutputStream.short(v: Int) { write(v and 0xff); write((v shr 8) and 0xff) }
    fun ByteArrayOutputStream.int(v: Long) { short((v and 0xffff).toInt()); short(((v shr 16) and 0xffff).toInt()) }
    entries.forEach { (name, text) ->
      val bytes = text.toByteArray()
      val crc = CRC32().apply { update(bytes) }.value
      val offset = out.size().toLong()
      val nameBytes = name.toByteArray()
      out.int(0x04034b50); out.short(10); out.short(0); out.short(0); out.short(0); out.short(0)
      out.int(crc); out.int(bytes.size.toLong()); out.int(bytes.size.toLong())
      out.short(nameBytes.size); out.short(0); out.write(nameBytes); out.write(bytes)
      central.int(0x02014b50); central.short(20); central.short(10); central.short(0); central.short(0)
      central.short(0); central.short(0); central.int(crc); central.int(bytes.size.toLong()); central.int(bytes.size.toLong())
      central.short(nameBytes.size); central.short(0); central.short(0); central.short(0); central.short(0)
      central.int(0); central.int(offset); central.write(nameBytes)
    }
    val centralOffset = out.size().toLong()
    val centralBytes = central.toByteArray()
    out.write(centralBytes)
    out.int(0x06054b50); out.short(0); out.short(0); out.short(entries.size); out.short(entries.size)
    out.int(centralBytes.size.toLong()); out.int(centralOffset); out.short(0)
    return out.toByteArray()
  }
}

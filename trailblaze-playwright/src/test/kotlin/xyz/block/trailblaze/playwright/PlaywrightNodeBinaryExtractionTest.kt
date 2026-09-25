package xyz.block.trailblaze.playwright

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The cache treats the Node binary's presence as "installed", so a copy interrupted mid-write must
 * never leave a truncated `node` where the next run will trust it.
 */
class PlaywrightNodeBinaryExtractionTest {

  @Test
  fun `a truncated node left by an interrupted copy is replaced, with no staging dir left behind`() {
    val work = Files.createTempDirectory("pw-node-extract-test")
    try {
      val jar = fakeDriverBundle(work.resolve("driver-bundle.jar"), "test-os", node = "full node binary")
      val driverDir = work.resolve("driver")
      Files.createDirectories(driverDir)
      Files.writeString(driverDir.resolve("node"), "trunc")

      PlaywrightDriverManager.extractNodeBinary(jar, "test-os", driverDir)

      val node = driverDir.resolve("node")
      assertEquals("full node binary", Files.readString(node))
      assertTrue(Files.isExecutable(node), "node should be executable")
      assertEquals(listOf("LICENSE", "node"), driverDir.toFile().list()!!.sorted())
    } finally {
      work.toFile().deleteRecursively()
    }
  }

  @Test
  fun `an extraction that fails partway publishes nothing`() {
    val work = Files.createTempDirectory("pw-node-extract-test")
    try {
      // `node/x` after `node` can't be created (its parent is a file), so extraction throws
      // after node's bytes are already written. In place, that node would be left for the cache.
      val jar = fakeDriverBundle(
        work.resolve("driver-bundle.jar"),
        "test-os",
        node = "full node binary",
        extra = mapOf("driver/test-os/node/x" to "conflict"),
      )
      val driverDir = work.resolve("driver")

      assertFails { PlaywrightDriverManager.extractNodeBinary(jar, "test-os", driverDir) }

      assertEquals(emptyList(), driverDir.toFile().list()!!.toList())
    } finally {
      work.toFile().deleteRecursively()
    }
  }

  private fun fakeDriverBundle(
    jar: Path,
    platform: String,
    node: String,
    extra: Map<String, String> = emptyMap(),
  ): Path {
    ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
      val entries = mapOf("driver/$platform/LICENSE" to "license", "driver/$platform/node" to node) + extra
      entries.forEach { (name, body) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray())
        zip.closeEntry()
      }
    }
    return jar
  }
}

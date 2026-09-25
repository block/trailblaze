package xyz.block.trailblaze.playwright

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The uber JAR excludes `driver-bundle`, and since Playwright 1.63 that bundle carries only Node.
 * The JS driver package has to come off the classpath instead, laid out where Playwright's
 * preinstalled-driver mode (`playwright.cli.dir`) looks for it.
 */
class PlaywrightDriverPackageExtractionTest {

  @Test
  fun `driver package is copied into the cli dir layout Playwright expects`() {
    val driverDir = Files.createTempDirectory("pw-driver-package-test")
    try {
      PlaywrightDriverManager.extractDriverPackage(driverDir)

      assertTrue(Files.isRegularFile(driverDir.resolve("package/cli.js")), "package/cli.js")
      // Read by PlaywrightDriverManager to know which Chromium revision to expect.
      assertTrue(Files.isRegularFile(driverDir.resolve("package/browsers.json")), "package/browsers.json")
    } finally {
      driverDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a complete package published by another process is kept, not deleted and replaced`() {
    val driverDir = Files.createTempDirectory("pw-driver-package-test")
    try {
      val theirs = driverDir.resolve("package/cli.js")
      Files.createDirectories(theirs.parent)
      Files.writeString(theirs, "published by another JVM")

      PlaywrightDriverManager.extractDriverPackage(driverDir)

      assertEquals("published by another JVM", Files.readString(theirs))
      assertEquals(listOf("package"), driverDir.toFile().list()!!.sorted())
    } finally {
      driverDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a partial package left by an interrupted extraction is replaced, with no staging dir left behind`() {
    val driverDir = Files.createTempDirectory("pw-driver-package-test")
    try {
      val stale = driverDir.resolve("package/lib/leftover.js")
      Files.createDirectories(stale.parent)
      Files.writeString(stale, "partial")

      PlaywrightDriverManager.extractDriverPackage(driverDir)

      assertTrue(Files.isRegularFile(driverDir.resolve("package/cli.js")), "package/cli.js")
      assertFalse(Files.exists(stale), "stale partial file should be gone")
      assertEquals(listOf("package"), driverDir.toFile().list()!!.sorted())
    } finally {
      driverDir.toFile().deleteRecursively()
    }
  }
}

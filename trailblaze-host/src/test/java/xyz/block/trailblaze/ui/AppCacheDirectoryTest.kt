package xyz.block.trailblaze.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CliOutCapture
import xyz.block.trailblaze.cli.DesktopLogFileWriter
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionCache
import xyz.block.trailblaze.util.Console

class AppCacheDirectoryTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `marks new and existing caches without excluding persistent state`() {
    for (existing in listOf(false, true)) {
      val appDataDir = tempFolder.newFolder("state-$existing")
      val settings = File(appDataDir, "trailblaze-settings.json").apply { writeText("{}") }
      val logs = File(appDataDir, "logs").apply { mkdirs() }
      val bundle = File(appDataDir, "cache/scripted-bundles/tool.bundle.js")
      if (existing) {
        bundle.parentFile.mkdirs()
        bundle.writeText("cached bundle")
      }

      TrailblazeDesktopUtil.markCacheDirectory(File(appDataDir, "cache"))

      val tag = File(appDataDir, "cache/CACHEDIR.TAG")
      assertEquals("Signature: 8a477f597d28d172789f06886806bc55", tag.readText().lineSequence().first())
      assertFalse(File(appDataDir, "CACHEDIR.TAG").exists())
      assertFalse(File(logs, "CACHEDIR.TAG").exists())
      assertEquals("{}", settings.readText())
      if (existing) assertEquals("cached bundle", bundle.readText())

      tag.appendText("# Existing annotation\n")
      val annotatedTag = tag.readText()
      TrailblazeDesktopUtil.markCacheDirectory(File(appDataDir, "cache"))
      assertEquals(annotatedTag, tag.readText())
    }
  }

  @Test
  fun `repairs empty truncated and incorrect signatures but preserves valid tags`() {
    val signature = "Signature: 8a477f597d28d172789f06886806bc55"
    val cacheDir = tempFolder.newFolder("repair")
    val tag = File(cacheDir, "CACHEDIR.TAG")
    for (invalid in listOf("", signature.dropLast(1), signature.dropLast(1) + "0", "\n$signature")) {
      tag.writeText(invalid)
      TrailblazeDesktopUtil.markCacheDirectory(cacheDir)
      assertEquals(signature, tag.readText().lineSequence().first())
    }
    // The format requires the signature bytes, not a newline or a particular comment format.
    for (valid in listOf(signature, "$signature\n# Keep this annotation\n")) {
      tag.writeText(valid)
      TrailblazeDesktopUtil.markCacheDirectory(cacheDir)
      assertEquals(valid, tag.readText())
    }
  }

  @Test
  fun `analyzer marks its workspace cache without excluding workspace state`() {
    val workspace = tempFolder.newFolder("workspace")
    val cacheDir = File(workspace, ".trailblaze/cache/analyzer").apply { mkdirs() }
    val entry = File(cacheDir, "existing.json").apply { writeText("cached analysis") }
    val state = File(workspace, ".trailblaze/settings.json").apply { writeText("{}") }

    ScriptedToolDefinitionCache(cacheDir, "dependency-key")

    assertEquals(
      "Signature: 8a477f597d28d172789f06886806bc55",
      File(cacheDir, "CACHEDIR.TAG").readText().lineSequence().first(),
    )
    assertFalse(File(workspace, "CACHEDIR.TAG").exists())
    assertFalse(File(workspace, ".trailblaze/CACHEDIR.TAG").exists())
    assertEquals("cached analysis", entry.readText())
    assertEquals("{}", state.readText())
  }

  @Test
  fun `cannot write a tag without a cache directory but startup can continue`() {
    val appDataDir = tempFolder.newFolder("blocked")
    val blockingFile = File(appDataDir, "cache").apply { writeText("not a directory") }

    TrailblazeDesktopUtil.markCacheDirectory(blockingFile)

    assertEquals("not a directory", blockingFile.readText())
  }

  @Test
  fun `early tagging failure does not keep later console output out of the desktop log`() {
    // Console caches stdout on first use, so only a fresh JVM exercises startup ordering.
    val appDataDir = tempFolder.newFolder("startup")
    File(appDataDir, "cache").writeText("not a directory")
    val output = File(appDataDir, "probe-output.txt")
    val process = ProcessBuilder(
      File(System.getProperty("java.home"), "bin/java").absolutePath,
      "-cp",
      System.getProperty("java.class.path"),
      CacheTagStartupProbe::class.java.name,
    ).apply {
      environment()["TRAILBLAZE_HOME"] = appDataDir.absolutePath
      redirectErrorStream(true)
      redirectOutput(output)
    }.start()
    try {
      // Hang containment, not a startup performance assertion.
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Probe JVM did not exit")
      assertEquals(0, process.exitValue(), output.readText())
      assertTrue(
        File(appDataDir, "desktop-logs/trailblaze.log").readText().contains("after-cache-tagging"),
      )
    } finally {
      process.destroyForcibly().waitFor()
    }
  }
}

object CacheTagStartupProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    CliOutCapture.install()
    TrailblazeDesktopUtil.markCacheDirectory()
    DesktopLogFileWriter.install(disableFlag = null)
    Console.log("after-cache-tagging")
  }
}

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.api.GradleException

/**
 * Unit tests for the pure pieces behind `trailblazeAndroid { inProcessIdle { } }` — naming
 * conventions, manifest stamping (against the REAL bundled template, so template/code drift fails
 * here instead of at a consumer's build), signing-mode resolution, and SDK tool-version picking.
 */
class TrailblazeInProcessIdleApkTest {

  private fun bundledTemplate(): String =
    javaClass.classLoader.getResourceAsStream(InProcessIdleConventions.MANIFEST_RESOURCE)!!.use {
      it.readBytes().toString(Charsets.UTF_8)
    }

  @Test
  fun `idle detector package suffix is the last dotted label`() {
    assertEquals("development", InProcessIdleConventions.packageSuffix("com.example.development"))
    assertEquals("app", InProcessIdleConventions.packageSuffix("app"))
  }

  @Test
  fun `asset path and task name follow the farm-bundle conventions`() {
    assertEquals(
      "inprocess-idle-apks/trailblaze-inprocess-idle-sampleapp.apk",
      InProcessIdleConventions.apkAssetPath("xyz.block.trailblaze.examples.sampleapp"),
    )
    assertEquals(
      "buildTrailblazeInProcessIdleSampleappApk",
      InProcessIdleConventions.taskName("xyz.block.trailblaze.examples.sampleapp"),
    )
  }

  @Test
  fun `bundled idle detector source resource is present in the plugin classpath`() {
    val source =
      javaClass.classLoader.getResourceAsStream(InProcessIdleConventions.SOURCE_RESOURCE)?.use {
        it.readBytes().toString(Charsets.UTF_8)
      }
    assertTrue(
      source != null && source.contains("class InProcessIdleInstrumentation"),
      "embedded idle detector source missing or unrecognizable",
    )
    assertTrue(
      source!!.contains("package ${InProcessIdleConventions.IN_PROCESS_IDLE_BASE_PACKAGE};"),
      "embedded idle detector source's java package drifted from IN_PROCESS_IDLE_BASE_PACKAGE",
    )
  }

  @Test
  fun `stampManifest stamps idle detector package suffix and target package on the bundled template`() {
    val stamped = InProcessIdleConventions.stampManifest(bundledTemplate(), "com.example.myapp")
    assertTrue(
      stamped.contains("package=\"xyz.block.trailblaze.inprocessidle.myapp\""),
      "idle detector package not suffixed:\n$stamped",
    )
    assertTrue(
      stamped.contains("android:targetPackage=\"com.example.myapp\""),
      "targetPackage not stamped:\n$stamped",
    )
    // The instrumentation class name keeps the UN-suffixed base package (the class's real package).
    assertTrue(
      stamped.contains("android:name=\"xyz.block.trailblaze.inprocessidle.InProcessIdleInstrumentation\""),
      "instrumentation class name was corrupted by stamping:\n$stamped",
    )
  }

  @Test
  fun `stampManifest fails loudly on a template without the expected anchors`() {
    assertFailsWith<GradleException> {
      InProcessIdleConventions.stampManifest("<manifest package=\"something.else\"/>", "com.example.a")
    }
    assertFailsWith<GradleException> {
      InProcessIdleConventions.stampManifest(
        "<manifest package=\"xyz.block.trailblaze.inprocessidle\"/>",
        "com.example.a",
      )
    }
  }

  @Test
  fun `signing defaults to the standard debug keystore when nothing is configured`() {
    val mode = resolveInProcessIdleSigningMode(null, null, null, null, null)
    assertEquals(InProcessIdleSigningMode.DefaultDebugKeystore, mode)
  }

  @Test
  fun `signing uses the named signing config when only signingConfigName is set`() {
    val mode = resolveInProcessIdleSigningMode(null, null, null, null, "internalRelease")
    assertEquals(InProcessIdleSigningMode.FromSigningConfig("internalRelease"), mode)
  }

  @Test
  fun `explicit signing keeps the quad and defaults keyPassword to the store password`() {
    val ks = File("some.keystore")
    val mode = resolveInProcessIdleSigningMode(ks, "storepw", "alias1", null, null)
    assertEquals(InProcessIdleSigningMode.Explicit(ks, "storepw", "alias1", "storepw"), mode)
    val modeWithKeyPw = resolveInProcessIdleSigningMode(ks, "storepw", "alias1", "keypw", null)
    assertEquals(InProcessIdleSigningMode.Explicit(ks, "storepw", "alias1", "keypw"), modeWithKeyPw)
  }

  @Test
  fun `explicit signing with a missing required property is a directed error`() {
    assertFailsWith<GradleException> {
      resolveInProcessIdleSigningMode(File("some.keystore"), null, "alias1", null, null)
    }
  }

  @Test
  fun `explicit signing and signingConfigName together is a directed error`() {
    assertFailsWith<GradleException> {
      resolveInProcessIdleSigningMode(File("some.keystore"), "pw", "alias1", null, "debug")
    }
  }

  @Test
  fun `pickHighestVersionedName orders numerically and prefers stable over rc`() {
    assertEquals(
      "36.1.0",
      pickHighestVersionedName(listOf("35.0.0", "36.0.0", "36.1.0", "9.0.0")),
    )
    assertEquals("36.0.0", pickHighestVersionedName(listOf("36.0.0-rc1", "36.0.0")))
    assertEquals("36.0.0-rc1", pickHighestVersionedName(listOf("35.0.0", "36.0.0-rc1")))
    assertNull(pickHighestVersionedName(listOf("not-a-version", ".DS_Store")))
  }

  @Test
  fun `appendFileToZip preserves each entry's compression method and round-trips STORED bytes`() {
    // The subtle correctness path the whole approach depends on: an aapt2-linked APK carries a
    // STORED resources.arsc (API 30+ rejects a deflated one), so appending classes.dex must not
    // re-deflate it. Exercised here without an Android SDK — the e2e build only covers it on a
    // machine that has one.
    val dir = Files.createTempDirectory("inprocess-idle-zip-test").toFile()
    try {
      val zip = File(dir, "in.zip")
      val storedBytes = "stored-uncompressed-payload".repeat(8).toByteArray()
      ZipOutputStream(zip.outputStream()).use { out ->
        out.putNextEntry(
          ZipEntry("resources.arsc").apply {
            method = ZipEntry.STORED
            size = storedBytes.size.toLong()
            compressedSize = storedBytes.size.toLong()
            crc = CRC32().apply { update(storedBytes) }.value
          }
        )
        out.write(storedBytes)
        out.closeEntry()
        out.putNextEntry(ZipEntry("AndroidManifest.xml").apply { method = ZipEntry.DEFLATED })
        out.write("<manifest/>".repeat(32).toByteArray())
        out.closeEntry()
      }

      appendFileToZip(zip, File(dir, "classes.dex").apply { writeText("dex-bytes") }, "classes.dex")

      ZipFile(zip).use { result ->
        val stored = assertNotNull(result.getEntry("resources.arsc"), "STORED entry was dropped")
        assertEquals(
          ZipEntry.STORED,
          stored.method,
          "STORED entry must stay STORED — a deflated resources.arsc fails install on API 30+",
        )
        assertEquals(
          ZipEntry.DEFLATED,
          assertNotNull(result.getEntry("AndroidManifest.xml")).method,
        )
        assertNotNull(result.getEntry("classes.dex"), "appended entry is missing")
        assertEquals(
          storedBytes.toList(),
          result.getInputStream(stored).readBytes().toList(),
          "STORED payload must round-trip byte-for-byte",
        )
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `pickHighestPlatformName picks the highest numeric api and skips previews`() {
    assertEquals(
      "android-36",
      pickHighestPlatformName(listOf("android-28", "android-36", "android-Baklava", "junk")),
    )
    assertNull(pickHighestPlatformName(listOf("android-Baklava")))
  }
}

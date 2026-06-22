package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the JAR-bundled analyzer shim fallback in [ScriptedToolDefinitionAnalyzer] —
 * the path that lets an installed CLI (no SDK source tree on disk) analyze typed tools by
 * extracting a self-contained, dependency-bundled `extract-tool-defs.mjs` from the JAR.
 *
 * Covers the pure extraction helper [ScriptedToolDefinitionAnalyzer.extractBundledShim]
 * (no classpath/JAR needed). The resource-loading wrapper `resolveBundledAnalyzerSdkDir`
 * is exercised end-to-end by the installed-CLI OOBE flow rather than a unit test, since it
 * depends on the bundle being staged into JAR resources at build time.
 */
class ScriptedToolAnalyzerBundledShimTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun tempRoot(): File = createTempDirectory("bundled-shim-test").toFile().also { tempDirs += it }

  @Test
  fun `extractBundledShim writes the shim under tools and returns the cache root`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val bytes = "// self-contained shim bundle\n".toByteArray()

    val returned = ScriptedToolDefinitionAnalyzer.extractBundledShim(bytes, cacheRoot)

    assertEquals(cacheRoot, returned)
    val shim = File(cacheRoot, "tools/extract-tool-defs.mjs")
    assertTrue(shim.isFile, "shim should be written at <cacheRoot>/tools/extract-tool-defs.mjs")
    assertTrue(shim.readBytes().contentEquals(bytes), "shim content should match the input bytes")
    // The extracted layout is exactly what resolveExtractorShim() probes for.
    assertEquals(shim, ScriptedToolDefinitionAnalyzer.resolveExtractorShim(cacheRoot))
  }

  @Test
  fun `extractBundledShim is idempotent for identical bytes (no rewrite)`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val bytes = "// stable bundle\n".toByteArray()
    ScriptedToolDefinitionAnalyzer.extractBundledShim(bytes, cacheRoot)
    val shim = File(cacheRoot, "tools/extract-tool-defs.mjs")
    // Stamp an old mtime; a no-op second extraction must leave it untouched.
    shim.setLastModified(1_000_000_000_000L)

    ScriptedToolDefinitionAnalyzer.extractBundledShim(bytes, cacheRoot)

    assertEquals(1_000_000_000_000L, shim.lastModified(), "identical bytes must not trigger a rewrite")
  }

  @Test
  fun `extractBundledShim rewrites when the bundle changes`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    ScriptedToolDefinitionAnalyzer.extractBundledShim("// bundle v1\n".toByteArray(), cacheRoot)
    val v2 = "// bundle v2 — framework upgraded\n".toByteArray()

    ScriptedToolDefinitionAnalyzer.extractBundledShim(v2, cacheRoot)

    val shim = File(cacheRoot, "tools/extract-tool-defs.mjs")
    assertTrue(shim.readBytes().contentEquals(v2), "a changed bundle should overwrite the cached shim")
  }

  @Test
  fun `extractBundledShim writes the self-contained marker`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    ScriptedToolDefinitionAnalyzer.extractBundledShim("// bundle\n".toByteArray(), cacheRoot)
    val marker = File(cacheRoot, ScriptedToolDefinitionAnalyzer.BUNDLED_ANALYZER_MARKER_FILENAME)
    assertTrue(marker.isFile, "extraction must drop the marker so analyzerToolingAvailable accepts the bundled dir")
  }

  @Test
  fun `analyzerToolingAvailable accepts the bundled marker (no node_modules needed)`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    ScriptedToolDefinitionAnalyzer.extractBundledShim("// bundle\n".toByteArray(), cacheRoot)
    // The bundled dir has the marker but NO node_modules — the gate must still pass.
    assertFalse(File(cacheRoot, "node_modules/ts-json-schema-generator").exists())
    assertTrue(
      ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(cacheRoot),
      "a self-contained bundled shim dir (marker present) must be accepted",
    )
  }

  @Test
  fun `analyzerToolingAvailable accepts a real SDK tree with node_modules`() {
    val sdkDir = tempRoot()
    File(sdkDir, "node_modules/ts-json-schema-generator").mkdirs()
    assertTrue(
      ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(sdkDir),
      "an SDK tree with ts-json-schema-generator installed must be accepted",
    )
  }

  @Test
  fun `analyzerToolingAvailable rejects a dir with neither node_modules nor marker`() {
    val bare = tempRoot()
    assertFalse(
      ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(bare),
      "a shim dir with neither installed deps nor the bundled marker must be rejected",
    )
  }
}

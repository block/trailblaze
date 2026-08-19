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

  // ---------------------------------------------------------------------------
  // TypeScript lib payload
  //
  // The bundle inlines TypeScript's code but not its `lib*.d.ts` standard library, and
  // `bun build` freezes the path TypeScript loads those from to the build machine. Shipping
  // the libs and redirecting that frozen path at them is what lets an installed CLI resolve
  // `Record` / `Partial` / `Pick` in a tool's I/O types.
  // ---------------------------------------------------------------------------

  /** Zip with one entry per (path, content) pair, mirroring the shipped `ts-lib.zip` shape. */
  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
      entries.forEach { (path, content) ->
        zip.putNextEntry(java.util.zip.ZipEntry(path))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  @Test
  fun `extraction points the bundle's frozen path at the unpacked libs`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val placeholder = ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER
    // Shaped like the real bundle: TypeScript's CJS `__dirname`/`__filename`, frozen at build
    // time, with the build-machine prefix already rewritten to the placeholder.
    val shim = """
      var __dirname="$placeholder/node_modules/typescript/lib";
      var __filename="$placeholder/node_modules/typescript/lib/typescript.js";
    """.trimIndent().toByteArray()

    ScriptedToolDefinitionAnalyzer.extractBundledShim(
      shim,
      cacheRoot,
      tsLibArchive = zipOf("node_modules/typescript/lib/lib.es5.d.ts" to "declare type Record<K, T> = {}"),
    )

    val libRoot = ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot)
    val written = File(cacheRoot, "tools/extract-tool-defs.mjs").readText()
    assertFalse(
      placeholder in written,
      "no placeholder may survive into the shim the analyzer actually runs; got:\n$written",
    )
    assertTrue(
      "${libRoot.absolutePath}/node_modules/typescript/lib" in written,
      "the frozen path must resolve under the unpacked lib root; got:\n$written",
    )
    // The relative tail is load-bearing: it's what makes one substitution place every file
    // where its own TypeScript copy looks for it.
    assertTrue(
      File(libRoot, "node_modules/typescript/lib/lib.es5.d.ts").isFile,
      "lib entries must unpack at their SDK-relative paths",
    )
  }

  @Test
  fun `each bundled TypeScript copy gets its own libs`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    // `ts-json-schema-generator` pins its own `typescript` range, so the generator runs a
    // nested copy while the shim imports the hoisted one. Sharing one lib set between two
    // compiler versions is exactly the mismatch this layout avoids.
    ScriptedToolDefinitionAnalyzer.extractBundledShim(
      "var __dirname=\"${ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER}/x\";".toByteArray(),
      cacheRoot,
      tsLibArchive = zipOf(
        "node_modules/typescript/lib/lib.es5.d.ts" to "hoisted",
        "node_modules/ts-json-schema-generator/node_modules/typescript/lib/lib.es5.d.ts" to "nested",
      ),
    )

    val libRoot = ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot)
    assertEquals("hoisted", File(libRoot, "node_modules/typescript/lib/lib.es5.d.ts").readText())
    assertEquals(
      "nested",
      File(
        libRoot,
        "node_modules/ts-json-schema-generator/node_modules/typescript/lib/lib.es5.d.ts",
      ).readText(),
    )
  }

  @Test
  fun `re-extracting the same bundle rewrites nothing`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val shim = "var __dirname=\"${ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER}/lib\";".toByteArray()
    val archive = zipOf("node_modules/typescript/lib/lib.es5.d.ts" to "declare type Record<K, T> = {}")
    ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, archive)
    val shimFile = File(cacheRoot, "tools/extract-tool-defs.mjs")
    val libFile = File(
      ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot),
      "node_modules/typescript/lib/lib.es5.d.ts",
    )
    // The substituted bytes — not the input bytes — are what staleness must be judged against,
    // or every run would rewrite the shim and churn its mtime.
    shimFile.setLastModified(1_000_000_000_000L)
    libFile.setLastModified(1_000_000_000_000L)

    ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, archive)

    assertEquals(1_000_000_000_000L, shimFile.lastModified(), "identical bundle must not rewrite the shim")
    assertEquals(1_000_000_000_000L, libFile.lastModified(), "unchanged lib entries must not be rewritten")
  }

  @Test
  fun `a build with no lib payload still writes a usable shim`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val shim = "// no placeholder in here\n".toByteArray()

    ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, tsLibArchive = null)

    // Older/stripped builds ship no archive. That's a degradation, not a crash: the shim is
    // written verbatim and the analyzer keeps working for everything but lib-declared types.
    assertTrue(File(cacheRoot, "tools/extract-tool-defs.mjs").readBytes().contentEquals(shim))
    assertFalse(
      ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot).exists(),
      "no archive means no lib root to create",
    )
  }

  @Test
  fun `a stale lib file of identical length is replaced`() {
    val cacheRoot = File(tempRoot(), "analyzer")
    val entry = "node_modules/typescript/lib/lib.es5.d.ts"
    val shim = "var __dirname=\"${ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER}/lib\";".toByteArray()
    ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, zipOf(entry to "AAAA"))

    // Same byte length, different content — what a TypeScript upgrade can produce. A size-only
    // staleness check leaves the old file in place and the compiler resolves types against the
    // previous release's standard library, with nothing to indicate it.
    ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, zipOf(entry to "BBBB"))

    assertEquals(
      "BBBB",
      File(ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot), entry).readText(),
    )
  }

  @Test
  fun `extraction leaves no staging files behind`() {
    val cacheRoot = File(tempRoot(), "analyzer")

    ScriptedToolDefinitionAnalyzer.extractBundledShim(
      "var x=\"${ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER}\";".toByteArray(),
      cacheRoot,
      zipOf(
        "node_modules/typescript/lib/lib.es5.d.ts" to "a",
        "node_modules/typescript/lib/lib.dom.d.ts" to "b",
      ),
    )

    // Files are staged then renamed so a concurrent reader never sees a partial write; the
    // staging names must not survive, or the cache dir accretes junk on every upgrade.
    val leftovers = cacheRoot.walkTopDown().filter { it.isFile && ".tmp." in it.name }.toList()
    assertTrue(leftovers.isEmpty(), "staging files must be renamed away; found $leftovers")
  }

  @Test
  fun `concurrent extraction into one cache root neither throws nor corrupts`() {
    // `resolveBundledAnalyzerSdkDir`'s memo deliberately tolerates a two-thread double-extract,
    // so staging must be unique per writer rather than per process. With a shared staging name
    // the loser's rename fails once the winner moves the file away, and the caller degrades to
    // "analyzer unavailable" — a failure the plain in-place write never had.
    val cacheRoot = File(tempRoot(), "analyzer")
    val placeholder = ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER
    val shim = "var __dirname=\"$placeholder/node_modules/typescript/lib\";".toByteArray()
    val libBody = "declare type Record<K, T> = {};\n".repeat(64)
    val archive = zipOf(
      "node_modules/typescript/lib/lib.es5.d.ts" to libBody,
      "node_modules/typescript/lib/lib.dom.d.ts" to libBody,
    )

    val threads = 4
    val start = java.util.concurrent.CountDownLatch(1)
    val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    val workers = (1..threads).map {
      Thread {
        start.await()
        runCatching {
          ScriptedToolDefinitionAnalyzer.extractBundledShim(shim, cacheRoot, archive)
        }.onFailure { failures += it }
      }.apply { start() }
    }
    start.countDown()
    workers.forEach { it.join(30_000) }

    assertTrue(failures.isEmpty(), "concurrent extraction must not throw; got $failures")
    val libRoot = ScriptedToolDefinitionAnalyzer.bundledTsLibRoot(cacheRoot)
    // Whichever writer won, every file must be whole — a partial write is the outcome staging
    // exists to prevent, and it would be invisible until the compiler failed on it.
    assertEquals(libBody, File(libRoot, "node_modules/typescript/lib/lib.es5.d.ts").readText())
    assertEquals(libBody, File(libRoot, "node_modules/typescript/lib/lib.dom.d.ts").readText())
    assertTrue(
      File(cacheRoot, "tools/extract-tool-defs.mjs").readText().startsWith("var __dirname="),
      "the shim bun executes must be complete, not half-written",
    )
    val leftovers = cacheRoot.walkTopDown().filter { it.isFile && ".tmp" in it.name }.toList()
    assertTrue(leftovers.isEmpty(), "staging files must not survive; found $leftovers")
  }

  @Test
  fun `lib extraction refuses entries that escape the extraction root`() {
    val root = tempRoot()
    val libRoot = File(root, "ts-lib")
    val escapee = File(root, "escaped.d.ts")

    ScriptedToolDefinitionAnalyzer.extractTsLibArchive(
      zipOf(
        "../escaped.d.ts" to "should not be written",
        "node_modules/typescript/lib/lib.es5.d.ts" to "legitimate",
      ),
      libRoot,
    )

    assertFalse(escapee.exists(), "a traversal entry must not be written outside the lib root")
    assertTrue(
      File(libRoot, "node_modules/typescript/lib/lib.es5.d.ts").isFile,
      "a skipped entry must not abort the rest of the archive",
    )
  }

  @Test
  fun `the shipped bundle resource carries the placeholder and a lib payload`() {
    // Closes the SISTER-IMPL-TAG gap: the placeholder is duplicated between
    // `:trailblaze-models`'s bundling task and this class's constant, with no compile-time
    // check that they agree. Skipped when the build didn't stage the bundle (no `bun` on PATH,
    // or SDK deps not installed) — the same tolerance the bundling task itself has.
    val loader = ScriptedToolDefinitionAnalyzer::class.java.classLoader
    val shimBytes = loader
      ?.getResourceAsStream(ScriptedToolDefinitionAnalyzer.BUNDLED_ANALYZER_SHIM_RESOURCE)
      ?.use { it.readBytes() }
    org.junit.Assume.assumeTrue(
      "requires the analyzer bundle staged into JAR resources",
      shimBytes != null && shimBytes.isNotEmpty(),
    )

    assertTrue(
      ScriptedToolDefinitionAnalyzer.ANALYZER_SDK_ROOT_PLACEHOLDER in shimBytes!!.toString(Charsets.UTF_8),
      "the staged bundle must carry the placeholder this class substitutes; a mismatch here " +
        "means the bundling task and the runtime constant have drifted apart",
    )
    val archive = loader
      ?.getResourceAsStream(ScriptedToolDefinitionAnalyzer.BUNDLED_ANALYZER_TS_LIB_RESOURCE)
      ?.use { it.readBytes() }
    assertTrue(
      archive != null && archive.isNotEmpty(),
      "a bundle carrying the placeholder must ship the lib payload it will be redirected at",
    )
  }
}

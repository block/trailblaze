package xyz.block.trailblaze.config.project

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.testing.ClasspathFixture
import xyz.block.trailblaze.util.Console

class TrailblazePackManifestLoaderTest {

  private val classpath = ClasspathFixture()

  /**
   * Captures `Console.log` output so deprecation-warning tests can assert on the
   * exact text emitted. Uses the same field-reflection trick as `ConsoleTest` —
   * `Console` caches `System.out` at class-init time, so `System.setOut` alone is
   * insufficient.
   *
   * The shared (jvm + android) source set means this same setup runs against
   * `Console.jvm.kt` (which has `out` and `userOut`) AND `Console.android.kt`
   * (which has only `out`). We swap whichever fields exist; missing fields are
   * tolerated so the test class compiles and runs identically in both variants.
   */
  private var capturedLog: ByteArrayOutputStream? = null
  private val originalConsoleStreams = mutableMapOf<String, PrintStream>()

  @BeforeTest fun setUpConsoleCapture() {
    val newCapture = ByteArrayOutputStream()
    val newStream = PrintStream(newCapture, /* autoFlush = */ true, Charsets.UTF_8)
    listOf("out", "userOut").forEach { fieldName ->
      val field = runCatching {
        Console::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
      }.getOrNull() ?: return@forEach
      originalConsoleStreams[fieldName] = field.get(Console) as PrintStream
      field.set(Console, newStream)
    }
    capturedLog = newCapture
  }

  @AfterTest fun cleanup() {
    classpath.cleanup()
    // Clear the per-classloader cache + reserved-field warning dedup so dead test
    // classloaders don't linger in the loader's static state across tests in the same JVM.
    TrailblazePackManifestLoader.clearClasspathPackCacheForTesting()
    // Restore Console so subsequent tests / JVM teardown see the original streams.
    originalConsoleStreams.forEach { (fieldName, original) ->
      Console::class.java.getDeclaredField(fieldName).apply { isAccessible = true }.set(Console, original)
    }
    originalConsoleStreams.clear()
    capturedLog = null
  }

  private fun capturedText(): String =
    capturedLog?.toString(Charsets.UTF_8) ?: error("Console capture not initialized")

  @Test
  fun `loader decodes dependencies and defaults`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: sample
          dependencies:
            - trailblaze
            - other-lib
          defaults:
            android:
              tool_sets:
                - core_interaction
            ios:
              tool_sets:
                - core_interaction
          """.trimIndent(),
        )
      }

      val manifest = TrailblazePackManifestLoader.load(packFile).manifest

      assertEquals(listOf("trailblaze", "other-lib"), manifest.dependencies)
      assertEquals(
        setOf("android", "ios"),
        assertNotNull(manifest.defaults).keys,
      )
      assertEquals(
        listOf("core_interaction"),
        manifest.defaults?.get("android")?.toolSets,
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Deprecation warning for the legacy `use:` / `extend:` / `replace:` fields.
  // These were removed in favour of `dependencies:`. kaml's strictMode
  // is off so unknown keys parse silently — `warnOnRemovedComposeFields` exists
  // exactly to keep upgrading authors from losing those fields without a signal.
  // ==========================================================================

  @Test
  fun `loader warns when manifest declares a top-level legacy use field`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: legacy
          target:
            display_name: Legacy Pack
          use:
            - some-other-pack
          """.trimIndent(),
        )
      }

      // The manifest must still load — the warning is a nudge, not a hard error.
      val manifest = TrailblazePackManifestLoader.load(packFile).manifest
      assertEquals("legacy", manifest.id)
      assertEquals(emptyList(), manifest.dependencies)

      val log = capturedText()
      assertTrue(
        log.contains("legacy composition field(s)") && log.contains("use"),
        "Expected deprecation warning naming `use`; got: $log",
      )
      assertTrue(
        log.contains("dependencies:"),
        "Expected migration nudge to mention `dependencies:`; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `legacy-field warning dedups so it does not re-fire on a second load of the same pack`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: legacy
          target:
            display_name: Legacy Pack
          extend:
            - some-other-pack
          """.trimIndent(),
        )
      }

      TrailblazePackManifestLoader.load(packFile)
      TrailblazePackManifestLoader.load(packFile)

      // The dedup is keyed on `removed-compose:<id>@<source>`. Two loads of the
      // same file produce the same key, so the warning fires at most once.
      val log = capturedText()
      val warningCount = log.split("legacy composition field(s)").size - 1
      assertEquals(
        1,
        warningCount,
        "Expected the deprecation warning to fire exactly once across two loads, got $warningCount.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `loader does not warn when use appears only as a nested key not at column 0`() {
    // Regression test for a column-0 fix: an earlier implementation matched the
    // legacy field names at any indent level, which would falsely trip on a nested
    // YAML key like `target.platforms.android.use:`. Top-level keys in pack.yaml
    // files are always at column 0, so the strict prefix check is sufficient.
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: nested-only
          target:
            display_name: Nested-Only Pack
            platforms:
              android:
                # 'use' here is a nested platform key, NOT the legacy top-level field.
                use:
                  - hypothetical-future-platform-field
          """.trimIndent(),
        )
      }

      TrailblazePackManifestLoader.load(packFile)

      val log = capturedText()
      assertFalse(
        log.contains("legacy composition field(s)"),
        "Expected NO deprecation warning for a nested `use:` key, but got one.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Deprecation warning for the removed `routes:` field. Removed 2026-04-28 as
  // part of the shortcuts-as-tools unification. Same string-scan + dedup
  // pattern as the legacy-compose warning above; tests mirror that flow.
  // ==========================================================================

  @Test
  fun `loader warns when manifest declares the removed top-level routes field`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: legacy-nav
          target:
            display_name: Legacy Nav Pack
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      // The manifest must still load — the warning is a nudge, not a hard error.
      val manifest = TrailblazePackManifestLoader.load(packFile).manifest
      assertEquals("legacy-nav", manifest.id)

      val log = capturedText()
      assertTrue(
        log.contains("removed navigation field(s)") && log.contains("routes"),
        "Expected deprecation warning naming `routes`; got: $log",
      )
      assertTrue(
        log.contains("shortcuts-as-tools"),
        "Expected migration nudge to mention shortcuts-as-tools; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `removed-routes warning dedups across two loads of the same pack`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: legacy-nav-dedup
          target:
            display_name: Legacy Nav Pack
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      TrailblazePackManifestLoader.load(packFile)
      TrailblazePackManifestLoader.load(packFile)

      // Dedup is keyed on `removed-nav:<id>@<source>`. Two loads of the same
      // file produce the same key, so the warning fires at most once.
      val log = capturedText()
      val warningCount = log.split("removed navigation field(s)").size - 1
      assertEquals(
        1,
        warningCount,
        "Expected the deprecation warning to fire exactly once across two loads, " +
          "got $warningCount.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `loader does not warn when routes appears only as a nested key not at column 0`() {
    // Same column-0 concern as the legacy-compose-field test above. A nested key
    // named `routes` (e.g. inside a hypothetical platform-specific block) should
    // not trip the top-level deprecation warning.
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: nested-routes-only
          target:
            display_name: Nested Routes Pack
            platforms:
              android:
                # 'routes' here is hypothetical nested data, NOT the removed top-level field.
                routes:
                  - hypothetical-future-platform-field
          """.trimIndent(),
        )
      }

      TrailblazePackManifestLoader.load(packFile)

      val log = capturedText()
      assertFalse(
        log.contains("removed navigation field(s)"),
        "Expected NO deprecation warning for a nested `routes:` key, but got one.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `loader fires both legacy-compose and removed-nav warnings independently`() {
    // Pin: a manifest that triggers both warning categories surfaces both messages —
    // the dedup keys (`removed-compose:` vs `removed-nav:`) must not collide and one
    // category's warning must not suppress the other's.
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply {
        writeText(
          """
          id: legacy-everything
          target:
            display_name: Legacy Everything Pack
          use:
            - some-other-pack
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      TrailblazePackManifestLoader.load(packFile)

      val log = capturedText()
      assertTrue(
        log.contains("legacy composition field(s)") && log.contains("use"),
        "Expected legacy-compose warning naming `use`; got: $log",
      )
      assertTrue(
        log.contains("removed navigation field(s)") && log.contains("routes"),
        "Expected removed-nav warning naming `routes`; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `bundled trailblaze framework pack publishes per-platform defaults`() {
    val packUrl = checkNotNull(
      javaClass.classLoader.getResource("trailblaze-config/packs/trailblaze/pack.yaml"),
    )
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val packFile = File(tempDir, "pack.yaml").apply { writeText(packUrl.readText()) }
      val manifest = TrailblazePackManifestLoader.load(packFile).manifest

      assertEquals("trailblaze", manifest.id)
      // Framework pack contributes defaults only — no own runnable target.
      assertEquals(null, manifest.target)
      val defaults = assertNotNull(manifest.defaults)
      assertEquals(setOf("android", "ios", "web", "compose"), defaults.keys)
      assertTrue(defaults.getValue("android").toolSets!!.contains("memory"))
      assertTrue(defaults.getValue("web").toolSets!!.contains("web_core"))
      assertTrue(defaults.getValue("compose").toolSets!!.contains("compose_core"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `load all resilient returns sibling failures without aborting successes`() {
    val tempDir = createTempDirectory("pack-loader-test").toFile()
    try {
      val goodDir = File(tempDir, "good").apply { mkdirs() }
      File(goodDir, "pack.yaml").writeText(
        """
        id: good
        target:
          display_name: Good Pack
        """.trimIndent(),
      )
      val badDir = File(tempDir, "bad").apply { mkdirs() }
      File(badDir, "pack.yaml").writeText("id: bad\ntarget:\n  display_name")

      val result = TrailblazePackManifestLoader.loadAllResilient(
        packRefs = listOf("good/pack.yaml", "bad/pack.yaml"),
        anchor = tempDir,
      )

      assertEquals(listOf("good"), result.definitions.map { it.manifest.manifest.id })
      assertEquals(listOf("good/pack.yaml"), result.definitions.map { it.requestedRef })
      assertEquals(1, result.failures.size)
      assertTrue(result.failures.single().requestedPath.endsWith("bad/pack.yaml"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Classpath pack discovery — only direct `<id>/pack.yaml` entries are accepted.
  // Deeper nesting and flat top-level files must be silently ignored, mirroring
  // the convention spelled out in `TrailblazePackManifestLoader.discoverAndLoadFromClasspath`.
  // ==========================================================================

  @Test
  fun `discoverAndLoadFromClasspath accepts direct id-slash-pack-dot-yaml entries`() {
    val root = newTempDir()
    val packsDir = File(root, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha Pack
      """.trimIndent(),
    )
    File(packsDir, "beta").mkdirs()
    File(packsDir, "beta/pack.yaml").writeText(
      """
      id: beta
      target:
        display_name: Beta Pack
      """.trimIndent(),
    )

    val discovered = withClasspathRoot(root) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    }

    val ids = discovered.map { it.manifest.id }.toSet()
    assertEquals(setOf("alpha", "beta"), ids)
  }

  @Test
  fun `discoverAndLoadFromClasspath ignores deeper nested pack-dot-yaml entries`() {
    val root = newTempDir()
    val packsDir = File(root, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha Pack
      """.trimIndent(),
    )
    // A pack.yaml under a sub-directory of an existing pack — must NOT be auto-discovered
    // as a separate pack. The convention is that pack manifests live exactly one level
    // deep under trailblaze-config/packs/.
    val nestedDir = File(packsDir, "alpha/sub").apply { mkdirs() }
    File(nestedDir, "pack.yaml").writeText(
      """
      id: nested
      target:
        display_name: Nested (should not be discovered)
      """.trimIndent(),
    )

    val discovered = withClasspathRoot(root) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    }

    val ids = discovered.map { it.manifest.id }
    assertEquals(listOf("alpha"), ids)
  }

  @Test
  fun `discoverAndLoadFromClasspath ignores flat pack files at top level`() {
    val root = newTempDir()
    val packsDir = File(root, "trailblaze-config/packs").apply { mkdirs() }
    // Top-level YAML directly under packs/ — does NOT match the `<id>/pack.yaml` shape
    // and must be silently ignored. The recursive helper's `/pack.yaml` suffix matcher
    // already excludes this; keeping a test ensures the convention is enforced.
    File(packsDir, "stray.yaml").writeText("id: stray")
    File(packsDir, "pack.yaml").writeText("id: top-level-not-allowed")

    val discovered = withClasspathRoot(root) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    }

    assertTrue(
      discovered.isEmpty(),
      "Expected no packs from a packs/ dir containing only flat top-level files; got: " +
        discovered.map { it.manifest.id },
    )
  }

  @Test
  fun `discoverAndLoadFromClasspath returns empty when packs dir is missing`() {
    val root = newTempDir()
    // No `trailblaze-config/packs/` at all under the classpath root.
    val discovered = withClasspathRoot(root) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    }
    assertTrue(discovered.isEmpty())
  }

  // ==========================================================================
  // Classpath pack cache — `discoverAndLoadFromClasspath` must reuse results across
  // calls under the same classloader, miss after a classloader swap, and clear on
  // request via the test API. Without these guarantees a typical workspace boot
  // re-walks the classpath on every target/tool resolution.
  // ==========================================================================

  @Test
  fun `discoverAndLoadFromClasspath returns the same list on a second call under the same classloader`() {
    TrailblazePackManifestLoader.clearClasspathPackCacheForTesting()
    val root = newTempDir()
    val packsDir = File(root, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "cached").mkdirs()
    File(packsDir, "cached/pack.yaml").writeText(
      """
      id: cached
      target:
        display_name: Cached Pack
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val first = TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
      val second = TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
      // Reference equality on the returned list means the cache returned the cached object,
      // not just an equivalent list — proves the second call didn't re-walk the classpath.
      assertSame(first, second)
    }
  }

  @Test
  fun `discoverAndLoadFromClasspath misses the cache on a different classloader and rediscovers`() {
    TrailblazePackManifestLoader.clearClasspathPackCacheForTesting()
    val rootA = newTempDir()
    File(rootA, "trailblaze-config/packs/onlyA").mkdirs()
    File(rootA, "trailblaze-config/packs/onlyA/pack.yaml").writeText(
      """
      id: onlyA
      target:
        display_name: Pack only on A
      """.trimIndent(),
    )
    val rootB = newTempDir()
    File(rootB, "trailblaze-config/packs/onlyB").mkdirs()
    File(rootB, "trailblaze-config/packs/onlyB/pack.yaml").writeText(
      """
      id: onlyB
      target:
        display_name: Pack only on B
      """.trimIndent(),
    )

    val idsFromA = withClasspathRoot(rootA) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath().map { it.manifest.id }.toSet()
    }
    val idsFromB = withClasspathRoot(rootB) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath().map { it.manifest.id }.toSet()
    }
    assertEquals(setOf("onlyA"), idsFromA)
    assertEquals(setOf("onlyB"), idsFromB)
  }

  @Test
  fun `clearClasspathPackCacheForTesting forces rediscovery on the next call`() {
    val root = newTempDir()
    val packsDir = File(root, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    val packFile = File(packsDir, "alpha/pack.yaml")
    packFile.writeText(
      """
      id: alpha
      target:
        display_name: Original
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val first = TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
      assertEquals("Original", first.single().manifest.target?.displayName)

      // Mutate the on-disk pack and clear the cache. Without the clear, the loader would
      // keep returning the original cached value because `discoverAndLoadFromClasspath` is
      // cache-first under the same classloader.
      packFile.writeText(
        """
        id: alpha
        target:
          display_name: Updated
        """.trimIndent(),
      )
      TrailblazePackManifestLoader.clearClasspathPackCacheForTesting()
      val second = TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
      assertEquals("Updated", second.single().manifest.target?.displayName)
    }
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun newTempDir(): File = classpath.newTempDir(prefix = "pack-manifest-loader-test")

  private fun <T> withClasspathRoot(root: File, block: () -> T): T =
    classpath.withClasspathRoot(root, block)
}

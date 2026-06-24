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
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.testing.ClasspathFixture
import xyz.block.trailblaze.util.Console

class TrailblazeTrailmapManifestLoaderTest {

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
    TrailblazeTrailmapManifestLoader.clearClasspathTrailmapCacheForTesting()
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
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
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

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest

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
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy
          target:
            display_name: Legacy Trailmap
          use:
            - some-other-trailmap
          """.trimIndent(),
        )
      }

      // The manifest must still load — the warning is a nudge, not a hard error.
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
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
  fun `legacy-field warning dedups so it does not re-fire on a second load of the same trailmap`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy
          target:
            display_name: Legacy Trailmap
          extend:
            - some-other-trailmap
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)
      TrailblazeTrailmapManifestLoader.load(trailmapFile)

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
    // YAML key like `target.platforms.android.use:`. Top-level keys in trailmap.yaml
    // files are always at column 0, so the strict prefix check is sufficient.
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: nested-only
          target:
            display_name: Nested-Only Trailmap
            platforms:
              android:
                # 'use' here is a nested platform key, NOT the legacy top-level field.
                use:
                  - hypothetical-future-platform-field
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)

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
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy-nav
          target:
            display_name: Legacy Nav Trailmap
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      // The manifest must still load — the warning is a nudge, not a hard error.
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
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
  fun `removed-routes warning dedups across two loads of the same trailmap`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy-nav-dedup
          target:
            display_name: Legacy Nav Trailmap
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)
      TrailblazeTrailmapManifestLoader.load(trailmapFile)

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
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: nested-routes-only
          target:
            display_name: Nested Routes Trailmap
            platforms:
              android:
                # 'routes' here is hypothetical nested data, NOT the removed top-level field.
                routes:
                  - hypothetical-future-platform-field
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      val log = capturedText()
      assertFalse(
        log.contains("removed navigation field(s)"),
        "Expected NO deprecation warning for a nested `routes:` key, but got one.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Deprecation warning for the removed `mcp_servers:` field. Removed when the
  // on-device MCP bundle path was retired in favour of the in-process QuickJS
  // runtime and the host-subprocess `tools:` path. Same string-scan + dedup
  // pattern as the routes/use warnings above, with one extra wrinkle: the
  // field used to live nested under `target:` (two-space indent) in trailmap
  // manifests, so the scan tolerates leading whitespace.
  // ==========================================================================

  @Test
  fun `loader warns when manifest declares the removed mcp_servers field under target`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy-mcp
          target:
            display_name: Legacy MCP Trailmap
            mcp_servers:
              - script: ./tools/mcp/server.ts
          """.trimIndent(),
        )
      }

      // The manifest must still load — the warning is a nudge, not a hard error.
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("legacy-mcp", manifest.id)

      val log = capturedText()
      assertTrue(
        log.contains("removed field `mcp_servers:`") && log.contains("legacy-mcp"),
        "Expected deprecation warning naming `mcp_servers:` and the trailmap id; got: $log",
      )
      assertTrue(
        log.contains("runtime: subprocess"),
        "Expected migration nudge to mention `runtime: subprocess`; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `removed-mcp-servers warning dedups across two loads of the same trailmap`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy-mcp-dedup
          target:
            display_name: Legacy MCP Trailmap
            mcp_servers:
              - script: ./tools/mcp/server.ts
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)
      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      val log = capturedText()
      val warningCount = log.split("removed field `mcp_servers:`").size - 1
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
  fun `loader fires both legacy-compose and removed-nav warnings independently`() {
    // Pin: a manifest that triggers both warning categories surfaces both messages —
    // the dedup keys (`removed-compose:` vs `removed-nav:`) must not collide and one
    // category's warning must not suppress the other's.
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: legacy-everything
          target:
            display_name: Legacy Everything Trailmap
          use:
            - some-other-trailmap
          routes:
            - some/route.yaml
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)

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
  fun `bundled trailblaze framework trailmap publishes per-platform defaults`() {
    val trailmapUrl = checkNotNull(
      javaClass.classLoader.getResource("trails/config/trailmaps/trailblaze/trailmap.yaml"),
    )
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply { writeText(trailmapUrl.readText()) }
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest

      assertEquals("trailblaze", manifest.id)
      // Framework trailmap contributes defaults only — no own runnable target.
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
  fun `bundled mobile library trailmap loads and owns the four mobile primitive tools`() {
    // Pins the `mobile` library trailmap (PR #3435) as a target-less manifest that owns the
    // cross-platform `mobile_*` primitives via the `mobile_primitives` toolset. The trailmap
    // itself is purely declarative — the toolset lives in the global toolsets pool — but the
    // manifest must load cleanly under the library-trailmap contract (no target, no
    // waypoints, no trailheads) so the prefix-equals-trailmap-id rule holds for these tools.
    val trailmapUrl = checkNotNull(
      javaClass.classLoader.getResource("trails/config/trailmaps/mobile/trailmap.yaml"),
    )
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply { writeText(trailmapUrl.readText()) }
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest

      assertEquals("mobile", manifest.id)
      // Library trailmap — no own runnable target.
      assertEquals(null, manifest.target)
      // No waypoints / trailheads — enforced by enforceLibraryTrailmapContract; load would have
      // thrown above if either had been declared.
      assertTrue(manifest.waypoints.isEmpty())

      // Runtime-registry wiring: the top-level `platforms.{android,ios}.tool_sets:` declares
      // that consumers depending on `mobile` will get `mobile_primitives` registered for
      // callback dispatch on those platforms. Without this assertion, a future schema edit
      // that drops the platforms block would silently break the ownership contract the
      // manifest's docstring promises. Web/compose deliberately omitted — these are mobile
      // primitives.
      val platforms = checkNotNull(manifest.platforms) {
        "mobile library trailmap must declare top-level `platforms:` to wire its toolset into " +
          "the runtime registry for consumers that depend on it"
      }
      assertEquals(setOf("android", "ios"), platforms.keys)
      assertEquals(listOf("mobile_primitives"), platforms["android"]?.toolSets)
      assertEquals(listOf("mobile_primitives"), platforms["ios"]?.toolSets)

      // The companion toolset must also be on the classpath so consumers can reference it once
      // future PRs migrate them. Decoded here rather than asserted-by-string so any schema
      // drift (e.g. a renamed field) breaks the test loudly instead of silently passing.
      val toolsetUrl = checkNotNull(
        javaClass.classLoader.getResource("trails/config/trailmaps/mobile/toolsets/mobile_primitives.yaml"),
      )
      val toolset = TrailblazeConfigYaml.instance.decodeFromString(
        ToolSetYamlConfig.serializer(),
        toolsetUrl.readText(),
      )
      assertEquals("mobile_primitives", toolset.id)
      assertEquals(
        listOf("mobile_clearAppData", "mobile_listInstalledApps", "mobile_pasteClipboard", "mobile_setClipboard"),
        toolset.tools,
      )
      // Always-enabled so callback dispatch can resolve these by name even though they're
      // surfaceToLlm = false (the LLM never calls setActiveToolSets for them).
      assertTrue(toolset.alwaysEnabled)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `bundled android library trailmap loads and owns the android primitive tools`() {
    // Pins the `android` library trailmap (parallel to the `mobile` trailmap above, see
    // PR #3451) as a target-less manifest that owns the Android framework `android_*`
    // primitives via the `android_primitives` toolset. Same shape as the mobile case:
    // the trailmap itself is purely declarative — the toolset lives in the global
    // toolsets pool — but the manifest must load cleanly under the library-trailmap
    // contract (no target, no waypoints, no trailheads) so the prefix-equals-trailmap-id
    // rule holds for these tools.
    val trailmapUrl = checkNotNull(
      javaClass.classLoader.getResource("trails/config/trailmaps/android/trailmap.yaml"),
    )
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply { writeText(trailmapUrl.readText()) }
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest

      assertEquals("android", manifest.id)
      // Library trailmap — no own runnable target.
      assertEquals(null, manifest.target)
      // No waypoints / trailheads — enforced by enforceLibraryTrailmapContract; load would have
      // thrown above if either had been declared.
      assertTrue(manifest.waypoints.isEmpty())

      // Runtime-registry wiring: the top-level `platforms.android.tool_sets:` declares that
      // consumers depending on `android` will get `android_primitives` registered for callback
      // dispatch on Android. Without this assertion, a future schema edit that drops the
      // platforms block would silently break the ownership contract the manifest's docstring
      // promises. iOS / web / compose deliberately omitted — these are Android-only primitives;
      // the resolver test pins that no phantom platform keys are synthesized.
      val platforms = checkNotNull(manifest.platforms) {
        "android library trailmap must declare top-level `platforms:` to wire its toolset into " +
          "the runtime registry for consumers that depend on it"
      }
      assertEquals(setOf("android"), platforms.keys)
      assertEquals(listOf("android_primitives"), platforms["android"]?.toolSets)
      assertFalse("ios" in platforms, "android library trailmap must not declare an `ios` platform entry")

      // The companion toolset must also be on the classpath so consumers can reference it once
      // future PRs migrate them. Decoded here rather than asserted-by-string so any schema
      // drift (e.g. a renamed field) breaks the test loudly instead of silently passing.
      val toolsetUrl = checkNotNull(
        javaClass.classLoader.getResource("trails/config/trailmaps/android/toolsets/android_primitives.yaml"),
      )
      val toolset = TrailblazeConfigYaml.instance.decodeFromString(
        ToolSetYamlConfig.serializer(),
        toolsetUrl.readText(),
      )
      assertEquals("android_primitives", toolset.id)
      assertEquals(
        listOf(
          "android_adbShell",
          "android_grantAppOpsPermission",
          "android_grantPermission",
          "android_sendBroadcast",
          "android_systemUiDemoMode",
          "android_writeBytesToFile",
          "android_writeFileToDownloads",
        ),
        toolset.tools,
      )
      // Always-enabled so callback dispatch can resolve these by name even though they're
      // surfaceToLlm = false (the LLM never calls setActiveToolSets for them).
      assertTrue(toolset.alwaysEnabled)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `load all resilient returns sibling failures without aborting successes`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val goodDir = File(tempDir, "good").apply { mkdirs() }
      File(goodDir, "trailmap.yaml").writeText(
        """
        id: good
        target:
          display_name: Good Trailmap
        """.trimIndent(),
      )
      val badDir = File(tempDir, "bad").apply { mkdirs() }
      File(badDir, "trailmap.yaml").writeText("id: bad\ntarget:\n  display_name")

      val result = TrailblazeTrailmapManifestLoader.loadAllResilient(
        trailmapRefs = listOf("good/trailmap.yaml", "bad/trailmap.yaml"),
        anchor = tempDir,
      )

      assertEquals(listOf("good"), result.definitions.map { it.manifest.manifest.id })
      assertEquals(listOf("good/trailmap.yaml"), result.definitions.map { it.requestedRef })
      assertEquals(1, result.failures.size)
      assertTrue(result.failures.single().requestedPath.endsWith("bad/trailmap.yaml"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Classpath trailmap discovery — only direct `<id>/trailmap.yaml` entries are accepted.
  // Deeper nesting and flat top-level files must be silently ignored, mirroring
  // the convention spelled out in `TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath`.
  // ==========================================================================

  @Test
  fun `discoverAndLoadFromClasspath accepts direct id-slash-trailmap-dot-yaml entries`() {
    val root = newTempDir()
    val trailmapsDir = File(root, "trails/config/trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha Trailmap
      """.trimIndent(),
    )
    File(trailmapsDir, "beta").mkdirs()
    File(trailmapsDir, "beta/trailmap.yaml").writeText(
      """
      id: beta
      target:
        display_name: Beta Trailmap
      """.trimIndent(),
    )

    val discovered = withClasspathRoot(root) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
    }

    val ids = discovered.map { it.manifest.id }.toSet()
    assertEquals(setOf("alpha", "beta"), ids)
  }

  @Test
  fun `discoverAndLoadFromClasspath ignores deeper nested trailmap-dot-yaml entries`() {
    val root = newTempDir()
    val trailmapsDir = File(root, "trails/config/trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha Trailmap
      """.trimIndent(),
    )
    // A trailmap.yaml under a sub-directory of an existing trailmap — must NOT be auto-discovered
    // as a separate trailmap. The convention is that trailmap manifests live exactly one level
    // deep under trails/config/trailmaps/.
    val nestedDir = File(trailmapsDir, "alpha/sub").apply { mkdirs() }
    File(nestedDir, "trailmap.yaml").writeText(
      """
      id: nested
      target:
        display_name: Nested (should not be discovered)
      """.trimIndent(),
    )

    val discovered = withClasspathRoot(root) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
    }

    val ids = discovered.map { it.manifest.id }
    assertEquals(listOf("alpha"), ids)
  }

  @Test
  fun `discoverAndLoadFromClasspath ignores flat trailmap files at top level`() {
    val root = newTempDir()
    val trailmapsDir = File(root, "trails/config/trailmaps").apply { mkdirs() }
    // Top-level YAML directly under trailmaps/ — does NOT match the `<id>/trailmap.yaml` shape
    // and must be silently ignored. The recursive helper's `/trailmap.yaml` suffix matcher
    // already excludes this; keeping a test ensures the convention is enforced.
    File(trailmapsDir, "stray.yaml").writeText("id: stray")
    File(trailmapsDir, "trailmap.yaml").writeText("id: top-level-not-allowed")

    val discovered = withClasspathRoot(root) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
    }

    assertTrue(
      discovered.isEmpty(),
      "Expected no trailmaps from a trailmaps/ dir containing only flat top-level files; got: " +
        discovered.map { it.manifest.id },
    )
  }

  @Test
  fun `discoverAndLoadFromClasspath returns empty when trailmaps dir is missing`() {
    val root = newTempDir()
    // No `trails/config/trailmaps/` at all under the classpath root.
    val discovered = withClasspathRoot(root) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
    }
    assertTrue(discovered.isEmpty())
  }

  // ==========================================================================
  // Classpath trailmap cache — `discoverAndLoadFromClasspath` must reuse results across
  // calls under the same classloader, miss after a classloader swap, and clear on
  // request via the test API. Without these guarantees a typical workspace boot
  // re-walks the classpath on every target/tool resolution.
  // ==========================================================================

  @Test
  fun `discoverAndLoadFromClasspath returns the same list on a second call under the same classloader`() {
    TrailblazeTrailmapManifestLoader.clearClasspathTrailmapCacheForTesting()
    val root = newTempDir()
    val trailmapsDir = File(root, "trails/config/trailmaps").apply { mkdirs() }
    File(trailmapsDir, "cached").mkdirs()
    File(trailmapsDir, "cached/trailmap.yaml").writeText(
      """
      id: cached
      target:
        display_name: Cached Trailmap
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val first = TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
      val second = TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
      // Reference equality on the returned list means the cache returned the cached object,
      // not just an equivalent list — proves the second call didn't re-walk the classpath.
      assertSame(first, second)
    }
  }

  @Test
  fun `discoverAndLoadFromClasspath misses the cache on a different classloader and rediscovers`() {
    TrailblazeTrailmapManifestLoader.clearClasspathTrailmapCacheForTesting()
    val rootA = newTempDir()
    File(rootA, "trails/config/trailmaps/onlyA").mkdirs()
    File(rootA, "trails/config/trailmaps/onlyA/trailmap.yaml").writeText(
      """
      id: onlyA
      target:
        display_name: Trailmap only on A
      """.trimIndent(),
    )
    val rootB = newTempDir()
    File(rootB, "trails/config/trailmaps/onlyB").mkdirs()
    File(rootB, "trails/config/trailmaps/onlyB/trailmap.yaml").writeText(
      """
      id: onlyB
      target:
        display_name: Trailmap only on B
      """.trimIndent(),
    )

    val idsFromA = withClasspathRoot(rootA) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath().map { it.manifest.id }.toSet()
    }
    val idsFromB = withClasspathRoot(rootB) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath().map { it.manifest.id }.toSet()
    }
    assertEquals(setOf("onlyA"), idsFromA)
    assertEquals(setOf("onlyB"), idsFromB)
  }

  @Test
  fun `clearClasspathTrailmapCacheForTesting forces rediscovery on the next call`() {
    val root = newTempDir()
    val trailmapsDir = File(root, "trails/config/trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    val trailmapFile = File(trailmapsDir, "alpha/trailmap.yaml")
    trailmapFile.writeText(
      """
      id: alpha
      target:
        display_name: Original
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val first = TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
      assertEquals("Original", first.single().manifest.target?.displayName)

      // Mutate the on-disk trailmap and clear the cache. Without the clear, the loader would
      // keep returning the original cached value because `discoverAndLoadFromClasspath` is
      // cache-first under the same classloader.
      trailmapFile.writeText(
        """
        id: alpha
        target:
          display_name: Updated
        """.trimIndent(),
      )
      TrailblazeTrailmapManifestLoader.clearClasspathTrailmapCacheForTesting()
      val second = TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
      assertEquals("Updated", second.single().manifest.target?.displayName)
    }
  }

  // ==========================================================================
  // Library-trailmap contract: a manifest with no `target:` block cannot declare
  // `waypoints:`. Catches the contract violation at parse time so the failure
  // names the offending trailmap and field instead of surfacing later as
  // "no target found for waypoint X" during reverse-lookup.
  // ==========================================================================

  @Test
  fun `library trailmap with waypoints fails to load with clear message`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: bad-library
          waypoints:
            - waypoints/home.waypoint.yaml
          """.trimIndent(),
        )
      }

      val error = runCatching { TrailblazeTrailmapManifestLoader.load(trailmapFile) }.exceptionOrNull()
      val typed = assertNotNull(error as? TrailblazeProjectConfigException)
      val message = typed.message.orEmpty()
      assertTrue(
        "Library trailmaps (no target)" in message && "cannot own waypoints" in message,
        "Expected library-trailmap waypoint guard message; got: $message",
      )
      assertTrue(
        "waypoints/home.waypoint.yaml" in message,
        "Expected offending entry named in message; got: $message",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `library trailmap without waypoints loads cleanly`() {
    // The canonical happy path: a tools-only library trailmap with no target. Pin so the
    // new validation rule never starts rejecting this shape.
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: my-library
          tools:
            - tools/foo.tool.yaml
          """.trimIndent(),
        )
      }

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("my-library", manifest.id)
      assertEquals(null, manifest.target)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Top-level `platforms:` + `exports:` — the runtime-registry / typed-surface
  // schema additions. Library trailmaps may declare both; target trailmaps may declare
  // `exports:` but MUST NOT declare top-level `platforms:` (that belongs under
  // `target.platforms:`).
  // ==========================================================================

  @Test
  fun `library trailmap with top-level platforms and exports parses cleanly`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: entity_factory
          platforms:
            web:
              tool_sets: [web_core]
          exports:
            - createEntity
            - configureEntitySettings
          """.trimIndent(),
        )
      }

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("entity_factory", manifest.id)
      val webPlatform = assertNotNull(manifest.platforms?.get("web"))
      assertEquals(listOf("web_core"), webPlatform.toolSets)
      assertEquals(listOf("createEntity", "configureEntitySettings"), manifest.exports)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `target trailmap rejects top-level platforms with named offending keys`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: misplaced-platforms
          target:
            display_name: Misplaced Platforms Target
          platforms:
            android:
              tool_sets: [core_interaction]
            web:
              tool_sets: [web_core]
          """.trimIndent(),
        )
      }

      val error = runCatching { TrailblazeTrailmapManifestLoader.load(trailmapFile) }.exceptionOrNull()
      val typed = assertNotNull(error as? TrailblazeProjectConfigException)
      val message = typed.message.orEmpty()
      assertTrue(
        "top-level platforms:" in message && "target.platforms:" in message,
        "Expected migration hint pointing at target.platforms:; got: $message",
      )
      assertTrue(
        "android" in message && "web" in message,
        "Expected offending platform keys named in message; got: $message",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `target trailmap with exports but no top-level platforms loads cleanly`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: target-with-exports
          target:
            display_name: Target With Exports
            platforms:
              android:
                tool_sets: [core_interaction]
          exports:
            - publicTool
          """.trimIndent(),
        )
      }

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals(listOf("publicTool"), manifest.exports)
      assertEquals(null, manifest.platforms)
      assertEquals(
        listOf("core_interaction"),
        manifest.target?.platforms?.get("android")?.toolSets,
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `target trailmap with waypoints loads cleanly`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: my-target
          target:
            display_name: My Target
          waypoints:
            - waypoints/home.waypoint.yaml
          """.trimIndent(),
        )
      }

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("my-target", manifest.id)
      assertEquals(listOf("waypoints/home.waypoint.yaml"), manifest.waypoints)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Advisory trailmap-scoping check (2026-05-27 devlog).
  // Verifies the load-time warning fires when a non-framework trailmap's id or
  // target.tools: names don't follow the `<trailmapId>_<localName>` convention,
  // stays silent on compliant trailmaps, skips the framework `trailblaze`
  // trailmap entirely, and dedup-keys per (id, source) so repeated loads of the
  // same manifest don't double-fire. ADVISORY ONLY in this chip — the manifest
  // must still load cleanly even when violations are reported.
  // ==========================================================================

  @Test
  fun `compliant trailmap emits no scoping warning`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: wikipedia
          target:
            display_name: Wikipedia
            tools:
              - wikipedia_android_launchApp
              - wikipedia_web_openMainPage
          """.trimIndent(),
        )
      }

      // Manifest loads regardless — the check is silent on compliant input.
      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      val log = capturedText()
      assertFalse(
        log.contains("[TrailmapScopingCheck]"),
        "Expected no scoping warning for a compliant trailmap, got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `framework trailblaze trailmap is exempt from the scoping check`() {
    // The framework trailmap's primitives (`tap`, `tapOnElementBySelector`,
    // `web_evaluate`, `mobile_*`, etc.) intentionally do NOT carry the
    // `trailblaze_` prefix — they're the implicit unscoped namespace. The
    // check must skip this trailmap entirely so authors don't see noise about
    // names that are correct by exemption.
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: trailblaze
          target:
            display_name: Trailblaze Framework
            tools:
              - tap
              - tapOnElementBySelector
              - web_evaluate
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      val log = capturedText()
      assertFalse(
        log.contains("[TrailmapScopingCheck]"),
        "Framework trailmap must be exempt; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `tool name missing the trailmap-id prefix triggers an advisory warning`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: sampleapp
          target:
            display_name: Sample App
            tools:
              - host_writeArtifact
          """.trimIndent(),
        )
      }

      // Advisory — load must succeed and the parsed manifest must be intact.
      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("sampleapp", manifest.id)
      assertEquals(listOf("host_writeArtifact"), manifest.target?.tools)

      val log = capturedText()
      assertTrue(
        log.contains("[TrailmapScopingCheck]"),
        "Expected scoping-check warning prefix; got: $log",
      )
      assertTrue(
        log.contains("'host_writeArtifact'") && log.contains("'sampleapp_'"),
        "Expected warning to name the offending tool and the expected prefix; got: $log",
      )
      assertTrue(
        log.contains("Advisory only"),
        "Expected the warning to state it is advisory and load proceeds; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `trailmap id with a dash triggers an advisory warning`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: my-trailmap
          target:
            display_name: Dashed-id Trailmap
          """.trimIndent(),
        )
      }

      val manifest = TrailblazeTrailmapManifestLoader.load(trailmapFile).manifest
      assertEquals("my-trailmap", manifest.id)

      val log = capturedText()
      assertTrue(
        log.contains("[TrailmapScopingCheck]"),
        "Expected scoping-check warning for a dashed trailmap id; got: $log",
      )
      assertTrue(
        log.contains("trailmap id 'my-trailmap'"),
        "Expected warning to name the offending id; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `trailmap id with an underscore triggers an advisory warning`() {
    // Underscores in trailmap ids reintroduce the wire-name parsing ambiguity
    // the convention exists to remove — see TRAILMAP_ID_REGEX kdoc on the
    // loader. Independently testable from the dash case to keep the failure
    // signal sharp if someone relaxes one branch of the regex.
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: my_trailmap
          target:
            display_name: Snake-case-id Trailmap
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      val log = capturedText()
      assertTrue(
        log.contains("[TrailmapScopingCheck]") && log.contains("trailmap id 'my_trailmap'"),
        "Expected scoping-check warning naming the underscored id; got: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `scoping warning dedups across two loads of the same trailmap`() {
    val tempDir = createTempDirectory("trailmap-loader-test").toFile()
    try {
      val trailmapFile = File(tempDir, "trailmap.yaml").apply {
        writeText(
          """
          id: sampleapp
          target:
            display_name: Sample App
            tools:
              - host_writeArtifact
          """.trimIndent(),
        )
      }

      TrailblazeTrailmapManifestLoader.load(trailmapFile)
      TrailblazeTrailmapManifestLoader.load(trailmapFile)

      // Dedup is keyed on `trailmap-scoping:<id>@<source>` — two loads of the
      // same file produce the same key, so the warning fires at most once.
      val log = capturedText()
      val warningCount = log.split("[TrailmapScopingCheck]").size - 1
      assertEquals(
        1,
        warningCount,
        "Expected the scoping warning to fire exactly once across two loads, got " +
          "$warningCount.\nLog: $log",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun newTempDir(): File = classpath.newTempDir(prefix = "trailmap-manifest-loader-test")

  private fun <T> withClasspathRoot(root: File, block: () -> T): T =
    classpath.withClasspathRoot(root, block)
}

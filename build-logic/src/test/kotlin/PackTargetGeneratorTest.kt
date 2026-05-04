import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [PackTargetGenerator] — the core renderer used by the
 * `trailblaze.bundled-config` plugin to derive checked-in flat target YAMLs from
 * authored pack manifests.
 *
 * Covers:
 *  - Single-pack → single-target rendering with the GENERATED-FILE banner
 *  - Multi-pack rendering produces a deterministic file per pack
 *  - Duplicate target ids across packs fail loudly via `check()`
 *  - Stale generated files (no matching pack source) are detectable
 *  - Empty `packsDir` yields no output (no crash)
 *  - Pack manifests without a `target:` block are silently skipped
 *
 * The generator runs in-process against a temp-dir fixture, so each test exercises
 * the real YAML parsing and rendering logic without needing a running Gradle build.
 */
class PackTargetGeneratorTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `renders a single pack to a generated target file with banner`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids:
              - com.example.alpha
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "./gradlew :foo:generate",
    )
    val expected = generator.buildExpectedTargets()

    assertEquals(1, expected.size)
    val (file, content) = expected.entries.single()
    assertEquals(File(targetsDir, "alpha.yaml").absolutePath, file.absolutePath)
    assertTrue(content.startsWith("# GENERATED FILE. DO NOT EDIT."))
    assertTrue(content.contains("# Source: "))
    assertTrue(content.contains("# Regenerate with: ./gradlew :foo:generate"))
    assertTrue(content.contains("id: alpha"))
    assertTrue(content.contains("display_name: Alpha"))
    assertTrue(content.contains("- com.example.alpha"))
  }

  @Test
  fun `renders multiple packs in deterministic order`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    listOf("zeta", "alpha", "mid").forEach { id ->
      File(packsDir, id).mkdirs()
      File(packsDir, "$id/pack.yaml").writeText(
        """
        id: $id
        target:
          display_name: $id
        """.trimIndent(),
      )
    }

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    // discoverPackFiles sorts by relative path → alphabetical.
    val orderedIds = expected.keys.map { it.nameWithoutExtension }
    assertEquals(listOf("alpha", "mid", "zeta"), orderedIds)
  }

  @Test
  fun `duplicate target ids across packs fail loudly`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "first").mkdirs()
    File(packsDir, "first/pack.yaml").writeText(
      """
      id: first
      target:
        id: shared
        display_name: First
      """.trimIndent(),
    )
    File(packsDir, "second").mkdirs()
    File(packsDir, "second/pack.yaml").writeText(
      """
      id: second
      target:
        id: shared
        display_name: Second
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val ex = assertFailsWith<IllegalStateException> { generator.buildExpectedTargets() }
    assertTrue(ex.message!!.contains("Duplicate generated target id 'shared'"))
  }

  @Test
  fun `target id defaults to pack id when target_id is omitted`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    val (file, content) = expected.entries.single()
    assertEquals("myapp.yaml", file.name)
    assertTrue(content.contains("id: myapp"))
  }

  @Test
  fun `target id from manifest overrides pack id when target_id is set`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        id: customAppId
        display_name: My App
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    val (file, content) = expected.entries.single()
    assertEquals("customAppId.yaml", file.name)
    assertTrue(content.contains("id: customAppId"))
  }

  @Test
  fun `pack without a target block contributes no file`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "lib").mkdirs()
    File(packsDir, "lib/pack.yaml").writeText(
      """
      id: lib
      toolsets:
        - toolsets/some.yaml
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    assertTrue(
      expected.isEmpty(),
      "A pack without target: should produce no generated target file",
    )
  }

  @Test
  fun `empty packs dir yields no output`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    assertTrue(generator.buildExpectedTargets().isEmpty())
  }

  @Test
  fun `nonexistent packs dir yields no output without crashing`() {
    val packsDir = File(newTempDir(), "does-not-exist")
    val targetsDir = newTempDir()
    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    assertTrue(generator.buildExpectedTargets().isEmpty())
  }

  @Test
  fun `findManagedTargetFiles only matches files with the GENERATED banner`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    // A previously-generated file (banner present) — should be detected.
    File(targetsDir, "previously-generated.yaml").writeText(
      "# GENERATED FILE. DO NOT EDIT.\nid: previously-generated\n",
    )
    // A hand-authored file without the banner — must NOT be claimed by the generator.
    File(targetsDir, "hand-authored.yaml").writeText(
      "# This is a hand-authored target.\nid: hand-authored\n",
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val managed = generator.findManagedTargetFiles().map { it.name }
    assertEquals(listOf("previously-generated.yaml"), managed)
  }

  @Test
  fun `deleteStaleGeneratedTargets removes generated files no longer expected`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    val staleFile = File(targetsDir, "stale.yaml").apply {
      writeText("# GENERATED FILE. DO NOT EDIT.\nid: stale\n")
    }
    val keepFile = File(targetsDir, "keep.yaml").apply {
      writeText("# GENERATED FILE. DO NOT EDIT.\nid: keep\n")
    }

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    generator.deleteStaleGeneratedTargets(expectedFiles = setOf(keepFile))

    assertTrue(!staleFile.exists(), "stale.yaml should have been deleted")
    assertTrue(keepFile.exists(), "keep.yaml should NOT have been deleted")
  }

  @Test
  fun `numeric target id scalar is treated as a string id`() {
    // Under SnakeYAML, `target.id: 123` parsed as Int and the generator threw because
    // the value wasn't a String. kaml's tree API normalizes plain scalars to String —
    // `123` and `'123'` both surface identically. The runtime kotlinx-serialization
    // path also stringifies into the typed `target.id: String` field. Behavior is now
    // consistent: a numeric-looking id is just a string id with the natural form.
    // Non-scalar ids (map, list — see the next test) still fail loudly.
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        id: 123
        display_name: My App
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val expected = generator.buildExpectedTargets()
    val (file, content) = expected.entries.single()
    assertEquals("123.yaml", file.name)
    // Emitted with single-quoting because the formatter recognizes the numeric-looking
    // value and quotes to preserve string identity through any future re-parse.
    assertTrue("emitted: $content") { content.contains("id: '123'") }
  }

  @Test
  fun `non-string target id list type is also rejected`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "weird").mkdirs()
    File(packsDir, "weird/pack.yaml").writeText(
      """
      id: weird
      target:
        id: [a, b]
        display_name: Weird
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val ex = assertFailsWith<RuntimeException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message!!.contains("target.id must be a string"),
      "Expected target.id type-check error message, got: ${ex.message}",
    )
  }

  @Test
  fun `roundtrip generate-write-discover-clean`() {
    // End-to-end exercise so any drift between filename derivation in
    // buildExpectedTargets and findManagedTargetFiles surfaces here. Without this
    // integration test, the per-method tests pass while a regression that splits
    // filenames inconsistently between phases would slip through silently.
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    listOf("alpha", "beta").forEach { id ->
      File(packsDir, id).mkdirs()
      File(packsDir, "$id/pack.yaml").writeText(
        """
        id: $id
        target:
          display_name: $id
        """.trimIndent(),
      )
    }

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    // Phase 1: generate the expected output.
    val expected = generator.buildExpectedTargets()
    assertEquals(2, expected.size)

    // Phase 2: write each generated file to disk (mirrors what the Gradle task does
    // in production).
    expected.forEach { (file, content) ->
      file.parentFile.mkdirs()
      file.writeText(content)
    }

    // Phase 3: re-discover. The exact set of files written must match what the
    // discoverer claims we own — this is the property where filename-derivation
    // bugs would show up as set inequality.
    val managed = generator.findManagedTargetFiles().toSet()
    assertEquals(
      expected.keys.map { it.canonicalFile }.toSet(),
      managed.map { it.canonicalFile }.toSet(),
      "Discovered managed files must match exactly what generate produced",
    )

    // Phase 4: stale cleanup with an EMPTY expected set should remove every managed
    // file (because none of them are on the keep list).
    generator.deleteStaleGeneratedTargets(expectedFiles = emptySet())
    val survivors = generator.findManagedTargetFiles()
    assertTrue(
      survivors.isEmpty(),
      "deleteStaleGeneratedTargets(emptySet) should remove every managed file; survivors=$survivors",
    )
  }

  @Test
  fun `pack with dependencies inherits per-platform defaults from a library pack`() {
    // Build-logic mini-resolver mirrors PackDependencyResolver in trailblaze-common —
    // closest-wins per (platform, field), no list concatenation. This test guards the
    // mini-resolver's intended semantics; the actual byte-for-byte equivalence with the
    // canonical resolver is covered by the runtime test
    // `bundled clock and wikipedia packs resolve per-platform defaults...` in
    // TrailblazeProjectConfigLoaderTest, which loads the same bundled framework packs.
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "framework").mkdirs()
    File(packsDir, "framework/pack.yaml").writeText(
      """
      id: framework
      defaults:
        android:
          tool_sets:
            - core_interaction
            - memory
        web:
          drivers:
            - playwright-native
          tool_sets:
            - web_core
      """.trimIndent(),
    )
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      dependencies:
        - framework
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
          web:
            tool_sets:
              - app_specific_only
      """.trimIndent(),
    )

    val expected = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    ).buildExpectedTargets()

    // Library pack (`framework`) has no `target:` so produces no output file.
    assertEquals(setOf(File(targetsDir, "myapp.yaml")), expected.keys)
    val rendered = expected.values.single()

    // Android: consumer's app_ids preserved; tool_sets inherited from framework.
    assertTrue(rendered.contains("app_ids:"), "expected app_ids on android in: $rendered")
    assertTrue(rendered.contains("- com.example.myapp"), "missing consumer app_id in: $rendered")
    assertTrue(rendered.contains("- core_interaction"), "missing inherited android toolset in: $rendered")
    assertTrue(rendered.contains("- memory"), "missing inherited android toolset in: $rendered")

    // Web: consumer's explicit tool_sets wins entirely (no concat); drivers inherited.
    assertTrue(rendered.contains("- app_specific_only"), "missing consumer toolset in: $rendered")
    assertTrue(!rendered.contains("- web_core"), "consumer override should replace inherited list, got: $rendered")
    assertTrue(rendered.contains("- playwright-native"), "missing inherited driver in: $rendered")
  }

  @Test
  fun `dependencies cycle is rejected with a clear error`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "a").mkdirs()
    File(packsDir, "a/pack.yaml").writeText(
      """
      id: a
      dependencies: [b]
      target:
        display_name: A
        platforms:
          android: {}
      """.trimIndent(),
    )
    File(packsDir, "b").mkdirs()
    File(packsDir, "b/pack.yaml").writeText(
      """
      id: b
      dependencies: [a]
      """.trimIndent(),
    )

    val ex = assertFailsWith<RuntimeException> {
      PackTargetGenerator(
        packsDir = packsDir,
        targetsDir = targetsDir,
        regenerateCommand = "regen",
      ).buildExpectedTargets()
    }
    assertTrue(
      ex.message!!.contains("Cycle in pack dependencies"),
      "Expected cycle-detection error, got: ${ex.message}",
    )
  }

  @Test
  fun `missing dependency id is rejected with a clear error`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      dependencies: [does-not-exist]
      target:
        display_name: My App
        platforms:
          android: {}
      """.trimIndent(),
    )

    val ex = assertFailsWith<RuntimeException> {
      PackTargetGenerator(
        packsDir = packsDir,
        targetsDir = targetsDir,
        regenerateCommand = "regen",
      ).buildExpectedTargets()
    }
    assertTrue(
      ex.message!!.contains("does-not-exist") && ex.message!!.contains("not found"),
      "Expected missing-dep error, got: ${ex.message}",
    )
  }

  @Test
  fun `pack manifest without an id field is rejected`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "noid").mkdirs()
    File(packsDir, "noid/pack.yaml").writeText(
      """
      target:
        display_name: No ID
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val ex = assertFailsWith<RuntimeException> { generator.buildExpectedTargets() }
    assertTrue(ex.message!!.contains("missing required 'id'"))
  }

  /**
   * Single-test snapshot of the closest-wins inheritance contract this generator
   * shares with `:trailblaze-common`'s `PackDependencyResolver.resolveTarget`. Any
   * change to the expected outputs MUST be mirrored in
   * `trailblaze-common/src/jvmAndAndroidTest/kotlin/xyz/block/trailblaze/config/project/PackDependencyResolverTest.kt`'s
   * sibling test of the same name. Both implementations are independently
   * maintained but behaviorally identical; both tests pin the contract from their
   * respective side. If you find yourself updating one to make it pass, update the
   * other with the matching change before merging.
   */
  @Test
  fun `parity contract — closest-wins inheritance, no list concat, multi-field, multi-platform`() {
    val packsDir = newTempDir()
    val targetsDir = newTempDir()
    File(packsDir, "framework").mkdirs()
    File(packsDir, "framework/pack.yaml").writeText(
      """
      id: framework
      defaults:
        android:
          tool_sets: [framework_android_set]
          drivers: [android-ondevice-instrumentation]
          base_url: https://framework.example/android
          min_build_version: '10'
        web:
          tool_sets: [framework_web_set]
          drivers: [playwright-native, playwright-electron]
      """.trimIndent(),
    )
    File(packsDir, "consumer").mkdirs()
    File(packsDir, "consumer/pack.yaml").writeText(
      """
      id: consumer
      dependencies: [framework]
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example.consumer]
            tool_sets: [consumer_only]
            drivers: [android-ondevice-accessibility]
            min_build_version: '20'
          web: {}
      """.trimIndent(),
    )

    val generator = PackTargetGenerator(
      packsDir = packsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()
    val rendered = expected[File(targetsDir, "consumer.yaml")]
      ?: error("consumer.yaml not generated; got: ${expected.keys}")

    // Android: consumer-set fields win, baseUrl inherits.
    assertTrue(rendered.contains("- com.example.consumer"))
    assertTrue(rendered.contains("- consumer_only"), "consumer override wins")
    assertTrue(!rendered.contains("- framework_android_set"), "framework's tool_sets must NOT survive consumer override (no concat)")
    assertTrue(rendered.contains("- android-ondevice-accessibility"), "consumer driver wins")
    assertTrue(!rendered.contains("- android-ondevice-instrumentation"), "framework driver must NOT survive consumer override")
    assertTrue(rendered.contains("base_url: https://framework.example/android"), "baseUrl inherited")
    assertTrue(rendered.contains("min_build_version: '20'"), "consumer minBuildVersion wins")

    // Web: empty consumer map → every field inherits.
    assertTrue(rendered.contains("- framework_web_set"), "web tool_sets inherited from framework")
    assertTrue(rendered.contains("- playwright-native"), "web drivers inherited from framework")
    assertTrue(rendered.contains("- playwright-electron"), "all web drivers inherited from framework")
  }

  private fun newTempDir(): File =
    createTempDirectory("pack-target-generator-test").toFile().also { tempDirs += it }
}

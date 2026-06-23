import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

/**
 * Tests for [TrailmapTargetGenerator] — the core renderer used by the
 * `trailblaze.bundled-config` plugin to derive checked-in flat target YAMLs from
 * authored trailmap manifests.
 *
 * Covers:
 *  - Single-trailmap → single-target rendering with the GENERATED-FILE banner
 *  - Multi-trailmap rendering produces a deterministic file per trailmap
 *  - Duplicate target ids across trailmaps fail loudly via `check()`
 *  - Stale generated files (no matching trailmap source) are detectable
 *  - Empty `trailmapsDir` yields no output (no crash)
 *  - Trailmap manifests without a `target:` block are silently skipped
 *
 * The generator runs in-process against a temp-dir fixture, so each test exercises
 * the real YAML parsing and rendering logic without needing a running Gradle build.
 */
class TrailmapTargetGeneratorTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `renders a single trailmap to a generated target file with banner`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
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

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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
  fun `renders multiple trailmaps in deterministic order`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    listOf("zeta", "alpha", "mid").forEach { id ->
      File(trailmapsDir, id).mkdirs()
      File(trailmapsDir, "$id/trailmap.yaml").writeText(
        """
        id: $id
        target:
          display_name: $id
        """.trimIndent(),
      )
    }

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    // discoverTrailmapFiles sorts by relative path → alphabetical.
    val orderedIds = expected.keys.map { it.nameWithoutExtension }
    assertEquals(listOf("alpha", "mid", "zeta"), orderedIds)
  }

  @Test
  fun `duplicate target ids across trailmaps fail loudly`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "first").mkdirs()
    File(trailmapsDir, "first/trailmap.yaml").writeText(
      """
      id: first
      target:
        id: shared
        display_name: First
      """.trimIndent(),
    )
    File(trailmapsDir, "second").mkdirs()
    File(trailmapsDir, "second/trailmap.yaml").writeText(
      """
      id: second
      target:
        id: shared
        display_name: Second
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val ex = assertFailsWith<IllegalStateException> { generator.buildExpectedTargets() }
    assertTrue(ex.message!!.contains("Duplicate generated target id 'shared'"))
  }

  @Test
  fun `target id defaults to trailmap id when target_id is omitted`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    val (file, content) = expected.entries.single()
    assertEquals("myapp.yaml", file.name)
    assertTrue(content.contains("id: myapp"))
  }

  @Test
  fun `target id from manifest overrides trailmap id when target_id is set`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
      """
      id: myapp
      target:
        id: customAppId
        display_name: My App
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    val (file, content) = expected.entries.single()
    assertEquals("customAppId.yaml", file.name)
    assertTrue(content.contains("id: customAppId"))
  }

  @Test
  fun `trailmap without a target block contributes no file`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "lib").mkdirs()
    File(trailmapsDir, "lib/trailmap.yaml").writeText(
      """
      id: lib
      toolsets:
        - toolsets/some.yaml
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val expected = generator.buildExpectedTargets()

    assertTrue(
      expected.isEmpty(),
      "A trailmap without target: should produce no generated target file",
    )
  }

  @Test
  fun `empty trailmaps dir yields no output`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    assertTrue(generator.buildExpectedTargets().isEmpty())
  }

  @Test
  fun `nonexistent trailmaps dir yields no output without crashing`() {
    val trailmapsDir = File(newTempDir(), "does-not-exist")
    val targetsDir = newTempDir()
    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    assertTrue(generator.buildExpectedTargets().isEmpty())
  }

  @Test
  fun `findManagedTargetFiles only matches files with the GENERATED banner`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    // A previously-generated file (banner present) — should be detected.
    File(targetsDir, "previously-generated.yaml").writeText(
      "# GENERATED FILE. DO NOT EDIT.\nid: previously-generated\n",
    )
    // A hand-authored file without the banner — must NOT be claimed by the generator.
    File(targetsDir, "hand-authored.yaml").writeText(
      "# This is a hand-authored target.\nid: hand-authored\n",
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )
    val managed = generator.findManagedTargetFiles().map { it.name }
    assertEquals(listOf("previously-generated.yaml"), managed)
  }

  @Test
  fun `deleteStaleGeneratedTargets removes generated files no longer expected`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    val staleFile = File(targetsDir, "stale.yaml").apply {
      writeText("# GENERATED FILE. DO NOT EDIT.\nid: stale\n")
    }
    val keepFile = File(targetsDir, "keep.yaml").apply {
      writeText("# GENERATED FILE. DO NOT EDIT.\nid: keep\n")
    }

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
      """
      id: myapp
      target:
        id: 123
        display_name: My App
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "weird").mkdirs()
    File(trailmapsDir, "weird/trailmap.yaml").writeText(
      """
      id: weird
      target:
        id: [a, b]
        display_name: Weird
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    listOf("alpha", "beta").forEach { id ->
      File(trailmapsDir, id).mkdirs()
      File(trailmapsDir, "$id/trailmap.yaml").writeText(
        """
        id: $id
        target:
          display_name: $id
        """.trimIndent(),
      )
    }

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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
  fun `trailmap with dependencies inherits per-platform defaults from a library trailmap`() {
    // Build-logic mini-resolver mirrors TrailmapDependencyResolver in trailblaze-common —
    // closest-wins per (platform, field), no list concatenation. This test guards the
    // mini-resolver's intended semantics; the actual byte-for-byte equivalence with the
    // canonical resolver is covered by the runtime test
    // `bundled clock and wikipedia trailmaps resolve per-platform defaults...` in
    // TrailblazeProjectConfigLoaderTest, which loads the same bundled framework trailmaps.
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "framework").mkdirs()
    File(trailmapsDir, "framework/trailmap.yaml").writeText(
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
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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

    val expected = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    ).buildExpectedTargets()

    // Library trailmap (`framework`) has no `target:` so produces no output file.
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
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "a").mkdirs()
    File(trailmapsDir, "a/trailmap.yaml").writeText(
      """
      id: a
      dependencies: [b]
      target:
        display_name: A
        platforms:
          android: {}
      """.trimIndent(),
    )
    File(trailmapsDir, "b").mkdirs()
    File(trailmapsDir, "b/trailmap.yaml").writeText(
      """
      id: b
      dependencies: [a]
      """.trimIndent(),
    )

    val ex = assertFailsWith<RuntimeException> {
      TrailmapTargetGenerator(
        trailmapsDir = trailmapsDir,
        targetsDir = targetsDir,
        regenerateCommand = "regen",
      ).buildExpectedTargets()
    }
    assertTrue(
      ex.message!!.contains("Cycle in trailmap dependencies"),
      "Expected cycle-detection error, got: ${ex.message}",
    )
  }

  @Test
  fun `missing dependency id is rejected with a clear error`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      TrailmapTargetGenerator(
        trailmapsDir = trailmapsDir,
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
  fun `trailmap manifest without an id field is rejected`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "noid").mkdirs()
    File(trailmapsDir, "noid/trailmap.yaml").writeText(
      """
      target:
        display_name: No ID
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "regen",
    )

    val ex = assertFailsWith<RuntimeException> { generator.buildExpectedTargets() }
    assertTrue(ex.message!!.contains("missing required 'id'"))
  }

  /**
   * Single-test snapshot of the closest-wins inheritance contract this generator
   * shares with `:trailblaze-common`'s `TrailmapDependencyResolver.resolveTarget`. Any
   * change to the expected outputs MUST be mirrored in
   * `trailblaze-common/src/jvmAndAndroidTest/kotlin/xyz/block/trailblaze/config/project/TrailmapDependencyResolverTest.kt`'s
   * sibling test of the same name. Both implementations are independently
   * maintained but behaviorally identical; both tests pin the contract from their
   * respective side. If you find yourself updating one to make it pass, update the
   * other with the matching change before merging.
   */
  @Test
  fun `parity contract — closest-wins inheritance, no list concat, multi-field, multi-platform`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "framework").mkdirs()
    File(trailmapsDir, "framework/trailmap.yaml").writeText(
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
    File(trailmapsDir, "consumer").mkdirs()
    File(trailmapsDir, "consumer/trailmap.yaml").writeText(
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

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
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

  // ===========================================================================
  // system_prompt_file resolution (build-time `resolveSystemPromptFile`).
  // ===========================================================================

  @Test
  fun `trailmap with system_prompt_file inlines content into generated target system_prompt`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    File(trailmapsDir, "alpha/prompt.md").writeText("Alpha prompt body.\nLine two.")

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val expected = generator.buildExpectedTargets()
    val rendered = expected.values.single()

    // The system_prompt_file path is replaced by the inlined content under `system_prompt:`.
    assertTrue(rendered.contains("system_prompt:"), "expected `system_prompt:` in rendered: $rendered")
    assertTrue(rendered.contains("Alpha prompt body."), "prompt content not inlined: $rendered")
    assertTrue(rendered.contains("Line two."), "multi-line prompt content not preserved: $rendered")
    assertTrue(
      !rendered.contains("system_prompt_file:"),
      "system_prompt_file path should be dropped after resolution; got: $rendered",
    )
  }

  @Test
  fun `trailmap with missing system_prompt_file fails the build with the resolved path`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "beta").mkdirs()
    File(trailmapsDir, "beta/trailmap.yaml").writeText(
      """
      id: beta
      target:
        display_name: Beta
        system_prompt_file: nope.md
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex =
      assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("nope.md") == true,
      "error should name the missing file; got: ${ex.message}",
    )
  }

  @Test
  fun `system_prompt_file pointing outside the trailmap directory is rejected`() {
    // ../-escape attack: a malicious or buggy trailmap.yaml could try to read a file outside its
    // trailmap root. Path-element containment in resolveSystemPromptFile must reject this.
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "gamma").mkdirs()
    File(trailmapsDir, "gamma/trailmap.yaml").writeText(
      """
      id: gamma
      target:
        display_name: Gamma
        system_prompt_file: ../../outside.md
      """.trimIndent(),
    )
    // Create a file at the escape target so the failure is "outside the trailmap directory" and
    // not just "file not found" — the latter would be a weaker assertion.
    File(trailmapsDir.parentFile, "outside.md").writeText("should not be reachable")

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex =
      assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("resolves outside the trailmap directory") == true,
      "error should call out the containment violation; got: ${ex.message}",
    )
  }

  @Test
  fun `system_prompt_file rejects sibling-prefix attack against a similarly-named directory`() {
    // Without a path-element separator on the containment check, a sibling directory that
    // starts with the trailmap dir name as a literal prefix (e.g. `delta-extras/...` against trailmap
    // root `/.../delta`) would pass a raw startsWith check. The current implementation appends
    // `File.separator` to force a real directory boundary; this test pins that.
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "delta").mkdirs()
    File(trailmapsDir, "delta-extras").mkdirs()
    File(trailmapsDir, "delta-extras/sneaky.md").writeText("attacker-controlled content")
    File(trailmapsDir, "delta/trailmap.yaml").writeText(
      """
      id: delta
      target:
        display_name: Delta
        system_prompt_file: ../delta-extras/sneaky.md
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex =
      assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("resolves outside the trailmap directory") == true,
      "sibling-prefix attack should be rejected; got: ${ex.message}",
    )
  }

  // -------------------------------------------------------------------------
  // Phase C — scripted-tool name resolution.
  //
  // `target.tools:` is a list of tool *names* that resolve against a per-trailmap
  // registry built by discover-and-decode of every `.yaml` under `<trailmap>/tools/`
  // (operational suffixes excluded). These tests pin the discovery, lookup, and
  // diagnostic behavior of `buildTrailmapScriptedToolRegistry` + `resolveScriptedToolList`.
  // The sister implementations (runtime loader, build-time bundler, daemon bundler)
  // have their own tests in their respective modules; these guard the Gradle
  // generator's slice of the same algorithm.
  // -------------------------------------------------------------------------

  @Test
  fun `multi-tool descriptor entries resolve individually by name in target tools`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "multipack").mkdirs()
    File(trailmapsDir, "multipack/tools").mkdirs()
    // One descriptor exposing two named entries — only one referenced in target.tools.
    File(trailmapsDir, "multipack/tools/multi.yaml").writeText(
      """
      script: ./multi.ts
      tools:
        - name: keepMe
          description: This one is referenced.
          inputSchema:
            x: { type: string }
        - name: skipMe
          description: This one is intentionally not referenced.
      """.trimIndent(),
    )
    File(trailmapsDir, "multipack/trailmap.yaml").writeText(
      """
      id: multipack
      target:
        display_name: MultiTrailmap
        tools:
          - keepMe
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val expected = generator.buildExpectedTargets()
    val (_, content) = expected.entries.single()

    assertTrue(content.contains("name: keepMe"), "generated YAML should include the referenced entry; got: $content")
    assertTrue(
      !content.contains("name: skipMe"),
      "generated YAML must NOT include unreferenced multi-tool entries; got: $content",
    )
  }

  @Test
  fun `target tools entry that doesn't match any descriptor fails with available names`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "unknownnamepack").mkdirs()
    File(trailmapsDir, "unknownnamepack/tools").mkdirs()
    File(trailmapsDir, "unknownnamepack/tools/real.yaml").writeText(
      """
      script: ./real.ts
      name: realTool
      """.trimIndent(),
    )
    File(trailmapsDir, "unknownnamepack/trailmap.yaml").writeText(
      """
      id: unknownnamepack
      target:
        display_name: Unknown
        tools:
          - missingTool
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex = assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("'missingTool'") == true,
      "error must name the unknown tool; got: ${ex.message}",
    )
    assertTrue(
      ex.message?.contains("realTool") == true,
      "error must enumerate available names so authors can spot typos; got: ${ex.message}",
    )
  }

  @Test
  fun `legacy file-path entry in target tools fires the migration hint`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "legacypack").mkdirs()
    File(trailmapsDir, "legacypack/tools").mkdirs()
    File(trailmapsDir, "legacypack/tools/real.yaml").writeText(
      """
      script: ./real.ts
      name: realTool
      """.trimIndent(),
    )
    File(trailmapsDir, "legacypack/trailmap.yaml").writeText(
      """
      id: legacypack
      target:
        display_name: Legacy
        tools:
          - tools/real.yaml
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex = assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("Hint:") == true && ex.message?.contains("looks like a file path") == true,
      "legacy file-path entries must trigger the migration hint; got: ${ex.message}",
    )
  }

  @Test
  fun `two descriptors declaring the same name in one trailmap fail with both file names`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "duppack").mkdirs()
    File(trailmapsDir, "duppack/tools").mkdirs()
    File(trailmapsDir, "duppack/tools/first.yaml").writeText(
      """
      script: ./first.ts
      name: dupTool
      """.trimIndent(),
    )
    File(trailmapsDir, "duppack/tools/second.yaml").writeText(
      """
      script: ./second.ts
      name: dupTool
      """.trimIndent(),
    )
    File(trailmapsDir, "duppack/trailmap.yaml").writeText(
      """
      id: duppack
      target:
        display_name: Dup
        tools:
          - dupTool
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex = assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(ex.message?.contains("'dupTool'") == true, "got: ${ex.message}")
    assertTrue(
      ex.message?.contains("first.yaml") == true && ex.message?.contains("second.yaml") == true,
      "error must name both contributing files so the author can pick which to keep; got: ${ex.message}",
    )
  }

  @Test
  fun `duplicate target tools entries within one trailmap fail loudly`() {
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "dupentrypack").mkdirs()
    File(trailmapsDir, "dupentrypack/tools").mkdirs()
    File(trailmapsDir, "dupentrypack/tools/foo.yaml").writeText(
      """
      script: ./foo.ts
      name: foo
      """.trimIndent(),
    )
    File(trailmapsDir, "dupentrypack/trailmap.yaml").writeText(
      """
      id: dupentrypack
      target:
        display_name: DupEntry
        tools:
          - foo
          - foo
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex = assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("'foo'") == true && ex.message?.contains("more than once") == true,
      "duplicate entry in target.tools must fail loudly; got: ${ex.message}",
    )
  }

  @Test
  fun `operational tool YAMLs in tools dir are skipped during scripted-tool discovery`() {
    // `<trailmap>/tools/foo.tool.yaml` is a pure-YAML operational tool. The scripted-tool
    // discovery walk excludes it by suffix so it doesn't get decoded as a scripted-tool
    // descriptor (which would either fail to parse or shadow a legitimate scripted name).
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "mixedpack").mkdirs()
    File(trailmapsDir, "mixedpack/tools").mkdirs()
    File(trailmapsDir, "mixedpack/tools/scripted.yaml").writeText(
      """
      script: ./scripted.ts
      name: scriptedTool
      """.trimIndent(),
    )
    File(trailmapsDir, "mixedpack/tools/legacy.tool.yaml").writeText(
      """
      # Pure-YAML operational tool — not a scripted-tool descriptor.
      id: legacyOperationalTool
      description: not a scripted tool
      """.trimIndent(),
    )
    File(trailmapsDir, "mixedpack/trailmap.yaml").writeText(
      """
      id: mixedpack
      target:
        display_name: Mixed
        tools:
          - scriptedTool
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val expected = generator.buildExpectedTargets()
    val (_, content) = expected.entries.single()
    assertTrue(content.contains("name: scriptedTool"), "scripted-tool entry must be present; got: $content")
    assertTrue(
      !content.contains("legacyOperationalTool"),
      "operational `.tool.yaml` siblings must NOT be decoded as scripted-tool descriptors; got: $content",
    )
  }

  @Test
  fun `symlinked descriptor that escapes the trailmap directory is rejected`() {
    // Mirrors the runtime loader's containment guarantee — a `<trailmap>/tools/foo.yaml`
    // symlink that resolves outside the trailmap must be rejected, not silently followed.
    val trailmapsDir = newTempDir()
    val targetsDir = newTempDir()
    File(trailmapsDir, "containmentpack").mkdirs()
    File(trailmapsDir, "containmentpack/tools").mkdirs()
    // The descriptor file that *would* be the escape target — outside the trailmap.
    val outsideDir = newTempDir()
    val outsideTarget = File(outsideDir, "outside.yaml").apply {
      writeText(
        """
        script: ./outside.ts
        name: outsideTool
        """.trimIndent(),
      )
    }
    val symlinkSource = File(trailmapsDir, "containmentpack/tools/escape.yaml").toPath()
    val symlinkTarget = outsideTarget.toPath()
    try {
      java.nio.file.Files.createSymbolicLink(symlinkSource, symlinkTarget)
    } catch (_: UnsupportedOperationException) {
      // Filesystem doesn't support symlinks (Windows without elevated perms) — skip the test.
      return
    }
    File(trailmapsDir, "containmentpack/trailmap.yaml").writeText(
      """
      id: containmentpack
      target:
        display_name: Containment
        tools:
          - escape
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(trailmapsDir, targetsDir, "./gradlew :foo:generate")
    val ex = assertFailsWith<GradleException> { generator.buildExpectedTargets() }
    assertTrue(
      ex.message?.contains("resolves outside the trailmap directory") == true,
      "escaping symlinks must be rejected at discovery time; got: ${ex.message}",
    )
  }

  @Test
  fun `descriptor-less target tools name resolves from the analyzer JSON with a relativized script`() {
    // The analyzer-JSON fallback: a `target.tools:` name with NO sibling YAML descriptor is resolved
    // from the JSON emitted by BundledScriptedToolAnalyzeMain (keyed trailmapId -> toolName ->
    // inline config). The kaml descriptor path stays primary; this only fills the descriptor-less gap.
    val scriptRoot = newTempDir()
    val trailmapsDir = File(scriptRoot, "trails/config/trailmaps").apply { mkdirs() }
    val targetsDir = newTempDir()
    File(trailmapsDir, "myapp/tools").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
      """
      id: myapp
      target:
        display_name: MyApp
        tools:
          - myTool
      """.trimIndent(),
    )
    // The descriptor-less `.ts` (no sibling myTool.yaml).
    val tsFile = File(trailmapsDir, "myapp/tools/myTool.ts").apply {
      writeText("export const myTool = trailblaze.tool(async () => \"ok\");\n")
    }
    // Analyzer JSON as BundledScriptedToolAnalyzeMain would emit it — `script` is an ABSOLUTE path.
    val analyzerJson = File(newTempDir(), "analyzer-tool-defs.json")
    analyzerJson.writeText(
      """
      {
        "myapp": {
          "myTool": {
            "script": "${tsFile.absolutePath}",
            "name": "myTool",
            "description": "Does a thing.",
            "_meta": { "trailblaze/supportedPlatforms": ["ios"] },
            "inputSchema": { "type": "object", "properties": {} }
          }
        }
      }
      """.trimIndent(),
    )

    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir,
      targetsDir = targetsDir,
      regenerateCommand = "./gradlew :foo:generate",
      scriptRootDir = scriptRoot,
      analyzerToolsJson = analyzerJson,
    )
    val content = generator.buildExpectedTargets().values.single()

    assertTrue(content.contains("name: myTool"), "analyzer-derived tool missing from target: $content")
    assertTrue(content.contains("Does a thing."), "analyzer-derived description missing: $content")
    // `script` relativized to scriptRootDir, NOT the absolute analyzer path.
    assertTrue(
      content.contains("script: trails/config/trailmaps/myapp/tools/myTool.ts"),
      "script should be relativized to scriptRootDir; got: $content",
    )
    assertTrue(!content.contains(tsFile.absolutePath), "absolute script path leaked into target: $content")
    assertTrue(content.contains("trailblaze/supportedPlatforms"), "analyzer-derived _meta missing: $content")
  }

  private fun newTempDir(): File =
    createTempDirectory("trailmap-target-generator-test").toFile().also { tempDirs += it }
}

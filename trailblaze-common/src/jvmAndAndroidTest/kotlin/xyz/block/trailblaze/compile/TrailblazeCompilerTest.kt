package xyz.block.trailblaze.compile

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.llm.config.ConfigResourceSource

class TrailblazeCompilerTest {

  private val workDir: File = createTempDirectory("trailblaze-compiler-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `compile resolves dependencies and writes one yaml per app pack`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "framework").mkdirs()
    // Toolset names used here (`core_interaction`, `memory`, `verification`) are real
    // toolsets shipped on `:trailblaze-models`, so they're reachable via the default
    // `ClasspathConfigResourceSource` and the unresolved-reference validation passes.
    // Tests exercising validation specifically use a synthetic ConfigResourceSource
    // (see the validation tests below).
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
            - core_interaction
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
              - verification
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)

    assertTrue(result.isSuccess, "Expected success, got errors: ${result.errors}")
    assertEquals(1, result.emittedTargets.size, "Library pack should not produce target output")
    val out = result.emittedTargets.single()
    assertEquals("myapp.yaml", out.name)
    assertTrue(
      out.readText().startsWith(TrailblazeCompiler.GENERATED_BANNER),
      "Emitted file should start with the generated-file banner; got: ${out.readText().take(120)}",
    )

    // The kaml decoder ignores leading comments, so the banner doesn't break parsing.
    val target = TrailblazeConfigYaml.instance.decodeFromString(
      AppTargetYamlConfig.serializer(),
      out.readText(),
    )
    assertEquals("myapp", target.id)
    assertEquals("My App", target.displayName)

    val android = assertNotNull(target.platforms?.get("android"))
    assertEquals(listOf("com.example.myapp"), android.appIds)
    assertEquals(listOf("core_interaction", "memory"), android.toolSets)

    val web = assertNotNull(target.platforms?.get("web"))
    // Closest-wins, no list concat: consumer's tool_sets fully replaces inherited.
    assertEquals(listOf("verification"), web.toolSets)
    // Drivers inherited because consumer didn't set them.
    assertEquals(listOf("playwright-native"), web.drivers)
  }

  @Test
  fun `compile names the failing pack when a dependency is missing`() {
    // Gap-detection upgrade: the error message must name WHICH pack failed instead of
    // a generic "N pack(s) failed" — so authors can jump straight to the offending
    // manifest. A generic count is correct but useless for triage.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "consumer").mkdirs()
    File(packsDir, "consumer/pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-pack
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)

    assertTrue(!result.isSuccess, "Expected compile to fail on missing dep")
    assertTrue(
      result.errors.any { "consumer" in it && "failed dependency resolution" in it },
      "Expected error message to name the failing pack 'consumer'; got: ${result.errors}",
    )
    assertTrue(result.emittedTargets.isEmpty(), "No targets should be emitted on error")
  }

  @Test
  fun `compile is a no-op when no packs are present`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)

    assertTrue(result.isSuccess)
    assertTrue(result.emittedTargets.isEmpty())
    assertTrue(result.deletedOrphans.isEmpty())
  }

  @Test
  fun `compile errors on unknown toolset reference`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
            tool_sets:
              - core_intaraction
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = staticReferenceSource(toolsets = setOf("core_interaction")),
    )

    assertTrue(!result.isSuccess, "Expected compile to fail on unknown toolset, got: ${result.emittedTargets}")
    assertTrue(
      result.errors.any { "core_intaraction" in it && "android" in it && "myapp" in it && "tool_sets" in it },
      "Expected error to mention the unknown toolset, platform, field, and target id; got: ${result.errors}",
    )
    assertTrue(result.emittedTargets.isEmpty(), "No targets should be emitted on validation error")
  }

  @Test
  fun `compile errors on unknown tool reference at the platform level`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
            tools:
              - tap_with_tehxt
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = staticReferenceSource(tools = setOf("tap_with_text")),
    )

    assertTrue(!result.isSuccess, "Expected compile to fail on unknown tool")
    assertTrue(
      result.errors.any { "tap_with_tehxt" in it && "tools" in it },
      "Expected error to name the unknown tool; got: ${result.errors}",
    )
  }

  @Test
  fun `compile errors on unknown tool reference in excluded_tools`() {
    // Typos in `excluded_tools:` are easy to miss — the runtime tolerates exclusions
    // for unknown tools (they're not present anyway) so the intended exclusion
    // silently doesn't apply. Compile-time validation surfaces the typo.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
            excluded_tools:
              - tap_with_tehxt
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = staticReferenceSource(tools = setOf("tap_with_text")),
    )

    assertTrue(!result.isSuccess, "Expected compile to fail on unknown excluded tool")
    assertTrue(
      result.errors.any { "tap_with_tehxt" in it && "excluded_tools" in it },
      "Expected error to name the unknown excluded tool; got: ${result.errors}",
    )
  }

  @Test
  fun `compile errors on unknown driver`() {
    // Drivers are an enum-defined set (DriverTypeKey.knownKeys), not classpath-discovered.
    // A typo like `playwright-nativ` would resolve to no drivers at runtime and silently
    // cripple the platform. Compile-time validation against the enum surfaces it.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
        platforms:
          web:
            drivers:
              - playwright-nativ
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      // Drivers are validated even with a null reference source — they don't depend on
      // classpath I/O. This proves the driver pool is enum-driven.
      referenceSource = null,
    )

    assertTrue(!result.isSuccess, "Expected compile to fail on unknown driver")
    assertTrue(
      result.errors.any { "playwright-nativ" in it && "drivers" in it },
      "Expected error to name the unknown driver; got: ${result.errors}",
    )
  }

  @Test
  fun `compile accepts toolsets declared in workspace packs`() {
    // Pack-declared toolsets should be in the validation pool so consumer packs
    // referencing them by name don't false-positive. This covers workspace authors
    // who define toolsets next to their target without going through the classpath.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      toolsets:
        - toolsets/custom_set.yaml
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
            tool_sets:
              - custom_set
      """.trimIndent(),
    )
    File(packsDir, "myapp/toolsets").mkdirs()
    File(packsDir, "myapp/toolsets/custom_set.yaml").writeText(
      """
      id: custom_set
      tools: []
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    // Empty classpath pool — `custom_set` only resolves because the pack declared it.
    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = staticReferenceSource(),
    )

    assertTrue(result.isSuccess, "Expected pack-declared toolset to satisfy reference, got: ${result.errors}")
  }

  @Test
  fun `compile skips toolset and tool validation when referenceSource is null but still validates drivers`() {
    // Cross-classpath callers (build-logic / future bridges) opt out of toolset+tool
    // validation by passing null. Drivers are still validated because they're enum-
    // defined and don't depend on classpath state.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "myapp").mkdirs()
    File(packsDir, "myapp/pack.yaml").writeText(
      """
      id: myapp
      target:
        display_name: My App
        platforms:
          android:
            app_ids:
              - com.example.myapp
            tool_sets:
              - definitely_does_not_exist
            tools:
              - also_does_not_exist
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = null,
    )

    assertTrue(
      result.isSuccess,
      "Expected toolset+tool validation to be skipped when referenceSource = null, got errors: ${result.errors}",
    )
    assertEquals(1, result.emittedTargets.size)
  }

  @Test
  fun `compile deletes orphan generated files from a previous run`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    File(packsDir, "beta").mkdirs()
    File(packsDir, "beta/pack.yaml").writeText(
      """
      id: beta
      target:
        display_name: Beta
        platforms:
          android:
            app_ids: [com.example.beta]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    // Compile both packs first.
    val first = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir, referenceSource = null)
    assertTrue(first.isSuccess)
    assertEquals(setOf("alpha.yaml", "beta.yaml"), first.emittedTargets.map { it.name }.toSet())

    // Remove `beta` and recompile. The previous `beta.yaml` should be cleaned up.
    File(packsDir, "beta").deleteRecursively()
    val second = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir, referenceSource = null)
    assertTrue(second.isSuccess)
    assertEquals(listOf("alpha.yaml"), second.emittedTargets.map { it.name })
    assertEquals(
      listOf("beta.yaml"),
      second.deletedOrphans.map { it.name },
      "Stale beta.yaml should have been deleted as an orphan",
    )
    assertTrue(
      !File(outputDir, "beta.yaml").exists(),
      "beta.yaml should be physically removed from disk",
    )
  }

  @Test
  fun `compile leaves hand-authored yaml files in the output dir alone`() {
    // Orphan cleanup is gated on the GENERATED_BANNER signature so the compiler
    // only deletes files it owns. A hand-authored YAML the user dropped into the
    // output dir survives recompilation.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out").apply { mkdirs() }
    val handAuthored = File(outputDir, "user-notes.yaml").apply {
      writeText("# my notes\nsome: data\n")
    }

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = outputDir,
      referenceSource = null,
    )

    assertTrue(result.isSuccess)
    assertTrue(result.deletedOrphans.isEmpty(), "Hand-authored file must NOT be deleted")
    assertTrue(handAuthored.exists(), "Hand-authored file must survive compile")
  }

  @Test
  fun `compile skips library packs that have no target block`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "lib").mkdirs()
    File(packsDir, "lib/pack.yaml").writeText(
      """
      id: lib
      defaults:
        android:
          tool_sets: [core_interaction]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)

    assertTrue(result.isSuccess)
    assertTrue(
      result.emittedTargets.isEmpty(),
      "Library pack (no target:) should not produce target output",
    )
  }

  @Test
  fun `compile reports parse errors with the offending pack ref named`() {
    // Malformed YAML in a pack manifest used to surface only as "N packs failed
    // dependency resolution" via the resolver's atomic-per-pack soft-fail. The
    // compiler now pre-parses each manifest and surfaces the parse error directly
    // with the pack ref so authors can jump to the broken file.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "broken").mkdirs()
    File(packsDir, "broken/pack.yaml").writeText(
      """
      id: broken
      target:
        display_name: [this is a list, not a string]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir, referenceSource = null)

    assertTrue(!result.isSuccess, "Expected compile to fail on parse error")
    assertTrue(
      result.errors.any { "broken" in it && "failed to parse" in it },
      "Expected parse error to name the offending pack ref; got: ${result.errors}",
    )
  }

  /**
   * Returns a [ConfigResourceSource] that pretends [toolsets] are the toolsets and
   * [tools] are the tools available on the classpath. Skips real filesystem /
   * classpath I/O so tests don't depend on what's reachable in
   * `:trailblaze-common:jvmTest`'s classpath (which deliberately doesn't include
   * sibling-module toolsets like `web_core` from `:trailblaze-playwright`).
   */
  private fun staticReferenceSource(
    toolsets: Set<String> = emptySet(),
    tools: Set<String> = emptySet(),
  ): ConfigResourceSource =
    ConfigResourceSource { directoryPath, _ ->
      when {
        directoryPath.endsWith("/toolsets") ->
          toolsets.associate { id -> "$id.yaml" to "id: $id\ntools: []\n" }
        directoryPath.endsWith("/tools") ->
          tools.associate { id -> "$id.yaml" to "id: $id\n" }
        else -> emptyMap()
      }
    }
}

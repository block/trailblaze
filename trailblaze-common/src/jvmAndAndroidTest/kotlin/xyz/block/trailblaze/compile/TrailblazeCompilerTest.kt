package xyz.block.trailblaze.compile

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.llm.config.ConfigResourceSource

class TrailblazeCompilerTest {

  private val workDir: File = createTempDirectory("trailblaze-compiler-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `compile resolves dependencies and writes one yaml per app trailmap`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "framework").mkdirs()
    // Toolset names used here (`core_interaction`, `memory`, `verification`) are real
    // toolsets shipped on `:trailblaze-models`, so they're reachable via the default
    // `ClasspathConfigResourceSource` and the unresolved-reference validation passes.
    // Tests exercising validation specifically use a synthetic ConfigResourceSource
    // (see the validation tests below).
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
            - core_interaction
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
              - verification
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir)

    assertTrue(result.isSuccess, "Expected success, got errors: ${result.errors}")
    assertEquals(1, result.emittedTargets.size, "Library trailmap should not produce target output")
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
  fun `compile names the failing trailmap when a dependency is missing`() {
    // Gap-detection upgrade: the error message must name WHICH trailmap failed instead of
    // a generic "N trailmap(s) failed" — so authors can jump straight to the offending
    // manifest. A generic count is correct but useless for triage.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "consumer").mkdirs()
    File(trailmapsDir, "consumer/trailmap.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-trailmap
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir)

    assertTrue(!result.isSuccess, "Expected compile to fail on missing dep")
    assertTrue(
      result.errors.any { "consumer" in it && "missing-trailmap" in it },
      "Expected error message to name the failing trailmap 'consumer' and the missing dep " +
        "'missing-trailmap'; got: ${result.errors}",
    )
    assertTrue(result.emittedTargets.isEmpty(), "No targets should be emitted on error")
  }

  @Test
  fun `compile is a no-op when no trailmaps are present`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir)

    assertTrue(result.isSuccess)
    assertTrue(result.emittedTargets.isEmpty())
    assertTrue(result.deletedOrphans.isEmpty())
  }

  @Test
  fun `compile errors on unknown toolset reference`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
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
  fun `compile accepts toolsets declared in workspace trailmaps`() {
    // Trailmap-declared toolsets should be in the validation pool so consumer trailmaps
    // referencing them by name don't false-positive. This covers workspace authors
    // who define toolsets next to their target without going through the classpath.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
    File(trailmapsDir, "myapp/toolsets").mkdirs()
    File(trailmapsDir, "myapp/toolsets/custom_set.yaml").writeText(
      """
      id: custom_set
      tools: []
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    // Empty classpath pool — `custom_set` only resolves because the trailmap declared it.
    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = staticReferenceSource(),
    )

    assertTrue(result.isSuccess, "Expected trailmap-declared toolset to satisfy reference, got: ${result.errors}")
  }

  @Test
  fun `compile skips toolset and tool validation when referenceSource is null but still validates drivers`() {
    // Cross-classpath callers (build-logic / future bridges) opt out of toolset+tool
    // validation by passing null. Drivers are still validated because they're enum-
    // defined and don't depend on classpath state.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "myapp").mkdirs()
    File(trailmapsDir, "myapp/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
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
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    File(trailmapsDir, "beta").mkdirs()
    File(trailmapsDir, "beta/trailmap.yaml").writeText(
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

    // Compile both trailmaps first.
    val first = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir, referenceSource = null)
    assertTrue(first.isSuccess)
    assertEquals(setOf("alpha.yaml", "beta.yaml"), first.emittedTargets.map { it.name }.toSet())

    // Remove `beta` and recompile. The previous `beta.yaml` should be cleaned up.
    File(trailmapsDir, "beta").deleteRecursively()
    val second = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir, referenceSource = null)
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
  fun `compile cleans up files bearing the legacy 'trailblaze compile' banner`() {
    // The CLI was renamed from `trailblaze compile` to `trailblaze check` (PR #3236).
    // Working trees built before the rename carry generated files starting with the
    // OLD banner. Recognizing only the new banner would silently strand those as
    // orphans: AppTargetDiscovery prefers `dist/targets/` over hand-authored ones, so
    // a removed-trailmap's old file would shadow the user's intent. This test pins the
    // backward-compat behavior.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
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
    // Pre-seed the output dir with a file bearing the old banner — simulating a
    // generated artifact from a pre-#3236 compile run for a trailmap that's been removed.
    val staleLegacyFile = File(outputDir, "gamma.yaml").apply {
      writeText("# GENERATED BY trailblaze compile. DO NOT EDIT.\nid: gamma\n")
    }

    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = null,
    )

    assertTrue(result.isSuccess)
    assertTrue(
      result.deletedOrphans.any { it.name == "gamma.yaml" },
      "Stale gamma.yaml with legacy banner should have been cleaned up as orphan",
    )
    assertTrue(
      !staleLegacyFile.exists(),
      "gamma.yaml should be physically removed from disk",
    )
  }

  @Test
  fun `compile leaves hand-authored yaml files in the output dir alone`() {
    // Orphan cleanup is gated on the GENERATED_BANNER signature so the compiler
    // only deletes files it owns. A hand-authored YAML the user dropped into the
    // output dir survives recompilation.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "alpha").mkdirs()
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
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
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = null,
    )

    assertTrue(result.isSuccess)
    assertTrue(result.deletedOrphans.isEmpty(), "Hand-authored file must NOT be deleted")
    assertTrue(handAuthored.exists(), "Hand-authored file must survive compile")
  }

  @Test
  fun `compile skips library trailmaps that have no target block`() {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "lib").mkdirs()
    File(trailmapsDir, "lib/trailmap.yaml").writeText(
      """
      id: lib
      defaults:
        android:
          tool_sets: [core_interaction]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir)

    assertTrue(result.isSuccess)
    assertTrue(
      result.emittedTargets.isEmpty(),
      "Library trailmap (no target:) should not produce target output",
    )
  }

  @Test
  fun `compile reports parse errors with the offending trailmap ref named`() {
    // Malformed YAML in a trailmap manifest used to surface only as "N trailmaps failed
    // dependency resolution" via the resolver's atomic-per-trailmap soft-fail. The
    // compiler now pre-parses each manifest and surfaces the parse error directly
    // with the trailmap ref so authors can jump to the broken file.
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    File(trailmapsDir, "broken").mkdirs()
    File(trailmapsDir, "broken/trailmap.yaml").writeText(
      """
      id: broken
      target:
        display_name: [this is a list, not a string]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val result = TrailblazeCompiler.compile(trailmapsDir = trailmapsDir, outputDir = outputDir, referenceSource = null)

    assertTrue(!result.isSuccess, "Expected compile to fail on parse error")
    assertTrue(
      result.errors.any { "broken" in it && "failed to parse" in it },
      "Expected parse error to name the offending trailmap ref; got: ${result.errors}",
    )
  }

  @Test
  fun `compile fails with named diagnostic when meta-only descriptor lacks enrichment`() {
    // Regression guard for the wire-up that landed alongside the ios-contacts typed-authoring
    // migration. A meta-only descriptor (`script:` + `_meta:` only, no top-level `name:`)
    // is unresolvable without an analyzer-backed enrichment; before this wiring, compile()
    // silently passed null and the loader's diagnostic surfaced only as a generic
    // "dependency resolution failed" message. Now compile() accepts an explicit enrichment
    // parameter — verify that the no-enrichment path still raises the expected diagnostic so
    // a future refactor that drops the parameter doesn't silently regress.
    val (trailmapsDir, outputDir) = writeMetaOnlyTrailmapFixture()
    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = null,
      commandLabel = "compile",
      scriptedToolEnrichment = null,
    )

    assertTrue(!result.isSuccess, "Expected compile to fail without enrichment, got: ${result.errors}")
    assertTrue(
      result.errors.any { "metapack" in it && "failed dependency resolution" in it },
      "Expected gap-detection to name the offending trailmap with a meta-only descriptor; got: ${result.errors}",
    )
  }

  @Test
  fun `compile succeeds when a stub enrichment resolves meta-only descriptors`() {
    // Companion to the negative case above — same fixture, but with a stub enrichment that
    // mimics what `AnalyzerScriptedToolEnrichment` would produce for the descriptor. Asserts
    // the success path the host call sites (`CompileCommand`, `WorkspaceCompileBootstrap`)
    // depend on. Without this test, a future refactor that breaks the enrichment threading
    // through `compile()` would only fail at daemon-init time on a workspace that happens
    // to ship meta-only descriptors.
    val (trailmapsDir, outputDir) = writeMetaOnlyTrailmapFixture()
    val stubEnrichment = object : ScriptedToolEnrichment {
      override fun enrich(
        trailmapId: String,
        trailmapDir: File,
        trailmapToolsDir: File,
        deferredDescriptors: List<ScriptedToolEnrichment.DeferredDescriptor>,
      ): List<ScriptedToolEnrichment.EnrichmentResult> = deferredDescriptors.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            InlineScriptToolConfig(
              script = File(trailmapsDir, "metapack/tools/example_tool.ts").absolutePath,
              name = "example_tool",
              description = "stub tool surfaced by the enrichment double for testing.",
              meta = JsonObject(emptyMap()),
            ),
          ),
        )
      }
    }

    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = null,
      commandLabel = "compile",
      scriptedToolEnrichment = stubEnrichment,
    )

    assertTrue(result.isSuccess, "Expected compile to succeed with stub enrichment, got: ${result.errors}")
    assertEquals(1, result.emittedTargets.size, "Expected exactly one target.yaml emitted")
    assertEquals("metapack.yaml", result.emittedTargets.single().name)
  }

  @Test
  fun `compile surfaces enrichment Failed result with reason and trailmap name`() {
    // The third branch of the contract that the two tests above leave uncovered:
    // enrichment IS wired AND runs, but the analyzer couldn't extract a typed
    // declaration from the descriptor's `.ts` source (file missing, no
    // `trailblaze.tool<...>` call, schema extraction error). The loader must
    // propagate the failure as a clear diagnostic citing the descriptor path AND
    // the analyzer's reason. Without this test, a future refactor that silently
    // drops the Failed result (or strips the reason from the diagnostic) would
    // leave authors with an opaque trailmap-level "dependency resolution" error.
    val (trailmapsDir, outputDir) = writeMetaOnlyTrailmapFixture()
    val analyzerReason = "synthetic: no trailblaze.tool<...> export found"
    val failingEnrichment = object : ScriptedToolEnrichment {
      override fun enrich(
        trailmapId: String,
        trailmapDir: File,
        trailmapToolsDir: File,
        deferredDescriptors: List<ScriptedToolEnrichment.DeferredDescriptor>,
      ): List<ScriptedToolEnrichment.EnrichmentResult> = deferredDescriptors.map { d ->
        ScriptedToolEnrichment.EnrichmentResult.Failed(
          relativePath = d.relativePath,
          reason = analyzerReason,
        )
      }
    }

    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = outputDir,
      referenceSource = null,
      commandLabel = "compile",
      scriptedToolEnrichment = failingEnrichment,
    )

    assertTrue(!result.isSuccess, "Expected compile to fail when enrichment returns Failed")
    val joined = result.errors.joinToString("\n")
    assertTrue("metapack" in joined, "Error must name the offending trailmap; got: $joined")
    assertTrue(result.emittedTargets.isEmpty(), "No targets should be emitted when enrichment fails")
    // Note: the full descriptor path + analyzer reason are emitted via `Console.log` as a
    // warning by `TrailblazeTrailmapArtifact.tryResolve` (the atomic-per-trailmap failure-isolation
    // pattern) and don't surface in `result.errors`. That's the existing resolver
    // contract, not something this PR changed. If a future change starts threading the
    // analyzer's reason into `result.errors`, expand this test to assert on both
    // `example_tool.yaml` and the analyzer reason string.
  }

  /**
   * Writes a minimal trailmap fixture with one meta-only scripted-tool descriptor under
   * `<trailmapsDir>/metapack/tools/example_tool.{yaml,ts}`. Returns the (trailmaps, output) pair
   * for the caller to pass into `compile(...)`. Used by the meta-only enrichment tests so
   * each side (no-enrichment vs. stubbed-enrichment) exercises the same on-disk shape.
   */
  private fun writeMetaOnlyTrailmapFixture(): Pair<File, File> {
    val trailmapsDir = File(workDir, "trailmaps").apply { mkdirs() }
    val trailmapDir = File(trailmapsDir, "metapack").apply { mkdirs() }
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: metapack
      target:
        display_name: Meta Trailmap
        tools:
          - example_tool
        platforms:
          android:
            app_ids:
              - com.example.metapack
      """.trimIndent(),
    )
    File(toolsDir, "example_tool.yaml").writeText(
      """
      script: ./example_tool.ts
      _meta:
        trailblaze/supportedPlatforms: [android]
      """.trimIndent(),
    )
    File(toolsDir, "example_tool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      export const example_tool = trailblaze.tool(async () => "ok");
      """.trimIndent(),
    )
    return trailmapsDir to File(workDir, "out")
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

package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.PackTargetConfig
import xyz.block.trailblaze.config.project.ResolvedPack
import xyz.block.trailblaze.config.project.TrailblazePackManifest

/**
 * Tests for [ResolvedTargetReportEmitter] — the per-target Markdown agent-toolbox report.
 *
 * Acceptance coverage:
 *  - A target whose `platforms.*.tool_sets:` is inherited from a framework-defaults dep
 *    has the trace attribute that field to the dep, not to the consumer pack.
 *  - A target that explicitly overrides `platforms.web.tool_sets:` has the trace attribute
 *    that field to its own pack.yaml.
 *  - A target whose dep declares `exports:` surfaces those exported scripted tools in the
 *    agent toolbox section attributed to the dep.
 *  - Idempotent: re-emitting unchanged inputs returns an empty written-list.
 *  - Stats section + table structure are present.
 */
class ResolvedTargetReportEmitterTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `framework-defaults inherited target attributes tool_sets to the dep pack`() {
    val frameworkPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "trailblaze",
        defaults = mapOf(
          "web" to PlatformConfig(
            toolSets = listOf("web_core", "memory"),
            drivers = listOf("playwright-native"),
          ),
        ),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/trailblaze"),
      target = null,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val consumerOwnPlatforms = mapOf(
      "web" to PlatformConfig(appIds = listOf("com.example.consumer")),
    )
    val resolvedPlatforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.consumer"),
        toolSets = listOf("web_core", "memory"),
        drivers = listOf("playwright-native"),
      ),
    )
    val consumerPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "consumer",
        target = PackTargetConfig(displayName = "Consumer", platforms = consumerOwnPlatforms),
        dependencies = listOf("trailblaze"),
      ),
      source = PackSource.Filesystem(newDir("consumer")),
      target = AppTargetYamlConfig(
        id = "consumer",
        displayName = "Consumer",
        platforms = resolvedPlatforms,
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    val emitted = ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(consumerPack.target!!),
      resolvedPacks = listOf(frameworkPack, consumerPack),
      outputDir = outDir,
    )

    assertEquals(1, emitted.size, "expected one report; got $emitted")
    val report = File(outDir, "consumer.report.md").readText()

    // The trace must say `tool_sets` was inherited from `trailblaze`, not the consumer.
    assertTrue(
      "expected tool_sets attributed to `trailblaze` dep in trace, got:\n$report",
    ) {
      report.contains("`tool_sets") && report.contains("inherited from `trailblaze`")
    }
    assertTrue("expected drivers attributed to `trailblaze` dep, got:\n$report") {
      report.contains("`drivers") && report.contains("inherited from `trailblaze`")
    }
    // app_ids was set directly on consumer, so attributes to the consumer pack.
    assertTrue("expected app_ids attributed to consumer pack.yaml, got:\n$report") {
      report.contains("app_ids") && report.contains("declared by `consumer/pack.yaml`")
    }
    assertTrue("expected dependencies block, got:\n$report") {
      report.contains("`dependencies: [trailblaze]`")
    }
  }

  @Test
  fun `target overriding platforms_web_tool_sets attributes to own pack`() {
    val frameworkPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "trailblaze",
        defaults = mapOf(
          "web" to PlatformConfig(toolSets = listOf("web_core")),
        ),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/trailblaze"),
      target = null,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val consumerOverrideToolSets = listOf("memory")
    val ownPlatforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.override"),
        toolSets = consumerOverrideToolSets,
      ),
    )
    val consumerPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "override_consumer",
        target = PackTargetConfig(displayName = "Override Consumer", platforms = ownPlatforms),
        dependencies = listOf("trailblaze"),
      ),
      source = PackSource.Filesystem(newDir("override_consumer")),
      target = AppTargetYamlConfig(
        id = "override_consumer",
        displayName = "Override Consumer",
        platforms = ownPlatforms, // own wins, no overlay since field was set
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(consumerPack.target!!),
      resolvedPacks = listOf(frameworkPack, consumerPack),
      outputDir = outDir,
    )

    val report = File(outDir, "override_consumer.report.md").readText()
    // tool_sets attributed to override_consumer/pack.yaml, NOT the framework dep.
    assertTrue("expected tool_sets attributed to own pack.yaml, got:\n$report") {
      report.contains("`tool_sets = [memory]") &&
        report.contains("declared by `override_consumer/pack.yaml`")
    }
    assertFalse("tool_sets must NOT be attributed to the dep when overridden, got:\n$report") {
      report.contains("`tool_sets") &&
        report.lines().any { it.contains("tool_sets") && it.contains("inherited from") }
    }
  }

  @Test
  fun `library-pack exports flow into agent toolbox attributed to dep`() {
    val libPackDir = newDir("entity_factory")
    val createEntity = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Create an entity.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {})
      },
    )
    val internalHelper = InlineScriptToolConfig(
      script = "./tools/_internal.ts",
      name = "internal_helper",
      description = "Library-internal.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {})
      },
    )
    val libPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "entity_factory",
        target = PackTargetConfig(displayName = "Entity Factory"),
        exports = listOf("createEntity"),
      ),
      source = PackSource.Filesystem(libPackDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity, internalHelper),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "storefront",
        target = PackTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = PackSource.Filesystem(newDir("storefront")),
      target = AppTargetYamlConfig(id = "storefront", displayName = "Storefront"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(appPack.target!!),
      resolvedPacks = listOf(libPack, appPack),
      outputDir = outDir,
    )

    val report = File(outDir, "storefront.report.md").readText()
    // Exported tool appears in agent toolbox attributed to the lib pack.
    assertTrue("expected createEntity from dep entity_factory, got:\n$report") {
      report.contains("`createEntity`") && report.contains("exported from dep `entity_factory`")
    }
    // Non-exported internal helper must NOT appear in the storefront's agent toolbox.
    assertFalse("internal_helper must NOT surface in app's report, got:\n$report") {
      report.contains("`internal_helper`")
    }
  }

  @Test
  fun `report is idempotent across repeated emissions`() {
    val packDir = newDir("idem")
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "idem",
        target = PackTargetConfig(
          displayName = "Idem",
          platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.idem"))),
        ),
      ),
      source = PackSource.Filesystem(packDir),
      target = AppTargetYamlConfig(
        id = "idem",
        displayName = "Idem",
        platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.idem"))),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")

    val firstRun = ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )
    assertEquals(1, firstRun.size, "first run should write the report")

    val report = File(outDir, "idem.report.md")
    val firstMtime = report.lastModified()

    val secondRun = ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )
    assertTrue("second run should be a no-op (got $secondRun)") { secondRun.isEmpty() }
    assertEquals(firstMtime, report.lastModified(), "mtime should not change on no-op re-emit")
  }

  @Test
  fun `orphan reports from prior runs are deleted`() {
    val outDir = newDir("out")
    val orphan = File(outDir, "stale.report.md").apply {
      writeText(
        """
        ${ResolvedTargetReportEmitter.GENERATED_BANNER}
        # stale — agent toolbox report
        """.trimIndent(),
      )
    }
    val handAuthored = File(outDir, "handauthored.report.md").apply {
      writeText("# Hand-authored, no banner — must survive\n")
    }

    val packDir = newDir("current")
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "current",
        target = PackTargetConfig(displayName = "Current"),
      ),
      source = PackSource.Filesystem(packDir),
      target = AppTargetYamlConfig(id = "current", displayName = "Current"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )

    assertFalse("orphan with banner should be deleted") { orphan.exists() }
    assertTrue("hand-authored without banner must survive") { handAuthored.exists() }
    assertTrue("current report must exist") { File(outDir, "current.report.md").exists() }
  }

  @Test
  fun `orphan reports carrying the pre-check legacy banner are also deleted`() {
    // Backward-compat migration: reports written before the `trailblaze compile` →
    // `trailblaze check` CLI verb unification carry the pre-#3236 GENERATED_BANNER.
    // The emitter must still recognize those as emitter-owned and clean them up;
    // otherwise stale reports from removed packs linger in users' working trees
    // forever, defeating the orphan-cleanup contract.
    val outDir = newDir("out")
    val legacyOrphan = File(outDir, "removed.report.md").apply {
      writeText(
        """
        <!-- GENERATED BY trailblaze compile. DO NOT EDIT. -->
        # removed — agent toolbox report
        """.trimIndent(),
      )
    }

    val packDir = newDir("current")
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "current",
        target = PackTargetConfig(displayName = "Current"),
      ),
      source = PackSource.Filesystem(packDir),
      target = AppTargetYamlConfig(id = "current", displayName = "Current"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )

    assertFalse("legacy-bannered orphan should be deleted") { legacyOrphan.exists() }
  }

  @Test
  fun `report has expected section structure`() {
    val packDir = newDir("shape")
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "shape",
        target = PackTargetConfig(
          displayName = "Shape",
          platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.shape"))),
        ),
      ),
      source = PackSource.Filesystem(packDir),
      target = AppTargetYamlConfig(
        id = "shape",
        displayName = "Shape",
        platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.shape"))),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )
    val report = File(outDir, "shape.report.md").readText()

    assertTrue("missing banner") { report.startsWith(ResolvedTargetReportEmitter.GENERATED_BANNER) }
    assertTrue("missing regenerate-with hint naming the current CLI verb") {
      report.contains("<!-- Regenerate with: trailblaze check -->")
    }
    assertTrue("missing title") { report.contains("# shape — agent toolbox report") }
    assertTrue("missing toolset closure section") {
      report.contains("## Toolset closure (runtime registry — transitive union)")
    }
    assertTrue("missing agent toolbox section") {
      report.contains("## Agent toolbox (what the LLM sees at session start)")
    }
    assertTrue("missing resolution trace section") { report.contains("## Resolution trace") }
    assertTrue("missing stats section") { report.contains("## Stats") }
  }

  @Test
  fun `same-depth tie-break attributes to later-declared sibling`() {
    // Two deps at depth 1 both set `platforms.web.tool_sets` in their defaults. The
    // consumer leaves it null. PackDependencyResolver applies overlays farthest-first,
    // so for ties at the same depth the later-declared sibling's value wins — the
    // report must attribute to `depB`, not `depA`.
    val depA = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "depA",
        defaults = mapOf("web" to PlatformConfig(toolSets = listOf("from_a"))),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/depA"),
      target = null,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val depB = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "depB",
        defaults = mapOf("web" to PlatformConfig(toolSets = listOf("from_b"))),
      ),
      source = PackSource.Classpath(resourceDir = "trailblaze-config/packs/depB"),
      target = null,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    // Consumer left `tool_sets` null so the overlay fires; depB is declared after depA
    // in `dependencies:` so it must win on tie.
    val consumerOwnPlatforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.tie")))
    val resolvedPlatforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.tie"),
        toolSets = listOf("from_b"),
      ),
    )
    val consumerPack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "tie_consumer",
        target = PackTargetConfig(displayName = "Tie", platforms = consumerOwnPlatforms),
        dependencies = listOf("depA", "depB"),
      ),
      source = PackSource.Filesystem(newDir("tie_consumer")),
      target = AppTargetYamlConfig(
        id = "tie_consumer",
        displayName = "Tie",
        platforms = resolvedPlatforms,
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(consumerPack.target!!),
      resolvedPacks = listOf(depA, depB, consumerPack),
      outputDir = outDir,
    )
    val report = File(outDir, "tie_consumer.report.md").readText()

    // Must attribute `tool_sets` to depB (later-declared at same depth wins), NOT depA.
    assertTrue("expected tool_sets attributed to depB on same-depth tie, got:\n$report") {
      report.contains("tool_sets") && report.contains("inherited from `depB`")
    }
    assertFalse("must NOT attribute tool_sets to depA on same-depth tie, got:\n$report") {
      report.lines().any { it.contains("tool_sets") && it.contains("inherited from `depA`") }
    }
  }

  @Test
  fun `agent toolbox surfaces individual tools additions and applies excluded tools`() {
    // Target declares `tools: [extra_tool]` (individual addition) and
    // `excluded_tools: [skip_me]` (name-based removal). Even with no resolved toolset
    // (empty closure), the additions must surface, and the exclusions section must list
    // the removed name.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.add"),
        tools = listOf("extra_tool", "skip_me"),
        excludedTools = listOf("skip_me"),
      ),
    )
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "addex",
        target = PackTargetConfig(displayName = "AddEx", platforms = platforms),
      ),
      source = PackSource.Filesystem(newDir("addex")),
      target = AppTargetYamlConfig(id = "addex", displayName = "AddEx", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )
    val report = File(outDir, "addex.report.md").readText()

    // `extra_tool` surfaces under "individual `tools:` addition" (and was NOT excluded).
    assertTrue("expected extra_tool as individual addition, got:\n$report") {
      report.contains("`extra_tool` (individual `tools:` addition)")
    }
    // `skip_me` is excluded — must NOT appear as an addition, but MUST appear in the
    // dedicated Excluded section.
    assertFalse("skip_me must not surface as included tool, got:\n$report") {
      report.contains("`skip_me` (individual `tools:` addition)")
    }
    assertTrue("expected Excluded section listing skip_me, got:\n$report") {
      report.contains("Excluded by `excluded_tools:`:") && report.contains("`skip_me` (excluded)")
    }
  }

  @Test
  fun `empty tool_sets list materializes platform in runtime registry`() {
    // `tool_sets: []` (explicit empty list) must materialize the `web` platform in the
    // runtime-registry section — mirrors PackRuntimeRegistryResolver.contribute, which
    // seeds the platform key even for an empty list so callers can distinguish "declared
    // but empty" from "never mentioned."
    val platforms = mapOf(
      "web" to PlatformConfig(appIds = listOf("com.example.empty"), toolSets = emptyList()),
    )
    val pack = ResolvedPack(
      manifest = TrailblazePackManifest(
        id = "empty_ts",
        target = PackTargetConfig(displayName = "Empty TS", platforms = platforms),
      ),
      source = PackSource.Filesystem(newDir("empty_ts")),
      target = AppTargetYamlConfig(id = "empty_ts", displayName = "Empty TS", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(pack.target!!),
      resolvedPacks = listOf(pack),
      outputDir = outDir,
    )
    val report = File(outDir, "empty_ts.report.md").readText()

    // The runtime-registry section must include a `web` platform heading (materialized
    // from `tool_sets: []`), with the empty-state line — NOT silently omitted.
    val registrySection = report
      .substringAfter("## Toolset closure (runtime registry — transitive union)")
      .substringBefore("## Agent toolbox")
    assertTrue("expected `web` platform heading in runtime-registry section, got:\n$registrySection") {
      registrySection.contains("### Platform `web`")
    }
    assertTrue("expected empty-state message under web platform, got:\n$registrySection") {
      registrySection.contains("_(no tool_sets declared for this platform)_")
    }
    // Resolved-platforms header must also mention `web`.
    assertTrue("expected resolved platform `web` listed in header, got:\n$report") {
      report.contains("Resolved platforms: `web`")
    }
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("resolved-target-report-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }
}

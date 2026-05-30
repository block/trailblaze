package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest

/**
 * Tests for [ResolvedTargetReportEmitter] — the per-target Markdown agent-toolbox report.
 *
 * Acceptance coverage:
 *  - A target whose `platforms.*.tool_sets:` is inherited from a framework-defaults dep
 *    has the trace attribute that field to the dep, not to the consumer trailmap.
 *  - A target that explicitly overrides `platforms.web.tool_sets:` has the trace attribute
 *    that field to its own trailmap.yaml.
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
  fun `framework-defaults inherited target attributes tool_sets to the dep trailmap`() {
    val frameworkTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "trailblaze",
        defaults = mapOf(
          "web" to PlatformConfig(
            toolSets = listOf("web_core", "memory"),
            drivers = listOf("playwright-native"),
          ),
        ),
      ),
      source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/trailblaze"),
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
    val consumerTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "consumer",
        target = TrailmapTargetConfig(displayName = "Consumer", platforms = consumerOwnPlatforms),
        dependencies = listOf("trailblaze"),
      ),
      source = TrailmapSource.Filesystem(newDir("consumer")),
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
      resolvedTargets = listOf(consumerTrailmap.target!!),
      resolvedTrailmaps = listOf(frameworkTrailmap, consumerTrailmap),
      outputDir = outDir,
    )

    val emittedReports = emitted.filter { it.name.endsWith(".report.md") }
    assertEquals(1, emittedReports.size, "expected one index report; got $emittedReports")
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
    // app_ids was set directly on consumer, so attributes to the consumer trailmap.
    assertTrue("expected app_ids attributed to consumer trailmap.yaml, got:\n$report") {
      report.contains("app_ids") && report.contains("declared by `consumer/trailmap.yaml`")
    }
    assertTrue("expected dependencies block, got:\n$report") {
      report.contains("`dependencies: [trailblaze]`")
    }
  }

  @Test
  fun `target overriding platforms_web_tool_sets attributes to own trailmap`() {
    val frameworkTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "trailblaze",
        defaults = mapOf(
          "web" to PlatformConfig(toolSets = listOf("web_core")),
        ),
      ),
      source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/trailblaze"),
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
    val consumerTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "override_consumer",
        target = TrailmapTargetConfig(displayName = "Override Consumer", platforms = ownPlatforms),
        dependencies = listOf("trailblaze"),
      ),
      source = TrailmapSource.Filesystem(newDir("override_consumer")),
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
      resolvedTargets = listOf(consumerTrailmap.target!!),
      resolvedTrailmaps = listOf(frameworkTrailmap, consumerTrailmap),
      outputDir = outDir,
    )

    val report = File(outDir, "override_consumer.report.md").readText()
    // tool_sets attributed to override_consumer/trailmap.yaml, NOT the framework dep.
    assertTrue("expected tool_sets attributed to own trailmap.yaml, got:\n$report") {
      report.contains("`tool_sets = [memory]") &&
        report.contains("declared by `override_consumer/trailmap.yaml`")
    }
    assertFalse("tool_sets must NOT be attributed to the dep when overridden, got:\n$report") {
      report.contains("`tool_sets") &&
        report.lines().any { it.contains("tool_sets") && it.contains("inherited from") }
    }
  }

  @Test
  fun `library-trailmap exports flow into agent toolbox attributed to dep`() {
    val libTrailmapDir = newDir("entity_factory")
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
    val libTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "entity_factory",
        target = TrailmapTargetConfig(displayName = "Entity Factory"),
        exports = listOf("createEntity"),
      ),
      source = TrailmapSource.Filesystem(libTrailmapDir),
      target = AppTargetYamlConfig(
        id = "entity_factory",
        displayName = "Entity Factory",
        tools = listOf(createEntity, internalHelper),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val appTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "storefront",
        target = TrailmapTargetConfig(displayName = "Storefront"),
        dependencies = listOf("entity_factory"),
      ),
      source = TrailmapSource.Filesystem(newDir("storefront")),
      target = AppTargetYamlConfig(id = "storefront", displayName = "Storefront"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(appTrailmap.target!!),
      resolvedTrailmaps = listOf(libTrailmap, appTrailmap),
      outputDir = outDir,
    )

    val report = File(outDir, "storefront.report.md").readText()
    // Exported tool appears in agent toolbox attributed to the lib trailmap — and the tool-name
    // cell links to the sidecar detail file rather than just rendering the bare name.
    assertTrue("expected createEntity link cell from dep entity_factory, got:\n$report") {
      report.contains("[`createEntity`](storefront/tools/createEntity.md)") &&
        report.contains("exported from dep `entity_factory`")
    }
    // Non-exported internal helper must NOT appear in the storefront's agent toolbox.
    assertFalse("internal_helper must NOT surface in app's report, got:\n$report") {
      report.contains("`internal_helper`")
    }
    // The scripted-tool sidecar must exist and render the metadata we declared.
    val createEntitySidecar = File(outDir, "storefront/tools/createEntity.md")
    assertTrue("expected sidecar for createEntity at $createEntitySidecar") { createEntitySidecar.exists() }
    val sidecarText = createEntitySidecar.readText()
    assertTrue("sidecar must carry the generated banner") {
      sidecarText.startsWith(ResolvedTargetToolDetailRenderer.GENERATED_BANNER)
    }
    assertTrue("sidecar must render the tool's declared description") {
      sidecarText.contains("Create an entity.")
    }
    assertTrue("sidecar must show the scripted source kind + script path") {
      sidecarText.contains("- Kind: scripted") &&
        sidecarText.contains("- Script: `./tools/createEntity.ts`")
    }
    assertTrue("sidecar must attribute the tool to the exporting dep") {
      sidecarText.contains("Origin trailmap: `entity_factory`")
    }
    assertFalse("internal helper must NOT have a sidecar in the app's tools dir") {
      File(outDir, "storefront/tools/internal_helper.md").exists()
    }
  }

  @Test
  fun `report is idempotent across repeated emissions`() {
    val trailmapDir = newDir("idem")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "idem",
        target = TrailmapTargetConfig(
          displayName = "Idem",
          platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.idem"))),
        ),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
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
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    assertTrue("first run should write the report (got $firstRun)") {
      firstRun.any { it.name == "idem.report.md" }
    }

    val report = File(outDir, "idem.report.md")
    val firstMtime = report.lastModified()

    val secondRun = ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
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

    val trailmapDir = newDir("current")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "current",
        target = TrailmapTargetConfig(displayName = "Current"),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "current", displayName = "Current"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
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
    // otherwise stale reports from removed trailmaps linger in users' working trees
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

    val trailmapDir = newDir("current")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "current",
        target = TrailmapTargetConfig(displayName = "Current"),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
      target = AppTargetYamlConfig(id = "current", displayName = "Current"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )

    assertFalse("legacy-bannered orphan should be deleted") { legacyOrphan.exists() }
  }

  @Test
  fun `report has expected section structure`() {
    val trailmapDir = newDir("shape")
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "shape",
        target = TrailmapTargetConfig(
          displayName = "Shape",
          platforms = mapOf("web" to PlatformConfig(appIds = listOf("com.example.shape"))),
        ),
      ),
      source = TrailmapSource.Filesystem(trailmapDir),
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
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
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
    // consumer leaves it null. TrailmapDependencyResolver applies overlays farthest-first,
    // so for ties at the same depth the later-declared sibling's value wins — the
    // report must attribute to `depB`, not `depA`.
    val depA = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "depA",
        defaults = mapOf("web" to PlatformConfig(toolSets = listOf("from_a"))),
      ),
      source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/depA"),
      target = null,
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val depB = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "depB",
        defaults = mapOf("web" to PlatformConfig(toolSets = listOf("from_b"))),
      ),
      source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/depB"),
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
    val consumerTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "tie_consumer",
        target = TrailmapTargetConfig(displayName = "Tie", platforms = consumerOwnPlatforms),
        dependencies = listOf("depA", "depB"),
      ),
      source = TrailmapSource.Filesystem(newDir("tie_consumer")),
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
      resolvedTargets = listOf(consumerTrailmap.target!!),
      resolvedTrailmaps = listOf(depA, depB, consumerTrailmap),
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
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "addex",
        target = TrailmapTargetConfig(displayName = "AddEx", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("addex")),
      target = AppTargetYamlConfig(id = "addex", displayName = "AddEx", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
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
  fun `agent toolbox renders per-driver and excludes driver-incompatible tools`() {
    // A web-only target declaring tool_sets that an Android-only always_enabled toolset
    // also defines (e.g. `core_interaction` for `tap`/`swipe`/`launchApp`) must not surface
    // those Android-bound tools in the web driver's agent-toolbox view. Before the fix,
    // `TrailblazeToolSetCatalog.resolve` admitted every always_enabled entry regardless of
    // `compatibleDriverTypes` and the report leaked `tap`/`launchApp`/`android_adbShell`
    // into wikipedia (web) and contacts (iOS) reports. Drives the report against the real
    // classpath catalog so the test catches catalog drift too.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.web"),
        toolSets = listOf("web_core", "memory"),
        drivers = listOf("playwright-native"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "weblike",
        target = TrailmapTargetConfig(displayName = "WebLike", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("weblike")),
      target = AppTargetYamlConfig(id = "weblike", displayName = "WebLike", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "weblike.report.md").readText()
    val toolboxSection = report
      .substringAfter("## Agent toolbox (what the LLM sees at session start)")
      .substringBefore("## Resolution trace")

    // Renders per-driver — `playwright-native` heading must appear under the web platform.
    assertTrue("expected per-driver heading `playwright-native`, got:\n$toolboxSection") {
      toolboxSection.contains("#### Driver `playwright-native`")
    }

    // The leaks the fix is closing: these tools come from Android/iOS-bound always_enabled
    // toolsets (`core_interaction`, `android_framework`) and must NOT be surfaced for a
    // Playwright driver session.
    val mustNotLeak = listOf(
      "tap", "tapOnPoint", "swipe", "launchApp", "inputText", "hideKeyboard",
      "android_adbShell", "android_sendBroadcast", "mobile_listInstalledApps",
      "mobile_pasteClipboard", "mobile_setClipboard", "scrollUntilTextIsVisible",
      "networkConnection", "pressKey", "openUrl", "takeSnapshot", "wait",
    )
    for (leaked in mustNotLeak) {
      assertFalse(
        "`$leaked` is Android/iOS-only and must NOT appear in a Playwright agent toolbox, got:\n$toolboxSection",
      ) {
        // Match `- $leaked` at a bullet boundary (either bare backtick or sidecar-linkified
        // form) so substring matches like `web_wait` don't false-positive on a check for
        // `wait`. Sidecar-linked rows are `- [\`name\`](weblike/tools/name.md)` for this
        // target.
        toolboxSection.lineSequence().any { line ->
          val t = line.trim()
          t == "- `$leaked`" ||
            t == "- [`$leaked`](weblike/tools/$leaked.md)" ||
            t == "- `$leaked` (YAML-defined)" ||
            t == "- [`$leaked`](weblike/tools/$leaked.md) (YAML-defined)" ||
            t == "- `$leaked` (individual `tools:` addition)" ||
            t == "- [`$leaked`](weblike/tools/$leaked.md) (individual `tools:` addition)"
        }
      }
    }

    // Presence: a regression that filtered out *everything* would silently pass the
    // absence checks above. Assert that the visible compatible tools from the declared
    // `web_core` and `memory` toolsets actually render under the playwright driver
    // heading. Pick one representative from each so a single missing tool fails loudly.
    val mustBePresent = listOf("web_click", "web_navigate", "rememberText")
    for (expected in mustBePresent) {
      assertTrue(
        "`$expected` must appear in a Playwright agent toolbox under web/playwright-native, got:\n$toolboxSection",
      ) {
        toolboxSection.lineSequence().any { line ->
          val t = line.trim()
          t == "- `$expected`" ||
            t == "- [`$expected`](weblike/tools/$expected.md)" ||
            t == "- `$expected` (YAML-defined)" ||
            t == "- [`$expected`](weblike/tools/$expected.md) (YAML-defined)"
        }
      }
    }
  }

  @Test
  fun `agent toolbox renders empty-state when drivers is explicitly empty`() {
    // Explicit `drivers: []` (empty list) is distinct from `drivers: null`: the platform
    // is declared but pins no specific driver, so the LLM is never told about any tool
    // from that platform's `tool_sets:`. The report must surface this clearly with the
    // empty-state line rather than silently rendering nothing.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.no-drivers"),
        toolSets = listOf("web_core"),
        drivers = emptyList(),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "no_drivers",
        target = TrailmapTargetConfig(displayName = "No Drivers", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("no_drivers")),
      target = AppTargetYamlConfig(
        id = "no_drivers",
        displayName = "No Drivers",
        platforms = platforms,
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "no_drivers.report.md").readText()
    val toolboxSection = report
      .substringAfter("## Agent toolbox (what the LLM sees at session start)")
      .substringBefore("## Resolution trace")
    assertTrue("expected empty-drivers line, got:\n$toolboxSection") {
      toolboxSection.contains("no drivers resolved for this platform")
    }
    // And: NO `#### Driver` subheading should appear since we resolved zero drivers.
    assertFalse("no Driver subheadings should render, got:\n$toolboxSection") {
      toolboxSection.contains("#### Driver")
    }
  }

  @Test
  fun `agent toolbox expands shorthand drivers entry`() {
    // `PlatformConfig.drivers` accepts shorthand tokens (`web`, `android`, `all`) via
    // `DriverTypeKey.resolve`. Before the shorthand-expansion fix, the report's
    // `resolveDriversForReport` matched tokens directly against `TrailblazeDriverType.yamlKey`
    // and dropped shorthands entirely, leaving the platform with zero driver slices and
    // a misleading "no drivers resolved" line — runtime/report divergence at the
    // resolver layer this PR claims to centralize.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.shorthand"),
        toolSets = listOf("web_core"),
        drivers = listOf("web"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "shorthand",
        target = TrailmapTargetConfig(displayName = "Shorthand", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("shorthand")),
      target = AppTargetYamlConfig(
        id = "shorthand",
        displayName = "Shorthand",
        platforms = platforms,
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "shorthand.report.md").readText()
    val toolboxSection = report
      .substringAfter("## Agent toolbox (what the LLM sees at session start)")
      .substringBefore("## Resolution trace")
    // Both playwright drivers should be rendered, since the `web` shorthand expands to
    // them (Revyl drivers are excluded from the platform shorthand by `DriverTypeKey`).
    assertTrue("expected playwright-native heading from `web` shorthand, got:\n$toolboxSection") {
      toolboxSection.contains("#### Driver `playwright-native`")
    }
    assertTrue("expected playwright-electron heading from `web` shorthand, got:\n$toolboxSection") {
      toolboxSection.contains("#### Driver `playwright-electron`")
    }
    // And: a representative web tool must be rendered under at least one of those
    // headings (proves the resolve step ran, not just that the headings drew). The tool
    // name lands in a sidecar-linkified cell `[\`web_click\`](shorthand/tools/web_click.md)`.
    assertTrue("expected `web_click` under at least one driver, got:\n$toolboxSection") {
      toolboxSection.contains("- [`web_click`](shorthand/tools/web_click.md)") ||
        toolboxSection.contains("- `web_click`")
    }
  }

  @Test
  fun `empty tool_sets list materializes platform in runtime registry`() {
    // `tool_sets: []` (explicit empty list) must materialize the `web` platform in the
    // runtime-registry section — mirrors TrailmapRuntimeRegistryResolver.contribute, which
    // seeds the platform key even for an empty list so callers can distinguish "declared
    // but empty" from "never mentioned."
    val platforms = mapOf(
      "web" to PlatformConfig(appIds = listOf("com.example.empty"), toolSets = emptyList()),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "empty_ts",
        target = TrailmapTargetConfig(displayName = "Empty TS", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("empty_ts")),
      target = AppTargetYamlConfig(id = "empty_ts", displayName = "Empty TS", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
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

  @Test
  fun `YAML-defined tool added directly via tools list gets a linkified sidecar`() {
    // Regression for the codex-bot finding on PR #3318: pre-fix, `collectToolDetails` only
    // emitted a YAML sidecar when the tool name was also listed in some catalog toolset's
    // `yamlToolNames`. A YAML-defined tool added directly via `platforms.<p>.tools:` (which
    // the runtime resolver accepts via `ToolNameResolver.resolveYamlNameOrNull`) was left
    // un-linkified in the matrix and never got a sidecar — the report would name the tool
    // but offer no detail page, even though the YAML config is right there. `eraseText` is
    // a real framework YAML-defined tool (see trails/config/tools/eraseText.tool.yaml)
    // that ships in `core_interaction` only on mobile; if a target adds it to a web
    // platform's `tools:` directly (rare but legal), the sidecar must still be emitted.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.yaml-direct"),
        tools = listOf("eraseText"),
        drivers = listOf("playwright-native"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "yamldirect",
        target = TrailmapTargetConfig(displayName = "YamlDirect", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("yamldirect")),
      target = AppTargetYamlConfig(id = "yamldirect", displayName = "YamlDirect", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "yamldirect.report.md").readText()

    // The `tools:` addition row must be linkified — not the bare backtick form that signals
    // "no metadata available."
    assertTrue("expected linkified eraseText cell, got:\n$report") {
      report.contains("[`eraseText`](yamldirect/tools/eraseText.md) (individual `tools:` addition)")
    }
    // And the sidecar file itself must exist with the YAML-defined source metadata.
    val sidecar = File(outDir, "yamldirect/tools/eraseText.md")
    assertTrue("expected sidecar for direct YAML addition at $sidecar") { sidecar.exists() }
    val sidecarText = sidecar.readText()
    assertTrue("sidecar should declare YAML-defined source, got:\n$sidecarText") {
      sidecarText.contains("- Kind: YAML-defined") && sidecarText.contains("- Tool id: `eraseText`")
    }
  }

  @Test
  fun `scripted tool sidecar renders required and optional parameters from input schema`() {
    val makePost = InlineScriptToolConfig(
      script = "./tools/makePost.ts",
      name = "makePost",
      description = "Authors a new post.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
          "properties",
          buildJsonObject {
            put(
              "title",
              buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Post headline."))
              },
            )
            put(
              "draft",
              buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Mark as draft."))
              },
            )
          },
        )
        put(
          "required",
          kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("title")) },
        )
      },
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "blog",
        target = TrailmapTargetConfig(displayName = "Blog"),
      ),
      source = TrailmapSource.Filesystem(newDir("blog")),
      target = AppTargetYamlConfig(
        id = "blog",
        displayName = "Blog",
        tools = listOf(makePost),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )

    val sidecar = File(outDir, "blog/tools/makePost.md").readText()
    assertTrue("sidecar should carry the description") { sidecar.contains("Authors a new post.") }
    assertTrue("required param should be listed with type") {
      sidecar.contains("### Required parameters") &&
        sidecar.contains("- `title` — `string`") &&
        sidecar.contains("Post headline.")
    }
    assertTrue("optional param should be listed with type") {
      sidecar.contains("### Optional parameters") &&
        sidecar.contains("- `draft` — `boolean`") &&
        sidecar.contains("Mark as draft.")
    }
    assertTrue("sidecar should declare the current return shape") {
      sidecar.contains("Returns: `string` (opaque text content)")
    }
  }

  @Test
  fun `stale sidecar files for renamed tools are pruned but hand-authored files survive`() {
    val outDir = newDir("out")
    val toolsDir = File(outDir, "renames/tools").apply { mkdirs() }
    val stale = File(toolsDir, "removed_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # `removed_tool`
        """.trimIndent(),
      )
    }
    val handAuthored = File(toolsDir, "hand_authored.md").apply {
      writeText("# Hand-authored, must survive\n")
    }

    val currentTool = InlineScriptToolConfig(
      script = "./tools/current.ts",
      name = "current_tool",
      description = "Still here.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "renames",
        target = TrailmapTargetConfig(displayName = "Renames"),
      ),
      source = TrailmapSource.Filesystem(newDir("renames")),
      target = AppTargetYamlConfig(
        id = "renames",
        displayName = "Renames",
        tools = listOf(currentTool),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )

    assertFalse("stale emitter-owned sidecar should be deleted") { stale.exists() }
    assertTrue("hand-authored sidecar without the banner must survive") { handAuthored.exists() }
    assertTrue("current sidecar should have been written") {
      File(outDir, "renames/tools/current_tool.md").exists()
    }
  }

  @Test
  fun `orphan sidecar directory for a removed target is cleaned up`() {
    val outDir = newDir("out")
    val staleDir = File(outDir, "removed/tools").apply { mkdirs() }
    val staleSidecar = File(staleDir, "stale_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # `stale_tool`
        """.trimIndent(),
      )
    }

    val currentTrailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "kept",
        target = TrailmapTargetConfig(displayName = "Kept"),
      ),
      source = TrailmapSource.Filesystem(newDir("kept")),
      target = AppTargetYamlConfig(id = "kept", displayName = "Kept"),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(currentTrailmap.target!!),
      resolvedTrailmaps = listOf(currentTrailmap),
      outputDir = outDir,
    )

    assertFalse("stale sidecar file under the removed target must be deleted") { staleSidecar.exists() }
    assertFalse("the removed target's empty tools dir should be cleaned up") { staleDir.exists() }
  }

  // ── Tool availability matrix ────────────────────────────────────────────────────────

  @Test
  fun `availability matrix renders one column per (platform, driver) with linkified tool cells`() {
    // Web target declaring both Playwright drivers — matrix should have two driver columns.
    // Tools from `web_core` resolve ✅ under both; the matrix is the at-a-glance view that
    // mirrors the legacy internal `TARGET_<id>.md` matrix shape inside the workspace
    // report (so `trailblaze check` is the single source of truth, no Gradle).
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.matrix"),
        toolSets = listOf("web_core"),
        drivers = listOf("playwright-native", "playwright-electron"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "matrix",
        target = TrailmapTargetConfig(displayName = "Matrix", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("matrix")),
      target = AppTargetYamlConfig(id = "matrix", displayName = "Matrix", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )

    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val report = File(outDir, "matrix.report.md").readText()
    val matrixSection = report
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")

    assertTrue("section heading present, got:\n$report") { report.contains("## Tool availability matrix") }
    // Header row carries one column per (platform, driver) — the legacy `TARGET_<id>.md`
    // header shape `<driver> (<PLATFORM>)`. Both drivers must appear.
    assertTrue("playwright-native column header, got:\n$matrixSection") {
      matrixSection.contains("playwright-native (WEB)")
    }
    assertTrue("playwright-electron column header, got:\n$matrixSection") {
      matrixSection.contains("playwright-electron (WEB)")
    }
    // A representative `web_core` tool must show ✅ under both drivers AND be linkified to
    // its sidecar (proves cells use the same toolCell() helper as the bullet list).
    val webClickRow = matrixSection.lines().firstOrNull { it.startsWith("| [`web_click`](matrix/tools/web_click.md)") }
    assertTrue("expected linkified web_click row, got matrix:\n$matrixSection") { webClickRow != null }
    // Two ✅ cells in the row (one per driver). The row format is
    // `| <link> | web_core | ✅ | ✅ |` so two pipe-separated ✅ cells must be present.
    assertTrue("expected ✅ under both drivers for web_click, got:\n$webClickRow") {
      webClickRow!!.split("|").map { it.trim() }.count { it == "✅" } == 2
    }
    assertTrue("web_core toolset attribution must appear on web_click's row") {
      webClickRow!!.contains("web_core")
    }
  }

  @Test
  fun `availability matrix marks excluded tools with cross instead of check`() {
    // Excluded-tools semantics: a tool reachable through the toolset closure but listed in
    // `excluded_tools:` renders as ❌ rather than blank, so a reader can tell "this driver
    // exposes the toolset but the target opted out of this specific tool."
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.exclude"),
        toolSets = listOf("web_core"),
        drivers = listOf("playwright-native"),
        excludedTools = listOf("web_click"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "exclude",
        target = TrailmapTargetConfig(displayName = "Exclude", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("exclude")),
      target = AppTargetYamlConfig(id = "exclude", displayName = "Exclude", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "exclude.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    val webClickRow = matrixSection.lines().firstOrNull { it.contains("`web_click`") }
    assertTrue("expected web_click row to exist (excluded tools still surface), got:\n$matrixSection") {
      webClickRow != null
    }
    assertTrue("web_click must show ❌ under playwright-native, got:\n$webClickRow") {
      webClickRow!!.contains("❌")
    }
    assertFalse("excluded web_click must NOT show ✅, got:\n$webClickRow") {
      webClickRow!!.contains("✅")
    }
  }

  @Test
  fun `availability matrix shows blank cell when driver does not surface the tool`() {
    // A target with two platforms (web + android) declaring driver-incompatible toolsets
    // for each — `web_core` tools should be blank under the Android driver column, and
    // `core_interaction` mobile tools blank under the Playwright column. This pins the
    // tri-state cell semantics: ✅ included / ❌ excluded / blank not-applicable.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.dual.web"),
        toolSets = listOf("web_core"),
        drivers = listOf("playwright-native"),
      ),
      "android" to PlatformConfig(
        appIds = listOf("com.example.dual.android"),
        toolSets = listOf("core_interaction"),
        drivers = listOf("android-ondevice-instrumentation"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "dualplatform",
        target = TrailmapTargetConfig(displayName = "Dual", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("dualplatform")),
      target = AppTargetYamlConfig(id = "dualplatform", displayName = "Dual", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "dualplatform.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    // web_click under playwright-native ✅; under android-ondevice-instrumentation blank.
    val webClickRow = matrixSection.lines().firstOrNull { it.contains("`web_click`") }
    assertTrue("expected web_click row, got matrix:\n$matrixSection") { webClickRow != null }
    // Driver columns are alphabetical by platform key (matches the Agent toolbox section's
    // `toSortedSet()` ordering) — so the column order is `android-ondevice-instrumentation
    // (ANDROID) | playwright-native (WEB)`. Identify each cell by header rather than by
    // brittle positional index so a future ordering tweak doesn't silently slip.
    val headerRow = matrixSection.lines().first { it.startsWith("| Tool ") }
    val headers = headerRow.split("|").map { it.trim() }
    val androidIdx = headers.indexOfFirst { it.startsWith("android-ondevice-instrumentation") }
    val webIdx = headers.indexOfFirst { it.startsWith("playwright-native") }
    val cells = webClickRow!!.split("|").map { it.trim() }
    assertEquals("✅", cells[webIdx], "expected ✅ under playwright-native for web_click")
    assertEquals("", cells[androidIdx], "expected blank under android driver for web_click")
  }

  @Test
  fun `availability matrix includes scripted tools labeled with their script filename`() {
    // Scripted tools sit at target-root scope and apply to every driver this target
    // supports. The matrix must render them with a `script:<filename>` toolset label
    // and ✅ under every driver column — matching `TargetToolBaselineGenerator`'s
    // default behavior.
    val createPost = InlineScriptToolConfig(
      script = "./tools/createPost.ts",
      name = "createPost",
      description = "Author a post.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.scripted"),
        toolSets = listOf("web_core"),
        drivers = listOf("playwright-native"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "scripted",
        target = TrailmapTargetConfig(displayName = "Scripted", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("scripted")),
      target = AppTargetYamlConfig(
        id = "scripted",
        displayName = "Scripted",
        platforms = platforms,
        tools = listOf(createPost),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "scripted.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    val createPostRow = matrixSection.lines().firstOrNull { it.contains("`createPost`") }
    assertTrue("expected scripted createPost row, got:\n$matrixSection") { createPostRow != null }
    assertTrue("scripted toolset label must be `script:createPost.ts`, got:\n$createPostRow") {
      createPostRow!!.contains("script:createPost.ts")
    }
    assertTrue("scripted createPost must show ✅ under playwright-native, got:\n$createPostRow") {
      createPostRow!!.contains("✅")
    }
  }

  @Test
  fun `availability matrix renders empty-state when no drivers and no scripted tools resolve`() {
    // Empty `drivers: []` + no scripted tools = nothing to put in the matrix. The section
    // should still render but with the empty-state line, not silently omit (which would
    // make the absence ambiguous between "no matrix needed" and "matrix broke").
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.empty"),
        toolSets = listOf("web_core"),
        drivers = emptyList(),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "emptymatrix",
        target = TrailmapTargetConfig(displayName = "Empty", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("emptymatrix")),
      target = AppTargetYamlConfig(id = "emptymatrix", displayName = "Empty", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "emptymatrix.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    assertTrue("expected empty-state message in matrix section, got:\n$matrixSection") {
      matrixSection.contains("_(empty matrix")
    }
  }

  @Test
  fun `availability matrix narrows scripted tools by supportedPlatforms metadata`() {
    // Regression for the Copilot / codex-bot finding on PR #3326: pre-fix, the matrix
    // unconditionally marked every scripted tool ✅ across every driver column, ignoring
    // the per-tool `_meta["trailblaze/supportedPlatforms"]` filter the runtime registration
    // path applies. A tool declared `supportedPlatforms: [android]` must NOT show ✅ under
    // a Web driver — that would mislead the reader about where the tool actually lands.
    val androidOnlyTool = InlineScriptToolConfig(
      script = "./tools/androidOnly.ts",
      name = "androidOnly",
      description = "Android-only scripted tool.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
      meta = buildJsonObject {
        put(
          "trailblaze/supportedPlatforms",
          buildJsonArray { add(JsonPrimitive("android")) },
        )
      },
    )
    val crossPlatformTool = InlineScriptToolConfig(
      script = "./tools/anywhere.ts",
      name = "anywhere",
      description = "Driver-agnostic scripted tool — no metadata filter.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val platforms = mapOf(
      "android" to PlatformConfig(
        appIds = listOf("com.example.scoped"),
        toolSets = listOf("memory"),
        drivers = listOf("android-ondevice-instrumentation"),
      ),
      "web" to PlatformConfig(
        appIds = listOf("com.example.scoped.web"),
        toolSets = listOf("memory"),
        drivers = listOf("playwright-native"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "scoped",
        target = TrailmapTargetConfig(displayName = "Scoped", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("scoped")),
      target = AppTargetYamlConfig(
        id = "scoped",
        displayName = "Scoped",
        platforms = platforms,
        tools = listOf(androidOnlyTool, crossPlatformTool),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "scoped.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    val headerRow = matrixSection.lines().first { it.startsWith("| Tool ") }
    val headers = headerRow.split("|").map { it.trim() }
    val androidIdx = headers.indexOfFirst { it.startsWith("android-ondevice-instrumentation") }
    val webIdx = headers.indexOfFirst { it.startsWith("playwright-native") }

    // androidOnly: ✅ under Android, blank under Playwright (the supportedPlatforms filter
    // narrowed the applicable columns).
    val androidOnlyRow = matrixSection.lines().first { it.contains("`androidOnly`") }
    val androidOnlyCells = androidOnlyRow.split("|").map { it.trim() }
    assertEquals("✅", androidOnlyCells[androidIdx], "androidOnly must show ✅ under Android")
    assertEquals("", androidOnlyCells[webIdx], "androidOnly must NOT show ✅ under Web (supportedPlatforms filter)")

    // anywhere: no metadata → ✅ everywhere (default fallback path).
    val anywhereRow = matrixSection.lines().first { it.contains("`anywhere`") }
    val anywhereCells = anywhereRow.split("|").map { it.trim() }
    assertEquals("✅", anywhereCells[androidIdx], "anywhere (no metadata) must show ✅ under Android")
    assertEquals("✅", anywhereCells[webIdx], "anywhere (no metadata) must show ✅ under Web")
  }

  @Test
  fun `availability matrix renders empty-state for a scripted-only target with no drivers`() {
    // Regression for the Copilot finding on PR #3326: pre-fix, when `driverColumns` was
    // empty but scripted tools existed, the matrix produced a malformed table with zero
    // driver columns (`| Tool | Toolset(s) |` header followed by rows with trailing `| |`).
    // Render an empty-state instead, listing any target-root scripted tools so the reader
    // sees what would dispatch if a driver were configured.
    val scriptedOnly = InlineScriptToolConfig(
      script = "./tools/orphan.ts",
      name = "orphanTool",
      description = "Scripted tool with no driver attached at the target.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.scripted-only"),
        toolSets = listOf("web_core"),
        drivers = emptyList(), // explicit empty — no driver columns resolved
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "scriptedonly",
        target = TrailmapTargetConfig(displayName = "ScriptedOnly", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("scriptedonly")),
      target = AppTargetYamlConfig(
        id = "scriptedonly",
        displayName = "ScriptedOnly",
        platforms = platforms,
        tools = listOf(scriptedOnly),
      ),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "scriptedonly.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    // Must NOT contain a malformed table header (header with `| Tool | Toolset(s) |` and
    // nothing else after).
    assertFalse("must not render a zero-driver-column table header, got:\n$matrixSection") {
      matrixSection.lines().any { it.trim() == "| Tool | Toolset(s) |" }
    }
    // Must list the scripted tool name in the empty-state message so the reader still
    // knows what would dispatch if a driver were configured.
    assertTrue("expected the scripted tool listed in the empty-state message, got:\n$matrixSection") {
      matrixSection.contains("`orphanTool`")
    }
    assertTrue("expected explicit no-drivers empty-state language, got:\n$matrixSection") {
      matrixSection.contains("no drivers resolved")
    }
  }

  @Test
  fun `availability matrix renders bare backticks for tools without sidecar metadata`() {
    // Regression for the lead-dev review finding (LR#5): if a tool name is added via
    // `platforms.<p>.tools:` but isn't in any toolset and has no class-backed / yaml-defined
    // config (e.g. a future tool source the matrix surfaces but `collectToolDetails`
    // doesn't classify), the matrix should render the bare backtick form rather than a
    // broken link. Pin both shapes so a `toolCell()` regression that always emits links
    // would trip this.
    val platforms = mapOf(
      "web" to PlatformConfig(
        appIds = listOf("com.example.bare"),
        tools = listOf("ghost_tool_no_metadata"),
        drivers = listOf("playwright-native"),
      ),
    )
    val trailmap = ResolvedTrailmap(
      manifest = TrailblazeTrailmapManifest(
        id = "barecell",
        target = TrailmapTargetConfig(displayName = "Bare", platforms = platforms),
      ),
      source = TrailmapSource.Filesystem(newDir("barecell")),
      target = AppTargetYamlConfig(id = "barecell", displayName = "Bare", platforms = platforms),
      toolsets = emptyList(),
      tools = emptyList(),
      waypoints = emptyList(),
    )
    val outDir = newDir("out")
    ResolvedTargetReportEmitter.emit(
      resolvedTargets = listOf(trailmap.target!!),
      resolvedTrailmaps = listOf(trailmap),
      outputDir = outDir,
    )
    val matrixSection = File(outDir, "barecell.report.md").readText()
      .substringAfter("## Tool availability matrix")
      .substringBefore("## Resolution trace")
    val ghostRow = matrixSection.lines().firstOrNull { it.contains("ghost_tool_no_metadata") }
    assertTrue("expected ghost_tool_no_metadata row, got:\n$matrixSection") { ghostRow != null }
    // Must be the bare-backtick form, NOT the linkified `[`name`](path)` form — there's
    // no sidecar to link to.
    assertTrue("expected bare backtick form for unknown tool, got:\n$ghostRow") {
      ghostRow!!.contains("`ghost_tool_no_metadata`") &&
        !ghostRow.contains("[`ghost_tool_no_metadata`](")
    }
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("resolved-target-report-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }
}

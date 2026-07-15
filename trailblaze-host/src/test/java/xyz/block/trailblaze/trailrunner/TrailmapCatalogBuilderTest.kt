package xyz.block.trailblaze.trailrunner

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for [TrailmapCatalogBuilder.build] sourcing tools/trailheads from the same
 * resolved-config pipeline (`TrailblazeProjectConfigLoader.resolveRuntime`) that `trailblaze run`
 * itself uses to dispatch — not a hand-rolled regex parser that can silently disagree with the real
 * resolution about what counts as a trailhead (the bug this replaced; see #4439).
 */
class TrailmapCatalogBuilderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  // Mirrors ToolCatalogBuilderTest's assume-skip gate so these tests run wherever that suite does
  // (Hermit-pinned `bun` + a `bun install`'d SDK) and skip cleanly elsewhere instead of failing red.
  private fun assumeAnalyzerRunnable() {
    val bun = BunBinaryResolver.resolveBunBinary()
    assumeTrue("bun binary not found on PATH — see ScriptedToolDefinitionAnalyzerTest.", bun != null && bun.isFile)
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
    assumeTrue("SDK dir not resolvable.", sdkDir != null && sdkDir.isDirectory)
    val shim = sdkDir?.let { ScriptedToolDefinitionAnalyzer.resolveExtractorShim(it) }
    assumeTrue("extract-tool-defs.mjs not found.", shim != null && shim.isFile)
    assumeTrue(
      "ts-json-schema-generator not installed — run `bun install` under sdks/typescript.",
      File(sdkDir, "node_modules/ts-json-schema-generator").isDirectory,
    )
  }

  // A loose ambient `trailblaze` so fixtures can declare the real `trailblaze.tool<I, O>(spec, handler)`
  // shape the analyzer extracts, without needing the actual SDK resolvable from a temp dir.
  private fun declareTypedToolStub(): String =
    "declare const trailblaze: { tool: <I, O>(spec: any, handler: (input: I) => Promise<O>) => unknown };"

  private fun withWorkspace(configDir: File, block: () -> Unit) {
    val previous = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      block()
    } finally {
      WorkspaceConfigDirHolder.resolver = previous
    }
  }

  @Test
  fun `a scripted tool's inline trailhead is resolved via the real analyzer`() {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config")
    val trailmapDir = File(configDir, "trailmaps/demo").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo
      target:
        display_name: Demo
        tools:
          - demo_launchAppSignedIn
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "demo_launchAppSignedIn.ts").writeText(
      """
        |${declareTypedToolStub()}
        |interface I { email: string; }
        |interface O { ok: boolean; }
        |
        |export const demo_launchAppSignedIn = trailblaze.tool<I, O>(
        |  { trailhead: { to: "demo/home" } },
        |  async () => ({ ok: true }),
        |);
      """.trimMargin(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val demo = catalog.single { it.id == "demo" }

      assertTrue(
        demo.tools.any { it.name == "demo_launchAppSignedIn" },
        "expected demo_launchAppSignedIn in tools, got ${demo.tools}",
      )
      assertTrue(
        demo.trailheads.any { it.name == "demo_launchAppSignedIn" },
        "expected demo_launchAppSignedIn to also be unioned into trailheads, got ${demo.trailheads}",
      )
    }
  }

  @Test
  fun `a scripted tool with no trailhead block is not treated as a trailhead`() {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-no-trailhead")
    val trailmapDir = File(configDir, "trailmaps/demo2").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo2
      target:
        display_name: Demo2
        tools:
          - demo2_someTool
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "demo2_someTool.ts").writeText(
      """
        |${declareTypedToolStub()}
        |interface I { label: string; }
        |interface O { ok: boolean; }
        |
        |export const demo2_someTool = trailblaze.tool<I, O>(
        |  {},
        |  async () => ({ ok: true }),
        |);
      """.trimMargin(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val demo2 = catalog.single { it.id == "demo2" }

      assertTrue(demo2.tools.any { it.name == "demo2_someTool" })
      assertEquals(emptyList(), demo2.trailheads)
    }
  }

  @Test
  fun `a yaml-sourced trailhead and an inline scripted trailhead coexist`() {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-mixed")
    val trailmapDir = File(configDir, "trailmaps/demo3").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo3
      target:
        display_name: Demo3
        tools:
          - demo3_inlineTrailhead
      """.trimIndent(),
    )
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "demo3_yamlTrailhead.trailhead.yaml").writeText(
      """
      id: demo3_yamlTrailhead
      description: "existing sidecar trailhead"
      trailhead:
        to: "demo3/home"
      tools: []
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "demo3_inlineTrailhead.ts").writeText(
      """
        |${declareTypedToolStub()}
        |interface I { email: string; }
        |interface O { ok: boolean; }
        |
        |export const demo3_inlineTrailhead = trailblaze.tool<I, O>(
        |  { trailhead: { to: "demo3/other" } },
        |  async () => ({ ok: true }),
        |);
      """.trimMargin(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val demo3 = catalog.single { it.id == "demo3" }

      val trailheadNames = demo3.trailheads.map { it.name }
      assertTrue(
        trailheadNames.containsAll(listOf("demo3_yamlTrailhead", "demo3_inlineTrailhead")),
        "expected both the yaml-sourced and inline trailheads, got $trailheadNames",
      )
    }
  }

  @Test
  fun `a library trailmap's operational tools appear in the catalog`() {
    // Regression test for the resolveRuntime dependency-graph gap (see PR #4503's
    // includeOrphanTrailmaps): a library trailmap (no `target:` block) that nothing references via
    // `dependencies:` must still show up here — it's a real, authored trailmap for browsing
    // purposes even though it sits outside any target's dispatch graph.
    val configDir = tempFolder.newFolder("config-library")
    val hostTrailmapDir = File(configDir, "trailmaps/hostapp").apply { mkdirs() }
    File(hostTrailmapDir, "trailmap.yaml").writeText(
      """
      id: hostapp
      target:
        display_name: Host App
      """.trimIndent(),
    )
    val libTrailmapDir = File(configDir, "trailmaps/orphanlib").apply { mkdirs() }
    File(libTrailmapDir, "trailmap.yaml").writeText(
      """
      id: orphanlib
      """.trimIndent(),
    )
    val libToolsDir = File(libTrailmapDir, "tools").apply { mkdirs() }
    File(libToolsDir, "orphan_tool.tool.yaml").writeText(
      """
      id: orphan_tool
      class: com.example.tools.OrphanTool
      """.trimIndent(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val orphan = catalog.singleOrNull { it.id == "orphanlib" }
        ?: error("expected orphanlib in the catalog, got ids: ${catalog.map { it.id }}")
      assertTrue(orphan.tools.any { it.name == "orphan_tool" })
    }
  }

  @Test
  fun `a multi-tool script's relPath points at its real shared file, not a guessed one per tool name`() {
    // Regression test: InlineScriptToolConfig carries no source-path field, so a relPath
    // reconstructed from the tool's own name (e.g. "tools/<name>.ts") is wrong whenever one .ts
    // file exports more than one named tool — a real, common shape in this codebase's trailmaps.
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-multi-tool")
    val trailmapDir = File(configDir, "trailmaps/demo4").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo4
      target:
        display_name: Demo4
        tools:
          - demo4_first
          - demo4_second
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "demo4_shared.ts").writeText(
      """
        |${declareTypedToolStub()}
        |interface I { value: string; }
        |interface O { ok: boolean; }
        |
        |export const demo4_first = trailblaze.tool<I, O>({}, async () => ({ ok: true }));
        |export const demo4_second = trailblaze.tool<I, O>({}, async () => ({ ok: true }));
      """.trimMargin(),
    )
    // A multi-export .ts needs a sidecar descriptor naming each exported tool — the analyzer
    // needs the file loaded once and each entry registered, it doesn't infer names from
    // `export const` alone.
    File(toolsDir, "demo4_shared.yaml").writeText(
      """
      script: ./demo4_shared.ts
      tools:
        - name: demo4_first
          description: First tool.
        - name: demo4_second
          description: Second tool.
      """.trimIndent(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val demo4 = catalog.single { it.id == "demo4" }
      val expectedRelPath = "trails/config/trailmaps/demo4/tools/demo4_shared.ts"
      listOf("demo4_first", "demo4_second").forEach { name ->
        val component = demo4.tools.singleOrNull { it.name == name }
          ?: error("expected $name in tools, got ${demo4.tools}")
        assertEquals(expectedRelPath, component.relPath)
      }
    }
  }

  @Test
  fun `a nested operational YAML component keeps its real subdirectory in relPath`() {
    // Regression test: ToolYamlConfig carries no source-path field either, so a relPath
    // reconstructed from just the component's id (e.g. "shortcuts/<id>.shortcut.yaml") loses any
    // subdirectory nesting — a real, common shape (see trailmaps/square/shortcuts/web/*.yaml).
    val configDir = tempFolder.newFolder("config-nested")
    val trailmapDir = File(configDir, "trailmaps/demo5").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo5
      target:
        display_name: Demo5
      """.trimIndent(),
    )
    val nestedShortcutsDir = File(trailmapDir, "shortcuts/web").apply { mkdirs() }
    File(nestedShortcutsDir, "demo5_webShortcut.shortcut.yaml").writeText(
      """
      id: demo5_webShortcut
      shortcut:
        from: demo5/a
        to: demo5/b
      tools: []
      """.trimIndent(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      val demo5 = catalog.single { it.id == "demo5" }
      val component = demo5.tools.singleOrNull { it.name == "demo5_webShortcut" }
        ?: error("expected demo5_webShortcut in tools, got ${demo5.tools}")
      assertEquals("trails/config/trailmaps/demo5/shortcuts/web/demo5_webShortcut.shortcut.yaml", component.relPath)
    }
  }

  @Test
  fun `one broken trailmap does not blank the rest of the catalog`() {
    val configDir = tempFolder.newFolder("config-broken")
    val validTrailmapDir = File(configDir, "trailmaps/validpack").apply { mkdirs() }
    File(validTrailmapDir, "trailmap.yaml").writeText(
      """
      id: validpack
      target:
        display_name: Valid
      """.trimIndent(),
    )
    val brokenTrailmapDir = File(configDir, "trailmaps/brokenpack").apply { mkdirs() }
    File(brokenTrailmapDir, "trailmap.yaml").writeText(
      """
      id: brokenpack
      target:
        display_name: Broken
      toolsets:
        - toolsets/missing-toolset.yaml
      """.trimIndent(),
    )

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      assertTrue(
        catalog.any { it.id == "validpack" },
        "expected validpack to still resolve, got ${catalog.map { it.id }}",
      )
    }
  }

  @Test
  fun `platforms carries the sorted resolved platform keys, empty for library trailmaps`() {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-platforms")
    File(configDir, "trailmaps/multi").apply { mkdirs() }.let { dir ->
      File(dir, "trailmap.yaml").writeText(
        """
        id: multi
        target:
          display_name: Multi
          platforms:
            ios: {}
            android: {}
        """.trimIndent(),
      )
    }
    File(configDir, "trailmaps/lib").apply { mkdirs() }.let { dir ->
      File(dir, "trailmap.yaml").writeText("id: lib\n")
    }

    withWorkspace(configDir) {
      val catalog = TrailmapCatalogBuilder.build()
      // Sorted regardless of declaration order — the picker's web-only suppression reads this.
      assertEquals(listOf("android", "ios"), catalog.single { it.id == "multi" }.platforms)
      assertEquals(emptyList(), catalog.single { it.id == "lib" }.platforms)
    }
  }

  @Test
  fun `workspaceListed reflects the workspace targets allow-list`() {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-allowlist")
    fun declareTarget(id: String) {
      val dir = File(configDir, "trailmaps/$id").apply { mkdirs() }
      File(dir, "trailmap.yaml").writeText("id: $id\ntarget:\n  display_name: $id\n")
    }
    declareTarget("alpha")
    declareTarget("beta")

    withWorkspace(configDir) {
      // No trailblaze.yaml (or an empty/omitted targets list) = auto-discovery: everything listed.
      val open = TrailmapCatalogBuilder.build()
      assertTrue(open.single { it.id == "alpha" }.workspaceListed)
      assertTrue(open.single { it.id == "beta" }.workspaceListed)

      // A non-empty targets: allow-list flags everything outside it — the runtime would never
      // load those targets, so the Target picker must not offer them as activatable cards.
      File(configDir, "trailblaze.yaml").writeText("targets:\n  - alpha\n")
      val gated = TrailmapCatalogBuilder.build()
      assertTrue(gated.single { it.id == "alpha" }.workspaceListed)
      assertFalse(gated.single { it.id == "beta" }.workspaceListed)
    }
  }

  @Test
  fun `an inline trailhead carries its flavor and supported platforms, a yaml one carries neither`() {
    // The flavor tells pickers a parameter schema is fetchable (scripted-tool-params); platforms
    // (projected from the analyzer-enriched `_meta`) are the durable platform signal the
    // `_<platform>_` name heuristic stood in for.
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config-meta")
    val trailmapDir = File(configDir, "trailmaps/demo4").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: demo4
      target:
        display_name: Demo4
        tools:
          - demo4_signedIn
      """.trimIndent(),
    )
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "demo4_fresh.trailhead.yaml").writeText(
      """
      id: demo4_fresh
      description: "fresh install"
      trailhead:
        to: "demo4/home"
      tools: []
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "demo4_signedIn.ts").writeText(
      """
        |${declareTypedToolStub()}
        |interface I { email: string; }
        |interface O { ok: boolean; }
        |
        |export const demo4_signedIn = trailblaze.tool<I, O>(
        |  {
        |    supportedPlatforms: ["android", "ios"],
        |    trailhead: { dynamic: true },
        |  },
        |  async () => ({ ok: true }),
        |);
      """.trimMargin(),
    )

    withWorkspace(configDir) {
      val byName = TrailmapCatalogBuilder.build().single { it.id == "demo4" }.trailheads.associateBy { it.name }

      assertEquals(ToolFlavor.SCRIPTED, byName.getValue("demo4_signedIn").flavor)
      assertEquals(listOf("android", "ios"), byName.getValue("demo4_signedIn").platforms)
      assertEquals(null, byName.getValue("demo4_fresh").flavor)
      assertEquals(null, byName.getValue("demo4_fresh").platforms)
    }
  }
}

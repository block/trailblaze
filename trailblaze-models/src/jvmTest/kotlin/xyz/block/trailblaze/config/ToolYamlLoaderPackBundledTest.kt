package xyz.block.trailblaze.config

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * Pins the pack-bundled tool discovery added alongside the library/target pack
 * distinction. Tools that ship inside a pack at
 * `trailblaze-config/packs/<id>/{tools,shortcuts,trailheads}/<name>.<kind>.yaml`
 * must surface in the same global tool registry that the flat
 * `trailblaze-config/tools/` scan populates so toolset YAMLs and trail YAMLs can
 * reference them by bare id regardless of which pack ships them. Without this
 * hook, moving a tool YAML out of the flat dir into a pack subdirectory would
 * silently drop it from `ToolNameResolver` and break dependent toolsets.
 *
 * Each operational class lives under its own top-level dir:
 *  - `<pack>/tools/<name>.tool.yaml`
 *  - `<pack>/shortcuts/<name>.shortcut.yaml`
 *  - `<pack>/trailheads/<name>.trailhead.yaml`
 *
 * Subdirectories below each top-level dir are organizational only (any depth).
 *
 * Tests exercise the full [ToolYamlLoader.discoverAndLoadAll] entrypoint via a
 * classpath-fixture pattern (mirrors `ClasspathResourceDiscoveryRecursiveTest`).
 * Inline classloader swap rather than [xyz.block.trailblaze.testing.ClasspathFixture]
 * because that helper lives in `trailblaze-common`, which sits above
 * `trailblaze-models` in the dependency graph.
 */
class ToolYamlLoaderPackBundledTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `pack-bundled class-mode tool surfaces in discoverAndLoadAll`() {
    // FakeToolYamlLoaderTool lives next door in the same jvmTest source set; using it
    // here means Class.forName can actually resolve the FQCN. The discovery path
    // (classpath fixture below) walks the URLClassLoader at the temp root for resource
    // discovery, but `resolveToolClass` calls `Class.forName(fqcn)` which uses the
    // calling class's classloader (ToolYamlLoader's), so the test class on the regular
    // jvmTest classpath remains reachable.
    val root = newTempDir()
    val packToolsDir = File(root, "trailblaze-config/packs/sample-library/tools").apply { mkdirs() }
    File(packToolsDir, "sampleLibrary_saveItem.tool.yaml").writeText(
      """
      id: sampleLibrary_saveItem
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertTrue(
        ToolName("sampleLibrary_saveItem") in resolved,
        "Expected pack-bundled tool to surface in global registry, got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `pack-bundled shortcut tool surfaces in discoverYamlDefinedTools when tools-mode`() {
    val root = newTempDir()
    val packShortcutsDir = File(root, "trailblaze-config/packs/sample/shortcuts").apply { mkdirs() }
    File(packShortcutsDir, "go_to_home.shortcut.yaml").writeText(
      """
      id: go_to_home
      description: Navigate from somewhere to the home screen.
      shortcut:
        from: app/elsewhere
        to: app/home
      tools:
        - tap: { selector: "home_button" }
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverYamlDefinedTools()
      assertTrue(
        ToolName("go_to_home") in resolved,
        "Expected pack-bundled shortcut tool to surface, got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `pack-bundled scripted-tool YAML with bare yaml suffix is NOT picked up`() {
    // PackScriptedToolFile descriptors live in `<pack>/tools/<name>.yaml` (no operational
    // suffix) — they flow through the per-target `target.tools:` resolution, NOT the
    // global registry. Pin that the recursive scan deliberately ignores them so a clock-
    // pack-style scripted tool doesn't accidentally register as a class-backed tool.
    val root = newTempDir()
    val packToolsDir = File(root, "trailblaze-config/packs/clock/tools").apply { mkdirs() }
    File(packToolsDir, "clock_android_launchApp.yaml").writeText(
      """
      script: ./trails/config/tools/clock_android_launchApp.ts
      name: clock_android_launchApp
      description: Launch the Clock app.
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertEquals(emptyMap(), resolved)
    }
  }

  @Test
  fun `pack-bundled YAML in unrecognized top-level subdirectory is NOT picked up as a tool`() {
    // The convention layout pins each operational class to a single top-level dir:
    // `tools/`, `shortcuts/`, `trailheads/`. A YAML at some other path inside the pack
    // tree (e.g. a misplaced waypoint file with a `.tool.yaml` suffix) must not register
    // as a tool — discovery is intentionally narrow.
    val root = newTempDir()
    val miscDir = File(root, "trailblaze-config/packs/sample/misc").apply { mkdirs() }
    File(miscDir, "stray.tool.yaml").writeText(
      """
      id: stray
      class: com.example.StrayTool
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertEquals(emptyMap(), resolved)
    }
  }

  @Test
  fun `pack-bundled shortcut YAML under tools dir is NOT picked up`() {
    // Each suffix has exactly one home: `.shortcut.yaml` belongs under `shortcuts/`,
    // not `tools/`. Putting it in the wrong sibling dir causes discovery to silently
    // ignore it — symmetric with the misplaced-misc-dir case above.
    val root = newTempDir()
    val toolsDir = File(root, "trailblaze-config/packs/sample/tools").apply { mkdirs() }
    File(toolsDir, "misplaced.shortcut.yaml").writeText(
      """
      id: misplaced
      description: A shortcut placed in the wrong directory.
      shortcut:
        from: app/a
        to: app/b
      tools:
        - tap: { selector: "go" }
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverYamlDefinedTools()
      assertEquals(emptyMap(), resolved)
    }
  }

  @Test
  fun `pack-bundled YAML nested under tools subdirectory IS picked up`() {
    // The layout is `<pack-id>/tools/[<subdir>/...]<name>.tool.yaml` — any depth under
    // `tools/` is acceptable so authors can organize a pack's tool surface (e.g. by
    // platform: `tools/web/`, `tools/android/`, or by sub-flow: `tools/checkout/`).
    // The constraint is structural — must live under `tools/` — not a depth limit.
    val root = newTempDir()
    val nestedDir = File(root, "trailblaze-config/packs/sample/tools/subdir").apply { mkdirs() }
    File(nestedDir, "deeplyNested.tool.yaml").writeText(
      """
      id: deeplyNested
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertTrue(
        ToolName("deeplyNested") in resolved,
        "Expected deeplyNested tool in nested subdir to register, got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `pack-bundled YAML at multiple depths under tools subdirectory all register`() {
    // Pin: depth is unrestricted under each top-level dir. A pack can use shallow
    // (tools/web/, shortcuts/web/) and deep (tools/android/checkout/keypad/) nesting in
    // the same pack — subdirs are organizational only.
    val root = newTempDir()
    val webDir = File(root, "trailblaze-config/packs/sample/tools/web").apply { mkdirs() }
    File(webDir, "loginWeb.tool.yaml").writeText(
      """
      id: login_web
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )
    val deepDir = File(root, "trailblaze-config/packs/sample/tools/android/checkout/keypad")
      .apply { mkdirs() }
    File(deepDir, "tapDigit.tool.yaml").writeText(
      """
      id: tap_digit
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertTrue(ToolName("login_web") in resolved)
      assertTrue(ToolName("tap_digit") in resolved)
    }
  }

  @Test
  fun `pack-bundled shortcuts and trailheads in their own dirs surface alongside tools`() {
    // Pin the cross-class layout: a single pack with all three operational classes,
    // each in its own top-level dir, all register together.
    val root = newTempDir()
    val packDir = File(root, "trailblaze-config/packs/multi").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: multi
      target:
        display_name: Multi
      """.trimIndent(),
    )
    val toolsDir = File(packDir, "tools").apply { mkdirs() }
    File(toolsDir, "save.tool.yaml").writeText(
      """
      id: multi_save
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )
    val shortcutsDir = File(packDir, "shortcuts/web").apply { mkdirs() }
    File(shortcutsDir, "open_form.shortcut.yaml").writeText(
      """
      id: multi_open_form
      description: Open the form.
      shortcut:
        from: app/home
        to: app/form
      tools:
        - tap: { selector: "form_button" }
      """.trimIndent(),
    )
    val trailheadsDir = File(packDir, "trailheads/android").apply { mkdirs() }
    File(trailheadsDir, "launch.trailhead.yaml").writeText(
      """
      id: multi_launch
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      trailhead:
        to: app/home
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val classBacked = ToolYamlLoader.discoverAndLoadAll()
      val yamlDefined = ToolYamlLoader.discoverYamlDefinedTools()
      assertTrue(ToolName("multi_save") in classBacked, "tool: $classBacked")
      assertTrue(ToolName("multi_launch") in classBacked, "trailhead: $classBacked")
      assertTrue(ToolName("multi_open_form") in yamlDefined, "shortcut: $yamlDefined")
    }
  }

  @Test
  fun `same-basename tools in two different packs both register without collision`() {
    // Pin: keying the discovery map by full `<pack-id>/tools/<name>.<kind>` rather than
    // basename means two packs can ship a same-filename tool without one silently
    // overwriting the other before parse. Per-tool *id* uniqueness is then the
    // downstream cross-config invariant detected by `warnOnDuplicateIds`.
    val root = newTempDir()
    val packAToolsDir = File(root, "trailblaze-config/packs/pack-a/tools").apply { mkdirs() }
    File(packAToolsDir, "shared.tool.yaml").writeText(
      """
      id: pack_a_shared
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )
    val packBToolsDir = File(root, "trailblaze-config/packs/pack-b/tools").apply { mkdirs() }
    File(packBToolsDir, "shared.tool.yaml").writeText(
      """
      id: pack_b_shared
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertTrue(
        ToolName("pack_a_shared") in resolved && ToolName("pack_b_shared") in resolved,
        "Expected both same-basename pack tools to surface, got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `library pack trailhead tool is skipped at discovery time`() {
    // Pin: even if a library pack (no `target:` in pack.yaml) physically ships a
    // `*.trailhead.yaml` file under its `trailheads/` directory — i.e. the manifest-side
    // guard in `TrailblazeProjectConfigLoader.resolvePackSiblings` would never fire
    // because the file isn't manifest-declared — discovery still refuses to register
    // it. Symmetric with the manifest rule: trailheads bootstrap to a known waypoint
    // and only make sense within a target pack.
    val root = newTempDir()
    val packDir = File(root, "trailblaze-config/packs/library-only").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: library-only
      """.trimIndent(),
    )
    val trailheadsDir = File(packDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      trailhead:
        to: app/home
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertEquals(
        emptyMap(),
        resolved,
        "Library-pack trailhead tool must not register at discovery; got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `target pack trailhead tool registers normally at discovery`() {
    // Symmetric happy-path: a target pack (target: present) legitimately owns trailhead
    // tools, so the discovery-time guard must not fire here.
    val root = newTempDir()
    val packDir = File(root, "trailblaze-config/packs/target-pack").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: target-pack
      target:
        display_name: Target Pack
      """.trimIndent(),
    )
    val trailheadsDir = File(packDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: go_home
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      trailhead:
        to: app/home
      """.trimIndent(),
    )

    withClasspathRoot(root) {
      val resolved = ToolYamlLoader.discoverAndLoadAll()
      assertTrue(
        ToolName("go_home") in resolved,
        "Expected target-pack trailhead tool to surface, got: ${resolved.keys}",
      )
    }
  }

  @Test
  fun `pack-bundled tools surface when discovery is routed through a non-classpath ConfigResourceSource`() {
    // Regression for the on-device Android registration gap: pre-fix, discoverPackBundledToolContents
    // hardcoded ClasspathResourceDiscovery and ignored the injected resourceSource, so any non-JVM
    // source (AssetManager on Android instrumentation, in-memory test fixtures, etc.) saw the
    // pack-bundled hook as a no-op and tools were silently dropped.
    val source = object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> = emptyMap()
      override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> {
        if (directoryPath != "trailblaze-config/packs") return emptyMap()
        val packYaml = """
          id: target-pack
          target:
            display_name: Target Pack
        """.trimIndent()
        val toolYaml = """
          id: in_memory_tool
          class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
        """.trimIndent()
        return when (suffix) {
          "/pack.yaml" -> mapOf("target-pack/pack.yaml" to packYaml)
          ".tool.yaml" -> mapOf("target-pack/tools/in_memory_tool.tool.yaml" to toolYaml)
          else -> emptyMap()
        }
      }
    }

    val resolved = ToolYamlLoader.discoverAndLoadAll(resourceSource = source)
    assertTrue(
      ToolName("in_memory_tool") in resolved,
      "Expected pack-bundled tool from injected source to surface; got: ${resolved.keys}",
    )
  }

  private fun newTempDir(): File =
    createTempDirectory("tool-yaml-loader-pack-bundled-test").toFile().also { tempDirs += it }

  private fun withClasspathRoot(root: File, block: () -> Unit) {
    val classLoader = URLClassLoader(arrayOf(root.toURI().toURL()), null)
    val originalCcl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      block()
    } finally {
      Thread.currentThread().contextClassLoader = originalCcl
      classLoader.close()
    }
  }
}

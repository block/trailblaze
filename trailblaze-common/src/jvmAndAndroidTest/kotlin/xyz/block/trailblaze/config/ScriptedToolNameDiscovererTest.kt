package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [ScriptedToolNameDiscoverer] — the startup discovery that lets a toolset
 * reference a scripted (`.ts` / `.js`) tool by bare name.
 *
 * Fixtures are fed through an in-memory [ConfigResourceSource] keyed by trailmap-relative path
 * (`<trailmap-id>/...`), matching the `discoverAndLoadRecursive` contract.
 */
class ScriptedToolNameDiscovererTest {

  /** In-memory resource source: returns the fixtures whose key ends with [suffix]. */
  private fun resourceSourceOf(contents: Map<String, String>): ConfigResourceSource =
    object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
        emptyMap()

      override fun discoverAndLoadRecursive(
        directoryPath: String,
        suffix: String,
      ): Map<String, String> = contents.filterKeys { it.endsWith(suffix) }
    }

  @Test
  fun `single-tool descriptor with explicit name is discovered`() {
    val source = resourceSourceOf(
      mapOf(
        "trailblaze/tools/assertEquals.yaml" to """
          script: ./assertEquals.ts
          name: assertEquals
          description: Assert two interpolated values are equal.
        """.trimIndent(),
      ),
    )
    assertEquals(setOf(ToolName("assertEquals")), ScriptedToolNameDiscoverer.discoverAllNames(source))
  }

  @Test
  fun `multi-tool descriptor discovers every entry name`() {
    val source = resourceSourceOf(
      mapOf(
        "sampleapp/tools/sampleapp_reader.yaml" to """
          script: ./sampleapp_reader.ts
          tools:
            - name: sampleapp_connectReader
              description: Connect a fake reader.
            - name: sampleapp_removeCard
              description: Remove the inserted card.
        """.trimIndent(),
      ),
    )
    assertEquals(
      setOf(ToolName("sampleapp_connectReader"), ToolName("sampleapp_removeCard")),
      ScriptedToolNameDiscoverer.discoverAllNames(source),
    )
  }

  @Test
  fun `meta-only descriptor without a static name is skipped`() {
    // Name is analyzer-derived from the .ts — not knowable at startup, so it must not be
    // discoverable for toolset delivery.
    val source = resourceSourceOf(
      mapOf(
        "wikipedia/tools/wikipedia_typed_demo.yaml" to """
          script: ./wikipedia_typed_demo.ts
        """.trimIndent(),
      ),
    )
    assertTrue(ScriptedToolNameDiscoverer.discoverAllNames(source).isEmpty())
  }

  @Test
  fun `operational tool yaml suffixes under tools are excluded`() {
    // *.tool.yaml is owned by ToolYamlLoader (class-backed / YAML-defined tools), not scripted.
    val source = resourceSourceOf(
      mapOf(
        "trailblaze/tools/assertVisible.tool.yaml" to """
          id: assertVisible
          class: xyz.block.trailblaze.toolcalls.commands.AssertVisibleTrailblazeTool
        """.trimIndent(),
      ),
    )
    assertTrue(ScriptedToolNameDiscoverer.discoverAllNames(source).isEmpty())
  }

  @Test
  fun `plain yaml under tools that is not a scripted descriptor is skipped`() {
    // No `script:` field — won't decode as TrailmapScriptedToolFile, skipped leniently.
    val source = resourceSourceOf(
      mapOf(
        "trailblaze/tools/not_a_tool.yaml" to """
          some_other_key: value
        """.trimIndent(),
      ),
    )
    assertTrue(ScriptedToolNameDiscoverer.discoverAllNames(source).isEmpty())
  }

  @Test
  fun `yaml outside a tools directory is ignored`() {
    val source = resourceSourceOf(
      mapOf(
        "trailblaze/toolsets/memory.yaml" to """
          id: memory
          description: Memory toolset.
          tools:
            - assertEquals
        """.trimIndent(),
        "sampleapp/tools/sampleapp_reader.yaml" to """
          script: ./sampleapp_reader.ts
          name: sampleapp_connectReader
          description: Connect a fake reader.
        """.trimIndent(),
      ),
    )
    // Only the descriptor under `tools/` contributes; the toolset YAML is ignored here.
    assertEquals(
      setOf(ToolName("sampleapp_connectReader")),
      ScriptedToolNameDiscoverer.discoverAllNames(source),
    )
  }

  @Test
  fun `discovered names feed the resolver so a toolset can reference a scripted tool`() {
    val source = resourceSourceOf(
      mapOf(
        "myapp/tools/myapp_doThing.yaml" to """
          script: ./myapp_doThing.ts
          name: myapp_doThing
          description: Do the thing.
        """.trimIndent(),
      ),
    )
    val scriptedNames = ScriptedToolNameDiscoverer.discoverAllNames(source)
    val resolver = ToolNameResolver(
      knownTools = emptyMap(),
      knownScriptedToolNames = scriptedNames,
    )

    assertTrue(resolver.isKnown("myapp_doThing"))
    assertEquals(ToolName("myapp_doThing"), resolver.resolveScriptedNameOrNull("myapp_doThing"))

    val partitioned = resolver.partitionLenient(listOf("myapp_doThing", "definitelyUnknown"))
    assertTrue(partitioned.classBacked.isEmpty())
    assertTrue(partitioned.yamlDefinedNames.isEmpty())
    assertEquals(setOf(ToolName("myapp_doThing")), partitioned.scriptedToolNames)
    assertFalse(ToolName("definitelyUnknown") in partitioned.scriptedToolNames)
  }

  @Test
  fun `discoverDescriptorsByName fails fast when two descriptors claim the same name`() {
    val source = resourceSourceOf(
      mapOf(
        "appone/tools/shared.yaml" to """
          script: ./shared.ts
          name: shared_doThing
          description: One.
        """.trimIndent(),
        "apptwo/tools/shared.yaml" to """
          script: ./shared.ts
          name: shared_doThing
          description: Two.
        """.trimIndent(),
      ),
    )
    val error = assertFailsWith<IllegalArgumentException> {
      ScriptedToolNameDiscoverer.discoverDescriptorsByName(source)
    }
    assertTrue(
      error.message?.contains("shared_doThing") == true &&
        error.message?.contains("collision") == true,
      "Expected a name-collision diagnostic naming the tool, got: ${error.message}",
    )
  }

  @Test
  fun `bundleResourcePath builds a forward-slash classpath path shared by host and on-device`() {
    val discovered = ScriptedToolNameDiscoverer.DiscoveredDescriptor(
      relPath = "trailblaze/tools/frameworkToolCanary.yaml",
      descriptor = TrailmapScriptedToolFile(
        script = "./frameworkToolCanary.ts",
        name = "frameworkToolCanary",
      ),
    )
    val path = ScriptedToolNameDiscoverer.bundleResourcePath(discovered)
    assertTrue(path.startsWith(TrailblazeConfigPaths.TRAILMAPS_DIR), "got: $path")
    assertTrue(
      path.endsWith("/trailblaze/tools/frameworkToolCanary.bundle.js"),
      "expected the descriptor's parent + script base name with a .bundle.js suffix, got: $path",
    )
    assertFalse(path.contains('\\'), "path must use forward slashes only, got: $path")
  }

  @Test
  fun `bundleResourcePath rejects a blank script`() {
    val discovered = ScriptedToolNameDiscoverer.DiscoveredDescriptor(
      relPath = "x/tools/x.yaml",
      descriptor = TrailmapScriptedToolFile(script = "   ", name = "x"),
    )
    assertFailsWith<IllegalArgumentException> {
      ScriptedToolNameDiscoverer.bundleResourcePath(discovered)
    }
  }
}

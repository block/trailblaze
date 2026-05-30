package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import xyz.block.trailblaze.llm.config.ConfigResourceSource

/**
 * Pins the trailmap-scoped discovery contract on [ToolSetYamlLoader.discoverAndLoadAll]: it
 * walks every `trails/config/trailmaps/<id>/toolsets/<name>.yaml` recursively, keyed by the
 * full relPath so two trailmaps shipping the same filename don't clobber each other, and
 * filtered by the `segments[1] == "toolsets"` segment guard so sibling directories (like
 * `tools/`, `trailmap.yaml`) don't sneak into the toolset registry.
 */
class ToolSetYamlLoaderTest {

  /**
   * Fake [ConfigResourceSource] that returns pre-canned content for the recursive trailmaps
   * walk. Lets the test exercise the discovery path without standing up real classpath
   * fixtures.
   */
  private class FakeSource(
    private val trailmapsRecursive: Map<String, String> = emptyMap(),
  ) : ConfigResourceSource {
    override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> = emptyMap()

    override fun discoverAndLoadRecursive(
      directoryPath: String,
      suffix: String,
    ): Map<String, String> = if (directoryPath.endsWith("trailmaps")) trailmapsRecursive else emptyMap()
  }

  @Test
  fun `two trailmaps shipping the same toolset filename with different ids both load`() {
    // Copilot's lead-dev finding: keying trailmap-scoped entries by basename would silently
    // drop one when two trailmaps ship the same filename. Keying by full relPath lets both
    // reach loadAllFromYamlContents, where the id-level dedup distinguishes them.
    val trailmapAToolset = """
      id: toolset_in_a
      description: contributed by trailmap a
    """.trimIndent()
    val trailmapBToolset = """
      id: toolset_in_b
      description: contributed by trailmap b
    """.trimIndent()
    val source = FakeSource(
      trailmapsRecursive = mapOf(
        "a/toolsets/shared_filename.yaml" to trailmapAToolset,
        "b/toolsets/shared_filename.yaml" to trailmapBToolset,
      ),
    )

    val resolver = ToolNameResolver.fromToolClasses(emptySet())
    val result = ToolSetYamlLoader.discoverAndLoadAll(resolver, source)

    assertNotNull(result["toolset_in_a"], "trailmap-a's toolset must survive the merge")
    assertNotNull(result["toolset_in_b"], "trailmap-b's toolset must survive the merge")
  }

  @Test
  fun `only entries under toolsets segment are picked up from the trailmaps walk`() {
    // The recursive walk catches every `.yaml` under `trails/config/trailmaps/`, which
    // includes `trailmap.yaml`, `tools/*.tool.yaml`, etc. The segment-filter
    // (`segments[1] == "toolsets"`) is what keeps the toolset discovery from misclaiming
    // tool YAMLs. This test pins the filter.
    val realToolset = """
      id: real_toolset
      description: actual toolset
    """.trimIndent()
    val toolYaml = """
      id: not_a_toolset
      class: com.example.NotAToolset
    """.trimIndent()
    val source = FakeSource(
      trailmapsRecursive = mapOf(
        "my_trailmap/toolsets/real.yaml" to realToolset,
        "my_trailmap/tools/foo.tool.yaml" to toolYaml,
        "my_trailmap/trailmap.yaml" to "id: my_trailmap",
      ),
    )

    val resolver = ToolNameResolver.fromToolClasses(emptySet())
    val result = ToolSetYamlLoader.discoverAndLoadAll(resolver, source)

    assertNotNull(result["real_toolset"], "toolset under <id>/toolsets/ must be picked up")
    assertEquals(
      null,
      result["not_a_toolset"],
      "YAML under <id>/tools/ must NOT be treated as a toolset by this loader",
    )
    assertEquals(
      null,
      result["my_trailmap"],
      "<id>/trailmap.yaml must NOT be treated as a toolset by this loader",
    )
  }
}

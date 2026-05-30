package xyz.block.trailblaze.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool

data class FakeWorkspaceTool(val ignored: String = "") : TrailblazeTool

/**
 * Pins the contract on the new `resourceSource` parameter of
 * [ToolNameResolver.fromBuiltInAndCustomTools]: when a caller explicitly supplies a source,
 * the resolver's `knownTools` MUST come from that source — not from the JVM platform default.
 *
 * Without this test the new parameter is implicitly covered through the platform default in
 * every existing caller, so a future refactor that wired the factory to ignore the parameter
 * would silently regress docs-generator pinning (the canonical caller that depends on the
 * parameter to opt out of workspace bleed).
 */
class ToolNameResolverTest {

  private lateinit var tempRoot: File

  @BeforeTest
  fun setUp() {
    tempRoot = Files.createTempDirectory("tool-name-resolver-test").toFile()
  }

  @AfterTest
  fun tearDown() {
    tempRoot.deleteRecursively()
  }

  @Test
  fun `explicit resourceSource is honored — workspace-fixture tool resolves through it`() {
    // Write a workspace-style tool YAML pointing at a TrailblazeTool that lives in this test
    // module — the fixture's id ("fakeWorkspaceTool") deliberately doesn't collide with any
    // bundled tool name, so its presence in `knownTools` is unambiguous evidence the parameter
    // was actually routed through the discovery call.
    val toolsDir = File(tempRoot, "trailmaps/workspaceFakeTrailmap/tools").apply { mkdirs() }
    File(toolsDir, "fakeWorkspaceTool.tool.yaml").writeText(
      """
      id: fakeWorkspaceTool
      class: xyz.block.trailblaze.config.FakeWorkspaceTool
      """.trimIndent(),
    )
    // Trailmap manifest so the loader recognizes this as a real trailmap (and won't drop
    // any of its tools under the library-trailmap-trailhead guard for shortcuts/trailheads
    // — irrelevant here since this is a tool, but matches production layout).
    File(tempRoot, "trailmaps/workspaceFakeTrailmap/trailmap.yaml").writeText(
      "id: workspaceFakeTrailmap\n",
    )
    val workspaceLayered = CompositeConfigResourceSource(
      sources = listOf(ClasspathConfigResourceSource, FilesystemConfigResourceSource(tempRoot)),
    )

    val resolverWithWorkspace = ToolNameResolver.fromBuiltInAndCustomTools(
      resourceSource = workspaceLayered,
    )
    val resolverClasspathOnly = ToolNameResolver.fromBuiltInAndCustomTools(
      resourceSource = ClasspathConfigResourceSource,
    )

    val fixtureName = "fakeWorkspaceTool"
    val partitionedWithWorkspace = resolverWithWorkspace.partitionLenient(listOf(fixtureName))
    val partitionedClasspathOnly = resolverClasspathOnly.partitionLenient(listOf(fixtureName))

    assertTrue(
      partitionedWithWorkspace.classBacked.any { it == FakeWorkspaceTool::class },
      "workspace-layered resourceSource must surface the fixture tool through fromBuiltInAndCustomTools",
    )
    assertFalse(
      partitionedClasspathOnly.classBacked.any { it == FakeWorkspaceTool::class },
      "classpath-only resourceSource must NOT surface a filesystem-only fixture tool",
    )
  }
}

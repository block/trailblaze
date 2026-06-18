package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import java.nio.file.Files

/**
 * Unit tests for [InProcessScriptedToolLauncher], the one in-process QuickJS launch path shared by
 * the host runner and the MCP daemon. Exercises the no-engine [InProcessScriptedToolLauncher.describe]
 * path and the full launch → resolve → YAML-round-trip path against the framework `openUrl` scripted
 * tool, whose descriptor YAML + pre-compiled `.bundle.js` ship on the classpath via :trailblaze-common.
 */
class InProcessScriptedToolLauncherTest {

  private val openUrl = ToolName("openUrl")

  @Test
  fun `describe builds a descriptor for a framework scripted tool without launching an engine`() {
    val descriptors = InProcessScriptedToolLauncher.describe(setOf(openUrl))
    val descriptor = descriptors.firstOrNull { it.name == "openUrl" }
    assertNotNull(descriptor, "openUrl descriptor should be built from the catalog YAML")
    val params = descriptor.requiredParameters + descriptor.optionalParameters
    assertTrue(params.any { it.name == "url" }, "openUrl descriptor should expose its `url` parameter")
  }

  @Test
  fun `describe skips names with no catalog descriptor`() {
    val descriptors = InProcessScriptedToolLauncher.describe(setOf(ToolName("definitelyNotARealScriptedTool")))
    assertTrue(descriptors.isEmpty(), "unknown scripted tool names produce no descriptors")
  }

  @Test
  fun `describe returns nothing for an empty name set`() {
    assertTrue(InProcessScriptedToolLauncher.describe(emptySet()).isEmpty())
  }

  /**
   * Regression test for the host-driver YAML round-trip arg drop: the resolved scripted tool is a
   * `ContextSettingScriptedTool`, and the host-driver dispatch (e.g. iOS-host) YAML-encodes it and
   * re-runs it via `runYaml`. Before the wrapper implemented `RawArgumentTrailblazeTool`, the YAML
   * serializer hit its generic `::class.serializer()` fallback (the wrapper isn't `@Serializable`)
   * and dropped every argument — so `openUrl` re-decoded + executed with an empty `url`. This pins
   * that the round-trip now preserves the arguments.
   */
  @Test
  fun `a launched scripted tool survives a YAML round-trip with its args`() = runBlocking {
    val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
    val sessionDir = Files.createTempDirectory("inproc-launcher-test").toFile()
    var registrations: List<LazyYamlScriptedToolRegistration> = emptyList()
    try {
      registrations = InProcessScriptedToolLauncher.launch(
        toolRepo = toolRepo,
        sessionId = SessionId.sanitized("inproc-launcher-test"),
        sessionDir = sessionDir,
        toolNames = setOf(openUrl),
      )
      assertTrue(
        registrations.any { it.name == openUrl },
        "openUrl should launch in-process from its classpath bundle",
      )

      val resolved = toolRepo.toolCallToTrailblazeTool("openUrl", """{"url":"https://example.com"}""")

      // Encode exactly like the host-driver dispatch (TrailblazeMcpBridgeImpl) does before re-running.
      val yaml = createTrailblazeYaml().encodeToString(
        TrailblazeYamlBuilder().tools(listOf(resolved)).build(),
      )
      assertTrue(
        yaml.contains("https://example.com"),
        "YAML round-trip must preserve the scripted tool's `url` arg; got:\n$yaml",
      )
    } finally {
      registrations.forEach { runCatching { it.dispose() } }
      sessionDir.deleteRecursively()
    }
  }
}

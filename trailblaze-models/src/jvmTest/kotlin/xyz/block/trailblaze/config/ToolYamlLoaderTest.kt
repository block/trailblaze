package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool

data class FakeToolYamlLoaderTool(val ignored: String = "") : TrailblazeTool

class ToolYamlLoaderTest {

  @Test
  fun `loadFromConfigs skips invalid config entries instead of aborting all resolution`() {
    val configs = listOf(
      ToolYamlConfig(
        id = "validTool",
        toolClass = "xyz.block.trailblaze.config.FakeToolYamlLoaderTool",
      ),
      ToolYamlConfig(
        id = "invalidTool",
      ),
    )

    val loaded = ToolYamlLoader.loadFromConfigs(configs)

    assertEquals(setOf(ToolName("validTool")), loaded.keys)
    val resolvedClass = loaded[ToolName("validTool")]
    assertTrue(resolvedClass != null)
    assertTrue(TrailblazeTool::class.java.isAssignableFrom(resolvedClass.java))
  }
}

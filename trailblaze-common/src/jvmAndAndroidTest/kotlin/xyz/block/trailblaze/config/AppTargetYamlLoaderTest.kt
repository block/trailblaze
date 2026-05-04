package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTargetYamlLoaderTest {

  @Test
  fun `loadAllFromConfigs skips invalid target ids instead of aborting all targets`() {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    val configs = listOf(
      AppTargetYamlConfig(
        id = "valid-target",
        displayName = "Valid Target",
      ),
      AppTargetYamlConfig(
        id = "Invalid Target",
        displayName = "Broken Target",
      ),
    )

    val loaded = AppTargetYamlLoader.loadAllFromConfigs(
      configs = configs,
      toolNameResolver = resolver,
    )

    assertEquals(listOf("valid-target"), loaded.map { it.id })
  }
}

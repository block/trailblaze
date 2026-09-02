package xyz.block.trailblaze.android.test

import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestScrollUntilVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTypeTool
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.llm.config.bundledConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation

/**
 * The module's `tools/<name>.tool.yaml` descriptors have to be discoverable **on device**, which is a
 * different mechanism from the JVM one its sibling unit test exercises: Android cannot enumerate an
 * APK's Java resources by directory, so discovery reads them through `AssetManager`. A module that
 * ships the descriptors only as Java resources passes the unit test and resolves nothing here, and
 * a recorded `- androidTest_tap:` step then fails to deserialize at replay.
 *
 * Goes through `bundledConfigResourceSource()` — the same call the runtime makes — rather than
 * naming the asset source, so this asserts the contract rather than the implementation.
 */
class AndroidTestToolDescriptorOnDeviceTest {

  @Test
  fun toolNamesResolveToTheirClassesThroughOnDeviceDiscovery() {
    val discovered = ToolYamlLoader.discoverAndLoadAll(bundledConfigResourceSource())

    listOf(
      AndroidTestTapTool::class,
      AndroidTestTypeTool::class,
      AndroidTestAssertVisibleTool::class,
      AndroidTestScrollUntilVisibleTool::class,
    ).forEach { toolClass ->
      val name = toolClass.trailblazeToolClassAnnotation().name
      assertEquals(
        toolClass,
        discovered[ToolName(name)],
        "$name did not resolve to ${toolClass.simpleName} through on-device discovery. " +
          "Discovered ${discovered.size} tool(s): ${discovered.keys.map { it.toolName }.sorted()}",
      )
    }
  }
}

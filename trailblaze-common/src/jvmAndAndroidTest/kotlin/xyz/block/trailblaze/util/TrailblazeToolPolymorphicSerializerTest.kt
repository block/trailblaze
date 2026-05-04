package xyz.block.trailblaze.util

import org.junit.Test
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.assertEquals

/**
 * Regression coverage that survived the polymorphic dispatcher removal.
 *
 * The old tests in this file exercised `TrailblazeJsonInstance.encodeToString<TrailblazeTool>(...)`
 * and `decodeFromString<TrailblazeTool>(...)` — both intentionally non-functional now: tool
 * encoding goes through the tool's concrete class serializer (or `OtherTrailblazeTool` for the
 * persisted-log shape), and decoding routes by `toolName` via
 * `TrailblazeToolRepo.toolCallToTrailblazeTool`. Those behaviors have their own dedicated tests
 * (`DynamicToolSerializationTest`, `TrailblazeToolRepoTest`, etc.).
 */
class TrailblazeToolPolymorphicSerializerTest {

  /**
   * Regression guard for silent YAML → class drops. `ToolYamlLoader.discoverAndLoadAll()` logs a
   * warning and returns an incomplete map when a `trailblaze-config/tools/<name>.yaml` file has a
   * typo'd `class:` FQCN or the class has been renamed/moved without updating the YAML. Without an
   * explicit assertion, such regressions are invisible to the test suite and only surface at
   * runtime as "Could not find Trailblaze tool for name: ..." from the scripting callback
   * endpoint. Asserting one tool's mapping here is cheap and catches the whole failure class —
   * add future tools to this assertion if you want the same guarantee for them.
   */
  @Test
  fun mobileListInstalledAppsYamlResolvesToClass() {
    val discovered = ToolYamlLoader.discoverAndLoadAll()
    assertEquals(
      expected = ListInstalledAppsTrailblazeTool::class,
      actual = discovered[ToolName("mobile_listInstalledApps")],
      message = "mobile_listInstalledApps.yaml did not resolve to ListInstalledAppsTrailblazeTool. " +
        "Check `trailblaze-common/src/commonMain/resources/trailblaze-config/tools/mobile_listInstalledApps.yaml` " +
        "for a typo'd or stale `class:` FQCN.",
    )
  }
}

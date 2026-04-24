package xyz.block.trailblaze.util

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.test.assertEquals

@LLMDescription("A custom tool for tests")
@TrailblazeToolClass("testTrailblazeTool")
@Serializable
private data class TestTrailblazeTool(val x: Int, val y: Int) : TrailblazeTool

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

  @Test
  fun testSerialize() {
    val trailblazeTools = listOf(
      WaitForIdleSyncTrailblazeTool(),
      TapTrailblazeTool(
        ref = "y778",
        longPress = false,
        reasoning = "The Reason",
      ),
    )
    val normalJson = TrailblazeJsonInstance.encodeToString<List<TrailblazeTool>>(trailblazeTools)
    Console.log(normalJson)
  }

  @Test
  fun testDeserializeRoundtrip() {
    val tools: List<TrailblazeTool> = listOf(
      WaitForIdleSyncTrailblazeTool(),
      TapTrailblazeTool(
        ref = "y778",
        longPress = false,
        reasoning = "The Reason",
      ),
    )

    val json = TrailblazeJsonInstance.encodeToString(tools)
    Console.log("Encoded: $json")
    val decoded = TrailblazeJsonInstance.decodeFromString<List<TrailblazeTool>>(json)
    Console.log("Decoded: $decoded")

    assertEquals(
      expected = tools,
      actual = decoded,
    )
  }

  @Test
  fun testDeserialize() {
    val expectedTrailblazeTools = listOf(
      WaitForIdleSyncTrailblazeTool(),
      TapTrailblazeTool(
        ref = "y778",
        longPress = false,
        reasoning = "The Reason",
      ),
      OtherTrailblazeTool(toolName = "someTool", raw = JsonObject(mapOf("someKey" to JsonPrimitive("someValue")))),
      TestTrailblazeTool(10, 10),
    )
    val jsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
      ToolYamlLoader.discoverAndLoadAll() + mapOf(
        TestTrailblazeTool::class.toolName() to TestTrailblazeTool::class,
      ),
    )
    val encoded = jsonInstance.encodeToString<List<TrailblazeTool>>(expectedTrailblazeTools)
    Console.log("Encoded: $encoded")

    val decoded = jsonInstance.decodeFromString<List<TrailblazeTool>>(encoded)
    assertEquals(decoded, expectedTrailblazeTools)
  }

  @Test
  fun testSerializeOther() {
    val someInstance = TestTrailblazeTool(5, 5)
    val rawJson = TrailblazeJsonInstance.decodeFromString<JsonObject>(
      TrailblazeJsonInstance.encodeToString(someInstance),
    )
    val someTrailblazeTool = OtherTrailblazeTool(
      toolName = "someTool",
      raw = rawJson,
    )
    val json = TrailblazeJsonInstance.encodeToString(someTrailblazeTool)
    Console.log(json)
    assertEquals(
      json,
      """
    {
        "toolName": "someTool",
        "raw": {
            "x": 5,
            "y": 5
        }
    }
      """.trimIndent(),
    )
  }
}

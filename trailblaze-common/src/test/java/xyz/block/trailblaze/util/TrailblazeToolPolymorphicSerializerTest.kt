package xyz.block.trailblaze.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import kotlin.test.assertEquals

class TrailblazeToolPolymorphicSerializerTest {

  @Test
  fun testSerialize() {
    val trailblazeTools = listOf(
      WaitForIdleSyncTrailblazeTool(),
      TapOnElementByNodeIdTrailblazeTool(
        nodeId = 5,
        longPress = false,
        reason = "The Reason",
      ),
    )
    val normalJson = TrailblazeJsonInstance.encodeToString<List<TrailblazeTool>>(trailblazeTools)
    println(normalJson)
  }

  @Test
  fun testDeserialize() {
    val json = """
[
    {
        "class": "xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool"
    },
    {
        "class": "xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool",
        "nodeId": 5,
        "longPress": false,
        "reason": "The Reason"
    }
]

    """.trimIndent()
    val expectedTrailblazeTools = listOf(
      WaitForIdleSyncTrailblazeTool(),
      TapOnElementByNodeIdTrailblazeTool(
        nodeId = 5,
        longPress = false,
        reason = "The Reason",
      ),
    )
    val decoded = TrailblazeJsonInstance.decodeFromString<List<TrailblazeTool>>(json)

    assertEquals(decoded, expectedTrailblazeTools)
  }

  @Test
  fun testDeserializeOther() {
    val json = """
[
    {
        "class": "xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool"
    },
    {
        "class": "xyz.block.trailblaze.toolcalls.commands.SomeOtherCustomCommand",
        "x": 5,
        "y": 5
    }
]

    """.trimIndent()
    val expectedTrailblazeTools = listOf(
      WaitForIdleSyncTrailblazeTool(),
      OtherTrailblazeTool(
        raw = JsonObject(
          mapOf(
            "class" to JsonPrimitive("xyz.block.trailblaze.toolcalls.commands.SomeOtherCustomCommand"),
            "x" to JsonPrimitive(5),
            "y" to JsonPrimitive(5),
          ),
        ),

      ),
    )
    val decoded = TrailblazeJsonInstance.decodeFromString<List<TrailblazeTool>>(json)

    assertEquals(decoded, expectedTrailblazeTools)
  }
}

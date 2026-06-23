package xyz.block.trailblaze.mcp.utils

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end pin for the LLM-facing JSON-Schema rendering of an enum parameter. The descriptor
 * builders capture a scripted/MCP tool's JSON-Schema `enum` into
 * `TrailblazeToolParameterDescriptor.validValues`, and the koog converters lower that to a
 * [ToolParameterType.Enum]. This test closes the loop: a Koog `Enum` parameter must render back to
 * `{"type":"string","enum":[…]}` here — the shape the agent's LLM (and any MCP client) actually
 * sees. Without this, the upstream tests only prove the intermediate `ToolParameterType.Enum` is
 * produced, not that it survives all the way to the wire schema.
 */
class KoogToMcpExtTest {

  @Test fun `enum parameter renders to a string type with an enum constraint`() {
    val schema = ToolParameterDescriptor(
      name = "direction",
      description = "which direction",
      type = ToolParameterType.Enum(arrayOf("UP", "DOWN", "LEFT", "RIGHT")),
    ).toMcpJsonSchemaObject()

    assertEquals("string", (schema["type"] as? JsonPrimitive)?.contentOrNull)
    val enum = (schema["enum"] as? JsonArray)?.map { (it as JsonPrimitive).content }
    assertEquals(listOf("UP", "DOWN", "LEFT", "RIGHT"), enum)
    assertEquals("which direction", (schema["description"] as? JsonPrimitive)?.contentOrNull)
  }

  @Test fun `plain string parameter renders with no enum constraint`() {
    val schema = ToolParameterDescriptor(
      name = "label",
      description = "free text",
      type = ToolParameterType.String,
    ).toMcpJsonSchemaObject()

    assertEquals("string", (schema["type"] as? JsonPrimitive)?.contentOrNull)
    assertNull(schema["enum"], "a non-enum string param must not emit an `enum` constraint")
  }
}

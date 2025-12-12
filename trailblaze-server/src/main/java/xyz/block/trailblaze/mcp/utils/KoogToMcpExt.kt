package xyz.block.trailblaze.mcp.utils

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Helper functions to convert Koog tool parameters to MCP JSON schema (following Koog pattern)
 *
 * Original Code in Koog
 * https://github.com/JetBrains/koog/blob/c213e846834355c983eea16d41e1cf80d52f1cfc/agents/agents-mcp-server/src/commonMain/kotlin/ai/koog/agents/mcp/server/McpServer.kt#L154-L205
 */
object KoogToMcpExt {
  fun ToolParameterDescriptor.toMcpJsonSchemaObject(): JsonObject = buildJsonObject {
    put("description", JsonPrimitive(description))
    fillJsonSchema(type)
  }

  private fun JsonObjectBuilder.fillJsonSchema(type: ToolParameterType) {
    when (type) {
      ToolParameterType.Boolean -> put("type", JsonPrimitive("boolean"))
      ToolParameterType.Float -> put("type", JsonPrimitive("number"))
      ToolParameterType.Integer -> put("type", JsonPrimitive("integer"))
      ToolParameterType.String -> put("type", JsonPrimitive("string"))
      ToolParameterType.Null -> put("type", JsonPrimitive("null"))

      is ToolParameterType.Enum -> {
        put("type", JsonPrimitive("string"))
        putJsonArray("enum") {
          type.entries.forEach { entry -> add(JsonPrimitive(entry)) }
        }
      }

      is ToolParameterType.List -> {
        put("type", JsonPrimitive("array"))
        putJsonObject("items") { fillJsonSchema(type.itemsType) }
      }

      is ToolParameterType.AnyOf -> {
        putJsonArray("anyOf") {
          type.types.forEach { propertiesType ->
            add(propertiesType.toMcpJsonSchemaObject())
          }
        }
      }

      is ToolParameterType.Object -> {
        put("type", JsonPrimitive("object"))
        type.additionalProperties?.let { put("additionalProperties", JsonPrimitive(it)) }
        putJsonObject("properties") {
          type.properties.forEach { property ->
            putJsonObject(property.name) {
              fillJsonSchema(property.type)
              put("description", JsonPrimitive(property.description))
            }
          }
        }
      }
    }
  }
}

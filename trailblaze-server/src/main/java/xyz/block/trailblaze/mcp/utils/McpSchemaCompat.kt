package xyz.block.trailblaze.mcp.utils

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Simplify nullable `anyOf` patterns in MCP tool JSON schemas for broad client compatibility.
 *
 * Koog represents nullable types as `anyOf: [{type: "null"}, {type: "string"}]`. This is valid
 * JSON Schema but not supported by clients that use a restricted schema subset (e.g., OpenAI
 * Codex uses OpenAI function calling which rejects `anyOf`).
 *
 * This function rewrites `anyOf: [{type: "null"}, {type: X, ...}]` → `{type: X, ...}`,
 * stripping the null variant. The corresponding parameter should also be removed from the
 * `required` list so that omitting the field is equivalent to passing null.
 *
 * True union types (multiple non-null variants) are left unchanged.
 */
fun JsonObject.simplifyNullableAnyOf(): JsonObject {
  val anyOf = this["anyOf"] as? JsonArray ?: return this

  // Check if this is a nullable pattern: exactly one null type + one non-null type
  val elements = anyOf.map { it.jsonObject }
  val nullTypes = elements.filter { it["type"]?.jsonPrimitive?.content == "null" }
  val nonNullTypes = elements.filter { it["type"]?.jsonPrimitive?.content != "null" }

  if (nullTypes.size != 1 || nonNullTypes.size != 1) {
    // Not a simple nullable — leave as-is
    return this
  }

  // Merge: keep all fields from the original object (like "description") except "anyOf",
  // and add all fields from the non-null type variant.
  val nonNull = nonNullTypes.single()
  val merged = buildMap {
    this@simplifyNullableAnyOf.forEach { (key, value) ->
      if (key != "anyOf") put(key, value)
    }
    nonNull.forEach { (key, value) ->
      if (key != "description" || !containsKey("description")) {
        put(key, value)
      }
    }
  }
  return JsonObject(merged)
}

/**
 * Filter required parameters to exclude nullable ones (AnyOf containing Null).
 *
 * Clients using restricted JSON Schema (e.g., OpenAI Codex) reject tools where required fields
 * use `anyOf`. Since nullable parameters accept null, omitting them is semantically equivalent
 * to passing null — so they can safely be treated as optional.
 */
fun List<ToolParameterDescriptor>.filterNonNullableRequired(): List<String> =
  filter { param ->
    val type = param.type
    type !is ToolParameterType.AnyOf ||
      type.types.none { it.type is ToolParameterType.Null }
  }.map { it.name }

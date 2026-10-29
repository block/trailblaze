package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the JSON Schemas yaml-language-server validates Trailblaze YAML against.
 *
 * - [build] is the `.tool.yaml` schema: a fixed envelope mirroring `ToolYamlConfig` — `id`, plus a
 *   `class:` body and/or a `trailhead:` metadata block. It is closed (`additionalProperties:false`),
 *   so a key the loader rejects, such as a retired `tools:` composition body, is flagged in the editor.
 * - [toolCallItemSchema] is the registry-driven half, reused by [TrailYamlSchemaBuilder] for a trail's
 *   `recording:` → `tools:` blocks: one tool call keyed by a known tool id, with that tool's params
 *   offered for completion. Each tool's argument object is left open (`additionalProperties:true`,
 *   nothing required) because the catalog's per-tool `parameters` are lossy for the complex framework
 *   escape-hatches (e.g. `mobile_maestro` reports a single `yaml` param yet is authored with a
 *   `commands:` list) — completion is offered, a tool's body is never flagged.
 *
 * Pure (inputs in, schema-JSON string out) so it's unit-tested without a daemon — see
 * `ToolYamlSchemaBuilderTest`.
 */
object ToolYamlSchemaBuilder {

  /**
   * The trailmap that holds framework/built-in tools (e.g. `mobile_maestro`), always in completion
   * scope for a trail. Must match the framework-wide id (`TrailblazeTrailmapManifestLoader.FRAMEWORK_TRAILMAP_ID`,
   * which is `private` there); duplicated as a local const to avoid taking a dependency on that loader
   * just for a string. If the framework trailmap id ever changes, update both.
   */
  const val FRAMEWORK_TRAILMAP: String = "trailblaze"

  fun build(): String = JSON.encodeToString(JsonObject.serializer(), schema())

  private fun schema(): JsonObject = buildJsonObject {
    put("\$schema", "http://json-schema.org/draft-07/schema#")
    put("\$id", "https://trailblaze.block.xyz/schemas/tool.schema.json")
    put("title", "Trailblaze tool definition (.tool.yaml)")
    put(
      "description",
      "A tool definition. Provide `class:` (Kotlin-backed) or, with no class, a `trailhead:` metadata " +
        "block; a metadata block may also accompany a `class:`. A tool composed from other tools is a " +
        "TypeScript tool (trailblaze.tool(...)), not a `.tool.yaml`.",
    )
    put("type", "object")
    putJsonArray("required") { add("id") }
    put("additionalProperties", false)
    // The top-level envelope mirrors `ToolYamlConfig` (the source of truth for `.tool.yaml` structure).
    // It's hand-built here, so keep these keys in sync if that data class gains/renames a field — a
    // drift would silently let a new valid key be flagged as `additionalProperties:false` violation.
    putJsonObject("properties") {
      putJsonObject("id") { put("type", "string"); put("description", "The tool's unique id (the name the LLM and trails call it by). Required.") }
      putJsonObject("class") { put("type", "string"); put("description", "CLASS mode: fully-qualified Kotlin class of a @TrailblazeToolClass.") }
      putJsonObject("trailhead") {
        put("type", "object"); put("description", "Marks the tool as a trailhead (bootstrap from any state, no `from`); `{}` is enough. File must be *.trailhead.yaml.")
        put("additionalProperties", true)
        putJsonObject("properties") {
          putJsonObject("to") { put("type", "string"); put("description", "Optional. The waypoint it usually lands on, drawn on the waypoint graph. Never checked.") }
        }
      }
    }
  }

  /**
   * Schema for one entry in a `tools:` array: an object with exactly one key (the tool id), whose value
   * is that tool's (open) argument object.
   *
   * [closedToolNames] governs whether an *unknown* tool name is an error:
   *  - `true`: `additionalProperties:false` — a typo'd/out-of-scope tool name is flagged, and the known
   *    names autocomplete. Right when [tools] is the complete authoring surface.
   *  - `false` (`.trail.yaml` recordings): `additionalProperties:true` — known names still autocomplete
   *    (from `properties`), but an unknown name is NOT flagged. Recorded `tools:` blocks legitimately
   *    contain framework primitives and non-LLM-facing tools that aren't in a given completion set, and
   *    recordings are machine-generated + already validated, so flagging there would be a false positive.
   *
   * `internal` so [TrailYamlSchemaBuilder] can build a trail's `recording:` → `tools:` blocks from it.
   */
  internal fun toolCallItemSchema(tools: List<ToolCatalogEntry>, closedToolNames: Boolean = true): JsonObject = buildJsonObject {
    put("type", "object")
    put("minProperties", 1)
    put("maxProperties", 1)
    put("additionalProperties", !closedToolNames)
    putJsonObject("properties") {
      tools.forEach { tool -> put(tool.id, toolArgsSchema(tool)) }
    }
  }

  /** Open argument-object schema for a single tool: param-name completion from the catalog, no required/closed set. */
  private fun toolArgsSchema(tool: ToolCatalogEntry): JsonObject = buildJsonObject {
    // Allow `object` OR `null`: `- toolName:` with no args (a null value) is a valid call of a tool
    // that takes no arguments (or one whose args you haven't filled yet). Constraining to `object`
    // alone falsely flags every body-less tool entry ("Incorrect type. Expected object").
    putJsonArray("type") { add("object"); add("null") }
    put("additionalProperties", true)
    tool.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
    if (tool.parameters.isNotEmpty()) {
      putJsonObject("properties") {
        tool.parameters.forEach { p ->
          putJsonObject(p.name) {
            jsonTypeFor(p.type)?.let { put("type", it) }
            p.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
          }
        }
      }
    }
  }

  /** Map a catalog parameter `type` string to a JSON Schema `type`, or null to leave it unconstrained. */
  internal fun jsonTypeFor(type: String): String? = when (type.trim().lowercase()) {
    "string" -> "string"
    "integer", "int", "long" -> "integer"
    "number", "double", "float" -> "number"
    "boolean", "bool" -> "boolean"
    "object", "map" -> "object"
    "array", "list" -> "array"
    else -> null
  }
}

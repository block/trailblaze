package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the JSON Schema that yaml-language-server validates `.tool.yaml` files against — the dynamic,
 * registry-driven half of the YAML IntelliSense. The static envelope (the `id`/`description`/
 * `parameters`/`tools`/… top-level keys) is fixed, but the schema for entries **inside** a `tools:`
 * composition block is generated from the live tool catalog so authors get real autocomplete +
 * validation of the framework/trailmap tools they're composing — not just generic YAML.
 *
 * **Strict on tool NAMES, permissive on each tool's ARGS — deliberately.** Every registered tool
 * (framework + trailmap) is in the catalog, so listing the tool ids as the only allowed keys under a
 * `tools:` item (`additionalProperties:false`) is safe: it powers tool-name completion and flags a
 * typo'd/out-of-scope tool. But the catalog's per-tool `parameters` are lossy for the complex
 * framework escape-hatches (e.g. `mobile_maestro` reports a single `yaml` param yet is authored with a
 * `commands:` list), so each tool's argument object is left open (`additionalProperties:true`,
 * nothing required) — we offer param-name completion from the catalog but never flag a tool's body as
 * invalid. Tightening args would mean a faithful per-tool schema (a follow-up), not the catalog params.
 *
 * **Scope = this trailmap + the shared framework trailmap.** A `.tool.yaml` in trailmap `myapp`
 * composes `myapp` tools and the framework tools under [FRAMEWORK_TRAILMAP]; surfacing `otherapp` tools
 * there would be noise (and arguably wrong). When [trailmap] is null, all tools are included.
 *
 * Pure (catalog in, schema-JSON string out) so it's unit-tested without a daemon — see
 * `ToolYamlSchemaBuilderTest`.
 */
object ToolYamlSchemaBuilder {

  /**
   * The trailmap that holds framework/built-in tools (e.g. `mobile_maestro`), always in scope. Must
   * match the framework-wide id (`TrailblazeTrailmapManifestLoader.FRAMEWORK_TRAILMAP_ID`, which is
   * `private` there); duplicated as a local const to avoid taking a dependency on that loader just for
   * a string. If the framework trailmap id ever changes, update both.
   */
  const val FRAMEWORK_TRAILMAP: String = "trailblaze"

  fun build(entries: List<ToolCatalogEntry>, trailmap: String?): String {
    val inScope = entries
      .filter { trailmap == null || it.trailmap == trailmap || it.trailmap == FRAMEWORK_TRAILMAP }
      .distinctBy { it.id }
      .sortedBy { it.id }
    return JSON.encodeToString(JsonObject.serializer(), schema(inScope))
  }

  private fun schema(tools: List<ToolCatalogEntry>): JsonObject = buildJsonObject {
    put("\$schema", "http://json-schema.org/draft-07/schema#")
    put("\$id", "https://trailblaze.block.xyz/schemas/tool.schema.json")
    put("title", "Trailblaze tool definition (.tool.yaml)")
    put(
      "description",
      "A tool definition. Provide `class:` (Kotlin-backed) OR `tools:` (YAML-composed) — these two are " +
        "mutually exclusive — or, with neither, a `shortcut:`/`trailhead:` metadata block (`shortcut:` and " +
        "`trailhead:` are themselves mutually exclusive). A metadata block may also accompany a `class:`/`tools:` body.",
    )
    put("type", "object")
    putJsonArray("required") { add("id") }
    put("additionalProperties", false)
    // The top-level envelope mirrors `ToolYamlConfig` (the source of truth for `.tool.yaml` structure).
    // It's hand-built here, so keep these keys in sync if that data class gains/renames a field — a
    // drift would silently let a new valid key be flagged as `additionalProperties:false` violation.
    putJsonObject("properties") {
      putJsonObject("id") { put("type", "string"); put("description", "The tool's unique id (the name the LLM and trails call it by). Required.") }
      putJsonObject("class") { put("type", "string"); put("description", "CLASS mode: fully-qualified Kotlin class of a @TrailblazeToolClass. Mutually exclusive with `tools:`.") }
      putJsonObject("description") { put("type", "string"); put("description", "LLM-facing description (TOOLS mode).") }
      putJsonObject("parameters") {
        put("type", "array"); put("description", "Declared input parameters (TOOLS mode), referenced as {{params.<name>}}.")
        putJsonObject("items") {
          // The parameter-definition fields ARE a known, closed set (unlike a tool's runtime args), so
          // close it: typos in a parameter object get flagged and the keys autocomplete.
          put("type", "object"); putJsonArray("required") { add("name"); add("type") }; put("additionalProperties", false)
          putJsonObject("properties") {
            putJsonObject("name") { put("type", "string"); put("description", "Parameter name (referenced as {{params.<name>}}).") }
            putJsonObject("type") { put("type", "string"); put("description", "Parameter type — commonly `string`, `integer`, `number`, or `boolean`.") }
            putJsonObject("required") { put("type", "boolean"); put("description", "Whether the parameter must be supplied. Defaults to true.") }
            putJsonObject("description") { put("type", "string"); put("description", "Human/LLM-facing description of the parameter.") }
            // `default` (TrailblazeToolParameterConfig.default / DefaultBehavior) is left untyped — it can
            // be a literal or a behavior keyword — so it autocompletes/hovers without over-constraining.
            putJsonObject("default") { put("description", "Default-value behavior when the parameter is omitted (TrailblazeToolParameterConfig.default).") }
          }
        }
      }
      // The dynamic part: each `tools:` entry is a single-tool-call object keyed by a known tool id.
      putJsonObject("tools") {
        put("type", "array")
        put("description", "TOOLS mode: an ordered list of framework/trailmap tool calls this tool composes (with {{params.x}} interpolation). Mutually exclusive with `class:`.")
        put("items", toolCallItemSchema(tools))
      }
      putJsonObject("surface_to_llm") { put("type", "boolean"); put("description", "Whether the tool is advertised to the LLM. TOOLS mode only; null defaults to true.") }
      putJsonObject("is_recordable") { put("type", "boolean"); put("description", "Whether calls are recorded into a trail. TOOLS mode only; null defaults to true.") }
      putJsonObject("requires_host") { put("type", "boolean"); put("description", "Host-only execution. TOOLS mode only; null defaults to false.") }
      putJsonObject("is_verification") { put("type", "boolean"); put("description", "Marks the tool as a read-only assertion. TOOLS mode only; null defaults to false.") }
      putJsonObject("shortcut") {
        put("type", "object"); put("description", "METADATA mode: a navigation shortcut (waypoint→waypoint). File must be *.shortcut.yaml. Mutually exclusive with `trailhead:`.")
        put("additionalProperties", true); putJsonArray("required") { add("from"); add("to") }
        putJsonObject("properties") {
          putJsonObject("from") { put("type", "string"); put("description", "Waypoint that must match before this shortcut runs.") }
          putJsonObject("to") { put("type", "string"); put("description", "Waypoint that must match after this shortcut runs.") }
          putJsonObject("variant") { put("type", "string"); put("description", "Optional variant discriminator.") }
        }
      }
      putJsonObject("trailhead") {
        put("type", "object"); put("description", "METADATA mode: a trailhead (bootstrap to a waypoint, no `from`). File must be *.trailhead.yaml. Mutually exclusive with `shortcut:`.")
        put("additionalProperties", true)
        putJsonObject("properties") {
          putJsonObject("to") { put("type", "string"); put("description", "Waypoint that must match after the trailhead runs.") }
          putJsonObject("dynamic") { put("type", "boolean"); put("description", "Whether the trailhead resolves its destination dynamically at runtime.") }
        }
      }
    }
  }

  /**
   * Schema for one entry in a `tools:` array: an object with exactly one key (the tool id), whose value
   * is that tool's (open) argument object.
   *
   * [closedToolNames] governs whether an *unknown* tool name is an error:
   *  - `true` (`.tool.yaml`): `additionalProperties:false` — a typo'd/out-of-scope tool name is flagged,
   *    and the known names autocomplete. The catalog is the complete authoring surface there, so closing
   *    is safe and desirable.
   *  - `false` (`.trail.yaml` recordings): `additionalProperties:true` — known names still autocomplete
   *    (from `properties`), but an unknown name is NOT flagged. Recorded `tools:` blocks legitimately
   *    contain framework primitives and non-LLM-facing tools that aren't in a given completion set, and
   *    recordings are machine-generated + already validated, so flagging there would be a false positive.
   *
   * `internal` so [TrailYamlSchemaBuilder] can reuse the identical shape for a trail's `recording:` →
   * `tools:` blocks, keeping tool-name/param completion consistent across both editors.
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
    // Allow `object` OR `null`: `- toolName:` with no args (a null value) is a valid composition of a
    // tool that takes no arguments (or one whose args you haven't filled yet). Constraining to `object`
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

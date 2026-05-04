package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.JsonObjectYamlSerializer

/**
 * Author-friendly per-file shape for scripted MCP tools owned by a pack.
 *
 * Each pack puts one of these YAML files under `<pack>/tools/<tool-name>.yaml` and references
 * it from the pack manifest's `target.tools:` list. The pack loader decodes each file with
 * this shape, then translates [inputSchema] into a fully conformant JSON Schema before
 * handing the result to the runtime as an [InlineScriptToolConfig] (Decision 038).
 *
 * The flat schema shape is the design point: authors declare parameters as a plain map of
 * `name -> { type, description, ... }` rather than the JSON Schema ceremony of
 * `{ type: object, properties: { ... }, required: [ ... ] }`. The wrapping is mechanical,
 * so the loader does it.
 *
 * Example:
 * ```yaml
 * script: ./tools/openFixtureAndVerifyText.js
 * name: playwrightSample_web_openFixtureAndVerifyText
 * description: Open the Playwright sample page and verify visible text.
 * _meta:
 *   trailblaze/supportedPlatforms: [web]
 *   trailblaze/requiresContext: true
 * inputSchema:
 *   relativePath:
 *     type: string
 *     description: Sample-app fixture path relative to the playwright-native examples root.
 *   text:
 *     type: string
 *     description: Visible text to assert on the loaded page.
 * ```
 *
 * Note: `trailblaze/supportedPlatforms` values are case-insensitive at parse time
 * (`TrailblazeToolMeta.fromJsonObject` normalizes to uppercase). `[web]`, `[WEB]`, and
 * `[Web]` all decode to the same canonical form. Lowercase is the conventional shape since
 * it matches the TS-side context envelope.
 *
 * Note: per-tool `_meta.trailblaze/toolset` is intentionally absent here. Toolset membership
 * is owned by the toolset YAML (it claims its tools), not declared inverted on each tool.
 * The runtime [TrailblazeToolMeta] still parses a `trailblaze/toolset` field for legacy
 * authors who set it on raw MCP-server tools, but new pack-shape tools shouldn't author it.
 */
@Serializable
data class PackScriptedToolFile(
  /**
   * Absolute or relative path to the JS module backing this tool.
   *
   * **Resolution rule (intentional asymmetry with the rest of the pack)**: relative `script:`
   * paths resolve against the **JVM working directory**, not the pack directory. This is the
   * `McpSubprocessSpawner` contract — the subprocess runs in CWD and imports its module
   * relative to that working directory. Pack-relative resolution would require the host
   * runtime to materialize each pack into a transient working directory before spawning bun,
   * which we don't do today. Authors should write paths from the repo root (or use absolute
   * paths) — pack sibling resolution via `tools/<id>.yaml` is for the YAML files themselves,
   * not for what those YAMLs reference.
   *
   * If pack-relative `script:` resolution becomes a hard requirement, route through
   * `PackSource.readSibling` and stage the script into a per-pack scratch dir before
   * subprocess spawn.
   */
  val script: String,
  val name: String,
  val description: String? = null,
  /**
   * Top-level shortcut for `_meta: { trailblaze/requiresHost: true }`. Setting this to `true`
   * marks the tool as host-only — the on-device agent skips it at registration via the same
   * `TrailblazeToolMeta.shouldRegister` gate that Kotlin
   * `@TrailblazeToolClass(requiresHost = true)` already uses. Use it for tools that need
   * Node/Bun APIs (`node:fs`, `node:child_process`) or otherwise can't run inside the
   * on-device QuickJS bundle.
   *
   * Translated by [toInlineScriptToolConfig] into `_meta["trailblaze/requiresHost"] = true`
   * on the runtime [InlineScriptToolConfig]. Default `false` is a no-op — any explicit
   * `_meta["trailblaze/requiresHost"]` you set still flows through unchanged.
   */
  val requiresHost: Boolean = false,
  @SerialName("_meta")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val meta: JsonObject? = null,
  /**
   * Flat map of parameter name to property descriptor. Order is preserved by the YAML
   * decoder, which matters for the `properties` map (LLMs sometimes anchor on declaration
   * order) and for the derived `required` array (we emit it in the same order).
   */
  @SerialName("inputSchema") val inputSchema: Map<String, ScriptedToolProperty> = emptyMap(),
)

/**
 * One parameter on a [PackScriptedToolFile.inputSchema]. Mirrors the JSON Schema fields the
 * authored packs in this repo actually use (`type`, `description`, `enum`); deliberately not
 * a complete JSON Schema modeller. If we hit a tool that needs `items`, `format`, `minimum`,
 * etc., extend this class — don't reach for a generic JsonObject escape hatch.
 *
 * [required] defaults to `true` because the overwhelming majority of authored tool params
 * are required; flipping the default keeps the YAML quiet for the common case.
 */
@Serializable
data class ScriptedToolProperty(
  val type: String,
  val description: String? = null,
  val enum: List<String>? = null,
  val required: Boolean = true,
)

/**
 * Translates the author-friendly flat shape into the JSON-Schema-conformant
 * [InlineScriptToolConfig.inputSchema] the runtime expects (`{ type: object, properties:
 * {...}, required: [...] }`). Done at pack load time so authors never write the wrapper
 * and the runtime never sees the flat shape.
 *
 * Also validates per-property invariants that the YAML schema can't catch on its own —
 * notably that any author-declared `enum` has at least one value, since JSON Schema's
 * `enum` keyword requires a non-empty array.
 */
fun PackScriptedToolFile.toInlineScriptToolConfig(): InlineScriptToolConfig {
  inputSchema.forEach { (propName, prop) ->
    prop.enum?.let { values ->
      require(values.isNotEmpty()) {
        "Tool '$name' property '$propName': `enum` must declare at least one value (JSON Schema " +
          "rejects an empty enum array). Either remove the `enum` field or list the allowed values."
      }
    }
  }
  return InlineScriptToolConfig(
    script = script,
    name = name,
    description = description,
    requiresHost = requiresHost,
    meta = meta,
    inputSchema = buildInputSchemaObject(inputSchema),
  )
}

private fun buildInputSchemaObject(properties: Map<String, ScriptedToolProperty>): JsonObject =
  buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildPropertiesObject(properties))
    // Iterate `entries` rather than `filterValues(...).keys` so the resulting list ordering
    // is unambiguously declaration-order — Kotlin's stdlib LinkedHashMap preserves insertion
    // order, but the test suite asserts on order and we want the contract on the surface,
    // not relying on stdlib impl details.
    val requiredNames = properties.entries.filter { it.value.required }.map { it.key }
    if (requiredNames.isNotEmpty()) {
      put("required", buildRequiredArray(requiredNames))
    }
  }

private fun buildPropertiesObject(properties: Map<String, ScriptedToolProperty>): JsonObject =
  buildJsonObject {
    properties.forEach { (name, prop) ->
      put(name, buildPropertyObject(prop))
    }
  }

private fun buildPropertyObject(prop: ScriptedToolProperty): JsonObject =
  buildJsonObject {
    put("type", JsonPrimitive(prop.type))
    prop.description?.let { put("description", JsonPrimitive(it)) }
    prop.enum?.let { values ->
      put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
  }

private fun buildRequiredArray(requiredNames: List<String>): JsonArray =
  buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } }

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
 * Example (preferred shape — top-level shortcut fields, no `_meta:` ceremony, no
 * `inputSchema: {}` boilerplate when the tool takes no args, and the `.ts` source
 * co-located with the descriptor under the owning pack per the library/target pack
 * convention #2783):
 * ```yaml
 * # trails/config/packs/playwrightsample/tools/playwrightSample_web_openFixtureAndVerifyText.yaml
 * script: ./trails/config/packs/playwrightsample/tools/playwrightSample_web_openFixtureAndVerifyText.ts
 * name: playwrightSample_web_openFixtureAndVerifyText
 * description: Open the Playwright sample page and verify visible text.
 * supportedPlatforms:
 *   - web
 * inputSchema:
 *   relativePath:
 *     type: string
 *     description: Sample-app fixture path relative to the playwright-native examples root.
 *   text:
 *     type: string
 *     description: Visible text to assert on the loaded page.
 * ```
 *
 * The top-level `supportedPlatforms` field is sugar — it's translated into the namespaced
 * `_meta.trailblaze/supportedPlatforms` key that downstream framework consumers actually
 * read. The escape-hatch `_meta:` block is still available for arbitrary keys that don't
 * have shortcuts yet, but most authors should never need it.
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
   * which we don't do today.
   *
   * **Authoring convention**: even though the resolver is JVM-CWD-relative, **the `.ts`
   * source itself should live next to its descriptor under the owning pack** —
   * `trails/config/packs/<pack>/tools/<tool>.ts` co-located with
   * `trails/config/packs/<pack>/tools/<tool>.yaml`. The `script:` value then writes the
   * long form `./trails/config/packs/<pack>/tools/<tool>.ts` (resolves from the repo
   * root). This keeps the on-disk ownership boundary clean — each pack owns the
   * implementation files of every tool it contributes — without requiring framework
   * changes to the resolver. The longer path is the workaround until pack-relative
   * resolution lands; pack sibling resolution via `tools/<id>.yaml` is for the
   * descriptor YAMLs themselves, not for the scripts those YAMLs reference.
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
  /**
   * Top-level shortcut for `_meta: { trailblaze/supportedPlatforms: [...] }`. Limits the
   * tool to the listed platforms — Trailblaze's per-target / per-driver gates use this to
   * filter the tool out of sessions running on other platforms. Values are case-insensitive
   * (`[web]`, `[WEB]`, `[Web]` all collapse to canonical form at runtime).
   *
   * Translated by [toInlineScriptToolConfig] into `_meta["trailblaze/supportedPlatforms"]`
   * on the runtime [InlineScriptToolConfig]. `null` (the default) is a no-op — any explicit
   * `_meta["trailblaze/supportedPlatforms"]` you set still flows through unchanged.
   */
  val supportedPlatforms: List<String>? = null,
  /**
   * Escape hatch for arbitrary `_meta:` keys that don't have a top-level shortcut yet
   * (e.g., custom MCP-spec extensions, future framework hints). Most authors should
   * never need to set this — prefer the top-level fields ([requiresHost],
   * [supportedPlatforms]). Values supplied here merge with the top-level fields'
   * translations; on key conflicts, the top-level field wins (so a `requiresHost: true`
   * top-level field overrides a `_meta: { trailblaze/requiresHost: false }`).
   */
  @SerialName("_meta")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val meta: JsonObject? = null,
  /**
   * Flat map of parameter name to property descriptor. Order is preserved by the YAML
   * decoder, which matters for the `properties` map (LLMs sometimes anchor on declaration
   * order) and for the derived `required` array (we emit it in the same order).
   *
   * Defaults to empty when omitted from the YAML — tools that take no arguments don't
   * need to write `inputSchema: {}` explicitly.
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
  // Validate the tool name with a YAML-author-facing error message before we hand off to
  // `InlineScriptToolConfig`'s init block — same regex (the source of truth lives on
  // `InlineScriptToolConfig.TOOL_NAME_PATTERN`), but this site has the `<pack>/tools/<id>.yaml`
  // file shape in mind and produces a more actionable message that names the descriptor.
  require(InlineScriptToolConfig.TOOL_NAME_PATTERN.matches(name)) {
    "Invalid scripted-tool name '$name' in per-file descriptor for script '$script': must match " +
      "${InlineScriptToolConfig.TOOL_NAME_PATTERN} (letters, digits, _, -, ., starting with a " +
      "letter or _). Update the descriptor YAML's `name:` field to use a supported character set."
  }
  inputSchema.forEach { (propName, prop) ->
    prop.enum?.let { values ->
      require(values.isNotEmpty()) {
        "Tool '$name' property '$propName': `enum` must declare at least one value (JSON Schema " +
          "rejects an empty enum array). Either remove the `enum` field or list the allowed values."
      }
    }
  }
  // Validate types on known `trailblaze/...` _meta keys before we merge shortcuts. Without this,
  // a typo like `_meta: { trailblaze/supportedPlatforms: "android" }` (string, not list) would
  // silently slip through — the conflicting top-level shortcut would overwrite it before any
  // consumer noticed, masking the author error. We'd rather fail loudly with a `<pack>/tools/<id>.yaml`-aware
  // message at parse time.
  validateKnownMetaShapes(name, meta)
  // Merge the top-level shortcut field [supportedPlatforms] into the [_meta] JsonObject so
  // downstream consumers that read namespaced keys directly (`TrailblazeToolMeta`, toolset
  // filtering, etc.) keep working without needing to know about the new shortcut field.
  // Top-level wins on key conflicts — explicit YAML `supportedPlatforms: [android]` overrides
  // any stale `_meta: { trailblaze/supportedPlatforms: [...] }` an author might have copied
  // from a different descriptor.
  val mergedMeta = mergeMetaShortcuts(
    explicitMeta = meta,
    supportedPlatforms = supportedPlatforms,
  )
  return InlineScriptToolConfig(
    script = script,
    name = name,
    description = description,
    requiresHost = requiresHost,
    meta = mergedMeta,
    inputSchema = buildInputSchemaObject(inputSchema),
  )
}

/**
 * Fails fast on author errors that the YAML schema can't catch on its own — specifically,
 * type mismatches on the namespaced `trailblaze/...` keys. The runtime would eventually
 * misbehave if a string slipped in where a JsonArray is expected, but that error would surface
 * far from the descriptor file. We'd rather throw here with the descriptor name in the message.
 *
 * Only validates keys we know about (`trailblaze/requiresHost`, `trailblaze/supportedPlatforms`);
 * arbitrary author keys flow through unchecked.
 */
private fun validateKnownMetaShapes(toolName: String, meta: JsonObject?) {
  if (meta == null) return
  meta["trailblaze/requiresHost"]?.let { v ->
    require(v is JsonPrimitive && v.isBooleanLiteral()) {
      "Tool '$toolName' `_meta.trailblaze/requiresHost`: expected a boolean, got ${v::class.simpleName}. " +
        "Prefer the top-level `requiresHost: true|false` shortcut instead of authoring this key directly."
    }
  }
  meta["trailblaze/supportedPlatforms"]?.let { v ->
    require(v is JsonArray && v.all { it is JsonPrimitive && it.isString }) {
      "Tool '$toolName' `_meta.trailblaze/supportedPlatforms`: expected a YAML list of strings " +
        "(e.g. `[android, web]`), got ${v::class.simpleName}. Prefer the top-level " +
        "`supportedPlatforms: [...]` shortcut instead of authoring this key directly."
    }
  }
}

/** Recognizes `true` and `false` JSON literals; rejects e.g. the string `"true"`. */
private fun JsonPrimitive.isBooleanLiteral(): Boolean =
  !isString && (content == "true" || content == "false")

/**
 * Folds the top-level shortcut field [PackScriptedToolFile.supportedPlatforms] onto the
 * explicit `_meta` JsonObject, producing the runtime-shaped meta map. Returns `null` when both
 * sources are empty so [InlineScriptToolConfig.meta] stays absent rather than becoming an
 * empty object (downstream code distinguishes "no meta" from "empty meta" in some paths).
 */
private fun mergeMetaShortcuts(
  explicitMeta: JsonObject?,
  supportedPlatforms: List<String>?,
): JsonObject? {
  val explicit = explicitMeta ?: JsonObject(emptyMap())
  val needsSupportedPlatforms = !supportedPlatforms.isNullOrEmpty()
  if (explicit.isEmpty() && !needsSupportedPlatforms) {
    return null
  }
  return buildJsonObject {
    // Write explicit map first, then write shortcut keys after. `buildJsonObject`'s `put` is
    // last-write-wins, so the shortcut overrides any conflicting key the author copied into
    // their `_meta:` block.
    explicit.forEach { (k, v) -> put(k, v) }
    if (needsSupportedPlatforms) {
      put(
        "trailblaze/supportedPlatforms",
        buildJsonArray { supportedPlatforms!!.forEach { add(JsonPrimitive(it)) } },
      )
    }
  }
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

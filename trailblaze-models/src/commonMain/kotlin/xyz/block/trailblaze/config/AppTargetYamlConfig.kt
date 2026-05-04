package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlInput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.JsonElementSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Schema for `.app.yaml` files — declarative configuration for a
 * [TrailblazeHostAppTarget][xyz.block.trailblaze.model.TrailblazeHostAppTarget].
 *
 * Example:
 * ```yaml
 * id: sample
 * display_name: Sample App
 * has_custom_ios_driver: true
 *
 * platforms:
 *   android:
 *     app_ids:
 *       - com.example.development
 *     tool_sets:
 *       - core_interaction
 *       - memory
 *       - sample_android
 *     tools: [customDebugTool]
 *     excluded_tools: [tapOnPoint]
 *   ios:
 *     app_ids:
 *       - com.example.sample
 *     tool_sets:
 *       - core_interaction
 *       - memory
 *     min_build_version: "6515"
 *   web:
 *     drivers: [playwright-native]
 *     tool_sets:
 *       - web_core
 *       - memory
 * ```
 */
@Serializable
data class AppTargetYamlConfig(
  val id: String,
  @SerialName("display_name") val displayName: String,
  val platforms: Map<String, PlatformConfig>? = null,
  @SerialName("has_custom_ios_driver") val hasCustomIosDriver: Boolean = false,
  /**
   * MCP server declarations that contribute tools to the Trailblaze tool
   * registry for sessions targeting this app (Decision 038). Each entry
   * is a [McpServerConfig] — currently only `script:` entries are
   * implemented; `command:` entries are reserved for a follow-up per
   * `docs/devlog/2026-04-21-scripted-tools-mcp-integration-patterns.md`.
   *
   * Spawning requires a resolved session (target + driver + agent-mode).
   * Tool registration applies the standard filters (platform / driver /
   * host-required) from annotations on each contributed tool.
   */
  @SerialName("mcp_servers") val mcpServers: List<McpServerConfig>? = null,
  /**
   * Optional system-prompt template content for sessions targeting this app. When set, rules
   * for this target seed the LLM session with this content as the system prompt (additive to
   * the framework default). Use this to express target-specific guidance — what the app is,
   * what testers commonly want — without each test author having to re-pass a Kotlin resource.
   *
   * **Post-resolution shape.** Authors don't write this field directly on a pack manifest. They
   * set [PackTargetConfig.systemPromptFile][xyz.block.trailblaze.config.project.PackTargetConfig.systemPromptFile]
   * (a relative file path); the build-time pack generator and runtime pack loader both read that
   * file and inline its content into this field on the generated / resolved [AppTargetYamlConfig].
   * Downstream consumers ([xyz.block.trailblaze.config.YamlBackedHostAppTarget] and Block-side
   * `BundledTargetYamlLookup`) read this field directly.
   */
  @SerialName("system_prompt") val systemPrompt: String? = null,
  /**
   * Resolved scripted tools — one MCP tool per entry, each backed by an author-owned JS module
   * referenced by [InlineScriptToolConfig.script] and exporting a named function matching
   * [InlineScriptToolConfig.name].
   *
   * **Authoring surface differs depending on origin**:
   *  - **Packs** (the recommended path): authors place each scripted tool in its own
   *    `<pack>/tools/<tool-name>.yaml` file with the [PackScriptedToolFile][xyz.block.trailblaze.config.project.PackScriptedToolFile]
   *    shape (flat `inputSchema`). The pack loader resolves each ref, translates the flat
   *    schema into JSON Schema, and produces this list — authors never write the JSON
   *    Schema wrapper.
   *  - **Legacy flat targets** (preserved for backwards compatibility): authors inline the
   *    full [InlineScriptToolConfig] shape (incl. raw JSON Schema) directly in
   *    `targets/<id>.yaml`. New code should use packs.
   *
   * Unlike [mcpServers], these entries are synthesized into temporary MCP wrapper scripts by the
   * host runner at session start. This is intentionally host-first: the current on-device bundle
   * runtime only accepts pre-bundled JS artifacts, so raw inline author files are not wired there
   * yet.
   */
  val tools: List<InlineScriptToolConfig>? = null,
)

/**
 * Per-platform configuration within a target. Groups app IDs, toolsets, individual tools,
 * exclusions, and version requirements under the platform they apply to.
 */
@Serializable
data class PlatformConfig(
  @SerialName("app_ids") val appIds: List<String>? = null,
  @SerialName("tool_sets") val toolSets: List<String>? = null,
  val tools: List<String>? = null,
  @SerialName("excluded_tools") val excludedTools: List<String>? = null,
  val drivers: List<String>? = null,
  @SerialName("base_url") val baseUrl: String? = null,
  @SerialName("min_build_version") val minBuildVersion: String? = null,
) {

  /**
   * Resolves the driver types for this platform section using explicit [drivers] if set, otherwise
   * the [platformKey].
   */
  fun resolveDriverTypes(platformKey: String): Set<TrailblazeDriverType> =
    if (drivers != null) DriverTypeKey.resolveAll(drivers) else DriverTypeKey.resolve(platformKey)
}

@Serializable
data class InlineScriptToolConfig(
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
   * Setting this to `true` forces `_meta["trailblaze/requiresHost"] = true` in the synthesized
   * wrapper. The default `false` is a no-op — any `_meta["trailblaze/requiresHost"]` you set
   * explicitly still flows through unchanged.
   */
  val requiresHost: Boolean = false,
  @SerialName("_meta")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val meta: JsonObject? = null,
  @SerialName("inputSchema")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val inputSchema: JsonObject = JsonObject(emptyMap()),
)

/**
 * Bridges YAML maps into [JsonObject] so target-level inline tool declarations can author
 * `inputSchema:` in natural YAML without dropping to a quoted JSON string.
 */
internal object JsonObjectYamlSerializer : KSerializer<JsonObject> {
  private val fallback = MapSerializer(String.serializer(), JsonElementSerializer)

  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun serialize(encoder: Encoder, value: JsonObject) {
    when (encoder) {
      is JsonEncoder -> encoder.encodeJsonElement(value)
      else -> encoder.encodeSerializableValue(
        fallback,
        value.mapValues { (_, v) -> YamlJsonBridge.jsonElementToSerializable(v) },
      )
    }
  }

  override fun deserialize(decoder: Decoder): JsonObject = when (decoder) {
    is YamlInput -> {
      YamlJsonBridge.yamlNodeToJsonElement(decoder.node) as? JsonObject
        ?: error("inline tool `inputSchema` must decode to a YAML map / JSON object")
    }
    is JsonDecoder -> decoder.decodeJsonElement().jsonObject
    else -> {
      val decoded = decoder.decodeSerializableValue(fallback)
      YamlJsonBridge.serializableToJsonElement(decoded) as? JsonObject
        ?: error("inline tool `inputSchema` must decode to an object")
    }
  }
}

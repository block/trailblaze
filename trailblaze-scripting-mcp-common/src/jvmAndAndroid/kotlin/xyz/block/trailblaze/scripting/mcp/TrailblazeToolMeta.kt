package xyz.block.trailblaze.scripting.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Trailblaze-specific metadata read off an advertised MCP [Tool]'s `_meta` object.
 *
 * The conventions devlog is canonical — see § 1 "Tool metadata via _meta trailblaze-prefixed
 * keys" in `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md`. Keys follow the MCP
 * `<vendor-prefix>/<name>` convention, so everything here lives under a `trailblaze/` prefix:
 * `trailblaze/requiresHost`, `trailblaze/supportedDrivers`, etc. Authors set them on
 * `Tool._meta` in their `.ts` source; Trailblaze reads them at registration time to decide
 * whether a tool applies to the current session and how to surface it.
 *
 * Missing keys take sensible defaults (all-permissive) per the conventions devlog's
 * § Enforcement section.
 *
 * Shared across the subprocess (`:trailblaze-scripting-subprocess`) and on-device bundle
 * (`:trailblaze-scripting-bundle`) runtimes so both read the same keys the same way — an
 * author-visible guarantee that "one TS source, two deployment modes" (per PR A5 devlog)
 * actually does use the same filter semantics in both places.
 */
data class TrailblazeToolMeta(
  val isForLlm: Boolean = true,
  val isRecordable: Boolean = true,
  val requiresHost: Boolean = false,
  /** Empty = unrestricted. Non-empty = registers only if session driver is in the list. */
  val supportedDrivers: List<String> = emptyList(),
  /** Empty = unrestricted. Non-empty = registers only if session platform is in the list. */
  val supportedPlatforms: List<String> = emptyList(),
  /** Null = global registry (pull-based). Non-null = pushed into that toolset at registration. */
  val toolset: String? = null,
  val requiresContext: Boolean = false,
) {

  /**
   * Decide whether this tool should register for a session identified by its [driver] (which
   * encodes platform) and host-agent [preferHostAgent] bit. Returns `true` when the tool
   * applies; `false` when any of the filter rules rejects it.
   *
   * Implements the scope devlog's § Capability filtering order: drivers → platforms →
   * requiresHost. `requiresContext` is not a filter — it's UX-only per § 1.
   *
   * On-device callers pass `preferHostAgent = false`, which is what makes
   * `trailblaze/requiresHost: true` tools skip at registration without any extra branching
   * in the on-device launcher (PR A5 §"Capability gating at registration").
   */
  fun shouldRegister(
    driver: TrailblazeDriverType,
    preferHostAgent: Boolean,
  ): Boolean {
    if (supportedDrivers.isNotEmpty() && driver.yamlKey !in supportedDrivers) {
      return false
    }
    if (supportedPlatforms.isNotEmpty() && driver.platform.name !in supportedPlatforms) {
      return false
    }
    if (requiresHost && !preferHostAgent) {
      return false
    }
    return true
  }

  companion object {

    /** Vendor prefix the MCP spec reserves for Trailblaze-owned `_meta` keys. */
    private const val PREFIX: String = "trailblaze/"

    private const val KEY_IS_FOR_LLM = "${PREFIX}isForLlm"
    private const val KEY_IS_RECORDABLE = "${PREFIX}isRecordable"
    private const val KEY_REQUIRES_HOST = "${PREFIX}requiresHost"
    private const val KEY_SUPPORTED_DRIVERS = "${PREFIX}supportedDrivers"
    private const val KEY_SUPPORTED_PLATFORMS = "${PREFIX}supportedPlatforms"
    private const val KEY_TOOLSET = "${PREFIX}toolset"
    private const val KEY_REQUIRES_CONTEXT = "${PREFIX}requiresContext"

    /**
     * Parses a [Tool]'s `_meta` object into typed [TrailblazeToolMeta] fields. Missing keys
     * take the data class defaults. Keys of the wrong JSON shape are treated as absent —
     * the MCP server is untrusted enough that a malformed key shouldn't crash registration
     * (it should fail the shape-validation on the author's end, in a typed TS types package).
     */
    fun fromTool(tool: Tool): TrailblazeToolMeta {
      val meta = tool.meta ?: return TrailblazeToolMeta()
      return fromJsonObject(meta)
    }

    /** Same as [fromTool] but directly on the raw [JsonObject] — exposed for tests. */
    fun fromJsonObject(meta: JsonObject): TrailblazeToolMeta = TrailblazeToolMeta(
      isForLlm = meta.readBoolean(KEY_IS_FOR_LLM, default = true),
      isRecordable = meta.readBoolean(KEY_IS_RECORDABLE, default = true),
      requiresHost = meta.readBoolean(KEY_REQUIRES_HOST, default = false),
      supportedDrivers = meta.readStringList(KEY_SUPPORTED_DRIVERS),
      supportedPlatforms = meta.readStringList(KEY_SUPPORTED_PLATFORMS),
      toolset = meta.readString(KEY_TOOLSET),
      requiresContext = meta.readBoolean(KEY_REQUIRES_CONTEXT, default = false),
    )

    private fun JsonObject.readBoolean(key: String, default: Boolean): Boolean =
      (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun JsonObject.readString(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.readStringList(key: String): List<String> {
      val element = this[key] ?: return emptyList()
      val array = element as? JsonArray ?: return emptyList()
      return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
  }
}

/** Extension mirror of [TrailblazeToolMeta.fromTool] for ergonomic call sites. */
fun Tool.trailblazeMeta(): TrailblazeToolMeta = TrailblazeToolMeta.fromTool(this)

/**
 * Convenience overload mirroring [TrailblazeToolMeta.shouldRegister] for a full session —
 * takes the resolved [platform] explicitly in case the caller has it but not the driver enum.
 *
 * Not called by the runtime today; there for tests and for future callers that don't have a
 * [TrailblazeDriverType] in hand.
 */
fun TrailblazeToolMeta.shouldRegisterForPlatform(
  platform: TrailblazeDevicePlatform,
  preferHostAgent: Boolean,
): Boolean {
  if (supportedPlatforms.isNotEmpty() && platform.name !in supportedPlatforms) {
    return false
  }
  if (requiresHost && !preferHostAgent) {
    return false
  }
  return true
}

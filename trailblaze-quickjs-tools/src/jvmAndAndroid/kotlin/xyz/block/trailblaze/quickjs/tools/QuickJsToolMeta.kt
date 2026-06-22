package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Trailblaze-specific metadata read off a registered tool's `spec._meta` object. Lean
 * subset of the legacy `TrailblazeToolMeta` (in `:trailblaze-scripting-mcp-common`) that
 * keeps this module free of the MCP-SDK dependency. Reads the same `trailblaze/`-prefixed
 * keys the legacy reader does so a tool authored against `@trailblaze/scripting` filters
 * identically whether it loads through this runtime or the MCP-shaped one.
 *
 * Missing keys take all-permissive defaults — a bundle with no `_meta` filtering still
 * registers everywhere, matching the legacy reader's defaults.
 */
data class QuickJsToolMeta(
  val requiresHost: Boolean = false,
  /** Empty = unrestricted. Non-empty = registers only if session driver is in the list. */
  val supportedDrivers: List<String> = emptyList(),
  /**
   * Empty = unrestricted. Non-empty = registers only if session platform is in the list.
   * Values are normalized to uppercase at parse time so authors can write either casing.
   */
  val supportedPlatforms: List<String> = emptyList(),
  /**
   * Whether to advertise this tool to the LLM. Unlike [requiresHost] / [supportedDrivers] /
   * [supportedPlatforms], this is NOT a [shouldRegister] gate — a `surfaceToLlm = false` tool
   * still registers for by-name dispatch (so a parent tool can compose it and recorded replays
   * resolve it); it's only dropped from the advertised set. Default `true`.
   */
  val surfaceToLlm: Boolean = true,
  /**
   * Whether this tool's invocation is written to the recorded `.trail.yaml`. Like [surfaceToLlm]
   * this is not a [shouldRegister] gate — the tool still registers and runs; the runtime threads
   * this onto the decoded tool's `toolMetadata` so the recording gate honors the opt-out. Default
   * `true`.
   */
  val isRecordable: Boolean = true,
) {

  /**
   * Decide whether this tool should register for a session identified by its [driver].
   * On-device callers pass `preferHostAgent = false`, which is what makes
   * `trailblaze/requiresHost: true` tools skip at registration without any extra branching.
   * Filter order matches the legacy reader's: drivers → platforms → requiresHost.
   */
  fun shouldRegister(driver: TrailblazeDriverType, preferHostAgent: Boolean): Boolean {
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
    private const val PREFIX: String = "trailblaze/"
    private const val KEY_REQUIRES_HOST = "${PREFIX}requiresHost"
    private const val KEY_SUPPORTED_DRIVERS = "${PREFIX}supportedDrivers"
    private const val KEY_SUPPORTED_PLATFORMS = "${PREFIX}supportedPlatforms"
    private const val KEY_SURFACE_TO_LLM = "${PREFIX}surfaceToLlm"
    private const val KEY_IS_RECORDABLE = "${PREFIX}isRecordable"

    /**
     * Reads a registered tool's `spec` object — looking inside `spec._meta` for the
     * Trailblaze-prefixed keys. A spec with no `_meta` (or a non-object `_meta`) yields
     * all-permissive defaults; an MCP server worth its salt will surface schema-level
     * validation on the author side, so a malformed key here shouldn't crash registration.
     */
    fun fromSpec(spec: JsonObject): QuickJsToolMeta {
      val meta = spec["_meta"] as? JsonObject ?: return QuickJsToolMeta()
      return QuickJsToolMeta(
        requiresHost = (meta[KEY_REQUIRES_HOST] as? JsonPrimitive)?.booleanOrNull ?: false,
        supportedDrivers = meta.readStringList(KEY_SUPPORTED_DRIVERS),
        supportedPlatforms = meta.readStringList(KEY_SUPPORTED_PLATFORMS).map { it.uppercase() },
        surfaceToLlm = (meta[KEY_SURFACE_TO_LLM] as? JsonPrimitive)?.booleanOrNull ?: true,
        isRecordable = (meta[KEY_IS_RECORDABLE] as? JsonPrimitive)?.booleanOrNull ?: true,
      )
    }

    private fun JsonObject.readStringList(key: String): List<String> {
      val element = this[key] ?: return emptyList()
      val array = element as? JsonArray ?: return emptyList()
      return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
  }
}

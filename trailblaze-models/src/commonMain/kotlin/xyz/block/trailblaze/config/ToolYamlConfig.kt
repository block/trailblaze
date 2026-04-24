package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonEncoder
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Schema for per-tool `.yaml` files in `trailblaze-config/tools/`.
 *
 * Supports two authoring modes, selected by which field is present:
 *
 * - **`class:` mode** (existing) — Kotlin-backed tool. [toolClass] points to a fully qualified
 *   class name; [description] and [parameters] are reflected from the class.
 *
 *   ```yaml
 *   id: my_custom_tool
 *   class: com.example.tools.MyCustomTrailblazeTool
 *   ```
 *
 * - **`tools:` mode** (YAML-defined) — static composition. [toolsList] is an inline list of
 *   Trailblaze tool calls. [description] and [parameters] MUST be supplied in the YAML since
 *   there is no Kotlin class to reflect. Parameter values are substituted into the tool tree
 *   via `{{params.x}}` tokens at expansion time.
 *
 *   ```yaml
 *   id: eraseText
 *   description: "Erases characters from the focused text field."
 *   parameters:
 *     - name: charactersToErase
 *       type: integer
 *       required: false
 *   tools:
 *     - maestro:
 *         commands:
 *           - eraseText:
 *               charactersToErase: "{{params.charactersToErase}}"
 *   ```
 *
 * Exactly one of [toolClass] or [toolsList] must be present. Load-time validation enforces this
 * in [validate] — this class itself is a plain data holder to keep kaml deserialization boring.
 */
@Serializable
data class ToolYamlConfig(
  val id: String,
  @SerialName("class") val toolClass: String? = null,
  val description: String? = null,
  val parameters: List<TrailblazeToolParameterConfig> = emptyList(),
  @SerialName("tools")
  @Serializable(with = ToolsListSerializer::class)
  val toolsList: List<JsonObject>? = null,
) {
  /** Which authoring mode this config is in, derived from which fields are populated. */
  val mode: Mode
    get() = when {
      toolClass != null && toolsList != null ->
        error("Tool '$id' declares both 'class:' and 'tools:' — exactly one is allowed")
      toolClass != null -> Mode.CLASS
      toolsList != null -> Mode.TOOLS
      else -> error("Tool '$id' declares neither 'class:' nor 'tools:' — exactly one is required")
    }

  /**
   * Enforces the one-of rule and mode-specific required-field rules. Call at load time after
   * deserialization.
   */
  fun validate() {
    val m = mode // triggers one-of check
    when (m) {
      Mode.CLASS -> {
        require(description == null) {
          "Tool '$id' uses 'class:' mode; 'description' is reflected from the class and must not " +
            "be declared in YAML"
        }
        require(parameters.isEmpty()) {
          "Tool '$id' uses 'class:' mode; 'parameters' are reflected from the class and must not " +
            "be declared in YAML"
        }
      }
      Mode.TOOLS -> {
        require(!description.isNullOrBlank()) {
          "Tool '$id' uses 'tools:' mode and must declare a 'description:' (nothing to reflect)"
        }
        val paramNames = parameters.map { it.name }
        require(paramNames.size == paramNames.toSet().size) {
          "Tool '$id' declares duplicate parameter names: $paramNames"
        }
        paramNames.forEach { name ->
          require(name.matches(PARAM_NAME_REGEX)) {
            "Tool '$id' parameter name '$name' is invalid — must match ${PARAM_NAME_REGEX.pattern} " +
              "(no dots; those are reserved for namespace separators in {{params.x}} / {{memory.x}})"
          }
        }
      }
    }
  }

  enum class Mode { CLASS, TOOLS }

  companion object {
    /** Reserved identifier grammar for parameter names. Dots are forbidden — they separate
     *  namespaces in interpolation tokens like `{{params.x}}` / `{{memory.x}}`. */
    val PARAM_NAME_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
  }
}

/**
 * Serializes `List<JsonObject>` from kaml (YAML) or kotlinx-json. Routes YAML through
 * [YamlJsonBridge] since the stock `JsonObject` serializer only works with [JsonDecoder].
 */
internal object ToolsListSerializer : KSerializer<List<JsonObject>> {
  private val jsonListSerializer = ListSerializer(JsonElement.serializer())

  override val descriptor: SerialDescriptor = jsonListSerializer.descriptor

  override fun serialize(encoder: Encoder, value: List<JsonObject>) {
    if (encoder is JsonEncoder) {
      encoder.encodeJsonElement(JsonArray(value))
    } else {
      // Encode as a list of nested maps via YamlJsonBridge.
      error("ToolsListSerializer: non-JSON encoding is not yet wired (YAML encode of tools blocks)")
    }
  }

  override fun deserialize(decoder: Decoder): List<JsonObject> = when (decoder) {
    is YamlInput -> {
      val node = decoder.node
      require(node is YamlList) {
        "Expected a list for tools:, got ${node::class.simpleName}"
      }
      node.items.map { item ->
        YamlJsonBridge.yamlNodeToJsonElement(item) as? JsonObject
          ?: error("Each tool entry must be a map, got ${item::class.simpleName}")
      }
    }
    is JsonDecoder -> {
      val elem = decoder.decodeJsonElement()
      val array = elem as? JsonArray
        ?: error("Expected a JSON array for tools:, got ${elem::class.simpleName}")
      array.mapIndexed { index, item ->
        item as? JsonObject
          ?: error(
            "Each tool entry must be a JSON object, got ${item::class.simpleName} at index $index",
          )
      }
    }
    else -> error("ToolsListSerializer: unsupported decoder ${decoder::class.simpleName}")
  }
}

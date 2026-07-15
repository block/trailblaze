package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.DefaultBehavior
import xyz.block.trailblaze.logs.client.temp.JsonElementSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Declares one parameter of a parameterized trail — the value side of a trail's
 * `config.args:` map, keyed by the argument NAME (so the config has no `name:` field here).
 * A trail declaring `args:` is a *parameterized* trail: the caller supplies values per run
 * (`--arg KEY=VAL` / `--args-file`), which resolve into `{{args.x}}` tokens everywhere memory
 * tokens resolve.
 *
 * Mirrors [xyz.block.trailblaze.config.TrailblazeToolParameterConfig] (the tool-definition-local
 * `params.` family) so the two declaration surfaces stay shaped the same — `args` are declared
 * vs. `params` are declared, and both pass typed values through the same coercion + default
 * machinery.
 *
 * ```yaml
 * config:
 *   args:
 *     # Compact shorthand — bare type scalar, required (no default):
 *     recipient: string
 *     # Full form:
 *     subject:
 *       type: string
 *       description: "Email subject line"
 *     retries:
 *       type: integer
 *       default: 3          # `default:` present => optional
 *     verbose:
 *       type: boolean
 *       default: false
 * ```
 *
 * ## Optional vs. required (Terraform's rule)
 *
 * Presence of `default:` is the ONLY switch: `default:` present ⇔ optional; absent ⇔ required.
 * There is no `?` type suffix. An explicit `required:` field is accepted but only to *confirm*
 * what `default:` already implies — `required: false` requires a `default:`, and `required: true`
 * with a `default:` is a contradiction. Both are rejected at parse ([TrailArgConfigSerializer]).
 *
 * [default] is presence-aware via [DefaultBehavior] exactly like the tool-parameter config: an
 * absent `default:` is [DefaultBehavior.DropIfOmitted] (⇒ required); a declared `default:` is
 * [DefaultBehavior.Use]. Defaults may themselves be tokens (`default: '{{memory.email}}'`),
 * resolved against memory at seed time.
 *
 * ## Types
 *
 * v1 EXECUTES [STRING], [INTEGER], [BOOLEAN] (the JSON-Schema words the tool `parameters:` surface
 * already uses). [ARRAY] / [OBJECT] are accepted by the grammar and the args file from day one but
 * their substitution is DEFERRED — a token that resolves to an array/object value is left literal
 * with a one-time "not yet executed" diagnostic rather than substituted.
 */
@Serializable(with = TrailArgConfigSerializer::class)
data class TrailArgConfig(
  val type: String = STRING,
  val description: String? = null,
  val default: DefaultBehavior = DefaultBehavior.DropIfOmitted,
) {
  /** Terraform's rule: a declared `default:` makes the arg optional; its absence makes it required. */
  val required: Boolean get() = default is DefaultBehavior.DropIfOmitted

  companion object {
    const val STRING = "string"
    const val INTEGER = "integer"
    const val BOOLEAN = "boolean"
    const val ARRAY = "array"
    const val OBJECT = "object"

    /**
     * Every type the grammar accepts. Array/object parse and bind fine, but their token
     * substitution is deferred — the runtime gates that on the VALUE's shape (`is JsonPrimitive`
     * in `AgentMemory`), deliberately not on the declared type, so un-deferral is a runtime
     * change with no new declaration surface.
     */
    val VALID_TYPES = setOf(STRING, INTEGER, BOOLEAN, ARRAY, OBJECT)

    /** Validate a declared arg type is one the grammar accepts; returns it for chaining. */
    fun validateType(type: String): String {
      require(type in VALID_TYPES) {
        "Trail arg has invalid type '$type' — must be one of ${VALID_TYPES.sorted()}."
      }
      return type
    }
  }
}

/**
 * Map serializer for a trail's `config.args:` block. Needed because, in YAML, a declared arg's value
 * may be a bare type scalar (`recipient: string`, the compact shorthand) OR a full map
 * (`recipient: { type: string }`), and kaml refuses to hand a scalar node to a class-kind value
 * serializer. This serializer reads the raw `args:` map and, per entry, builds the shorthand form
 * directly while routing the full form through [TrailArgConfigSerializer].
 *
 * JSON is the log wire format: a parameterized trail's config round-trips through JSON on the
 * `/agentlog` path (TrailblazeLog → SessionStatus.Started → TrailConfig.args). There, args are
 * always the full object-per-arg form — the scalar shorthand and arg-name validation are YAML
 * authoring concerns that never reach the wire — so each value is routed through the JSON-aware
 * [TrailArgConfigSerializer] individually (rather than one blanket map decode), which lets a
 * decode failure name the offending arg.
 */
object TrailArgMapSerializer : KSerializer<Map<String, TrailArgConfig>> {
  private val delegate = MapSerializer(String.serializer(), TrailArgConfig.serializer())
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun serialize(encoder: Encoder, value: Map<String, TrailArgConfig>) {
    encoder.encodeSerializableValue(delegate, value)
  }

  override fun deserialize(decoder: Decoder): Map<String, TrailArgConfig> = when (decoder) {
    is YamlInput -> deserializeFromYaml(decoder)
    is JsonDecoder -> deserializeFromJson(decoder)
    else -> decoder.decodeSerializableValue(delegate)
  }

  private fun deserializeFromYaml(decoder: YamlInput): Map<String, TrailArgConfig> {
    val node = decoder.node
    require(node is YamlMap) { "config.args must be a map of arg-name to declaration, got ${node::class.simpleName}." }
    val result = LinkedHashMap<String, TrailArgConfig>()
    for ((keyNode, valueNode) in node.entries) {
      val name = keyNode.content
      require(TrailArgTokens.isValidArgName(name)) {
        "Trail arg name '$name' is not a legal identifier — arg names must match " +
          "[a-zA-Z_][a-zA-Z0-9_]* (no dashes, dots, or spaces) so `{{args.$name}}` tokens can " +
          "reference them."
      }
      result[name] = when (valueNode) {
        is YamlScalar -> TrailArgConfig(type = TrailArgConfig.validateType(valueNode.content))
        else -> decoder.yaml.decodeFromYamlNode(TrailArgConfig.serializer(), valueNode)
      }
    }
    return result
  }

  /**
   * Decodes each entry through [TrailArgConfigSerializer] individually (rather than one blanket
   * [decodeSerializableValue]) so a decode failure names the offending arg — without this, a
   * malformed entry surfaces a generic error with no indication of which of possibly several args
   * was bad, harder to triage from a production log than the arg-name-aware YAML errors above.
   */
  private fun deserializeFromJson(decoder: JsonDecoder): Map<String, TrailArgConfig> {
    val element = decoder.decodeJsonElement()
    require(element is JsonObject) {
      "config.args (JSON) must be an object of arg-name to declaration, got ${element::class.simpleName}."
    }
    return element.mapValues { (name, value) ->
      try {
        decoder.json.decodeFromJsonElement(TrailArgConfig.serializer(), value)
      } catch (e: Exception) {
        throw IllegalArgumentException("Trail arg '$name' (JSON): ${e.message}", e)
      }
    }
  }
}

/**
 * Presence-aware serializer for [TrailArgConfig], mirroring
 * [xyz.block.trailblaze.config.TrailblazeToolParameterConfigSerializer]: kotlinx.serialization can't
 * tell "key absent" from "key present, null value", but kaml's [YamlInput] exposes the raw node so
 * `default:` presence maps to [DefaultBehavior] correctly. Adds a compact shorthand — a bare scalar
 * value (`recipient: string`) is read as `type:` with no default (⇒ required) — and validates the
 * `type` and the `default`/`required` relationship at parse time.
 */
object TrailArgConfigSerializer : KSerializer<TrailArgConfig> {

  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("TrailArgConfig") {
      element<String>("type")
      element<String>("description", isOptional = true)
      element<String>("default", isOptional = true)
    }

  override fun serialize(encoder: Encoder, value: TrailArgConfig) = when (encoder) {
    is JsonEncoder -> serializeToJson(encoder, value)
    else -> serializeToYaml(encoder, value)
  }

  /**
   * JSON is a machine transport (the log wire): emit a faithful JSON object so the round-trip is
   * lossless — a numeric/boolean default stays a JSON number/boolean and an array/object default
   * stays structural, so decode reconstructs the exact `DefaultBehavior.Use` value. [serializeToYaml]
   * deliberately stringifies a primitive default (its raw scalar re-reads against `type` on the
   * next parse) — JSON has no re-coercion step, so it must not stringify. `required` is a
   * YAML-authoring-only field (a redundant confirmation of `default:` presence, validated then
   * discarded) — [TrailArgConfig] itself has no `required` property, so there's nothing to write.
   */
  private fun serializeToJson(encoder: JsonEncoder, value: TrailArgConfig) {
    encoder.encodeJsonElement(
      buildJsonObject {
        put("type", value.type)
        value.description?.let { put("description", it) }
        (value.default as? DefaultBehavior.Use)?.let { put("default", it.value) }
      },
    )
  }

  private fun serializeToYaml(encoder: Encoder, value: TrailArgConfig) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.type)
      value.description?.let { encodeStringElement(descriptor, 1, it) }
      // A scalar default round-trips as its raw content; because coercion is declaration-driven
      // (re-read against `type` on the next parse), emitting the raw scalar is value-lossless for
      // string/integer/boolean. An array/object default has no scalar form, so it goes through the
      // JsonElement<->structural bridge `YamlDefinedToolSerializer` uses to write a params tree back
      // to YAML (kaml has no native `JsonElement` support).
      (value.default as? DefaultBehavior.Use)?.value?.let { defaultElement ->
        when (defaultElement) {
          is JsonPrimitive -> encodeStringElement(descriptor, 2, defaultElement.content)
          else -> encodeSerializableElement(
            descriptor,
            2,
            JsonElementSerializer,
            YamlJsonBridge.jsonElementToSerializable(defaultElement),
          )
        }
      }
    }
  }

  override fun deserialize(decoder: Decoder): TrailArgConfig = when (decoder) {
    is JsonDecoder -> deserializeFromJson(decoder)
    is YamlInput -> deserializeFromYaml(decoder)
    else -> error("TrailArgConfig can only be deserialized from YAML or JSON, got ${decoder::class.simpleName}.")
  }

  private fun deserializeFromYaml(decoder: YamlInput): TrailArgConfig {
    val node = decoder.node

    // Compact shorthand: `argName: string` — the scalar IS the type, no default (⇒ required).
    // Reached only if TrailArgConfig.serializer() is applied to a scalar directly; the normal path
    // ([TrailArgMapSerializer]) builds the shorthand without invoking this serializer.
    if (node is YamlScalar) {
      return TrailArgConfig(type = TrailArgConfig.validateType(node.content))
    }

    require(node is YamlMap) {
      "A trail arg must be a type scalar (e.g. `email: string`) or a map " +
        "(e.g. `email: { type: string, default: '' }`), got ${node::class.simpleName}."
    }
    val entries = node.entries.entries.associate { (k, v) -> k.content to v }

    val type = TrailArgConfig.validateType(entries["type"]?.let { scalarContent(it, "type") } ?: TrailArgConfig.STRING)
    val description = entries["description"]?.let { scalarContent(it, "description") }
    val explicitRequired = entries["required"]?.let {
      decoder.yaml.decodeFromYamlNode(Boolean.serializer(), it)
    }
    val default = if (entries.containsKey("default")) {
      val defaultNode = entries.getValue("default")
      // Preserve a string default's raw scalar (YamlJsonBridge would coerce "007" -> 7); other
      // types decode generically so array/object defaults survive as their JSON shape.
      val jsonElement: JsonElement = if (type == TrailArgConfig.STRING && defaultNode is YamlScalar) {
        JsonPrimitive(defaultNode.content)
      } else {
        YamlJsonBridge.yamlNodeToJsonElement(defaultNode)
      }
      // The bind boundary rejects a PROVIDED null loudly; a DECLARED `default: null` must not
      // slip past it into DefaultBehavior.Use and fail later with a generic coercion error.
      require(jsonElement !is JsonNull) {
        "Trail arg declares `default: null` — args have no null (optional is not nullable). " +
          "Use `default: ''` for an empty string, or omit `default:` to keep the arg required."
      }
      DefaultBehavior.Use(jsonElement)
    } else {
      DefaultBehavior.DropIfOmitted
    }

    return TrailArgConfig(type = type, description = description, default = default)
      .also { validateRequiredRelationship(it, explicitRequired) }
  }

  /**
   * JSON transport inverse of the [JsonEncoder] branch of [serialize]. Unlike [deserializeFromYaml],
   * this never sees the scalar shorthand or a `required:` field — both are YAML-authoring
   * conveniences that never reach the wire, so only `type`/`description`/`default` are read and
   * [validateRequiredRelationship] doesn't apply. This decoder also reads arbitrary historical log
   * files, not just our own [serialize] output, so a field that is PRESENT but malformed (an
   * object/array/null where a scalar default is expected) must fail loudly rather than be treated
   * the same as an ABSENT field and silently defaulted — mirroring the YAML branch's own
   * scalar/null guards.
   */
  private fun deserializeFromJson(decoder: JsonDecoder): TrailArgConfig {
    val element = decoder.decodeJsonElement()
    require(element is JsonObject) {
      "A trail arg (JSON) must be an object, got ${element::class.simpleName}."
    }
    val type = TrailArgConfig.validateType(jsonScalarField(element, "type") ?: TrailArgConfig.STRING)
    val description = jsonScalarField(element, "description")
    // Key present => DefaultBehavior.Use (kept verbatim as the emitted JSON value); absent =>
    // DropIfOmitted (⇒ required). This is the exact inverse of the JsonEncoder serialize branch.
    val default = element["default"]?.let {
      require(it !is JsonNull) { "Trail arg (JSON) declares a null default — args have no null (optional is not nullable)." }
      DefaultBehavior.Use(it)
    } ?: DefaultBehavior.DropIfOmitted
    return TrailArgConfig(type = type, description = description, default = default)
  }

  /**
   * Extracts a present JSON field's raw scalar content, distinguishing "absent" (returns `null`,
   * so the caller applies its own default) from "present but not a scalar" (throws) — a malformed
   * `type`/`description` (object, array, or explicit JSON null) must fail loudly rather than
   * silently fall through to the same default used for an absent key.
   */
  private fun jsonScalarField(element: JsonObject, key: String): String? {
    val value = element[key] ?: return null
    require(value is JsonPrimitive && value !is JsonNull) {
      "Trail arg (JSON) field '$key' must be a scalar, got ${value::class.simpleName}."
    }
    return value.content
  }

  private fun validateRequiredRelationship(config: TrailArgConfig, explicitRequired: Boolean?) {
    if (explicitRequired == null) return
    val hasDefault = config.default is DefaultBehavior.Use
    require(!(explicitRequired && hasDefault)) {
      "Trail arg declares `required: true` alongside a `default:` — a default makes an arg optional. " +
        "Drop one: omit `default:` to keep it required, or drop `required: true`."
    }
    require(!(!explicitRequired && !hasDefault)) {
      "Trail arg declares `required: false` without a `default:` — an optional arg needs a default " +
        "(there is no null in the args value domain). Add a `default:`, or drop `required: false`."
    }
  }

  private fun scalarContent(node: YamlNode, key: String): String =
    (node as? YamlScalar)?.content
      ?: error("Trail arg field '$key' must be a scalar, got ${node::class.simpleName}.")
}

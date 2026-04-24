package xyz.block.trailblaze.mobile.tools

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A single broadcast-intent extra, using the same type names as `Intent.putExtra`
 * on the Android Java API — `string`, `boolean`, `int`, `long`, `float`.
 *
 * [value] is always the literal string a user would type on the `am broadcast` CLI
 * (matching how `am broadcast` itself takes every argument as text and lets Android
 * parse it). [type] is matched case-insensitively and also accepts the short-form
 * `am broadcast` CLI flags (`es`/`ez`/`ei`/`el`/`ef`) as aliases for
 * `string`/`boolean`/`int`/`long`/`float`; short forms are normalized to the long form
 * before storage so log output stays consistent.
 *
 * See the Android docs for the underlying `am broadcast` CLI:
 * https://developer.android.com/tools/adb#IntentSpec
 *
 * YAML supports two shapes (interpreted by [BroadcastExtrasMapSerializer]):
 * ```yaml
 * extras:
 *   enable_test_mode: "1"            # shorthand → type "string"
 *   count:
 *     value: "42"
 *     type: int
 * ```
 */
@Serializable
data class BroadcastExtra(
  val value: String,
  val type: String = SupportedExtraType.DEFAULT.typeName,
) {
  /**
   * Coerces [value] to the Kotlin type that [BroadcastIntent.extras] expects.
   *
   * Both device-side code paths dispatch on the resulting Kotlin runtime type, so
   * adding a new broadcast extra type only requires extending [SupportedExtraType].
   */
  fun toTypedValue(): Any {
    val supportedType = SupportedExtraType.fromTypeName(type) ?: error(
      "Unsupported broadcast extra type '$type' (value='$value'). " +
        "Currently supported: ${SupportedExtraType.allTypeNames()}. " +
        "See https://developer.android.com/tools/adb#IntentSpec for the full set.",
    )
    return try {
      when (supportedType) {
        SupportedExtraType.STRING -> value
        SupportedExtraType.BOOLEAN -> value.toBooleanStrict()
        SupportedExtraType.INT -> value.toInt()
        SupportedExtraType.LONG -> value.toLong()
        SupportedExtraType.FLOAT -> value.toFloat()
      }
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException(
        "Failed to parse broadcast extra value='$value' as type '$type'.",
        e,
      )
    }
  }
}

/**
 * Internal mapping from public type name → Kotlin target type. Exists purely for
 * readability of [BroadcastExtra.toTypedValue]; the public API stays a raw [String]
 * so new types can be added by extending this enum without changing the YAML schema.
 *
 * [typeName] matches `Intent.putExtra` in Android's Java API; [cliFlag] is the
 * equivalent `am broadcast` CLI flag (without the leading `--`).
 */
private enum class SupportedExtraType(val typeName: String, val cliFlag: String) {
  STRING("string", "es"),
  BOOLEAN("boolean", "ez"),
  INT("int", "ei"),
  LONG("long", "el"),
  FLOAT("float", "ef"),
  ;

  companion object {
    val DEFAULT = STRING
    fun fromTypeName(name: String): SupportedExtraType? = entries.firstOrNull {
      it.typeName.equals(name, ignoreCase = true) || it.cliFlag.equals(name, ignoreCase = true)
    }
    fun canonicalize(name: String): String = fromTypeName(name)?.typeName ?: name
    fun allTypeNames(): String = entries.joinToString { "${it.typeName} (or ${it.cliFlag})" }
  }
}

/**
 * Custom serializer for a `Map<String, BroadcastExtra>` that accepts a bare YAML
 * scalar as shorthand for the common `value: <scalar>, type: string` case:
 *
 * ```yaml
 * extras:
 *   enable_test_mode: "1"                 # shorthand → BroadcastExtra(value = "1")
 *   count:                                # full form
 *     value: "42"
 *     type: int
 * ```
 *
 * Shorthand is input-only: encoding always emits the canonical object form
 * (`{ value: ..., type: ... }`), so a YAML decode → encode round-trip will expand
 * every shorthand back to its full form. This keeps JSON log output uniform.
 *
 * Lives at the map level (not on [BroadcastExtra] itself) because kaml's map
 * decoder inspects element descriptors up-front and rejects scalar values for
 * class-shaped elements before any element-level serializer gets to run.
 */
object BroadcastExtrasMapSerializer : KSerializer<Map<String, BroadcastExtra>> {
  private val fallback = MapSerializer(String.serializer(), BroadcastExtra.serializer())

  override val descriptor: SerialDescriptor = fallback.descriptor

  override fun serialize(encoder: Encoder, value: Map<String, BroadcastExtra>) {
    fallback.serialize(encoder, value)
  }

  override fun deserialize(decoder: Decoder): Map<String, BroadcastExtra> {
    if (decoder !is YamlInput) return fallback.deserialize(decoder)

    val node = decoder.node
    require(node is YamlMap) {
      "Expected a map for broadcast extras, got ${node::class.simpleName}"
    }
    return node.entries.entries.associate { (keyNode, valueNode) ->
      val key = keyNode.content
      val extra = when (valueNode) {
        is YamlScalar -> BroadcastExtra(value = valueNode.content)
        is YamlMap -> decodeBroadcastExtra(valueNode, key)
        else -> error(
          "Expected scalar or map for broadcast extra '$key', got ${valueNode::class.simpleName}",
        )
      }
      key to extra
    }
  }

  private fun decodeBroadcastExtra(node: YamlMap, key: String): BroadcastExtra {
    var value: String? = null
    var type: String? = null
    node.entries.forEach { (keyNode, valueNode) ->
      when (val fieldName = keyNode.content) {
        "value" -> value = (valueNode as? YamlScalar)?.content
          ?: error("Expected scalar for 'value' of broadcast extra '$key'")
        "type" -> type = (valueNode as? YamlScalar)?.content
          ?: error("Expected scalar for 'type' of broadcast extra '$key'")
        else -> error(
          "Unknown key '$fieldName' on broadcast extra '$key'. Expected 'value' or 'type'.",
        )
      }
    }
    val resolvedValue = value ?: error("Broadcast extra '$key' is missing a 'value'")
    return type?.let { BroadcastExtra(value = resolvedValue, type = SupportedExtraType.canonicalize(it)) }
      ?: BroadcastExtra(value = resolvedValue)
  }
}

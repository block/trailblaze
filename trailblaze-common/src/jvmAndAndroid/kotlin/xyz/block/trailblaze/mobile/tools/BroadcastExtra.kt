package xyz.block.trailblaze.mobile.tools

import kotlinx.serialization.Serializable

/**
 * A single broadcast-intent extra, using the same type names as `Intent.putExtra`
 * on the Android Java API — `string`, `boolean`, `int`, `long`, `float`.
 *
 * [value] is always the literal string a user would type on the `am broadcast` CLI
 * (matching how `am broadcast` itself takes every argument as text and lets Android
 * parse it). [type] is matched case-insensitively and also accepts the short-form
 * `am broadcast` CLI flags (`es`/`ez`/`ei`/`el`/`ef`) as aliases for
 * `string`/`boolean`/`int`/`long`/`float`; both forms are accepted at [toTypedValue]
 * via case-insensitive lookup.
 *
 * See the Android docs for the underlying `am broadcast` CLI:
 * https://developer.android.com/tools/adb#IntentSpec
 *
 * Extras are authored as a closed-shape list — each entry carries its [key] alongside the
 * value/type — which keeps the tool reachable from the typed scripted-tool surface
 * (`client.tools.android_sendBroadcast`). The per-trailmap d.ts emitter currently lowers
 * `List<DataClass>` to `Array<unknown>` rather than rendering the nested object element type,
 * so the typed binding's `extras` is effectively `unknown[]` in `client.d.ts` today — the win
 * is the binding existing at all (a Map-shaped param made the whole tool skip codegen):
 *
 * ```yaml
 * extras:
 *   - key: enable_test_mode
 *     value: "1"
 *   - key: count
 *     value: "42"
 *     type: int
 * ```
 *
 * NOTE: this class is referenced by fully-qualified name as the teaching example in
 * `xyz.block.trailblaze.toolcalls.TrailblazeKoogToolExt.toScriptedToolDescriptor`. If you
 * rename or move it, update that swallow-message reference too.
 */
@Serializable
data class BroadcastExtra(
  val key: String,
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
    fun allTypeNames(): String = entries.joinToString { "${it.typeName} (or ${it.cliFlag})" }
  }
}

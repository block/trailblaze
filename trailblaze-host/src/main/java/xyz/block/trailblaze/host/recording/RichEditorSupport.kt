package xyz.block.trailblaze.host.recording

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import kotlin.reflect.KClass

/**
 * Best-effort lookup for the rich-editor surface in [ActionYamlCard]. Resolves the descriptor
 * the form widgets need (param names, types, descriptions, enum values) by reading the tool
 * class's `@TrailblazeToolClass` annotation through [toKoogToolDescriptor], then falls back
 * to `null` for tools the descriptor pipeline can't represent — `isForLlm = false` tools
 * (`SwipeWithRelativeCoordinatesTool`, internal/wrapper tools, etc.) intentionally short-
 * circuit there. Callers treat null as "fall back to YAML editor" so we don't lose the
 * ability to edit those by-text.
 *
 * Lives next to the screen stream rather than in [RecordingWidgets] because the JSON
 * round-trip relies on JVM-only `TrailblazeJsonInstance` reflection — promoting this to
 * commonMain would need an expect/actual seam for the serializer, which is bigger than the
 * recording editor alone justifies.
 */
fun resolveDescriptorAndValues(
  tool: TrailblazeTool,
): Pair<TrailblazeToolDescriptor, Map<String, String>>? {
  val descriptor = tool::class.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
    ?: return null
  val values = extractParamValues(tool) ?: return null
  return descriptor to values
}

/**
 * Convert a [TrailblazeTool] instance to a flat `name → string-value` map by serializing
 * through [TrailblazeJsonInstance] and reading top-level fields. Returns null when *any*
 * field decodes to a non-primitive shape (nested object / array) — the rich form only
 * renders flat string/number/boolean inputs, so promoting a structured-value tool into the
 * form would silently drop the nested data on save. Falling back to the YAML editor
 * preserves it.
 *
 * **Why the explicit concrete-class serializer.** The `TrailblazeTool` interface is
 * registered in [TrailblazeJsonInstance] with a contextual serializer
 * ([xyz.block.trailblaze.toolcalls.TrailblazeToolJsonSerializer]) that wraps every tool as
 * `{toolName, raw}` for the executor-boundary call sites (RPC, recordings on disk). If we
 * called `Json.encodeToJsonElement(tool)` here with `tool` typed as the abstract interface
 * we'd get that wrapped shape — `toolName` and `raw` as the top-level keys, with the actual
 * field values hidden inside the `raw` JSON string. Resolving the concrete class's
 * serializer first sidesteps the wrapper and gives us the field-level JSON we need for the
 * form (e.g. `{text: "abc", reasoning: null}` for `inputText`).
 *
 * Primitives are coerced to strings via [JsonPrimitive.contentOrNull]; an explicit JSON
 * `null` field is represented as the empty string (matching what the form would emit when
 * the user clears an optional field). Polymorphic discriminator fields (`class` / `type`)
 * are dropped because they're never user-editable params.
 */
@OptIn(InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
private fun extractParamValues(tool: TrailblazeTool): Map<String, String>? {
  val concreteSerializer = (tool::class as KClass<TrailblazeTool>).serializer()
  val element = TrailblazeJsonInstance.encodeToJsonElement(concreteSerializer, tool)
  val obj = element as? JsonObject ?: return emptyMap()
  val out = LinkedHashMap<String, String>()
  for ((key, value) in obj) {
    if (key == "class" || key == "type") continue
    val primitive = value as? JsonPrimitive ?: return null
    out[key] = primitive.contentOrNull ?: ""
  }
  return out
}

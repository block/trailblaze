package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console

/**
 * Field names for the persisted [OtherTrailblazeTool] `{toolName, raw}` wire shape. Centralized
 * so [TrailblazeToolJsonSerializer], [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeToolFlatSerializer],
 * and any future serializer that emits the same shape stay in sync — drift between them would
 * silently lose data on round-trip.
 */
const val OTHER_TRAILBLAZE_TOOL_NAME_FIELD = "toolName"
const val OTHER_TRAILBLAZE_TOOL_RAW_FIELD = "raw"

/**
 * Encodes any [TrailblazeTool] into the persisted [OtherTrailblazeTool] `{toolName, raw}`
 * shape — the canonical encoding used for log persistence and JSON round-trips of
 * `@Contextual TrailblazeTool` fields.
 *
 * Branches:
 * - [OtherTrailblazeTool]: pass through unchanged (already in payload shape).
 * - [RawArgumentTrailblazeTool]: dynamic / scripted tool with verbatim args — wrap with
 *   `(instanceToolName, rawToolArguments)`.
 * - [InstanceNamedTrailblazeTool] (catch-all for tools that carry an instance-level name):
 *   use [InstanceNamedTrailblazeTool.instanceToolName] for the wire `toolName` and encode
 *   `raw` via the tool's class serializer. This is the path host-local subprocess tools
 *   (`HostLocalExecutableTrailblazeTool`, dynamically-constructed) take — their meaningful
 *   identifier is the instance name, not `class.simpleName`.
 * - Everything else: encode via the tool's concrete `@Serializable` class serializer into
 *   `raw`, and look up `toolName` from the class's `@TrailblazeToolClass` annotation.
 *
 * **Does NOT handle [InstanceNamedTrailblazeTool] backed by a YAML-defined serializer** —
 * that path requires JVM-side reflection into `TrailblazeSerializationInitializer` (which
 * lives in JVM/Android source sets only) and is handled by
 * `trailblaze-common`'s `TrailblazeTool.toLogPayload()` extension before falling through here.
 *
 * Falls back gracefully on serialization failures: a non-`@Serializable` tool, a class
 * missing `@TrailblazeToolClass`, or a serializer that throws all produce a structurally
 * valid payload (best-available `toolName`, empty `raw`) plus a diagnostic [Console] warning
 * so the failure is observable in logs.
 */
fun TrailblazeTool.toOtherTrailblazeToolPayload(): OtherTrailblazeTool {
  if (this is OtherTrailblazeTool) return this
  if (this is RawArgumentTrailblazeTool) {
    return OtherTrailblazeTool(instanceToolName, rawToolArguments)
  }
  if (this is InstanceNamedTrailblazeTool) {
    // Instance-named tool (e.g. host-local subprocess) — the meaningful identifier is the
    // dynamic instance name. Class-level annotation may be absent or generic; falling
    // through to `trailblazeToolNameOrFallback` would emit a misleading `class.simpleName`.
    return OtherTrailblazeTool(
      toolName = instanceToolName,
      raw = encodeAsRawJsonOrEmpty(),
    )
  }
  return OtherTrailblazeTool(
    toolName = trailblazeToolNameOrFallback(),
    raw = encodeAsRawJsonOrEmpty(),
  )
}

/**
 * Shared name resolution for tools encoded into the [OtherTrailblazeTool] payload shape.
 * Reads the class-level [TrailblazeToolClass] annotation via the platform-specific
 * [findTrailblazeToolClassAnnotation]; falls back to `class.simpleName` (last resort
 * `"UnknownTool"`). Centralized so [TrailblazeToolJsonSerializer] and
 * `getToolNameFromAnnotation` (in `trailblaze-common`) share the same logic instead of
 * drifting independently.
 */
internal fun TrailblazeTool.trailblazeToolNameOrFallback(): String =
  findTrailblazeToolClassAnnotation()?.name ?: this::class.simpleName ?: "UnknownTool"

/**
 * Platform-specific lookup of the class-level [TrailblazeToolClass] annotation.
 *
 * The `KClass.annotations` API isn't part of the commonMain stdlib surface — it's only
 * present on JVM-style targets where full reflection is available. Wasm/JS targets have
 * no equivalent, so this is split via `expect`/`actual`:
 * - JVM/Android: read the annotation through `kotlin.reflect.full.findAnnotation`.
 * - Wasm/JS: return `null` and let the caller fall back to `class.simpleName`. The wasm UI
 *   only DECODES log files (always to [OtherTrailblazeTool]) and never encodes tools,
 *   so the missing annotation read is graceful degradation rather than a functional gap.
 */
internal expect fun TrailblazeTool.findTrailblazeToolClassAnnotation(): TrailblazeToolClass?

/**
 * Encodes a class-backed tool through its concrete `@Serializable` serializer into a
 * [JsonObject]. Returns an empty object on failure (non-`@Serializable` tool, reflection
 * error, or serializer mismatch) plus a diagnostic [Console] warning — empty `raw` alone
 * would otherwise be indistinguishable from a tool that legitimately has no parameters.
 *
 * Cooperative cancellation is preserved: [kotlin.coroutines.cancellation.CancellationException]
 * is re-thrown rather than swallowed so a caller's coroutine cancellation can unwind cleanly.
 *
 * Diagnostic logging is throttled to one message per offending tool class per process. In a
 * high-volume eval (hundreds of tool encodes per minute), a single non-`@Serializable` tool
 * class would otherwise flood stderr with the same message every encode and degrade the
 * signal-to-noise ratio of operational logs.
 */
@OptIn(InternalSerializationApi::class)
internal fun TrailblazeTool.encodeAsRawJsonOrEmpty(): JsonObject = try {
  @Suppress("UNCHECKED_CAST")
  val concreteSerializer = this::class.serializer() as KSerializer<TrailblazeTool>
  val jsonStr = sharedLenientJson.encodeToString(concreteSerializer, this)
  sharedLenientJson.decodeFromString<JsonObject>(jsonStr)
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
  // Catch-broad below would otherwise swallow this and break cooperative cancellation.
  throw e
} catch (e: Exception) {
  // Serializer reflection can throw `SerializationException` (no serializer registered for
  // class), `IllegalArgumentException`, `NoSuchElementException`, or platform reflection
  // errors. Catch broadly so the persistence path can never hard-fail on a tool that simply
  // can't be encoded.
  val className = this::class.simpleName ?: "<anonymous>"
  if (encodeFailureKeysLogged.add(className)) {
    Console.error(
      "[TrailblazeToolPayload] Failed to encode $className as raw JSON " +
        "(${e::class.simpleName}: ${e.message}). Persisting with empty raw payload. " +
        "Subsequent failures for this class will be suppressed for the rest of this process.",
    )
  }
  JsonObject(emptyMap())
}

/**
 * Tracks tool classes that have already produced an [encodeAsRawJsonOrEmpty] failure log so
 * each offending class logs at most once per process. `MutableSet` access is guarded by the
 * platform's atomic-add semantics — we rely on the single-process, fairly-coarse-grained
 * log emit, not on strict happens-before, so a few extra log lines under contention are
 * acceptable.
 */
private val encodeFailureKeysLogged: MutableSet<String> = mutableSetOf()

/**
 * Lenient [Json] used only for class-backed tool encoding. Strict mode would fail on tools
 * with default-valued fields, which downstream consumers tolerate. Module-private to avoid
 * leaking a permissive parser into general use.
 */
private val sharedLenientJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

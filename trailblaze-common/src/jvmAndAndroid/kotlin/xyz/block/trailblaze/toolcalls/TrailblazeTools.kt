package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

object TrailblazeTools {
  const val REQUIRED_TEXT_DESCRIPTION = """
The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.
      """

  const val REQUIRED_ACCESSIBILITY_TEXT_DESCRIPTION = """
The accessibilityText to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.
      """

  fun List<KClass<out TrailblazeTool>>.filterForMapsToMaestroCommands(): List<KClass<out TrailblazeTool>> = this.filter { it.isSubclassOf(MapsToMaestroCommands::class) }
}

// Make this a top-level public function so it can be used elsewhere
@Suppress("UNCHECKED_CAST")
fun TrailblazeTool.getToolNameFromAnnotation(): String = when {
  this is OtherTrailblazeTool -> this.toolName
  // Dynamically-constructed host-local tools (e.g. subprocess MCP) have no class-level
  // @TrailblazeToolClass — the advertised name flows through the marker interface so
  // session logging picks up the right identifier instead of the bare class simpleName.
  this is HostLocalExecutableTrailblazeTool -> this.advertisedToolName
  else -> try {
    val kClass = this::class
    val annotation = kClass.findAnnotation<TrailblazeToolClass>()
    annotation?.name ?: kClass.simpleName ?: "UnknownTool"
  } catch (e: Exception) {
    this::class.simpleName ?: "UnknownTool"
  }
}

/**
 * Resolves the recordability bit for a tool instance.
 *
 * Per-instance [TrailblazeTool.toolMetadata] (when present) wins — that's the path
 * `YamlDefinedTrailblazeTool` uses to surface its per-config flag. Otherwise, the class-level
 * [TrailblazeToolClass] annotation is authoritative. Defaults to `true` if neither resolves
 * (to match the annotation default).
 *
 * Name kept for backwards compatibility with existing call sites; the lookup logic is no
 * longer strictly "from annotation" once metadata-overriding tools are in play.
 */
fun TrailblazeTool.getIsRecordableFromAnnotation(): Boolean {
  this.toolMetadata?.isRecordable?.let { return it }
  return try {
    this::class.findAnnotation<TrailblazeToolClass>()?.isRecordable ?: true
  } catch (e: Exception) {
    Console.error(
      "Failed to read isRecordable annotation from ${this::class.simpleName}: ${e.message}. " +
        "Defaulting to true.",
    )
    true
  }
}

/**
 * Resolves the host-required bit for a tool instance.
 *
 * Instance-level analogue of [requiresHost][xyz.block.trailblaze.toolcalls.requiresHost] (the
 * KClass extension). Per-instance [TrailblazeTool.toolMetadata] (when present) wins —
 * `YamlDefinedTrailblazeTool` uses that path to expose its per-config flag, since the shared
 * implementation class annotation can't carry per-instance data. For everything else the
 * class-level annotation is authoritative; defaults to `false`.
 */
fun TrailblazeTool.requiresHostInstance(): Boolean {
  this.toolMetadata?.requiresHost?.let { return it }
  return try {
    this::class.findAnnotation<TrailblazeToolClass>()?.requiresHost ?: false
  } catch (e: Exception) {
    Console.error(
      "Failed to read requiresHost annotation from ${this::class.simpleName}: ${e.message}. " +
        "Defaulting to false.",
    )
    false
  }
}

/**
 * Wraps any [TrailblazeTool] into the persisted [OtherTrailblazeTool] log-payload shape:
 * `{"toolName": "...", "raw": {...flat tool params...}}`.
 *
 * Logs persist this shape so that a viewer or downstream parser can identify the tool by name
 * without needing the implementing class on the classpath.
 *
 * The YAML-defined-tool branch (`InstanceNamedTrailblazeTool` with a registered
 * `YamlDefinedToolSerializer`) is handled here because it requires the JVM-side
 * `TrailblazeSerializationInitializer` lookup — everything else delegates to the shared
 * common-main canonical encoder [toOtherTrailblazeToolPayload], which is also what
 * [TrailblazeToolJsonSerializer] uses for `@Contextual TrailblazeTool` JSON encoding so
 * both paths produce identical wire formats.
 */
fun TrailblazeTool.toLogPayload(): OtherTrailblazeTool {
  if (this is OtherTrailblazeTool) return this
  if (this is RawArgumentTrailblazeTool) {
    return OtherTrailblazeTool(instanceToolName, rawToolArguments)
  }
  if (this is InstanceNamedTrailblazeTool) {
    val yamlSerializer =
      TrailblazeSerializationInitializer.buildYamlDefinedToolSerializers()[ToolName(instanceToolName)]
    if (yamlSerializer != null) {
      val encoded = try {
        @Suppress("UNCHECKED_CAST")
        val rawJson = TrailblazeJsonInstance.encodeToString(
          yamlSerializer as KSerializer<TrailblazeTool>,
          this,
        )
        TrailblazeJsonInstance.decodeFromString<JsonObject>(rawJson)
      } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        // Re-throw cancellation so the catch-broad below can't swallow it.
        throw e
      } catch (e: Exception) {
        // YAML-defined tool whose serializer failed (param-shape drift, registry mismatch,
        // reflection error, or other). Catch broadly — the persistence path can never
        // hard-fail on a tool that simply can't be encoded — but log once per tool name so
        // the failure is observable without flooding stderr in a high-volume eval.
        if (yamlEncodeFailureNamesLogged.add(instanceToolName)) {
          Console.error(
            "[toLogPayload] YAML-defined tool '$instanceToolName' serializer failed " +
              "(${e::class.simpleName}: ${e.message}). Persisting with empty raw payload. " +
              "Subsequent failures for this tool name will be suppressed.",
          )
        }
        JsonObject(emptyMap())
      }
      return OtherTrailblazeTool(instanceToolName, encoded)
    }
  }
  // Class-backed (`@TrailblazeToolClass`), or anything else without a YAML serializer —
  // delegate to the shared canonical encoder. It handles the class.serializer() lookup,
  // the @TrailblazeToolClass name fallback, and the diagnostic logging on failure.
  return toOtherTrailblazeToolPayload()
}

/**
 * Tracks YAML-defined tool names whose encode has already produced a failure log so each
 * offending name logs at most once per process. Same throttling rationale as
 * `encodeFailureKeysLogged` in `TrailblazeToolPayload.kt`.
 */
private val yamlEncodeFailureNamesLogged: MutableSet<String> = mutableSetOf()

/**
 * Convenience over [toLogPayload] for collections — maps every element through the wrapper.
 * Used by [xyz.block.trailblaze.TrailblazeAgentContext.logDelegatingTool] and friends so the
 * `executableTools` field of [xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog]
 * can be populated without each call site repeating `.map { it.toLogPayload() }`.
 */
fun List<TrailblazeTool>.toLogPayloads(): List<OtherTrailblazeTool> = map { it.toLogPayload() }

/**
 * Resolves the verification bit for a tool instance.
 *
 * Verification tools are read-only assertions whose successful execution is itself the
 * verify verdict (e.g. `assertVisible`, `web_verify_text_visible`). Used by
 * `blaze(hint=VERIFY)` to gate which LLM-recommended tools may execute — non-verification
 * tools could side-effect the device and their success is unrelated to the assertion.
 *
 * Per-instance [TrailblazeTool.toolMetadata] (when present) wins — same path
 * `YamlDefinedTrailblazeTool` uses for the other flags. Otherwise the class-level
 * [TrailblazeToolClass] annotation is authoritative; defaults to `false`.
 *
 * Class-only lookups (when only a name is in hand) should use
 * [xyz.block.trailblaze.toolcalls.isVerification] on the resolved [KClass] instead.
 */
fun TrailblazeTool.isVerificationToolInstance(): Boolean {
  this.toolMetadata?.isVerification?.let { return it }
  return try {
    this::class.findAnnotation<TrailblazeToolClass>()?.isVerification ?: false
  } catch (e: Exception) {
    Console.error(
      "Failed to read isVerification annotation from ${this::class.simpleName}: ${e.message}. " +
        "Defaulting to false.",
    )
    false
  }
}

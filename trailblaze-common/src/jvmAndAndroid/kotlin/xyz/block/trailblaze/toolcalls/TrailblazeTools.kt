package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import xyz.block.trailblaze.yaml.TrailArgTokens
import xyz.block.trailblaze.maestro.withAuthoredMaestroLaunchArgumentsRedacted
import xyz.block.trailblaze.maestro.maestroLaunchArgumentSecrets
import xyz.block.trailblaze.maestro.maestroAuthoredLaunchLiterals
import xyz.block.trailblaze.maestro.withKnownMaestroLaunchValuesRedacted
import xyz.block.trailblaze.maestro.withMaestroLaunchArgumentsRedacted
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

object TrailblazeTools {
  const val REQUIRED_TEXT_DESCRIPTION =
    "Text to match; may be a regex. If several elements match, set the other fields to disambiguate."

  const val REQUIRED_ACCESSIBILITY_TEXT_DESCRIPTION =
    "Accessibility text to match; may be a regex. If several elements match, set the other " +
      "fields to disambiguate."

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
 * The name this tool instance answers to — what a reader should see in a log, a trace span or a
 * failure report.
 *
 * An instance that names itself wins over its class annotation: one implementation class can
 * back many named tools, and going by the annotation alone would collapse them into one name.
 * Same rule the session-log payload applies in `toOtherTrailblazeToolPayload`.
 */
fun TrailblazeTool.resolveToolName(): String =
  (this as? InstanceNamedTrailblazeTool)?.instanceToolName?.takeIf { it.isNotBlank() }
    ?: getToolNameFromAnnotation()

/**
 * Resolves the recordability bit for a tool instance.
 *
 * Per-instance [TrailblazeTool.toolMetadata] (when present) wins. Otherwise, the class-level
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
 * KClass extension). Per-instance [TrailblazeTool.toolMetadata] (when present) wins, since a
 * shared implementation class annotation can't carry per-instance data. For everything else the
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
 * Everything except an [OtherTrailblazeTool] or [RawArgumentTrailblazeTool] delegates to the
 * shared common-main canonical encoder [toOtherTrailblazeToolPayload], which is also what
 * [TrailblazeToolJsonSerializer] uses for `@Contextual TrailblazeTool` JSON encoding so
 * both paths produce identical wire formats.
 *
 * A [SensitiveArgsTrailblazeTool] instance gets its declared arg values masked in the returned
 * payload — this is the LOG encode, so secrets (credentials, session seeds) must not survive it.
 * The wire/execution encode ([toOtherTrailblazeToolPayload] called directly) stays unredacted.
 *
 * SISTER-IMPL-TAG: tool-payload-encoders. This is the REDACTED half of the pair; every path that
 * persists a tool payload must come through here rather than calling the canonical encoder
 * directly. See [toOtherTrailblazeToolPayload]'s kdoc for the full invariant and the indirect
 * ways (`@Contextual TrailblazeTool` fields) the unredacted one gets reached.
 */
fun TrailblazeTool.toLogPayload(): OtherTrailblazeTool = toLogPayloadUnredacted()
  .let { if (this is SensitiveArgsTrailblazeTool) it.withSensitiveArgsRedacted(sensitiveArgNames) else it }
  .let {
    if (this is MaestroTrailblazeTool) it.copy(raw = it.raw.withMaestroLaunchArgumentsRedacted()
      .withKnownMaestroLaunchValuesRedacted(sensitiveValues, preserveReferences = false))
    else if (this is SensitiveValuesTrailblazeTool) it.withSensitiveValuesRedacted(sensitiveValues)
    else it
  }

/** Preserve only authored references that demonstrably changed in the executed payload. */
internal fun TrailblazeTool.toAuthoredLogPayload(executed: TrailblazeTool): OtherTrailblazeTool {
  val authored = toLogPayloadUnredacted()
  val resolved = executed.toLogPayloadUnredacted()
  val maskedNames = (executed as? SensitiveArgsTrailblazeTool)?.sensitiveArgNames ?: emptySet()
  val rawDeclaredValues = if (executed is SensitiveValuesTrailblazeTool) {
    (authored.raw["secrets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
      ?.filterNot(::isMemoryReference) ?: emptyList()
  } else emptyList()
  val maskedValues = rawDeclaredValues +
    ((this as? SensitiveValuesTrailblazeTool)?.sensitiveValues ?: emptyList()) +
    ((executed as? SensitiveValuesTrailblazeTool)?.sensitiveValues ?: emptyList())
  return authored.withSensitiveArgsRedacted(maskedNames).withSensitiveValuesRedacted(maskedValues, resolved)
}

/**
 * Whether [value] is exactly one memory reference (`{{token}}`, `{{args.path}}`): the name of a
 * value, not a value, so a tool declaring its strings secret need not mask it.
 */
fun isMemoryReference(value: String): Boolean =
  TrailArgTokens.TOKEN_REGEX.matchEntire(value)?.let { match ->
    TrailArgTokens.tokenBody(match)?.let { body ->
      if (TrailArgTokens.isArgsToken(body)) TrailArgTokens.isValidDottedPath(body)
      else MEMORY_PATH.matches(body)
    }
  } == true

private val MEMORY_PATH = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

/** The authored form keeps only references that actually changed during memory resolution. */
internal fun TrailblazeTool.toAuthoredMaestroLogPayload(executed: MaestroTrailblazeTool): OtherTrailblazeTool {
  val authored = toLogPayloadUnredacted()
  val resolved = executed.toLogPayloadUnredacted()
  return authored.copy(raw = authored.raw.withAuthoredMaestroLaunchArgumentsRedacted(resolved.raw)
    .withKnownMaestroLaunchValuesRedacted(
      maestroAuthoredLaunchLiterals(authored.raw, resolved.raw), preserveReferences = true, executed = resolved.raw,
    ))
}

/**
 * The text of each arg a [SensitiveArgsTrailblazeTool] declares secret, for masking that value
 * where it turns up outside the payload (a tool can copy an arg into memory or a message). Every
 * string or number inside an object or list arg counts, unless it is shorter than
 * [MIN_NESTED_SECRET_CHARS]: masking a card's `expMonth: 1` would hide every "1" in the text.
 * Booleans and nulls can't hold a secret. [resolve] adds each value as a scripted tool would resolve
 * it (a token to what memory holds); the length rule applies to what it resolves to.
 */
internal fun TrailblazeTool.sensitiveArgValues(resolve: (String) -> String? = { null }): List<String> {
  val names = (this as? SensitiveArgsTrailblazeTool)?.sensitiveArgNames.orEmpty()
  if (names.isEmpty()) return emptyList()
  val args = toLogPayloadUnredacted().raw
  return buildList {
    fun addTexts(value: JsonElement, nested: Boolean) {
      when (value) {
        is JsonObject -> value.values.forEach { addTexts(it, nested = true) }
        is JsonArray -> value.forEach { addTexts(it, nested = true) }
        is JsonPrimitive ->
          if (value != JsonNull && (value.isString || value.booleanOrNull == null)) {
            listOfNotNull(value.content, resolve(value.content))
              .filter { it.isNotBlank() && (!nested || it.length >= MIN_NESTED_SECRET_CHARS) }
              .forEach(::add)
          }
      }
    }
    names.forEach { name -> args[name]?.let { addTexts(it, nested = false) } }
  }
}

/** Shortest value inside an object or list secret arg that [sensitiveArgValues] masks elsewhere. */
private const val MIN_NESTED_SECRET_CHARS = 3

private fun TrailblazeTool.toLogPayloadUnredacted(): OtherTrailblazeTool {
  if (this is OtherTrailblazeTool) return this
  if (this is RawArgumentTrailblazeTool) {
    return OtherTrailblazeTool(instanceToolName, rawToolArguments)
  }
  // Class-backed (`@TrailblazeToolClass`) — delegate to the shared canonical encoder. It handles
  // the class.serializer() lookup, the @TrailblazeToolClass name fallback, and the diagnostic
  // logging on failure.
  return toOtherTrailblazeToolPayload()
}

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
 * verify verdict (e.g. `assertVisible`, `web_verifyElementVisible`). Used by
 * `blaze(hint=VERIFY)` to gate which LLM-recommended tools may execute — non-verification
 * tools could side-effect the device and their success is unrelated to the assertion.
 *
 * Per-instance [TrailblazeTool.toolMetadata] (when present) wins. Otherwise the class-level
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

package xyz.block.trailblaze.logs.client

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.util.Console

/**
 * The secret values each session has handled, so nothing written for the session repeats one.
 *
 * A tool's declared secret args are masked in that tool's own log entry, but the value travels on:
 * a sign-in tool types its password with `inputText`, hands it to `android_sendBroadcast`, or fills
 * it into a web form whose request the network capture records. Each of those is logged against
 * its own arguments, which declare nothing. So dispatch registers every value a tool declares
 * secret here ([register], called as each call starts), and the places that write a session down
 * scrub against it: [TrailblazeLogger.log] for every session log, the web network capture for
 * every request.
 *
 * Values shorter than [MIN_SECRET_CHARS] aren't registered. Masking a session-wide substring as
 * short as a 4-digit passcode would also hit screenshot file names and timestamps; the declaring
 * tool's own entry still masks it by name. Nor is a [PLACEHOLDER_SECRETS] value (a published
 * placeholder such as `password`): masking it would mask that word in every prompt, tool
 * description and screen of the session.
 *
 * A step's prompt is left as authored. It is the step's identity (a recording pairs the step's
 * start and completion by it), it is written before the step's own secrets are known, and the
 * trail file already holds it.
 *
 * Process-wide and keyed by session, because the writers don't share an object with dispatch. A
 * session's set is kept until [MAX_SESSIONS] newer sessions have registered, rather than cleared
 * at session end, since a capture can still be draining after it.
 */
object SessionSecrets {

  const val MIN_SECRET_CHARS: Int = 8
  private const val MAX_SESSIONS: Int = 64

  /** Published placeholder credentials, matched whole and case-insensitively; not secrets. */
  private val PLACEHOLDER_SECRETS = setOf("password")

  private val bySession = object : LinkedHashMap<String, MutableSet<String>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?): Boolean =
      size > MAX_SESSIONS
  }

  fun register(sessionId: SessionId, values: Collection<String>) = register(sessionId.value, values)

  fun register(sessionId: String, values: Collection<String>) {
    val kept = values.filter { it.length >= MIN_SECRET_CHARS && it.isNotBlank() && it.lowercase() !in PLACEHOLDER_SECRETS }
    if (kept.isEmpty()) return
    synchronized(bySession) { bySession.getOrPut(sessionId) { HashSet() }.addAll(kept) }
  }

  /** The values registered for [sessionId]; empty when none. */
  fun valuesFor(sessionId: String): Set<String> =
    synchronized(bySession) { bySession[sessionId]?.toSet() } ?: emptySet()

  fun valuesFor(sessionId: SessionId): Set<String> = valuesFor(sessionId.value)

  /** [text] with each of [sessionId]'s secrets masked, raw or JSON-escaped. */
  fun scrubText(sessionId: SessionId, text: String): String {
    val forms = formsFor(sessionId.value)
    return if (forms.isEmpty()) text else redactSensitiveValuesIn(text, forms)
  }

  /**
   * [log] with every string inside it scrubbed of [sessionId]'s secrets. Returns [log] itself when
   * none appear in it, so a session that never handled a secret pays nothing.
   *
   * Works on the log's JSON, the form it is written in, so every log type and field is covered
   * without listing them. Returns null, and the log is not written, if the scrubbed JSON can't be
   * read back as a log: a missing entry is recoverable, a leaked credential is not.
   */
  fun scrub(sessionId: SessionId, log: TrailblazeLog): TrailblazeLog? {
    val forms = formsFor(sessionId.value)
    if (forms.isEmpty()) return log
    val json = TrailblazeCompactJsonInstance.encodeToJsonElement(TrailblazeLog.serializer(), log)
    val scrubbed = json.scrubbed(forms).withPromptAsAuthored(json)
    if (scrubbed == json) return log
    return try {
      TrailblazeCompactJsonInstance.decodeFromJsonElement(TrailblazeLog.serializer(), scrubbed)
    } catch (e: Exception) {
      Console.error(
        "[SessionSecrets] dropped a ${log::class.simpleName}: it held a session secret and could not be " +
          "rebuilt with it masked (${e::class.simpleName}).",
      )
      null
    }
  }

  private fun formsFor(sessionId: String): List<String> {
    val values = valuesFor(sessionId)
    if (values.isEmpty()) return emptyList()
    return values.flatMap { value ->
      val escaped = Json.encodeToString(String.serializer(), value).removeSurrounding("\"")
      if (escaped == value) listOf(value) else listOf(value, escaped)
    }.distinct()
  }

  private fun JsonElement.scrubbed(forms: List<String>): JsonElement = when (this) {
    is JsonPrimitive ->
      if (!isString) {
        this
      } else {
        val masked = redactSensitiveValuesIn(content, forms)
        if (masked == content) this else JsonPrimitive(masked)
      }
    is JsonArray -> {
      val items = map { it.scrubbed(forms) }
      if (items.indices.all { items[it] === this[it] }) this else JsonArray(items)
    }
    is JsonObject -> {
      val entries = mapValues { (_, value) -> value.scrubbed(forms) }
      if (entries.all { (key, value) -> value === this[key] }) this else JsonObject(entries)
    }
  }

  private fun JsonElement.withPromptAsAuthored(original: JsonElement): JsonElement {
    val step = (this as? JsonObject)?.get(PROMPT_STEP) as? JsonObject ?: return this
    val authored = ((original as JsonObject)[PROMPT_STEP] as? JsonObject)
      ?.filterKeys { it in PROMPT_KEYS }
      ?.takeIf { it.isNotEmpty() } ?: return this
    return JsonObject(this + (PROMPT_STEP to JsonObject(step + authored)))
  }

  private const val PROMPT_STEP = "promptStep"

  /** Where a [xyz.block.trailblaze.yaml.PromptStep]'s text is written: `step:` or `verify:`. */
  private val PROMPT_KEYS = setOf("step", "verify", "prompt")

  /** For tests: forgets every session's secrets. */
  internal fun clearForTest() = synchronized(bySession) { bySession.clear() }
}

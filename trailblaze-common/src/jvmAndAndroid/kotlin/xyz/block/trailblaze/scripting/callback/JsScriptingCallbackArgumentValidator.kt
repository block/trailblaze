package xyz.block.trailblaze.scripting.callback

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * Shared argument check used by every scripted-tool dispatch wrapper. Closes two loopholes
 * in the typed-surface contract:
 *
 *  - **Unknown keys (#3209)** — `kotlinx.serialization`'s `ignoreUnknownKeys = true` (see
 *    [xyz.block.trailblaze.logs.client.TrailblazeJson]) silently dropped keys the tool didn't
 *    declare, splitting the contract between TypeScript (compile error) and the runtime
 *    (no signal). An `arguments_json` payload with a key the tool's `inputSchema` doesn't
 *    recognize is rejected with a message naming the bad key and pointing at the canonical
 *    typed shape.
 *  - **Missing required keys (#3261)** — the wrapper used to drop trail-YAML invocations
 *    that omitted a `required: false` arg into the deserializer where the resulting error
 *    didn't distinguish "you forgot a required arg" from "this optional one was
 *    accidentally treated as required." A payload missing a key the tool's `inputSchema`
 *    declares as required is now rejected up-front with a directed message; payloads that
 *    omit declared-optional args fall through unchanged (the JS handler sees `undefined`
 *    for those keys, matching TS `?:` semantics).
 *
 * **Scope on purpose:** invoked only from the scripted-tool dispatch path — the JVM
 * [JsScriptingCallbackDispatcher] (HTTP subprocess MCP + on-device QuickJS bridge) and the
 * in-process [xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding]. The LLM agent
 * path (`DirectMcpToolExecutor.deserializeTool` → `TrailblazeToolRepo.toolCallToTrailblazeTool`)
 * goes around this wrapper and remains tolerant of authoring-hint keys the LLM emits today.
 * If that policy needs to flip for the LLM too, do it as a separate follow-up — tracked in #3214.
 */
object JsScriptingCallbackArgumentValidator {

  /**
   * Parser used only to surface the top-level JSON object's keys. The dispatcher's downstream
   * tool deserialization still goes through [xyz.block.trailblaze.logs.client.TrailblazeJsonInstance]
   * with its `ignoreUnknownKeys = true`, polymorphic registrations, etc. This parser is
   * intentionally minimal — non-object payloads (e.g. `null`, a JSON primitive) decode as
   * `JsonObject`-not-the-type so [validate] returns `null` and falls through to the tool's
   * own deserializer, which will emit the usual decode-failure message. We don't want to
   * compete with that path on malformed-JSON handling.
   *
   * **Maintenance contract:** this parser does NOT inherit settings from
   * [xyz.block.trailblaze.logs.client.TrailblazeJson.createTrailblazeJsonInstance] — strict mode
   * is load-bearing for unknown-key detection and lenient mode would defeat it. If a future
   * change to `createTrailblazeJsonInstance` adds a setting that ALSO needs to apply here
   * (e.g. a custom number-format policy or required serializer module entry), audit this
   * configuration and add the matching field. The pair is intentionally isolated, not
   * accidentally divergent.
   */
  private val parser = Json { ignoreUnknownKeys = false }

  /**
   * Inspect [argumentsJson] for two contract violations against the [toolName] schema:
   *
   *   1. **Unknown keys** — keys the tool's `inputSchema` doesn't declare.
   *   2. **Missing required keys** — declared-required keys absent from the payload.
   *
   * Returns `null` when the payload is well-formed, uses only known keys, and includes
   * every required key, or when the tool's schema can't be introspected (the repo returns
   * `null` from [TrailblazeToolRepo.expectedArgumentKeysFor] — fall through to the
   * deserializer in that case rather than rejecting a tool whose schema we can't read).
   * Returns a human-readable error message naming the offending keys + the canonical
   * accepted keys on a mismatch.
   *
   * **Why this lives above [TrailblazeToolRepo.toolCallToTrailblazeTool]:** we want a custom
   * message that points at `client.tools.<toolName>` (the TypeScript-author entry point), not
   * the kotlinx-serialization "Encountered an unknown key" / "Field 'x' is required" exceptions.
   * Pre-checking before deserialization also lets us list every bad key in one shot, whereas
   * the deserializer would fail-fast on the first one encountered.
   *
   * **Order matters:** unknown-key rejection runs first so a payload that misspells a required
   * arg (`{"querry": "..."}` against an `inputSchema` declaring `query: required`) surfaces as
   * "unknown key `querry`" rather than "missing required `query`" — the typo is closer to the
   * author's fix than the absence is.
   */
  fun validate(repo: TrailblazeToolRepo, toolName: String, argumentsJson: String): String? {
    val expected = repo.expectedArgumentKeysFor(toolName) ?: return null
    val incoming = try {
      parser.parseToJsonElement(argumentsJson) as? JsonObject ?: return null
    } catch (_: SerializationException) {
      // Malformed JSON or non-object payload — let the downstream deserializer produce its
      // own error message rather than synthesizing a confusing one here.
      return null
    }

    val unknown = incoming.keys.filter { it !in expected }
    if (unknown.isNotEmpty()) {
      val expectedList = expected.joinToString(", ")
      val unknownList = unknown.joinToString(", ") { "\"$it\"" }
      return buildString {
        append("Tool '")
        append(toolName)
        append("' was called with unknown argument keys: [")
        append(unknownList)
        append("].")
        if (expectedList.isNotEmpty()) {
          append(" Expected one of: ")
          append(expectedList)
          append(".")
        } else {
          append(" This tool accepts no arguments.")
        }
        append(" (See client.tools.")
        append(toolName)
        append(" for the canonical typed shape.)")
      }
    }

    // Missing-required gate (#3261). `requiredArgumentKeysFor` follows the same resolution
    // chain as `expectedArgumentKeysFor`, so a tool whose schema we just confirmed is
    // introspectable resolves here too. The `?: emptySet()` defends against a future
    // refactor that decouples the chains — if required-introspection ever lags expected-
    // introspection, default to "no required keys" rather than synthesizing a wrong message.
    val required = repo.requiredArgumentKeysFor(toolName) ?: emptySet()
    val missing = required.filter { it !in incoming.keys }
    if (missing.isNotEmpty()) {
      val missingList = missing.joinToString(", ") { "\"$it\"" }
      val requiredList = required.joinToString(", ")
      return buildString {
        append("Tool '")
        append(toolName)
        append("' was called without required argument keys: [")
        append(missingList)
        append("]. Required: ")
        append(requiredList)
        append(". (See client.tools.")
        append(toolName)
        append(" for the canonical typed shape — arguments declared `required: false` in the ")
        append("tool's `inputSchema` may be omitted; the handler receives `undefined` for them.)")
      }
    }

    return null
  }
}

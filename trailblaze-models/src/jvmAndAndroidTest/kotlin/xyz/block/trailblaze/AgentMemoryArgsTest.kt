package xyz.block.trailblaze

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for the `args.` namespace on [AgentMemory] — the parameterized-trail resolution the
 * dispatch boundary performs. Pins the observable substitution contract:
 *  - a whole-scalar `{{args.x}}` token yields the arg's NATIVE JSON type;
 *  - an embedded `{{args.x}}` token renders the scalar as text;
 *  - `args.` and `memory.` namespaces are independent;
 *  - a non-dotted-path (expression) `args.` token hard-errors at runtime.
 */
class AgentMemoryArgsTest {

  private fun memoryWithArgs(vararg args: Pair<String, kotlinx.serialization.json.JsonElement>): AgentMemory {
    val memory = AgentMemory()
    memory.seedArgs(args.toMap())
    return memory
  }

  @Test
  fun `whole-scalar integer arg substitutes the native JSON number`() {
    val memory = memoryWithArgs("retries" to JsonPrimitive(3))
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.retries}}"))
    assertEquals(3, result.jsonPrimitive.int)
    assertTrue(!result.jsonPrimitive.isString, "whole-scalar integer arg must be a native number, not a string")
  }

  @Test
  fun `whole-scalar boolean arg substitutes the native JSON boolean`() {
    val memory = memoryWithArgs("verbose" to JsonPrimitive(true))
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.verbose}}"))
    assertEquals(true, result.jsonPrimitive.boolean)
  }

  @Test
  fun `whole-scalar string arg stays a string`() {
    val memory = memoryWithArgs("recipient" to JsonPrimitive("alice@example.com"))
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.recipient}}"))
    assertEquals("alice@example.com", result.jsonPrimitive.content)
    assertTrue(result.jsonPrimitive.isString)
  }

  @Test
  fun `embedded arg token renders the scalar as text`() {
    val memory = memoryWithArgs("retries" to JsonPrimitive(3))
    val result = memory.interpolateVariablesInJson(JsonPrimitive("retry {{args.retries}} times"))
    assertEquals("retry 3 times", result.jsonPrimitive.content)
  }

  @Test
  fun `dotted sub-path resolves into an object arg`() {
    val replyTo = buildJsonObject { put("email", "a@b.com") }
    val memory = memoryWithArgs("reply_to" to replyTo)
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.reply_to.email}}"))
    assertEquals("a@b.com", result.jsonPrimitive.content)
  }

  @Test
  fun `plain string interpolation renders an embedded arg`() {
    val memory = memoryWithArgs("name" to JsonPrimitive("Ada"))
    assertEquals("Hi Ada", memory.interpolateVariables("Hi {{args.name}}"))
  }

  @Test
  fun `args and memory namespaces resolve independently`() {
    val memory = AgentMemory()
    memory.remember("email", "mem@example.com")
    memory.seedArgs(mapOf("email" to JsonPrimitive("arg@example.com")))
    assertEquals("mem@example.com", memory.interpolateVariables("{{memory.email}}"))
    assertEquals("arg@example.com", memory.interpolateVariables("{{args.email}}"))
  }

  @Test
  fun `an expression-bearing args token hard-errors at runtime`() {
    val memory = memoryWithArgs("count" to JsonPrimitive(1))
    val error = assertFailsWith<IllegalArgumentException> {
      memory.interpolateVariables("{{args.count + 1}}")
    }
    assertTrue(error.message?.contains("dotted paths only") == true, error.message)
  }

  @Test
  fun `an expression-bearing whole-scalar args token hard-errors in JSON interpolation`() {
    val memory = memoryWithArgs("count" to JsonPrimitive(1))
    assertFailsWith<IllegalArgumentException> {
      memory.interpolateVariablesInJson(JsonPrimitive("{{args.count | upper}}"))
    }
  }

  @Test
  fun `an unknown args reference is left as a literal`() {
    val memory = memoryWithArgs("known" to JsonPrimitive("v"))
    assertEquals("{{args.unknown}}", memory.interpolateVariables("{{args.unknown}}"))
  }

  @Test
  fun `a deferred whole-scalar array arg stays a literal token, NOT the native JSON array`() {
    // type: array is accepted by the grammar today, but substitution is deferred — a whole-scalar
    // token resolving to an array must leave the literal in place (a STRING, not a JsonArray),
    // never throw and never resolve to some interim wrong value.
    val memory = memoryWithArgs("items" to buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.items}}"))
    assertTrue(result is JsonPrimitive && result.isString, "expected a literal string, got $result")
    assertEquals("{{args.items}}", result.jsonPrimitive.content)
  }

  @Test
  fun `a deferred whole-scalar object arg stays a literal token, NOT the native JSON object`() {
    // Whole-OBJECT substitution (not just dotted sub-path access into one) is deferred the same way
    // as array — this is the standalone case, distinct from the dot-path test above.
    val memory = memoryWithArgs("opts" to buildJsonObject { put("verbose", true) })
    val result = memory.interpolateVariablesInJson(JsonPrimitive("{{args.opts}}"))
    assertTrue(result is JsonPrimitive && result.isString, "expected a literal string, got $result")
    assertEquals("{{args.opts}}", result.jsonPrimitive.content)
  }

  @Test
  fun `a deferred embedded object arg stays a literal token, NOT JSON-stringified`() {
    val memory = memoryWithArgs("opts" to buildJsonObject { put("verbose", true) })
    // Embedded substitution for executable types renders `.content` as text; for a deferred type
    // there is no execution yet, so the literal token — NOT a JSON.stringify'd rendering of the
    // object — must survive unchanged.
    assertEquals("config: {{args.opts}}", memory.interpolateVariables("config: {{args.opts}}"))
  }

  @Test
  fun `a deferred embedded array arg stays a literal token, NOT JSON-stringified`() {
    val memory = memoryWithArgs("items" to buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
    assertEquals("items: {{args.items}}", memory.interpolateVariables("items: {{args.items}}"))
  }

  @Test
  fun `a deferred array or object arg NEVER blanks, even under the kill-switch`() {
    // The blank-unknown-tokens kill-switch exists for genuinely-unknown tokens (see the shared-sink
    // test above); it must never apply to a KNOWN-but-deferred arg — that would silently turn a
    // declared array/object value into an empty string, which is exactly the "silent wrong
    // substitution" the deferred-type contract forbids.
    val memory = memoryWithArgs(
      "items" to buildJsonArray { add(JsonPrimitive("a")) },
      "opts" to buildJsonObject { put("verbose", true) },
    )
    assertEquals("{{args.items}}", memory.interpolateVariables("{{args.items}}", blankUnknownTokens = true))
    assertEquals("{{args.opts}}", memory.interpolateVariables("{{args.opts}}", blankUnknownTokens = true))
    val jsonResult = memory.interpolateVariablesInJson(JsonPrimitive("{{args.items}}"))
    assertEquals("{{args.items}}", jsonResult.jsonPrimitive.content)
  }

  @Test
  fun `unknown args and memory tokens share the leave-literal vs blank behavior`() {
    // The `args.` resolver feeds the SAME unknown-token sink as `memory.`, so the leave-literal
    // default and the TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS kill-switch (injected here as
    // blankUnknownTokens) apply uniformly to both namespaces. Uses the internal overload so the
    // behavior is pinned without process-env manipulation.
    val memory = memoryWithArgs("known" to JsonPrimitive("v"))

    // Kill-switch on: both unknown namespaces blank identically.
    assertEquals("", memory.interpolateVariables("{{args.unknown}}", blankUnknownTokens = true))
    assertEquals("", memory.interpolateVariables("{{memory.unknown}}", blankUnknownTokens = true))

    // Kill-switch off (default): both unknown namespaces leave the literal identically.
    assertEquals("{{args.unknown}}", memory.interpolateVariables("{{args.unknown}}", blankUnknownTokens = false))
    assertEquals("{{memory.unknown}}", memory.interpolateVariables("{{memory.unknown}}", blankUnknownTokens = false))
  }

  @Test
  fun `a token-valued default resolves against seeded memory`() {
    val memory = AgentMemory()
    memory.remember("email", "seeded@example.com")
    // seedArgs runs after memory is seeded; a string arg value carrying a memory token resolves.
    memory.seedArgs(mapOf("recipient" to JsonPrimitive("{{memory.email}}")))
    assertEquals("seeded@example.com", memory.interpolateVariables("{{args.recipient}}"))
  }

  @Test
  fun `a sibling-arg reference resolves fully regardless of declaration order`() {
    // subject references recipient, whose own value is a memory token. Seeding iterates the
    // provided map's order, so listing subject FIRST is the adversarial order: a single pass
    // would leave subject holding the intermediate '{{memory.email}}' literal.
    val subjectFirst = AgentMemory()
    subjectFirst.remember("email", "seeded@example.com")
    subjectFirst.seedArgs(
      linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "subject" to JsonPrimitive("Re: {{args.recipient}}"),
        "recipient" to JsonPrimitive("{{memory.email}}"),
      ),
    )
    assertEquals("Re: seeded@example.com", subjectFirst.interpolateVariables("{{args.subject}}"))

    val recipientFirst = AgentMemory()
    recipientFirst.remember("email", "seeded@example.com")
    recipientFirst.seedArgs(
      linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "recipient" to JsonPrimitive("{{memory.email}}"),
        "subject" to JsonPrimitive("Re: {{args.recipient}}"),
      ),
    )
    assertEquals("Re: seeded@example.com", recipientFirst.interpolateVariables("{{args.subject}}"))
  }

  @Test
  fun `a sibling-arg reference cycle terminates and leaves literals`() {
    // A cycle can never resolve; seeding must terminate (bounded passes) rather than spin, and
    // whatever literal each arg stabilized on surfaces visibly instead of blanking.
    val memory = AgentMemory()
    memory.seedArgs(
      linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "a" to JsonPrimitive("{{args.b}}"),
        "b" to JsonPrimitive("{{args.a}}"),
      ),
    )
    val resolvedA = memory.args.getValue("a").jsonPrimitive.content
    assertTrue(resolvedA.startsWith("{{args."), "a cycle must stabilize on a literal token, got $resolvedA")
  }

  @Test
  fun `argsForLlmContext keys entries by token spelling and renders primitives as content`() {
    // The LLM never sees interpolated prompt text — a literal `{{args.x}}` in an objective is
    // resolved by the model against the remembered-values reminder. Keys must therefore match the
    // token spelling exactly, and primitive values must render as plain text (no JSON quoting).
    val memory = memoryWithArgs(
      "recipient" to JsonPrimitive("alice@example.com"),
      "retries" to JsonPrimitive(3),
      "verbose" to JsonPrimitive(true),
    )
    assertEquals(
      mapOf(
        "args.recipient" to "alice@example.com",
        "args.retries" to "3",
        "args.verbose" to "true",
      ),
      memory.argsForLlmContext(),
    )
  }

  @Test
  fun `argsForLlmContext renders deferred array and object args as compact JSON`() {
    // Deferred types can't substitute into tool args yet, but the LLM can still read their bound
    // value — compact JSON is the unambiguous rendering for a structured value in a prompt list.
    val memory = memoryWithArgs(
      "items" to buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) },
      "opts" to buildJsonObject { put("verbose", true) },
    )
    val rendered = memory.argsForLlmContext()
    assertEquals("""["a","b"]""", rendered.getValue("args.items"))
    assertEquals("""{"verbose":true}""", rendered.getValue("args.opts"))
  }

  @Test
  fun `argsForLlmContext is sorted by name for deterministic prompt output`() {
    val memory = memoryWithArgs(
      "zeta" to JsonPrimitive("z"),
      "alpha" to JsonPrimitive("a"),
    )
    assertEquals(listOf("args.alpha", "args.zeta"), memory.argsForLlmContext().keys.toList())
  }

  @Test
  fun `argsForLlmContext is empty when no args are seeded`() {
    assertEquals(emptyMap(), AgentMemory().argsForLlmContext())
  }

  @Test
  fun `an arg that laundered in a sensitive memory value is hidden from the LLM but still resolves`() {
    // --secret is a session-lifetime redaction promise. An arg value carrying '{{memory.pin}}'
    // resolves the secret INTO the args namespace at seed time — that value must never surface
    // through the LLM reminder, while the dispatch boundary keeps resolving it (the LLM can copy
    // the literal {{args.x}} into tool args, exactly like sensitive memory tokens).
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "9876")
    memory.seedArgs(
      mapOf(
        "vaultCode" to JsonPrimitive("{{memory.pin}}"),
        "recipient" to JsonPrimitive("alice@example.com"),
      ),
    )
    // Dispatch-boundary resolution still works.
    assertEquals("9876", memory.interpolateVariables("{{args.vaultCode}}"))
    // LLM reminder: the laundered arg is absent; the clean sibling stays.
    val llmContext = memory.argsForLlmContext()
    assertEquals(mapOf("args.recipient" to "alice@example.com"), llmContext)
  }

  @Test
  fun `argsForLogSafePayload masks a laundered sensitive value back to its own token`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "9876")
    memory.seedArgs(
      mapOf(
        "vaultCode" to JsonPrimitive("{{memory.pin}}"),
        "recipient" to JsonPrimitive("alice@example.com"),
      ),
    )
    val logSafe = memory.argsForLogSafePayload()
    assertEquals(JsonPrimitive("{{args.vaultCode}}"), logSafe.getValue("vaultCode"))
    assertEquals(JsonPrimitive("alice@example.com"), logSafe.getValue("recipient"))
  }

  @Test
  fun `arg taint is sticky - deleting the source memory key cannot un-redact the arg`() {
    // Regression: taint was once recomputed against LIVE memory on every read. `delete` keeps
    // memory's sensitivity MARKER but drops the value the containment check matched against —
    // while the arg's copy of the secret lives on. A scripted tool can delete a key mid-run
    // (applyScriptedToolMemoryDelta), which would have leaked the secret into every subsequent
    // LLM reminder and persisted log payload. Taint is recorded at seed time and never revoked.
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "9876")
    memory.seedArgs(mapOf("vaultCode" to JsonPrimitive("{{memory.pin}}")))
    memory.delete("pin")

    assertEquals(emptyMap(), memory.argsForLlmContext())
    assertEquals(JsonPrimitive("{{args.vaultCode}}"), memory.argsForLogSafePayload().getValue("vaultCode"))
  }

  @Test
  fun `rehydrated arg taint marking survives without the source memory value`() {
    // The RPC crossing re-marks taint from the wire (RunYamlRequest.sensitiveArgNames) — the
    // receiving side may never have held the source memory value at all.
    val memory = AgentMemory()
    memory.putArg("vaultCode", JsonPrimitive("9876"))
    memory.markArgSensitive("vaultCode")

    assertEquals(emptyMap(), memory.argsForLlmContext())
    assertEquals(JsonPrimitive("{{args.vaultCode}}"), memory.argsForLogSafePayload().getValue("vaultCode"))
  }

  @Test
  fun `putArg taints when the value matches a sensitive memory value present at rehydration`() {
    // Device-side rehydration order is memory snapshot first, then args — so a still-present
    // sensitive value taints at putArg time even without the explicit wire marking.
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "9876")
    memory.putArg("vaultCode", JsonPrimitive("9876"))
    memory.delete("pin")

    assertEquals(emptyMap(), memory.argsForLlmContext())
  }

  @Test
  fun `clear drops args`() {
    val memory = memoryWithArgs("x" to JsonPrimitive("v"))
    memory.clear()
    assertEquals("{{args.x}}", memory.interpolateVariables("{{args.x}}"))
  }

  @Test
  fun `memory-only interpolation is unaffected when no args are seeded`() {
    val memory = AgentMemory()
    memory.remember("name", "Ada")
    assertEquals("Hi Ada", memory.interpolateVariables("Hi {{name}}"))
    assertEquals("Hi Ada", memory.interpolateVariables("Hi {{memory.name}}"))
  }
}

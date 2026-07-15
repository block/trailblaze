package xyz.block.trailblaze.yaml

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.DefaultBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [TrailArgBinder] — the pure, declaration-driven bind that turns a caller's
 * supplied values into the typed `args.` map (or a single actionable failure). Assertions are on
 * the observable contract: the bound value's type + content, or the failure message, never internal
 * coercion steps.
 */
class TrailArgBinderTest {

  private fun required(type: String) = TrailArgConfig(type = type)

  private fun optional(type: String, default: DefaultBehavior) =
    TrailArgConfig(type = type, default = default)

  private fun success(result: TrailArgBinder.BindResult): Map<String, kotlinx.serialization.json.JsonElement> {
    assertTrue(result is TrailArgBinder.BindResult.Success, "expected Success, got $result")
    return result.args
  }

  private fun failure(result: TrailArgBinder.BindResult): String {
    assertTrue(result is TrailArgBinder.BindResult.Failure, "expected Failure, got $result")
    return result.message
  }

  @Test
  fun `string arg keeps provided text verbatim`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("recipient" to required(TrailArgConfig.STRING)),
        provided = mapOf("recipient" to JsonPrimitive("alice@example.com")),
      ),
    )
    assertEquals("alice@example.com", bound.getValue("recipient").jsonPrimitive.content)
  }

  @Test
  fun `integer arg coerces a numeric string to a native JSON integer`() {
    // Declaration-driven: the `--arg` wire form is always a JSON string; the declared `integer`
    // type is what turns "3" into the native number 3.
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("retries" to required(TrailArgConfig.INTEGER)),
        provided = mapOf("retries" to JsonPrimitive("3")),
      ),
    )
    assertEquals(3L, bound.getValue("retries").jsonPrimitive.long)
    assertTrue(bound.getValue("retries").jsonPrimitive.isString.not(), "integer must not be a JSON string")
  }

  @Test
  fun `boolean arg coerces a string to a native JSON boolean`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("verbose" to required(TrailArgConfig.BOOLEAN)),
        provided = mapOf("verbose" to JsonPrimitive("true")),
      ),
    )
    assertEquals(true, bound.getValue("verbose").jsonPrimitive.boolean)
  }

  @Test
  fun `string arg preserves a numeric-looking value as a string`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("zip" to required(TrailArgConfig.STRING)),
        provided = mapOf("zip" to JsonPrimitive("007")),
      ),
    )
    assertEquals("007", bound.getValue("zip").jsonPrimitive.content)
  }

  @Test
  fun `string arg coerces a bare JSON number from an args-file to its text`() {
    // An args-file entry like `retries: 3` arrives as a native JSON number; declared `string`
    // coerces it to "3" (declaration-driven — the value's wire shape never wins over the type).
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("retries" to required(TrailArgConfig.STRING)),
        provided = mapOf("retries" to JsonPrimitive(3)),
      ),
    )
    val value = bound.getValue("retries").jsonPrimitive
    assertEquals("3", value.content)
    assertTrue(value.isString, "declared string must bind as a JSON string")
  }

  @Test
  fun `non-numeric value on an integer arg fails with the arg name`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("retries" to required(TrailArgConfig.INTEGER)),
        provided = mapOf("retries" to JsonPrimitive("lots")),
      ),
    )
    assertTrue(message.contains("retries"), message)
    assertTrue(message.contains("integer"), message)
  }

  @Test
  fun `non-boolean value on a boolean arg fails`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("verbose" to required(TrailArgConfig.BOOLEAN)),
        provided = mapOf("verbose" to JsonPrimitive("yes")),
      ),
    )
    assertTrue(message.contains("verbose"), message)
    assertTrue(message.contains("boolean"), message)
  }

  @Test
  fun `missing required arg fails before any work and names the arg`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf(
          "recipient" to required(TrailArgConfig.STRING),
          "subject" to required(TrailArgConfig.STRING),
        ),
        provided = mapOf("recipient" to JsonPrimitive("a@b.com")),
      ),
    )
    assertTrue(message.contains("subject"), message)
    assertTrue(message.contains("Missing required", ignoreCase = true), message)
  }

  @Test
  fun `omitted optional arg takes its declared default`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf(
          "retries" to optional(TrailArgConfig.INTEGER, DefaultBehavior.Use(JsonPrimitive("3"))),
        ),
        provided = emptyMap(),
      ),
    )
    assertEquals(3L, bound.getValue("retries").jsonPrimitive.long)
  }

  @Test
  fun `provided value overrides the declared default`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf(
          "retries" to optional(TrailArgConfig.INTEGER, DefaultBehavior.Use(JsonPrimitive("3"))),
        ),
        provided = mapOf("retries" to JsonPrimitive("9")),
      ),
    )
    assertEquals(9L, bound.getValue("retries").jsonPrimitive.long)
  }

  @Test
  fun `an invalid declared default fails loudly and names the arg`() {
    // An author typo (`default: lots` on an integer arg) must surface at bind time as ITS OWN
    // error, not dispatch a garbage value or read as a caller mistake.
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf(
          "retries" to optional(TrailArgConfig.INTEGER, DefaultBehavior.Use(JsonPrimitive("lots"))),
        ),
        provided = emptyMap(),
      ),
    )
    assertTrue(message.contains("retries"), message)
    assertTrue(message.contains("Default", ignoreCase = true), message)
  }

  @Test
  fun `a malformed args token inside a string value fails at bind, not at seed`() {
    // A string value's embedded `args.` tokens resolve at seed time, where an expression-bearing
    // token hard-errors deep in the runner. Binding is validation — it must surface as a clean
    // input error (CLI MISUSE) that names the arg instead.
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("subject" to required(TrailArgConfig.STRING)),
        provided = mapOf("subject" to JsonPrimitive("total: {{args.count + 1}}")),
      ),
    )
    assertTrue(message.contains("subject"), message)
    assertTrue(message.contains("args.count + 1"), message)
  }

  @Test
  fun `a malformed args token inside a declared default fails at bind too`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf(
          "subject" to optional(
            TrailArgConfig.STRING,
            DefaultBehavior.Use(JsonPrimitive("{{args.recipient | upper}}")),
          ),
        ),
        provided = emptyMap(),
      ),
    )
    assertTrue(message.contains("subject"), message)
    assertTrue(message.contains("args.recipient | upper"), message)
  }

  @Test
  fun `a well-formed sibling args reference in a value binds fine`() {
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("subject" to required(TrailArgConfig.STRING)),
        provided = mapOf("subject" to JsonPrimitive("Re: {{args.original_subject}}")),
      ),
    )
    assertEquals("Re: {{args.original_subject}}", bound.getValue("subject").jsonPrimitive.content)
  }

  @Test
  fun `null provided value is a loud error, never a null arg`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("recipient" to required(TrailArgConfig.STRING)),
        provided = mapOf("recipient" to JsonNull),
      ),
    )
    assertTrue(message.contains("recipient"), message)
    assertTrue(message.contains("null"), message)
  }

  @Test
  fun `unknown arg fails and names the offending key`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("recipient" to required(TrailArgConfig.STRING)),
        provided = mapOf(
          "recipient" to JsonPrimitive("a@b.com"),
          "recipent" to JsonPrimitive("typo"),
        ),
      ),
    )
    assertTrue(message.contains("recipent"), message)
    assertTrue(message.contains("Unknown", ignoreCase = true), message)
  }

  @Test
  fun `unknown arg against a trail that declares none is explained`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = null,
        provided = mapOf("x" to JsonPrimitive("1")),
      ),
    )
    assertTrue(message.contains("declares no"), message)
  }

  @Test
  fun `no declared and none provided binds to empty - the non-parameterized path`() {
    val bound = success(TrailArgBinder.bind(declared = null, provided = emptyMap()))
    assertTrue(bound.isEmpty())
  }

  @Test
  fun `wire round-trip preserves a value's JSON identity`() {
    // A quoted vs. bare scalar keeps its string-vs-number identity across the CLI wire.
    val original = mapOf(
      "s" to JsonPrimitive("hello"),
      "n" to JsonPrimitive(42L),
      "b" to JsonPrimitive(true),
    )
    val decoded = TrailArgBinder.decodeProvided(TrailArgBinder.encodeProvided(original))
    assertEquals(original, decoded)
  }

  @Test
  fun `array arg accepts and passes through a provided array verbatim`() {
    // Accepted-but-deferred: the value itself is validated (must actually be an array), but its
    // substitution into tokens isn't executed yet — that's AgentMemory's concern, not the binder's.
    val provided = buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("items" to required(TrailArgConfig.ARRAY)),
        provided = mapOf("items" to provided),
      ),
    )
    assertEquals(provided, bound.getValue("items").jsonArray)
  }

  @Test
  fun `object arg accepts and passes through a provided object verbatim`() {
    val provided = buildJsonObject { put("email", "a@b.com") }
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("replyTo" to required(TrailArgConfig.OBJECT)),
        provided = mapOf("replyTo" to provided),
      ),
    )
    assertEquals(provided, bound.getValue("replyTo").jsonObject)
  }

  @Test
  fun `a scalar provided for a declared array arg is rejected, not silently accepted`() {
    // A plain string can only reach here via --arg (always a JSON string on the wire) mismatched
    // against a declared array/object arg. Binding is validation for every declared type, not just
    // the executable ones — a scalar masquerading as an array must fail loudly, never pass through
    // as a value that later resolves as if it were a plain string substitution.
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("items" to required(TrailArgConfig.ARRAY)),
        provided = mapOf("items" to JsonPrimitive("not-an-array")),
      ),
    )
    assertTrue(message.contains("items"), message)
    assertTrue(message.contains("array"), message)
  }

  @Test
  fun `a scalar provided for a declared object arg is rejected, not silently accepted`() {
    val message = failure(
      TrailArgBinder.bind(
        declared = mapOf("opts" to required(TrailArgConfig.OBJECT)),
        provided = mapOf("opts" to JsonPrimitive("not-an-object")),
      ),
    )
    assertTrue(message.contains("opts"), message)
    assertTrue(message.contains("object"), message)
  }

  @Test
  fun `an omitted array arg with a declared default takes the array default`() {
    val default = buildJsonArray { add(JsonPrimitive("x")) }
    val bound = success(
      TrailArgBinder.bind(
        declared = mapOf("items" to optional(TrailArgConfig.ARRAY, DefaultBehavior.Use(default))),
        provided = emptyMap(),
      ),
    )
    assertEquals(default, bound.getValue("items").jsonArray)
  }

  @Test
  fun `arg-file null decodes to JsonNull so bind can reject it`() {
    // A YAML-null in an args file encodes as JSON `null` on the wire; decode must surface it as
    // JsonNull so the bind step produces the loud null error rather than a "null" string.
    val decoded = TrailArgBinder.decodeProvided(mapOf("x" to "null"))
    assertEquals(JsonNull, decoded.getValue("x"))
  }
}

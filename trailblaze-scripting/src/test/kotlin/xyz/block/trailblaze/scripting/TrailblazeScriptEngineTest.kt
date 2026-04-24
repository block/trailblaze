package xyz.block.trailblaze.scripting

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Unit-level coverage of [TrailblazeScriptEngine]: source-in, string-out with an optional
 * `input` global. Does not touch YAML parsing or the Trailblaze tool registry — see
 * [ScriptTrailblazeToolTest] for end-to-end expansion behavior.
 */
class TrailblazeScriptEngineTest {

  @Test
  fun `evaluates a bare string-returning expression`() {
    val result = TrailblazeScriptEngine.evaluate("return 'hello';")
    assertThat(result).isEqualTo("hello")
  }

  @Test
  fun `input is exposed as a global object the script can read`() {
    val input = buildJsonObject {
      put("memory", buildJsonObject { put("name", JsonPrimitive("sample-a")) })
      put("params", buildJsonObject { put("count", JsonPrimitive("3")) })
    }

    val result = TrailblazeScriptEngine.evaluate(
      source = "return input.memory.name + ':' + input.params.count;",
      input = input,
    )

    assertThat(result).isEqualTo("sample-a:3")
  }

  @Test
  fun `script with logic and template literals returns a multi-line string`() {
    val input = buildJsonObject {
      put("memory", buildJsonObject { put("appName", JsonPrimitive("SampleApp")) })
      put("params", buildJsonObject { })
    }

    val result = TrailblazeScriptEngine.evaluate(
      source = """
        const name = input.memory.appName || 'default';
        return '- launchApp:\n    appId: com.example.' + name.toLowerCase();
      """.trimIndent(),
      input = input,
    )

    assertThat(result).isEqualTo("- launchApp:\n    appId: com.example.sampleapp")
  }

  @Test
  fun `non-string return surfaces a ScriptEvaluationException`() {
    // Script returns a number, not a string. Engine must reject cleanly rather than
    // leaking the raw QuickJS exception.
    assertFailure {
      TrailblazeScriptEngine.evaluate("return 42;")
    }.transform { it::class.simpleName ?: "" }.isEqualTo("ScriptEvaluationException")
  }

  @Test
  fun `syntax error in source surfaces a ScriptEvaluationException`() {
    assertFailure {
      TrailblazeScriptEngine.evaluate("return (function() { this is not valid js };")
    }.transform { it::class.simpleName ?: "" }.isEqualTo("ScriptEvaluationException")
  }

  @Test
  fun `runtime throw inside script surfaces a ScriptEvaluationException`() {
    assertFailure {
      TrailblazeScriptEngine.evaluate("throw new Error('boom');")
    }.transform { it::class.simpleName ?: "" }.isEqualTo("ScriptEvaluationException")
  }

  @Test
  fun `trailblaze execute binding passes name and params and surfaces parsed result`() {
    val calls = mutableListOf<Pair<String, String>>()
    val dispatcher = TrailblazeScriptEngine.ToolDispatcher { name, paramsJson ->
      calls.add(name to paramsJson)
      """{"isError":false,"message":"ok from $name"}"""
    }

    val result = TrailblazeScriptEngine.evaluate(
      source = """
        const r = trailblaze.execute("assertVisibleWithText", { text: "Login" });
        return r.message + ":" + r.isError;
      """.trimIndent(),
      dispatcher = dispatcher,
    )

    assertThat(result).isEqualTo("ok from assertVisibleWithText:false")
    assertThat(calls).hasSize(1)
    assertThat(calls[0].first).isEqualTo("assertVisibleWithText")
    assertThat(calls[0].second).contains("\"text\":\"Login\"")
  }

  @Test
  fun `trailblaze execute returns error shape scripts can branch on`() {
    val dispatcher = TrailblazeScriptEngine.ToolDispatcher { _, _ ->
      """{"isError":true,"message":"element not found"}"""
    }

    val result = TrailblazeScriptEngine.evaluate(
      source = """
        const r = trailblaze.execute("assertVisibleWithText", { text: "Missing" });
        if (r.isError) {
          return "failed: " + r.message;
        }
        return "passed";
      """.trimIndent(),
      dispatcher = dispatcher,
    )

    assertThat(result).isEqualTo("failed: element not found")
  }

  @Test
  fun `trailblaze execute handles multiple calls and no-params invocations`() {
    var count = 0
    val dispatcher = TrailblazeScriptEngine.ToolDispatcher { name, paramsJson ->
      count++
      """{"isError":false,"message":"$name#$count params=$paramsJson"}"""
    }

    val result = TrailblazeScriptEngine.evaluate(
      source = """
        const a = trailblaze.execute("pressBack");
        const b = trailblaze.execute("pressBack");
        return a.message + "|" + b.message;
      """.trimIndent(),
      dispatcher = dispatcher,
    )

    assertThat(count).isEqualTo(2)
    // No-params invocation should round-trip as `{}`, not `null` or `undefined`.
    assertThat(result).contains("pressBack#1 params={}")
    assertThat(result).contains("pressBack#2 params={}")
  }

  @Test
  fun `dispatcher receives nested object and array params as valid JSON`() {
    val seen = mutableListOf<String>()
    val dispatcher = TrailblazeScriptEngine.ToolDispatcher { _, paramsJson ->
      seen.add(paramsJson)
      """{"isError":false}"""
    }

    TrailblazeScriptEngine.evaluate(
      source = """
        trailblaze.execute("someTool", {
          selector: { type: "id", value: "login-btn" },
          flags: [1, 2, 3],
          nested: { a: { b: "deep" } }
        });
        return "done";
      """.trimIndent(),
      dispatcher = dispatcher,
    )

    assertThat(seen).hasSize(1)
    val paramsJson = seen[0]
    // Basic shape checks — the JS object survives the round-trip through JSON.stringify.
    assertThat(paramsJson).contains("\"selector\"")
    assertThat(paramsJson).contains("\"flags\":[1,2,3]")
    assertThat(paramsJson).contains("\"nested\":{\"a\":{\"b\":\"deep\"}}")
  }

  @Test
  fun `engine without a dispatcher does not expose trailblaze global`() {
    assertFailure {
      TrailblazeScriptEngine.evaluate(
        source = "return typeof trailblaze.execute;",
      )
    }.transform { it::class.simpleName ?: "" }.isEqualTo("ScriptEvaluationException")
  }
}

package xyz.block.trailblaze.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Locks the wire-level constraint the OpenAI provider path depends on: a GPT-5.6-family chat
 * completion carrying function tools must ship `reasoning_effort: none`, and nothing else may be
 * rewritten.
 */
class OpenAiReasoningEffortCompatibilityPluginTest {

  private fun body(
    model: String,
    tools: String = TOOLS,
    reasoningEffort: String? = null,
  ): String = buildString {
    append("""{"model": "$model", "messages": [{"role": "user", "content": "hi"}]""")
    if (reasoningEffort != null) append(""", "reasoning_effort": "$reasoningEffort"""")
    if (tools.isNotEmpty()) append(""", "tools": $tools""")
    append("}")
  }

  private fun pin(requestBody: String): String? =
    OpenAiReasoningEffortCompatibilityPlugin.pinReasoningEffortForFunctionTools(requestBody)

  private fun reasoningEffortOf(requestBody: String): String? =
    (Json.parseToJsonElement(requestBody) as JsonObject)["reasoning_effort"]?.jsonPrimitive?.content

  @Test
  fun `a gpt-5_6 body with function tools is pinned to none`() {
    val pinned = pin(body("gpt-5.6-terra"))
    assertThat(pinned).isNotNull()
    assertThat(reasoningEffortOf(pinned!!)).isEqualTo("none")
  }

  @Test
  fun `an active reasoning effort is replaced rather than duplicated`() {
    val pinned = pin(body("gpt-5.6-terra", reasoningEffort = "high"))
    assertThat(pinned).isNotNull()
    assertThat(reasoningEffortOf(pinned!!)).isEqualTo("none")
    assertThat((Json.parseToJsonElement(pinned) as JsonObject).keys.count { it == "reasoning_effort" })
      .isEqualTo(1)
  }

  @Test
  fun `the dash-spelled deployment id is pinned too`() {
    assertThat(reasoningEffortOf(pin(body("gpt-5-6"))!!)).isEqualTo("none")
  }

  @Test
  fun `a non gpt-5_6 body passes through untouched`() {
    assertThat(pin(body("gpt-4.1"))).isNull()
    assertThat(pin(body("claude-haiku-4-5"))).isNull()
    assertThat(pin(body("gemini-3-5-flash"))).isNull()
  }

  @Test
  fun `a gpt-5_6 body without function tools passes through untouched`() {
    assertThat(pin(body("gpt-5.6-terra", tools = ""))).isNull()
    assertThat(pin(body("gpt-5.6-terra", tools = "[]"))).isNull()
  }

  @Test
  fun `a body already pinned to none passes through untouched`() {
    assertThat(pin(body("gpt-5.6-terra", reasoningEffort = "none"))).isNull()
  }

  @Test
  fun `a body with no model field passes through untouched`() {
    assertThat(pin("""{"messages": [], "tools": $TOOLS}""")).isNull()
  }

  @Test
  fun `a non-json body passes through untouched`() {
    assertThat(pin("not json at all")).isNull()
    assertThat(pin("")).isNull()
  }

  @Test
  fun `sibling fields survive the rewrite`() {
    val pinned = Json.parseToJsonElement(pin(body("gpt-5.6-terra"))!!) as JsonObject
    assertThat(pinned["model"]!!.jsonPrimitive.content).isEqualTo("gpt-5.6-terra")
    val original = Json.parseToJsonElement(body("gpt-5.6-terra")) as JsonObject
    assertThat(pinned["messages"]).isEqualTo(original["messages"])
    assertThat(pinned["tools"]).isEqualTo(original["tools"])
  }

  companion object {
    private const val TOOLS =
      """[{"type": "function", "function": {"name": "tapOn", "parameters": {}}}]"""
  }
}

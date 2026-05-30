package xyz.block.trailblaze.scripting.callback

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Wire-shape round-trip coverage for [JsScriptingCallbackResult.CallToolResult.structuredContent].
 *
 * The dispatcher test pins the *producer* side (a tool returning a structured payload lands
 * onto the wire); this file pins the *serializer* side (the wire-shape's JSON encoding +
 * lenient backward-compat decoding). Together they guarantee a TS SDK consumer that decodes
 * a [JsScriptingCallbackResponse] over either transport sees the structured payload exactly as
 * the producer set it, AND that a pre-PR producer (no `structured_content` key) keeps
 * deserializing cleanly.
 *
 * Mirrors the `Json` configuration the `/scripting/callback` HTTP endpoint uses
 * ([ScriptingCallbackEndpoint] in `:trailblaze-server`) — `classDiscriminator = "type"` so the
 * sealed-interface variant tag rides as the `type` field, and `ignoreUnknownKeys = true` so
 * future additive fields (a `version`, a binary content variant, etc.) don't fail decode on
 * older SDKs.
 */
class JsScriptingCallbackContractTest {

  // Mirror the production [ScriptingCallbackEndpoint] Json config exactly so a wire-shape
  // assertion here pins production behavior, not a test-only encoding. `encodeDefaults` is
  // intentionally left at the kotlinx default (false) — that's what omits `structured_content`
  // from the wire when the field is null, which is the omission this test pins below.
  private val json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
  }

  @Test
  fun `CallToolResult with structuredContent round-trips JSON intact`() {
    val payload: JsonObject = buildJsonObject {
      put("formatted", JsonPrimitive("prefix:msg"))
      put("inputLength", JsonPrimitive(3))
    }
    val original = JsScriptingCallbackResult.CallToolResult(
      success = true,
      textContent = "",
      structuredContent = payload,
    )
    val encoded = json.encodeToString(JsScriptingCallbackResult.serializer(), original)
    // The wire uses snake_case `structured_content` per `@SerialName`. Verifying the literal
    // key here pins the contract a TS SDK consumer reads off the response — anything else
    // would silently break the unwrap path.
    assertThat(encoded).contains("\"structured_content\"")
    assertThat(encoded).contains("\"formatted\":\"prefix:msg\"")
    assertThat(encoded).contains("\"inputLength\":3")

    val decoded = json.decodeFromString(JsScriptingCallbackResult.serializer(), encoded)
    val decodedCallToolResult = decoded as JsScriptingCallbackResult.CallToolResult
    assertThat(decodedCallToolResult.success).isEqualTo(true)
    assertThat(decodedCallToolResult.structuredContent).isEqualTo(payload)
  }

  @Test
  fun `CallToolResult without structuredContent omits the key on the wire`() {
    val original = JsScriptingCallbackResult.CallToolResult(
      success = true,
      textContent = "plain text",
    )
    val encoded = json.encodeToString(JsScriptingCallbackResult.serializer(), original)
    // Producer that doesn't populate the field must not synthesize one — otherwise the TS
    // SDK's unwrap branch ("if structured_content is non-null, return it as the typed
    // result") would fire on every legacy tool and return null/empty objects in place of
    // the expected string. Shape-aware key absence check rather than a substring search:
    // parse the encoded JSON back to a [JsonObject] and assert the field name isn't a key.
    // A naive `doesNotContain("structured_content")` would false-positive if any other
    // field's *value* happened to contain that substring (e.g. an error message about the
    // field), and `encodeDefaults = true` would silently re-emit the key as explicit-null
    // and still pass a round-trip check.
    val encodedJsonKeys = json.parseToJsonElement(encoded).jsonObject.keys
    assertThat(encodedJsonKeys.contains("structured_content")).isFalse()
    val decoded = json.decodeFromString(JsScriptingCallbackResult.serializer(), encoded)
    val decodedCallToolResult = decoded as JsScriptingCallbackResult.CallToolResult
    assertThat(decodedCallToolResult.structuredContent).isNull()
    assertThat(decodedCallToolResult.textContent).isEqualTo("plain text")
  }

  @Test
  fun `legacy wire JSON without structured_content key decodes with null`() {
    // Forward-compat companion: an older daemon shipping the pre-`structured_content` wire
    // shape (or a producer that hasn't migrated) sends a CallToolResult JSON object without
    // the new key. A consumer on this PR's SDK must decode it without complaint and treat
    // the missing field as null — never an error, never a default-stub.
    val legacyJson = """
      {"type":"call_tool_result","success":true,"text_content":"hi","error_message":""}
    """.trimIndent()
    val decoded = json.decodeFromString(JsScriptingCallbackResult.serializer(), legacyJson)
    val decodedCallToolResult = decoded as JsScriptingCallbackResult.CallToolResult
    assertThat(decodedCallToolResult.success).isEqualTo(true)
    assertThat(decodedCallToolResult.textContent).isEqualTo("hi")
    assertThat(decodedCallToolResult.structuredContent).isNull()
  }
}

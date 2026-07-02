package xyz.block.trailblaze.logs.client.temp

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pins [YamlJsonBridge]'s scalar→JSON coercion, specifically that zero-padded / non-canonical numeric
 * scalars are preserved as STRINGS rather than lossily coerced to numbers.
 *
 * Regression: a scripted (TypeScript) tool's recorded arg flows YAML → [YamlJsonBridge] → JSON →
 * QuickJS. kaml's `YamlScalar` discards quote style, so `"0000"` and `0000` both arrive as
 * `content == "0000"`; the old `toLongOrNull()` coercion turned that into the number `0`, so a
 * text-input tool typed `"0"` instead of the zero-padded PIN `"0000"` and the PIN never submitted (the
 * former Kotlin `@Serializable` String-typed tool preserved it). The fix treats a scalar as a number
 * only when it round-trips exactly.
 */
class YamlJsonBridgeScalarCoercionTest {

  private fun primitiveFor(yaml: String): JsonPrimitive =
    YamlJsonBridge.yamlNodeToJsonElement(Yaml.default.parseToYamlNode(yaml)) as JsonPrimitive

  @Test
  fun `zero-padded scalars stay strings, not coerced to numbers`() {
    for (raw in listOf("\"0000\"", "0000", "\"07928\"", "007", "\"0123\"")) {
      val p = primitiveFor(raw)
      val expected = raw.trim('"')
      assertTrue(p.isString, "expected string primitive for $raw, got $p")
      assertEquals(expected, p.content, "zero-padded scalar $raw must be preserved verbatim")
    }
  }

  @Test
  fun `canonical numbers still decode as numbers`() {
    for (raw in listOf("5", "0", "123123", "-4")) {
      val p = primitiveFor(raw)
      assertTrue(!p.isString, "expected numeric primitive for $raw, got $p")
      assertEquals(raw, p.content)
    }
  }

  @Test
  fun `canonical decimals decode as numbers - booleans and plain strings unchanged`() {
    assertTrue(!primitiveFor("3.5").isString)
    assertEquals("3.5", primitiveFor("3.5").content)
    assertEquals("true", primitiveFor("true").content)
    assertTrue(!primitiveFor("true").isString)
    assertTrue(primitiveFor("\"hello\"").isString)
    assertEquals("hello", primitiveFor("\"hello\"").content)
  }

  @Test
  fun `coerceNumbers = false keeps even canonical numeric scalars as strings`() {
    // Regression for a real login-flow CI failure: a verification code like "123123" is a
    // clean, zero-padding-free numeric string, so the round-trip check above intentionally still
    // treats it as a number by default. A caller whose scalar is ALWAYS free-form text at the
    // schema level (e.g. MaestroTrailblazeToolSerializer, whose fields are Maestro command
    // arguments that Maestro's own lenient parser will coerce back to a number if it actually
    // needs one) must opt out entirely rather than rely on the round-trip guess.
    for (raw in listOf("5", "0", "123123", "-4", "3.5")) {
      val p = YamlJsonBridge.yamlNodeToJsonElement(
        Yaml.default.parseToYamlNode(raw),
        coerceNumbers = false,
      ) as JsonPrimitive
      assertTrue(p.isString, "expected string primitive for $raw with coerceNumbers=false, got $p")
      assertEquals(raw, p.content)
    }
    // Booleans are unaffected by coerceNumbers — only the number-guessing branch is gated.
    val boolPrimitive = YamlJsonBridge.yamlNodeToJsonElement(
      Yaml.default.parseToYamlNode("true"),
      coerceNumbers = false,
    ) as JsonPrimitive
    assertTrue(!boolPrimitive.isString)
  }
}

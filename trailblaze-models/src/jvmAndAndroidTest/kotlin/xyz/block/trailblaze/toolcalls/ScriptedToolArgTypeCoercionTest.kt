package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptedToolArgTypeCoercionTest {

  private fun descriptor(vararg params: Pair<String, String>) = TrailblazeToolDescriptor(
    name = "scripted_tool",
    optionalParameters = params.map { (n, t) -> TrailblazeToolParameterDescriptor(name = n, type = t) },
  )

  @Test
  fun `number arriving for a string param is stringified (the passcode bug)`() {
    // A recorded quoted `'12345678'` decodes as a JSON number; the tool declares it a string.
    val args = buildJsonObject { put("passcode", 12345678L) }
    val out = coerceArgsToDescriptorTypes(args, descriptor("passcode" to "string"))
    val v = out["passcode"] as JsonPrimitive
    assertTrue(v.isString)
    assertEquals("12345678", v.content)
  }

  @Test
  fun `boolean arriving for a string param is stringified (the setFeatureFlag bug)`() {
    val args = buildJsonObject { put("value", true) }
    val out = coerceArgsToDescriptorTypes(args, descriptor("value" to "string"))
    val v = out["value"] as JsonPrimitive
    assertTrue(v.isString)
    assertEquals("true", v.content)
  }

  @Test
  fun `zero-padded string for a string param keeps its leading zeros`() {
    // Must never round-trip through a number — that would drop the leading zero.
    val args = buildJsonObject { put("pin", "0130") }
    val out = coerceArgsToDescriptorTypes(args, descriptor("pin" to "string"))
    val v = out["pin"] as JsonPrimitive
    assertTrue(v.isString)
    assertEquals("0130", v.content)
  }

  @Test
  fun `canonical numeric string for a number param becomes a number`() {
    // The faithful-recording direction: a string that should be numeric per the schema.
    val args = buildJsonObject { put("timeoutMs", "5000") }
    val out = coerceArgsToDescriptorTypes(args, descriptor("timeoutMs" to "number"))
    val v = out["timeoutMs"] as JsonPrimitive
    assertFalse(v.isString)
    assertEquals(5000L, v.long)
  }

  @Test
  fun `zero-padded string for a number param is left alone (no lossy coercion)`() {
    val args = buildJsonObject { put("code", "0130") }
    val out = coerceArgsToDescriptorTypes(args, descriptor("code" to "number"))
    val v = out["code"] as JsonPrimitive
    assertTrue(v.isString)
    assertEquals("0130", v.content)
  }

  @Test
  fun `true false string for a boolean param becomes a boolean`() {
    val args = buildJsonObject {
      put("enable", "false")
      put("selected", "TRUE")
    }
    val out = coerceArgsToDescriptorTypes(args, descriptor("enable" to "boolean", "selected" to "boolean"))
    assertFalse((out["enable"] as JsonPrimitive).boolean)
    assertTrue((out["selected"] as JsonPrimitive).boolean)
  }

  @Test
  fun `value already matching its declared type is untouched`() {
    val args = buildJsonObject {
      put("name", "Sam")
      put("count", 3L)
      put("on", true)
    }
    val out = coerceArgsToDescriptorTypes(
      args,
      descriptor("name" to "string", "count" to "number", "on" to "boolean"),
    )
    // No change means the same instance is returned.
    assertSame(args, out)
  }

  @Test
  fun `keys the descriptor does not declare are left as-is`() {
    val args = buildJsonObject { put("mystery", 42L) }
    val out = coerceArgsToDescriptorTypes(args, descriptor("other" to "string"))
    assertSame(args, out)
  }

  @Test
  fun `object and array values are never coerced`() {
    val args = buildJsonObject {
      put("nested", buildJsonObject { put("x", 1L) })
      put("list", buildJsonArray { add(JsonPrimitive(1)) })
    }
    val out = coerceArgsToDescriptorTypes(args, descriptor("nested" to "string", "list" to "string"))
    assertSame(args, out)
  }

  @Test
  fun `required and optional params are both consulted`() {
    val d = TrailblazeToolDescriptor(
      name = "t",
      requiredParameters = listOf(TrailblazeToolParameterDescriptor(name = "req", type = "string")),
      optionalParameters = listOf(TrailblazeToolParameterDescriptor(name = "opt", type = "string")),
    )
    val args = buildJsonObject {
      put("req", 1L)
      put("opt", 2L)
    }
    val out = coerceArgsToDescriptorTypes(args, d)
    assertEquals("1", (out["req"] as JsonPrimitive).content)
    assertEquals("2", (out["opt"] as JsonPrimitive).content)
    assertTrue((out["req"] as JsonPrimitive).isString)
  }

  @Test
  fun `explicit null for a string param is left as JSON null (never the string "null")`() {
    // JsonNull is a JsonPrimitive; it must not be coerced to JsonPrimitive("null").
    val args = buildJsonObject { put("note", JsonNull) }
    val out = coerceArgsToDescriptorTypes(args, descriptor("note" to "string"))
    assertSame(args, out)
    assertTrue(out["note"] is JsonNull)
  }

  @Test
  fun `empty args are returned unchanged`() {
    val args = JsonObject(emptyMap())
    assertSame(args, coerceArgsToDescriptorTypes(args, descriptor("x" to "string")))
  }
}

package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

  // ── Nested coercion (schema-driven) ──────────────────────────────────────────────────────────
  //
  // A flat parameter list can't express `overrides[].value` — nested coercion is driven off the
  // tool's JSON Schema. Neutral tool/param names only (this file is public).

  /** `{ type: object, properties: { <prop>: <propSchema> }, ... }`. */
  private fun objectSchema(vararg props: Pair<String, JsonObject>) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (name, schema) -> put(name, schema) } }
  }

  private fun scalarSchema(type: String) = buildJsonObject { put("type", type) }

  private fun arraySchema(items: JsonObject) = buildJsonObject {
    put("type", "array")
    put("items", items)
  }

  private fun schemaDescriptor(schema: JsonObject) =
    TrailblazeToolDescriptor(name = "scripted_tool").apply { inputSchema = schema }

  @Test
  fun `array-of-objects coerces a nested boolean to a string (the setFeatureFlag bug)`() {
    // overrides: [{ flagKey: string, value: string }] — recorded `value: 'true'` decodes to a
    // nested JSON boolean; the item schema declares it a string.
    val itemSchema = objectSchema("flagKey" to scalarSchema("string"), "value" to scalarSchema("string"))
    val schema = objectSchema("overrides" to arraySchema(itemSchema))
    val args = buildJsonObject {
      putJsonArray("overrides") {
        addJsonObject {
          put("flagKey", "convergence")
          put("value", true)
        }
      }
    }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val override = (out["overrides"] as JsonArray).single() as JsonObject
    val value = override["value"] as JsonPrimitive
    assertTrue(value.isString)
    assertEquals("true", value.content)
    // The sibling string is untouched.
    assertEquals("convergence", (override["flagKey"] as JsonPrimitive).content)
  }

  @Test
  fun `nested object coerces a number arriving for a nested string param`() {
    val nested = objectSchema("passcode" to scalarSchema("string"))
    val schema = objectSchema("credentials" to nested)
    val args = buildJsonObject { putJsonObject("credentials") { put("passcode", 12345678L) } }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val passcode = (out["credentials"] as JsonObject)["passcode"] as JsonPrimitive
    assertTrue(passcode.isString)
    assertEquals("12345678", passcode.content)
  }

  @Test
  fun `array of scalars coerces each canonical numeric string to a number`() {
    val schema = objectSchema("timeouts" to arraySchema(scalarSchema("number")))
    val args = buildJsonObject { putJsonArray("timeouts") { add("5000"); add("250") } }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val list = out["timeouts"] as JsonArray
    assertEquals(5000L, (list[0] as JsonPrimitive).long)
    assertEquals(250L, (list[1] as JsonPrimitive).long)
    assertFalse((list[0] as JsonPrimitive).isString)
  }

  @Test
  fun `zero-padded string inside a nested object is left alone (no lossy coercion)`() {
    val itemSchema = objectSchema("code" to scalarSchema("number"))
    val schema = objectSchema("entries" to arraySchema(itemSchema))
    val args = buildJsonObject {
      putJsonArray("entries") { addJsonObject { put("code", "0130") } }
    }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    // Unchanged — a zero-padded string must never round-trip through a number, even nested.
    assertSame(args, out)
    val code = ((out["entries"] as JsonArray).single() as JsonObject)["code"] as JsonPrimitive
    assertTrue(code.isString)
    assertEquals("0130", code.content)
  }

  @Test
  fun `explicit null inside a nested object is left as JSON null`() {
    val nested = objectSchema("note" to scalarSchema("string"))
    val schema = objectSchema("details" to nested)
    val args = buildJsonObject { putJsonObject("details") { put("note", JsonNull) } }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    assertSame(args, out)
    assertTrue((out["details"] as JsonObject)["note"] is JsonNull)
  }

  @Test
  fun `fully type-correct nested args return the same instance`() {
    val itemSchema = objectSchema("flagKey" to scalarSchema("string"), "value" to scalarSchema("string"))
    val schema = objectSchema("overrides" to arraySchema(itemSchema))
    val args = buildJsonObject {
      putJsonArray("overrides") {
        addJsonObject {
          put("flagKey", "convergence")
          put("value", "true")
        }
      }
    }

    assertSame(args, coerceArgsToDescriptorTypes(args, schemaDescriptor(schema)))
  }

  @Test
  fun `an array whose items schema is absent is left untouched`() {
    // No `items` schema → nothing to coerce against; the values pass through verbatim.
    val schema = objectSchema("anything" to buildJsonObject { put("type", "array") })
    val args = buildJsonObject { putJsonArray("anything") { add(1L); add(true) } }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `a nested boolean is coerced through a local definitions ref (the real analyzer shape)`() {
    // ts-json-schema-generator emits a named nested type as a `$ref` into `definitions`, not an
    // inline object: `overrides.items` is `{ "$ref": "#/definitions/FlagOverride" }`. The ref must
    // be resolved to reach `value`.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("overrides") {
          put("type", "array")
          putJsonObject("items") { put("\$ref", "#/definitions/FlagOverride") }
        }
      }
      putJsonObject("definitions") {
        put("FlagOverride", objectSchema("flagKey" to scalarSchema("string"), "value" to scalarSchema("string")))
      }
    }
    val args = buildJsonObject {
      putJsonArray("overrides") {
        addJsonObject {
          put("flagKey", "search-convergence")
          put("value", false)
        }
      }
    }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val override = (out["overrides"] as JsonArray).single() as JsonObject
    val value = override["value"] as JsonPrimitive
    assertTrue(value.isString)
    assertEquals("false", value.content)
  }

  @Test
  fun `a nested boolean is coerced through a local defs ref (draft 2020-12 shape)`() {
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("override") { put("\$ref", "#/\$defs/FlagOverride") }
      }
      putJsonObject("\$defs") {
        put("FlagOverride", objectSchema("value" to scalarSchema("string")))
      }
    }
    val args = buildJsonObject { putJsonObject("override") { put("value", true) } }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val value = (out["override"] as JsonObject)["value"] as JsonPrimitive
    assertTrue(value.isString)
    assertEquals("true", value.content)
  }

  @Test
  fun `an unresolvable ref is left untouched (no definitions block)`() {
    // A `$ref` with no matching definition can't be followed — the value passes through verbatim.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("mystery") { put("\$ref", "#/definitions/Missing") }
      }
    }
    val args = buildJsonObject { put("mystery", 42L) }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `a self-referential ref cycle terminates without coercing`() {
    // A definition that refs itself must not loop forever; it resolves to itself, finds no `type`,
    // and leaves the value alone.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("node") { put("\$ref", "#/definitions/Node") }
      }
      putJsonObject("definitions") {
        putJsonObject("Node") { put("\$ref", "#/definitions/Node") }
      }
    }
    val args = buildJsonObject { putJsonObject("node") { put("x", 1L) } }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `a nested object property with no declared type is left untouched`() {
    // A bare `$ref`/`anyOf` nested prop carries no `type`; a scalar under it is left as-is
    // (conservative — never guess a type the schema didn't declare).
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("mystery") { put("\$ref", "#/definitions/Foo") }
      }
    }
    val args = buildJsonObject { put("mystery", 42L) }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `a mutual ref cycle A to B to A terminates without coercing`() {
    // The self-cycle test trips the guard on the first hop; a mutual cycle is the only case that
    // walks the loop across two distinct refs before the seen-set short-circuits.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("node") { put("\$ref", "#/definitions/A") }
      }
      putJsonObject("definitions") {
        putJsonObject("A") { put("\$ref", "#/definitions/B") }
        putJsonObject("B") { put("\$ref", "#/definitions/A") }
      }
    }
    val args = buildJsonObject { putJsonObject("node") { put("x", 1L) } }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `refs into both definitions and defs in one schema are each resolved`() {
    // collectDefinitions merges both blocks; a schema can legitimately carry each.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("legacy") { put("\$ref", "#/definitions/Legacy") }
        putJsonObject("modern") { put("\$ref", "#/\$defs/Modern") }
      }
      putJsonObject("definitions") {
        put("Legacy", objectSchema("value" to scalarSchema("string")))
      }
      putJsonObject("\$defs") {
        put("Modern", objectSchema("value" to scalarSchema("string")))
      }
    }
    val args = buildJsonObject {
      putJsonObject("legacy") { put("value", true) }
      putJsonObject("modern") { put("value", 7L) }
    }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    assertEquals("true", ((out["legacy"] as JsonObject)["value"] as JsonPrimitive).content)
    assertEquals("7", ((out["modern"] as JsonObject)["value"] as JsonPrimitive).content)
  }

  @Test
  fun `a non-local ref is left untouched`() {
    // Only local `#/definitions` / `#/$defs` refs are followed; anything else is conservatively skipped.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("external") { put("\$ref", "https://example.com/schema.json#/Foo") }
      }
    }
    val args = buildJsonObject { putJsonObject("external") { put("value", true) } }

    assertSame(args, coerceArgsToSchemaTypes(args, schema))
  }

  @Test
  fun `a nullable-scalar union type coerces like a plain scalar`() {
    // ts-json-schema-generator emits a nullable string as `{ "type": ["string", "null"] }`; the
    // flat path defaulted this to "string" and coerced, so the schema path must too.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("note") { putJsonArray("type") { add("string"); add("null") } }
      }
    }
    val args = buildJsonObject { put("note", true) }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    val note = out["note"] as JsonPrimitive
    assertTrue(note.isString)
    assertEquals("true", note.content)
  }

  @Test
  fun `a non-finite string for a number param is left as a string (never NaN or Infinity)`() {
    // "NaN"/"Infinity" round-trip through Double but are not valid JSON numbers — coercing them
    // would produce a literal the default kotlinx Json throws on when re-encoding.
    val schema = objectSchema("ratio" to scalarSchema("number"), "scale" to scalarSchema("number"))
    val args = buildJsonObject {
      put("ratio", "NaN")
      put("scale", "Infinity")
    }

    val out = coerceArgsToDescriptorTypes(args, schemaDescriptor(schema))

    assertSame(args, out)
    assertTrue((out["ratio"] as JsonPrimitive).isString)
    assertEquals("NaN", (out["ratio"] as JsonPrimitive).content)
    assertEquals("Infinity", (out["scale"] as JsonPrimitive).content)
  }
}

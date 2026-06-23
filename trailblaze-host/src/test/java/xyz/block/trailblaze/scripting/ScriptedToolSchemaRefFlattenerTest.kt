package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ScriptedToolSchemaRefFlattener] — the pure schema transform that inlines the
 * `$ref` / `definitions` shape `ts-json-schema-generator` emits for named types (string-literal
 * union enums, `Record<string, T>`, nested interfaces). These are the deterministic core regression
 * guards for the enum-param subprocess-registration bug; they need no `bun` / Node.
 */
class ScriptedToolSchemaRefFlattenerTest {

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  /** True if a `$ref` key appears ANYWHERE in the tree — stronger than a `toString().contains`. */
  private fun containsRef(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.keys.contains("\$ref") || element.values.any { containsRef(it) }
    is JsonArray -> element.any { containsRef(it) }
    else -> false
  }

  @Test
  fun `top-level enum ref is inlined and the definitions bag dropped`() {
    // The exact shape ts-json-schema-generator emits for `type Dir = "UP" | "DOWN"; { direction: Dir }`.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "direction": { "${'$'}ref": "#/definitions/Dir" } },
        "required": ["direction"],
        "${'$'}schema": "http://json-schema.org/draft-07/schema#",
        "definitions": { "Dir": { "type": "string", "enum": ["UP", "DOWN"] } }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)

    assertNull(flat["definitions"], "definitions bag must be dropped")
    assertNull(flat["\$schema"], "\$schema must be dropped")
    val direction = assertNotNull(flat["properties"]?.jsonObject?.get("direction")?.jsonObject)
    assertNull(direction["\$ref"], "the \$ref must be replaced by the inlined definition")
    assertEquals("string", direction["type"]?.jsonPrimitive?.content)
    assertEquals(listOf("UP", "DOWN"), direction["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    // Untouched siblings survive.
    assertEquals("object", flat["type"]?.jsonPrimitive?.content)
    assertEquals(listOf("direction"), flat["required"]?.jsonArray?.map { it.jsonPrimitive.content })
  }

  @Test
  fun `property-level keys win over the referenced definition on merge`() {
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "direction": { "${'$'}ref": "#/definitions/Dir", "description": "Property-level doc." }
        },
        "definitions": {
          "Dir": { "type": "string", "enum": ["UP"], "description": "Definition-level doc." }
        }
      }
      """.trimIndent(),
    )

    val direction = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]
      ?.jsonObject?.get("direction")?.jsonObject
    assertNotNull(direction)
    assertEquals("string", direction["type"]?.jsonPrimitive?.content)
    assertEquals(
      "Property-level doc.",
      direction["description"]?.jsonPrimitive?.content,
      "the referencing property's own description must win over the definition's",
    )
  }

  @Test
  fun `url-encoded ref name resolves against the decoded definitions key`() {
    // ts-json-schema-generator percent-encodes special chars in the pointer but keys the bag by the
    // decoded name — e.g. `Record<string,number>`. The one-level non-decoding predecessor missed this.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "attrs": { "${'$'}ref": "#/definitions/Record%3Cstring%2Cnumber%3E" } },
        "definitions": {
          "Record<string,number>": { "type": "object", "additionalProperties": { "type": "number" } }
        }
      }
      """.trimIndent(),
    )

    val attrs = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]
      ?.jsonObject?.get("attrs")?.jsonObject
    assertNotNull(attrs)
    assertNull(attrs["\$ref"])
    assertEquals("object", attrs["type"]?.jsonPrimitive?.content)
    assertEquals("number", attrs["additionalProperties"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
  }

  @Test
  fun `nested and array refs are resolved recursively`() {
    // A ref nested under a property's object, and a ref under array `items` — both must inline.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "user": { "type": "object", "properties": { "role": { "${'$'}ref": "#/definitions/Role" } } },
          "tags": { "type": "array", "items": { "${'$'}ref": "#/definitions/Role" } }
        },
        "definitions": { "Role": { "type": "string", "enum": ["ADMIN", "USER"] } }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)
    val role = flat["properties"]?.jsonObject?.get("user")?.jsonObject
      ?.get("properties")?.jsonObject?.get("role")?.jsonObject
    assertNotNull(role)
    assertEquals(listOf("ADMIN", "USER"), role["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    val items = flat["properties"]?.jsonObject?.get("tags")?.jsonObject?.get("items")?.jsonObject
    assertNotNull(items)
    assertEquals("string", items["type"]?.jsonPrimitive?.content)
    assertNull(items["\$ref"], "array items \$ref must be inlined")
  }

  @Test
  fun `dollar-defs bag is supported as an alias for definitions`() {
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "direction": { "${'$'}ref": "#/${'$'}defs/Dir" } },
        "${'$'}defs": { "Dir": { "type": "string", "enum": ["L", "R"] } }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)
    assertNull(flat["\$defs"])
    val direction = flat["properties"]?.jsonObject?.get("direction")?.jsonObject
    assertEquals(listOf("L", "R"), direction?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content })
  }

  @Test
  fun `unresolvable ref is left untouched rather than throwing`() {
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "mystery": { "${'$'}ref": "#/definitions/DoesNotExist" } },
        "definitions": { "Dir": { "type": "string" } }
      }
      """.trimIndent(),
    )

    // Must not throw; the dangling ref survives so the shape isn't silently corrupted.
    val mystery = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]
      ?.jsonObject?.get("mystery")?.jsonObject
    assertEquals("#/definitions/DoesNotExist", mystery?.get("\$ref")?.jsonPrimitive?.content)
  }

  @Test
  fun `cyclic ref does not loop forever and leaves the back-edge in place`() {
    // A self-referential type (e.g. a tree node) — ts-json-schema-generator MUST use a $ref here
    // because it can't inline it. The flattener inlines the first hop and leaves the cycle's
    // back-edge as a $ref instead of recursing forever.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "root": { "${'$'}ref": "#/definitions/Node" } },
        "definitions": {
          "Node": {
            "type": "object",
            "properties": { "child": { "${'$'}ref": "#/definitions/Node" } }
          }
        }
      }
      """.trimIndent(),
    )

    val root = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]?.jsonObject?.get("root")?.jsonObject
    assertNotNull(root)
    assertEquals("object", root["type"]?.jsonPrimitive?.content)
    // First hop inlined; the recursive back-edge stays a $ref (cycle guard kicked in).
    val child = root["properties"]?.jsonObject?.get("child")?.jsonObject
    assertEquals("#/definitions/Node", child?.get("\$ref")?.jsonPrimitive?.content)
  }

  @Test
  fun `ref-free schema round-trips equivalently and idempotently`() {
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "name": { "type": "string" }, "count": { "type": "integer" } },
        "required": ["name"]
      }
      """.trimIndent(),
    )

    val once = ScriptedToolSchemaRefFlattener.flatten(schema)
    assertEquals(schema, once, "a schema with no \$ref / bag must be unchanged")
    assertEquals(once, ScriptedToolSchemaRefFlattener.flatten(once), "flatten must be idempotent")
  }

  @Test
  fun `the flattened output contains no remaining ref anywhere`() {
    // Belt-and-suspenders: the synthesizer throws on ANY bare $ref, so assert the whole subtree is
    // ref-free after flattening a multi-shape schema.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "direction": { "${'$'}ref": "#/definitions/Dir" },
          "attrs": { "${'$'}ref": "#/definitions/Attrs" }
        },
        "definitions": {
          "Dir": { "type": "string", "enum": ["UP", "DOWN"] },
          "Attrs": { "type": "object", "additionalProperties": { "type": "string" } }
        }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)
    assertTrue(!containsRef(flat), "no \$ref key must remain anywhere after flattening; got: $flat")
  }

  @Test
  fun `a tool param literally named definitions survives flattening`() {
    // Regression: an earlier draft dropped `definitions` / `$defs` / `$schema` at EVERY nesting
    // level, so a tool whose input has a property literally named `definitions` lost it. The bag is
    // a meta key only at the ROOT; inside `properties` those are author-chosen param names.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "definitions": { "type": "string" },
          "${'$'}defs": { "type": "integer" },
          "direction": { "${'$'}ref": "#/definitions/Dir" }
        },
        "definitions": { "Dir": { "type": "string", "enum": ["UP", "DOWN"] } }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)
    val props = assertNotNull(flat["properties"]?.jsonObject)
    // The root definitions bag is gone...
    assertNull(flat["definitions"], "the ROOT definitions bag must still be stripped")
    // ...but the author's params named `definitions` / `$defs` survive untouched.
    assertEquals("string", props["definitions"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals("integer", props["\$defs"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    // ...and the real ref still inlines.
    assertEquals(
      listOf("UP", "DOWN"),
      props["direction"]?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `non-string ref value is treated as an ordinary property, not a reference`() {
    // A malformed `"$ref": 123` is not a JSON-Schema reference; leave it as-is, don't throw.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "weird": { "${'$'}ref": 123 } },
        "definitions": { "Dir": { "type": "string" } }
      }
      """.trimIndent(),
    )

    val weird = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]?.jsonObject?.get("weird")?.jsonObject
    assertNotNull(weird)
    assertEquals(123, weird["\$ref"]?.jsonPrimitive?.content?.toInt())
  }

  @Test
  fun `ref to a non-object definition is left untouched`() {
    // If a definitions entry is a primitive (or array) rather than an object schema, the ref can't
    // be inlined — leave it rather than crash.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": { "x": { "${'$'}ref": "#/definitions/P" } },
        "definitions": { "P": "string" }
      }
      """.trimIndent(),
    )

    val x = ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]?.jsonObject?.get("x")?.jsonObject
    assertEquals("#/definitions/P", x?.get("\$ref")?.jsonPrimitive?.content)
  }

  @Test
  fun `refs that are not single-segment local definitions pointers are left untouched`() {
    // Only `#/definitions/<name>` and `#/$defs/<name>` are inlinable. An external ref, a foreign
    // JSON-pointer root, or a DEEP pointer must NOT be resolved by grabbing a last segment that
    // happens to collide with a bag key (regression for the over-eager substringAfterLast).
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "ext":  { "${'$'}ref": "other.json#/definitions/Dir" },
          "deep": { "${'$'}ref": "#/definitions/Outer/properties/inner" },
          "frag": { "${'$'}ref": "#/components/schemas/Dir" }
        },
        "definitions": {
          "Dir": { "type": "string", "enum": ["UP"] },
          "inner": { "type": "string" }
        }
      }
      """.trimIndent(),
    )

    val props = assertNotNull(ScriptedToolSchemaRefFlattener.flatten(schema)["properties"]?.jsonObject)
    assertEquals(
      "other.json#/definitions/Dir",
      props["ext"]?.jsonObject?.get("\$ref")?.jsonPrimitive?.content,
      "external ref must be left untouched",
    )
    assertEquals(
      "#/definitions/Outer/properties/inner",
      props["deep"]?.jsonObject?.get("\$ref")?.jsonPrimitive?.content,
      "deep JSON pointer must not be collapsed to its last segment",
    )
    assertEquals(
      "#/components/schemas/Dir",
      props["frag"]?.jsonObject?.get("\$ref")?.jsonPrimitive?.content,
      "a non-definitions pointer root must be left untouched even though 'Dir' is a bag key",
    )
  }

  @Test
  fun `refs inside oneOf anyOf allOf branches are resolved`() {
    // Pins the kdoc's claim that composition keywords (which hold arrays of sub-schemas) flatten.
    val schema = parse(
      """
      {
        "type": "object",
        "properties": {
          "choice": {
            "oneOf": [ { "${'$'}ref": "#/definitions/Dir" }, { "type": "null" } ],
            "anyOf": [ { "${'$'}ref": "#/definitions/Dir" } ],
            "allOf": [ { "${'$'}ref": "#/definitions/Dir" } ]
          }
        },
        "definitions": { "Dir": { "type": "string", "enum": ["UP", "DOWN"] } }
      }
      """.trimIndent(),
    )

    val flat = ScriptedToolSchemaRefFlattener.flatten(schema)
    assertTrue(!containsRef(flat), "composition-branch refs must all be inlined; got: $flat")
    val choice = assertNotNull(flat["properties"]?.jsonObject?.get("choice")?.jsonObject)
    val oneOfFirst = choice["oneOf"]?.jsonArray?.first()?.jsonObject
    assertEquals(
      listOf("UP", "DOWN"),
      oneOfFirst?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content },
    )
  }
}

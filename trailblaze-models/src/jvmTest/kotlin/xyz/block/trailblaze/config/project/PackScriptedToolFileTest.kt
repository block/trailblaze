package xyz.block.trailblaze.config.project

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.TrailblazeConfigYaml

/**
 * Locks down the author-friendly per-file shape for scripted pack tools and the JSON-Schema
 * translation that bridges it to the runtime [InlineScriptToolConfig].
 *
 * The translation is the load-bearing piece: authors write a flat `inputSchema` map; the
 * runtime expects a JSON-Schema-conformant `{ type: object, properties: {...}, required:
 * [...] }`. If this conversion regresses, MCP tool registration silently breaks.
 */
class PackScriptedToolFileTest {

  private val yaml: Yaml = TrailblazeConfigYaml.instance

  @Test
  fun `decodes minimal scripted tool with flat inputSchema and translates to JSON Schema`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      description: Does foo.
      inputSchema:
        bar:
          type: string
          description: A string param.
        count:
          type: integer
          description: An integer param.
    """.trimIndent()

    val parsed = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    assertEquals("./tools/foo.js", parsed.script)
    assertEquals("foo_tool", parsed.name)
    assertEquals("Does foo.", parsed.description)
    assertEquals(2, parsed.inputSchema.size)

    val inline = parsed.toInlineScriptToolConfig()
    val schema = inline.inputSchema
    assertEquals(JsonPrimitive("object"), schema["type"])
    val properties = schema["properties"] as JsonObject
    val bar = properties["bar"] as JsonObject
    assertEquals(JsonPrimitive("string"), bar["type"])
    assertEquals(JsonPrimitive("A string param."), bar["description"])
    val count = properties["count"] as JsonObject
    assertEquals(JsonPrimitive("integer"), count["type"])
    // Both params default to required: true, so both names appear in the required array
    // in declaration order (Map preserves insertion order through the YAML decoder).
    assertEquals(
      JsonArray(listOf(JsonPrimitive("bar"), JsonPrimitive("count"))),
      schema["required"],
    )
  }

  @Test
  fun `omits required array entirely when no properties are required`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        bar:
          type: string
          required: false
    """.trimIndent()

    val parsed = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    val schema = parsed.toInlineScriptToolConfig().inputSchema
    // Empty `required` is omitted (rather than serialized as an empty array) to keep the
    // generated JSON Schema minimal.
    assertNull(schema["required"])
  }

  @Test
  fun `enum values pass through to the property schema`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        category:
          type: string
          description: Category to pick.
          enum: [support, sales, feedback]
    """.trimIndent()

    val schema = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig().inputSchema
    val category = (schema["properties"] as JsonObject)["category"] as JsonObject
    assertEquals(
      JsonArray(
        listOf(
          JsonPrimitive("support"),
          JsonPrimitive("sales"),
          JsonPrimitive("feedback"),
        ),
      ),
      category["enum"],
    )
  }

  @Test
  fun `mixed required and optional emit only required names in the required array`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        a:
          type: string
        b:
          type: string
          required: false
        c:
          type: string
          required: true
    """.trimIndent()

    val schema = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig().inputSchema
    val required = schema["required"] as JsonArray
    val names = required.map { (it as JsonPrimitive).content }
    // a and c are required (a defaults to true; c is explicit), b is optional.
    assertTrue("a" in names && "c" in names && "b" !in names, "got: $names")
  }

  @Test
  fun `empty inputSchema produces a minimal but valid JSON Schema`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
    """.trimIndent()

    val schema = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig().inputSchema
    // No `required` array (would have been empty) and no `properties` entries.
    // Authors of zero-parameter tools shouldn't need to write anything but `type: object`.
    assertEquals(JsonPrimitive("object"), schema["type"])
    val properties = assertIs<JsonObject>(assertNotNull(schema["properties"]))
    assertTrue(properties.isEmpty(), "expected empty properties, got: $properties")
    // `required` is omitted (rather than emitted as []) so the generated schema stays minimal.
    assertEquals(null, schema["required"])
  }

  @Test
  fun `all-optional inputSchema omits the required array entirely`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        a:
          type: string
          required: false
        b:
          type: integer
          required: false
        c:
          type: string
          required: false
    """.trimIndent()

    val schema = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig().inputSchema
    val properties = schema["properties"] as JsonObject
    assertEquals(3, properties.size)
    // No required field should be emitted at all when none are required — JSON Schema
    // conformance demands `required` arrays be non-empty if present.
    assertEquals(null, schema["required"])
  }

  @Test
  fun `single mid-required property emits exactly that name in required order`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        first:
          type: string
          required: false
        second:
          type: string
        third:
          type: string
          required: false
    """.trimIndent()

    val schema = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig().inputSchema
    val required = assertIs<JsonArray>(assertNotNull(schema["required"]))
    assertEquals(1, required.size)
    assertEquals(JsonPrimitive("second"), required.single())
  }

  @Test
  fun `empty enum list is rejected at translation time`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      inputSchema:
        category:
          type: string
          enum: []
    """.trimIndent()

    val parsed = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    val ex = kotlin.runCatching { parsed.toInlineScriptToolConfig() }.exceptionOrNull()
    assertNotNull(ex, "expected toInlineScriptToolConfig to reject an empty enum list")
    assertIs<IllegalArgumentException>(ex)
    assertTrue(
      ex.message!!.contains("enum") && ex.message!!.contains("category"),
      "expected error to name the property and field; got: ${ex.message}",
    )
  }

  @Test
  fun `requiresHost shortcut translates through to the inline config`() {
    val source = """
      script: ./tools/host_only.js
      name: host_only_tool
      requiresHost: true
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertTrue(inline.requiresHost, "requiresHost flag should propagate verbatim")
  }

  @Test
  fun `requiresHost defaults to false when omitted`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
    """.trimIndent()

    val parsed = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    assertEquals(false, parsed.requiresHost)
    assertEquals(false, parsed.toInlineScriptToolConfig().requiresHost)
  }

  @Test
  fun `_meta passes through to the inline config verbatim`() {
    // Authors write `[web]` lowercase per the convention; the meta JsonObject preserves the
    // raw author value. Casing is normalized later in `TrailblazeToolMeta.fromJsonObject`,
    // which is exercised separately — this test only asserts the YAML->JsonObject pass-through.
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      _meta:
        trailblaze/supportedPlatforms: [web]
        trailblaze/requiresContext: true
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta)
    val platforms = meta["trailblaze/supportedPlatforms"] as JsonArray
    assertEquals(JsonPrimitive("web"), platforms.single())
    assertEquals(JsonPrimitive(true), meta["trailblaze/requiresContext"])
  }
}

package xyz.block.trailblaze.config.project

import com.charleskorn.kaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.InlineScriptToolConfig
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
  fun `supportedPlatforms top-level shortcut translates into the _meta namespaced key`() {
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      supportedPlatforms: [android, web]
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta)
    val platforms = meta["trailblaze/supportedPlatforms"] as JsonArray
    assertEquals(2, platforms.size)
    assertEquals(JsonPrimitive("android"), platforms[0])
    assertEquals(JsonPrimitive("web"), platforms[1])
  }

  @Test
  fun `top-level shortcut and explicit _meta merge with shortcut winning on conflict`() {
    // Pin the conflict-resolution rule documented on `mergeMetaShortcuts`: the top-level
    // shortcut wins. This protects against the common "I copy-pasted a stale `_meta:`
    // block from a different descriptor" bug — the explicit top-level
    // `supportedPlatforms: [android]` overrides the stale `_meta` value rather than silently
    // letting both coexist with the wrong one winning at random.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      supportedPlatforms: [android]
      _meta:
        trailblaze/supportedPlatforms: [web]
        trailblaze/customKey: "preserved"
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta)
    // Top-level shortcut wins on conflict — `supportedPlatforms: [android]` overrides the
    // `_meta: { trailblaze/supportedPlatforms: [web] }` stale value.
    val platforms = meta["trailblaze/supportedPlatforms"] as JsonArray
    assertEquals(JsonPrimitive("android"), platforms.single())
    // Non-conflicting `_meta` keys flow through unchanged.
    assertEquals(JsonPrimitive("preserved"), meta["trailblaze/customKey"])
  }

  @Test
  fun `supportedPlatforms empty list is treated as omitted (no _meta key emitted)`() {
    // Boundary: an explicit `supportedPlatforms: []` is observably indistinguishable from
    // omitting the field. Authors who write `[]` are unlikely to *mean* "empty platforms list"
    // — the only sensible reading is "I don't want to constrain platforms," which equals null.
    // Pin that semantics so a future refactor doesn't accidentally start emitting an empty
    // `trailblaze/supportedPlatforms: []` array (which downstream gating code would interpret
    // as "this tool runs on no platforms" — the worst possible failure mode).
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      supportedPlatforms: []
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertEquals(null, inline.meta, "empty supportedPlatforms list should produce null meta, not an empty array entry")
  }

  @Test
  fun `descriptor with no _meta and no shortcuts produces null meta on the inline config`() {
    // When neither `_meta:` nor any shortcut is set, the inline config's `meta` field
    // is `null` rather than an empty object — downstream code distinguishes these two
    // states in some paths.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertEquals(null, inline.meta)
  }

  @Test
  fun `descriptor with no inputSchema works (defaults to empty)`() {
    // Sam's PR feedback: authors shouldn't have to write `inputSchema: {}` for tools
    // that take no arguments. The Kotlin field already defaults to `emptyMap()`; this test
    // confirms YAML deserialization respects that default — omitting the field is valid.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      supportedPlatforms: [android]
    """.trimIndent()

    val parsed = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    assertEquals(emptyMap(), parsed.inputSchema)
    val inline = parsed.toInlineScriptToolConfig()
    // The translated JSON Schema is `{ type: object, properties: {} }` with no required array.
    assertEquals(JsonPrimitive("object"), inline.inputSchema["type"])
    assertEquals(0, (inline.inputSchema["properties"] as JsonObject).size)
    assertEquals(null, inline.inputSchema["required"])
  }

  @Test
  fun `_meta passes through to the inline config verbatim`() {
    // Authors write `[web]` lowercase per the convention; the meta JsonObject preserves the
    // raw author value. Casing is normalized later in `TrailblazeToolMeta.fromJsonObject`,
    // which is exercised separately — this test only asserts the YAML->JsonObject pass-through.
    // The `trailblaze/customKey` is an arbitrary author key without a top-level shortcut —
    // it represents the escape-hatch behavior of the `_meta:` block.
    val source = """
      script: ./tools/foo.js
      name: foo_tool
      _meta:
        trailblaze/supportedPlatforms: [web]
        trailblaze/customKey: "passes-through"
    """.trimIndent()

    val inline = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta)
    val platforms = meta["trailblaze/supportedPlatforms"] as JsonArray
    assertEquals(JsonPrimitive("web"), platforms.single())
    assertEquals(JsonPrimitive("passes-through"), meta["trailblaze/customKey"])
  }

  @Test
  fun `_meta with wrong type for trailblaze supportedPlatforms is rejected with descriptor-aware error`() {
    // Without parse-time validation, `_meta: { trailblaze/supportedPlatforms: "android" }` (string,
    // not list) silently flows through and gets overwritten only when a top-level shortcut conflicts.
    // We'd rather fail loudly with the tool name in the message — the runtime would otherwise
    // misbehave in surprising ways downstream when the gate code tries to iterate the value.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      _meta:
        trailblaze/supportedPlatforms: "android"
    """.trimIndent()

    val descriptor = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    val ex = assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
    val msg = assertNotNull(ex.message)
    assertTrue(msg.contains("foo_tool"), "expected tool name in: $msg")
    assertTrue(msg.contains("trailblaze/supportedPlatforms"), "expected key name in: $msg")
    assertTrue(msg.contains("list of strings"), "expected expected-type guidance in: $msg")
  }

  @Test
  fun `_meta with non-string element in trailblaze supportedPlatforms is rejected`() {
    // `[android, 42]` — second element is a number, not a string. The runtime gate code expects
    // strings, so a heterogeneous array would crash at the wrong layer.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      _meta:
        trailblaze/supportedPlatforms: [android, 42]
    """.trimIndent()

    val descriptor = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
  }

  @Test
  fun `_meta with wrong type for trailblaze requiresHost is rejected with descriptor-aware error`() {
    // Mirror of the supportedPlatforms wrong-type tests for the other validator branch:
    // `_meta: { trailblaze/requiresHost: 1 }` — a number, unambiguously non-boolean even
    // after YAML→JsonObject decoding (which can't be said for a quoted-string `"true"`,
    // since kaml normalizes that to a boolean primitive). Pins the validator branch so a
    // future refactor that loosens `isBooleanLiteral()` can't silently regress.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      _meta:
        trailblaze/requiresHost: 1
    """.trimIndent()

    val descriptor = yaml.decodeFromString(PackScriptedToolFile.serializer(), source)
    val ex = assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
    val msg = assertNotNull(ex.message)
    assertTrue(msg.contains("foo_tool"), "expected tool name in: $msg")
    assertTrue(msg.contains("trailblaze/requiresHost"), "expected key name in: $msg")
    assertTrue(msg.contains("boolean"), "expected expected-type guidance in: $msg")
  }

  /**
   * Pack-loader-facing error path: a malformed `name:` field surfaces an actionable error
   * pointing at the descriptor file *during YAML translation*, not deep inside the bundler
   * (which is too late — the author may have many tools and the bundler error doesn't name
   * the descriptor). The bundle-time check in `DaemonScriptedToolBundler` is defense-in-depth;
   * this one is the load-bearing user-facing failure mode.
   */
  @Test
  fun `toInlineScriptToolConfig rejects names that violate TOOL_NAME_PATTERN with descriptor-aware message`() {
    val invalidNames = listOf(
      "name with spaces",
      "name\"with\"quotes",
      "1starts-with-digit",
      "has\nnewline",
      "double--hyphen-still-fine_but-this-has-`backtick",
    )
    for (bad in invalidNames) {
      val descriptor = PackScriptedToolFile(script = "./tools/x.ts", name = bad)
      val ex = assertFailsWith<IllegalArgumentException>("expected '$bad' to be rejected") {
        descriptor.toInlineScriptToolConfig()
      }
      val msg = assertNotNull(ex.message, "expected an error message")
      assertTrue(msg.contains("Invalid scripted-tool name"), "missing prefix in: $msg")
      assertTrue(msg.contains(bad), "missing offending name '$bad' in: $msg")
      assertTrue(msg.contains("./tools/x.ts"), "missing script path in: $msg")
    }
  }

  /**
   * The init block on `InlineScriptToolConfig` itself is the canonical enforcement site —
   * any future construction path that bypasses [PackScriptedToolFile.toInlineScriptToolConfig]
   * (test fixtures, programmatic construction, direct `target.tools:` YAML decode, etc.)
   * still has to match the regex. This test verifies the data class enforces it directly.
   */
  @Test
  fun `InlineScriptToolConfig constructor rejects invalid names regardless of source path`() {
    assertFailsWith<IllegalArgumentException> {
      InlineScriptToolConfig(script = "./x.ts", name = "bad name")
    }
    // Sanity check: the well-formed name still constructs cleanly.
    val ok = InlineScriptToolConfig(script = "./x.ts", name = "clock_android-launchApp.v2")
    assertEquals("clock_android-launchApp.v2", ok.name)
  }
}

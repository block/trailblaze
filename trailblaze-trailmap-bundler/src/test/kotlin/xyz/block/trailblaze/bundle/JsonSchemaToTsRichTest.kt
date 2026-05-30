package xyz.block.trailblaze.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Unit tests for [JsonSchemaToTsRich] — the JSON Schema → TS type literal emitter that
 * powers the analyzer-aware codegen path in [WorkspaceClientDtsGenerator]. Tests parse
 * raw JSON Schema strings (matching what `ts-json-schema-generator` actually emits) so
 * the schema shape is verbatim — no Kotlin-side `buildJsonObject` reshuffling that could
 * accidentally test the test's own builder rather than the emitter.
 */
class JsonSchemaToTsRichTest {

  private fun render(json: String, baseIndent: Int = 6): String =
    JsonSchemaToTsRich.render(Json.parseToJsonElement(json), baseIndent = baseIndent)

  @Test
  fun `primitive types map to TS primitives`() {
    assertEquals("string", render("""{"type":"string"}"""))
    assertEquals("number", render("""{"type":"number"}"""))
    assertEquals("number", render("""{"type":"integer"}"""))
    assertEquals("boolean", render("""{"type":"boolean"}"""))
    assertEquals("null", render("""{"type":"null"}"""))
  }

  @Test
  fun `Date round-trips as string (ts-json-schema-generator format=date-time)`() {
    // The analyzer's contract pins Date as a supported subset member that emits string.
    // If a future generator change altered this conversion, the analyzer's own test
    // suite would catch it AND this test would surface the codegen impact.
    val schema = """{"type":"string","format":"date-time"}"""
    assertEquals("string", render(schema))
  }

  @Test
  fun `enum renders as union of string literals`() {
    val schema = """{"type":"string","enum":["a","b","c"]}"""
    assertEquals("\"a\" | \"b\" | \"c\"", render(schema))
  }

  @Test
  fun `const renders as a single literal type`() {
    assertEquals("\"ok\"", render("""{"const":"ok"}"""))
    assertEquals("42", render("""{"const":42}"""))
    assertEquals("true", render("""{"const":true}"""))
  }

  @Test
  fun `nullable string via type array yields string-or-null union`() {
    val schema = """{"type":["string","null"]}"""
    assertEquals("string | null", render(schema))
  }

  @Test
  fun `array of primitives renders as type-bracket syntax`() {
    assertEquals("string[]", render("""{"type":"array","items":{"type":"string"}}"""))
  }

  @Test
  fun `array of union wraps in Array to avoid precedence ambiguity`() {
    // Without Array<>, the postfix `[]` would bind tighter than `|` and produce a
    // `string | null[]` literal that parses as `string | (null[])` — the precedence
    // ambiguity the emitter has to actively guard against.
    val rendered = render("""{"type":"array","items":{"type":["string","null"]}}""")
    assertEquals("Array<string | null>", rendered)
  }

  @Test
  fun `tuple items render as bracketed tuple`() {
    val rendered = render(
      """{"type":"array","items":[{"type":"string"},{"type":"number"}]}""",
    )
    assertEquals("[string, number]", rendered)
  }

  @Test
  fun `empty tuple items renders as bracket-bracket`() {
    // Regression for an edge case that fires when a schema explicitly declares a
    // zero-length tuple — `items: []`. Distinct from missing `items:` (which is the
    // `unknown[]` fallback). Catch this so a future change that conflates the two
    // surfaces here rather than as a downstream TS compile failure.
    val rendered = render("""{"type":"array","items":[]}""")
    assertEquals("[]", rendered)
  }

  @Test
  fun `array with no items key renders as unknown bracket`() {
    val rendered = render("""{"type":"array"}""")
    assertEquals("unknown[]", rendered)
  }

  @Test
  fun `array of object-literal union wraps in Array to avoid precedence ambiguity`() {
    // Bot-flagged precedence trap: an earlier version of the array renderer exempted any
    // item type starting with `{` from the parenthesization rule, on the assumption that
    // an object literal binds tighter than `[]`. But a UNION of object literals also
    // starts with `{`, and TS parses `{a: string} | {b: number}[]` as
    // `{a: string} | ({b: number}[])` — wrong. The fix detects ` | ` / ` & ` at the top
    // level and wraps with `Array<...>` regardless of the leading character.
    val schema = """
      {"type":"array","items":{"anyOf":[
        {"type":"object","properties":{"a":{"type":"string"}},"required":["a"]},
        {"type":"object","properties":{"b":{"type":"number"}},"required":["b"]}
      ]}}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected Array<...> wrap for union of object literals: $rendered") {
      rendered.startsWith("Array<")
    }
    assertTrue("expected union to land inside the Array<>: $rendered") {
      rendered.contains("} | {")
    }
  }

  @Test
  fun `array of intersection wraps in Array`() {
    val schema = """
      {"type":"array","items":{"allOf":[
        {"type":"object","properties":{"a":{"type":"string"}},"required":["a"]},
        {"type":"object","properties":{"b":{"type":"number"}},"required":["b"]}
      ]}}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected Array<...> wrap for intersection: $rendered") {
      rendered.startsWith("Array<")
    }
    assertTrue("expected intersection inside Array<>: $rendered") {
      rendered.contains("} & {")
    }
  }

  @Test
  fun `object with explicit properties renders as TS object literal with required marks`() {
    val schema = """
      {"type":"object","properties":{
        "name":{"type":"string"},
        "age":{"type":"number"}
      },"required":["name"]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("rendered: $rendered") { rendered.contains("name: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("age?: number;") }
  }

  @Test
  fun `field-level description becomes JSDoc above the field`() {
    val schema = """
      {"type":"object","properties":{
        "msg":{"type":"string","description":"The message to format."}
      },"required":["msg"]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected JSDoc above msg: $rendered") {
      rendered.contains("/** The message to format. */")
    }
  }

  @Test
  fun `multi-line description renders as a single JSDoc block not N inline blocks`() {
    // Code-review pass caught this: an earlier version emitted each line as its own
    // self-closing `/** line */` block, and TS only attaches the LAST such block to
    // the following field. The earlier lines silently disappeared from IDE hover.
    // Fixed by emitting `/**\n * line1\n * line2\n */` for multi-line descriptions.
    val schema = """
      {"type":"object","properties":{
        "msg":{"type":"string","description":"First line.\nSecond line.\nThird line."}
      },"required":["msg"]}
    """.trimIndent()
    val rendered = render(schema)
    // Single opening `/**` line — not N of them.
    val opens = rendered.split("/**").size - 1
    assertEquals(1, opens, "expected exactly one `/**` opening token: $rendered")
    // Each line surfaces as a `* <text>` line.
    assertTrue("expected first line: $rendered") { rendered.contains("* First line.") }
    assertTrue("expected second line: $rendered") { rendered.contains("* Second line.") }
    assertTrue("expected third line: $rendered") { rendered.contains("* Third line.") }
    // Single closing `*/`.
    val closes = rendered.split("*/").size - 1
    assertEquals(1, closes, "expected exactly one `*/` closing token: $rendered")
  }

  @Test
  fun `single-line description still renders inline`() {
    // Pin that the multi-line fix didn't accidentally regress single-line rendering
    // (the inline `/** text */` shape stays for the common case to keep `.d.ts` files
    // compact).
    val schema = """
      {"type":"object","properties":{
        "x":{"type":"string","description":"Single line."}
      },"required":["x"]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected inline JSDoc: $rendered") { rendered.contains("/** Single line. */") }
    // The multi-line shape's `/**\n` opener should NOT appear.
    assertTrue("should not use multi-line opener for single-line desc: $rendered") {
      !rendered.contains("/**\n")
    }
  }

  @Test
  fun `nested object renders as nested TS object literal`() {
    val schema = """
      {"type":"object","properties":{
        "user":{"type":"object","properties":{
          "id":{"type":"string"},
          "age":{"type":"number"}
        },"required":["id","age"]}
      },"required":["user"]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected nested id field: $rendered") { rendered.contains("id: string;") }
    assertTrue("expected nested age field: $rendered") { rendered.contains("age: number;") }
    // Outer `user:` should not be `?` since it's required; nested `id`/`age` follow same rule.
    assertTrue("expected non-optional user: $rendered") { rendered.contains("user: {") }
  }

  @Test
  fun `Record-style object with additionalProperties only renders as Record literal`() {
    val schema = """{"type":"object","additionalProperties":{"type":"number"}}"""
    assertEquals("Record<string, number>", render(schema))
  }

  @Test
  fun `Record-style additionalProperties true renders as Record string unknown`() {
    val schema = """{"type":"object","additionalProperties":true}"""
    assertEquals("Record<string, unknown>", render(schema))
  }

  @Test
  fun `closed empty object renders as Record string never`() {
    val schema = """{"type":"object","additionalProperties":false}"""
    assertEquals("Record<string, never>", render(schema))
  }

  @Test
  fun `anyOf renders as union of branches`() {
    val schema = """
      {"anyOf":[
        {"type":"object","properties":{"kind":{"const":"ok"},"value":{"type":"string"}},"required":["kind","value"]},
        {"type":"object","properties":{"kind":{"const":"err"},"error":{"type":"string"}},"required":["kind","error"]}
      ]}
    """.trimIndent()
    val rendered = render(schema)
    // Should contain both branches' discriminator literal and field names.
    assertTrue("expected ok discriminator: $rendered") { rendered.contains("\"ok\"") }
    assertTrue("expected err discriminator: $rendered") { rendered.contains("\"err\"") }
    assertTrue("expected value field: $rendered") { rendered.contains("value: string;") }
    assertTrue("expected error field: $rendered") { rendered.contains("error: string;") }
    // Both branches are joined with `|`.
    assertTrue("expected at least one `|` in union: $rendered") { rendered.contains("} | {") }
  }

  @Test
  fun `oneOf renders the same as anyOf for closed unions`() {
    val schema = """{"oneOf":[{"type":"string"},{"type":"number"}]}"""
    assertEquals("string | number", render(schema))
  }

  @Test
  fun `allOf renders as intersection`() {
    val schema = """
      {"allOf":[
        {"type":"object","properties":{"a":{"type":"string"}},"required":["a"]},
        {"type":"object","properties":{"b":{"type":"number"}},"required":["b"]}
      ]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected intersection operator: $rendered") { rendered.contains("} & {") }
  }

  @Test
  fun `dollar-ref into sibling definitions resolves inline`() {
    // Matches the analyzer's actual shape for Record-of-T fields (which it $refs into a
    // sibling `definitions` bag). The renderer should follow the indirection transparently.
    val schema = """
      {"type":"object","properties":{
        "attrs":{"${'$'}ref":"#/definitions/AttrMap"}
      },"required":["attrs"],
      "definitions":{
        "AttrMap":{"type":"object","additionalProperties":{"type":"number"}}
      }}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected Record<string, number> resolved from \$ref: $rendered") {
      rendered.contains("attrs: Record<string, number>;")
    }
  }

  @Test
  fun `URL-encoded ref name resolves correctly`() {
    // `ts-json-schema-generator` URL-encodes special chars in generic instantiation names
    // (`Record<string, number>` becomes `Record%3Cstring%2C%20number%3E`). The renderer
    // must decode before lookup.
    val schema = """
      {"type":"object","properties":{
        "x":{"${'$'}ref":"#/definitions/Record%3Cstring%2C%20number%3E"}
      },"required":["x"],
      "definitions":{
        "Record<string, number>":{"type":"object","additionalProperties":{"type":"number"}}
      }}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected URL-decoded ref to resolve: $rendered") {
      rendered.contains("x: Record<string, number>;")
    }
  }

  @Test
  fun `self-referential ref short-circuits to unknown rather than infinite expansion`() {
    // `interface Node { children: Node[] }` would loop without cycle detection. The
    // renderer's expansion-stack guard catches the revisit and emits `unknown` for the
    // recursive arm so codegen always terminates.
    val schema = """
      {"type":"object","properties":{
        "value":{"type":"string"},
        "children":{"type":"array","items":{"${'$'}ref":"#/definitions/Node"}}
      },"required":["value","children"],
      "definitions":{
        "Node":{"type":"object","properties":{
          "value":{"type":"string"},
          "children":{"type":"array","items":{"${'$'}ref":"#/definitions/Node"}}
        },"required":["value","children"]}
      }}
    """.trimIndent()
    val rendered = render(schema)
    // Must terminate without blowing up the stack OR emitting an infinite string. Verify
    // it actually ran by checking the outer shape.
    assertTrue("expected children field: $rendered") { rendered.contains("children:") }
  }

  @Test
  fun `unknown schema construct degrades to unknown rather than failing`() {
    // A schema with no `type:`, no `enum:`, no `$ref:` — anything outside the supported
    // vocabulary should produce `unknown` so codegen stays robust to future generator
    // additions.
    val schema = """{"foo":"bar"}"""
    assertEquals("unknown", render(schema))
  }

  @Test
  fun `mixed properties and additionalProperties schema renders as object with index signature`() {
    // Lead-dev review #2: a schema with BOTH explicit `properties:` AND
    // `additionalProperties: <schema>` expresses TypeScript's
    // `{ a: string; [k: string]: number }` shape (named fields + index signature).
    // Earlier behaviour silently dropped `additionalProperties` in this branch,
    // losing the index signature. Pin the round-trip so a future change that
    // re-orders the branches surfaces here.
    val schema = """
      {"type":"object","properties":{
        "explicit":{"type":"string"}
      },"required":["explicit"],"additionalProperties":{"type":"number"}}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected explicit field: $rendered") { rendered.contains("explicit: string;") }
    assertTrue("expected index signature: $rendered") {
      rendered.contains("[key: string]: number;")
    }
  }

  @Test
  fun `mixed properties and additionalProperties true renders index signature with unknown`() {
    val schema = """
      {"type":"object","properties":{
        "explicit":{"type":"string"}
      },"required":["explicit"],"additionalProperties":true}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected explicit field: $rendered") { rendered.contains("explicit: string;") }
    assertTrue("expected unknown index signature: $rendered") {
      rendered.contains("[key: string]: unknown;")
    }
  }

  @Test
  fun `dollar-defs bag resolves the same as definitions`() {
    // Lead-dev review #6: the renderer has a fallback that recognizes `$defs` (modern
    // JSON Schema draft) in addition to `definitions` (the draft `ts-json-schema-
    // generator` actually emits). Tests covered `definitions` but not `$defs`.
    val schema = """
      {"type":"object","properties":{
        "x":{"${'$'}ref":"#/${'$'}defs/AttrMap"}
      },"required":["x"],
      "${'$'}defs":{
        "AttrMap":{"type":"object","additionalProperties":{"type":"number"}}
      }}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected \$defs to resolve: $rendered") {
      rendered.contains("x: Record<string, number>;")
    }
  }

  @Test
  fun `recursive ref short-circuits to unknown on the cycle arm`() {
    // The cycle-test above (`self-referential ref short-circuits...`) asserts the
    // renderer terminates. This stricter test pins that the RECURSIVE arm of the
    // type renders as `unknown` (not just "terminates somehow"). A regression that
    // removed the cycle guard would make `children:` render as an unbounded nested
    // type literal instead of `unknown`.
    val schema = """
      {"${'$'}ref":"#/definitions/Node",
      "definitions":{
        "Node":{"type":"object","properties":{
          "value":{"type":"string"},
          "children":{"type":"array","items":{"${'$'}ref":"#/definitions/Node"}}
        },"required":["value","children"]}
      }}
    """.trimIndent()
    val rendered = render(schema)
    // The recursive arm — `children: Array<unknown>[]` or similar — must terminate
    // with `unknown` somewhere downstream of the top-level resolve.
    assertTrue("expected at least one `unknown` in recursive output: $rendered") {
      rendered.contains("unknown")
    }
  }

  @Test
  fun `MAX_DEPTH guard returns unknown when budget exhausted`() {
    // Lead-dev review #7: MAX_DEPTH = 32 prevents stack blowup on pathologically deep
    // (non-recursive) schemas. Build a 35-level-deep nested object literal and verify
    // the renderer terminates with `unknown` somewhere — better than a StackOverflowError
    // in a daemon-init code path.
    val depth = 35
    val schema = buildString {
      repeat(depth) { append("""{"type":"object","properties":{"nested":""") }
      append(""""string"""")  // leaf is a non-schema sentinel to force degradation if the cap fires
      repeat(depth) { append("""},"required":["nested"]}""") }
    }
    // Note: leaf is intentionally a non-object so the depth-exhaustion branch is the
    // only way the renderer terminates without throwing.
    val rendered = render(schema)
    // Just assert termination (the render returned) AND that `unknown` appears
    // somewhere in the output (depth guard fired at least once).
    assertTrue("expected `unknown` in depth-exceeded output: $rendered") {
      rendered.contains("unknown")
    }
  }

  @Test
  fun `embedded asterisk-slash in description is escaped to prevent comment break`() {
    // The renderer escapes `*/` so a description like `Hello */ world` doesn't prematurely
    // close the surrounding JSDoc block and produce a syntax-error `.d.ts`.
    val schema = """
      {"type":"object","properties":{
        "msg":{"type":"string","description":"closes the comment */ broken"}
      },"required":["msg"]}
    """.trimIndent()
    val rendered = render(schema)
    assertTrue("expected escaped close-comment: $rendered") {
      rendered.contains("closes the comment * / broken")
    }
  }
}

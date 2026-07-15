package xyz.block.trailblaze.yaml

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.DefaultBehavior
import xyz.block.trailblaze.logs.client.TrailblazeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parse-level contract tests for the `config.args:` block ([TrailArgConfig] +
 * [TrailArgConfigSerializer]). Pins the declaration grammar's observable behavior: the compact
 * shorthand, the full form, presence-aware required/optional (Terraform's rule), and the parse-time
 * rejections that keep an ambiguous declaration from ever reaching the runtime.
 */
class TrailArgConfigParseTest {

  private val trailblazeYaml = createTrailblazeYaml()

  /** [argEntries] is the relative YAML under `args:` (its own indentation is normalized here). */
  private fun parseArgs(argEntries: String): Map<String, TrailArgConfig> {
    val entries = argEntries.trimIndent().prependIndent("      ")
    val doc = "- config:\n" +
      "    title: Parameterized trail\n" +
      "    platform: android\n" +
      "    args:\n" +
      entries + "\n" +
      "- tools:\n" +
      "  - pressBack: {}\n"
    return trailblazeYaml.extractTrailConfig(doc)?.args ?: error("expected args to parse")
  }

  @Test
  fun `trail without args parses with a null args map`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Plain trail
          platform: android
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )
    assertNull(parsed?.args, "args must be null when not declared")
  }

  @Test
  fun `compact shorthand declares a required string arg`() {
    val args = parseArgs("recipient: string")
    val recipient = args.getValue("recipient")
    assertEquals(TrailArgConfig.STRING, recipient.type)
    assertTrue(recipient.required, "no default => required")
  }

  @Test
  fun `full form with type and description parses`() {
    val args = parseArgs(
      """
      subject:
        type: string
        description: "Email subject line"
      """,
    )
    val subject = args.getValue("subject")
    assertEquals(TrailArgConfig.STRING, subject.type)
    assertEquals("Email subject line", subject.description)
    assertTrue(subject.required)
  }

  @Test
  fun `a default makes an arg optional`() {
    val args = parseArgs(
      """
      retries:
        type: integer
        default: 3
      """,
    )
    val retries = args.getValue("retries")
    assertTrue(!retries.required, "a default => optional")
    val default = retries.default
    assertTrue(default is DefaultBehavior.Use)
    assertEquals("3", default.value.jsonPrimitive.content)
  }

  @Test
  fun `a string default preserves a numeric-looking scalar as text`() {
    val args = parseArgs(
      """
      code:
        type: string
        default: "007"
      """,
    )
    val default = args.getValue("code").default
    assertTrue(default is DefaultBehavior.Use)
    assertEquals("007", default.value.jsonPrimitive.content)
  }

  @Test
  fun `a token-valued default round-trips as its token text`() {
    val args = parseArgs(
      """
      email:
        type: string
        default: "{{memory.email}}"
      """,
    )
    val default = args.getValue("email").default
    assertTrue(default is DefaultBehavior.Use)
    assertEquals("{{memory.email}}", default.value.jsonPrimitive.content)
  }

  @Test
  fun `an invalid type is rejected at parse`() {
    val error = assertFailsWith<Exception> {
      parseArgs(
        """
        x:
          type: number
        """,
      )
    }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("number"), error.toString())
  }

  @Test
  fun `required true alongside a default is rejected`() {
    val error = assertFailsWith<Exception> {
      parseArgs(
        """
        x:
          type: string
          required: true
          default: hi
        """,
      )
    }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("required", ignoreCase = true), error.toString())
  }

  @Test
  fun `required false without a default is rejected`() {
    val error = assertFailsWith<Exception> {
      parseArgs(
        """
        x:
          type: string
          required: false
        """,
      )
    }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("optional", ignoreCase = true) || msg.contains("default", ignoreCase = true), error.toString())
  }

  @Test
  fun `a declared default of null is rejected at parse with the no-null wording`() {
    // A PROVIDED null is caught loudly at bind; a DECLARED `default: null` must not slip past
    // into an "optional" arg that only fails later with a generic coercion error.
    val error = assertFailsWith<Exception> {
      parseArgs(
        """
        x:
          type: string
          default: null
        """,
      )
    }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("null", ignoreCase = true), error.toString())
    assertTrue(msg.contains("required", ignoreCase = true) || msg.contains("''"), error.toString())
  }

  @Test
  fun `an arg name outside the token identifier grammar is rejected at parse`() {
    // `{{args.user-id}}` can never parse as a token, so the declaration is unreferenceable —
    // reject it where the user can fix it, naming the offending arg.
    val error = assertFailsWith<Exception> { parseArgs("user-id: string") }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("user-id"), error.toString())
  }

  @Test
  fun `a dotted arg name is rejected at parse - it collides with dotted-path field access`() {
    val error = assertFailsWith<Exception> {
      parseArgs(
        """
        "a.b": string
        """,
      )
    }
    val msg = error.message.orEmpty() + error.cause?.message.orEmpty()
    assertTrue(msg.contains("a.b"), error.toString())
  }

  @Test
  fun `underscored and digit-bearing arg names are legal`() {
    val args = parseArgs(
      """
      _reply_to2: string
      """,
    )
    assertEquals(TrailArgConfig.STRING, args.getValue("_reply_to2").type)
  }

  @Test
  fun `array and object types parse (execution deferred)`() {
    val args = parseArgs(
      """
      items:
        type: array
      opts:
        type: object
      """,
    )
    assertEquals(TrailArgConfig.ARRAY, args.getValue("items").type)
    assertEquals(TrailArgConfig.OBJECT, args.getValue("opts").type)
  }

  @Test
  fun `an array default survives an encode-decode round trip`() {
    // Regression pin: TrailArgConfigSerializer.serialize() once only re-emitted a JsonPrimitive
    // default, silently dropping an array/object default on any re-serialize (migrate-trails,
    // recording writers, bundle migration all re-encode a trail's config).
    val original = TrailArgConfig(
      type = TrailArgConfig.ARRAY,
      default = DefaultBehavior.Use(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }),
    )

    val encoded = TrailblazeYaml.defaultYamlInstance.encodeToString(TrailArgConfig.serializer(), original)
    val decoded = TrailblazeYaml.defaultYamlInstance.decodeFromString(TrailArgConfig.serializer(), encoded)

    assertEquals(original, decoded, "an array default must not be silently dropped on re-serialize")
  }

  @Test
  fun `an object default survives an encode-decode round trip`() {
    val original = TrailArgConfig(
      type = TrailArgConfig.OBJECT,
      default = DefaultBehavior.Use(buildJsonObject { put("verbose", true) }),
    )

    val encoded = TrailblazeYaml.defaultYamlInstance.encodeToString(TrailArgConfig.serializer(), original)
    val decoded = TrailblazeYaml.defaultYamlInstance.decodeFromString(TrailArgConfig.serializer(), encoded)

    assertEquals(original, decoded, "an object default must not be silently dropped on re-serialize")
  }

  @Test
  fun `an array default is emitted intact by the JsonEncoder branch`() {
    // The serializer has a distinct JsonEncoder branch (a faithful JSON object) alongside the kaml
    // bridge the YAML round-trips above exercise. The pin here is on the emitted JSON: the
    // structured default must land verbatim, not be dropped or stringified. (The JSON round-trip
    // itself — decode included — is covered by the log-transport tests.)
    val default = buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
    val original = TrailArgConfig(
      type = TrailArgConfig.ARRAY,
      default = DefaultBehavior.Use(default),
    )

    val json = kotlinx.serialization.json.Json
    val encoded = json.parseToJsonElement(json.encodeToString(TrailArgConfig.serializer(), original))

    assertEquals(default, encoded.jsonObject.getValue("default"), "the array default must be emitted verbatim: $encoded")
  }

  @Test
  fun `a parameterized trail config survives the JSON log-transport round trip`() {
    // Regression pin for the `/agentlog` decode crash: a trail's `config.args` block is embedded in
    // TrailblazeLog → SessionStatus.Started → TrailConfig, and the log wire is JSON. The args
    // serializers used to hard-require a YAML decoder ("config.args can only be deserialized from
    // YAML"), so every parameterized trail's log 500'd on decode. Parse a full args block from YAML
    // (the authoring form) and assert it survives a JSON encode→decode intact.
    val config = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Parameterized trail
          platform: android
          args:
            recipient: string
            subject:
              type: string
              description: "Email subject line"
            retries:
              type: integer
              default: 3
            verbose:
              type: boolean
              default: false
            code:
              type: string
              default: "007"
            items:
              type: array
              default: [a, b]
            opts:
              type: object
              default: { verbose: true }
      - tools:
        - pressBack: {}
      """.trimIndent(),
    ) ?: error("expected the parameterized trail config to parse")

    val json = TrailblazeJson.defaultWithoutToolsInstance
    val decoded = json.decodeFromString(
      TrailConfig.serializer(),
      json.encodeToString(TrailConfig.serializer(), config),
    )

    assertEquals(config, decoded, "config.args must survive a JSON encode/decode round trip")
  }

  @Test
  fun `a null default in raw JSON is rejected on decode, matching the YAML no-null rule`() {
    // The JSON decode path reads arbitrary historical log files, not just our own serialize()
    // output (which never emits a null default) — a corrupted/hand-edited payload must fail loudly
    // here too, mirroring the YAML branch's "no null in the args domain" guard.
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(
        TrailArgConfig.serializer(),
        """{"type": "string", "default": null}""",
      )
    }
    assertTrue(error.message.orEmpty().contains("null", ignoreCase = true), error.toString())
  }

  @Test
  fun `a non-object arg entry in raw JSON is rejected with a clear error`() {
    // JSON has no scalar shorthand (that's a YAML authoring convenience) — a bare scalar arg value
    // must fail loudly rather than silently do something unexpected.
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(TrailArgConfig.serializer(), "\"string\"")
    }
    assertTrue(error.message.orEmpty().contains("object", ignoreCase = true), error.toString())
  }

  @Test
  fun `an invalid type in raw JSON is rejected at decode`() {
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(
        TrailArgConfig.serializer(),
        """{"type": "number"}""",
      )
    }
    assertTrue(error.message.orEmpty().contains("number"), error.toString())
  }

  @Test
  fun `a non-object entry in the raw JSON args map is rejected with a clear error`() {
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(TrailArgMapSerializer, """{"recipient": "string"}""")
    }
    assertTrue(error.message.orEmpty().contains("object", ignoreCase = true), error.toString())
  }

  @Test
  fun `an empty JSON args map decodes to an empty map`() {
    val json = kotlinx.serialization.json.Json
    val decoded = json.decodeFromString(TrailArgMapSerializer, "{}")
    assertTrue(decoded.isEmpty(), decoded.toString())
  }

  @Test
  fun `a type field absent in raw JSON defaults to string`() {
    val json = kotlinx.serialization.json.Json
    val decoded = json.decodeFromString(TrailArgConfig.serializer(), """{"description": "x"}""")
    assertEquals(TrailArgConfig.STRING, decoded.type)
  }

  @Test
  fun `a non-scalar type value in raw JSON is rejected, not silently defaulted to string`() {
    // Regression pin: a malformed (present-but-wrong-shape) `type` must fail loudly rather than
    // be treated the same as an ABSENT `type` and silently defaulted to STRING.
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(TrailArgConfig.serializer(), """{"type": {"nested": 1}}""")
    }
    assertTrue(error.message.orEmpty().contains("scalar", ignoreCase = true), error.toString())
  }

  @Test
  fun `a null type value in raw JSON is rejected, not silently defaulted to string`() {
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(TrailArgConfig.serializer(), """{"type": null}""")
    }
    assertTrue(error.message.orEmpty().contains("scalar", ignoreCase = true), error.toString())
  }

  @Test
  fun `a decode failure in the raw JSON args map names the offending arg`() {
    val json = kotlinx.serialization.json.Json
    val error = assertFailsWith<Exception> {
      json.decodeFromString(TrailArgMapSerializer, """{"subject": {"type": "number"}}""")
    }
    assertTrue(error.message.orEmpty().contains("subject"), error.toString())
  }
}

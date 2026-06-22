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
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.config.TrailblazeConfigYaml

/**
 * Locks down the author-friendly per-file shape for scripted trailmap tools and the JSON-Schema
 * translation that bridges it to the runtime [InlineScriptToolConfig].
 *
 * The translation is the load-bearing piece: authors write a flat `inputSchema` map; the
 * runtime expects a JSON-Schema-conformant `{ type: object, properties: {...}, required:
 * [...] }`. If this conversion regresses, MCP tool registration silently breaks.
 */
class TrailmapScriptedToolFileTest {

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

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val schema = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val schema = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val schema = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val schema = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val schema = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertTrue(inline.requiresHost, "requiresHost flag should propagate verbatim")
  }

  @Test
  fun `requiresHost defaults to false when omitted`() {
    val source = """
      script: ./tools/foo.js
      name: foo_tool
    """.trimIndent()

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
    assertEquals(false, parsed.requiresHost)
    assertEquals(false, parsed.toInlineScriptToolConfig().requiresHost)
  }

  @Test
  fun `requiresHost top-level shortcut translates into the _meta namespaced key`() {
    // The on-device QuickJS bundler reads `_meta["trailblaze/requiresHost"]` to decide
    // whether to skip a tool during on-device registration. The top-level
    // `requiresHost: true` shortcut must fold into the meta object so that gate fires —
    // otherwise the host dispatcher routes the tool to on-device via `executeToolViaRpc`,
    // the on-device side can't find the workspace .ts source, and the user sees
    // `Unknown tool 'X' is not registered`. The typed `InlineScriptToolConfig.requiresHost`
    // field already propagates, but only the meta path reaches the dispatch gate.
    val source = """
      script: ./tools/host_only.js
      name: host_only_tool
      requiresHost: true
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta, "requiresHost: true must produce a non-null _meta")
    assertEquals(
      JsonPrimitive(true),
      meta["trailblaze/requiresHost"],
      "Top-level `requiresHost: true` must fold into `_meta.trailblaze/requiresHost`",
    )
  }

  @Test
  fun `requiresHost false default does not emit the _meta key`() {
    // The schema default for `requiresHost` is `false`. Folding `false` into _meta would
    // change the wire shape for the overwhelmingly-common case (descriptor without an
    // explicit requiresHost), and some downstream consumers distinguish "key absent" from
    // "key explicitly false". Pin "no key emitted on default" so that subtle change doesn't
    // sneak in.
    val source = """
      script: ./tools/foo.js
      name: foo_tool
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    // With no shortcuts and no explicit _meta, the merged meta should be null entirely.
    assertEquals(null, inline.meta)
  }

  @Test
  fun `requiresHost top-level shortcut wins over stale explicit _meta value`() {
    // Same conflict-resolution rule as supportedPlatforms — copy-pasted stale `_meta:`
    // blocks shouldn't silently override the top-level shortcut. Pin the rule.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      requiresHost: true
      _meta:
        trailblaze/requiresHost: false
        trailblaze/customKey: "preserved"
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    val meta = assertNotNull(inline.meta)
    assertEquals(
      JsonPrimitive(true),
      meta["trailblaze/requiresHost"],
      "Top-level requiresHost: true must override _meta.trailblaze/requiresHost: false",
    )
    assertEquals(
      JsonPrimitive("preserved"),
      meta["trailblaze/customKey"],
      "Unrelated _meta keys must flow through unchanged",
    )
  }

  @Test
  fun `supportedPlatforms top-level shortcut translates into the _meta namespaced key`() {
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      supportedPlatforms: [android, web]
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val descriptor = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val descriptor = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
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

    val descriptor = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
    val ex = assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
    val msg = assertNotNull(ex.message)
    assertTrue(msg.contains("foo_tool"), "expected tool name in: $msg")
    assertTrue(msg.contains("trailblaze/requiresHost"), "expected key name in: $msg")
    assertTrue(msg.contains("boolean"), "expected expected-type guidance in: $msg")
  }

  /**
   * Trailmap-loader-facing error path: a malformed `name:` field surfaces an actionable error
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
      val descriptor = TrailmapScriptedToolFile(script = "./tools/x.ts", name = bad)
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
   * any future construction path that bypasses [TrailmapScriptedToolFile.toInlineScriptToolConfig]
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

  /**
   * The new `runtime:` override is the load-bearing escape hatch that lets a `.ts` author
   * opt into the bun/tsx subprocess path where `node:fs` is available. If decoding regresses
   * (e.g. wrong `@SerialName` casing), the partition in `TrailblazeHostYamlRunner` silently
   * falls back to extension-based routing and the tool runs under QuickJS where it crashes
   * with "fs is not defined" the moment the handler touches the filesystem. Pin both
   * variants + the default-null case.
   */
  @Test
  fun `runtime field decodes both variants and propagates to InlineScriptToolConfig`() {
    val subprocess = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: foo_tool
        runtime: subprocess
      """.trimIndent(),
    )
    assertEquals(ScriptedToolRuntime.SUBPROCESS, subprocess.runtime)
    assertEquals(ScriptedToolRuntime.SUBPROCESS, subprocess.toInlineScriptToolConfig().runtime)

    val inProcess = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.js
        name: foo_tool
        runtime: inProcess
      """.trimIndent(),
    )
    assertEquals(ScriptedToolRuntime.IN_PROCESS, inProcess.runtime)
    assertEquals(ScriptedToolRuntime.IN_PROCESS, inProcess.toInlineScriptToolConfig().runtime)

    val default = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: foo_tool
      """.trimIndent(),
    )
    assertNull(default.runtime, "runtime: should default to null so extension routing applies")
    assertNull(default.toInlineScriptToolConfig().runtime)
  }

  // --------------------------------------------------------------------------------------------
  // Multi-tool shape — one descriptor with `tools: [...]` declaring N tools, all sharing the
  // same author script. From a developer's perspective these are TRAILBLAZE SCRIPTED TOOLS, NOT
  // an MCP server — the framework's wrapper synthesis hides the MCP framing entirely. These
  // tests pin the load-time fan-out (`toInlineScriptToolConfigs`) that the production loader
  // uses to turn one multi-tool descriptor into N `InlineScriptToolConfig` entries.
  // --------------------------------------------------------------------------------------------

  @Test
  fun `multi-tool shape expands tools into one InlineScriptToolConfig per entry`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        runtime: subprocess
        supportedPlatforms: [web]
        _meta:
          trailblaze/custom: shared
        tools:
          - name: foo_alpha
            description: Alpha tool.
            inputSchema:
              x:
                type: string
          - name: foo_beta
            description: Beta tool.
            inputSchema:
              y:
                type: integer
                required: false
      """.trimIndent(),
    )
    val configs = parsed.toInlineScriptToolConfigs()
    assertEquals(2, configs.size, "multi-tool should expand to one InlineScriptToolConfig per entry")
    val alpha = configs[0]
    val beta = configs[1]

    // Both inherit the descriptor's `script:` and `runtime:`.
    assertEquals("./tools/foo.ts", alpha.script)
    assertEquals("./tools/foo.ts", beta.script)
    assertEquals(ScriptedToolRuntime.SUBPROCESS, alpha.runtime)
    assertEquals(ScriptedToolRuntime.SUBPROCESS, beta.runtime)

    // Per-entry name + description + schema flow through.
    assertEquals("foo_alpha", alpha.name)
    assertEquals("foo_beta", beta.name)
    assertEquals("Alpha tool.", alpha.description)
    assertEquals("Beta tool.", beta.description)

    // File-wide `_meta` + `supportedPlatforms` defaults apply to every entry.
    val alphaMeta = assertNotNull(alpha.meta, "alpha should have inherited file-wide _meta")
    assertEquals(JsonPrimitive("shared"), alphaMeta["trailblaze/custom"])
    val betaMeta = assertNotNull(beta.meta, "beta should have inherited file-wide _meta")
    assertEquals(JsonPrimitive("shared"), betaMeta["trailblaze/custom"])
    // supportedPlatforms fold into _meta under the namespaced key.
    val alphaPlatforms = alphaMeta["trailblaze/supportedPlatforms"] as JsonArray
    assertEquals(JsonPrimitive("web"), alphaPlatforms.single())
  }

  @Test
  fun `multi-tool per-entry _meta overrides file-wide _meta on key conflict`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        _meta:
          trailblaze/custom: file_wide_default
        tools:
          - name: foo_inherits
          - name: foo_overrides
            _meta:
              trailblaze/custom: per_entry
      """.trimIndent(),
    )
    val configs = parsed.toInlineScriptToolConfigs()
    val inherits = configs.single { it.name == "foo_inherits" }
    val overrides = configs.single { it.name == "foo_overrides" }
    assertEquals(JsonPrimitive("file_wide_default"), inherits.meta?.get("trailblaze/custom"))
    assertEquals(
      JsonPrimitive("per_entry"),
      overrides.meta?.get("trailblaze/custom"),
      "per-entry _meta should win on conflict",
    )
  }

  @Test
  fun `multi-tool per-entry requiresHost overrides file-wide default`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        requiresHost: true
        tools:
          - name: foo_inherits
          - name: foo_overrides
            requiresHost: false
      """.trimIndent(),
    )
    val configs = parsed.toInlineScriptToolConfigs()
    val inherits = configs.single { it.name == "foo_inherits" }
    val overrides = configs.single { it.name == "foo_overrides" }
    assertEquals(true, inherits.requiresHost, "file-wide default should apply when entry omits the field")
    assertEquals(false, overrides.requiresHost, "per-entry false should override file-wide true")
  }

  @Test
  fun `multi-tool per-entry supportedPlatforms overrides file-wide default`() {
    // Pins the `entry.supportedPlatforms?.takeIf { it.isNotEmpty() } ?: supportedPlatforms`
    // resolution in `toInlineScriptToolConfigs`. Three entries exercise the three branches:
    //  - foo_inherits     omits the field             → inherits file-wide [android, web]
    //  - foo_overrides    declares [android]          → entry value wins
    //  - foo_empty_inherits declares an empty list    → treated as "inherit" (empty list would
    //                                                  otherwise gate the tool out of every
    //                                                  platform, which is almost never intentional)
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        supportedPlatforms: [android, web]
        tools:
          - name: foo_inherits
          - name: foo_overrides
            supportedPlatforms: [android]
          - name: foo_empty_inherits
            supportedPlatforms: []
      """.trimIndent(),
    )
    val configs = parsed.toInlineScriptToolConfigs()
    val inherits = configs.single { it.name == "foo_inherits" }
    val overrides = configs.single { it.name == "foo_overrides" }
    val emptyInherits = configs.single { it.name == "foo_empty_inherits" }

    // supportedPlatforms folds into _meta under the namespaced key — read it back from there
    // (matches how downstream consumers `TrailblazeToolMeta` resolve the gate at runtime).
    fun platformsOf(name: String, cfg: InlineScriptToolConfig): List<String> {
      val meta = assertNotNull(cfg.meta, "$name should have inherited or overridden supportedPlatforms")
      val arr = meta["trailblaze/supportedPlatforms"] as JsonArray
      return arr.map { (it as JsonPrimitive).content }
    }

    assertEquals(
      listOf("android", "web"),
      platformsOf("foo_inherits", inherits),
      "entry omitting supportedPlatforms should inherit the file-wide list",
    )
    assertEquals(
      listOf("android"),
      platformsOf("foo_overrides", overrides),
      "per-entry non-empty list should override the file-wide list",
    )
    assertEquals(
      listOf("android", "web"),
      platformsOf("foo_empty_inherits", emptyInherits),
      "per-entry empty list should be treated as inherit (not 'gate out of every platform')",
    )
  }

  @Test
  fun `multi-tool shape rejects top-level name`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: should_not_be_here
        tools:
          - name: foo_alpha
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { parsed.toInlineScriptToolConfigs() }
    assertTrue(ex.message!!.contains("must not set a top-level `name:`"), ex.message)
  }

  @Test
  fun `multi-tool shape rejects top-level inputSchema`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        inputSchema:
          x:
            type: string
        tools:
          - name: foo_alpha
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { parsed.toInlineScriptToolConfigs() }
    assertTrue(ex.message!!.contains("must not set a top-level `inputSchema:`"), ex.message)
  }

  @Test
  fun `multi-tool shape rejects an empty tools list`() {
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        tools: []
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { parsed.toInlineScriptToolConfigs() }
    assertTrue(ex.message!!.contains("empty `tools:`"), ex.message)
  }

  @Test
  fun `single-tool descriptor expands to a singleton list under toInlineScriptToolConfigs`() {
    // The plural function MUST accept single-tool descriptors too — that's what makes it the
    // shape-agnostic entry point production loaders call via `flatMap`. Today's authors
    // shouldn't have to migrate any existing descriptor for the new shape to land.
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: foo_tool
        description: Single tool, no multi-tool list.
        inputSchema:
          x:
            type: string
      """.trimIndent(),
    )
    val configs = parsed.toInlineScriptToolConfigs()
    assertEquals(1, configs.size)
    assertEquals("foo_tool", configs.single().name)
  }

  @Test
  fun `single-tool toInlineScriptToolConfig refuses a multi-tool descriptor with a clear error`() {
    // Single-tool callers that pre-date the multi-tool shape get an actionable error pointing
    // at the plural function, not a confusing "name required" failure downstream.
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        tools:
          - name: foo_alpha
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { parsed.toInlineScriptToolConfig() }
    assertTrue(ex.message!!.contains("multi-tool descriptor"), ex.message)
    assertTrue(ex.message!!.contains("toInlineScriptToolConfigs"), ex.message)
  }

  @Test
  fun `single-tool descriptor missing name fails with descriptor-aware message`() {
    // When `tools:` is absent, top-level `name:` is required. The error message has to
    // mention the script path so the author knows which descriptor to fix.
    val parsed = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { parsed.toInlineScriptToolConfigs() }
    assertTrue(ex.message!!.contains("./tools/foo.ts"), ex.message)
    assertTrue(ex.message!!.contains("name:"), ex.message)
  }

  @Test
  fun `requiresEnrichment fires for meta-only, partial single-tool, and partial multi-tool ts descriptors`() {
    // The predicate gates the loader's "route through ScriptedToolEnrichment" branch.
    // Pin every shape so a future refactor that drops or loosens a case fails loudly.
    // Lead-review v2 #6, expanded in v4 for the partial-descriptor authoring shapes.

    // Shape 1 — meta-only: no name, no tools. The analyzer is the sole source of name +
    // inputSchema + description.
    val metaOnly = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        _meta:
          trailblaze/supportedPlatforms: [ios]
      """.trimIndent(),
    )
    assertTrue(metaOnly.requiresEnrichment(), "meta-only descriptor must route through enrichment")

    // Shape 2 — partial single-tool: name present, no description / inputSchema. The
    // analyzer fills the description + inputSchema gaps; the YAML's `name:` selects
    // which `.ts` export to consume (load-bearing for `target.tools:` resolution).
    val partialSingleTool = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: foo
        supportedPlatforms: [ios]
      """.trimIndent(),
    )
    assertTrue(partialSingleTool.requiresEnrichment(), "partial single-tool must route through enrichment")

    // Shape 3 — partial multi-tool: each entry has only `name:`, no `description:` /
    // `inputSchema:`. Analyzer fills the gaps per entry, matched by name against the
    // `.ts`'s exports.
    val partialMultiTool = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        supportedPlatforms: [web]
        tools:
          - name: foo
          - name: bar
      """.trimIndent(),
    )
    assertTrue(partialMultiTool.requiresEnrichment(), "partial multi-tool must route through enrichment")

    // Fully-populated single-tool descriptor: name + description + inputSchema all
    // authored in YAML. No analyzer enrichment needed; the legacy `toInlineScriptToolConfigs`
    // path carries the descriptor straight through.
    val fullSingleTool = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        name: foo
        description: A fully-authored tool.
        inputSchema:
          arg:
            type: string
            description: An arg.
      """.trimIndent(),
    )
    assertEquals(false, fullSingleTool.requiresEnrichment())

    // Fully-populated multi-tool: every entry has description + inputSchema. Legacy path.
    val fullMultiTool = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.ts
        tools:
          - name: foo
            description: Foo tool.
            inputSchema:
              arg:
                type: string
          - name: bar
            description: Bar tool.
            inputSchema:
              arg:
                type: string
      """.trimIndent(),
    )
    assertEquals(false, fullMultiTool.requiresEnrichment())

    // `.js` script with partial shape — analyzer can't parse `.js`, so the partial-
    // descriptor enrichment path is intentionally blocked. The descriptor passes through
    // the legacy full-YAML path; if it's actually missing description/schema the loader's
    // legacy code path handles it (the typical authoring of a `.js` tool with `name:` only
    // is a no-args tool whose name+behavior are fully captured in the YAML).
    val partialJsTool = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.js
        name: foo
      """.trimIndent(),
    )
    assertEquals(
      false,
      partialJsTool.requiresEnrichment(),
      "`.js` script must not route through enrichment — analyzer only parses `.ts`",
    )

    // Case-insensitive `.ts` extension check. macOS / Windows filesystems can surface
    // `foo.TS` and authors expect it to behave the same as `foo.ts`. The
    // `endsWith(".ts", ignoreCase = true)` gate routes both into enrichment so the
    // resulting "no typed declaration found" / "extension not supported" diagnostics
    // come from the analyzer path with a clear message — rather than silently
    // bypassing enrichment and registering an empty-schema tool.
    val partialTsUpperCase = yaml.decodeFromString(
      TrailmapScriptedToolFile.serializer(),
      """
        script: ./tools/foo.TS
        name: foo
      """.trimIndent(),
    )
    assertTrue(
      partialTsUpperCase.requiresEnrichment(),
      "case-insensitive `.TS` script must still route through enrichment",
    )
  }

  @Test
  fun `surfaceToLlm and isRecordable default to true and emit no _meta keys`() {
    // The common case: a normal scripted tool is LLM-visible + recordable. Folding the `true`
    // defaults into `_meta` would change the wire shape for nearly every tool, so they must NOT
    // appear when omitted — exactly the inverse of requiresHost (which only folds its `true`).
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
    """.trimIndent()

    val parsed = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
    assertTrue(parsed.surfaceToLlm)
    assertTrue(parsed.isRecordable)
    val inline = parsed.toInlineScriptToolConfig()
    assertTrue(inline.surfaceToLlm, "typed surfaceToLlm field must default true")
    assertTrue(inline.isRecordable, "typed isRecordable field must default true")
    assertEquals(null, inline.meta, "true defaults must not fold any _meta key")
  }

  @Test
  fun `surfaceToLlm false folds into the _meta namespaced key and the typed field`() {
    // The internal-step opt-out: the host advertise filter reads the typed field; the on-device
    // QuickJS launcher reads `_meta["trailblaze/surfaceToLlm"]`. Both must reflect the opt-out.
    val source = """
      script: ./tools/internal_step.ts
      name: internal_step
      surfaceToLlm: false
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertEquals(false, inline.surfaceToLlm, "typed surfaceToLlm field must propagate the opt-out")
    val meta = assertNotNull(inline.meta, "surfaceToLlm: false must produce a non-null _meta")
    assertEquals(
      JsonPrimitive(false),
      meta["trailblaze/surfaceToLlm"],
      "Top-level `surfaceToLlm: false` must fold into `_meta.trailblaze/surfaceToLlm`",
    )
  }

  @Test
  fun `isRecordable false folds into the _meta namespaced key and the typed field`() {
    val source = """
      script: ./tools/internal_step.ts
      name: internal_step
      isRecordable: false
    """.trimIndent()

    val inline = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfig()
    assertEquals(false, inline.isRecordable, "typed isRecordable field must propagate the opt-out")
    val meta = assertNotNull(inline.meta, "isRecordable: false must produce a non-null _meta")
    assertEquals(
      JsonPrimitive(false),
      meta["trailblaze/isRecordable"],
      "Top-level `isRecordable: false` must fold into `_meta.trailblaze/isRecordable`",
    )
  }

  @Test
  fun `multi-tool entry inherits file-wide surfaceToLlm and isRecordable unless overridden`() {
    // Per-entry `null` (default) inherits the file-wide opt-out; an explicit per-entry value wins.
    val source = """
      script: ./tools/steps.ts
      surfaceToLlm: false
      isRecordable: false
      tools:
        - name: inherits_both
        - name: overrides_visible
          surfaceToLlm: true
    """.trimIndent()

    val configs = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
      .toInlineScriptToolConfigs()
      .associateBy { it.name }

    val inherits = assertNotNull(configs["inherits_both"])
    assertEquals(false, inherits.surfaceToLlm)
    assertEquals(false, inherits.isRecordable)

    val overrides = assertNotNull(configs["overrides_visible"])
    assertEquals(true, overrides.surfaceToLlm, "per-entry surfaceToLlm: true must override file-wide false")
    assertEquals(false, overrides.isRecordable, "isRecordable still inherits the file-wide false")
  }

  @Test
  fun `_meta with non-boolean trailblaze surfaceToLlm is rejected with descriptor-aware error`() {
    // Mirrors the requiresHost wrong-type validation: a non-boolean `_meta.trailblaze/surfaceToLlm`
    // is read with `booleanOrNull` downstream and would silently default to `true` (a "my opt-out
    // didn't take" footgun). Fail loudly at descriptor-load time instead, naming the tool + key.
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      _meta:
        trailblaze/surfaceToLlm: 1
    """.trimIndent()

    val descriptor = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
    val ex = assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
    val msg = assertNotNull(ex.message)
    assertTrue(msg.contains("foo_tool"), "expected tool name in: $msg")
    assertTrue(msg.contains("trailblaze/surfaceToLlm"), "expected key name in: $msg")
    assertTrue(msg.contains("boolean"), "expected expected-type guidance in: $msg")
  }

  @Test
  fun `_meta with non-boolean trailblaze isRecordable is rejected with descriptor-aware error`() {
    val source = """
      script: ./tools/foo.ts
      name: foo_tool
      _meta:
        trailblaze/isRecordable: "yes"
    """.trimIndent()

    val descriptor = yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), source)
    val ex = assertFailsWith<IllegalArgumentException> { descriptor.toInlineScriptToolConfig() }
    val msg = assertNotNull(ex.message)
    assertTrue(msg.contains("trailblaze/isRecordable"), "expected key name in: $msg")
    assertTrue(msg.contains("boolean"), "expected expected-type guidance in: $msg")
  }
}

package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailblazeToolParameterConfig

/**
 * Direct tests for [ResolvedTargetToolDetailRenderer]. The renderer is the single source
 * of truth for per-tool Markdown across three pipelines (workspace `trailblaze check`
 * sidecars, OSS `:docs:generator:run`, internal `:internal-docs-generator:run`); without
 * dedicated tests at this layer, a regression in any of the rendered sections silently
 * propagates to every doc surface and is only caught by downstream `git diff --exit-code`
 * after a regen — far from the cause.
 *
 * Covers:
 *  - All three [ResolvedTargetToolDetailRenderer.ToolDetail] variants (class-backed via
 *    a `@TrailblazeToolClass` fixture, YAML-defined via [ToolYamlConfig], scripted via
 *    [InlineScriptToolConfig]).
 *  - Both `renderMarkdown` overloads — the workspace `(detail, targetId)` shorthand and
 *    the docs-generator `(detail, Header)` caller-configurable variant.
 *  - JSON-schema → param-row rendering for scripted tools (required vs optional split,
 *    enum/array types, missing properties).
 *  - The "no description" fallback render.
 *  - `originTrailmapDir` relative-path computation for scripted tools.
 */
class ResolvedTargetToolDetailRendererTest {

  // ── Fixtures ────────────────────────────────────────────────────────────────────────

  @Serializable
  @LLMDescription("Greets a user by name.")
  @TrailblazeToolClass("greetTool")
  private data class GreetTool(
    @param:LLMDescription("Person to greet.")
    val name: String,
    @param:LLMDescription("How loudly to greet.")
    val loudness: Int = 1,
  ) : TrailblazeTool

  @Serializable
  @LLMDescription("Tool with no documented parameters.")
  @TrailblazeToolClass("emptyTool")
  private class EmptyTool : TrailblazeTool

  @Serializable
  @LLMDescription("Sharp utility — not LLM-visible, not recordable, host-only.")
  @TrailblazeToolClass(
    name = "sharpUtility",
    surfaceToLlm = false,
    isRecordable = false,
    requiresHost = true,
  )
  private class SharpUtilityTool : TrailblazeTool

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("renderer-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  // ── ClassBacked ─────────────────────────────────────────────────────────────────────

  @Test
  fun `class-backed renders description, FQN source, required+optional params`() {
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "greetTool",
      kclass = GreetTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("missing banner") {
      md.startsWith(ResolvedTargetToolDetailRenderer.GENERATED_BANNER)
    }
    assertTrue("description from @LLMDescription") { md.contains("Greets a user by name.") }
    assertTrue("source kind") { md.contains("- Kind: class-backed") }
    assertTrue("source class FQN") {
      md.contains("- Class: `${GreetTool::class.qualifiedName}`")
    }
    assertTrue("required section header") { md.contains("### Required parameters") }
    assertTrue("required param + description") {
      md.contains("- `name` — `String`") && md.contains("Person to greet.")
    }
    assertTrue("optional section header") { md.contains("### Optional parameters") }
    assertTrue("optional param") {
      md.contains("- `loudness` — `Integer`") && md.contains("How loudly to greet.")
    }
    assertTrue("output section") { md.contains("Returns: `string` (opaque text content)") }
  }

  @Test
  fun `class-backed renders Contract section with annotation-derived flags (defaults)`() {
    // GreetTool sets only `name` — surfaceToLlm/isRecordable default to true and requiresHost
    // defaults to false. The Contract section should always render all three lines so a
    // reader scanning a sidecar can answer "is this LLM-visible / recordable / host-only?"
    // without grepping the annotation declaration.
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "greetTool",
      kclass = GreetTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("Contract section header") { md.contains("## Contract") }
    assertTrue("LLM-visible default=yes") {
      md.contains("- Visible to LLM: yes (`surface_to_llm: true`)")
    }
    assertTrue("Recordable default=yes") {
      md.contains("- Recordable: yes (`is_recordable: true`)")
    }
    assertTrue("Host-only default=no") {
      md.contains("- Host-only: no (`requires_host: false`)")
    }
  }

  @Test
  fun `class-backed renders Contract section with annotation-overridden flags`() {
    // SharpUtilityTool overrides all three flags. Pin that the renderer reflects them
    // verbatim from the @TrailblazeToolClass annotation rather than always reporting the
    // schema defaults — this is the bug class that motivated the Contract section
    // (PR #3407 fix: AndroidSendBroadcast carried surfaceToLlm=false but its annotation
    // hadn't actually set isRecordable=false).
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "sharpUtility",
      kclass = SharpUtilityTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("LLM-visible reflects surfaceToLlm=false") {
      md.contains("- Visible to LLM: no (`surface_to_llm: false`)")
    }
    assertTrue("Recordable reflects isRecordable=false") {
      md.contains("- Recordable: no (`is_recordable: false`)")
    }
    assertTrue("Host-only reflects requiresHost=true") {
      md.contains("- Host-only: yes (`requires_host: true`)")
    }
  }

  @Test
  fun `class-backed with no parameters renders the empty-params message`() {
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "emptyTool",
      kclass = EmptyTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("description present") { md.contains("Tool with no documented parameters.") }
    assertTrue("no-params message") { md.contains("_(no parameters)_") }
    assertFalse("no required header") { md.contains("### Required parameters") }
    assertFalse("no optional header") { md.contains("### Optional parameters") }
  }

  // ── YamlDefined ─────────────────────────────────────────────────────────────────────

  @Test
  fun `yaml-defined renders description, tool id, and parameter list`() {
    val config = ToolYamlConfig(
      id = "eraseText",
      description = "Erase characters from the focused text field.",
      parameters = listOf(
        TrailblazeToolParameterConfig(
          name = "charactersToErase",
          type = "integer",
          required = false,
          description = "Number of characters to erase.",
        ),
      ),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(
      name = "eraseText",
      config = config,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("description") { md.contains("Erase characters from the focused text field.") }
    assertTrue("source kind") { md.contains("- Kind: YAML-defined") }
    assertTrue("tool id") { md.contains("- Tool id: `eraseText`") }
    assertTrue("optional param row") {
      md.contains("- `charactersToErase` — `integer`") && md.contains("Number of characters to erase.")
    }
  }

  @Test
  fun `yaml-defined renders Contract section with explicit flags from YAML config`() {
    // ToolYamlConfig in `tools:` mode lets the YAML author set the three contract flags
    // directly. Pin that the renderer reads them through verbatim instead of forcing the
    // annotation-equivalent defaults — a YAML author saying `surface_to_llm: false` and
    // `is_recordable: false` should see those flags in the sidecar.
    val config = ToolYamlConfig(
      id = "internalUtility",
      description = "YAML-defined sharp utility.",
      surfaceToLlm = false,
      isRecordable = false,
      requiresHost = true,
      parameters = emptyList(),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(
      name = "internalUtility",
      config = config,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("Contract section header") { md.contains("## Contract") }
    assertTrue("LLM-visible reflects yaml `surface_to_llm: false`") {
      md.contains("- Visible to LLM: no (`surface_to_llm: false`)")
    }
    assertTrue("Recordable reflects yaml `is_recordable: false`") {
      md.contains("- Recordable: no (`is_recordable: false`)")
    }
    assertTrue("Host-only reflects yaml `requires_host: true`") {
      md.contains("- Host-only: yes (`requires_host: true`)")
    }
  }

  @Test
  fun `yaml-defined Contract falls back to framework defaults when flags are null`() {
    // The three flags are `Boolean?` on ToolYamlConfig — null means "use framework default".
    // The renderer should resolve null to true/true/false rather than render `null` or omit
    // the line, so the Contract section is always a complete three-row view.
    val config = ToolYamlConfig(
      id = "defaults",
      description = null,
      parameters = emptyList(),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(
      name = "defaults",
      config = config,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("LLM-visible default=yes") {
      md.contains("- Visible to LLM: yes (`surface_to_llm: true`)")
    }
    assertTrue("Recordable default=yes") {
      md.contains("- Recordable: yes (`is_recordable: true`)")
    }
    assertTrue("Host-only default=no") {
      md.contains("- Host-only: no (`requires_host: false`)")
    }
  }

  @Test
  fun `yaml-defined with no description falls back to the missing marker`() {
    val config = ToolYamlConfig(
      id = "bareTool",
      description = null,
      parameters = emptyList(),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(
      name = "bareTool",
      config = config,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("missing-description fallback") { md.contains("_(no description provided)_") }
  }

  // ── Scripted ────────────────────────────────────────────────────────────────────────

  @Test
  fun `scripted renders description, source, host-only flag, and required+optional schema rows`() {
    val config = InlineScriptToolConfig(
      script = "./tools/makePost.ts",
      name = "makePost",
      description = "Author a new post.",
      requiresHost = true,
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
          "properties",
          buildJsonObject {
            put(
              "title",
              buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Post headline."))
              },
            )
            put(
              "draft",
              buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Mark as draft."))
              },
            )
          },
        )
        put("required", buildJsonArray { add(JsonPrimitive("title")) })
      },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "makePost",
      config = config,
      originTrailmapId = "blog",
      consumerTrailmapId = "blog",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("description") { md.contains("Author a new post.") }
    assertTrue("source kind") { md.contains("- Kind: scripted") }
    assertTrue("script path verbatim (no relativize without trailmapDir)") {
      md.contains("- Script: `./tools/makePost.ts`")
    }
    assertTrue("origin trailmap") { md.contains("Origin trailmap: `blog` (declared by this trailmap)") }
    assertTrue("host-only flag") { md.contains("- Host-only: yes (`requiresHost: true`)") }
    assertTrue("required param") {
      md.contains("### Required parameters") &&
        md.contains("- `title` — `string`") &&
        md.contains("Post headline.")
    }
    assertTrue("optional param") {
      md.contains("### Optional parameters") &&
        md.contains("- `draft` — `boolean`") &&
        md.contains("Mark as draft.")
    }
  }

  @Test
  fun `scripted Contract renders all three flags at their defaults`() {
    // A scripted tool that declares none of the three flags should still get the complete
    // three-row Contract view — same shape a class-backed tool gets — rather than an absent
    // line a reader has to interpret.
    val config = InlineScriptToolConfig(
      script = "./tools/noHost.ts",
      name = "noHost",
      description = "Doesn't need host.",
      requiresHost = false,
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "noHost",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("Contract section header") { md.contains("## Contract") }
    assertTrue("LLM-visible default=yes") {
      md.contains("- Visible to LLM: yes (`surfaceToLlm: true`)")
    }
    assertTrue("Recordable default=yes") {
      md.contains("- Recordable: yes (`isRecordable: true`)")
    }
    assertTrue("Host-only line renders `no` explicitly") {
      md.contains("- Host-only: no (`requiresHost: false`)")
    }
  }

  @Test
  fun `scripted Contract reflects the typed surfaceToLlm and isRecordable opt-outs`() {
    // The authoring shape a `.ts` spec produces (`trailblaze.tool<A>({surfaceToLlm: false, …})`
    // lowers to the typed InlineScriptToolConfig fields). Without this, a reader of an
    // LLM-hidden scripted tool's page had no way to tell it was hidden from the agent — the
    // Contract section named only `requires_host`.
    val config = InlineScriptToolConfig(
      script = "./tools/deleteThing.ts",
      name = "deleteThing",
      description = "Destructive self-clean step a trail invokes explicitly.",
      surfaceToLlm = false,
      isRecordable = false,
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "deleteThing",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("LLM-visible reflects surfaceToLlm=false, got:\n$md") {
      md.contains("- Visible to LLM: no (`surfaceToLlm: false`)")
    }
    assertTrue("Recordable reflects isRecordable=false") {
      md.contains("- Recordable: no (`isRecordable: false`)")
    }
    assertTrue("Host-only untouched by the other two flags") {
      md.contains("- Host-only: no (`requiresHost: false`)")
    }
  }

  @Test
  fun `scripted Contract honors _meta opt-outs authored without the typed shortcut`() {
    // A hand-authored `target.tools:` entry can carry the namespaced `_meta` keys alone, with no
    // typed shortcut field. The runtime combines both sides (opt-out AND for surfaceToLlm /
    // isRecordable, opt-in OR for requiresHost); reading only the typed field would render a
    // hidden, non-recordable, host-only tool as visible/recordable/device-capable.
    val config = InlineScriptToolConfig(
      script = "./tools/internalStep.ts",
      name = "internalStep",
      description = "Internal launch step composed by an orchestrator.",
      meta = buildJsonObject {
        put("trailblaze/surfaceToLlm", JsonPrimitive(false))
        put("trailblaze/isRecordable", JsonPrimitive(false))
        put("trailblaze/requiresHost", JsonPrimitive(true))
      },
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "internalStep",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("LLM-visible reflects `_meta.trailblaze/surfaceToLlm: false`, got:\n$md") {
      md.contains("- Visible to LLM: no (`surfaceToLlm: false`)")
    }
    assertTrue("Recordable reflects `_meta.trailblaze/isRecordable: false`") {
      md.contains("- Recordable: no (`isRecordable: false`)")
    }
    assertTrue("Host-only reflects `_meta.trailblaze/requiresHost: true`") {
      md.contains("- Host-only: yes (`requiresHost: true`)")
    }
  }

  @Test
  fun `scripted Contract never renders the snake_case YAML flag spellings`() {
    // `InlineScriptToolConfig` declares no `@SerialName`, so camelCase is the only key a scripted
    // descriptor decodes — and because every scripted decode path runs `strictMode = false`, an
    // author who copies `surface_to_llm: false` off a page gets a silent no-op that leaves the tool
    // LLM-VISIBLE, the opposite of what they asked for. Pin the absence, not just the presence:
    // the tokens are the copy-paste contract, so a future refactor that re-shares one literal
    // across both kinds has to fail here.
    val config = InlineScriptToolConfig(
      script = "./tools/spelling.ts",
      name = "spelling",
      description = "Checks the rendered flag spelling.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "spelling",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    listOf("surface_to_llm", "is_recordable", "requires_host").forEach { yamlOnlyKey ->
      assertFalse("scripted page must not advertise the YAML-only key `$yamlOnlyKey`, got:\n$md") {
        md.contains(yamlOnlyKey)
      }
    }
  }

  @Test
  fun `yaml-defined Contract keeps the snake_case spellings its decoder accepts`() {
    // The other half of the split: `ToolYamlConfig` carries `@SerialName("surface_to_llm")`, so
    // snake_case IS the settable key there and must NOT drift to camelCase along with scripted.
    val config = ToolYamlConfig(
      id = "spelling",
      description = "Checks the rendered flag spelling.",
      parameters = emptyList(),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(name = "spelling", config = config)
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "fixture")
    assertTrue("yaml page keeps `surface_to_llm`, got:\n$md") { md.contains("`surface_to_llm: true`") }
    assertFalse("yaml page must not render the scripted camelCase key") {
      md.contains("`surfaceToLlm:")
    }
  }

  @Test
  fun `scripted from exporting dep shows the exports attribution`() {
    val config = InlineScriptToolConfig(
      script = "./tools/createEntity.ts",
      name = "createEntity",
      description = "Make a thing.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "createEntity",
      config = config,
      originTrailmapId = "entity_factory",
      consumerTrailmapId = "storefront",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "storefront")
    assertTrue("exports attribution") {
      md.contains("Origin trailmap: `entity_factory` (exported via `exports:` and consumed by `storefront`)")
    }
  }

  @Test
  fun `scripted with empty input schema renders the no-input-schema marker`() {
    val config = InlineScriptToolConfig(
      script = "./tools/noop.ts",
      name = "noop",
      description = "Does nothing.",
      inputSchema = buildJsonObject {},
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "noop",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "trailmap")
    assertTrue("empty-schema marker") { md.contains("_(no input schema provided)_") }
  }

  @Test
  fun `scripted with array type and enum type renders friendly type labels`() {
    val config = InlineScriptToolConfig(
      script = "./tools/picker.ts",
      name = "picker",
      description = "Pick a thing.",
      inputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
          "properties",
          buildJsonObject {
            put(
              "tags",
              buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
              },
            )
            put(
              "color",
              buildJsonObject {
                put("enum", buildJsonArray { add(JsonPrimitive("red")); add(JsonPrimitive("blue")) })
              },
            )
          },
        )
      },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "picker",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "trailmap")
    assertTrue("array<string> type label") { md.contains("- `tags` — `array<string>`") }
    assertTrue("enum type label") { md.contains("- `color` — `enum(red | blue)`") }
  }

  @Test
  fun `scripted relativizes absolute script path under originTrailmapDir`() {
    // Authoring side stamps `script` as the resolved absolute path. The renderer should
    // emit it relative to the trailmap root so committed sidecars don't leak per-machine
    // absolute paths.
    val trailmapDir = newDir("trailmap")
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    val scriptFile = File(toolsDir, "pingTool.ts").apply { writeText("// ping") }
    val config = InlineScriptToolConfig(
      script = scriptFile.absolutePath,
      name = "pingTool",
      description = "Ping.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "pingTool",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
      originTrailmapDir = trailmapDir,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "trailmap")
    assertTrue("script path rendered relative to trailmap root, got:\n$md") {
      md.contains("- Script: `./tools/pingTool.ts`")
    }
    assertFalse("absolute path must not leak when trailmap root is known") {
      md.contains(scriptFile.absolutePath)
    }
  }

  @Test
  fun `scripted falls back to raw script when originTrailmapDir is null`() {
    val config = InlineScriptToolConfig(
      script = "/repo/uitests/some/scripted_tool.ts",
      name = "absoluteTool",
      description = "Has an absolute path with no trailmap-dir context.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "absoluteTool",
      config = config,
      originTrailmapId = "trailmap",
      consumerTrailmapId = "trailmap",
      originTrailmapDir = null,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "trailmap")
    assertTrue("raw script path rendered without trailmap-dir context") {
      md.contains("- Script: `/repo/uitests/some/scripted_tool.ts`")
    }
  }

  // ── Header overload ────────────────────────────────────────────────────────────────

  @Test
  fun `Header overload stamps caller-configurable origin and regen hint into the preamble`() {
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "greetTool",
      kclass = GreetTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(
      detail = detail,
      header = ResolvedTargetToolDetailRenderer.Header(
        origin = "Trailblaze framework tool reference",
        regenerateHint = "Regenerate with: ./gradlew :docs:generator:run",
      ),
    )
    assertTrue("banner first line is unchanged") {
      md.lines().first() == ResolvedTargetToolDetailRenderer.GENERATED_BANNER
    }
    assertTrue("origin line under caller control") {
      md.contains("<!-- Trailblaze framework tool reference -->")
    }
    assertTrue("regen hint under caller control") {
      md.contains("<!-- Regenerate with: ./gradlew :docs:generator:run -->")
    }
  }

  @Test
  fun `targetId overload uses the sidecar-flavored preamble`() {
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "greetTool",
      kclass = GreetTool::class,
    )
    val md = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = "wikipedia")
    assertTrue("sidecar origin") { md.contains("<!-- Sidecar for target: wikipedia -->") }
    assertTrue("sidecar regen hint") { md.contains("<!-- Regenerate with: trailblaze check -->") }
  }

  // ── matrixKindAndSource (availability-matrix Kind/Source columns) ─────────────────────

  @Test
  fun `matrixKindAndSource labels a class-backed tool Kotlin with its simple class name`() {
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(
      name = "greetTool",
      kclass = GreetTool::class,
    )
    assertEquals("Kotlin" to "`GreetTool`", ResolvedTargetToolDetailRenderer.matrixKindAndSource(detail))
  }

  @Test
  fun `matrixKindAndSource labels a YAML-defined tool YAML with its tool id`() {
    val config = ToolYamlConfig(
      id = "eraseText",
      description = null,
      parameters = emptyList(),
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(name = "eraseText", config = config)
    assertEquals("YAML" to "`eraseText`", ResolvedTargetToolDetailRenderer.matrixKindAndSource(detail))
  }

  @Test
  fun `matrixKindAndSource labels a scripted tool TypeScript with just the ts filename`() {
    // Source is the base name only — not the full (possibly absolute) script path the runtime carries.
    val config = InlineScriptToolConfig(
      script = "/abs/workspace/trailmaps/blog/tools/makePost.ts",
      name = "makePost",
      description = "Author a new post.",
      inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
    )
    val detail = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
      name = "makePost",
      config = config,
      originTrailmapId = "blog",
      consumerTrailmapId = "blog",
    )
    assertEquals("TypeScript" to "`makePost.ts`", ResolvedTargetToolDetailRenderer.matrixKindAndSource(detail))
  }

  @Test
  fun `matrixKindAndSource renders dash-dash for an unclassifiable (null) tool`() {
    assertEquals("-" to "-", ResolvedTargetToolDetailRenderer.matrixKindAndSource(null))
  }
}

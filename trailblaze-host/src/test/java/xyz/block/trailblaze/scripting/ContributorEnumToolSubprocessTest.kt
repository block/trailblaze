package xyz.block.trailblaze.scripting

import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.subprocess.InlineScriptToolServerSynthesizer
import xyz.block.trailblaze.scripting.subprocess.McpSpawnContext
import xyz.block.trailblaze.scripting.subprocess.McpSubprocessSession
import xyz.block.trailblaze.scripting.subprocess.McpSubprocessSpawner
import xyz.block.trailblaze.scripting.subprocess.StderrCapture
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end regression for the enum-param subprocess-registration bug, exercising the FULL
 * contributor path: a `.ts` tool with a NAMED string-literal-union enum input param →
 * [AnalyzerScriptedToolEnrichment] (real `bun` + `ts-json-schema-generator`) →
 * [InlineScriptToolServerSynthesizer] → spawned MCP subprocess.
 *
 * `ts-json-schema-generator` (configured `expose: "all"`) emits a named enum type as a property
 * `{ "$ref": "#/definitions/Direction" }` plus a top-level `definitions` bag. Before the fix, the
 * subprocess wrapper's zod converter (`jsonSchemaPropertyToZod`) threw on that bare `$ref` at
 * z-schema build time, so the spawned server crashed during module evaluation and
 * [McpSubprocessSession.connect] failed. The enrichment layer now inlines the ref
 * ([ScriptedToolSchemaRefFlattener]) before the schema becomes an [InlineScriptToolConfig], so the
 * subprocess registers and advertises the enum cleanly.
 *
 * Gated on `bun` + the analyzer tooling via [AnalyzerScriptedToolEnrichment.resolveFromEnvironment];
 * on a host without them it assume-skips, matching [ScriptedToolDefinitionAnalyzerTest]. The
 * deterministic, always-run guards live in [ScriptedToolSchemaRefFlattenerTest] and
 * [AnalyzerScriptedToolEnrichmentTest].
 */
class ContributorEnumToolSubprocessTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `contributor enum-param ts tool enriches, synthesizes, and registers as a subprocess`() = runBlocking {
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    assumeTrue(
      "bun + ts-json-schema-generator must be available to exercise the real analyzer path — " +
        "install bun (https://bun.sh/) and run `bun install` under sdks/typescript.",
      enrichment != null,
    )

    // --- 1. Author a contributor-style `.ts` tool whose input param is a NAMED enum type alias. ---
    // The named alias (not an inline union) is what makes ts-json-schema-generator emit a `$ref`.
    val trailmapDir = tempFolder.newFolder("enum-trailmap")
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "enumTool.ts").writeText(
      """
      |declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => unknown };
      |
      |type Direction = "UP" | "DOWN";
      |
      |interface EnumInput {
      |  /** Which way to swipe. */
      |  direction: Direction;
      |}
      |interface EnumOutput { ok: boolean; }
      |
      |/** Swipes the screen in a fixed direction. */
      |export const enumTool = trailblaze.tool<EnumInput, EnumOutput>({
      |  handler: async () => ({ ok: true }),
      |});
      """.trimMargin() + "\n",
    )

    // --- 2. Run the REAL enrichment (analyzer subprocess + ref-flattening). ---
    val results = enrichment!!.enrich(
      trailmapId = "enumapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = toolsDir,
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/enumTool.yaml",
          descriptor = TrailmapScriptedToolFile(script = "./enumTool.ts"),
        ),
      ),
    )
    val config = (results.single() as? ScriptedToolEnrichment.EnrichmentResult.Resolved)
      ?.configs?.single()
      ?: fail("expected the enum tool to enrich cleanly, got: ${results.single()}")

    // The enriched schema must already be self-contained — this is the fix, and the assertion
    // fails before it (the analyzer's raw `$ref` would pass through untouched).
    val direction = assertNotNull(
      config.inputSchema["properties"]?.jsonObject?.get("direction")?.jsonObject,
      "expected a 'direction' property on the enriched schema: ${config.inputSchema}",
    )
    assertNull(config.inputSchema["definitions"], "definitions bag must be inlined away")
    assertNull(direction["\$ref"], "the enum \$ref must be inlined before reaching the synthesizer")
    assertTrue(
      direction["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() == setOf("UP", "DOWN"),
      "expected inline enum [UP, DOWN], got: $direction",
    )

    // --- 3. Synthesize + spawn the subprocess against a runtime author file. ---
    // The analyzed `.ts` uses a type-only `declare const trailblaze` stub, so it isn't importable
    // at runtime; point the spawn config's `script` at a real module that exports the handler. The
    // SCHEMA under test still comes from the contributor authoring path above.
    val runtimeAuthor = File(toolsDir, "enumTool_runtime.mjs").apply {
      writeText(
        """
        export async function ${config.name}(args) {
          return `direction=${'$'}{args.direction}`;
        }
        """.trimIndent() + "\n",
      )
    }
    val spawnConfig = InlineScriptToolConfig(
      script = runtimeAuthor.absolutePath,
      name = config.name,
      description = config.description,
      inputSchema = config.inputSchema,
    )

    val generatedDir = tempFolder.newFolder("generated")
    val generated = InlineScriptToolServerSynthesizer.synthesize(
      tools = listOf(spawnConfig),
      outputDir = generatedDir,
    )

    val spawned = McpSubprocessSpawner.spawn(
      config = generated.single(),
      context = McpSpawnContext(
        platform = TrailblazeDevicePlatform.ANDROID,
        driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 2400,
        sessionId = SessionId("contributor_enum_tool_test"),
      ),
    )
    val stderrLog = File(tempFolder.root, "enum-tool.stderr.log")
    try {
      // `connect` performs the MCP `initialize` handshake. Pre-fix the subprocess crashed during
      // module evaluation (the `$ref` zod-build throw) and this would fail with the crash stderr.
      val session = runCatching {
        McpSubprocessSession.connect(spawnedProcess = spawned, stderrCapture = StderrCapture(stderrLog))
      }.getOrElse { t ->
        val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
        throw AssertionError("enum-param inline tool subprocess failed to register. stderr:\n$stderr", t)
      }
      try {
        val listed = session.client.listTools(ListToolsRequest()).tools
        assertTrue(
          listed.any { it.name == config.name },
          "expected the spawned server to advertise '${config.name}', got: ${listed.map { it.name }}",
        )
        // The advertised schema must carry the enum constraint (proves the z.enum branch ran).
        val advertised = listed.single { it.name == config.name }.inputSchema.toString()
        assertTrue(advertised.contains("direction"), "advertised schema missing 'direction': $advertised")
        assertTrue(
          advertised.contains("UP") && advertised.contains("DOWN"),
          "advertised schema missing the enum values: $advertised",
        )
      } finally {
        session.shutdown()
      }
    } finally {
      if (!spawned.process.waitFor(10, TimeUnit.SECONDS)) {
        spawned.process.destroyForcibly()
        spawned.process.waitFor(5, TimeUnit.SECONDS)
      }
    }
  }
}

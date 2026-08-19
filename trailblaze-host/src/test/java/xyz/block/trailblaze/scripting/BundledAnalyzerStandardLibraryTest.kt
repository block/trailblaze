package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.util.BunBinaryResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * End-to-end guard on the shipped analyzer bundle's ability to resolve TypeScript's **standard
 * library**.
 *
 * Every other analyzer test runs the shim out of the SDK source tree, where `node_modules` is
 * present and TypeScript finds its own `lib*.d.ts` files with no help. That is why the whole
 * suite stayed green while 2026.08.18 shipped a bundle that could resolve none of them: the
 * bundle inlines TypeScript's code but not its lib files, and `bun build` freezes the path it
 * loads them from to the build machine — a path that exists on the build agent and nowhere
 * else. Installed users got `Unhandled error while creating Base Type` on any tool whose I/O
 * type used `Record`, `Partial`, `Pick`, or `Omit`, while a raw index signature (which needs no
 * lib type) worked fine.
 *
 * So this test deliberately runs the **extracted bundle**, from a cache dir with no SDK tree
 * anywhere above it — the installed-CLI shape — and asserts the lib types extract.
 */
class BundledAnalyzerStandardLibraryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var bun: File
  private var bundledSdkDir: File? = null

  @Before
  fun setup() {
    bun = BunBinaryResolver.resolveBunBinary() ?: File(tempFolder.root, "missing-bun")
    // Extract into a temp dir rather than the real `~/.trailblaze/analyzer` cache so the test
    // never depends on — or disturbs — the developer's own extracted bundle.
    val loader = ScriptedToolDefinitionAnalyzer::class.java.classLoader
    val shimBytes = loader
      ?.getResourceAsStream(ScriptedToolDefinitionAnalyzer.BUNDLED_ANALYZER_SHIM_RESOURCE)
      ?.use { it.readBytes() }
      ?.takeIf { it.isNotEmpty() }
    val libArchive = loader
      ?.getResourceAsStream(ScriptedToolDefinitionAnalyzer.BUNDLED_ANALYZER_TS_LIB_RESOURCE)
      ?.use { it.readBytes() }
      ?.takeIf { it.isNotEmpty() }
    bundledSdkDir = shimBytes?.let {
      ScriptedToolDefinitionAnalyzer.extractBundledShim(
        it,
        tempFolder.newFolder("bundled-analyzer"),
        tsLibArchive = libArchive,
      )
    }
  }

  private fun analyzerOverBundle(): ScriptedToolDefinitionAnalyzer {
    assumeTrue(
      "bun binary not found on PATH — install bun (`brew install bun`, or see https://bun.sh/).",
      bun.isFile,
    )
    val sdkDir = bundledSdkDir
    assumeTrue(
      "requires the analyzer bundle staged into JAR resources by " +
        ":trailblaze-models:bundleScriptedToolAnalyzerShim",
      sdkDir != null,
    )
    val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir!!)
    assumeTrue("extracted bundle is missing its shim", shim != null)
    return ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim!!,
      sdkDir = sdkDir,
      // The cache keys on content, and a hit would serve a result the bundle never produced —
      // which is exactly the code path under test.
      disableCache = true,
    )
  }

  @Test
  fun `the extracted bundle resolves types declared in TypeScript's standard library`() = runBlocking {
    val analyzer = analyzerOverBundle()
    val toolsDir = tempFolder.newFolder("stdlib-trailmap-tools")
    // One tool per lib-declared utility type that a real trailmap reached for. `Record` is the
    // one seen in practice (a proto `map` field and a deep-link parameter bag); the others share
    // its resolution path, so they fail and recover together.
    File(toolsDir, "stdlibTools.ts").writeText(
      """
        |declare const trailblaze: { tool: <I, O>(spec: unknown) => unknown };
        |
        |interface Base { a: string; b: number; }
        |interface Ok { ok: boolean; }
        |
        |interface RecordIn { attrs: Record<string, string>; }
        |interface RecordUnknownOut { profile?: Record<string, unknown>; }
        |interface PartialIn { p: Partial<Base>; }
        |interface PickIn { q: Pick<Base, "a">; }
        |interface OmitIn { r: Omit<Base, "a">; }
        |
        |export const recordTool = trailblaze.tool<RecordIn, RecordUnknownOut>({
        |  handler: async () => ({}),
        |});
        |export const partialTool = trailblaze.tool<PartialIn, Ok>({
        |  handler: async () => ({ ok: true }),
        |});
        |export const pickTool = trailblaze.tool<PickIn, Ok>({
        |  handler: async () => ({ ok: true }),
        |});
        |export const omitTool = trailblaze.tool<OmitIn, Ok>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    // A ScriptedToolDefinitionException here carries the per-tool diagnostics, so letting it
    // propagate reports which utility types failed rather than just a count mismatch.
    val defs = analyzer.analyze(toolsDir)

    assertEquals(
      listOf("omitTool", "partialTool", "pickTool", "recordTool"),
      defs.map { it.name }.sorted(),
      "every lib-declared utility type must extract from the bundled analyzer",
    )
    // Not just "it didn't throw": the mapped type has to be described, or a downstream LLM sees
    // a property with no shape. `Record<string, string>` lands as a `$ref` into a sibling
    // `definitions` entry carrying `additionalProperties`, so follow the ref and pin the value
    // type — the same shape the source-tree analyzer test pins for `Record<string, number>`.
    val inputSchema = defs.single { it.name == "recordTool" }.inputSchemaObject
    val ref = inputSchema["properties"]?.jsonObject?.get("attrs")?.jsonObject
      ?.get("\$ref")?.jsonPrimitive?.contentOrNull
      ?: fail("expected a \$ref for Record<string, string>; got $inputSchema")
    val definitionName = java.net.URLDecoder.decode(ref.substringAfter("#/definitions/"), "UTF-8")
    val recordSchema = inputSchema["definitions"]?.jsonObject?.get(definitionName)?.jsonObject
      ?: fail("expected definitions['$definitionName']; got $inputSchema")
    assertEquals(
      "string",
      recordSchema["additionalProperties"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull,
      "Record<string, string> must describe its value type; got $recordSchema",
    )
  }

  @Test
  fun `the extracted bundle still handles types needing no standard library`() = runBlocking {
    val analyzer = analyzerOverBundle()
    val toolsDir = tempFolder.newFolder("nostdlib-trailmap-tools")
    // These kept working through the outage, which is what made it read like a `Record` bug
    // rather than a missing standard library. Pinned so a future fix can't trade one for the other.
    File(toolsDir, "plainTools.ts").writeText(
      """
        |declare const trailblaze: { tool: <I, O>(spec: unknown) => unknown };
        |
        |interface IndexIn { attrs: { [key: string]: unknown }; }
        |interface PrimitiveIn { name: string; count: number; tags: string[]; }
        |interface Ok { ok: boolean; }
        |
        |export const indexTool = trailblaze.tool<IndexIn, Ok>({ handler: async () => ({ ok: true }) });
        |export const primitiveTool = trailblaze.tool<PrimitiveIn, Ok>({ handler: async () => ({ ok: true }) });
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)

    assertEquals(listOf("indexTool", "primitiveTool"), defs.map { it.name }.sorted())
  }
}

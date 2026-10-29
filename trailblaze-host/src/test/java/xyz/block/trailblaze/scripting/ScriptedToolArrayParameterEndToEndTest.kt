package xyz.block.trailblaze.scripting

import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.util.BunBinaryResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * From a scripted tool's TypeScript source to the parameter types the LLM is offered.
 *
 * A tool declaring `requiredItems?: string[]` or `itemPrices?: { item: string; priceCents: number }[]`
 * must reach the model as a list (of objects), not as text it then has to guess a format for: text
 * produced recordings that failed `trailblaze check` against the tool's own TypeScript types.
 * Runs the real analyzer, so it covers the TypeScript-to-schema step and the schema-to-LLM step together.
 */
class ScriptedToolArrayParameterEndToEndTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var bun: File
  private lateinit var shim: File
  private lateinit var sdkDir: File
  private lateinit var analyzer: ScriptedToolDefinitionAnalyzer

  @Before
  fun setup() {
    bun = BunBinaryResolver.resolveBunBinary() ?: File(tempFolder.root, "missing-bun")
    sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: File(tempFolder.root, "missing-sdk")
    shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir.takeIf { it.isDirectory })
      ?: File(tempFolder.root, "missing-shim")
    analyzer = ScriptedToolDefinitionAnalyzer(bunBinary = bun, extractorShim = shim, sdkDir = sdkDir)
  }

  private fun assumeAnalyzerRunnable() {
    assumeTrue("bun binary not found on PATH", bun.isFile)
    assumeTrue("extract-tool-defs.mjs not found", shim.isFile)
    assumeTrue(
      "ts-json-schema-generator not installed under ${sdkDir.absolutePath}/node_modules",
      File(sdkDir, "node_modules/ts-json-schema-generator").isDirectory,
    )
  }

  @Test
  fun `array parameters declared in TypeScript are offered to the LLM as lists`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("array-tools")
    File(toolsDir, "orderTool.ts").writeText(
      """
      |declare const trailblaze: { tool: <I, O>(spec: any, handler: (input: I) => Promise<O>) => unknown };
      |
      |type Price = { item: string; priceCents: number };
      |type Line = { name: string; modifiers?: string[] };
      |
      |interface OrderOutput { ok: boolean }
      |interface OrderInput {
      |  /** Items the order must contain. */
      |  requiredItems?: string[];
      |  itemPrices?: Price[];
      |  lines: Line[];
      |  grid?: number[][];
      |  flags?: ("A" | "B")[];
      |}
      |
      |export const orderTool = trailblaze.tool<OrderInput, OrderOutput>({}, async () => ({ ok: true }));
      """.trimMargin(),
    )

    val definition = analyzer.analyze(toolsDir).single()
    val config = InlineScriptToolConfig(
      script = "orderTool.ts",
      name = definition.name,
      description = definition.description,
      inputSchema = ScriptedToolSchemaRefFlattener.flatten(definition.inputSchemaObject),
    )
    val koog = LazyYamlScriptedToolRegistration
      .buildScriptedToolDescriptor(config)
      .toKoogToolDescriptor(strict = false)
    val types = (koog.requiredParameters + koog.optionalParameters).associate { it.name to it.type }

    assertEquals(ToolParameterType.List(ToolParameterType.String), types["requiredItems"])

    val price = assertIs<ToolParameterType.Object>(assertIs<ToolParameterType.List>(types["itemPrices"]).itemsType)
    assertEquals(
      listOf("item" to ToolParameterType.String, "priceCents" to ToolParameterType.Float),
      price.properties.map { it.name to it.type },
    )
    assertEquals(setOf("item", "priceCents"), price.requiredProperties.toSet())

    val line = assertIs<ToolParameterType.Object>(assertIs<ToolParameterType.List>(types["lines"]).itemsType)
    assertEquals(
      ToolParameterType.List(ToolParameterType.String),
      line.properties.single { it.name == "modifiers" }.type,
    )
    assertEquals(listOf("lines"), koog.requiredParameters.map { it.name })

    assertEquals(
      ToolParameterType.List(ToolParameterType.List(ToolParameterType.Float)),
      types["grid"],
    )
    assertEquals(ToolParameterType.List(ToolParameterType.Enum(arrayOf("A", "B"))), types["flags"])
  }
}

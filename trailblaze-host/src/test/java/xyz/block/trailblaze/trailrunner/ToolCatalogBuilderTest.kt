package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the scripted-tool (`.ts`) parameter-completion gap this PR closes: a custom TS tool used
 * to show up in the catalog (and therefore in the `.tool.yaml`/`.trail.yaml` schemas) as an empty,
 * un-named argument object. [ToolCatalogBuilder.discoverScriptedTools] now enriches each scripted entry
 * via [ToolCatalogBuilder.scriptedParamsByToolName] — the SAME analyzer the on-demand record editor's
 * [ToolCatalogBuilder.scriptedToolParams] already relies on, so this is wiring, not new machinery.
 */
class ToolCatalogBuilderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  // `scriptedToolParams` resolves the analyzer lazily and short-circuits on `resolveBunBinary()` /
  // `resolveSdkDir()` / the extractor shim before ever touching a real trailmap dir — mirrors the
  // assume-skip gate `ScriptedToolDefinitionAnalyzerTest` uses so this runs wherever that suite does
  // (Hermit-pinned `bun` + a `bun install`'d SDK) and skips cleanly elsewhere instead of failing red.
  private fun assumeAnalyzerRunnable() {
    val bun = BunBinaryResolver.resolveBunBinary()
    assumeTrue("bun binary not found on PATH — see ScriptedToolDefinitionAnalyzerTest.", bun != null && bun.isFile)
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
    assumeTrue("SDK dir not resolvable.", sdkDir != null && sdkDir.isDirectory)
    val shim = sdkDir?.let { ScriptedToolDefinitionAnalyzer.resolveExtractorShim(it) }
    assumeTrue("extract-tool-defs.mjs not found.", shim != null && shim.isFile)
    assumeTrue(
      "ts-json-schema-generator not installed — run `bun install` under sdks/typescript.",
      File(sdkDir, "node_modules/ts-json-schema-generator").isDirectory,
    )
  }

  @Test
  fun `scripted-tool params are empty for a trailmap with no resolvable workspace directory`() = runBlocking {
    // No fixture workspace is pinned here, so `trailmapBaseDir` can't resolve — this is exactly the
    // classpath-BUNDLED trailmap shape (source packaged into the JAR, no on-disk `tools/` dir at
    // runtime). No bun/analyzer tooling is touched: `scriptedToolParams` short-circuits on the null
    // base dir before it would even check for bun. Proves the workspace-only scoping costs nothing and
    // never regresses a bundled trailmap's existing (open, no-param-completion) behavior.
    val params = ToolCatalogBuilder.scriptedToolParams("no-such-trailmap-xyz", "someTool")
    assertEquals(emptyList(), params)
  }

  @Test
  fun `a scripted tool in a workspace trailmap gets real parameter names from the analyzer`() = runBlocking {
    assumeAnalyzerRunnable()
    val configDir = tempFolder.newFolder("config")
    val toolsDir = File(configDir, "trailmaps/demo/tools").apply { mkdirs() }
    File(toolsDir, "echoThing.ts").writeText(
      """
        |declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };
        |interface EchoThingInput {
        |  text: string;
        |  times?: number;
        |}
        |interface EchoThingOutput { out: string; }
        |
        |export const echoThing = trailblaze.tool<EchoThingInput, EchoThingOutput>({
        |  handler: async () => ({ out: "" }),
        |});
      """.trimMargin(),
    )
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      val catalog = ToolCatalogBuilder.build()
      val entry = catalog.singleOrNull { it.id == "echoThing" } ?: error("echoThing not in catalog: $catalog")
      assertEquals(ToolFlavor.SCRIPTED, entry.flavor)
      // Before this change, a scripted entry's `parameters` was always emptyList() — this is the gap
      // this PR closes: the schema-driven editors can now offer `text`/`times` completion for it.
      val byName = entry.parameters.associateBy { it.name }
      assertTrue(byName.containsKey("text"), "expected a `text` param, got ${entry.parameters}")
      assertEquals("string", byName["text"]?.type)
      assertEquals(true, byName["text"]?.required)
      assertTrue(byName.containsKey("times"), "expected a `times` param, got ${entry.parameters}")
      assertEquals(false, byName["times"]?.required, "`times?` is optional, should not be required")
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  @Test
  fun `a discriminated-union input flattens to a dropdown discriminator plus gated variant fields`() = runBlocking {
    assumeAnalyzerRunnable()
    // The configurable-trailhead shape (a signed-in entry-state tool): a union property
    // must surface as dotted params the pickers can render - `account.type` as a closed dropdown,
    // each variant's companion field gated on the discriminator value, flat params untouched.
    // Named union types arrive from the generator as `${'$'}ref` + definitions, so this also pins
    // the local-ref resolution.
    val configDir = tempFolder.newFolder("config")
    val toolsDir = File(configDir, "trailmaps/demo/tools").apply { mkdirs() }
    File(toolsDir, "signedIn.ts").writeText(
      """
        |declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };
        |type Account =
        |  | { type: "pool"; serviceId?: string }
        |  | { type: "existing"; email: string }
        |  | { type: "newUser" };
        |interface SignedInInput {
        |  account: Account;
        |  startingClientRoute?: string;
        |}
        |interface SignedInOutput { out: string; }
        |
        |export const signedIn = trailblaze.tool<SignedInInput, SignedInOutput>({
        |  handler: async () => ({ out: "" }),
        |});
      """.trimMargin(),
    )
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      val params = ToolCatalogBuilder.scriptedToolParams("demo", "signedIn")
      val byName = params.associateBy { it.name }
      assertTrue("account" !in byName, "the union parent must flatten away, got ${params.map { it.name }}")

      val disc = byName.getValue("account.type")
      assertEquals(listOf("pool", "existing", "newUser"), disc.validValues)
      assertEquals(true, disc.required)
      assertEquals(null, disc.visibleWhen)

      val serviceId = byName.getValue("account.serviceId")
      assertEquals("account.type", serviceId.visibleWhen?.parameterName)
      assertEquals(listOf("pool"), serviceId.visibleWhen?.values)
      assertEquals(false, serviceId.required, "serviceId is optional within its variant")

      val email = byName.getValue("account.email")
      assertEquals(listOf("existing"), email.visibleWhen?.values)
      assertEquals(true, email.required, "email is required while its variant is selected")

      val route = byName.getValue("startingClientRoute")
      assertEquals(null, route.visibleWhen)
      assertEquals(false, route.required)
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  @Test
  fun `one broken tool file does not blank out params for other tools in the same trailmap`() = runBlocking {
    assumeAnalyzerRunnable()
    // Regression test for the real bug this fix uncovered: the live `myapp` trailmap has 53 `.ts`
    // files, 2 of which the analyzer can't parse — and the ORIGINAL scriptedToolParams caught
    // ScriptedToolDefinitionException generically and discarded it to emptyList(), so the other 51
    // clean tools (including myapp_addItemToCart) silently lost their param completion too. Proves
    // the fix: a directory with one broken + one clean tool still yields params for the clean one.
    val configDir = tempFolder.newFolder("config-partial")
    val toolsDir = File(configDir, "trailmaps/demo2/tools").apply { mkdirs() }
    val stub = "declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };"
    // A function-typed field has no JSON Schema equivalent — a reliable, deterministic per-tool error
    // (see ScriptedToolDefinitionAnalyzerTest's identical fixture for the analyzer-level version of
    // this contract).
    File(toolsDir, "brokenTool.ts").writeText(
      """
        |$stub
        |interface BrokenInput { callback: (x: number) => string; }
        |interface BrokenOutput { ok: boolean; }
        |export const brokenTool = trailblaze.tool<BrokenInput, BrokenOutput>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )
    File(toolsDir, "cleanTool.ts").writeText(
      """
        |$stub
        |interface CleanInput { label: string; }
        |interface CleanOutput { ok: boolean; }
        |export const cleanTool = trailblaze.tool<CleanInput, CleanOutput>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      val params = ToolCatalogBuilder.scriptedToolParams("demo2", "cleanTool")
      assertTrue(
        params.any { it.name == "label" },
        "expected cleanTool's `label` param to survive brokenTool's analyzer error, got $params",
      )
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  @Test
  fun `an edited tool's new params are picked up on the next call, not a stale in-memory result`() = runBlocking {
    assumeAnalyzerRunnable()
    // A review comment flagged that an earlier version of this fix (a permanent JVM-lifetime cache
    // keyed by tools-dir path) would go stale the moment a tool's source changed while the daemon kept
    // running: the LSP schema routes rebuild the catalog fresh per editor-open, so a user editing a
    // tool's params and reopening the editor would still see the OLD params. Proves the fix: rename a
    // param and call scriptedToolParams again — the new name must be reflected, not the old one.
    val configDir = tempFolder.newFolder("config-stale")
    val toolsDir = File(configDir, "trailmaps/demo3/tools").apply { mkdirs() }
    val stub = "declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };"
    val toolFile = File(toolsDir, "renamedParamTool.ts")
    fun writeVersion(paramName: String) {
      toolFile.writeText(
        """
          |$stub
          |interface RenamedParamInput { $paramName: string; }
          |interface RenamedParamOutput { ok: boolean; }
          |export const renamedParamTool = trailblaze.tool<RenamedParamInput, RenamedParamOutput>({
          |  handler: async () => ({ ok: true }),
          |});
        """.trimMargin(),
      )
    }
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      writeVersion("oldName")
      val before = ToolCatalogBuilder.scriptedToolParams("demo3", "renamedParamTool")
      assertTrue(before.any { it.name == "oldName" }, "expected `oldName` before the edit, got $before")

      writeVersion("newName")
      val after = ToolCatalogBuilder.scriptedToolParams("demo3", "renamedParamTool")
      assertTrue(after.any { it.name == "newName" }, "expected `newName` after the edit, got $after")
      assertTrue(after.none { it.name == "oldName" }, "expected `oldName` to be gone after the edit, got $after")
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  @Test
  fun `a resolvable tools directory with zero ts files yields empty params, not an error`() = runBlocking {
    assumeAnalyzerRunnable()
    // Distinct from the "no resolvable directory" case above: here the directory genuinely EXISTS
    // (e.g. a freshly-scaffolded trailmap before any tool is authored) but is empty.
    val configDir = tempFolder.newFolder("config-empty")
    File(configDir, "trailmaps/demo4/tools").mkdirs()
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      val params = ToolCatalogBuilder.scriptedToolParams("demo4", "anyTool")
      assertEquals(emptyList(), params)
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  @Test
  fun `two trailmaps in the same build get their own correctly-scoped params, no cross-contamination`() = runBlocking {
    assumeAnalyzerRunnable()
    // Proves discoverScriptedTools's group-by-trailmap enrichment doesn't mix up which trailmap's
    // params apply to which entry — each trailmap here declares a same-shaped-but-differently-named
    // param, so a mix-up would show up as the WRONG param name on the WRONG trailmap's entry.
    val configDir = tempFolder.newFolder("config-two-trailmaps")
    val stub = "declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };"
    File(configDir, "trailmaps/alpha/tools").mkdirs()
    File(configDir, "trailmaps/alpha/tools/alphaTool.ts").writeText(
      """
        |$stub
        |interface AlphaInput { alphaOnlyField: string; }
        |interface AlphaOutput { ok: boolean; }
        |export const alphaTool = trailblaze.tool<AlphaInput, AlphaOutput>({ handler: async () => ({ ok: true }) });
      """.trimMargin(),
    )
    File(configDir, "trailmaps/beta/tools").mkdirs()
    File(configDir, "trailmaps/beta/tools/betaTool.ts").writeText(
      """
        |$stub
        |interface BetaInput { betaOnlyField: string; }
        |interface BetaOutput { ok: boolean; }
        |export const betaTool = trailblaze.tool<BetaInput, BetaOutput>({ handler: async () => ({ ok: true }) });
      """.trimMargin(),
    )
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      val catalog = ToolCatalogBuilder.build()
      val alpha = catalog.single { it.id == "alphaTool" }
      val beta = catalog.single { it.id == "betaTool" }
      assertTrue(alpha.parameters.any { it.name == "alphaOnlyField" }, "alpha missing its own param: ${alpha.parameters}")
      assertTrue(alpha.parameters.none { it.name == "betaOnlyField" }, "alpha got beta's param: ${alpha.parameters}")
      assertTrue(beta.parameters.any { it.name == "betaOnlyField" }, "beta missing its own param: ${beta.parameters}")
      assertTrue(beta.parameters.none { it.name == "alphaOnlyField" }, "beta got alpha's param: ${beta.parameters}")
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }
}

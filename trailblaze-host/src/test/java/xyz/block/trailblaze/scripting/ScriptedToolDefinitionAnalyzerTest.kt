package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Rule-branch tests for [ScriptedToolDefinitionAnalyzer]. Each test exercises one of
 * the documented contract points: type extraction, TSDoc capture, supported TS
 * subset, unsupported-construct errors, and multi-file/multi-tool trailmaps.
 *
 * Every test calls [assumeAnalyzerRunnable] first — without `node` on PATH AND
 * `sdks/typescript/node_modules/ts-json-schema-generator` installed, the analyzer
 * can't produce a verdict. On a fresh checkout that ran `bun install` (or `npm
 * install`) once under `sdks/typescript`, every test runs; on hosts without
 * either dependency they assume-skip cleanly. Mirrors the same skip pattern
 * [ScriptedToolImportAnalyzerTest] uses.
 *
 * Fixture `.ts` files use a `declare const trailblaze: { tool: <I, O>(...) => ... }`
 * stub so we don't need `@trailblaze/scripting` resolvable from the temp directory
 * — the shim's AST-walk only looks at the call site's syntactic shape (method name
 * + type arguments) and `ts-json-schema-generator` runs with `skipTypeCheck: true`
 * so unresolved import paths don't blow up the schema walk.
 */
class ScriptedToolDefinitionAnalyzerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var bun: File
  private lateinit var shim: File
  private lateinit var sdkDir: File
  private lateinit var analyzer: ScriptedToolDefinitionAnalyzer

  @Before
  fun setup() {
    bun = ScriptedToolDefinitionAnalyzer.resolveBunBinary() ?: File(tempFolder.root, "missing-bun")
    sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: File(tempFolder.root, "missing-sdk")
    shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir.takeIf { it.isDirectory })
      ?: File(tempFolder.root, "missing-shim")
    analyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
    )
  }

  private fun assumeAnalyzerRunnable() {
    assumeTrue(
      "bun binary not found on PATH — install bun (`brew install bun`, or see https://bun.sh/) to run this test.",
      bun.isFile,
    )
    assumeTrue(
      "extract-tool-defs.mjs not found — must live at sdks/typescript/tools/extract-tool-defs.mjs.",
      shim.isFile,
    )
    val tsjsg = File(sdkDir, "node_modules/ts-json-schema-generator")
    assumeTrue(
      "ts-json-schema-generator not installed under ${sdkDir.absolutePath}/node_modules — " +
        "run `npm install` (or `bun install`) in sdks/typescript to enable this test.",
      tsjsg.isDirectory,
    )
  }

  @Test
  fun `single tool with simple primitives produces input and output schemas`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("simple-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "echoTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface EchoInput {
        |  text: string;
        |  times: number;
        |}
        |interface EchoOutput {
        |  out: string;
        |}
        |
        |export const echoTool = trailblaze.tool<EchoInput, EchoOutput>({
        |  handler: async () => ({ out: "" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(1, defs.size)
    val def = defs.single()
    assertEquals("echoTool", def.name)
    val inputProps = def.inputSchemaObject["properties"]?.jsonObject ?: error("missing props")
    assertEquals("string", inputProps["text"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals("number", inputProps["times"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    // Both fields required (neither has `?`).
    val required = def.inputSchemaObject["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
    assertEquals(setOf("text", "times"), required)
  }

  @Test
  fun `optional fields are not included in required list`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("optional-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "optionalTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface OptArgs {
        |  required: string;
        |  optional?: number;
        |}
        |interface OptOut { ok: boolean; }
        |
        |export const optionalTool = trailblaze.tool<OptArgs, OptOut>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val required = def.inputSchemaObject["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
    assertEquals(setOf("required"), required, "only the non-? field should be required")
  }

  @Test
  fun `array field round-trips as array type with items schema`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("array-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "arrayTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface ArrInput {
        |  tags: string[];
        |}
        |interface ArrOut { count: number; }
        |
        |export const arrayTool = trailblaze.tool<ArrInput, ArrOut>({
        |  handler: async () => ({ count: 0 }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val tags = def.inputSchemaObject["properties"]?.jsonObject?.get("tags")?.jsonObject
      ?: fail("expected 'tags' property")
    assertEquals("array", tags["type"]?.jsonPrimitive?.content)
    assertEquals("string", tags["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
  }

  @Test
  fun `nested object field round-trips as object schema with nested properties`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("nested-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "nestedTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface NestedInput {
        |  user: {
        |    id: string;
        |    age: number;
        |  };
        |}
        |interface NestedOut { ok: boolean; }
        |
        |export const nestedTool = trailblaze.tool<NestedInput, NestedOut>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val user = def.inputSchemaObject["properties"]?.jsonObject?.get("user")?.jsonObject
      ?: fail("expected 'user' property")
    assertEquals("object", user["type"]?.jsonPrimitive?.content)
    val userProps = user["properties"]?.jsonObject ?: fail("expected nested 'properties'")
    assertEquals("string", userProps["id"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals("number", userProps["age"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
  }

  @Test
  fun `discriminated union output produces oneOf or anyOf schema with all variants`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("union-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "unionTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface UnionInput { q: string; }
        |type UnionOutput =
        |  | { kind: "ok"; value: string }
        |  | { kind: "err"; error: string };
        |
        |export const unionTool = trailblaze.tool<UnionInput, UnionOutput>({
        |  handler: async () => ({ kind: "ok", value: "v" }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    // ts-json-schema-generator emits `anyOf` for discriminated unions (or
    // `oneOf` for closed ones). Either is valid — pin only that the variants
    // are exposed.
    val branches: JsonArray = def.outputSchemaObject["anyOf"]?.jsonArray
      ?: def.outputSchemaObject["oneOf"]?.jsonArray
      ?: fail("expected anyOf/oneOf on discriminated-union output, got: ${def.outputSchemaObject}")
    assertEquals(2, branches.size)
    val kinds = branches.mapNotNull { branch ->
      val props = (branch as? JsonObject)?.get("properties")?.jsonObject ?: return@mapNotNull null
      val kind = props["kind"]?.jsonObject
      (kind?.get("const") as? JsonPrimitive)?.contentOrNull
        ?: (kind?.get("enum") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }.toSet()
    assertEquals(setOf("ok", "err"), kinds, "expected both discriminator literals to surface")
  }

  @Test
  fun `tsdoc descriptions flow through on interface, fields, and exported const`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("tsdoc-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "documentedTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |/** TSDoc on the input interface. */
        |interface DocInput {
        |  /** TSDoc on the input field. */
        |  foo: string;
        |}
        |
        |/** TSDoc on the output interface. */
        |interface DocOutput {
        |  /** TSDoc on the output field. */
        |  bar: string;
        |}
        |
        |/** TSDoc on the exported const. */
        |export const documentedTool = trailblaze.tool<DocInput, DocOutput>({
        |  handler: async () => ({ bar: "b" }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    assertEquals("TSDoc on the exported const.", def.description)
    assertEquals(
      "TSDoc on the input interface.",
      def.inputSchemaObject["description"]?.jsonPrimitive?.contentOrNull,
    )
    assertEquals(
      "TSDoc on the input field.",
      def.inputSchemaObject["properties"]?.jsonObject?.get("foo")?.jsonObject
        ?.get("description")?.jsonPrimitive?.contentOrNull,
    )
    assertEquals(
      "TSDoc on the output interface.",
      def.outputSchemaObject["description"]?.jsonPrimitive?.contentOrNull,
    )
    assertEquals(
      "TSDoc on the output field.",
      def.outputSchemaObject["properties"]?.jsonObject?.get("bar")?.jsonObject
        ?.get("description")?.jsonPrimitive?.contentOrNull,
    )
  }

  @Test
  fun `unsupported TS construct (function-typed field) raises descriptive error pointing at the file and tool`() = runBlocking {
    assumeAnalyzerRunnable()
    // Function types are explicitly listed as unsupported in the analyzer's
    // contract — they have no JSON Schema equivalent. The shim runs the generator
    // with `functions: "fail"` so a function-typed field raises rather than
    // silently emitting a `$comment` placeholder that the LLM would see as
    // structured input. `Date` is intentionally NOT used here even though the
    // docs originally hand-waved it as "unsupported"; ts-json-schema-generator
    // actually round-trips `Date` to `{ type: "string", format: "date-time" }`
    // out of the box, so it'd be a misleading test fixture.
    val toolsDir = tempFolder.newFolder("fn-field-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "fnFieldTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface FnInput {
        |  callback: (x: number) => string;
        |}
        |interface FnOutput { ok: boolean; }
        |
        |export const fnFieldTool = trailblaze.tool<FnInput, FnOutput>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    try {
      analyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for function-typed field")
    } catch (e: ScriptedToolDefinitionException) {
      assertTrue(e.errors.isNotEmpty(), "expected at least one per-tool error")
      val err = e.errors.first()
      assertEquals("fnFieldTool", err.toolName)
      assertTrue(
        err.file.endsWith("fnFieldTool.ts"),
        "expected error to name the offending file; got file=${err.file}",
      )
      // Don't pin the exact wording (generator versions change it) but do require
      // the message names either the input type or the function-kind so the
      // author has a breadcrumb back to the offending field. ts-json-schema-
      // generator emits `Unknown node of kind "FunctionType"` for this case
      // under `functions: "fail"`.
      assertTrue(
        err.message.contains("FnInput") || err.message.contains("Function"),
        "expected error message to mention the input type or function type; got: ${err.message}",
      )
    }
  }

  @Test
  fun `multiple tools in one file are all discovered`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("multi-tool-file-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "multiTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface AIn { a: string; }
        |interface AOut { ok: boolean; }
        |interface BIn { b: number; }
        |interface BOut { count: number; }
        |
        |export const firstTool = trailblaze.tool<AIn, AOut>({
        |  handler: async () => ({ ok: true }),
        |});
        |
        |export const secondTool = trailblaze.tool<BIn, BOut>({
        |  handler: async () => ({ count: 0 }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(2, defs.size)
    assertEquals(setOf("firstTool", "secondTool"), defs.map { it.name }.toSet())
  }

  @Test
  fun `multiple files each with one tool are all discovered`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("multi-file-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "fileA.ts",
      """
        |${declareTrailblazeStub()}
        |interface AIn { x: string; }
        |interface AOut { y: string; }
        |export const toolA = trailblaze.tool<AIn, AOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    writeTsFixture(
      toolsDir,
      "fileB.ts",
      """
        |${declareTrailblazeStub()}
        |interface BIn { p: number; }
        |interface BOut { q: number; }
        |export const toolB = trailblaze.tool<BIn, BOut>({
        |  handler: async () => ({ q: 0 }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(setOf("toolA", "toolB"), defs.map { it.name }.toSet())
    // sourcePath captures the file the export lived in so debug output can name
    // the originating tool file unambiguously.
    val byName = defs.associateBy { it.name }
    assertTrue(byName.getValue("toolA").sourcePath.endsWith("fileA.ts"))
    assertTrue(byName.getValue("toolB").sourcePath.endsWith("fileB.ts"))
  }

  @Test
  fun `empty trailmap returns an empty list`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("empty-trailmap-tools")
    // Intentionally write nothing — empty tools dir is a valid trailmap shape (library
    // trailmaps that contribute only via tool_sets or have no scripted tools).
    val defs = analyzer.analyze(toolsDir)
    assertTrue(defs.isEmpty(), "expected empty analyzer result for empty tools dir")
  }

  @Test
  fun `nonexistent tools dir returns empty list`() = runBlocking {
    assumeAnalyzerRunnable()
    val nonexistent = File(tempFolder.root, "no-such-tools")
    val defs = analyzer.analyze(nonexistent)
    assertTrue(defs.isEmpty(), "expected empty analyzer result for missing tools dir")
  }

  @Test
  fun `test files and declaration files are skipped`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("filtered-trailmap-tools")
    // A real tool that should be picked up.
    writeTsFixture(
      toolsDir,
      "realTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface RIn { x: string; }
        |interface ROut { y: string; }
        |export const realTool = trailblaze.tool<RIn, ROut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    // A `*.test.ts` companion that, if walked, would produce a spurious second tool.
    writeTsFixture(
      toolsDir,
      "realTool.test.ts",
      """
        |${declareTrailblazeStub()}
        |interface TIn { x: string; }
        |interface TOut { y: string; }
        |export const ghostTestTool = trailblaze.tool<TIn, TOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    // A `*.d.ts` declaration that should also be ignored — typing-only files
    // don't carry runnable tool declarations.
    writeTsFixture(
      toolsDir,
      "ambient.d.ts",
      """
        |${declareTrailblazeStub()}
        |interface AIn { x: string; }
        |interface AOut { y: string; }
        |export const ghostDtsTool = trailblaze.tool<AIn, AOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(setOf("realTool"), defs.map { it.name }.toSet())
  }

  @Test
  fun `analyze returns empty when bun binary is missing`() = runBlocking {
    val bunMissing = ScriptedToolDefinitionAnalyzer(
      bunBinary = File(tempFolder.root, "definitely-missing-bun"),
      extractorShim = shim,
      sdkDir = sdkDir,
    )
    val toolsDir = tempFolder.newFolder("missing-bun-trailmap-tools")
    writeTsFixture(toolsDir, "any.ts", "// empty fixture")
    val defs = bunMissing.analyze(toolsDir)
    assertTrue(defs.isEmpty())
  }

  @Test
  fun `analyze returns empty when extractor shim is missing`() = runBlocking {
    val shimMissing = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = File(tempFolder.root, "definitely-missing-shim.mjs"),
      sdkDir = sdkDir,
    )
    val toolsDir = tempFolder.newFolder("missing-shim-trailmap-tools")
    writeTsFixture(toolsDir, "any.ts", "// empty fixture")
    val defs = shimMissing.analyze(toolsDir)
    assertTrue(defs.isEmpty())
  }

  // ── resolveBunBinary — bun-only PATH walk ──────────────────────────────────
  //
  // Trailblaze uses bun as its sole JS runtime — no Node fallback. These
  // tests pin the resolver against an explicit PATH string so they don't
  // depend on the runner's actual env. The production no-arg overload reads
  // `System.getenv("PATH")` and delegates to the internal overload below.
  //
  // **Why no Node anywhere here.** The contract is "install bun; nothing
  // else is required for Trailblaze." A separate Node install must NOT be
  // picked up — both because it would mask a missing-bun environment on
  // hosts that happen to have only Node, and because we want the failure
  // mode to be the directed "install bun" diagnostic rather than a
  // potentially-surprising fallback.

  /**
   * Plant a fake executable script named [name] in [dir] so the resolver under
   * test sees a binary that passes both `exists()` and `canExecute()` without
   * having to actually run real bun/Node. Used across the [resolveBunBinary]
   * test block; the body is a sh stub that prints a marker, never executed by
   * production code paths.
   */
  private fun planFakeExecutable(dir: File, name: String): File =
    File(dir, name).apply {
      writeText("#!/bin/sh\necho 'fake $name'\n")
      setExecutable(true)
    }

  @Test
  fun `resolveBunBinary returns the bun binary when bun is on PATH`() {
    // Happy path — bun installed (via `brew install bun` in setup.sh on
    // Runway CI agents, the equivalent on any other host). Pre-fix this
    // case made `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()`
    // return null and every meta-only / partial-descriptor trailmap failed
    // to load.
    val bunDir = tempFolder.newFolder("bun-dir")
    val bun = planFakeExecutable(bunDir, "bun")
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = bunDir.absolutePath)
    assertEquals(bun, resolved, "expected bun to be returned")
  }

  @Test
  fun `resolveBunBinary returns null when only node is on PATH and bun is missing`() {
    // Explicit "no Node fallback" pin. A host that only has Node installed
    // (e.g. legacy developer machine, partial install) must surface the
    // missing-bun diagnostic — silently using Node would let the analyzer
    // run with subtle bun-vs-node behavior differences we've never tested
    // against, contradicting the "install bun, everything just works"
    // contract.
    val nodeOnlyDir = tempFolder.newFolder("node-only-dir")
    planFakeExecutable(nodeOnlyDir, "node")
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = nodeOnlyDir.absolutePath)
    assertNull(
      resolved,
      "node must NOT be accepted as a JS runtime — Trailblaze requires bun. " +
        "A node-only host should surface the 'install bun' diagnostic.",
    )
  }

  @Test
  fun `resolveBunBinary picks bun from a later PATH dir`() {
    // PATH iteration coverage — bun isn't always in the first dir. The
    // resolver must walk every dir in PATH, not just the head. A regression
    // that broke into the loop after dir[0] would fail this case while still
    // passing the single-dir happy-path test above.
    val emptyFirstDir = tempFolder.newFolder("empty-first")
    val bunLaterDir = tempFolder.newFolder("bun-later")
    val bun = planFakeExecutable(bunLaterDir, "bun")
    val combinedPath = emptyFirstDir.absolutePath + File.pathSeparator + bunLaterDir.absolutePath
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = combinedPath)
    assertEquals(bun, resolved)
  }

  @Test
  fun `resolveBunBinary returns null when bun is not on PATH`() {
    // The "no JS runtime available" terminal state — production callers
    // downgrade gracefully via `AnalyzerScriptedToolEnrichment
    // .resolveFromEnvironment()` returning null and emitting the
    // "analyzer unavailable" diagnostic. Pins the resolver's null return
    // rather than throwing or returning a stale candidate.
    val emptyDir = tempFolder.newFolder("empty-dir")
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = emptyDir.absolutePath)
    assertNull(resolved)
  }

  @Test
  fun `resolveBunBinary returns null when PATH env is null`() {
    // Defensive — `System.getenv("PATH")` can legitimately return null in
    // sandboxed or stripped-env scenarios. Without the null guard the
    // delegate would crash; with it, every downstream call site sees the
    // same "no runtime" return as the empty-PATH case above.
    assertNull(ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = null))
  }

  @Test
  fun `resolveBunBinary returns null when PATH contains only separators`() {
    // Defensive — PATH like `":"` (Unix) or `";"` (Windows) splits into
    // empty-string segments. The resolver filters them via
    // `.filter { it.isNotBlank() }`, but no test pinned that contract —
    // a regression that dropped the filter would let `File("", "bun")`
    // try to open `./bun` in the JVM's cwd, which could surprise on a
    // host that happens to have a `bun` file in CWD. Same null-return
    // shape as the empty-PATH case above, but exercises the blank-segment
    // filter explicitly. (Lead-review v1 finding #3.)
    assertNull(ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathEnv = File.pathSeparator))
    assertNull(
      ScriptedToolDefinitionAnalyzer.resolveBunBinary(
        pathEnv = File.pathSeparator + File.pathSeparator,
      ),
    )
  }

  // ── resolveBunViaWalkup — repo hermit `bin/bun` fallback ────────────────────
  //
  // The fresh-daemon fix. The `./trailblaze` wrapper spawns the daemon JVM with
  // the calling shell's PATH; on a host that already has JDK 21 the wrapper never
  // sourced `bin/activate-hermit`, so `bun` was absent from the daemon's PATH and
  // every meta-only / TS scripted-tool descriptor silently failed to enrich,
  // breaking a target's TypeScript launch-step tools that its launch orchestrator
  // composes by name.
  // The hermit `bin/bun` symlink is committed to the repo, so walking up from CWD
  // resolves it regardless of how the daemon was launched. These tests pin the
  // walk-up against an injected start dir (no dependency on the real repo layout).

  // The walk-up is gated on the committed `bin/activate-hermit` marker so it only ever
  // executes *this repo's* Hermit-pinned `bin/bun`, never a coincidental `bin/bun` in some
  // ancestor of CWD. The helper plants both the marker and an executable `bin/bun`.
  private fun planHermitBin(repoRoot: File): File {
    val bin = File(repoRoot, "bin").apply { mkdirs() }
    File(bin, "activate-hermit").writeText("#!/bin/sh\n# hermit activation marker\n")
    return planFakeExecutable(bin, "bun")
  }

  @Test
  fun `resolveBunViaWalkup finds bun at repo bin from the repo root`() {
    val repoRoot = tempFolder.newFolder("walkup-repo-root")
    val bun = planHermitBin(repoRoot)
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunViaWalkup(repoRoot)
    assertEquals(bun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunViaWalkup finds bun at repo bin from a deeply nested start dir`() {
    // Mirrors the real-world daemon scenario: CWD is the repo root (or any dir
    // under it) while the walk climbs ancestors to find the committed `bin/bun`.
    val repoRoot = tempFolder.newFolder("walkup-nested-repo")
    val bun = planHermitBin(repoRoot)
    val deeplyNested =
      File(repoRoot, "module/src/main/resources/trails/config/trailmaps/foo/tools").apply { mkdirs() }
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunViaWalkup(deeplyNested)
    assertEquals(bun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunViaWalkup returns null when no repo bin bun exists up the tree`() {
    val isolated = tempFolder.newFolder("walkup-no-bun")
    val nested = File(isolated, "some/empty/tree").apply { mkdirs() }
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunViaWalkup(nested)
    assertNull(
      resolved,
      "expected null when no bin/bun is present up the tree; got ${resolved?.absolutePath}",
    )
  }

  @Test
  fun `resolveBunViaWalkup ignores a non-executable bin bun`() {
    // Matches the PATH half's canExecute() guard — a permission-stripped or
    // half-extracted symlink target must read as "not found" rather than handing
    // back a binary the analyzer subprocess can't actually launch.
    val repoRoot = tempFolder.newFolder("walkup-nonexec-bun")
    val bin = File(repoRoot, "bin").apply { mkdirs() }
    File(bin, "activate-hermit").writeText("#!/bin/sh\n") // present, so we reach the canExecute gate
    File(bin, "bun").apply {
      writeText("#!/bin/sh\necho nope\n")
      // Deliberately do NOT set executable.
    }
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunViaWalkup(repoRoot)
    assertNull(resolved, "expected null for a non-executable bin/bun; got ${resolved?.absolutePath}")
  }

  @Test
  fun `resolveBunViaWalkup ignores a bin bun without the hermit activation marker`() {
    // Security gate (Codex review on #3929): an untrusted ancestor that carries an
    // executable `bin/bun` but is NOT a Hermit-managed repo (no `bin/activate-hermit`)
    // must be ignored, so the analyzer never executes an arbitrary project-local binary.
    val untrusted = tempFolder.newFolder("walkup-no-hermit-marker")
    val bin = File(untrusted, "bin").apply { mkdirs() }
    planFakeExecutable(bin, "bun") // executable bun, but no activate-hermit marker beside it
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunViaWalkup(untrusted)
    assertNull(
      resolved,
      "expected null for a bin/bun without the hermit activation marker; got ${resolved?.absolutePath}",
    )
  }

  // ── resolveBunBinary(pathEnv, startDir) — the production composition ─────────
  //
  // The no-arg resolveBunBinary() feeds live PATH + CWD into this composition. These pin the
  // PATH-first-then-walk-up behaviour (the actual production entry point used by the daemon's
  // scripted-tool enrichment) without mutating process env.

  @Test
  fun `resolveBunBinary composition prefers a PATH bun over the repo walk-up`() {
    val pathDir = tempFolder.newFolder("compose-path-bun")
    val pathBun = planFakeExecutable(pathDir, "bun")
    // startDir is a valid hermit repo too, but the bun-only contract resolves PATH first.
    val repoRoot = tempFolder.newFolder("compose-repo-shadowed")
    planHermitBin(repoRoot)
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(pathDir.absolutePath, repoRoot)
    assertEquals(pathBun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunBinary composition falls through to the repo walk-up when PATH has no bun`() {
    // The JDK-21 fresh-daemon case: hermit never activated, so PATH carries no bun and the
    // committed repo `bin/bun` must be found via the walk-up.
    val repoRoot = tempFolder.newFolder("compose-fallback-repo")
    val walkupBun = planHermitBin(repoRoot)
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(null, repoRoot)
    assertEquals(walkupBun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunBinary composition returns null when neither PATH nor the walk-up resolves bun`() {
    val isolated = tempFolder.newFolder("compose-none")
    val startDir = File(isolated, "deep/dir").apply { mkdirs() }
    val resolved = ScriptedToolDefinitionAnalyzer.resolveBunBinary(null, startDir)
    assertNull(resolved, "expected null when neither PATH nor the repo walk-up resolves bun")
  }

  @Test
  fun `line number on the export const is captured for error reporting`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("line-num-trailmap-tools")
    // Three blank lines + interfaces — the `export const` sits on line 11.
    writeTsFixture(
      toolsDir,
      "lineNumTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |
        |
        |
        |
        |export const lineNumTool = trailblaze.tool<I, O>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    val def = analyzer.analyze(toolsDir).single()
    assertEquals(11, def.line, "expected line 11 (the `export const ...` line)")
  }

  @Test
  fun `Date round-trips as string with date-time format`() = runBlocking {
    assumeAnalyzerRunnable()
    // ts-json-schema-generator natively converts `Date` to a string-with-format
    // shape. Pinning this in tests is a forcing function — if a future generator
    // version changes the conversion (or stops supporting `Date` entirely), this
    // test surfaces it loudly so we revisit the kdoc claim that `Date` is part
    // of the supported subset.
    val toolsDir = tempFolder.newFolder("date-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "dateTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface DateInput {
        |  when: Date;
        |}
        |interface DateOutput { ok: boolean; }
        |
        |export const dateTool = trailblaze.tool<DateInput, DateOutput>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val whenField = def.inputSchemaObject["properties"]?.jsonObject?.get("when")?.jsonObject
      ?: fail("expected 'when' property")
    assertEquals("string", whenField["type"]?.jsonPrimitive?.content)
    assertEquals("date-time", whenField["format"]?.jsonPrimitive?.content)
  }

  @Test
  fun `Record-of-string-to-primitive round-trips as object schema with additionalProperties`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("record-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "recordTool.ts",
      """
        |${declareTrailblazeStub()}
        |
        |interface RecordIn {
        |  attrs: Record<string, number>;
        |}
        |interface RecordOut { ok: boolean; }
        |
        |export const recordTool = trailblaze.tool<RecordIn, RecordOut>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val attrs = def.inputSchemaObject["properties"]?.jsonObject?.get("attrs")?.jsonObject
      ?: fail("expected 'attrs' property")
    // ts-json-schema-generator emits `Record<string, T>` as a `$ref` into a
    // sibling `definitions` entry — pin both the indirection and the underlying
    // `{ type: "object", additionalProperties: {<T-schema>} }` shape so a
    // future generator change that altered either part surfaces here.
    val ref = attrs["${'$'}ref"]?.jsonPrimitive?.contentOrNull
      ?: fail("expected a \$ref under attrs for Record<string, number>, got: $attrs")
    val refName = ref.substringAfter("#/definitions/").let { java.net.URLDecoder.decode(it, "UTF-8") }
    val defs = def.inputSchemaObject["definitions"]?.jsonObject
      ?: fail("expected a definitions bag on the input schema")
    val recordSchema = defs[refName]?.jsonObject
      ?: fail("expected definitions['$refName']; got keys=${defs.keys}")
    assertEquals("object", recordSchema["type"]?.jsonPrimitive?.content)
    val ap = recordSchema["additionalProperties"]?.jsonObject
      ?: fail("expected additionalProperties on Record<string, number> shape, got: $recordSchema")
    assertEquals("number", ap["type"]?.jsonPrimitive?.content)
  }

  @Test
  fun `destructured export pattern is rejected with a descriptive error`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("destructure-trailmap-tools")
    // Trailblaze.tool returns an opaque registration handle, not a destructurable
    // value — but a TypeScript author might still write this pattern by mistake.
    // The analyzer's contract is `export const <name> = trailblaze.tool<...>(...)`,
    // so a destructured BindingPattern on the left-hand side must surface a
    // diagnostic rather than silently vanishing from the typed surface. Stubbing
    // the runtime as returning `any` keeps the fixture compilable without
    // forcing the test to pull in the full SDK.
    writeTsFixture(
      toolsDir,
      "destructured.ts",
      """
        |declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => any };
        |interface DIn { x: string; }
        |interface DOut { y: string; }
        |
        |export const { foo } = trailblaze.tool<DIn, DOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    try {
      analyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for destructured export pattern")
    } catch (e: ScriptedToolDefinitionException) {
      val err = e.errors.firstOrNull { it.message.contains("destructuring") }
        ?: fail("expected a destructuring error in errors; got: ${e.errors}")
      assertTrue(
        err.file.endsWith("destructured.ts"),
        "expected error to name the offending file; got file=${err.file}",
      )
      assertNull(
        err.toolName,
        "expected toolName to be null (the analyzer couldn't derive a name from the destructured binding)",
      )
    }
  }

  @Test
  fun `inline type literal in type parameter is rejected with a descriptive error`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("inline-literal-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "inlineLiteral.ts",
      """
        |${declareTrailblazeStub()}
        |
        |// Inline type literals (`{ foo: string }` directly inside `<>`) are
        |// rejected because the LLM-facing description lives on the *named*
        |// interface and an anonymous literal would silently drop it.
        |export const inlineLiteral = trailblaze.tool<{ foo: string }, { bar: number }>({
        |  handler: async () => ({ bar: 0 }),
        |});
      """.trimMargin(),
    )

    try {
      analyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for inline type literal")
    } catch (e: ScriptedToolDefinitionException) {
      assertTrue(e.errors.isNotEmpty())
      val err = e.errors.first()
      assertEquals("inlineLiteral", err.toolName)
      assertTrue(
        err.message.contains("named references") || err.message.contains("Inline type literals"),
        "expected error message to call out the named-reference requirement; got: ${err.message}",
      )
    }
  }

  @Test
  fun `three or more type arguments are rejected as a likely refactor leftover`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("three-arg-trailmap-tools")
    // A refactor that means to drop an old type parameter can leave behind a
    // surplus arg. Without an error, the analyzer would silently pick the
    // first two and discard the rest — invisible to the author. Pin the
    // error path.
    writeTsFixture(
      toolsDir,
      "threeArgs.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |interface AIn { x: string; }
        |interface AOut { y: string; }
        |interface ALeftover { z: string; }
        |
        |export const tripleArgTool = trailblaze.tool<AIn, AOut, ALeftover>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    try {
      analyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for surplus type arguments")
    } catch (e: ScriptedToolDefinitionException) {
      val err = e.errors.firstOrNull { it.toolName == "tripleArgTool" }
        ?: fail("expected a tripleArgTool error; got: ${e.errors}")
      assertTrue(
        err.message.contains("got 3") || err.message.contains("Drop the extra"),
        "expected error to flag the surplus arg count; got: ${err.message}",
      )
    }
  }

  @Test
  fun `single type argument defaults result to string`() = runBlocking {
    // **Behavior change (PR #3338 ergonomics):** Was previously a hard error to nudge
    // authors toward `<MyInput, MyOutput>`. Updated SDK matches TypeScript convention
    // — `TResult = string` is the default — so `trailblaze.tool<MyInput>({...})` is
    // now the canonical shape for "typed input, message return" tools. The analyzer
    // mirrors the SDK's default and extracts the input schema while emitting
    // `{"type":"string"}` for the output.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("single-arg-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "singleArg.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |interface SIn { x: string; }
        |
        |export const singleArg = trailblaze.tool<SIn>({
        |  handler: async () => "ok",
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    val def = defs.firstOrNull { it.name == "singleArg" }
      ?: fail("expected a 'singleArg' tool definition; got: ${defs.map { it.name }}")
    // Input schema reflects the declared interface.
    val inputType = (def.inputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("object", inputType)
    val properties = def.inputSchemaObject["properties"]?.jsonObject
      ?: fail("expected `properties` on the input schema; got: ${def.inputSchema}")
    assertTrue(
      properties.containsKey("x"),
      "expected input schema to include the `x` field; got: $properties",
    )
    // Output schema defaults to {"type":"string"} — no named-type-reference required.
    val outputType = (def.outputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("string", outputType)
  }

  @Test
  fun `SDK EmptyInput import resolves to empty object schema for typed-output-no-input tools`() = runBlocking {
    // The canonical "typed result, no input" shape. TypeScript generic defaults are
    // positional, so skipping the first type arg to default it isn't possible; authors
    // spell `<EmptyInput, MyResult>`. Pin that the analyzer recognizes the SDK's
    // imported `EmptyInput` sentinel as an empty object without asking
    // ts-json-schema-generator to resolve the package-local root type.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("empty-input-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "typedReturnNoInput.ts",
      """
        |import { trailblaze, type EmptyInput } from "@trailblaze/scripting";
        |
        |interface FetchedItem {
        |  id: string;
        |  label: string;
        |}
        |
        |export const fetchItem = trailblaze.tool<EmptyInput, FetchedItem>({
        |  handler: async () => ({ id: "x", label: "y" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    val def = defs.firstOrNull { it.name == "fetchItem" }
      ?: fail("expected fetchItem; got: ${defs.map { it.name }}")
    // Input schema: empty object via the SDK sentinel. The shim emits the same
    // no-input schema used for omitted input type parameters.
    val inputType = (def.inputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("object", inputType)
    val inputProperties = def.inputSchemaObject["properties"]?.jsonObject
    assertTrue(
      inputProperties == null || inputProperties.isEmpty(),
      "expected absent or empty properties on SDK EmptyInput; got: $inputProperties",
    )
    // Output schema: the typed interface.
    val outputType = (def.outputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("object", outputType)
    val outputProperties = def.outputSchemaObject["properties"]?.jsonObject
    assertTrue(
      outputProperties != null && outputProperties.containsKey("id") && outputProperties.containsKey("label"),
      "expected FetchedItem fields in output schema; got: $outputProperties",
    )
  }

  @Test
  fun `SDK EmptyInput type-only import alias resolves to empty object schema`() = runBlocking {
    // Some tools import `trailblaze` from a local shared module but import `EmptyInput`
    // type-only from the SDK. Pin that shape, including aliasing.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("empty-input-type-only-alias-tools")
    writeTsFixture(
      toolsDir,
      "typeOnlyAliasNoInput.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |import type { EmptyInput as NoInput } from "@trailblaze/scripting";
        |
        |export const typeOnlyAliasNoInput = trailblaze.tool<NoInput>({
        |  handler: async () => "ok",
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val inputType = (def.inputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("object", inputType)
    val inputProperties = def.inputSchemaObject["properties"]?.jsonObject
    assertTrue(
      inputProperties == null || inputProperties.isEmpty(),
      "expected absent or empty properties on aliased SDK EmptyInput; got: $inputProperties",
    )
  }

  @Test
  fun `local EmptyInput type is analyzed normally when not imported from SDK`() = runBlocking {
    // The SDK sentinel handling must be import-aware. A user-defined type that
    // happens to be named `EmptyInput` is still just a normal input schema.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("local-empty-input-tools")
    writeTsFixture(
      toolsDir,
      "localEmptyInput.ts",
      """
        |import { trailblaze } from "@trailblaze/scripting";
        |
        |interface EmptyInput {
        |  requiredValue: string;
        |}
        |
        |export const localEmptyInput = trailblaze.tool<EmptyInput>({
        |  handler: async () => "ok",
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val properties = def.inputSchemaObject["properties"]?.jsonObject
      ?: fail("expected local EmptyInput properties; got: ${def.inputSchema}")
    assertTrue(
      properties.containsKey("requiredValue"),
      "expected local EmptyInput schema to include requiredValue; got: $properties",
    )
  }

  @Test
  fun `array-valued result via named type alias produces a top-level array schema`() = runBlocking {
    // Arrays are supported as top-level input or output, but ONLY via a named type
    // reference — `trailblaze.tool<string[], MyOutput>({...})` is rejected by the
    // analyzer's `readNamedTypeReference` (raw `string[]` is an `ArrayTypeNode`, not
    // a `TypeReferenceNode`). Authors who genuinely need a top-level array wrap it
    // in a `type` alias; ts-json-schema-generator walks the alias and produces the
    // expected `{"type":"array","items":...}` schema. Pin the named-alias path.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("array-result-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "listItems.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |
        |interface Item { id: string; label: string; }
        |type ItemList = Item[];
        |interface ListItemsInput { limit?: number; }
        |
        |export const listItems = trailblaze.tool<ListItemsInput, ItemList>({
        |  handler: async () => [],
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    val def = defs.firstOrNull { it.name == "listItems" }
      ?: fail("expected listItems; got: ${defs.map { it.name }}")
    val outputType = (def.outputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("array", outputType)
    val items = def.outputSchemaObject["items"]?.jsonObject
      ?: fail("expected `items` on the array output schema; got: ${def.outputSchema}")
    // `ts-json-schema-generator` may emit `items` as a `$ref` to a `definitions` entry
    // OR inline the Item type's properties directly, depending on referencing-heuristics
    // settings. Pin only that the items schema points at SOMETHING (not just an empty
    // object) — either path is functionally correct, and asserting on the precise shape
    // would couple this test to the generator's internal heuristics.
    val itemsHasRef = items["\$ref"] != null
    val itemsHasInlineProperties = items["properties"]?.jsonObject?.let {
      it.containsKey("id") && it.containsKey("label")
    } ?: false
    assertTrue(
      itemsHasRef || itemsHasInlineProperties,
      "expected items schema to be either a \$ref or inline-properties pointing at Item; got: $items",
    )
  }

  @Test
  fun `inline array type T-bracket-bracket in type parameter is rejected with a descriptive error`() = runBlocking {
    // The other half of the array story: inline `string[]` in the type parameter slot
    // hits the "named reference required" guard and gets the same actionable error
    // every other non-reference shape gets. Surface the diagnostic so authors know
    // to extract a type alias.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("inline-array-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "inlineArray.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |interface MyInput { ids: string[]; }
        |
        |export const inlineArray = trailblaze.tool<MyInput, string[]>({
        |  handler: async () => [],
        |});
      """.trimMargin(),
    )

    try {
      analyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for inline array type")
    } catch (e: ScriptedToolDefinitionException) {
      val err = e.errors.firstOrNull { it.toolName == "inlineArray" }
        ?: fail("expected inlineArray error; got: ${e.errors}")
      assertTrue(
        err.message.contains("named references"),
        "expected the named-reference diagnostic; got: ${err.message}",
      )
    }
  }

  @Test
  fun `zero type arguments defaults input to empty object and result to string`() = runBlocking {
    // **Behavior change (PR #3338 ergonomics):** Was previously a silent skip (the
    // analyzer treated zero-arg as a legacy-untyped declaration). The SDK's typed
    // overload now has `<TInput = Record<string, never>, TResult = string>` defaults,
    // so `trailblaze.tool({...})` is a real typed declaration the analyzer must
    // extract. Pins the simplest-possible-tool shape: no input, message return.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("zero-arg-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "ping.ts",
      """
        |declare const trailblaze: { tool: (...args: unknown[]) => unknown };
        |
        |export const ping = trailblaze.tool({
        |  handler: async () => "pong",
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    val def = defs.firstOrNull { it.name == "ping" }
      ?: fail("expected a 'ping' tool definition; got: ${defs.map { it.name }}")
    // Input schema defaults to empty object: { type: "object", properties: {} }.
    val inputType = (def.inputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("object", inputType)
    val inputProperties = def.inputSchemaObject["properties"]?.jsonObject
    assertTrue(
      inputProperties != null && inputProperties.isEmpty(),
      "expected empty `properties` on the default input schema; got: $inputProperties",
    )
    // Output schema defaults to {"type":"string"}.
    val outputType = (def.outputSchemaObject["type"] as? JsonPrimitive)?.contentOrNull
    assertEquals("string", outputType)
  }

  @Test
  fun `tools in nested subdirectories are discovered recursively`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("nested-dirs-trailmap-tools")
    val subDir = File(toolsDir, "mcp")
    subDir.mkdirs()
    writeTsFixture(
      toolsDir,
      "topLevel.ts",
      """
        |${declareTrailblazeStub()}
        |interface TIn { x: string; }
        |interface TOut { y: string; }
        |export const topLevelTool = trailblaze.tool<TIn, TOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    writeTsFixture(
      subDir,
      "nested.ts",
      """
        |${declareTrailblazeStub()}
        |interface NIn { x: string; }
        |interface NOut { y: string; }
        |export const nestedSubTool = trailblaze.tool<NIn, NOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(
      setOf("topLevelTool", "nestedSubTool"),
      defs.map { it.name }.toSet(),
      "expected the analyzer to recurse into mcp/nested.ts as well as the top-level file",
    )
  }

  @Test
  fun `symlinked directories are not followed during recursion (prevents loops + skip-evasion)`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("symlink-skip-trailmap-tools")
    // A real tool at the top level — should be discovered.
    writeTsFixture(
      toolsDir,
      "realTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface RIn { x: string; }
        |interface ROut { y: string; }
        |export const realTool = trailblaze.tool<RIn, ROut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    // Create a real sibling directory carrying a "ghost" tool that the
    // analyzer would discover IF it followed the symlink we're about to add.
    val sibling = tempFolder.newFolder("symlink-skip-sibling")
    writeTsFixture(
      sibling,
      "ghostTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface GIn { x: string; }
        |interface GOut { y: string; }
        |export const ghostSymlinkedTool = trailblaze.tool<GIn, GOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    // Symlink the sibling INTO the trailmap's tools dir under a name that doesn't
    // hit the `.trailblaze` / `node_modules` skips. Without the symlink check
    // the analyzer would follow into `linked/` and pick up `ghostTool`.
    val linkPath = java.nio.file.Paths.get(toolsDir.absolutePath, "linked")
    try {
      java.nio.file.Files.createSymbolicLink(linkPath, sibling.toPath())
    } catch (_: java.nio.file.FileSystemException) {
      // Some CI hosts don't grant symlink-create privilege (notably Windows
      // without developer mode); skip cleanly rather than fail the suite.
      assumeTrue("host filesystem doesn't allow symlink creation", false)
    }

    val defs = analyzer.analyze(toolsDir)
    assertEquals(
      setOf("realTool"),
      defs.map { it.name }.toSet(),
      "expected the symlinked subtree to be skipped; found: ${defs.map { it.name }}",
    )
  }

  @Test
  fun `TRAILBLAZE_SDK_DIR-style explicit sdkDir override resolves the shim from a non-walk-up location`() = runBlocking {
    assumeAnalyzerRunnable()
    // Construct an analyzer pointing explicitly at the real SDK directory —
    // this exercises the same code path as `TRAILBLAZE_SDK_DIR` would in
    // production (resolveSdkDir's env-var branch hands the explicit dir to
    // resolveExtractorShim with no walk-up). Pins the contract that an
    // explicit sdkDir IS the source of truth for shim resolution and that
    // a workspace anywhere on disk can be analyzed without changing CWD.
    val explicitSdk = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
      ?: fail("test precondition: SDK dir must be locatable via walk-up")
    val explicitShim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(explicitSdk)
      ?: fail("test precondition: shim must be present under SDK")
    val explicitAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = explicitShim,
      sdkDir = explicitSdk,
    )

    val toolsDir = tempFolder.newFolder("explicit-sdk-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "explicitSdkTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface EIn { x: string; }
        |interface EOut { y: string; }
        |export const explicitSdkTool = trailblaze.tool<EIn, EOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val def = explicitAnalyzer.analyze(toolsDir).single()
    assertEquals("explicitSdkTool", def.name)
  }

  @Test
  fun `subprocess timeout fires when configured below the shim's runtime`() = runBlocking {
    assumeAnalyzerRunnable()
    // Forcing function for the constructor's `subprocessTimeoutSeconds` knob:
    // a timeout shorter than the shim's typical runtime should trip the
    // ScriptedToolDefinitionException with the "timed out" message. Uses 1s
    // — well below the ~500ms TS-Program build, but flaky if CI is loaded;
    // the test asserts EITHER a timeout exception OR a successful analyze
    // (the slow path is the load-bearing case) so the suite stays green on
    // fast hosts without losing the timeout-coverage signal on slow ones.
    val toolsDir = tempFolder.newFolder("timeout-trailmap-tools")
    // Many fixtures so the shim has real work to do under the squeeze.
    repeat(8) { i ->
      writeTsFixture(
        toolsDir,
        "timeoutTool$i.ts",
        """
          |${declareTrailblazeStub()}
          |interface T${i}In { x: string; }
          |interface T${i}Out { y: string; }
          |export const timeoutTool$i = trailblaze.tool<T${i}In, T${i}Out>({
          |  handler: async () => ({ y: "" }),
          |});
        """.trimMargin(),
      )
    }
    val tight = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      subprocessTimeoutSeconds = 1,
    )
    try {
      val defs = tight.analyze(toolsDir)
      // Fast host — analyzer beat the timeout. Acceptable: this asserts the
      // timeout knob is at minimum threaded through without breaking the
      // happy path. The hostile case (slow CI) catches the exception below.
      assertEquals(8, defs.size)
    } catch (e: ScriptedToolDefinitionException) {
      assertTrue(
        e.message.orEmpty().contains("timed out"),
        "expected 'timed out' in exception message; got: ${e.message}",
      )
    }
  }

  @Test
  fun `framework-generated dot-trailblaze subtree is skipped during recursion`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("dot-trailblaze-skip-trailmap-tools")
    val dotDir = File(toolsDir, ".trailblaze")
    dotDir.mkdirs()
    // Real tool at the top level — should be discovered.
    writeTsFixture(
      toolsDir,
      "realTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface RIn { x: string; }
        |interface ROut { y: string; }
        |export const realTool = trailblaze.tool<RIn, ROut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    // Codegen-ish file inside `.trailblaze/` — analyzer must skip the whole
    // subtree, otherwise the regenerated client.d.ts produced by
    // PerTrailmapClientDtsEmitter would feed back into this analyzer's input every
    // run.
    writeTsFixture(
      dotDir,
      "client.ts",
      """
        |${declareTrailblazeStub()}
        |interface FIn { x: string; }
        |interface FOut { y: string; }
        |export const ghostFrameworkTool = trailblaze.tool<FIn, FOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertEquals(
      setOf("realTool"),
      defs.map { it.name }.toSet(),
      "expected `.trailblaze/` subtree to be skipped",
    )
  }

  @Test
  fun `third-party builder with matching syntactic shape is NOT recognized as a tool`() = runBlocking {
    assumeAnalyzerRunnable()
    // Pins the strict-receiver semantics: another library's `.tool<I, O>(...)`
    // builder must not be picked up by this analyzer. Without the receiver
    // check, the analyzer would attempt to extract schemas for foreign tool
    // definitions and produce confusing per-tool errors when the schema walk
    // failed against unrelated type closures.
    val toolsDir = tempFolder.newFolder("foreign-receiver-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "foreignBuilder.ts",
      """
        |// A third-party builder that happens to expose `.tool<I, O>(...)`.
        |// Must NOT be picked up by the analyzer.
        |declare const somelib: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => unknown };
        |interface In { x: string; }
        |interface Out { y: string; }
        |export const foreignTool = somelib.tool<In, Out>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val defs = analyzer.analyze(toolsDir)
    assertTrue(
      defs.isEmpty(),
      "expected zero tools (somelib.tool isn't the Trailblaze builder); got: ${defs.map { it.name }}",
    )
  }

  @Test
  fun `aliased import of trailblaze from the SDK is recognized as a tool`() = runBlocking {
    assumeAnalyzerRunnable()
    // Pins the alias-resolution path: `import { trailblaze as tb } from
    // "@trailblaze/scripting"` should let `tb.tool<I, O>(...)` be recognized
    // by the analyzer the same way the bare-name form is.
    val toolsDir = tempFolder.newFolder("aliased-import-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "aliasedImport.ts",
      """
        |import { trailblaze as tb } from "@trailblaze/scripting";
        |interface AIn { x: string; }
        |interface AOut { y: string; }
        |export const aliasedTool = tb.tool<AIn, AOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    assertEquals("aliasedTool", def.name)
  }

  @Test
  fun `namespace import of the SDK resolves via the namespace dot-trailblaze access pattern`() = runBlocking {
    assumeAnalyzerRunnable()
    // `import * as sdk from "@trailblaze/scripting"` then `sdk.trailblaze.tool<...>(...)`
    // — the analyzer should follow the property-access chain back through the
    // namespace binding to the SDK's exported `trailblaze`.
    val toolsDir = tempFolder.newFolder("namespace-import-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "namespaceImport.ts",
      """
        |import * as sdk from "@trailblaze/scripting";
        |interface NIn { x: string; }
        |interface NOut { y: string; }
        |export const namespaceTool = sdk.trailblaze.tool<NIn, NOut>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    assertEquals("namespaceTool", def.name)
  }

  @Test
  fun `tool without TSDoc returns null description`() = runBlocking {
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("no-tsdoc-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "undocumentedTool.ts",
      """
        |${declareTrailblazeStub()}
        |interface I { x: string; }
        |interface O { y: string; }
        |export const undocumentedTool = trailblaze.tool<I, O>({
        |  handler: async () => ({ y: "" }),
        |});
      """.trimMargin(),
    )
    val def = analyzer.analyze(toolsDir).single()
    assertNull(def.description, "expected null description when no TSDoc precedes the export")
  }

  @Test
  fun `with-spec overload — supportedPlatforms and requiresContext are extracted into ScriptedToolDefinition spec`() = runBlocking {
    // Pin the load-bearing contract from #3352: the typed `(spec, handler)`
    // overload's inline-literal spec fields flow through the analyzer into
    // `ScriptedToolDefinition.spec` so the enrichment layer can project them
    // into the runtime `_meta` JSON. Without this extraction the SDK-side
    // typed surface would compile and run but the runtime `TrailblazeToolMeta`
    // gates (`supportedPlatforms` / `requiresHost`) would all default to
    // empty/false — tools that should register on Web only would register
    // everywhere.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("with-spec-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "specTool.ts",
      """
        |${declareTypedToolStub()}
        |interface SIn { x: string; }
        |interface SOut { y: string; }
        |
        |export const specTool = trailblaze.tool<SIn, SOut>(
        |  { supportedPlatforms: ["web"], requiresContext: true },
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val spec = def.spec ?: fail("expected non-null spec; got bare-handler shape")
    val platforms = spec["supportedPlatforms"]?.jsonArray
      ?: fail("expected `supportedPlatforms` array in spec; got: $spec")
    assertEquals(listOf("web"), platforms.map { it.jsonPrimitive.content })
    assertEquals(JsonPrimitive(true), spec["requiresContext"])
    // Booleans not authored on the call site stay absent (defaults applied
    // downstream by the runtime, not by the analyzer).
    assertNull(spec["requiresHost"], "expected requiresHost absent when not authored")
    assertNull(spec["supportedDrivers"], "expected supportedDrivers absent when not authored")
  }

  @Test
  fun `with-spec overload — requiresHost and supportedDrivers are extracted when authored`() = runBlocking {
    // Parallel coverage to the previous test for the other two recognized
    // spec fields. Keeps the analyzer's contract honest: every recognized
    // `TrailblazeTypedToolSpec` field must round-trip end-to-end.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("with-spec-host-drivers-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "hostDriverTool.ts",
      """
        |${declareTypedToolStub()}
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |export const hostDriverTool = trailblaze.tool<I, O>(
        |  { requiresHost: true, supportedDrivers: ["playwright-native", "playwright-electron"] },
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val spec = def.spec ?: fail("expected non-null spec; got bare-handler shape")
    assertEquals(JsonPrimitive(true), spec["requiresHost"])
    val drivers = spec["supportedDrivers"]?.jsonArray
      ?: fail("expected `supportedDrivers` array in spec; got: $spec")
    assertEquals(
      listOf("playwright-native", "playwright-electron"),
      drivers.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `with-spec overload — surfaceToLlm and isRecordable are extracted when authored`() = runBlocking {
    // Parallel coverage for the two flags this PR added to the recognized
    // `TrailblazeTypedToolSpec` field set (RECOGNIZED_SPEC_FIELDS). Without
    // extraction, an author's `.ts`-declared `surfaceToLlm: false` /
    // `isRecordable: false` would never reach the enrichment layer's typed slots
    // or the runtime `_meta`, so a hidden / non-recordable internal step would be
    // advertised + recorded anyway. Pins the end-to-end extractor contract.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("with-spec-surface-record-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "internalStepTool.ts",
      """
        |${declareTypedToolStub()}
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |export const internalStepTool = trailblaze.tool<I, O>(
        |  { surfaceToLlm: false, isRecordable: false },
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val spec = def.spec ?: fail("expected non-null spec; got bare-handler shape")
    assertEquals(JsonPrimitive(false), spec["surfaceToLlm"])
    assertEquals(JsonPrimitive(false), spec["isRecordable"])
    // Fields not authored on the call site stay absent (defaults applied downstream).
    assertNull(spec["requiresHost"], "expected requiresHost absent when not authored")
    assertNull(spec["supportedPlatforms"], "expected supportedPlatforms absent when not authored")
  }

  @Test
  fun `bare-handler overload — spec is null when no spec object is passed`() = runBlocking {
    // The bare-handler form (arg 0 is an arrow/function) carries no spec, so
    // `ScriptedToolDefinition.spec` must be null — never an empty object. The
    // downstream enrichment layer relies on null-vs-empty to skip the analyzer-
    // fill-in branch entirely; emitting `{}` here would push it into a code path
    // that builds an empty namespaced map for nothing.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("bare-handler-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "bareTool.ts",
      """
        |${declareTypedToolStub()}
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |export const bareTool = trailblaze.tool<I, O>(
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    assertNull(def.spec, "expected null spec on the bare-handler overload")
    // A 1-arg bare handler is NOT the dangerous "spec reference dropped" case — the lone
    // argument IS the handler, not a spec. The footgun guard requires exactly 2 args, so this
    // must stay false (otherwise every bare-handler tool would be flagged as un-gated).
    assertEquals(
      false,
      def.uncapturedSpec,
      "expected uncapturedSpec=false on the bare-handler overload (one arg, no spec to drop)",
    )
  }

  @Test
  fun `with-spec overload — unresolvable expressions (spread, identifier) are silently skipped`() = runBlocking {
    // Documents the inline-literal-only contract called out in the JS shim:
    // object spread (`...sharedDefaults`) and identifier references aren't
    // resolved today. The analyzer captures whatever inline literals it can
    // read and skips the rest. Authors who need every field captured can
    // inline the values at the call site until the future helper-resolution
    // PR lands.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("with-spec-partial-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "partialTool.ts",
      """
        |${declareTypedToolStub()}
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |const SHARED = { requiresContext: true };
        |const PLATFORMS = ["web"];
        |
        |export const partialTool = trailblaze.tool<I, O>(
        |  // Inline boolean is captured; spread + identifier reference are skipped.
        |  { ...SHARED, requiresHost: true, supportedPlatforms: PLATFORMS },
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val spec = def.spec ?: fail("expected partial spec extraction; got null")
    // `requiresHost: true` (inline boolean literal) is captured.
    assertEquals(JsonPrimitive(true), spec["requiresHost"])
    // Spread expressions (`...SHARED`) are skipped — no `requiresContext` key.
    assertNull(spec["requiresContext"], "expected spread-introduced fields to be skipped")
    // Identifier references (`supportedPlatforms: PLATFORMS`) are skipped — no
    // `supportedPlatforms` key.
    assertNull(spec["supportedPlatforms"], "expected identifier-referenced values to be skipped")
  }

  @Test
  fun `with-spec overload — unrecognized fields are silently dropped`() = runBlocking {
    // Defends the JS shim's `RECOGNIZED_SPEC_FIELDS` allowlist. Authors who
    // typo a field name (`supportedPlatform: ["web"]` missing the `s`) get a
    // TypeScript compile error on the typed surface — the analyzer doesn't
    // need to double-check, and dropping unknown keys keeps the envelope from
    // exposing implementation-internal field names downstream.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("with-spec-unknown-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "unknownFieldTool.ts",
      """
        |declare const trailblaze: { tool: <I, O>(spec: any, handler: (input: I) => Promise<O>) => unknown };
        |interface I { x: string; }
        |interface O { y: string; }
        |
        |export const unknownFieldTool = trailblaze.tool<I, O>(
        |  { supportedPlatforms: ["web"], futureField: "ignored", anotherUnknown: 42 },
        |  async () => ({ y: "" }),
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    val spec = def.spec ?: fail("expected non-null spec; got null")
    // Recognized field captured.
    val platforms = spec["supportedPlatforms"]?.jsonArray
      ?: fail("expected `supportedPlatforms` in spec; got: $spec")
    assertEquals(listOf("web"), platforms.map { it.jsonPrimitive.content })
    // Unrecognized fields dropped.
    assertNull(spec["futureField"], "expected unknown field to be dropped")
    assertNull(spec["anotherUnknown"], "expected unknown field to be dropped")
  }

  @Test
  fun `with-spec overload — every non-inline spec reference shape sets uncapturedSpec and drops the whole spec`() = runBlocking {
    // Pins the JS shim's `specArgIsUncapturedReference()` footgun guard. When the
    // (spec, handler) overload is used with a NON-inline spec argument — a named
    // `const SPEC = {...}`, a `Specs.foo` member access, or a `makeSpec()` factory
    // call — the analyzer's AST-only walk can't read the object literal, so the
    // ENTIRE spec (supportedPlatforms / surfaceToLlm / requiresHost / …) is
    // dropped, silently un-gating the tool. The shim flags that with
    // `uncapturedSpec: true` so the Kotlin enrichment layer can warn (or hard-fail
    // a descriptor-less tool) instead of shipping the un-gated tool.
    // `AnalyzerScriptedToolEnrichmentTest` pins the resulting hard-error; this pins
    // the upstream mjs-side DETECTION that feeds it.
    //
    // All three reference shapes share one code path (anything at arg 0 that isn't a
    // function, an object literal, or a string literal), but pinning each guards
    // against a future narrowing of the detection to only bare identifiers.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("uncaptured-spec-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "uncapturedSpecTools.ts",
      """
        |${declareTypedToolStub()}
        |declare const Specs: { web: unknown };
        |declare function makeSpec(): unknown;
        |interface I { x: string; }
        |
        |// Each spec is authored as a non-inline reference rather than inline at the
        |// call site — the analyzer can't resolve any of them, so the whole spec is dropped.
        |const SPEC = { supportedPlatforms: ["web"] } as const;
        |
        |export const viaConstRef = trailblaze.tool<I>(SPEC, async () => "ok");
        |export const viaMemberAccess = trailblaze.tool<I>(Specs.web, async () => "ok");
        |export const viaFactoryCall = trailblaze.tool<I>(makeSpec(), async () => "ok");
      """.trimMargin(),
    )

    val defsByName = analyzer.analyze(toolsDir).associateBy { it.name }
    assertEquals(
      setOf("viaConstRef", "viaMemberAccess", "viaFactoryCall"),
      defsByName.keys,
      "expected all three reference-shaped tools to be discovered",
    )
    defsByName.values.forEach { def ->
      assertTrue(
        def.uncapturedSpec,
        "expected uncapturedSpec=true for non-inline spec reference '${'$'}{def.name}'",
      )
      assertNull(
        def.spec,
        "expected spec=null for '${'$'}{def.name}' — a non-inline reference can't be read, so the whole spec is dropped",
      )
    }
  }

  @Test
  fun `with-spec overload — an inline-literal spec leaves uncapturedSpec false and captures the fields`() = runBlocking {
    // Control for the uncapturedSpec footgun above: the SAME 2-arg (spec, handler)
    // call shape, but with the spec inlined at the call site. The analyzer reads
    // the literal, so the fields are captured AND `uncapturedSpec` stays false —
    // the dangerous whole-spec-dropped signal must fire ONLY for the non-inline-
    // reference case, never for every (spec, handler) call.
    assumeAnalyzerRunnable()
    val toolsDir = tempFolder.newFolder("captured-spec-trailmap-tools")
    writeTsFixture(
      toolsDir,
      "capturedSpecTool.ts",
      """
        |${declareTypedToolStub()}
        |interface I { x: string; }
        |
        |export const capturedSpecTool = trailblaze.tool<I>(
        |  { supportedPlatforms: ["web"] },
        |  async () => "ok",
        |);
      """.trimMargin(),
    )

    val def = analyzer.analyze(toolsDir).single()
    assertEquals(
      false,
      def.uncapturedSpec,
      "expected uncapturedSpec=false when the spec is an inline object literal",
    )
    val spec = def.spec ?: fail("expected non-null spec captured from the inline literal")
    val platforms = spec["supportedPlatforms"]?.jsonArray
      ?: fail("expected `supportedPlatforms` captured from the inline literal; got: $spec")
    assertEquals(listOf("web"), platforms.map { it.jsonPrimitive.content })
  }

  @Test
  fun `wikipedia example trailmap — migrated tools expose the expected spec fields end-to-end`() = runBlocking {
    // Integration test for the wikipedia migration that landed alongside the
    // typed `(spec, handler)` overload. Pins the contract that the in-tree
    // wikipedia tools — whose YAML descriptors are meta-only and which carry
    // ALL their `_meta` keys on the `trailblaze.tool<I, O>(spec, handler)` call
    // site in `.ts` — still extract correctly after a future analyzer change.
    //
    // Failure mode this catches: if a future refactor silently drops spec
    // extraction (e.g. broken `RECOGNIZED_SPEC_FIELDS` set, regressed
    // object-literal walk, mis-routed bare-handler detection), every analyzer
    // unit test still passes against synthetic fixtures but the real wikipedia
    // tools register on every platform — a quiet failure that only surfaces
    // when someone tries to run a wikipedia tool on Android/iOS.
    //
    // Walks the test JVM's user.dir for the wikipedia tools directory the same
    // way `ScriptedToolDefinitionAnalyzer.resolveSdkDir` walks for the SDK
    // tree. Skips cleanly when the wikipedia trailmap isn't reachable (e.g. a
    // sandboxed test environment) so the suite stays portable.
    assumeAnalyzerRunnable()
    val wikipediaToolsDir = resolveWikipediaToolsDir() ?: run {
      assumeTrue(
        "wikipedia example trailmap not reachable from test cwd — skipping integration test",
        false,
      )
      return@runBlocking
    }

    val defs = analyzer.analyze(wikipediaToolsDir)
    val byName = defs.associateBy { it.name }

    // Pin the migrated tools — both should carry the spec extracted from their
    // `.ts` call sites, and the spec should project to the same `_meta` keys the
    // YAML descriptors used to carry directly.
    val dismissBanner = byName["wikipedia_web_dismissBannerIfPresent"]
      ?: fail(
        "expected `wikipedia_web_dismissBannerIfPresent` to be extracted; got: ${byName.keys}",
      )
    val dismissSpec = dismissBanner.spec
      ?: fail(
        "expected `wikipedia_web_dismissBannerIfPresent` to use the (spec, handler) " +
          "overload — extracted spec was null. Did the .ts file revert to the bare-handler form?",
      )
    val dismissPlatforms = dismissSpec["supportedPlatforms"]?.jsonArray
      ?: fail("expected `supportedPlatforms` on dismissBanner spec; got: $dismissSpec")
    assertEquals(
      listOf("web"),
      dismissPlatforms.map { it.jsonPrimitive.content },
      "wikipedia_web_dismissBannerIfPresent should be web-only",
    )
    assertEquals(
      JsonPrimitive(true),
      dismissSpec["requiresContext"],
      "wikipedia_web_dismissBannerIfPresent should declare requiresContext",
    )

    val openMainPage = byName["wikipedia_web_openMainPage"]
      ?: fail(
        "expected `wikipedia_web_openMainPage` to be extracted; got: ${byName.keys}",
      )
    val openSpec = openMainPage.spec
      ?: fail(
        "expected `wikipedia_web_openMainPage` to use the (spec, handler) overload " +
          "— extracted spec was null.",
      )
    val openPlatforms = openSpec["supportedPlatforms"]?.jsonArray
      ?: fail("expected `supportedPlatforms` on openMainPage spec; got: $openSpec")
    assertEquals(
      listOf("web"),
      openPlatforms.map { it.jsonPrimitive.content },
      "wikipedia_web_openMainPage should be web-only",
    )
    assertEquals(
      JsonPrimitive(true),
      openSpec["requiresContext"],
      "wikipedia_web_openMainPage should declare requiresContext",
    )
  }

  // --- helpers ---

  /**
   * Walk ancestors of the test JVM's `user.dir` looking for the wikipedia
   * example trailmap's `tools/` directory. Mirrors
   * [ScriptedToolDefinitionAnalyzer.resolveSdkDir]'s walk-up pattern so the
   * test stays portable across different `gradlew` invocation cwds (module
   * directory vs. repo root) AND across this code's two layouts: a
   * flat layout (`examples/wikipedia/...` at the repo root) and a nested
   * layout (where the same tree lives under a parent directory). The
   * walk-up succeeds against the first matching ancestor — `examples/...`
   * lives at exactly one depth in each layout, so the loop converges either
   * way. Returns null when the trailmap isn't reachable so the caller can
   * `assumeTrue`-skip cleanly in sandboxed environments.
   */
  private fun resolveWikipediaToolsDir(): File? {
    val relPath = "examples/wikipedia/trails/config/trailmaps/wikipedia/tools"
    var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (current != null) {
      val candidate = File(current, relPath)
      if (candidate.isDirectory) return candidate
      current = current.parentFile
    }
    return null
  }


  /**
   * Minimal `declare const trailblaze: ...` stub so test fixtures don't need to
   * import `@trailblaze/scripting` (which isn't resolvable from a `@TempDir`).
   * Mirrors the contract the SDK exports — a `.tool` method that takes a generic
   * `<I, O>` and a single config object — without pulling in the runtime
   * implementation.
   */
  private fun declareTrailblazeStub(): String =
    "declare const trailblaze: { tool: <I, O>(spec: { handler: (input: I) => Promise<O> }) => unknown };"

  /**
   * Stub mirroring the typed `(spec, handler)` overload introduced in #3352. The
   * SDK's real signature is `tool<I, O>(spec: TrailblazeTypedToolSpec, handler:
   * (input: I, ctx: ToolContext) => Promise<O>)`. The stub uses `any` for the
   * spec type so fixtures can declare unknown / mistyped fields without the
   * surrounding TS program failing to parse — the analyzer is what asserts the
   * extracted shape against the RECOGNIZED_SPEC_FIELDS allowlist.
   */
  private fun declareTypedToolStub(): String =
    "declare const trailblaze: { tool: <I, O>(spec: any, handler: (input: I) => Promise<O>) => unknown };"

  private fun writeTsFixture(dir: File, name: String, body: String): File {
    val file = File(dir, name)
    file.writeText(body)
    return file
  }

  @Test
  fun `cache miss populates the cache file on the first run`() = runBlocking {
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("cache-miss-cache")
    val cachedAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val toolsDir = tempFolder.newFolder("cache-miss-trailmap-tools")
    writeTsFixture(toolsDir, "cachedTool.ts", simpleToolFixture("cachedTool"))

    val defs = cachedAnalyzer.analyze(toolsDir)
    assertEquals(1, defs.size)
    assertEquals("cachedTool", defs.single().name)

    val cacheFiles = cacheDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    assertEquals(
      1,
      cacheFiles.size,
      "expected exactly one cache JSON file written on first run; got: ${cacheFiles.map { it.absolutePath }}",
    )
  }

  @Test
  fun `cache hit skips subprocess and serves the prior result`() = runBlocking {
    // Forcing function: warm the cache with a real subprocess run, then construct a
    // SECOND analyzer pointing at the same cache dir but with a broken node binary.
    // If the cache hit path works, the second analyze() returns the prior result
    // without ever attempting to launch the broken subprocess. If the cache is
    // bypassed, the broken node would cause analyze() to return empty (the
    // documented "node missing → empty" degradation) and the assertion below
    // would fail.
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("cache-hit-cache")
    val warmAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val toolsDir = tempFolder.newFolder("cache-hit-trailmap-tools")
    writeTsFixture(toolsDir, "hitTool.ts", simpleToolFixture("hitTool"))
    val firstDefs = warmAnalyzer.analyze(toolsDir)
    assertEquals(1, firstDefs.size)

    val bogusBun = File(tempFolder.root, "definitely-not-bun")
    val cachedOnlyAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bogusBun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val secondDefs = cachedOnlyAnalyzer.analyze(toolsDir)
    assertEquals(
      firstDefs.map { it.name },
      secondDefs.map { it.name },
      "cache hit must return the prior result even when the node binary is missing",
    )
    assertEquals(firstDefs.single().sourcePath, secondDefs.single().sourcePath)
  }

  @Test
  fun `content change invalidates the cache and re-runs the subprocess`() = runBlocking {
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("content-change-cache")
    val cachedAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val toolsDir = tempFolder.newFolder("content-change-trailmap-tools")
    writeTsFixture(toolsDir, "evolving.ts", simpleToolFixture("originalTool"))
    val firstDefs = cachedAnalyzer.analyze(toolsDir)
    assertEquals(setOf("originalTool"), firstDefs.map { it.name }.toSet())

    // Mutate the .ts file's tool name. The cache lookup uses a content hash; the
    // new contents produce a different hash and the analyzer must NOT serve the
    // stale entry.
    writeTsFixture(toolsDir, "evolving.ts", simpleToolFixture("renamedTool"))
    val secondDefs = cachedAnalyzer.analyze(toolsDir)
    assertEquals(
      setOf("renamedTool"),
      secondDefs.map { it.name }.toSet(),
      "content change must invalidate the cache; got stale: ${secondDefs.map { it.name }}",
    )

    // Both entries should now live on disk — old + new — because the cache file
    // path is derived from the content hash and the v1 design intentionally
    // doesn't garbage-collect superseded entries.
    val cacheFiles = cacheDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    assertEquals(
      2,
      cacheFiles.size,
      "expected two cache entries (original + renamed); got: ${cacheFiles.map { it.name }}",
    )
  }

  @Test
  fun `dependency-key change invalidates the cache even when trailmap content is unchanged`() = runBlocking {
    // Models the "SDK .d.ts changed" / "extractor shim changed" / "ts-json-schema-
    // generator version bumped" scenarios all at once: the dependency key bakes
    // each into the hash, so the way to exercise the invalidation contract from
    // a test is to construct two analyzers whose dependency keys disagree.
    //
    // We point the second analyzer at a different SDK dir whose dist/index.d.ts
    // carries different bytes than the real one. The shim files are byte-identical
    // (we copy the real shim into the synthetic SDK so the subprocess still runs).
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("dep-key-cache")
    val toolsDir = tempFolder.newFolder("dep-key-trailmap-tools")
    writeTsFixture(toolsDir, "depKeyTool.ts", simpleToolFixture("depKeyTool"))

    val firstAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val firstDefs = firstAnalyzer.analyze(toolsDir)
    assertEquals(1, firstDefs.size)
    val cacheFilesAfterFirst = cacheDir.walkTopDown().filter { it.extension == "json" }.toList()
    assertEquals(1, cacheFilesAfterFirst.size)

    // Build a synthetic SDK with a DIFFERENT dist/index.d.ts. Copy the real shim
    // and node_modules entry so the second analyzer can still actually run a
    // subprocess (we want a cache miss + a successful re-run, not a hard fail).
    val syntheticSdk = tempFolder.newFolder("synthetic-sdk")
    File(syntheticSdk, "tools").mkdirs()
    File(syntheticSdk, "dist").mkdirs()
    shim.copyTo(File(syntheticSdk, "tools/extract-tool-defs.mjs"), overwrite = true)
    File(syntheticSdk, "dist/index.d.ts").writeText(
      "// synthetic dist that diverges from the real SDK's dist/index.d.ts\n",
    )
    // Symlink the real `node_modules/` so ts-json-schema-generator resolves under
    // the synthetic SDK without us copying tens of MB of node_modules into the
    // temp dir.
    val realNodeModules = File(sdkDir, "node_modules")
    if (realNodeModules.isDirectory) {
      try {
        java.nio.file.Files.createSymbolicLink(
          java.nio.file.Paths.get(syntheticSdk.absolutePath, "node_modules"),
          realNodeModules.toPath(),
        )
      } catch (_: java.nio.file.FileSystemException) {
        assumeTrue("host doesn't allow symlink creation in tmp dir", false)
      }
    }
    val secondAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = File(syntheticSdk, "tools/extract-tool-defs.mjs"),
      sdkDir = syntheticSdk,
      cacheDir = cacheDir,
    )
    val secondDefs = secondAnalyzer.analyze(toolsDir)
    assertEquals(1, secondDefs.size)
    val cacheFilesAfterSecond = cacheDir.walkTopDown().filter { it.extension == "json" }.toList()
    assertEquals(
      2,
      cacheFilesAfterSecond.size,
      "dependency-key divergence must produce a distinct cache entry; got: ${cacheFilesAfterSecond.map { it.name }}",
    )
  }

  @Test
  fun `cache disabled when cacheDir is null — no cache files written`() = runBlocking {
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("no-cache-cache")
    val uncachedAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = null,
    )
    val toolsDir = tempFolder.newFolder("uncached-trailmap-tools")
    writeTsFixture(toolsDir, "uncachedTool.ts", simpleToolFixture("uncachedTool"))

    val defs = uncachedAnalyzer.analyze(toolsDir)
    assertEquals(1, defs.size)
    val cacheFiles = cacheDir.walkTopDown().filter { it.isFile }.toList()
    assertTrue(
      cacheFiles.isEmpty(),
      "expected no cache files written when cacheDir is null; got: ${cacheFiles.map { it.name }}",
    )
  }

  @Test
  fun `corrupt cache file falls back to subprocess and overwrites with a clean entry`() = runBlocking {
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("corrupt-cache")
    val cachedAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val toolsDir = tempFolder.newFolder("corrupt-trailmap-tools")
    writeTsFixture(toolsDir, "corruptTool.ts", simpleToolFixture("corruptTool"))

    // Prime the cache with one good run, then truncate-and-replace the JSON with
    // garbage. The analyzer must recover (cache helper logs + returns null on the
    // unreadable file) and re-run the subprocess; the rewrite then restores a
    // valid entry.
    cachedAnalyzer.analyze(toolsDir)
    val cacheFile = cacheDir.walkTopDown().first { it.isFile && it.extension == "json" }
    cacheFile.writeText("{ this is not valid JSON")

    val recoveredDefs = cachedAnalyzer.analyze(toolsDir)
    assertEquals(setOf("corruptTool"), recoveredDefs.map { it.name }.toSet())

    // The corrupt entry was overwritten by the same path on the second run because
    // the (trailmapToolsDir, contentKey) inputs are unchanged.
    val rewritten = cacheFile.readText()
    assertTrue(
      rewritten.contains("corruptTool"),
      "expected the corrupt cache file to be overwritten with a valid entry on rerun; " +
        "got: ${rewritten.take(200)}",
    )
  }

  /**
   * Minimal one-tool fixture body — used by the cache tests so each test stays
   * focused on the cache contract rather than re-declaring tool shape.
   */
  private fun simpleToolFixture(toolName: String): String = """
    |${declareTrailblazeStub()}
    |interface I { x: string; }
    |interface O { y: string; }
    |export const $toolName = trailblaze.tool<I, O>({
    |  handler: async () => ({ y: "" }),
    |});
  """.trimMargin()

  @Test
  fun `disableCache=true bypasses cache lookup and writes`() = runBlocking {
    // Forcing function for the TRAILBLAZE_TOOL_ANALYZER_NO_CACHE env var bypass:
    // we can't mutate JVM env vars at test time, so the analyzer exposes
    // `disableCache` as a constructor parameter whose production default IS the
    // env var. Setting it to true with a non-null cacheDir must skip both lookup
    // and write — verifying that the env-var-driven escape hatch actually
    // unwires the cache.
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("disable-cache-cache")
    val toolsDir = tempFolder.newFolder("disable-cache-trailmap-tools")
    writeTsFixture(toolsDir, "disableCacheTool.ts", simpleToolFixture("disableCacheTool"))

    // First, prove the env-var path is wired: an analyzer with disableCache=true +
    // a populated cacheDir should NOT read OR write cache files.
    val bypassAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
      disableCache = true,
    )
    val defs = bypassAnalyzer.analyze(toolsDir)
    assertEquals(1, defs.size)
    val cacheFilesAfterBypass = cacheDir.walkTopDown().filter { it.isFile }.toList()
    assertTrue(
      cacheFilesAfterBypass.isEmpty(),
      "expected no cache files written when disableCache=true; got: ${cacheFilesAfterBypass.map { it.name }}",
    )

    // Second, prove the bypass also skips LOOKUP: warm the cache via a normal
    // analyzer, then run the bypassed analyzer against a bogus node binary. If
    // the bypass weren't honored, the bogus node would never be invoked and the
    // call would succeed via cache hit. Instead, the bypassed analyzer must
    // attempt the subprocess (which fails with empty result via the "node
    // missing" branch).
    val warmAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    warmAnalyzer.analyze(toolsDir) // populate cache via real subprocess
    val bogusBun = File(tempFolder.root, "definitely-not-bun-bypass")
    val bypassAgainstWarmCache = ScriptedToolDefinitionAnalyzer(
      bunBinary = bogusBun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
      disableCache = true,
    )
    val bypassResult = bypassAgainstWarmCache.analyze(toolsDir)
    assertTrue(
      bypassResult.isEmpty(),
      "expected disableCache=true to bypass cache lookup and fall through to the " +
        "missing-node branch; got: ${bypassResult.map { it.name }}",
    )
  }

  @Test
  fun `mixed-outcome envelope is NOT cached — partial failures stay transient`() = runBlocking {
    // PR contract: when the subprocess returns some clean tools AND some errors
    // (per-tool failures, e.g. unsupported TS construct), the analyzer throws
    // ScriptedToolDefinitionException and intentionally does NOT write a cache
    // entry. Without this guarantee, an author editing a broken tool would see
    // the failure cached and re-served on subsequent runs even after fixing the
    // .ts file. Pins the behavior so a future refactor that "helpfully" caches
    // partial results surfaces this test as the canary.
    assumeAnalyzerRunnable()
    val cacheDir = tempFolder.newFolder("partial-failure-cache")
    val cachedAnalyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = cacheDir,
    )
    val toolsDir = tempFolder.newFolder("partial-failure-trailmap-tools")
    // One healthy tool + one broken tool (function-typed input, which the shim
    // rejects under `functions: "fail"`). The shim reports the healthy tool in
    // `tools` and the broken one in `errors`.
    writeTsFixture(toolsDir, "healthy.ts", simpleToolFixture("healthyTool"))
    writeTsFixture(
      toolsDir,
      "broken.ts",
      """
        |${declareTrailblazeStub()}
        |interface BIn { callback: (x: number) => string; }
        |interface BOut { ok: boolean; }
        |export const brokenTool = trailblaze.tool<BIn, BOut>({
        |  handler: async () => ({ ok: true }),
        |});
      """.trimMargin(),
    )

    try {
      cachedAnalyzer.analyze(toolsDir)
      fail("expected ScriptedToolDefinitionException for the mixed-outcome envelope")
    } catch (e: ScriptedToolDefinitionException) {
      assertTrue(e.errors.isNotEmpty(), "expected per-tool errors in the exception")
      assertTrue(e.partialTools.isNotEmpty(), "expected the healthy tool surfaced as partial")
    }

    val cacheFiles = cacheDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    assertTrue(
      cacheFiles.isEmpty(),
      "expected NO cache file written when the envelope carried errors; got: ${cacheFiles.map { it.name }}",
    )
  }

  @Test
  fun `resolveDefaultCacheDir returns null when searchFrom is null`() {
    assertNull(ScriptedToolDefinitionCache.resolveDefaultCacheDir(null))
  }

  @Test
  fun `resolveDefaultCacheDir walks up to the nearest dot-trailblaze marker`() {
    // Pins the walk-up contract: from a deeply-nested directory, the resolver
    // finds the first ancestor that carries a `.trailblaze/` marker and returns
    // its `cache/analyzer/` subpath. Mirrors how `resolveSdkDir` walks up for
    // the SDK tree; without this test, a refactor that flipped the loop's
    // direction (parent → child instead of child → parent) would silently
    // resolve to wherever it landed first.
    val workspaceRoot = tempFolder.newFolder("walk-up-workspace")
    val nested = File(workspaceRoot, "subdir1/subdir2").also { it.mkdirs() }
    val marker = File(workspaceRoot, ".trailblaze").also { it.mkdirs() }

    val resolved = ScriptedToolDefinitionCache.resolveDefaultCacheDir(nested)
    assertNotNull(resolved)
    assertEquals(
      File(marker, "cache/analyzer").canonicalFile,
      resolved!!.canonicalFile,
      "expected walk-up to find `.trailblaze/` at the workspace root",
    )
  }

  @Test
  fun `resolveDefaultCacheDir always returns a cache-analyzer subpath`() {
    // Pins the structural contract: the resolver always returns a path ending
    // in `.trailblaze/cache/analyzer`, whether it found an ancestor marker or
    // fell back to `<searchFrom>/.trailblaze/...`. We can't reliably test the
    // pure-fallback branch (any leftover `/tmp/.trailblaze/` from a prior CLI
    // run on the same host would derail it), but we CAN assert the suffix is
    // stable so a refactor that changed the cache-dir layout (e.g. flattened
    // to `.trailblaze/cache/`) would surface here.
    val isolated = tempFolder.newFolder("structural-suffix")
    val resolved = ScriptedToolDefinitionCache.resolveDefaultCacheDir(isolated)
    assertNotNull(resolved)
    assertTrue(
      resolved!!.path.replace('\\', '/').endsWith(".trailblaze/cache/analyzer"),
      "expected resolved path to end in `.trailblaze/cache/analyzer`; got ${resolved.path}",
    )
  }

  @Test
  fun `unreadable file in content key produces a distinct hash from a legitimately empty file`() {
    // Pins the ERR sentinel contract: an unreadable `.ts` file must NOT hash
    // identically to an empty file. Without this, a transiently-permission-
    // denied file would cache as a 0-byte stand-in and serve stale (or worse,
    // identical-collision) results until the file became readable again.
    //
    // Constructed without an analyzer subprocess — pure cache-key math, so
    // this doesn't need `assumeAnalyzerRunnable`.
    val trailmapDir = tempFolder.newFolder("err-sentinel-trailmap")
    val emptyFile = File(trailmapDir, "empty.ts").apply { writeText("") }
    val realFile = File(trailmapDir, "real.ts").apply { writeText("export const x = 1;") }

    val emptyOnlyHash = ScriptedToolDefinitionCache.computeContentKey(
      trailmapToolsDir = trailmapDir,
      tsFiles = listOf(emptyFile),
      dependencyKey = "test-deps",
    )

    // Make the second file unreadable. `setReadable(false)` is portable on
    // POSIX; the test assume-skips cleanly on hosts where it can't be cleared
    // (Windows without dev mode + some CI sandboxes can't toggle this).
    val cleared = realFile.setReadable(false, false)
    assumeTrue(
      "host filesystem rejected setReadable(false) — can't exercise the ERR sentinel here",
      cleared && !realFile.canRead(),
    )
    try {
      val unreadableHash = ScriptedToolDefinitionCache.computeContentKey(
        trailmapToolsDir = trailmapDir,
        tsFiles = listOf(realFile),
        dependencyKey = "test-deps",
      )
      assertNotEquals(
        emptyOnlyHash,
        unreadableHash,
        "unreadable file collided with empty-file hash — ERR sentinel dropped?",
      )

      // After restoring read permission, the hash flips again (now that the
      // real bytes contribute), proving the sentinel was actually load-bearing
      // for the prior digest.
      realFile.setReadable(true, false)
      val readableHash = ScriptedToolDefinitionCache.computeContentKey(
        trailmapToolsDir = trailmapDir,
        tsFiles = listOf(realFile),
        dependencyKey = "test-deps",
      )
      assertNotEquals(
        unreadableHash,
        readableHash,
        "hash didn't change after restoring read permission — cache won't recover from a stale ERR entry",
      )
    } finally {
      // Restore so JUnit's tempFolder cleanup can delete the file.
      realFile.setReadable(true, false)
    }
  }

  @Test
  fun `typescript version pin participates in the dependency key`() {
    // Pins the round-1 fixup that added `<sdkDir>/node_modules/typescript/package.json`
    // to the dependency key. The existing `dependency-key change invalidates`
    // test covers SDK `.d.ts` divergence end-to-end; this one is a focused
    // unit test on the hash function — we don't need a subprocess to prove
    // the typescript pin contributes.
    //
    // We build two synthetic SDK trees that differ ONLY in their typescript
    // package.json contents and verify the resulting dependency keys diverge.
    val sdkA = tempFolder.newFolder("sdk-typescript-a")
    val sdkB = tempFolder.newFolder("sdk-typescript-b")
    listOf(sdkA, sdkB).forEach { dir ->
      File(dir, "tools").mkdirs()
      File(dir, "dist").mkdirs()
      File(dir, "node_modules/typescript").mkdirs()
      File(dir, "node_modules/ts-json-schema-generator").mkdirs()
      File(dir, "tools/extract-tool-defs.mjs").writeText("// shared shim bytes\n")
      File(dir, "dist/index.d.ts").writeText("// shared dts bytes\n")
      File(dir, "node_modules/ts-json-schema-generator/package.json")
        .writeText("""{"name":"ts-json-schema-generator","version":"2.9.0"}""")
    }
    // Only the typescript pin differs.
    File(sdkA, "node_modules/typescript/package.json")
      .writeText("""{"name":"typescript","version":"5.0.0"}""")
    File(sdkB, "node_modules/typescript/package.json")
      .writeText("""{"name":"typescript","version":"6.0.3"}""")

    val keyA = ScriptedToolDefinitionCache.computeDependencyKey(
      sdkA,
      File(sdkA, "tools/extract-tool-defs.mjs"),
    )
    val keyB = ScriptedToolDefinitionCache.computeDependencyKey(
      sdkB,
      File(sdkB, "tools/extract-tool-defs.mjs"),
    )
    assertNotEquals(
      keyA,
      keyB,
      "typescript package.json must participate in the dep key — a bump from 5.0.0 to 6.0.3 should flip the hash",
    )
  }

  @Test
  fun `bun lockfile participates in the dependency key`() {
    // Pins the Codex-review fixup (#3975): the analyzer cache dep key must include the
    // committed `bun.lock`, not just the ts-json-schema-generator / typescript
    // package.json version pins. Without it, a transitive analyzer-tool dependency
    // refreshed in the lockfile (generator + compiler versions unchanged) leaves the dep
    // key — and every cached content key — identical, so the workspace analyzer cache
    // serves stale definitions even though the bundled-config Gradle task (which DOES
    // track bun.lock) re-runs. This is a focused unit test on the hash function; it
    // mirrors the `typescript version pin participates` test above.
    //
    // Two synthetic SDK trees differ ONLY in their bun.lock contents — every other dep
    // input (shim, dts, both package.json pins) is byte-identical.
    val sdkA = tempFolder.newFolder("sdk-bunlock-a")
    val sdkB = tempFolder.newFolder("sdk-bunlock-b")
    listOf(sdkA, sdkB).forEach { dir ->
      File(dir, "tools").mkdirs()
      File(dir, "dist").mkdirs()
      File(dir, "node_modules/typescript").mkdirs()
      File(dir, "node_modules/ts-json-schema-generator").mkdirs()
      File(dir, "tools/extract-tool-defs.mjs").writeText("// shared shim bytes\n")
      File(dir, "dist/index.d.ts").writeText("// shared dts bytes\n")
      File(dir, "node_modules/ts-json-schema-generator/package.json")
        .writeText("""{"name":"ts-json-schema-generator","version":"2.9.0"}""")
      File(dir, "node_modules/typescript/package.json")
        .writeText("""{"name":"typescript","version":"6.0.3"}""")
    }
    // Only the lockfile differs — same shape as a transitive-dep refresh that leaves the
    // generator + compiler versions untouched.
    File(sdkA, "bun.lock").writeText("""{"lockfileVersion":1,"packages":{"left-pad":"1.3.0"}}""")
    File(sdkB, "bun.lock").writeText("""{"lockfileVersion":1,"packages":{"left-pad":"1.3.1"}}""")

    val keyA = ScriptedToolDefinitionCache.computeDependencyKey(
      sdkA,
      File(sdkA, "tools/extract-tool-defs.mjs"),
    )
    val keyB = ScriptedToolDefinitionCache.computeDependencyKey(
      sdkB,
      File(sdkB, "tools/extract-tool-defs.mjs"),
    )
    assertNotEquals(
      keyA,
      keyB,
      "bun.lock must participate in the dep key — a transitive-dep refresh that leaves the " +
        "generator/compiler package.json pins unchanged must still flip the hash",
    )
  }
}

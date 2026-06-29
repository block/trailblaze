package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AnalyzerScriptedToolEnrichment]. The analyzer's Node subprocess is
 * stubbed via a [FakeAnalyzer] subclass so these tests don't depend on the host's
 * Node / `ts-json-schema-generator` install — same isolation strategy that lets the
 * loader's enrichment tests use a `StubEnrichment`.
 *
 * Covers:
 *  - Happy-path single-export `.ts` → Resolved with merged `_meta:` + analyzer-derived
 *    name/inputSchema/description.
 *  - **Multi-export `.ts` rejected** with a clear diagnostic citing every export name.
 *    Pins the Copilot/Codex review fix on PR #3338 — the original `associateBy` would
 *    have collapsed multi-export results to one arbitrary survivor.
 *  - **Missing script file** surfaces a "file does not exist" reason instead of the
 *    misleading "no typed declaration" message.
 *  - **`ScriptedToolDefinitionException` with `partialTools`** routes the healthy
 *    descriptors to Resolved and the broken ones to Failed with the analyzer's reason.
 *  - **Subprocess-level throws** propagate as Failed for every descriptor in the batch.
 *  - **`_meta:` validation** rejects typo'd `trailblaze/requiresHost` / `supportedPlatforms`
 *    shapes (parity with `TrailmapScriptedToolFile.validateKnownMetaShapes`).
 *  - **`mergeMeta` precedence** — top-level `requiresHost: true` overrides a stale
 *    `_meta: { trailblaze/requiresHost: false }`.
 */
class AnalyzerScriptedToolEnrichmentTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun mkTrailmapDir(): File = tempFolder.newFolder("trailmap")

  private fun mkScript(trailmapDir: File, name: String, content: String = "// stub"): File {
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    val file = File(toolsDir, name)
    file.writeText(content)
    return file
  }

  private fun deferred(
    relativePath: String,
    script: String,
    meta: JsonObject? = null,
    requiresHost: Boolean = false,
    supportedPlatforms: List<String>? = null,
  ): ScriptedToolEnrichment.DeferredDescriptor =
    ScriptedToolEnrichment.DeferredDescriptor(
      relativePath = relativePath,
      descriptor = TrailmapScriptedToolFile(
        script = script,
        name = null,
        meta = meta,
        requiresHost = requiresHost,
        supportedPlatforms = supportedPlatforms,
      ),
    )

  private fun stubDef(
    name: String,
    sourcePath: String,
    description: String? = null,
    inputSchema: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    spec: JsonObject? = null,
    uncapturedSpec: Boolean = false,
  ): ScriptedToolDefinition = ScriptedToolDefinition(
    name = name,
    sourcePath = sourcePath,
    line = 1,
    description = description,
    inputSchema = inputSchema,
    outputSchema = JsonObject(mapOf("type" to JsonPrimitive("string"))),
    spec = spec,
    uncapturedSpec = uncapturedSpec,
  )

  /** Test seam: drives [AnalyzerScriptedToolEnrichment] with a fixed analyzer payload. */
  private class FakeAnalyzer(
    private val behavior: suspend (File) -> List<ScriptedToolDefinition>,
  ) : ScriptedToolDefinitionAnalyzer(
    bunBinary = File("/dev/null"),
    extractorShim = File("/dev/null"),
    sdkDir = File("/dev/null"),
  ) {
    override suspend fun analyze(trailmapToolsDir: File): List<ScriptedToolDefinition> =
      behavior(trailmapToolsDir)
  }

  @Test
  fun `single-export ts resolves with analyzer-derived name and merged meta`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "openSample.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "openSample",
          sourcePath = script.absolutePath,
          description = "Open the sample app.",
          inputSchema = JsonObject(
            mapOf(
              "type" to JsonPrimitive("object"),
              "properties" to JsonObject(emptyMap()),
            ),
          ),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/openSample.yaml",
          script = "./openSample.ts",
          supportedPlatforms = listOf("ios"),
        ),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val config = resolved.configs.single()
    assertEquals("openSample", config.name)
    assertEquals("Open the sample app.", config.description)
    assertEquals(script.absolutePath, config.script)
    // _meta carries the top-level supportedPlatforms shortcut.
    val platforms = assertIs<JsonArray>(config.meta?.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("ios"), platforms.single())
  }

  @Test
  fun `meta-only descriptor whose ts passes a non-inline spec reference hard-fails`() {
    // The analyzer drops the whole spec when the (spec, handler) overload is given a `const SPEC`
    // reference (uncapturedSpec=true). A meta-only descriptor has no YAML to supply the gates, so
    // enrichment must FAIL loud rather than silently advertise an un-gated tool — the safety net for
    // agents authoring descriptor-less `.ts` tools.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "swipe.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "swipe", sourcePath = script.absolutePath, uncapturedSpec = true))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(deferred(relativePath = "tools/swipe.ts", script = "./swipe.ts")),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("non-inline spec reference") &&
        failed.reason.contains("Inline the spec"),
      "expected an actionable inline-the-spec message, got: ${failed.reason}",
    )
  }

  @Test
  fun `multi-export ts surfaces a Failed result naming every export`() {
    // Regression pin: the original `associateBy { sourcePath }` would have picked one
    // export at random and silently registered the wrong tool. After the fix the
    // ambiguity must surface with both names.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "twoExports.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "exportA", sourcePath = script.absolutePath),
        stubDef(name = "exportB", sourcePath = script.absolutePath),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/twoExports.yaml", script = "./twoExports.ts"),
      ),
    )

    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(failed.reason.contains("exportA"), "reason should name exportA: ${failed.reason}")
    assertTrue(failed.reason.contains("exportB"), "reason should name exportB: ${failed.reason}")
    assertTrue(
      failed.reason.contains("more than one"),
      "reason should explain why: ${failed.reason}",
    )
  }

  @Test
  fun `missing script file surfaces a file-not-found reason`() {
    // Pin the Copilot finding: when `script:` points at a nonexistent file, the error
    // must say "file doesn't exist", not "no typed declaration found".
    val trailmapDir = mkTrailmapDir()
    File(trailmapDir, "tools").mkdirs()
    val analyzer = FakeAnalyzer { _ -> emptyList() }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/missing.yaml", script = "./missing.ts"),
      ),
    )

    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("does not exist"),
      "expected file-not-found reason, got: ${failed.reason}",
    )
  }

  @Test
  fun `partial analyzer failure routes healthy descriptors to Resolved and broken to Failed`() {
    val trailmapDir = mkTrailmapDir()
    val healthyScript = mkScript(trailmapDir, "healthy.ts")
    val brokenScript = mkScript(trailmapDir, "broken.ts")
    val analyzer = FakeAnalyzer { _ ->
      throw ScriptedToolDefinitionException(
        message = "1 of 2 tools failed",
        errors = listOf(
          ScriptedToolDefinitionError(
            file = brokenScript.absolutePath,
            toolName = null,
            message = "Unsupported TS construct: Map<string, T>",
          ),
        ),
        partialTools = listOf(
          stubDef(name = "healthy", sourcePath = healthyScript.absolutePath),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/healthy.yaml", script = "./healthy.ts"),
        deferred(relativePath = "tools/broken.yaml", script = "./broken.ts"),
      ),
    )

    assertEquals(2, results.size)
    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(
      results.first { it.relativePath == "tools/healthy.yaml" },
    )
    assertEquals("healthy", resolved.configs.single().name)
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(
      results.first { it.relativePath == "tools/broken.yaml" },
    )
    assertTrue(
      failed.reason.contains("Unsupported TS construct"),
      "expected analyzer's per-tool error to propagate, got: ${failed.reason}",
    )
  }

  @Test
  fun `subprocess throw propagates as Failed for every deferred descriptor`() {
    val trailmapDir = mkTrailmapDir()
    mkScript(trailmapDir, "a.ts")
    mkScript(trailmapDir, "b.ts")
    val analyzer = FakeAnalyzer { _ -> throw java.io.IOException("node binary not found") }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/a.yaml", script = "./a.ts"),
        deferred(relativePath = "tools/b.yaml", script = "./b.ts"),
      ),
    )

    assertEquals(2, results.size)
    results.forEach { result ->
      val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(result)
      assertTrue(
        failed.reason.contains("analyzer subprocess failed"),
        "expected subprocess-failure reason, got: ${failed.reason}",
      )
      assertTrue(
        failed.reason.contains("node binary not found"),
        "expected underlying cause in reason, got: ${failed.reason}",
      )
    }
  }

  @Test
  fun `typo on trailblaze requiresHost meta key surfaces a clear failure`() {
    // Pin the Copilot finding: meta-only descriptors must re-validate `_meta:` shapes
    // since they bypass the legacy `TrailmapScriptedToolFile.validateKnownMetaShapes` path.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          script = "./tool.ts",
          // String "true" instead of boolean literal — the legacy
          // `validateKnownMetaShapes` rejects this with the same message shape.
          meta = JsonObject(
            mapOf("trailblaze/requiresHost" to JsonPrimitive("true")),
          ),
        ),
      ),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("trailblaze/requiresHost"),
      "expected reason to name the offending key: ${failed.reason}",
    )
    assertTrue(
      failed.reason.contains("boolean"),
      "expected reason to explain shape mismatch: ${failed.reason}",
    )
  }

  @Test
  fun `typo on trailblaze supportedPlatforms meta key surfaces a clear failure`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          script = "./tool.ts",
          meta = JsonObject(
            // String "android" instead of list — the legacy validator rejects this.
            mapOf("trailblaze/supportedPlatforms" to JsonPrimitive("android")),
          ),
        ),
      ),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("trailblaze/supportedPlatforms"),
      "expected reason to name the offending key: ${failed.reason}",
    )
    assertTrue(
      failed.reason.contains("list"),
      "expected reason to explain shape mismatch: ${failed.reason}",
    )
  }

  @Test
  fun `top-level requiresHost overrides stale meta requiresHost false`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          script = "./tool.ts",
          requiresHost = true,
          // Author copied a stale value into _meta; top-level shortcut must win.
          meta = JsonObject(
            mapOf("trailblaze/requiresHost" to JsonPrimitive(false)),
          ),
        ),
      ),
    )
    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val config = resolved.configs.single()
    assertEquals(JsonPrimitive(true), config.meta?.get("trailblaze/requiresHost"))
    assertEquals(true, config.requiresHost)
  }

  @Test
  fun `top-level supportedPlatforms overrides stale meta supportedPlatforms`() {
    // Symmetric to the requiresHost-override test above. Lead-review v2 #6.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          script = "./tool.ts",
          supportedPlatforms = listOf("ios"),
          meta = JsonObject(
            mapOf(
              "trailblaze/supportedPlatforms" to JsonArray(listOf(JsonPrimitive("web"))),
            ),
          ),
        ),
      ),
    )
    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val merged = assertIs<JsonArray>(resolved.configs.single().meta?.get("trailblaze/supportedPlatforms"))
    // Top-level shortcut wins over stale meta — the merged value reflects [ios], not [web].
    assertEquals(listOf(JsonPrimitive("ios")), merged.toList())
  }

  @Test
  fun `valid meta shapes pass through without false-positive rejection`() {
    // Pin that the validateKnownMetaShapes guard doesn't reject well-formed inputs.
    // Lead-review v2 #6.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          script = "./tool.ts",
          meta = JsonObject(
            mapOf(
              "trailblaze/requiresHost" to JsonPrimitive(true),
              "trailblaze/supportedPlatforms" to JsonArray(
                listOf(JsonPrimitive("ios"), JsonPrimitive("android")),
              ),
            ),
          ),
        ),
      ),
    )
    // No Failed result — both meta entries are well-formed and pass validation.
    assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
  }

  @Test
  fun `absolute script path resolves without reanchoring against trailmap directory`() {
    // Absolute paths in script: should pass through unchanged (mirrors the loader's own
    // absolute-path rule for `<trailmap>/tools/<file>.yaml` script fields). Lead-review v2 #6.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/tool.yaml",
          // Absolute path — not relative to descriptor parent.
          script = script.absolutePath,
        ),
      ),
    )
    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    assertEquals(script.absolutePath, resolved.configs.single().script)
  }

  @Test
  fun `blank script field surfaces a clear authoring error`() {
    // Lead-review v3: a blank `script:` resolves to the cwd and `isFile` would reject
    // it as a directory, producing the misleading "does not exist" reason. Guard before
    // File resolution surfaces the real authoring mistake.
    val trailmapDir = mkTrailmapDir()
    File(trailmapDir, "tools").mkdirs()
    val analyzer = FakeAnalyzer { _ -> emptyList() }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/blank.yaml", script = ""),
      ),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("`script:` field is blank"),
      "expected blank-script diagnostic, got: ${failed.reason}",
    )
  }

  @Test
  fun `valid ts file with non-typed exports surfaces a no-typed-declaration reason`() {
    // Lead-review v4: the "missing script file" test covers absent files; the broader
    // "valid TS source, no `trailblaze.tool<I, O>({...})` call" path was only implicit
    // (via other negative tests). Pin it standalone — author writes a syntactically
    // valid `.ts` file with helper exports but forgets the typed declaration; surface
    // must direct the author at the missing declaration, not at a file-or-extension
    // problem.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(
      trailmapDir,
      "helpersOnly.ts",
      content = """
        // Valid TS, exports a helper, no trailblaze.tool<I, O>({...}) anywhere.
        export function helper(x: string): string { return x; }
      """.trimIndent(),
    )
    val analyzer = FakeAnalyzer { _ -> emptyList() } // analyzer ran cleanly, just found nothing typed
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/helpersOnly.yaml", script = "./helpersOnly.ts"),
      ),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("no `trailblaze.tool<I, O>({...})` declaration found"),
      "expected no-typed-declaration diagnostic, got: ${failed.reason}",
    )
    assertTrue(
      failed.reason.contains(script.absolutePath),
      "expected reason to name the offending file, got: ${failed.reason}",
    )
  }

  @Test
  fun `js script extension surfaces a clear hint`() {
    // Lead-review v3: a `.js` file silently produces zero typed declarations because the
    // analyzer only recognizes TypeScript call sites. Catch and surface the real cause
    // before the generic "no typed declaration found" message would mislead the author.
    val trailmapDir = mkTrailmapDir()
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "tool.js").writeText("// JS, not TS")
    val analyzer = FakeAnalyzer { _ -> emptyList() }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = toolsDir,
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/tool.yaml", script = "./tool.js"),
      ),
    )
    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(
      failed.reason.contains("must be a `.ts` (TypeScript) source"),
      "expected .ts-required diagnostic, got: ${failed.reason}",
    )
  }

  @Test
  fun `two descriptors pointing at the same multi-export ts file both fail with multi-export reason`() {
    // Lead-review v3: pin that the multi-export rejection fires PER-DESCRIPTOR rather
    // than once across the batch. The groupBy collects all exports under one source-path
    // key; each meta-only descriptor pointing at that file must independently surface a
    // multi-export diagnostic.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "shared.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "exportA", sourcePath = script.absolutePath),
        stubDef(name = "exportB", sourcePath = script.absolutePath),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/a.yaml", script = "./shared.ts"),
        deferred(relativePath = "tools/b.yaml", script = "./shared.ts"),
      ),
    )
    assertEquals(2, results.size)
    results.forEach { result ->
      val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(result)
      assertTrue(
        failed.reason.contains("more than one"),
        "expected multi-export diagnostic for ${result.relativePath}, got: ${failed.reason}",
      )
    }
  }

  @Test
  fun `empty deferred list returns empty result without spawning analyzer`() {
    var analyzerCalls = 0
    val analyzer = FakeAnalyzer { _ ->
      analyzerCalls++
      emptyList()
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val trailmapDir = mkTrailmapDir()
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = emptyList(),
    )
    assertTrue(results.isEmpty())
    assertEquals(0, analyzerCalls, "analyzer should not be invoked for empty input")
  }

  @Test
  fun `descriptor without meta or shortcuts produces null meta on resolved config`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "tool", sourcePath = script.absolutePath))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/tool.yaml", script = "./tool.ts"),
      ),
    )
    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    // No top-level requiresHost or supportedPlatforms AND no explicit _meta → null meta
    // (distinguishes "no meta" from "empty meta" — downstream consumers branch on this).
    assertNull(resolved.configs.single().meta)
  }

  @Test
  fun `analyzer-extracted spec fields project into namespaced meta on a minimal descriptor`() {
    // The load-bearing happy path for #3352: when the descriptor is meta-only (no
    // explicit `_meta:` and no top-level shortcuts) but the analyzer extracted a
    // typed spec from the sibling `.ts`, the spec fields project into the runtime
    // `_meta` JSON via their namespaced `trailblaze/*` keys. Without this, a tool
    // authored entirely in TS (no YAML _meta) would register on every platform
    // because the runtime defaults the gates to "unrestricted" when the keys are
    // absent.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "webTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "webTool",
          sourcePath = script.absolutePath,
          spec = JsonObject(
            mapOf(
              "supportedPlatforms" to JsonArray(listOf(JsonPrimitive("web"))),
              "requiresContext" to JsonPrimitive(true),
            ),
          ),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/webTool.yaml", script = "./webTool.ts"),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val meta = assertNotNull(
      resolved.configs.single().meta,
      "expected analyzer spec to produce a non-null meta map",
    )
    val platforms = assertIs<JsonArray>(meta.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("web"), platforms.single())
    assertEquals(JsonPrimitive(true), meta.get("trailblaze/requiresContext"))
  }

  @Test
  fun `descriptor meta overrides analyzer-extracted spec on conflict`() {
    // Precedence pin: authors who set a key on the YAML descriptor (more specific,
    // authored on the tool's own surface) win over the analyzer's TS-side default.
    // The reverse would mean a YAML override is silently ignored — a surprising
    // failure mode that erodes trust in the override.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "platTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "platTool",
          sourcePath = script.absolutePath,
          spec = JsonObject(
            mapOf(
              "supportedPlatforms" to JsonArray(listOf(JsonPrimitive("web"))),
            ),
          ),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/platTool.yaml",
          script = "./platTool.ts",
          // YAML descriptor's top-level shortcut sets [ios] — analyzer says [web].
          // Descriptor wins.
          supportedPlatforms = listOf("ios"),
        ),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val meta = assertNotNull(resolved.configs.single().meta)
    val platforms = assertIs<JsonArray>(meta.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("ios"), platforms.single(), "descriptor's [ios] should win over analyzer's [web]")
  }

  @Test
  fun `null analyzer spec leaves meta unchanged from descriptor alone`() {
    // Bare-handler form (no spec at the call site): analyzer's `def.spec` is null,
    // and the merge produces the same `_meta` shape as if the analyzer change had
    // never landed. Pins the no-regression contract for existing meta-only
    // descriptors whose `.ts` files don't use the new (spec, handler) overload.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "bareTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "bareTool", sourcePath = script.absolutePath, spec = null))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(
          relativePath = "tools/bareTool.yaml",
          script = "./bareTool.ts",
          supportedPlatforms = listOf("android"),
        ),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val meta = assertNotNull(resolved.configs.single().meta)
    val platforms = assertIs<JsonArray>(meta.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("android"), platforms.single())
    // No analyzer-only keys leaked through.
    assertNull(meta.get("trailblaze/requiresContext"))
    assertNull(meta.get("trailblaze/requiresHost"))
  }

  @Test
  fun `analyzer-extracted requiresHost promotes the typed InlineScriptToolConfig requiresHost slot`() {
    // `InlineScriptToolConfig.requiresHost` is a typed Kotlin slot (Boolean, not
    // a `_meta` lookup) that the on-device dispatcher reads to skip host-only
    // tools at registration. When the analyzer says `requiresHost: true` but the
    // YAML descriptor doesn't, promote the analyzer's value onto the typed slot —
    // otherwise the dispatch gate (which short-circuits before reading `_meta`)
    // would silently register the tool on-device.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "hostTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "hostTool",
          sourcePath = script.absolutePath,
          spec = JsonObject(mapOf("requiresHost" to JsonPrimitive(true))),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        // Descriptor leaves requiresHost at its default false — analyzer should
        // promote the typed slot.
        deferred(relativePath = "tools/hostTool.yaml", script = "./hostTool.ts"),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertTrue(config.requiresHost, "analyzer's requiresHost=true should promote the typed slot")
    // Also reflected in the namespaced _meta for downstream consumers that read it.
    val meta = assertNotNull(config.meta)
    assertEquals(JsonPrimitive(true), meta.get("trailblaze/requiresHost"))
  }

  @Test
  fun `analyzer-extracted surfaceToLlm and isRecordable promote the typed slots and fold into meta`() {
    // The `.ts`-spec-as-single-source path: an author writes `surfaceToLlm: false` /
    // `isRecordable: false` in the typed spec, the analyzer extracts them, and enrichment must
    // BOTH set the typed `InlineScriptToolConfig` slots (read by the host in-process advertise +
    // recording gates) AND fold the namespaced keys into `_meta` (read by the on-device launcher).
    // The descriptor leaves both at their default `true`, so the AND combine yields `false`.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "internalStep.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "internalStep",
          sourcePath = script.absolutePath,
          spec = JsonObject(
            mapOf(
              "surfaceToLlm" to JsonPrimitive(false),
              "isRecordable" to JsonPrimitive(false),
            ),
          ),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/internalStep.yaml", script = "./internalStep.ts"),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals(false, config.surfaceToLlm, "analyzer surfaceToLlm=false must promote the typed slot")
    assertEquals(false, config.isRecordable, "analyzer isRecordable=false must promote the typed slot")
    val meta = assertNotNull(config.meta)
    assertEquals(JsonPrimitive(false), meta.get("trailblaze/surfaceToLlm"))
    assertEquals(JsonPrimitive(false), meta.get("trailblaze/isRecordable"))
  }

  @Test
  fun `default surfaceToLlm and isRecordable leave the typed slots true and emit no meta keys`() {
    // No-regression pin: a normal tool (no opt-out anywhere) must keep the typed slots `true` and
    // must NOT emit the namespaced keys — folding the `true` defaults would change the wire shape
    // for nearly every tool and the runtime parsers already default to `true` on absence.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "normalTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "normalTool", sourcePath = script.absolutePath, spec = null))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/normalTool.yaml", script = "./normalTool.ts"),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertTrue(config.surfaceToLlm)
    assertTrue(config.isRecordable)
    // meta is null here (no shortcuts, no explicit _meta, null spec) — no surface/record keys.
    assertNull(config.meta?.get("trailblaze/surfaceToLlm"))
    assertNull(config.meta?.get("trailblaze/isRecordable"))
  }

  // ============================================================================
  // Description precedence — YAML sidecar > spec `description` > TSDoc-derived.
  // `description` is the tool's primary descriptor field (NOT a `_meta` gate), so
  // it routes into `InlineScriptToolConfig.description` rather than `_meta`.
  // ============================================================================

  @Test
  fun `analyzer-extracted spec description wins over the TSDoc on a meta-only descriptor`() {
    // The NEW middle tier on the meta-only (resolveOrFail) branch: a meta-only descriptor has no
    // YAML `description:`, so the typed spec's `description` must win over the analyzer's
    // TSDoc-derived `def.description`. Without the routing, the implementation-note-y TSDoc would
    // silently become the LLM-facing description — the exact footgun this field closes.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "describedTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "describedTool",
          sourcePath = script.absolutePath,
          description = "TSDoc-derived (must lose to the spec).",
          spec = JsonObject(mapOf("description" to JsonPrimitive("Spec description (must win)."))),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/describedTool.yaml", script = "./describedTool.ts"),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("Spec description (must win).", config.description)
    // Description is a primary descriptor field — it does NOT leak into the namespaced `_meta`.
    assertNull(config.meta?.get("trailblaze/description"))
  }

  @Test
  fun `blank or non-string spec description falls back to the TSDoc on a meta-only descriptor`() {
    // Defensive guard in `specDescriptionOf`: a blank (or, via `as any`, non-string) spec
    // `description` must NOT shadow the TSDoc — it resolves to null and resolution falls through
    // to `def.description`. Pins that a malformed spec value can't silently blank the description.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "blankDescTool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "blankDescTool",
          sourcePath = script.absolutePath,
          description = "TSDoc fallback (must win when the spec value is unusable).",
          // Whitespace-only string + a stray non-string value — both ignored by specDescriptionOf.
          spec = JsonObject(
            mapOf(
              "description" to JsonPrimitive("   "),
              "requiresContext" to JsonPrimitive(true),
            ),
          ),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        deferred(relativePath = "tools/blankDescTool.yaml", script = "./blankDescTool.ts"),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("TSDoc fallback (must win when the spec value is unusable).", config.description)
  }

  @Test
  fun `spec description is the middle tier on a partial single-tool descriptor without a YAML description`() {
    // Partial single-tool (buildPartialConfig) branch: the YAML names the export but supplies no
    // `description:`, so the typed spec's `description` wins over the analyzer's TSDoc.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "tool",
          sourcePath = script.absolutePath,
          description = "TSDoc (lowest tier).",
          spec = JsonObject(mapOf("description" to JsonPrimitive("Spec description (middle tier)."))),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/tool.yaml",
          descriptor = TrailmapScriptedToolFile(script = "./tool.ts", name = "tool"),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("Spec description (middle tier).", config.description)
  }

  @Test
  fun `YAML description wins over both the spec description and the TSDoc on a partial single-tool descriptor`() {
    // Full three-tier precedence on the buildPartialConfig branch: an author who writes all three
    // gets the YAML `description:` (top tier). Guards against a refactor that lets the new spec
    // tier leapfrog the author-explicit YAML override.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "tool",
          sourcePath = script.absolutePath,
          description = "TSDoc (lowest tier).",
          spec = JsonObject(mapOf("description" to JsonPrimitive("Spec description (middle tier)."))),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/tool.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./tool.ts",
            name = "tool",
            description = "YAML description (top tier, must win).",
          ),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("YAML description (top tier, must win).", config.description)
  }

  @Test
  fun `spec description applies per entry on a partial multi-tool descriptor`() {
    // Multi-tool (buildPartialConfig per entry) branch: one entry has a YAML `description:`
    // (wins over its spec), the other leaves it blank and falls through to its spec `description`
    // (which in turn wins over that entry's TSDoc). Pins the precedence holds independently per entry.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "multi.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "entryWithYamlDesc",
          sourcePath = script.absolutePath,
          description = "TSDoc A (lowest).",
          spec = JsonObject(mapOf("description" to JsonPrimitive("Spec A (middle)."))),
        ),
        stubDef(
          name = "entryWithSpecDesc",
          sourcePath = script.absolutePath,
          description = "TSDoc B (lowest).",
          spec = JsonObject(mapOf("description" to JsonPrimitive("Spec B (middle, must win)."))),
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/multi.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./multi.ts",
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "entryWithYamlDesc",
                description = "YAML A (top, must win).",
              ),
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "entryWithSpecDesc",
              ),
            ),
          ),
        ),
      ),
    )

    val byName = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
      .configs.associateBy { it.name }
    assertEquals("YAML A (top, must win).", assertNotNull(byName["entryWithYamlDesc"]).description)
    assertEquals("Spec B (middle, must win).", assertNotNull(byName["entryWithSpecDesc"]).description)
  }

  @Test
  fun `meta-only descriptor inlines a named-enum input schema ref the analyzer emitted`() {
    // Regression guard for the enum-param subprocess-registration bug. ts-json-schema-generator
    // (expose:"all") emits a NAMED string-literal union (`type Dir = "UP" | "DOWN"`) as a property
    // `$ref` into a sibling `definitions` bag. The subprocess wrapper's zod converter throws on a
    // bare `$ref`, so enrichment MUST inline it before the schema reaches `InlineScriptToolConfig`.
    // Pre-fix this passed `def.inputSchemaObject` through unchanged and this assertion fails.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "enumTool.ts")
    val refSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf("direction" to JsonObject(mapOf("\$ref" to JsonPrimitive("#/definitions/Dir")))),
        ),
        "definitions" to JsonObject(
          mapOf(
            "Dir" to JsonObject(
              mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(JsonPrimitive("UP"), JsonPrimitive("DOWN"))),
              ),
            ),
          ),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "enumTool", sourcePath = script.absolutePath, inputSchema = refSchema))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(deferred(relativePath = "tools/enumTool.yaml", script = "./enumTool.ts")),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    val schema = config.inputSchema
    assertNull(schema["definitions"], "the definitions bag must be dropped after inlining")
    val direction = assertIs<JsonObject>(
      assertIs<JsonObject>(schema["properties"])["direction"],
    )
    assertNull(direction["\$ref"], "the enum \$ref must be inlined, not passed through to the synthesizer")
    assertEquals(JsonPrimitive("string"), direction["type"])
    assertEquals(
      listOf(JsonPrimitive("UP"), JsonPrimitive("DOWN")),
      assertIs<JsonArray>(direction["enum"]).toList(),
    )
  }

  @Test
  fun `partial single-tool descriptor inlines a named-enum ref from the analyzer`() {
    // Same flattening, exercised through the partial single-tool branch (buildPartialConfig) where
    // the descriptor names the export but leaves inputSchema to the analyzer.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "enumTool.ts")
    val refSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf("mode" to JsonObject(mapOf("\$ref" to JsonPrimitive("#/definitions/Mode")))),
        ),
        "definitions" to JsonObject(
          mapOf(
            "Mode" to JsonObject(
              mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(JsonPrimitive("FAST"), JsonPrimitive("SLOW"))),
              ),
            ),
          ),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "enumTool", sourcePath = script.absolutePath, inputSchema = refSchema))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/enumTool.yaml",
          descriptor = TrailmapScriptedToolFile(script = "./enumTool.ts", name = "enumTool"),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    val mode = assertIs<JsonObject>(assertIs<JsonObject>(config.inputSchema["properties"])["mode"])
    assertNull(mode["\$ref"])
    assertEquals(
      listOf(JsonPrimitive("FAST"), JsonPrimitive("SLOW")),
      assertIs<JsonArray>(mode["enum"]).toList(),
    )
  }

  @Test
  fun `partial multi-tool descriptor inlines a named-enum ref per matched entry`() {
    // The multi-tool branch (descriptor.tools list) flows through the same buildPartialConfig flatten
    // site as the single-tool branch — pin that an enum `$ref` on a multi-tool entry inlines too.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "multiEnum.ts")
    fun enumSchema(name: String, vararg values: String) = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf(name to JsonObject(mapOf("\$ref" to JsonPrimitive("#/definitions/E")))),
        ),
        "definitions" to JsonObject(
          mapOf(
            "E" to JsonObject(
              mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(values.map { JsonPrimitive(it) }),
              ),
            ),
          ),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "toolA", sourcePath = script.absolutePath, inputSchema = enumSchema("dir", "UP", "DOWN")),
        stubDef(name = "toolB", sourcePath = script.absolutePath, inputSchema = enumSchema("mode", "FAST", "SLOW")),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/multiEnum.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./multiEnum.ts",
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(name = "toolA"),
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(name = "toolB"),
            ),
          ),
        ),
      ),
    )

    val byName = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
      .configs.associateBy { it.name }
    val dir = assertIs<JsonObject>(assertIs<JsonObject>(byName.getValue("toolA").inputSchema["properties"])["dir"])
    assertNull(dir["\$ref"])
    assertEquals(listOf(JsonPrimitive("UP"), JsonPrimitive("DOWN")), assertIs<JsonArray>(dir["enum"]).toList())
    val mode = assertIs<JsonObject>(assertIs<JsonObject>(byName.getValue("toolB").inputSchema["properties"])["mode"])
    assertNull(mode["\$ref"])
    assertEquals(listOf(JsonPrimitive("FAST"), JsonPrimitive("SLOW")), assertIs<JsonArray>(mode["enum"]).toList())
  }

  // ============================================================================
  // Partial single-tool descriptors — YAML carries `name:` + maybe `_meta:` /
  // shortcuts; analyzer fills description + inputSchema from the typed `.ts`.
  // ============================================================================

  @Test
  fun `partial single-tool descriptor pulls description and inputSchema from analyzer`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "contacts_ios_addPhoneNumber.ts")
    val analyzerSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf(
            "name" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "phoneNumber" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          ),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "contacts_ios_addPhoneNumber",
          sourcePath = script.absolutePath,
          description = "Add a new phone number to an existing iOS contact.",
          inputSchema = analyzerSchema,
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "contacts",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        // Partial single-tool: name + supportedPlatforms shortcut, no description / inputSchema.
        // The descriptor isn't constructed via `deferred(...)` because that helper assumes
        // meta-only (name = null) — partial descriptors carry the name explicitly.
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/contacts_ios_addPhoneNumber.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./contacts_ios_addPhoneNumber.ts",
            name = "contacts_ios_addPhoneNumber",
            supportedPlatforms = listOf("ios"),
          ),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    // YAML's `name:` wins (load-bearing for `target.tools:` resolution).
    assertEquals("contacts_ios_addPhoneNumber", config.name)
    // Description comes from the analyzer since the YAML didn't provide one.
    assertEquals("Add a new phone number to an existing iOS contact.", config.description)
    // inputSchema comes from the analyzer's `<I>`-generic-extracted JSON Schema.
    assertEquals(analyzerSchema, config.inputSchema)
    // Platform gate from the top-level shortcut still flows through.
    val platforms = assertIs<JsonArray>(config.meta?.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("ios"), platforms.single())
  }

  @Test
  fun `partial single-tool descriptor with mismatched name surfaces a Failed result`() {
    // Authoring error: the YAML's `name:` doesn't match any of the `.ts`'s exports.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "actualExportName", sourcePath = script.absolutePath),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/tool.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./tool.ts",
            name = "whatTheAuthorTyped",
          ),
        ),
      ),
    )

    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(failed.reason.contains("whatTheAuthorTyped"), "reason should cite YAML name: ${failed.reason}")
    assertTrue(failed.reason.contains("actualExportName"), "reason should cite available exports: ${failed.reason}")
  }

  @Test
  fun `partial single-tool descriptor picks the matching export from a multi-export ts`() {
    // Partial single-tool descriptors lift the "exactly one export" restriction —
    // the YAML's `name:` IS the disambiguator. This pins the contract.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "multi.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "exportA", sourcePath = script.absolutePath, description = "A's description"),
        stubDef(name = "exportB", sourcePath = script.absolutePath, description = "B's description"),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/multi.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./multi.ts",
            name = "exportB",
          ),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("exportB", config.name)
    assertEquals("B's description", config.description)
  }

  @Test
  fun `partial single-tool descriptor with explicit YAML description and inputSchema overrides analyzer`() {
    // Pin the YAML-wins-on-conflict precedence documented on `buildPartialConfig`. An
    // author who authors `description:` and `inputSchema:` on their partial YAML
    // expects those values to override whatever the `.ts` declared — the analyzer is a
    // GAP-FILLER, not a source-of-truth replacement. This test protects against a
    // future refactor that flips the precedence (e.g., trying to "always prefer the
    // typed source") and silently drops author-explicit overrides.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "tool.ts")
    // Analyzer's view: one description string + one input-property schema.
    val analyzerSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf("analyzerField" to JsonObject(mapOf("type" to JsonPrimitive("string")))),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "tool",
          sourcePath = script.absolutePath,
          description = "Analyzer-derived description (should be ignored).",
          inputSchema = analyzerSchema,
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/tool.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./tool.ts",
            name = "tool",
            // YAML-explicit description — must win over the analyzer's.
            description = "Author-explicit description (must win).",
            // YAML-explicit inputSchema — must win over the analyzer's.
            inputSchema = mapOf(
              "yamlField" to xyz.block.trailblaze.config.project.ScriptedToolProperty(
                type = "string",
                description = "Field declared in the YAML, not in the .ts.",
                required = true,
              ),
            ),
          ),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals("Author-explicit description (must win).", config.description)
    // YAML's inputSchema is translated into the same JSON-Schema shape via
    // `buildInputSchemaObject` (the now-public helper in `:trailblaze-models`).
    val properties = assertIs<JsonObject>(config.inputSchema?.get("properties"))
    assertNotNull(properties["yamlField"], "YAML field must be in the resolved schema")
    assertNull(properties["analyzerField"], "analyzer's field must NOT leak through when the YAML overrides")
  }

  // ============================================================================
  // Partial multi-tool descriptors — YAML carries `tools: [{name}, ...]`;
  // analyzer matches each entry by name and fills the missing fields.
  // ============================================================================

  @Test
  fun `partial multi-tool descriptor emits one resolved config per entry matched by name`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "sample_web_sign_in.ts")
    val signInSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    val signInWithCredsSchema = JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(
          mapOf(
            "email" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            "password" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          ),
        ),
      ),
    )
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "sample_webSignIn",
          sourcePath = script.absolutePath,
          description = "Sign in via account-resolver key.",
          inputSchema = signInSchema,
        ),
        stubDef(
          name = "sample_webSignInWithCredentials",
          sourcePath = script.absolutePath,
          description = "Sign in with explicit credentials.",
          inputSchema = signInWithCredsSchema,
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "sampleapp",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/sample_web_sign_in.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./sample_web_sign_in.ts",
            supportedPlatforms = listOf("web"),
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "sample_webSignIn",
              ),
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "sample_webSignInWithCredentials",
              ),
            ),
          ),
        ),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    assertEquals(2, resolved.configs.size, "one config per YAML `tools:` entry")
    val byName = resolved.configs.associateBy { it.name }
    val signIn = assertNotNull(byName["sample_webSignIn"])
    assertEquals("Sign in via account-resolver key.", signIn.description)
    assertEquals(signInSchema, signIn.inputSchema)
    val signInWithCreds = assertNotNull(byName["sample_webSignInWithCredentials"])
    assertEquals("Sign in with explicit credentials.", signInWithCreds.description)
    assertEquals(signInWithCredsSchema, signInWithCreds.inputSchema)
    // File-wide supportedPlatforms shortcut applies to every entry.
    val platforms = assertIs<JsonArray>(signIn.meta?.get("trailblaze/supportedPlatforms"))
    assertEquals(JsonPrimitive("web"), platforms.single())
  }

  @Test
  fun `partial multi-tool descriptor with an entry name not in the ts surfaces a Failed result`() {
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "multi.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(name = "exportA", sourcePath = script.absolutePath),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/multi.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./multi.ts",
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(name = "exportA"),
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(name = "ghostExport"),
            ),
          ),
        ),
      ),
    )

    val failed = assertIs<ScriptedToolEnrichment.EnrichmentResult.Failed>(results.single())
    assertTrue(failed.reason.contains("ghostExport"), "reason should name the missing entry: ${failed.reason}")
    assertTrue(failed.reason.contains("exportA"), "reason should cite available exports: ${failed.reason}")
  }

  @Test
  fun `partial multi-tool descriptor with per-entry description override resolves per-entry`() {
    // Multi-tool variant of the YAML-wins precedence pin. One YAML entry declares an
    // explicit `description:` that must win over the analyzer's TSDoc; the OTHER entry
    // leaves description blank and inherits from the analyzer. Both ride through the
    // same `buildPartialConfig` call site; the test asserts each entry independently.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "multi.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(
        stubDef(
          name = "explicitDescription",
          sourcePath = script.absolutePath,
          description = "Analyzer-derived (must be overridden).",
        ),
        stubDef(
          name = "inheritsFromAnalyzer",
          sourcePath = script.absolutePath,
          description = "Analyzer-derived (must be preserved).",
        ),
      )
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/multi.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./multi.ts",
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "explicitDescription",
                // YAML-explicit per-entry description — must win over analyzer.
                description = "YAML-explicit (must win).",
              ),
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "inheritsFromAnalyzer",
                // No description on the YAML entry — analyzer's value should pass through.
              ),
            ),
          ),
        ),
      ),
    )

    val resolved = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single())
    val byName = resolved.configs.associateBy { it.name }
    val explicit = assertNotNull(byName["explicitDescription"])
    assertEquals(
      "YAML-explicit (must win).",
      explicit.description,
      "per-entry YAML description must override the analyzer's TSDoc",
    )
    val inherited = assertNotNull(byName["inheritsFromAnalyzer"])
    assertEquals(
      "Analyzer-derived (must be preserved).",
      inherited.description,
      "entry without explicit description must inherit from the analyzer",
    )
  }

  @Test
  fun `per-entry _meta cannot re-introduce a surfaceToLlm true that contradicts the combined false slot`() {
    // Regression for the typed-slot-vs-_meta divergence on the per-entry overlay path: a file-wide
    // `surfaceToLlm: false` shortcut makes the typed slot false, but a per-entry raw
    // `_meta:{trailblaze/surfaceToLlm: true}` is overlaid AFTER mergeMeta's fold. Without the
    // re-assert, the on-device `_meta` (read by QuickJsToolMeta) would say true while the host's
    // typed slot says false. Pin that the combined opt-out wins in BOTH.
    val trailmapDir = mkTrailmapDir()
    val script = mkScript(trailmapDir, "entryMeta.ts")
    val analyzer = FakeAnalyzer { _ ->
      listOf(stubDef(name = "step", sourcePath = script.absolutePath, spec = null))
    }
    val enrichment = AnalyzerScriptedToolEnrichment(analyzer)
    val results = enrichment.enrich(
      trailmapId = "trailmap",
      trailmapDir = trailmapDir,
      trailmapToolsDir = File(trailmapDir, "tools"),
      deferredDescriptors = listOf(
        ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = "tools/entryMeta.yaml",
          descriptor = TrailmapScriptedToolFile(
            script = "./entryMeta.ts",
            // File-wide opt-out via the shortcut.
            surfaceToLlm = false,
            tools = listOf(
              xyz.block.trailblaze.config.project.TrailmapScriptedToolEntry(
                name = "step",
                // Contradictory per-entry raw `_meta` — must NOT win over the combined false.
                meta = buildJsonObject { put("trailblaze/surfaceToLlm", JsonPrimitive(true)) },
              ),
            ),
          ),
        ),
      ),
    )

    val config = assertIs<ScriptedToolEnrichment.EnrichmentResult.Resolved>(results.single()).configs.single()
    assertEquals(false, config.surfaceToLlm, "typed slot must reflect the file-wide opt-out")
    assertEquals(
      JsonPrimitive(false),
      config.meta?.get("trailblaze/surfaceToLlm"),
      "the combined opt-out must be re-asserted into `_meta` so on-device agrees with the typed slot",
    )
  }
}

package xyz.block.trailblaze.host

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.util.Console
import kotlin.system.exitProcess

/**
 * JVM `main` that derives the runtime [InlineScriptToolConfig] for every **YAML-less** scripted
 * `.ts` tool under a trailmaps directory and writes them to a JSON file — invoked from a Gradle
 * `JavaExec` task in [the bundled-config convention plugin][TrailblazeBundledConfigPlugin-equivalent]
 * so the build-time `targets/<id>.yaml` generator can describe a `.ts` tool from its
 * `trailblaze.tool<I, O>({...})` declaration alone, with no hand-written descriptor YAML.
 *
 * **Why a dedicated main and not the build-logic generator.** The analyzer
 * ([ScriptedToolDefinitionAnalyzer], spawned via `bun`) and the enrichment that turns its output
 * into an [InlineScriptToolConfig] live in `:trailblaze-common` / `:trailblaze-host`, which the lean
 * Gradle plugin classpath intentionally doesn't link (koog + models graph). Mirroring
 * [WorkspaceCompileMain], a `JavaExec` against `:trailblaze-host`'s runtime classpath gets us the
 * exact production resolution path without inflating build-logic. This is the same dogfooding the
 * binary/CLI uses: a contributor's `.ts` is self-describing; framework tools now resolve the same
 * way.
 *
 * **What "YAML-less" means here.** A `.ts` under `<trailmap>/tools/` with no sibling `<name>.yaml`
 * descriptor. Tools that still ship a descriptor YAML keep flowing through the build-logic kaml path
 * untouched — this main only fills the gap for descriptor-less `.ts` files, so the migration is
 * opt-in per file (delete a sidecar to adopt the analyzer path).
 *
 * **Reuses production enrichment verbatim.** Each descriptor-less `.ts` becomes a meta-only
 * [TrailmapScriptedToolFile] (`script: ./<name>.ts`, no other fields) — the same synthetic shape the
 * runtime loader's bare-`.ts` pass builds — and is resolved through
 * [AnalyzerScriptedToolEnrichment.enrich]. Name/description/inputSchema come from the analyzer
 * (export binding / TSDoc / `<I>` generic); `surfaceToLlm` / `isRecordable` / `supportedPlatforms` /
 * `requiresHost` come from the inline `tool(spec, handler)` spec, folded into `_meta`.
 *
 * **Usage.**
 *  - `BundledScriptedToolAnalyzeMain <trailmapsDir> <outputJsonFile>` — emit the configs.
 *  - `BundledScriptedToolAnalyzeMain --verify-determinism <trailmapsDir>` — resolve twice and assert
 *    byte-identical output, so a non-reproducible analyzer regression is caught and localized here
 *    rather than surfacing as an opaque committed-`targets/<id>.yaml` diff in CI. Both passes run in
 *    the SAME process, so they share whatever environment the caller set; the caller (the Gradle
 *    `verifyBundledScriptedToolDeterminism` task) sets `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE=1` for the
 *    whole run — read once at JVM start — so NEITHER pass is served from the analyzer cache and the
 *    two passes are genuinely independent.
 *
 * **Output JSON shape.** `{ "<trailmapId>": { "<toolName>": <InlineScriptToolConfig JSON> } }`.
 * `script` is left as the analyzer's absolute path; the build-logic consumer relativizes it against
 * its configured `scriptRootDir` exactly like the kaml path does, so the committed YAML stays
 * machine-independent. Defaults are omitted (`encodeDefaults = false`) so the emitted shape matches
 * the lean per-tool entry the kaml path produces.
 */
object BundledScriptedToolAnalyzeMain {

  private val json = Json {
    prettyPrint = true
    encodeDefaults = false
  }

  /** Top-level `id: <value>` line in a trailmap manifest (column 0, optional quotes/comment). */
  private val TRAILMAP_ID_LINE = Regex("""^id:\s*["']?([A-Za-z0-9_.\-]+)["']?\s*(#.*)?$""")

  /**
   * A `.ts` module that exports at least one typed tool (`export const x = trailblaze.tool<...>(`
   * or `(...)`). Mirrors `TrailblazeProjectConfigLoader`'s bare-`.ts` discovery gate verbatim so a
   * build-time descriptor-less `.ts` is treated as a tool source iff the runtime would also treat it
   * as one — and a shared helper module (e.g. a `login_common.ts` imported by other tools but
   * exporting no `trailblaze.tool` itself) is skipped, not mis-fed to the analyzer.
   *
   * SISTER-IMPL-TAG: typed-tool-binding-pattern. This regex MUST stay in lockstep with the runtime
   * copy in `TrailblazeProjectConfigLoader` (the bare-`.ts` discovery pass) — build-time and runtime
   * discovery have to agree on what counts as a tool source, or a `.ts` will be described at build
   * time but skipped at runtime (or vice versa). There is no compile-time check that they agree.
   */
  private val TYPED_TOOL_BINDING_PATTERN =
    Regex("""(?m)^\s*export\s+const\s+\w+\s*=\s*trailblaze\.tool\s*[<(]""")

  @JvmStatic
  fun main(args: Array<String>) {
    if (args.isEmpty()) {
      Console.error(
        "BundledScriptedToolAnalyzeMain: usage: <trailmapsDir> <outputJsonFile> | " +
          "--verify-determinism <trailmapsDir>",
      )
      exitProcess(2)
    }

    if (args[0] == "--verify-determinism") {
      val trailmapsDir = File(requireArg(args, 1, "trailmapsDir"))
      verifyDeterminism(trailmapsDir)
      return
    }

    val trailmapsDir = File(args[0])
    val outFile = File(requireArg(args, 1, "outputJsonFile"))
    val result = analyzeAll(trailmapsDir)
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(JsonObject.serializer(), result))
    Console.log(
      "BundledScriptedToolAnalyzeMain: wrote ${result.size} trailmap group(s) of analyzer-derived " +
        "scripted-tool configs to ${outFile.absolutePath}",
    )
  }

  /**
   * Resolve every descriptor-less `.ts` under each trailmap's `tools/` dir into an
   * [InlineScriptToolConfig], grouped by trailmap id. Trailmaps with no descriptor-less `.ts`
   * contribute nothing (so a repo with zero adopters yields `{}`).
   */
  private fun analyzeAll(trailmapsDir: File): JsonObject {
    if (!trailmapsDir.isDirectory) {
      error("trailmapsDir does not exist or is not a directory: ${trailmapsDir.absolutePath}")
    }
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
      ?: error(
        "Scripted-tool analyzer unavailable — `bun` must be on PATH (Hermit-pinned) and the SDK at " +
          "TRAILBLAZE_SDK_DIR must carry node_modules/ts-json-schema-generator. Ensure the " +
          "installTrailblazeScriptingSdk task ran before this JavaExec.",
      )

    return buildJsonObject {
      // Walk recursively for every `trailmap.yaml` — matching the consumer's
      // `TrailblazeBundledConfigTasks.discoverTrailmapFiles()` (`walkTopDown`), so a NESTED trailmap
      // (e.g. `trailmaps/a/b/trailmap.yaml`) is covered here too. A direct-children-only scan would
      // let the generator reference a descriptor-less tool the analyzer never emitted.
      trailmapsDir.walkTopDown()
        .filter { it.isFile && it.name == "trailmap.yaml" }
        .sortedBy { it.absolutePath }
        .forEach { manifest ->
          val trailmapDir = manifest.parentFile ?: return@forEach
          val toolsDir = File(trailmapDir, "tools")
          if (!toolsDir.isDirectory) return@forEach

          val descriptorlessTs = toolsDir.listFiles().orEmpty()
            .filter { it.isFile && it.isDescriptorlessTypedToolFile(toolsDir) }
            .sortedBy { it.name }
          if (descriptorlessTs.isEmpty()) return@forEach

          val trailmapId = readTrailmapId(manifest)
            ?: error("Trailmap manifest ${manifest.absolutePath} has no top-level `id:`")

          val deferred = descriptorlessTs.map { ts ->
            ScriptedToolEnrichment.DeferredDescriptor(
              relativePath = "tools/${ts.name}",
              descriptor = TrailmapScriptedToolFile(script = "./${ts.name}"),
            )
          }
          val results = enrichment.enrich(trailmapId, trailmapDir, toolsDir, deferred)

          val seenNames = mutableSetOf<String>()
          val configsForTrailmap = buildJsonObject {
            results.forEach { r ->
              when (r) {
                is ScriptedToolEnrichment.EnrichmentResult.Resolved ->
                  r.configs.forEach { cfg ->
                    // Fail loud on duplicate names — the runtime loader rejects two descriptor-less
                    // `.ts` tools resolving to the same name, so silently last-write-wins here would
                    // produce analyzer JSON that disagrees with runtime behavior.
                    if (!seenNames.add(cfg.name)) {
                      error(
                        "Trailmap '$trailmapId' has two descriptor-less `.ts` tools that resolve to " +
                          "the same name '${cfg.name}'. Tool names must be unique within a trailmap — " +
                          "rename one of the conflicting `trailblaze.tool` exports.",
                      )
                    }
                    val cfgJson =
                      json.encodeToJsonElement(InlineScriptToolConfig.serializer(), cfg).jsonObject
                    // `cfg.inputSchema` is already `$ref`-flattened: AnalyzerScriptedToolEnrichment
                    // runs it through ScriptedToolSchemaRefFlattener before returning the config, so
                    // the committed schema is self-contained + synthesizer-safe with no extra work
                    // here (the build-time path and the runtime path share that one flattening seam).
                    put(cfg.name, cfgJson)
                  }
                is ScriptedToolEnrichment.EnrichmentResult.Failed ->
                  error(
                    "Analyzer could not derive a tool from ${trailmapDir.name}/${r.relativePath}: " +
                      r.reason,
                  )
              }
            }
          }
          if (configsForTrailmap.isNotEmpty()) put(trailmapId, configsForTrailmap)
        }
    }
  }

  private fun verifyDeterminism(trailmapsDir: File) {
    val first = analyzeAll(trailmapsDir)
    val second = analyzeAll(trailmapsDir)
    val firstText = json.encodeToString(JsonObject.serializer(), first)
    val secondText = json.encodeToString(JsonObject.serializer(), second)
    if (firstText != secondText) {
      Console.error(
        "BundledScriptedToolAnalyzeMain: analyzer output is NON-DETERMINISTIC across two passes — " +
          "the committed targets/*.yaml would drift. First pass length=${firstText.length}, " +
          "second=${secondText.length}.",
      )
      exitProcess(1)
    }
    Console.log("BundledScriptedToolAnalyzeMain: analyzer output is deterministic across two passes.")
  }

  /**
   * A `.ts` under [toolsDir] that has no sibling `<name>.yaml` descriptor. `.d.ts` / `.test.ts`
   * are never tool sources. The sibling check is the opt-in boundary: a tool keeps its build-logic
   * kaml path until its descriptor YAML is deleted.
   */
  private fun File.isDescriptorlessTypedToolFile(toolsDir: File): Boolean {
    val n = name
    if (!n.endsWith(".ts") || n.endsWith(".d.ts") || n.endsWith(".test.ts")) return false
    val sibling = File(toolsDir, n.removeSuffix(".ts") + ".yaml")
    if (sibling.exists()) return false
    // Skip shared helper modules — only a `.ts` that exports a typed tool is a tool source.
    return TYPED_TOOL_BINDING_PATTERN.containsMatchIn(readText())
  }

  private fun readTrailmapId(manifest: File): String? =
    manifest.readLines().firstNotNullOfOrNull { line ->
      TRAILMAP_ID_LINE.matchEntire(line.trimEnd())?.groupValues?.get(1)
    }

  private fun requireArg(args: Array<String>, index: Int, label: String): String =
    args.getOrNull(index) ?: error("BundledScriptedToolAnalyzeMain: missing required arg <$label>")
}

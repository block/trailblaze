package xyz.block.trailblaze.host

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.bundle.ToolFrameworkMetadata
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailblazeToolParameterConfig
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.scripting.ScriptedToolDefinition
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionCache
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionException
import xyz.block.trailblaze.toolcalls.HandCuratedRecordableTools
import xyz.block.trailblaze.toolcalls.SelectorParamTs
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.selectorParamsForTs
import xyz.block.trailblaze.toolcalls.toScriptedToolDescriptor
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.util.Console

/**
 * Per-trailmap typed-bindings emitter — phase B of the trailmap-typing redesign. Replaces the
 * previous workspace-level [PerTargetClientDtsEmitter] (one `client.<target-id>.d.ts`
 * per target under `<workspace>/config/tools/.trailblaze/`) with one
 * `<trailmapDir>/tools/trailblaze-client.d.ts` per filesystem-backed trailmap.
 *
 * ## Three-layer trailmap-typing model
 *
 * Trailblaze trailmaps compose around three distinct concerns, each with its own
 * resolution rule (see `TrailmapRuntimeRegistryResolver`'s kdoc for the full breakdown):
 *
 *   | Concern | Composition rule |
 *   |---|---|
 *   | **Runtime registry** (what CAN dispatch) | Transitive union of every trailmap-in-closure's `platforms.*.tool_sets:` |
 *   | **Agent toolbox** (what the LLM sees) | Closest-wins on the target's `tool_sets:` + library `exports:` |
 *   | **Typed surface per trailmap** (what `.ts` author can call) | Trailmap's OWN declarations (non-transitive) + transitive `exports:` from deps |
 *
 * This emitter materializes the **typed-surface** layer. Each trailmap's emitted
 * `trailblaze-client.d.ts` covers exactly what its `.ts` authors can reach via `client.callTool` /
 * `client.tools.<name>`:
 *
 *   1. **Kotlin tools** resolved from THIS trailmap's OWN `platforms.<p>.tool_sets:`
 *      declarations (top-level `platforms:` for library trailmaps;
 *      `target.platforms:` for target trailmaps). Resolution runs through the same
 *      [TrailblazeToolSetCatalog.resolve] primitive the daemon uses for the
 *      runtime registry — single source of truth, no parallel definition.
 *   2. **Trailmap-local scripted tools** declared on the trailmap's own `target.tools:` list.
 *   3. **Transitively-inherited scripted tools** from deps' `exports:` field — for each
 *      dep in the trailmap's closure, scripted tools whose name appears in that dep's
 *      `exports:` list flow into the consumer's typed surface.
 *
 * Classpath-backed trailmaps are skipped — they live inside JARs and can't accept written
 * `trailblaze-client.d.ts` files. Their consumers still get typed bindings through the workspace
 * trailmaps that depend on them (transitive exports).
 *
 * Failure handling is the caller's responsibility — this helper just emits or throws.
 * The CLI's `trailblaze compile` lets exceptions propagate; the daemon-init bootstrap
 * downgrades to a warning so codegen failure doesn't take the daemon down with it.
 */
object PerTrailmapClientDtsEmitter {

  /**
   * Emit one `trailblaze-client.d.ts` per filesystem-backed trailmap in [resolvedTrailmaps]. Returns the
   * absolute paths of every file written.
   *
   * Empty trailmap list, or a pool consisting entirely of classpath-backed trailmaps, returns
   * an empty list (no-op).
   *
   * [catalog] defaults to the classpath-scanned [TrailblazeToolSetCatalog.defaultEntries]
   * — production callers should rely on the default. Test seam: tests pass a synthetic
   * catalog so they can pin tool-class-level resolution behavior without having to add real
   * tools to the default catalog or rebuild it from disk.
   *
   * **Blocking semantics.** Internally drives [ScriptedToolDefinitionAnalyzer.analyze]
   * via `runBlocking` on the calling thread (the analyzer is `suspend` for symmetry with
   * its own kotlinx-coroutines internals, but this emitter runs at daemon startup /
   * `trailblaze compile` — both single-shot contexts). Production call sites
   * ([xyz.block.trailblaze.host.WorkspaceCompileBootstrap],
   * [xyz.block.trailblaze.cli.CompileCommand]) invoke this once during initialization.
   * Do NOT invoke from a request-serving thread.
   */
  fun emit(
    resolvedTrailmaps: List<ResolvedTrailmap>,
    catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
    analyzer: ScriptedToolDefinitionAnalyzer? = resolveAnalyzerOrNull(),
  ): List<Path> {
    if (resolvedTrailmaps.isEmpty()) return emptyList()
    val trailmapsById = resolvedTrailmaps.associateBy { it.manifest.id }
    val generator = WorkspaceClientDtsGenerator()

    // Once-per-emit breadcrumb so a developer puzzling over "why doesn't my IDE show
    // typed results?" can grep `[PerTrailmapClientDtsEmitter]` and immediately see whether
    // the analyzer was active. The resolver itself stays silent on individual misses
    // (PATH, sdkDir, shim, node_modules) to avoid recurring noisy warnings on hosts
    // without bun — this single line at the call site is enough to tell the story
    // without polluting every daemon startup with a multi-line preflight dump.
    if (analyzer == null) {
      Console.info(
        "[PerTrailmapClientDtsEmitter] analyzer disabled — emitting per-trailmap trailblaze-client.d.ts " +
          "with YAML-derived flat shapes. To enable typed args/result upgrades, install " +
          "`bun` and run `bun install` under sdks/typescript.",
      )
    }

    // Pre-analyze every filesystem-backed trailmap ONCE so a diamond-shape dep graph
    // (two consumers depending on the same library trailmap) doesn't re-spawn the
    // bun subprocess for the library trailmap twice. Empty map when analyzer is null.
    // Codex bot caught the underlying gap that motivated this pre-pass: without it
    // each consumer trailmap only saw its own `tools/` directory, so typed tools
    // transitively exported by a dep landed in the consumer's `trailblaze-client.d.ts` with
    // YAML-flat `args` and the today-default `result: string` — a real cross-trailmap
    // regression for the typed surface story.
    val analyzerOutputByTrailmapId: Map<String, List<ScriptedToolDefinition>> =
      analyzeAllTrailmapsOnce(resolvedTrailmaps, analyzer)

    return resolvedTrailmaps.mapNotNull { trailmap ->
      val trailmapDir = (trailmap.source as? TrailmapSource.Filesystem)?.trailmapDir ?: return@mapNotNull null

      val kotlinTools = resolveKotlinToolDescriptorsForTrailmap(trailmap, catalog)
      val scriptedTools = collectTrailmapTypedScriptedTools(trailmap, trailmapsById)
      val typedOverrides = collectTypedToolOverridesForClosure(
        trailmap = trailmap,
        trailmapsById = trailmapsById,
        analyzerOutputByTrailmapId = analyzerOutputByTrailmapId,
      )

      val emittedPath = generator.generateForTrailmapFromResolved(
        trailmapDir = trailmapDir.toPath(),
        toolDescriptors = kotlinTools.descriptors,
        scriptedTools = scriptedTools,
        typedToolOverrides = typedOverrides,
        frameworkMetadataByName = kotlinTools.frameworkMetadataByName,
        extraParamsByToolName = kotlinTools.selectorParamsByName,
      )
      // Emit the machine-readable arg-type sidecar next to the .d.ts so the trail-recording
      // validator can coerce recorded args to their declared types (see
      // [TrailValidationDescriptorSidecar]). Same resolved tool set as the .d.ts, so they can't drift.
      writeValidationDescriptorSidecar(trailmapDir.toPath(), kotlinTools, scriptedTools, typedOverrides)
      emittedPath
    }
  }

  /**
   * Best-effort write of the [TrailValidationDescriptorSidecar] for [trailmapDir]. Strictly
   * non-fatal — a failure here must never take down typed-binding codegen, so it's logged and
   * swallowed; the validator falls back to no-coercion for a missing sidecar.
   */
  private fun writeValidationDescriptorSidecar(
    trailmapDir: Path,
    kotlinTools: KotlinToolResolution,
    scriptedTools: List<InlineScriptToolConfig>,
    typedOverrides: Map<String, WorkspaceClientDtsGenerator.TypedToolOverride>,
  ) {
    try {
      TrailValidationDescriptorSidecar.write(
        trailmapDir = trailmapDir,
        descriptors = TrailValidationDescriptorSidecar.buildValidationDescriptors(
          kotlinDescriptors = kotlinTools.descriptors,
          scriptedTools = scriptedTools,
          typedOverrides = typedOverrides,
        ),
      )
    } catch (e: Exception) {
      Console.error(
        "[PerTrailmapClientDtsEmitter] failed to write validation descriptor sidecar for " +
          "$trailmapDir (ignored — recording validation falls back to no arg coercion): " +
          (e.message ?: e::class.simpleName),
      )
    }
  }

  /**
   * Emit a **validation-only** typed surface (`<outputBaseDir>/<id>/tools/trailblaze-client.d.ts`)
   * for each classpath-bundled target in [targetConfigs] whose id is NOT in [excludeIds]. This is
   * the companion to [emit] for targets that live inside a JAR (e.g. app-bundled `square` /
   * `dashboardapp`) and therefore have no writable `<trailmapDir>/tools/` to receive a normal
   * per-trailmap surface. Returns the absolute paths of every file written.
   *
   * The surface produced here is intentionally NOT the IDE-autocomplete surface [emit] writes
   * (authors of a JAR-bundled trailmap edit its `.ts` in the *source* tree, not the JAR). It
   * exists so [TrailTscValidator] can type-check `.trail.yaml` recordings whose `target:` resolves
   * to a classpath-bundled trailmap — previously the single biggest coverage gap in that gate,
   * where the bulk of the trail corpus read as *skipped-no-surface*. The caller writes these into a
   * gitignored scratch dir (`<trails>/.trailblaze/…`) and points the validator at it.
   *
   * ## Why the input is baked [AppTargetYamlConfig]s, not [ResolvedTrailmap]s
   *
   * A classpath trailmap whose `target.tools:` names scripted tools that need analyzer
   * enrichment (e.g. `square`) is DROPPED from the runtime-resolved trailmap pool — the analyzer
   * can't walk `.ts` sources inside a JAR, so sibling-resolution throws and the loader drops the
   * target (runtime dispatch is instead served by the build-time-baked `targets/<id>.yaml`). That
   * drop is exactly what removed `square` — the whole point of this gate — from the
   * resolved pool. So we source from the **baked target configs** instead
   * ([AppTargetYamlLoader.discoverConfigs]): they carry the fully-resolved, hoisted scripted
   * `tools:` (with schemas) AND the `platforms.<p>.tool_sets:` for class-backed resolution, and
   * they exist for every bundled target regardless of whether analyzer enrichment can run.
   *
   * [excludeIds] are the workspace's own filesystem trailmap ids — those already get a real
   * (analyzer-upgraded) surface from [emit], so re-emitting a bundled copy here would shadow it.
   *
   * ## Fidelity vs [emit]
   *
   * **Class-backed tools are fully typed** — [resolveKotlinToolDescriptorsForTrailmap] resolves
   * them through [TrailblazeToolSetCatalog] via classpath reflection. These are the bulk of what
   * recorded trails actually call (`tapOnElementWithText`, …). **Scripted (`.ts`) tools carry the
   * baked target's schema** but are NOT re-run through the analyzer here (no on-disk `.ts` in a
   * JAR), so any residual gap between the baked schema and a precise typed shape reads as a
   * finding to curate rather than a silent gap — a surfaced finding beats a silent hole.
   *
   * **Strictly non-fatal per target** — a generation failure for one target is logged under the
   * `[PerTrailmapClientDtsEmitter]` prefix and skipped, so one broken bundled target can't abort
   * the surface for the rest.
   */
  fun emitClasspathValidationSurfaces(
    targetConfigs: List<AppTargetYamlConfig>,
    excludeIds: Set<String>,
    outputBaseDir: Path,
    catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  ): List<Path> {
    if (targetConfigs.isEmpty()) return emptyList()
    val generator = WorkspaceClientDtsGenerator()

    return targetConfigs.mapNotNull { config ->
      if (config.id in excludeIds) return@mapNotNull null
      try {
        val hostDir = outputBaseDir.resolve(config.id).toAbsolutePath().normalize()
        Files.createDirectories(hostDir)
        // Wrap the baked target config in a synthetic ResolvedTrailmap so the same private
        // resolution helpers [emit] uses apply unchanged: the manifest carries the target's
        // `platforms.<p>.tool_sets:` (→ class-backed tools via the catalog) and the `target`
        // carries the baked, hoisted scripted `tools:`. No `dependencies:` — the baked target
        // already hoisted every inherited tool, so there's no closure to walk.
        val synthetic = ResolvedTrailmap(
          manifest = TrailblazeTrailmapManifest(
            id = config.id,
            target = TrailmapTargetConfig(
              displayName = config.displayName,
              platforms = config.platforms,
            ),
          ),
          source = TrailmapSource.Classpath(resourceDir = "trails/config/trailmaps/${config.id}"),
          target = config,
          toolsets = emptyList(),
          tools = emptyList(),
          waypoints = emptyList(),
        )
        val kotlinTools = resolveKotlinToolDescriptorsForTrailmap(synthetic, catalog)
        val scriptedTools = collectTrailmapTypedScriptedTools(synthetic, mapOf(config.id to synthetic))
        val emittedPath = generator.generateForTrailmapFromResolved(
          trailmapDir = hostDir,
          toolDescriptors = kotlinTools.descriptors,
          scriptedTools = scriptedTools,
          typedToolOverrides = emptyMap(),
          frameworkMetadataByName = kotlinTools.frameworkMetadataByName,
          extraParamsByToolName = kotlinTools.selectorParamsByName,
        )
        // Sidecar for the validator, same as the workspace [emit] path — here the scripted-tool
        // arg types come from the baked target's own `inputSchema` (no analyzer override in a JAR).
        writeValidationDescriptorSidecar(hostDir, kotlinTools, scriptedTools, typedOverrides = emptyMap())
        emittedPath
      } catch (e: Exception) {
        Console.error(
          "[PerTrailmapClientDtsEmitter] failed to emit validation surface for classpath " +
            "target '${config.id}' (skipped): ${e.message ?: e::class.simpleName}",
        )
        null
      }
    }
  }

  /**
   * Resolve a [ScriptedToolDefinitionAnalyzer] from the ambient environment — `bun` on
   * PATH plus the SDK directory carrying `extract-tool-defs.mjs` and a usable
   * `ts-json-schema-generator` install. Returns `null` (and emits no log line — the daemon
   * starts on many machines without bun, and we don't want a recurring noisy warning) if
   * any of those pieces is missing. Callers that get `null` continue to emit per-trailmap
   * `trailblaze-client.d.ts` files with the YAML-derived flat shape; no typed-result upgrade, but no
   * regression from today.
   *
   * Test seam: tests construct their own analyzer (or pass `null` to exercise the
   * degradation path) and bypass this resolver by passing `analyzer = ...` to [emit].
   */
  private fun resolveAnalyzerOrNull(): ScriptedToolDefinitionAnalyzer? {
    val bun = BunBinaryResolver.resolveBunBinary() ?: return null
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: return null
    val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir) ?: return null
    // Preflight: the shim's deps must be resolvable — either a real SDK tree with
    // `ts-json-schema-generator` under `node_modules/`, OR the framework-bundled
    // self-contained shim (deps inlined). Same gate as
    // `AnalyzerScriptedToolEnrichment.resolveFromEnvironment`.
    if (!ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(sdkDir)) return null
    return ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      cacheDir = ScriptedToolDefinitionCache.resolveDefaultCacheDir(),
    )
  }

  /**
   * Pre-analyze every filesystem-backed trailmap in [resolvedTrailmaps] EXACTLY ONCE, keyed by
   * trailmap id, so subsequent dep-closure walks (one per consumer trailmap) reuse the cached
   * extraction instead of re-spawning a bun subprocess per visit. Returns an empty map
   * when [analyzer] is null; per-trailmap failure modes (analyzer exception, missing tools
   * dir) yield an empty list under that trailmap's key and the emit continues.
   *
   * Classpath-backed trailmaps contribute no entry — their `tools/` lives inside a JAR and
   * isn't analyzer-readable. A future expansion (analyzer reads from a classpath
   * resource bundle) can plug in here without changing the consumer signature.
   */
  private fun analyzeAllTrailmapsOnce(
    resolvedTrailmaps: List<ResolvedTrailmap>,
    analyzer: ScriptedToolDefinitionAnalyzer?,
  ): Map<String, List<ScriptedToolDefinition>> {
    if (analyzer == null) return emptyMap()
    return resolvedTrailmaps.associate { trailmap ->
      val trailmapDir = (trailmap.source as? TrailmapSource.Filesystem)?.trailmapDir
      val defs = if (trailmapDir == null) {
        emptyList()
      } else {
        analyzeTrailmapToolsDir(trailmapDir, analyzer)
      }
      trailmap.manifest.id to defs
    }
  }

  /**
   * Build the typed-tool override map for [trailmap]'s emitted `trailblaze-client.d.ts`. The map is the
   * union of:
   *
   *   - the trailmap's OWN analyzer output (every typed tool extracted from this trailmap's
   *     `tools/` directory), plus
   *   - the analyzer output of every trailmap reachable through `dependencies:`, filtered to
   *     names that the dep exposes via its `exports:` list (matching the surface-control
   *     rule used by `collectTrailmapTypedScriptedTools`).
   *
   * **First-write-wins** — the trailmap's own definition wins over an inherited dep definition
   * of the same name. Mirrors the override-precedence rule in `collectTrailmapTypedScriptedTools`
   * so the typed surface and the runtime registry stay aligned.
   *
   * Cross-dep collisions (two different deps in the closure both exporting the same
   * name) are NOT thrown here — `collectTrailmapTypedScriptedTools` already throws for that
   * case before this function ever gets called. Here we just emit the first one encountered
   * in walk order; the upstream throw would have already aborted emit() before we got here.
   */
  private fun collectTypedToolOverridesForClosure(
    trailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
    analyzerOutputByTrailmapId: Map<String, List<ScriptedToolDefinition>>,
  ): Map<String, WorkspaceClientDtsGenerator.TypedToolOverride> {
    if (analyzerOutputByTrailmapId.isEmpty()) return emptyMap()
    val byName = linkedMapOf<String, WorkspaceClientDtsGenerator.TypedToolOverride>()
    // Trailmap-local typed tools first so the override-by-trailmap rule (trailmap wins over dep)
    // is enforced by putIfAbsent below.
    analyzerOutputByTrailmapId[trailmap.manifest.id].orEmpty().forEach { def ->
      byName.putIfAbsent(def.name, def.toOverride())
    }
    // Walk the dep closure exactly the same way `collectTrailmapTypedScriptedTools` does:
    // BFS over `dependencies:`, filter each dep's tools by its `exports:` list.
    val visited = mutableSetOf(trailmap.manifest.id)
    val frontier = ArrayDeque<String>()
    trailmap.manifest.dependencies.forEach { frontier.add(it) }
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = trailmapsById[depId] ?: continue
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depDefs = analyzerOutputByTrailmapId[depId].orEmpty()
        depDefs.forEach { def ->
          if (def.name in depExports) {
            byName.putIfAbsent(def.name, def.toOverride())
          }
        }
      }
      dep.manifest.dependencies.forEach { frontier.add(it) }
    }
    return byName
  }

  /**
   * Run [analyzer] against [trailmapDir]/tools/ and return the extracted [ScriptedToolDefinition]
   * list. Returns an empty list when the trailmap has no `tools/` directory or the analyzer
   * extracts nothing.
   *
   * **Strictly non-fatal**: any analyzer failure (subprocess crash, malformed output,
   * per-tool error) is caught and logged; the emitter continues. Per-tool errors carrying
   * `partialTools` surface the healthy subset so a single broken `.ts` file in a trailmap
   * doesn't lose typed-surface upgrades for that trailmap's other tools.
   */
  private fun analyzeTrailmapToolsDir(
    trailmapDir: File,
    analyzer: ScriptedToolDefinitionAnalyzer,
  ): List<ScriptedToolDefinition> {
    val toolsDir = File(trailmapDir, "tools")
    if (!toolsDir.isDirectory) return emptyList()
    return try {
      runBlocking { analyzer.analyze(toolsDir) }
    } catch (e: ScriptedToolDefinitionException) {
      // Per-tool errors are common during WIP authoring — surface them under a grep-able
      // prefix so they're searchable in daemon logs, but don't fail codegen. The errors
      // carry per-tool detail; partialTools holds whatever DID extract cleanly so the
      // emitter still applies overrides for healthy tools alongside the broken one.
      Console.error(
        "[PerTrailmapClientDtsEmitter] analyzer failure for trailmap at ${trailmapDir.absolutePath}: " +
          "${e.message ?: e::class.simpleName}",
      )
      e.errors.forEach { err ->
        Console.error(
          "    - ${err.file}${err.toolName?.let { " (tool: $it)" }.orEmpty()}: ${err.message}",
        )
      }
      e.partialTools
    } catch (e: Throwable) {
      // Belt-and-suspenders for non-typed exceptions (subprocess launch IOException, etc.)
      // — same posture as `CheckCommand.emitScriptedToolDefinitionsDebug`'s top-level catch.
      Console.error(
        "[PerTrailmapClientDtsEmitter] unexpected analyzer failure for trailmap at " +
          "${trailmapDir.absolutePath} (ignored): ${e.message ?: e::class.simpleName}",
      )
      emptyList()
    }
  }

  private fun ScriptedToolDefinition.toOverride(): WorkspaceClientDtsGenerator.TypedToolOverride =
    WorkspaceClientDtsGenerator.TypedToolOverride(
      description = description,
      // Use the `.jsonObject` narrowers — the analyzer's own `init` block already enforces
      // these are JsonObject (see ScriptedToolDefinition.init), and `TypedToolOverride`'s
      // field types match that contract post-#1.
      inputSchema = inputSchemaObject,
      outputSchema = outputSchemaObject,
    )

  /**
   * Resolve the class-backed + YAML-defined tool surface for [trailmap], DERIVED FROM `isRecordable`
   * so the trail-recording validator can type-check every recordable tool. Three sources, merged
   * first-write-wins (a `tool_sets:` entry wins over the same tool in the recordable union — richer,
   * authoritative — and a name-colliding YAML tool loses to either):
   *
   *  1. **The trailmap's OWN `tool_sets:` tools** (via [TrailblazeToolSetCatalog.resolve]) — the
   *     authoring surface; every class-backed tool the trailmap declares, recordable or not, so a
   *     `.ts` author reaches the same set as before. Trailmap-local (never reads dependencies').
   *  2. **Every recordable framework tool** ([TrailblazeSerializationInitializer.buildAllTools]
   *     filtered to `isRecordable`), REGARDLESS of `tool_sets:`. This is the durable fix for the
   *     validator false-positive class: the selector-migration pipeline records tools the LLM never
   *     picks (`assertVisibleBySelector`, `tapOn`, …) that live in no `tool_sets:`, and hand-adding
   *     each to `built-in-tools.ts` inevitably drifts (it missed `assertNotVisibleBySelector`).
   *     Sourcing from the recordable registry — the same set replay decodes against — means the
   *     surface can't silently miss a recordable tool.
   *  3. **YAML-defined (`tools:` mode) recordable tools** ([TrailblazeSerializationInitializer.buildYamlDefinedTools],
   *     e.g. `eraseText` / `pressBack`) — no Kotlin class, so params come straight from the config.
   *
   * **Selector args re-injected.** [selectorParamsForTs] recovers the `TrailblazeNodeSelector` /
   * legacy `selector` params that [toScriptedToolDescriptor] strips (they overflow Koog's lowering),
   * typed against the generated selector grammar — otherwise every recorded selector call reads as an
   * "unexpected property." Returned as [KotlinToolResolution.selectorParamsByName] for the generator
   * to append. [HAND_CURATED_RECORDABLE] tools are skipped (they stay hand-typed in the SDK file).
   *
   * **Failure posture: skip-and-log per tool, never throw.** [toScriptedToolDescriptor] returns
   * null for a tool it can't lower; the entry is dropped from descriptors + metadata + selector
   * params in lockstep so a malformed tool never surfaces a half-populated entry.
   */
  private fun resolveKotlinToolDescriptorsForTrailmap(
    trailmap: ResolvedTrailmap,
    catalog: List<ToolSetCatalogEntry>,
  ): KotlinToolResolution {
    val descriptors = mutableListOf<ToolDescriptor>()
    val metadataByName = mutableMapOf<String, ToolFrameworkMetadata>()
    val selectorParamsByName = mutableMapOf<String, List<WorkspaceClientDtsGenerator.ToolParam>>()

    // First-write-wins across the three sources: once a name has a metadata entry it's skipped.
    fun addClassTool(kClass: KClass<out TrailblazeTool>) {
      val descriptor = kClass.toScriptedToolDescriptor() ?: return
      if (descriptor.name in HandCuratedRecordableTools.NAMES) return
      if (metadataByName.containsKey(descriptor.name)) return
      descriptors += descriptor
      // Reading the annotation here (not at descriptor-build time) keeps the projection at the
      // codegen boundary — `ToolFrameworkMetadata` is a bundler concern, the descriptor build is a
      // koog-runtime concern. Same `KClass` so no re-walking the catalog.
      val annotation = kClass.trailblazeToolClassAnnotation()
      metadataByName[descriptor.name] = ToolFrameworkMetadata(
        surfaceToLlm = annotation.surfaceToLlm,
        isRecordable = annotation.isRecordable,
        requiresHost = annotation.requiresHost,
        trailheadTo = annotation.trailheadTo,
        // Bare reference to the SAME named type `built-in-tools.ts` imports from the generated
        // `BuiltInToolResultTsBindings` output — see ToolFrameworkMetadata.resultTsType's kdoc.
        // `simpleName` (not a full SerialDescriptorTsCodegen walk) is enough: it's exactly what
        // that generator's `tsName()` would derive too, absent an `@SerialName` override on the
        // result class — neither of today's `resultType` classes has one. If that ever changes,
        // this would need to walk the descriptor's serialName instead of trusting `simpleName`.
        resultTsType = annotation.resultType.takeIf { it != Unit::class }?.simpleName,
      )
      val selectorParams = kClass.selectorParamsForTs()
      if (selectorParams.isNotEmpty()) {
        selectorParamsByName[descriptor.name] = selectorParams.map { it.toGeneratorParam() }
      }
    }

    // 1. The trailmap's own tool_sets tools (authoritative; also the authoring surface).
    val ownToolSetNames = collectOwnToolSetNames(trailmap)
    if (ownToolSetNames.isNotEmpty()) {
      TrailblazeToolSetCatalog.resolve(requestedIds = ownToolSetNames.toList(), catalog = catalog)
        .toolClasses.forEach(::addClassTool)
    }

    // 2. Every recordable framework tool, regardless of tool_sets — the isRecordable-derived surface.
    TrailblazeSerializationInitializer.buildAllTools().values
      .filter { it.trailblazeToolClassAnnotation().isRecordable }
      .forEach(::addClassTool)

    // 3. YAML-defined recordable tools (`tools:` mode, e.g. eraseText / pressBack).
    TrailblazeSerializationInitializer.buildYamlDefinedTools().forEach { (toolName, config) ->
      val name = toolName.toolName
      if (config.isRecordable == false) return@forEach
      if (name in HandCuratedRecordableTools.NAMES || metadataByName.containsKey(name)) return@forEach
      descriptors += config.toRecordableToolDescriptor()
      metadataByName[name] = ToolFrameworkMetadata(
        surfaceToLlm = config.surfaceToLlm ?: true,
        isRecordable = true,
        requiresHost = config.requiresHost ?: false,
      )
    }

    return KotlinToolResolution(
      descriptors = descriptors,
      frameworkMetadataByName = metadataByName,
      selectorParamsByName = selectorParamsByName,
    )
  }

  private fun SelectorParamTs.toGeneratorParam(): WorkspaceClientDtsGenerator.ToolParam =
    WorkspaceClientDtsGenerator.ToolParam(
      name = name,
      tsType = tsType,
      description = description,
      optional = optional,
    )

  /**
   * Build a koog [ToolDescriptor] for a YAML-defined (`tools:` mode) recordable tool from its
   * declared [TrailblazeToolParameterConfig]s. The validator only needs the arg shape (name / type /
   * required), so the composition body (`tools:`) is irrelevant here.
   */
  private fun ToolYamlConfig.toRecordableToolDescriptor(): ToolDescriptor {
    fun TrailblazeToolParameterConfig.toParamDescriptor() = ToolParameterDescriptor(
      name = name,
      description = description?.trim().orEmpty(),
      type = yamlParamTypeToToolParameterType(type),
    )
    return ToolDescriptor(
      name = id,
      description = description?.trim().orEmpty(),
      requiredParameters = parameters.filter { it.required }.map { it.toParamDescriptor() },
      optionalParameters = parameters.filterNot { it.required }.map { it.toParamDescriptor() },
    )
  }

  /**
   * Map a YAML parameter `type:` string to a koog [ToolParameterType] for descriptor building.
   * Covers the scalar types YAML-defined recordable tools use today; anything else degrades to
   * `String` (the validator still checks the arg's presence + name, just not its exact scalar type).
   */
  private fun yamlParamTypeToToolParameterType(type: String): ToolParameterType =
    when (type.trim().lowercase()) {
      "string" -> ToolParameterType.String
      "integer", "int", "long" -> ToolParameterType.Integer
      "number", "float", "double" -> ToolParameterType.Float
      "boolean", "bool" -> ToolParameterType.Boolean
      else -> ToolParameterType.String
    }

  /**
   * Bundles the outputs of the tool-resolution pass — the koog [ToolDescriptor] list (args/result
   * shape), the per-tool [ToolFrameworkMetadata] map (JSDoc tags), and the re-injected selector
   * params ([selectorParamsForTs] output) keyed by tool name (appended by the generator). All come
   * from one walk of the resolved + recordable tool set, so producing them together avoids re-work.
   */
  private data class KotlinToolResolution(
    val descriptors: List<ToolDescriptor>,
    val frameworkMetadataByName: Map<String, ToolFrameworkMetadata>,
    val selectorParamsByName: Map<String, List<WorkspaceClientDtsGenerator.ToolParam>> = emptyMap(),
  )

  /**
   * Union of every trailmap-local `tool_sets:` list across every platform the trailmap declares.
   * Reads the AUTHORED shape ([trailmap.manifest]), not the post-resolution
   * `AppTargetYamlConfig.platforms` (which would carry inherited defaults flattened in).
   * Library trailmaps declare under top-level `platforms:`; target trailmaps under `target.platforms:`.
   */
  private fun collectOwnToolSetNames(trailmap: ResolvedTrailmap): Set<String> {
    val targetPlatforms = trailmap.manifest.target?.platforms ?: emptyMap()
    val libraryPlatforms = trailmap.manifest.platforms ?: emptyMap()
    val ids = mutableSetOf<String>()
    targetPlatforms.values.forEach { platform ->
      platform.toolSets?.forEach { ids += it }
    }
    libraryPlatforms.values.forEach { platform ->
      platform.toolSets?.forEach { ids += it }
    }
    return ids
  }

  /**
   * Collect scripted tools that should appear in [trailmap]'s typed surface: the trailmap's own
   * `target.tools:` list, plus scripted tools transitively inherited from deps' `exports:`.
   *
   * The dep walk visits every trailmap reachable via `dependencies:` (excluding [trailmap]
   * itself). For each visited dep:
   *
   *   - If `exports:` is declared on the dep manifest, filter the dep's scripted-tool list
   *     to entries whose `name:` appears in `exports:` — those are the dep's public tools.
   *   - If `exports:` is omitted/null on the dep manifest, the dep contributes nothing to
   *     consumers' typed surface (Phase B contract: missing `exports:` means no public tools;
   *     trailmap scripted tools are internal helpers until explicitly listed).
   *
   * Deduplication by tool name keeps a single typed entry per name even if both the trailmap
   * and a dep declare it; the trailmap's own declaration wins (first-write semantics).
   *
   * **Phase B limitation — `exports:` flows scripted tools only.** A dep's `exports:` only
   * surfaces tools authored under its `target.tools:` (scripted `.ts` + `.yaml` pairs).
   * Library trailmaps (no `target:` block) get [ResolvedTrailmap.target] = null and therefore
   * cannot export scripted tools through this path today — they have nowhere to put them.
   * Composed `.tool.yaml` exports from a library trailmap's own `tools/` directory are
   * separately tracked and surface to consumers via the runtime registry, but they do NOT
   * land in the typed surface yet (they'd require a `ToolYamlConfig` → `ToolEntry` bridge
   * that handles composed-tool input schemas, deferred to Phase C/E along with the
   * `target.tools:` paths → names flip).
   *
   * Until then, a library trailmap's `exports:` list is effectively *advisory* for the typed
   * surface — it filters the runtime registry's view of "what consumers can see," but no
   * typed entries flow through to consumers' `trailblaze-client.d.ts`. Authoring teams should keep
   * library exports class-backed (via `tool_sets:` consumed by target trailmaps) until the
   * later phases land.
   */
  private fun collectTrailmapTypedScriptedTools(
    trailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): List<InlineScriptToolConfig> {
    val byName = linkedMapOf<String, InlineScriptToolConfig>()
    // Track which trailmap contributed each name so we can detect cross-dep collisions
    // (two deps publishing the same tool name with potentially different schemas).
    val sourceByName = mutableMapOf<String, String>()

    // Trailmap-local scripted tools first — trailmap's own declarations win on name collision
    // with any inherited tool. This is the deliberate consumer-override pattern (a trailmap
    // can replace an inherited tool of the same name with its own definition). It is NOT
    // a cross-dep collision, so no diagnostic is emitted here.
    trailmap.target?.tools.orEmpty().forEach { tool ->
      byName.putIfAbsent(tool.name, tool)
      sourceByName.putIfAbsent(tool.name, trailmap.manifest.id)
    }

    // Walk the dep closure (excluding self) and gather exported scripted tools.
    val visited = mutableSetOf(trailmap.manifest.id)
    val frontier = ArrayDeque<String>()
    trailmap.manifest.dependencies.forEach { frontier.add(it) }
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = trailmapsById[depId] ?: run {
        // Loader's strict dep-graph validation should have rejected the closure before
        // we got here. Surface a diagnostic if it ever fires so a missing typed-surface
        // entry is grep-able instead of an invisible gap.
        Console.error(
          "PerTrailmapClientDtsEmitter: trailmap '${trailmap.manifest.id}' references missing dep " +
            "'$depId' during typed-surface walk — loader should have caught this earlier.",
        )
        continue
      }
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depToolsByName = dep.target?.tools.orEmpty().associateBy { it.name }
        // Validate every name listed in `exports:` actually corresponds to a scripted
        // tool the dep ships. A typo in `exports: [createMrchant]` would otherwise
        // silently drop the tool from consumers' typed surface — a maddening
        // debug-by-staring exercise. Hard-error so the dep author sees the typo the
        // first time the consumer trailmap compiles. This is the same fail-loud posture
        // as `TrailblazeTrailmapBundler`'s missing-tool-file check.
        val unresolvedExports = depExports - depToolsByName.keys
        if (unresolvedExports.isNotEmpty()) {
          throw IllegalStateException(
            "Trailmap '${dep.manifest.id}' declares `exports: ${unresolvedExports.sorted()}` " +
              "but no scripted tool with that name is authored under its `target.tools:`. " +
              "Either remove the unresolved name(s) from `exports:` or add the matching " +
              "tool YAML to the trailmap. (Detected during typed-surface codegen for consumer " +
              "trailmap '${trailmap.manifest.id}'.)",
          )
        }
        depExports.forEach { exportName ->
          val tool = depToolsByName.getValue(exportName)
          val existingSource = sourceByName[tool.name]
          if (existingSource == null) {
            byName[tool.name] = tool
            sourceByName[tool.name] = dep.manifest.id
          } else if (existingSource != trailmap.manifest.id && existingSource != dep.manifest.id) {
            // Two DIFFERENT deps both export the same name → ambiguous typed surface.
            // Trailmap-local-overrides-dep is intentional and skipped here (existingSource
            // == trailmap.manifest.id). Same-dep-revisit via diamond paths is suppressed by
            // the `visited` set. Anything that lands here is a genuine cross-dep clash
            // — fail loudly, matching `TrailblazeTrailmapBundler.collectScriptedToolEntries
            // ForClosure`'s cross-trailmap collision rule so authors don't ship a
            // declaration-merging mess that compiles cleanly but resolves to whichever
            // schema TypeScript happens to pick.
            throw IllegalStateException(
              "Scripted tool name '${tool.name}' is exported by both trailmap " +
                "'$existingSource' and trailmap '${dep.manifest.id}', both of which are in " +
                "the dependency closure of trailmap '${trailmap.manifest.id}'. Tool names must " +
                "be unique across a consumer's exported-dependency closure so the " +
                "generated typed surface has a single shape per name. Rename one of the " +
                "tools, or remove the colliding name from one of the deps' `exports:`.",
            )
          }
          // Else: existingSource == trailmap.manifest.id (trailmap-local override) — silent skip,
          // trailmap-local wins on its own scripted-tool declarations.
        }
      }
      dep.manifest.dependencies.forEach { frontier.add(it) }
    }

    return byName.values.toList()
  }
}

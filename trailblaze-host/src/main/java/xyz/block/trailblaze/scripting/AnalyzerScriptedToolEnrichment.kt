package xyz.block.trailblaze.scripting

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.TrailheadMetadata
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.ScriptedToolProperty
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.config.project.buildInputSchemaObject
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * JVM host-side implementation of [ScriptedToolEnrichment] that runs
 * [ScriptedToolDefinitionAnalyzer] against each trailmap's `tools/` directory and produces
 * runtime-shaped [InlineScriptToolConfig]s for every descriptor that needs analyzer-derived
 * fields. Three authoring shapes route through here — see
 * [TrailmapScriptedToolFile.requiresEnrichment] for the full list — and each picks a
 * different rule for which analyzer export(s) to consume:
 *
 *  1. **Meta-only** (`script:` + optional `_meta:` only) — the `.ts` must export exactly
 *     one typed tool; the analyzer-derived name becomes the registered tool name. Multi-
 *     export `.ts` files are rejected because the YAML can't disambiguate.
 *  2. **Partial single-tool** (YAML carries `name:` but no `description:` / `inputSchema:`)
 *     — the analyzer extracts ALL exports; the one whose name matches the YAML's `name:`
 *     wins. The YAML's name is the disambiguator, so multi-export `.ts` files are fine.
 *  3. **Partial multi-tool** (YAML carries a `tools:` list with entries missing
 *     `description:` / `inputSchema:`) — each entry is matched by name against the
 *     analyzer's exports and emitted as its own [InlineScriptToolConfig].
 *
 * In every case, YAML-explicit fields win on conflict; the analyzer fills the gaps.
 *
 * **One subprocess per trailmap, not per descriptor.** The analyzer's batch mode amortizes
 * the `ts-json-schema-generator` warmup across every tool in the trailmap — calling it
 * once per descriptor would re-spawn bun for each. The same trailmap-wide walk powers
 * all three authoring shapes above.
 *
 * **Failure handling — the host's strict-all-or-nothing posture.** Any per-tool error
 * surfaced by the analyzer's [ScriptedToolDefinitionException.errors] flows through to
 * the caller as a per-descriptor [ScriptedToolEnrichment.EnrichmentResult.Failed]. The
 * loader's downstream `enrichDeferredDescriptors` translates each into a load-time
 * `TrailblazeProjectConfigException` citing the descriptor file path + analyzer reason
 * — so a single broken `.ts` fails the trailmap's load with an actionable diagnostic
 * rather than silently dropping the tool from the registry.
 *
 * **`_meta:` validation parity with the model.** Meta-only descriptors bypass
 * `TrailmapScriptedToolFile.toInlineScriptToolConfig()` and therefore its
 * `validateKnownMetaShapes` check, so this enrichment path re-validates the namespaced
 * `trailblaze/...` keys directly (Copilot review on #3338) — a typo like
 * `_meta: { trailblaze/supportedPlatforms: "android" }` fails at enrichment time with a
 * descriptor-aware message instead of slipping through to a downstream runtime failure.
 *
 * **Why we don't share the per-trailmap cache with [xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter]
 * yet.** The emitter already caches analyzer output per trailmap id for the dep-closure walk
 * (`PerTrailmapClientDtsEmitter.analyzeAllTrailmapsOnce`). Wiring that cache through to this
 * enrichment would let one analyzer subprocess cover both codegen (emitter) and runtime
 * registry (enrichment). Deferred: the emitter's cache lives behind its `emit()` entry
 * point and isn't reachable from the loader's call site without restructuring the
 * `WorkspaceCompileBootstrap` plumbing. Today each path pays its own subprocess
 * spawn (one per `enrich()` call). When both runtime (loader) and codegen (emitter)
 * paths run in the same process, two subprocesses spawn per trailmap — acceptable until the
 * analyzer becomes load-bearing on session start in a way the latency budget can't absorb.
 *
 * @see ScriptedToolDefinitionAnalyzer for the underlying extraction subprocess.
 * @see xyz.block.trailblaze.config.project.ScriptedToolEnrichment for the contract.
 */
class AnalyzerScriptedToolEnrichment(
  private val analyzer: ScriptedToolDefinitionAnalyzer,
) : ScriptedToolEnrichment {

  companion object {
    /**
     * Resolve an [AnalyzerScriptedToolEnrichment] from the ambient environment — `bun`
     * on PATH plus a usable SDK directory with `extract-tool-defs.mjs` +
     * `ts-json-schema-generator`. Returns `null` if any piece is missing (silent on each
     * individual miss, mirroring [xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter]'s
     * `resolveAnalyzerOrNull` convention) so a daemon starting on a host without bun
     * doesn't spam recurring warnings. When `null`, meta-only descriptors fail at load
     * time with the loader's "enrichment not wired" diagnostic; legacy full-YAML
     * descriptors load unaffected.
     *
     * **Why this lives on the impl class rather than on each call site.** The PR-#3338
     * lead-review surfaced that the resolver was duplicated across three callers
     * (`WorkspaceCompileBootstrap`, `AppTargetDiscovery`, parallels in
     * `PerTrailmapClientDtsEmitter.resolveAnalyzerOrNull`). Centralizing the resolution to
     * `AnalyzerScriptedToolEnrichment.Companion.resolveFromEnvironment` lets every host
     * loader call site (compile CLI, target discovery, workspace compile bootstrap,
     * future entry points) share one definition — adding a new preflight check (e.g. a
     * future `bun.lock` integrity probe) is a one-place change. The emitter's
     * `resolveAnalyzerOrNull` returns the bare `ScriptedToolDefinitionAnalyzer` instead
     * of the enrichment wrapper, so it stays distinct; both helpers compose the same
     * underlying [ScriptedToolDefinitionAnalyzer] resolver primitives.
     */
    fun resolveFromEnvironment(): AnalyzerScriptedToolEnrichment? {
      val bun = BunBinaryResolver.resolveBunBinary() ?: return null
      val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: return null
      val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir) ?: return null
      // Deps must be resolvable: a real SDK tree with node_modules installed, OR the
      // framework-bundled self-contained shim (deps inlined). Rejects a shim with neither.
      if (!ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(sdkDir)) return null
      val analyzer = ScriptedToolDefinitionAnalyzer(
        bunBinary = bun,
        extractorShim = shim,
        sdkDir = sdkDir,
        cacheDir = ScriptedToolDefinitionCache.resolveDefaultCacheDir(),
      )
      return AnalyzerScriptedToolEnrichment(analyzer)
    }
  }

  override fun enrich(
    trailmapId: String,
    trailmapDir: File,
    trailmapToolsDir: File,
    deferredDescriptors: List<ScriptedToolEnrichment.DeferredDescriptor>,
  ): List<ScriptedToolEnrichment.EnrichmentResult> {
    if (deferredDescriptors.isEmpty()) return emptyList()

    // Walk the trailmap's tools dir once and group by absolute source path. groupBy (rather
    // than associateBy) is load-bearing — a `.ts` file that exports multiple typed tools
    // must surface a clear failure rather than collapse to one arbitrary survivor.
    val analyzerOutput: Map<String, List<ScriptedToolDefinition>> = try {
      runBlocking { analyzer.analyze(trailmapToolsDir) }.groupBy { normalizePath(it.sourcePath) }
    } catch (e: ScriptedToolDefinitionException) {
      // Mixed-outcome envelope: some tools extracted cleanly, others failed. Index the
      // healthy ones by source path so we can still resolve them, and remember the
      // failure messages so we can attribute them to the right descriptor below.
      val totalTools = e.partialTools.size + e.errors.size
      Console.error(
        "[AnalyzerScriptedToolEnrichment] trailmap '$trailmapId': analyzer extraction completed " +
          "with ${e.errors.size} error(s) among $totalTools tool(s).",
      )
      val byPath = e.partialTools.groupBy { normalizePath(it.sourcePath) }
      return deferredDescriptors.map { deferred ->
        resolveOrFail(
          trailmapDir = trailmapDir,
          deferred = deferred,
          analyzerOutputByPath = byPath,
          // The errors map keys on file path; the documented contract is one error per
          // (file, tool) so multiple errors against the same file overwrite here. That's
          // acceptable for diagnostics — the first surviving error message is enough to
          // point the author at the right file; per-tool detail still rides in
          // `ScriptedToolDefinitionException.errors` upstream of this map.
          extraReasonByPath = e.errors.associate { normalizePath(it.file) to it.message },
        )
      }
    } catch (e: Throwable) {
      // Subprocess-level failure (missing bun, missing shim, timeout, bad JSON).
      // Every deferred descriptor is unresolvable — surface the same root cause on each.
      // Also log the raw exception under the grep-able `[AnalyzerScriptedToolEnrichment]`
      // prefix so an operator triaging the downstream "enrichment not wired" loader error
      // can find the actual subprocess failure (timeout, missing bun, ts-json-schema-generator
      // not installed) in the same daemon log — the Failed.reason text gets propagated through
      // the loader but the underlying exception message + class name is the actionable
      // breadcrumb. Lead-review v2 #5.
      Console.error(
        "[AnalyzerScriptedToolEnrichment] trailmap '$trailmapId': analyzer subprocess failed " +
          "(${e::class.simpleName}): ${e.message ?: "(no message)"}",
      )
      val reason = "analyzer subprocess failed: ${e.message ?: e::class.simpleName}"
      return deferredDescriptors.map { deferred ->
        ScriptedToolEnrichment.EnrichmentResult.Failed(
          relativePath = deferred.relativePath,
          reason = reason,
        )
      }
    }

    return deferredDescriptors.map { deferred ->
      resolveOrFail(
        trailmapDir = trailmapDir,
        deferred = deferred,
        analyzerOutputByPath = analyzerOutput,
        extraReasonByPath = emptyMap(),
      )
    }
  }

  private fun resolveOrFail(
    trailmapDir: File,
    deferred: ScriptedToolEnrichment.DeferredDescriptor,
    analyzerOutputByPath: Map<String, List<ScriptedToolDefinition>>,
    extraReasonByPath: Map<String, String>,
  ): ScriptedToolEnrichment.EnrichmentResult {
    val descriptor = deferred.descriptor
    // Catch a blank `script:` field BEFORE attempting File resolution — an empty string
    // resolves to the current working directory, which `isFile` rejects as a directory
    // and produces a misleading "does not exist" reason. Explicit guard surfaces the real
    // authoring error. Lead-review v3 finding.
    if (descriptor.script.isBlank()) {
      return ScriptedToolEnrichment.EnrichmentResult.Failed(
        relativePath = deferred.relativePath,
        reason = "descriptor's `script:` field is blank. Descriptors requiring analyzer " +
          "enrichment (meta-only, partial single-tool, or partial multi-tool authoring " +
          "shape) must carry a non-empty `script:` pointing at the sibling `.ts` file " +
          "that declares the typed tool.",
      )
    }
    val scriptFile = resolveScriptFile(trailmapDir, deferred.relativePath, descriptor.script)
    // Distinguish "the file isn't there" from "the file is there but the analyzer found
    // nothing" — the analyzer's silent-on-missing-source contract would otherwise produce
    // the misleading "no typed declaration found" message even when the script field
    // points at a typo.
    if (!scriptFile.isFile) {
      return ScriptedToolEnrichment.EnrichmentResult.Failed(
        relativePath = deferred.relativePath,
        reason = "script file '${descriptor.script}' (resolved to ${scriptFile.absolutePath}) does not exist.",
      )
    }
    // Meta-only descriptors must point at a `.ts` file — the analyzer only recognizes
    // `trailblaze.tool<I, O>({...})` call sites in TypeScript source. A `.js` (or other
    // extension) file will silently produce zero typed declarations and surface as
    // "no typed declaration found", which hides the real root cause. Lead-review v3.
    //
    // **Conservative on TS variants.** This check also rejects `.mts`, `.cts`, and
    // `.tsx`. The underlying `extract-tool-defs.mjs` shim *could* parse those, but
    // production trailmaps uniformly use `.ts` today and broadening the surface invites
    // edge cases (`.tsx` would surface React JSX failures from `ts-json-schema-generator`;
    // `.mts`/`.cts` are rare in scripted-tool authoring). Author-side fix is one-character
    // rename. If a future trailmap legitimately needs `.mts`, widen the check and add a
    // test fixture pinning the new extension.
    // Match `TrailmapScriptedToolFile.requiresEnrichment`'s case-insensitive `.ts` check so
    // the two gates agree — otherwise a `foo.TS` script would pass `requiresEnrichment`
    // (so it'd be routed to enrichment) but then fail here with a different message.
    if (!scriptFile.name.endsWith(".ts", ignoreCase = true)) {
      return ScriptedToolEnrichment.EnrichmentResult.Failed(
        relativePath = deferred.relativePath,
        reason = "script file '${descriptor.script}' must be a `.ts` (TypeScript) source so the " +
          "analyzer can extract its `trailblaze.tool<I, O>({...})` declaration. " +
          "Got '${scriptFile.name}'. Author the tool in TypeScript or fall back to a " +
          "full-YAML descriptor that names the export directly.",
      )
    }
    // Validate the descriptor's `_meta:` shape symmetric with the legacy
    // `TrailmapScriptedToolFile.toInlineScriptToolConfig()` path. Meta-only descriptors
    // skip that constructor entirely, so without this check a typo'd
    // `trailblaze/supportedPlatforms: "android"` (string instead of list) would slip
    // through enrichment and surface as a less actionable error downstream.
    validateKnownMetaShapes(descriptor.meta)?.let { reason ->
      return ScriptedToolEnrichment.EnrichmentResult.Failed(
        relativePath = deferred.relativePath,
        reason = reason,
      )
    }
    val normalizedScriptPath = normalizePath(scriptFile.absolutePath)
    val defs = analyzerOutputByPath[normalizedScriptPath].orEmpty()
    if (defs.isEmpty()) {
      // Either the analyzer didn't recognize a typed declaration in this `.ts`, or it
      // failed extraction for this specific file. Surface whichever reason we have.
      val reason = extraReasonByPath[normalizedScriptPath]
        ?: "no `trailblaze.tool<I, O>({...})` declaration found in ${scriptFile.absolutePath}. " +
        "Meta-only / partial YAML descriptors require the sibling `.ts` to export at least " +
        "one typed tool the analyzer can extract."
      return ScriptedToolEnrichment.EnrichmentResult.Failed(
        relativePath = deferred.relativePath,
        reason = reason,
      )
    }

    // Three authoring shapes route through this enrichment path; each picks which
    // analyzer exports to consume differently:
    //
    //  1. Meta-only (descriptor.name == null && descriptor.tools == null) → the `.ts`
    //     must export exactly one typed tool, and that export's name becomes the
    //     registered tool name.
    //  2. Partial single-tool (descriptor.name != null && descriptor.tools == null) →
    //     find the analyzer export whose name matches descriptor.name. Multi-export
    //     `.ts` files are fine here; the YAML's `name:` is the disambiguator.
    //  3. Partial multi-tool (descriptor.tools != null) → for each entry in
    //     descriptor.tools, find the analyzer export whose name matches the entry's
    //     name. Each match produces its own [InlineScriptToolConfig].
    val multiToolEntries = descriptor.tools
    val configs: List<InlineScriptToolConfig> = when {
      multiToolEntries != null -> {
        // Partial multi-tool. Match each entry by name against analyzer exports.
        val defsByName = defs.associateBy { it.name }
        val missingNames = multiToolEntries.map { it.name }.filter { it !in defsByName }
        if (missingNames.isNotEmpty()) {
          val available = defs.map { it.name }.sorted().joinToString(", ")
          return ScriptedToolEnrichment.EnrichmentResult.Failed(
            relativePath = deferred.relativePath,
            reason = "descriptor's `tools:` list names ${missingNames.joinToString(", ")} but " +
              "the sibling `.ts` (${scriptFile.absolutePath}) exports no `trailblaze.tool` " +
              "binding(s) under those names (available: [$available]). " +
              "Either rename the YAML entries to match the `.ts` exports, or add the missing " +
              "exports to the `.ts` file.",
          )
        }
        multiToolEntries.map { entry ->
          buildPartialConfig(
            descriptor = descriptor,
            scriptFile = scriptFile,
            entryName = entry.name,
            entryDescription = entry.description,
            entryInputSchema = entry.inputSchema,
            entryMeta = entry.meta,
            entryRequiresHost = entry.requiresHost,
            entrySupportedPlatforms = entry.supportedPlatforms,
            entrySurfaceToLlm = entry.surfaceToLlm,
            entryIsRecordable = entry.isRecordable,
            def = defsByName.getValue(entry.name),
          )
        }
      }
      descriptor.name != null -> {
        // Partial single-tool. The YAML's `name:` selects which analyzer export to consume.
        // Local-val capture because `descriptor.name` is a public property on a model in a
        // different module — Kotlin's smart-cast can't track its nullability through
        // subsequent string-concat call sites without a stable local reference.
        val descriptorName: String = descriptor.name!!
        val defsByName = defs.associateBy { it.name }
        val def = defsByName[descriptorName]
          ?: run {
            val available = defs.map { it.name }.sorted().joinToString(", ")
            return ScriptedToolEnrichment.EnrichmentResult.Failed(
              relativePath = deferred.relativePath,
              reason = "descriptor's `name:` is '$descriptorName' but the sibling `.ts` " +
                "(${scriptFile.absolutePath}) exports no `trailblaze.tool` binding under that " +
                "name (available: [$available]). Either rename the YAML's `name:` to match an " +
                "exported binding, or rename the `.ts`'s `export const X = trailblaze.tool(...)` " +
                "to match the YAML.",
            )
          }
        listOf(
          buildPartialConfig(
            descriptor = descriptor,
            scriptFile = scriptFile,
            entryName = descriptorName,
            entryDescription = descriptor.description,
            entryInputSchema = descriptor.inputSchema,
            entryMeta = null,
            entryRequiresHost = null,
            entrySupportedPlatforms = null,
            entrySurfaceToLlm = null,
            entryIsRecordable = null,
            def = def,
          ),
        )
      }
      else -> {
        // Meta-only (the original `_meta:`-only authoring shape). Exactly-one-export
        // contract: the YAML can't disambiguate so multi-export `.ts` files are
        // rejected to avoid the silent last-write-wins failure mode.
        if (defs.size > 1) {
          val names = defs.map { it.name }.sorted().joinToString(", ")
          return ScriptedToolEnrichment.EnrichmentResult.Failed(
            relativePath = deferred.relativePath,
            reason = "script ${scriptFile.absolutePath} declares more than one " +
              "`trailblaze.tool<I, O>({...})` export ([$names]); meta-only descriptors can't " +
              "disambiguate. Split the `.ts` into one typed export per file, or add a top-level " +
              "`name:` (partial single-tool shape) or `tools:` list (partial multi-tool shape) " +
              "to select which export(s) to register.",
          )
        }
        val def = defs.single()
        // A meta-only descriptor (no YAML name/description/inputSchema/_meta) delegates EVERYTHING
        // to the `.ts`. If the author used the (spec, handler) overload with a non-inline spec
        // reference, the analyzer dropped the whole spec — there's no YAML to supply
        // supportedPlatforms / surfaceToLlm, so the tool would silently ship un-gated. Fail loud
        // (the general case is only a warning; here the spec is the sole metadata source). This is
        // the safety net for agents vibe-authoring descriptor-less `.ts` tools.
        if (def.uncapturedSpec) {
          return ScriptedToolEnrichment.EnrichmentResult.Failed(
            relativePath = deferred.relativePath,
            reason = "${scriptFile.name}: trailblaze.tool(spec, handler) was called with a " +
              "non-inline spec reference (e.g. `const SPEC = {...}` or a factory call), so its " +
              "supportedPlatforms / surfaceToLlm / requiresHost were not captured. A descriptor-less " +
              "tool has no YAML to supply them, so this would advertise an un-gated tool. Inline the " +
              "spec object literal at the call site: " +
              "`trailblaze.tool<I>({ supportedPlatforms: [\"ios\"] }, async (args, ctx) => { ... })`.",
          )
        }
        val analyzerSurfaceToLlm = (def.spec?.get("surfaceToLlm") as? JsonPrimitive)?.booleanOrNull ?: true
        val analyzerIsRecordable = (def.spec?.get("isRecordable") as? JsonPrimitive)?.booleanOrNull ?: true
        val effectiveSurfaceToLlm = descriptor.surfaceToLlm && analyzerSurfaceToLlm
        val effectiveIsRecordable = descriptor.isRecordable && analyzerIsRecordable
        // Combined (descriptor AND analyzer) value into mergeMeta so the on-device `_meta` matches
        // the typed slot — see the buildPartialConfig rationale above.
        val merged = mergeMeta(
          descriptorMeta = descriptor.meta,
          requiresHost = descriptor.requiresHost,
          supportedPlatforms = descriptor.supportedPlatforms,
          analyzerSpec = def.spec,
          surfaceToLlm = effectiveSurfaceToLlm,
          isRecordable = effectiveIsRecordable,
        )
        listOf(
          InlineScriptToolConfig(
            script = scriptFile.absolutePath,
            name = def.name,
            // Description precedence: spec `description` (NEW middle tier) over the analyzer's
            // TSDoc-derived `def.description`. A meta-only descriptor carries no YAML
            // `description:` (that's the partial-descriptor shape), so the YAML override tier is
            // absent here — see [specDescriptionOf] and the precedence note on `buildPartialConfig`.
            description = specDescriptionOf(def.spec) ?: def.description,
            requiresHost = descriptor.requiresHost ||
              (def.spec?.get("requiresHost") as? JsonPrimitive)?.booleanOrNull == true,
            surfaceToLlm = effectiveSurfaceToLlm,
            isRecordable = effectiveIsRecordable,
            runtime = descriptor.runtime,
            meta = merged,
            // Inline any `$ref` (named enum / Record / nested type) the analyzer emitted so the
            // downstream subprocess synthesizer + in-process descriptor see a self-contained
            // schema — see [ScriptedToolSchemaRefFlattener].
            inputSchema = ScriptedToolSchemaRefFlattener.flatten(def.inputSchemaObject),
            trailhead = trailheadOf(def.name, def.spec),
          ),
        )
      }
    }
    return ScriptedToolEnrichment.EnrichmentResult.Resolved(
      relativePath = deferred.relativePath,
      configs = configs,
    )
  }

  /**
   * Build one [InlineScriptToolConfig] for a partial descriptor entry. The YAML's
   * explicit fields win on conflict; the analyzer fills the gaps:
   *
   *  - `name` — always the YAML's [entryName] (load-bearing for `target.tools:`
   *    resolution + per-trailmap dup detection).
   *  - `description` — three-tier precedence (most-authoritative first): YAML's
   *    [entryDescription] when non-null, else the typed spec's `description`
   *    ([specDescriptionOf]), else the analyzer's TSDoc-extracted description.
   *  - `inputSchema` — YAML's [entryInputSchema] when non-empty, else the analyzer's
   *    `<I>`-generic-extracted JSON Schema.
   *  - `_meta` — merged via [mergeMeta] from descriptor-side keys (file-wide + per-entry
   *    overrides) and the analyzer's typed spec.
   *  - `requiresHost` — true if any of (file-wide `requiresHost:`, per-entry
   *    `requiresHost:`, analyzer spec's `requiresHost`) opt in. Additive — matches
   *    [mergeMeta]'s union semantics.
   *
   * Per-entry [entryMeta] / [entryRequiresHost] / [entrySupportedPlatforms] are
   * non-null only for partial multi-tool entries; partial single-tool descriptors
   * carry their meta at the file level.
   */
  private fun buildPartialConfig(
    descriptor: TrailmapScriptedToolFile,
    scriptFile: File,
    entryName: String,
    entryDescription: String?,
    entryInputSchema: Map<String, ScriptedToolProperty>,
    entryMeta: JsonObject?,
    entryRequiresHost: Boolean?,
    entrySupportedPlatforms: List<String>?,
    entrySurfaceToLlm: Boolean?,
    entryIsRecordable: Boolean?,
    def: ScriptedToolDefinition,
  ): InlineScriptToolConfig {
    val description = entryDescription ?: specDescriptionOf(def.spec) ?: def.description
    // YAML inputSchema is the author's flat `Map<String, ScriptedToolProperty>` shape.
    // When present, translate it into the JSON-Schema object the runtime expects (same
    // translation the legacy `TrailmapScriptedToolFile.toInlineScriptToolConfig()` uses,
    // exposed as an internal helper since both call sites need it).
    // When empty, fall through to the analyzer's pre-built JSON Schema.
    val inputSchema = if (entryInputSchema.isNotEmpty()) {
      buildInputSchemaObject(entryInputSchema)
    } else {
      // Inline any `$ref` (named enum / Record / nested type) the analyzer emitted so the
      // downstream subprocess synthesizer + in-process descriptor see a self-contained schema —
      // see [ScriptedToolSchemaRefFlattener]. The YAML-authored branch above never has refs.
      ScriptedToolSchemaRefFlattener.flatten(def.inputSchemaObject)
    }
    // Merge meta with per-entry overrides applied last (entry wins over file-wide).
    val fileLevelSupportedPlatforms = descriptor.supportedPlatforms
    val effectiveSupportedPlatforms = entrySupportedPlatforms?.takeIf { it.isNotEmpty() }
      ?: fileLevelSupportedPlatforms
    // Targeted footgun warning (noise-free): a partial descriptor whose `.ts` passed a non-inline
    // spec reference (so the spec was dropped) AND that supplies no supportedPlatforms gate from the
    // YAML either → the tool advertises on ALL platforms. YAML-gated tools (the common existing
    // `const SPEC` + descriptor pattern) supply platforms here, so they stay silent.
    if (def.uncapturedSpec && effectiveSupportedPlatforms.isNullOrEmpty()) {
      Console.log(
        "[AnalyzerScriptedToolEnrichment] tool '$entryName' (${scriptFile.name}): " +
          "trailblaze.tool(spec, handler) uses a non-inline spec reference, so its spec was dropped, " +
          "and no supportedPlatforms gate is supplied by the spec OR the descriptor — the tool will " +
          "be advertised on ALL platforms. If unintended, inline the spec object literal at the " +
          "`trailblaze.tool(...)` call site, or add supportedPlatforms to the descriptor.",
      )
    }
    val effectiveRequiresHost = entryRequiresHost ?: descriptor.requiresHost
    val analyzerRequiresHost = (def.spec?.get("requiresHost") as? JsonPrimitive)?.booleanOrNull == true
    // surfaceToLlm / isRecordable default `true`; `false` is the opt-out. Combine descriptor-side
    // (per-entry wins over file-wide) with the analyzer-extracted `.ts` spec by AND — a `false`
    // from either side hides / un-records the tool. Mirrors `requiresHost`'s additive union, just
    // inverted because these flags default the other way.
    val descriptorSurfaceToLlm = entrySurfaceToLlm ?: descriptor.surfaceToLlm
    val descriptorIsRecordable = entryIsRecordable ?: descriptor.isRecordable
    val analyzerSurfaceToLlm = (def.spec?.get("surfaceToLlm") as? JsonPrimitive)?.booleanOrNull ?: true
    val analyzerIsRecordable = (def.spec?.get("isRecordable") as? JsonPrimitive)?.booleanOrNull ?: true
    val effectiveSurfaceToLlm = descriptorSurfaceToLlm && analyzerSurfaceToLlm
    val effectiveIsRecordable = descriptorIsRecordable && analyzerIsRecordable
    // Pass the FULLY-combined value (descriptor AND analyzer) into mergeMeta, not just the
    // descriptor-side value: the shortcut fold runs LAST, so feeding it the combined opt-out makes
    // the on-device `_meta` (read by QuickJsToolMeta) agree with the typed `InlineScriptToolConfig`
    // slot the host reads — otherwise an explicit descriptor `_meta:{trailblaze/surfaceToLlm:true}`
    // could win in `_meta` while the typed slot is `false`, diverging the two runtimes.
    val mergedMetaBase = mergeMeta(
      descriptorMeta = descriptor.meta,
      requiresHost = effectiveRequiresHost,
      supportedPlatforms = effectiveSupportedPlatforms,
      analyzerSpec = def.spec,
      surfaceToLlm = effectiveSurfaceToLlm,
      isRecordable = effectiveIsRecordable,
    )
    val mergedWithEntry = if (entryMeta == null || entryMeta.isEmpty()) mergedMetaBase else {
      // Per-entry `_meta:` keys win over file-wide ones.
      buildJsonObject {
        mergedMetaBase?.forEach { (k, v) -> put(k, v) }
        entryMeta.forEach { (k, v) -> put(k, v) }
      }
    }
    // The per-entry `_meta` overlay above runs AFTER mergeMeta's shortcut fold, so a per-entry raw
    // `_meta:{trailblaze/surfaceToLlm:true}` could otherwise re-introduce a `true` that contradicts
    // the combined-`false` typed slot — exactly the typed-vs-`_meta` divergence this enrichment is
    // meant to prevent. Re-assert the combined opt-out as the final authority so the on-device
    // `_meta` (read by QuickJsToolMeta) can never disagree with the host's typed slot.
    val merged = reassertOptOuts(mergedWithEntry, effectiveSurfaceToLlm, effectiveIsRecordable)
    return InlineScriptToolConfig(
      script = scriptFile.absolutePath,
      name = entryName,
      description = description,
      requiresHost = effectiveRequiresHost || analyzerRequiresHost,
      surfaceToLlm = effectiveSurfaceToLlm,
      isRecordable = effectiveIsRecordable,
      runtime = descriptor.runtime,
      meta = merged,
      inputSchema = inputSchema,
      trailhead = trailheadOf(entryName, def.spec),
    )
  }

  /**
   * Forces `_meta.trailblaze/surfaceToLlm` / `…/isRecordable` to `false` whenever the combined
   * effective value is `false`, so a later `_meta` overlay can't contradict the typed
   * [InlineScriptToolConfig] slot. A `true` effective value leaves `_meta` untouched (the `true`
   * default emits no key — re-asserting it would change the wire shape for the common case).
   * Returns the input unchanged when both flags are `true` and there's nothing to enforce.
   */
  private fun reassertOptOuts(
    meta: JsonObject?,
    surfaceToLlm: Boolean,
    isRecordable: Boolean,
  ): JsonObject? {
    if (surfaceToLlm && isRecordable) return meta
    return buildJsonObject {
      meta?.forEach { (k, v) -> put(k, v) }
      if (!surfaceToLlm) put("trailblaze/surfaceToLlm", JsonPrimitive(false))
      if (!isRecordable) put("trailblaze/isRecordable", JsonPrimitive(false))
    }
  }

  /**
   * Resolve the YAML's `script:` field against the descriptor's parent directory. Matches
   * the loader's own `script:` resolution rule (relative paths resolve against the
   * directory containing the descriptor YAML — trailmap-relative).
   *
   * **Duplicated logic, intentionally inlined.** `TrailblazeProjectConfigLoader.resolveTrailmapSiblings`
   * does the same File construction + `Path.normalize()` for the non-enrichment path. The
   * two implementations agree today and both call sites are five lines each — extraction
   * to a shared `TrailmapScriptedToolPaths.resolveScript(...)` utility was flagged in
   * the PR #3338 review thread as a deferred cleanup. If a third call site emerges or
   * the algorithm needs to grow (e.g., symlink containment policy), promote then.
   */
  private fun resolveScriptFile(trailmapDir: File, relativeDescriptorPath: String, scriptRef: String): File {
    val descriptorFile = File(trailmapDir, relativeDescriptorPath)
    val descriptorDir = descriptorFile.parentFile ?: trailmapDir
    val raw = File(scriptRef)
    return if (raw.isAbsolute) raw else File(descriptorDir, scriptRef).toPath().normalize().toFile().absoluteFile
  }

  /**
   * Re-validates the namespaced `trailblaze/...` keys in [meta] with the same shape contract
   * `TrailmapScriptedToolFile.validateKnownMetaShapes` enforces for the legacy full-YAML path.
   * Returns `null` on valid input (or absent / unknown keys), or a descriptor-aware
   * reason string on type mismatch.
   *
   * Kept colocated rather than reused from the models package because the model helper is
   * `private` and the meta-only path's diagnostic shape ("enrichment failure on tool
   * declared in `<descriptor>`") doesn't match the model's exception flow. The validations
   * stay tiny so the duplication is low-cost; an extracted shared `MetaShortcutMerger`
   * helper could replace both call sites — tracked as a deferred follow-up in the
   * PR #3338 review thread (no separate issue yet; flag inline here so the next
   * touch of either site finds the sibling).
   */
  private fun validateKnownMetaShapes(meta: JsonObject?): String? {
    if (meta == null) return null
    meta["trailblaze/requiresHost"]?.let { v ->
      if (v !is JsonPrimitive || v.isString || !(v.content == "true" || v.content == "false")) {
        return "`_meta.trailblaze/requiresHost` expected a boolean literal, got ${v::class.simpleName} '${v}'. " +
          "Prefer the top-level `requiresHost: true|false` shortcut instead of authoring this key directly."
      }
    }
    meta["trailblaze/supportedPlatforms"]?.let { v ->
      if (v !is JsonArray || v.any { it !is JsonPrimitive || !(it as JsonPrimitive).isString }) {
        return "`_meta.trailblaze/supportedPlatforms` expected a YAML list of strings " +
          "(e.g. `[android, web]`), got ${v::class.simpleName} '${v}'. Prefer the top-level " +
          "`supportedPlatforms: [...]` shortcut instead of authoring this key directly."
      }
    }
    return null
  }

  /**
   * Merge sources of namespaced `_meta:` keys for a meta-only descriptor in
   * descending precedence:
   *
   *   1. Descriptor-side `_meta:` map (explicit author keys in the YAML).
   *   2. Descriptor-side top-level shortcut fields (`requiresHost: true`,
   *      `supportedPlatforms: [...]` on the descriptor).
   *   3. Analyzer-extracted [TrailblazeTypedToolSpec] from the sibling `.ts`
   *      (`supportedPlatforms`, `requiresContext`, `requiresHost`, `supportedDrivers`).
   *
   * **Precedence rationale.** Author intent that's authored *on the descriptor*
   * (YAML) is more specific than the type-side defaults captured by the analyzer —
   * an author who writes `_meta: { trailblaze/supportedPlatforms: [android] }` on
   * the descriptor presumably intends to override whatever the `.ts` declared,
   * even if both spell their intent at different levels of the stack. The
   * analyzer's spec acts as a fill-in for fields the descriptor leaves
   * unspecified — exactly the role trailmap-level defaults would play, but per-tool
   * rather than per-trailmap and authored in TypeScript rather than YAML.
   *
   * **Why analyzer fields go through the namespaced `trailblaze/...` projection.**
   * The runtime's [TrailblazeToolMeta.fromJsonObject] reads namespaced keys
   * (`trailblaze/supportedPlatforms`, `trailblaze/requiresContext`, etc.). The
   * SDK's [TrailblazeTypedToolSpec] uses bare field names for ergonomics
   * (`supportedPlatforms`, `requiresContext`). This projection bridges the two —
   * the SDK author surface stays clean, the runtime wire shape stays unchanged,
   * and the analyzer's mapping is the seam.
   *
   * The analyzer-derived description lives on [InlineScriptToolConfig.description]
   * (set by `resolveOrFail`), not in this namespaced `_meta:` map — there's no
   * `trailblaze/description` key, and the wire-side consumers read descriptions
   * off the tool descriptor envelope directly.
   */
  private fun mergeMeta(
    descriptorMeta: JsonObject?,
    requiresHost: Boolean,
    supportedPlatforms: List<String>?,
    analyzerSpec: JsonObject?,
    surfaceToLlm: Boolean = true,
    isRecordable: Boolean = true,
  ): JsonObject? {
    val explicit = descriptorMeta ?: JsonObject(emptyMap())
    val needsSupportedPlatforms = !supportedPlatforms.isNullOrEmpty()
    val needsRequiresHost = requiresHost
    // surfaceToLlm / isRecordable default `true`; only the `false` opt-out folds a key.
    val needsSurfaceToLlm = !surfaceToLlm
    val needsIsRecordable = !isRecordable
    val analyzerProjected = projectAnalyzerSpec(analyzerSpec)
    val analyzerHasContent = analyzerProjected.isNotEmpty()
    if (
      explicit.isEmpty() &&
      !needsSupportedPlatforms &&
      !needsRequiresHost &&
      !needsSurfaceToLlm &&
      !needsIsRecordable &&
      !analyzerHasContent
    ) {
      return null
    }
    return buildJsonObject {
      // Analyzer fill-ins go FIRST so descriptor-side keys (added later) override
      // them on conflict — `buildJsonObject.put` is last-write-wins.
      analyzerProjected.forEach { (k, v) -> put(k, v) }
      // Descriptor-side explicit `_meta:` map next.
      explicit.forEach { (k, v) -> put(k, v) }
      // Descriptor-side top-level shortcuts last so they override both analyzer
      // fill-ins AND any conflicting key the author copied into their explicit
      // `_meta:` map (matches the legacy `mergeMetaShortcuts` precedence).
      if (needsSupportedPlatforms) {
        val arr = buildJsonArray {
          supportedPlatforms.orEmpty().forEach { add(JsonPrimitive(it)) }
        }
        put("trailblaze/supportedPlatforms", arr)
      }
      if (needsRequiresHost) {
        put("trailblaze/requiresHost", JsonPrimitive(true))
      }
      if (needsSurfaceToLlm) {
        put("trailblaze/surfaceToLlm", JsonPrimitive(false))
      }
      if (needsIsRecordable) {
        put("trailblaze/isRecordable", JsonPrimitive(false))
      }
    }
  }

  /**
   * Extract the typed spec's `description` field — the NEW middle tier in the description
   * precedence (YAML sidecar `description:` > spec `description` > TSDoc-derived). Unlike the
   * gate fields (`supportedPlatforms`, `surfaceToLlm`, …) which project into `_meta` via
   * [projectAnalyzerSpec], `description` is the tool's PRIMARY descriptor field, so it routes
   * straight into [InlineScriptToolConfig.description] at the two resolution sites
   * (`resolveOrFail`'s meta-only branch and [buildPartialConfig]).
   *
   * Returns `null` (treated upstream as "fall through to the next tier") when the spec is absent,
   * has no `description` key, or carries a non-string / blank value. The analyzer's inline-literal
   * extractor only captures a string literal here; the `isString` + `isNotBlank` guards defend
   * against a malformed `as any` value (e.g. `description: true`) silently winning over the TSDoc.
   */
  private fun specDescriptionOf(spec: JsonObject?): String? =
    (spec?.get("description") as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() }

  /**
   * Extract the typed spec's `trailhead` field into a [TrailheadMetadata] — the same shape a
   * `*.trailhead.yaml` sidecar's `trailhead:` block produces (see [ToolYamlConfig.trailhead]).
   * Like [specDescriptionOf], this is a primary-descriptor field: it routes straight into
   * [InlineScriptToolConfig.trailhead] at both resolution sites rather than through
   * [projectAnalyzerSpec]'s `_meta` projection, since trailhead-ness isn't a dispatch gate.
   *
   * Mirrors [xyz.block.trailblaze.config.ToolYamlConfig.validate]'s trailhead invariant (`to`
   * required unless `dynamic: true`) leniently: a malformed shape (neither `to` nor `dynamic`,
   * or both at once) logs a warning and is treated as "not a trailhead" / "dynamic wins" rather
   * than failing the whole tool — consistent with the analyzer's general "skip the unresolvable
   * bit, keep going" posture (RECOGNIZED_SPEC_FIELDS' typo policy) rather than the YAML loader's
   * hard-fail `require()`, since TypeScript's own type checker already guards authors against
   * most of this at the call site.
   */
  private fun trailheadOf(toolName: String, spec: JsonObject?): TrailheadMetadata? {
    val raw = spec?.get("trailhead") as? JsonObject ?: return null
    val to = (raw["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val dynamic = (raw["dynamic"] as? JsonPrimitive)?.booleanOrNull ?: false
    return when {
      to != null && dynamic -> {
        Console.log(
          "[AnalyzerScriptedToolEnrichment] tool '$toolName': spec's 'trailhead' block sets both " +
            "'to' and 'dynamic: true' — mutually exclusive (see TrailheadMetadata). Dropping 'to' " +
            "and treating as dynamic. Raw value: $raw",
        )
        TrailheadMetadata(dynamic = true)
      }
      to == null && !dynamic -> {
        Console.log(
          "[AnalyzerScriptedToolEnrichment] tool '$toolName': spec declares a 'trailhead' block " +
            "with neither a non-blank 'to' nor 'dynamic: true' — not a real bootstrap " +
            "destination, dropping trailhead role for this tool. Raw value: $raw",
        )
        null
      }
      else -> TrailheadMetadata(to = to, dynamic = dynamic)
    }
  }

  /**
   * Project the analyzer's bare-field-name spec object (`supportedPlatforms`,
   * `requiresContext`, ...) into the namespaced `_meta:` shape the runtime
   * (`TrailblazeToolMeta.fromJsonObject`) reads (`trailblaze/supportedPlatforms`,
   * `trailblaze/requiresContext`, ...).
   *
   * Returns an empty map when [analyzerSpec] is null or carries no recognized
   * fields. Unrecognized field names are silently dropped — the JS extractor's
   * `RECOGNIZED_SPEC_FIELDS` set is the source of truth for what's allowed;
   * defending against the same set on the Kotlin side would just duplicate the
   * authoring contract.
   */
  private fun projectAnalyzerSpec(analyzerSpec: JsonObject?): Map<String, JsonElement> {
    if (analyzerSpec == null || analyzerSpec.isEmpty()) return emptyMap()
    val projected = mutableMapOf<String, JsonElement>()
    // SISTER-IMPL-TAG: typed-tool-spec-fields. The bare-field-name set
    // (`supportedPlatforms`, `requiresContext`, `requiresHost`, `supportedDrivers`,
    // `surfaceToLlm`, `isRecordable`) is defined in THREE places that must stay in
    // lockstep when a new field is added to `TrailblazeTypedToolSpec`:
    //  1. `sdks/typescript/src/tool-core.ts`             (the SDK's TS surface)
    //  2. `sdks/typescript/tools/extract-tool-defs.mjs`  (`RECOGNIZED_SPEC_FIELDS`)
    //  3. This function (Kotlin projection into namespaced `_meta` keys)
    // The runtime parsers (`TrailblazeToolMeta.fromJsonObject` for MCP/subprocess,
    // `QuickJsToolMeta.fromSpec` for in-process) read the namespaced keys, so adding
    // a field here without updating them there means the value flows through `_meta`
    // but the runtime ignores it. There is no compile-time enforcement that the sites
    // agree — adding a parity test (or extracting a shared constant in a model module)
    // is tracked as a follow-up.
    //
    // `description` and `trailhead` are deliberately ABSENT below: both are primary-descriptor
    // fields, not `_meta` gates, so they route into `InlineScriptToolConfig.description` /
    // `InlineScriptToolConfig.trailhead` via [specDescriptionOf] / [trailheadOf] (called at the
    // resolution sites) instead of being projected here. The two runtime `_meta` parsers above
    // correctly never read either.
    analyzerSpec["supportedPlatforms"]?.let { projected["trailblaze/supportedPlatforms"] = it }
    analyzerSpec["requiresContext"]?.let { projected["trailblaze/requiresContext"] = it }
    analyzerSpec["requiresHost"]?.let { projected["trailblaze/requiresHost"] = it }
    analyzerSpec["supportedDrivers"]?.let { projected["trailblaze/supportedDrivers"] = it }
    analyzerSpec["surfaceToLlm"]?.let { projected["trailblaze/surfaceToLlm"] = it }
    analyzerSpec["isRecordable"]?.let { projected["trailblaze/isRecordable"] = it }
    return projected
  }

  /**
   * Canonical-form an arbitrary absolute path so the analyzer's `sourcePath` field and the
   * descriptor's resolved `script:` File can be matched. Both sides go through
   * `Path.normalize()` which collapses `..` / `.` segments via pure string math (no I/O),
   * then through `replace('\\', '/')` so Windows hosts (where `File.toPath().toString()`
   * uses `\` separators) still match the analyzer shim's emitted `sourcePath` strings (the
   * bun shim writes paths via `path.resolve()` which preserves the platform separator,
   * but `ts-json-schema-generator`'s internal canonicalization tends toward forward
   * slashes — converting both sides to `/` is the safe equivalence class).
   *
   * Doesn't follow symlinks — meta-only descriptors are expected to live alongside their
   * `.ts` source in the trailmap tree, and the loader's `requirePathInsideTrailmap` containment
   * check (in `DaemonScriptedToolBundler`) is the relevant safety net for symlink shenanigans.
   */
  private fun normalizePath(absolutePath: String): String =
    File(absolutePath).toPath().normalize().toString().replace('\\', '/')
}

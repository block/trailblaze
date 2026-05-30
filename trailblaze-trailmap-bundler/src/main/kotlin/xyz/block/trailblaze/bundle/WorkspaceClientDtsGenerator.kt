package xyz.block.trailblaze.bundle

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolProperty

/**
 * Daemon-time codegen that emits per-trailmap `trailblaze-client.d.ts` files declaring typed
 * `client.callTool` overloads for the tools authored TS code in that trailmap can dispatch —
 * the Kotlin-defined tools resolved through the trailmap's own platform `tool_sets:`, plus
 * trailmap-local scripted tools, plus scripted tools transitively inherited via deps' `exports:`.
 *
 * **Per-trailmap slicing — three-layer trailmap-typing model.** Each trailmap gets a typed surface
 * scoped to *what its `.ts` authors can call*: the trailmap's OWN `platforms.<p>.tool_sets:`
 * declarations (Kotlin half) plus the union of trailmap-local scripted tools and
 * transitively-inherited scripted tools from `exports:`. This is the *typed surface* layer
 * — distinct from the runtime registry (transitive union of every trailmap-in-closure's
 * `tool_sets:`) and the agent toolbox (closest-wins on the target's `tool_sets:` + library
 * `exports:`). See [TrailmapRuntimeRegistryResolver]'s kdoc for the full model and gradle
 * analogy.
 *
 * **Output path:** `<trailmapDir>/tools/trailblaze-client.d.ts` — one file per trailmap.
 * Lives directly in `tools/` (alongside the trailmap's `.ts` source files, not hidden
 * inside a `.trailblaze/` subdir) so the on-disk layout is honest: this is a typed
 * source-of-truth file an author can commit. Authors can either commit it (treat as
 * a checked-in API contract) OR add it to `.gitignore` (treat as derived output) —
 * both choices are supported because the file is idempotent: a re-run with the same
 * registered toolset writes the same bytes.
 *
 * **Companion to [TrailblazeTrailmapBundler].** That class emits `tools.d.ts` per build for the
 * scripted tools a *single trailmap* declares — useful when authoring inside a trailmap's source
 * tree before the daemon is running. THIS class emits `trailblaze-client.d.ts` per trailmap at *daemon
 * startup* / `trailblaze compile` time. Both files declaration-merge into the same
 * `TrailblazeToolMap` interface in `@trailblaze/scripting`.
 *
 * **Reuses [jsonSchemaToTsType]** (top-level `internal` in this package) so the schema → TS
 * vocabulary stays identical to the per-trailmap writer. New schema types added there flow
 * through automatically.
 */
class WorkspaceClientDtsGenerator {
  /**
   * Generate the trailmap's `trailblaze-client.d.ts` under `<trailmapDir>/tools/trailblaze-client.d.ts`
   * using author-shape scripted tools ([TrailmapScriptedToolFile] — the flat `inputSchema`
   * map). Used by tests and any caller working directly off the trailmap-manifest YAML shape.
   *
   * For the post-compile flow (after `TrailblazeCompiler.compile()` has resolved per-target
   * tool closures), prefer [generateForTrailmapFromResolved] — its inputs are already the
   * runtime shape, no need to round-trip through author shape.
   *
   * Writes only if the rendered content differs from any existing output, so a daemon
   * restart that finds the same tool registry doesn't churn mtimes (and downstream tooling
   * — IDE TS servers, file watchers — doesn't churn cache).
   *
   * Returns the absolute path of the output file. The parent dir is created on demand.
   */
  fun generateForTrailmap(
    trailmapDir: Path,
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<TrailmapScriptedToolFile>,
    frameworkMetadataByName: Map<String, ToolFrameworkMetadata> = emptyMap(),
  ): Path {
    val entries = collectEntries(toolDescriptors, scriptedTools, frameworkMetadataByName)
    return writeRendered(entries, trailmapDir)
  }

  /**
   * Generate the trailmap's `trailblaze-client.d.ts` from resolved [InlineScriptToolConfig] entries (the
   * shape `TrailblazeCompiler.compile()` emits, with `target.tools:` already inlined and
   * JSON Schema already shaped). Primary daemon-init / `trailblaze compile` wire-in path.
   *
   * [typedToolOverrides] (optional, keyed by tool name) carries the analyzer's view of any
   * scripted tool authored via `trailblaze.tool<I, O>({ handler })`. When an entry's name
   * matches a key in this map, the emitted typed surface uses the analyzer's input + output
   * JSON Schemas (serialized to TS literals by [JsonSchemaToTsRich]) instead of the
   * YAML-derived flat decomposition. Tools without an override (legacy YAML-only, or
   * Kotlin descriptors) keep the existing rendering. See [TypedToolOverride].
   */
  fun generateForTrailmapFromResolved(
    trailmapDir: Path,
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<InlineScriptToolConfig>,
    typedToolOverrides: Map<String, TypedToolOverride> = emptyMap(),
    frameworkMetadataByName: Map<String, ToolFrameworkMetadata> = emptyMap(),
  ): Path {
    val entries = collectEntriesFromResolved(
      toolDescriptors = toolDescriptors,
      scriptedTools = scriptedTools,
      typedToolOverrides = typedToolOverrides,
      frameworkMetadataByName = frameworkMetadataByName,
    )
    return writeRendered(entries, trailmapDir)
  }

  /**
   * Shared write path — idempotent (skip-write-if-content-matches).
   *
   * Defense in depth: even though both production call sites currently pass a [trailmapDir]
   * derived from `TrailmapSource.Filesystem.trailmapDir` (which the trailmap loader has already
   * canonicalized + containment-validated), the generator is a `public class` so a
   * future caller could pass a relative or `..`-laced path. The `require` below ensures
   * an absolute, existing directory — the same containment posture as the
   * `SAFE_FILENAME_PATTERN` guard the previous workspace-wide emitter used, just moved
   * to the new boundary (the path now comes from `trailmapDir`, not from a user-authored
   * filename template).
   */
  private fun writeRendered(entries: List<ToolEntry>, trailmapDir: Path): Path {
    require(trailmapDir.isAbsolute && Files.isDirectory(trailmapDir)) {
      "WorkspaceClientDtsGenerator: trailmapDir must be an absolute path to an existing " +
        "directory (got '$trailmapDir'). Callers should pass `TrailmapSource.Filesystem.trailmapDir` " +
        "or an equivalently-canonicalized path."
    }
    val rendered = renderClientDts(entries)
    val outputPath = trailmapDir
      .resolve(TRAILMAP_TOOLS_SUBDIR)
      .resolve(GENERATED_FILE_NAME)
      .toAbsolutePath()

    // Migration cleanup: prior framework versions wrote the typed surface to
    // `<trailmapDir>/tools/.trailblaze/client.d.ts`. After the rename, output
    // lives directly under `tools/`. A developer with a stale copy of the old
    // file on disk would end up with TWO files augmenting `TrailblazeToolMap`
    // for the same trailmap — at best a duplicate, at worst conflicting tool
    // entries that produce TS errors. Delete the legacy file if present, and
    // remove the now-empty legacy dir. Idempotent — no-op once cleaned.
    pruneLegacyClientDtsLocation(trailmapDir)

    Files.createDirectories(outputPath.parent)
    val existing = if (Files.isRegularFile(outputPath)) Files.readString(outputPath) else null
    if (existing != rendered) {
      Files.writeString(outputPath, rendered)
    }
    return outputPath
  }

  /**
   * One-shot cleanup of the pre-rename codegen location
   * (`<trailmapDir>/tools/.trailblaze/client.d.ts`). Only deletes the legacy file
   * if present, and only removes the legacy directory if it ends up empty —
   * defensive against a future framework version that might write other
   * artifacts under the same path.
   */
  private fun pruneLegacyClientDtsLocation(trailmapDir: Path) {
    val legacyDir = trailmapDir.resolve(TRAILMAP_TOOLS_SUBDIR).resolve(LEGACY_GENERATED_DIR_NAME)
    val legacyFile = legacyDir.resolve(LEGACY_GENERATED_FILE_NAME)
    if (Files.isRegularFile(legacyFile)) {
      Files.delete(legacyFile)
    }
    if (Files.isDirectory(legacyDir)) {
      val empty = Files.newDirectoryStream(legacyDir).use { !it.iterator().hasNext() }
      if (empty) Files.delete(legacyDir)
    }
  }

  /**
   * Visible for testing — flat sorted-by-name list of `(toolName, description?, params)`
   * entries collected from both the Kotlin descriptor list and the scripted-tool YAML list.
   *
   * Across-source duplicate names are silently de-duplicated by name with the *Kotlin*
   * descriptor winning. Rationale: the Kotlin side is the runtime-of-record (its schema is
   * what the dispatcher actually validates against); a scripted-tool YAML claiming the same
   * name would be the runtime-confusing case. The bundler's per-trailmap writer fails loudly on
   * duplicate scripted-tool names (declaration-merging silent collision); here at daemon
   * time we accept the tradeoff because the daemon may legitimately register the same name
   * across reloads / trailmap remounts and we'd rather emit a single typed entry than throw.
   */
  internal fun collectEntries(
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<TrailmapScriptedToolFile>,
    frameworkMetadataByName: Map<String, ToolFrameworkMetadata> = emptyMap(),
  ): List<ToolEntry> {
    val byName = LinkedHashMap<String, ToolEntry>()
    toolDescriptors.forEach { td ->
      // First-write-wins so later registrations of the same name (e.g. dynamic re-add)
      // don't shadow the one that was first registered. Stable-by-name is the contract.
      byName.putIfAbsent(td.name, td.toEntry(frameworkMetadataByName[td.name]))
    }
    scriptedTools.forEach { st ->
      // Scripted-tool entries lose to a Kotlin descriptor of the same name (see kdoc).
      // Each descriptor expands to 1..N entries: single-tool descriptors yield one entry,
      // multi-tool descriptors (with `tools: [...]`) yield one entry per `tools:` entry. The
      // bundler's typed `client.tools.<name>(...)` surface needs one entry per advertised
      // tool, so the flat list collapses multi-tool descriptors back to per-tool granularity here.
      st.toEntries().forEach { entry ->
        byName.putIfAbsent(entry.name, entry)
      }
    }
    return byName.values.sortedBy { it.name }
  }

  private fun ToolDescriptor.toEntry(frameworkMetadata: ToolFrameworkMetadata? = null): ToolEntry = ToolEntry(
    name = name,
    description = description.takeUnless { it.isBlank() },
    params = (
      requiredParameters.map { it.toParam(optional = false, contextOwner = name) } +
        optionalParameters.map { it.toParam(optional = true, contextOwner = name) }
      ),
    frameworkMetadata = frameworkMetadata,
  )

  private fun ToolParameterDescriptor.toParam(optional: Boolean, contextOwner: String): ToolParam =
    ToolParam(
      name = name,
      tsType = type.toTsType("Tool '$contextOwner' parameter '$name'"),
      description = description.takeUnless { it.isBlank() },
      optional = optional,
    )

  /**
   * Bridges [ToolParameterType] (koog's runtime parameter shape) to the same TS vocabulary the
   * per-trailmap writer uses — by routing through [jsonSchemaToTsType] for every variant whose
   * shape can be expressed as a `(type, enum?)` pair. The closed sealed set is exhausted so
   * a future addition (`AnyOf`, etc. — already present) is forced into a deliberate decision
   * here rather than silently falling back to `unknown` further downstream.
   */
  private fun ToolParameterType.toTsType(propertyContext: String): String = when (this) {
    is ToolParameterType.String -> jsonSchemaToTsType("string", null, propertyContext)
    is ToolParameterType.Integer -> jsonSchemaToTsType("integer", null, propertyContext)
    is ToolParameterType.Float -> jsonSchemaToTsType("number", null, propertyContext)
    is ToolParameterType.Boolean -> jsonSchemaToTsType("boolean", null, propertyContext)
    is ToolParameterType.Null -> jsonSchemaToTsType("null", null, propertyContext)
    is ToolParameterType.Enum -> jsonSchemaToTsType(null, entries.toList(), propertyContext)
    is ToolParameterType.List -> jsonSchemaToTsType("array", null, propertyContext)
    is ToolParameterType.Object -> jsonSchemaToTsType("object", null, propertyContext)
    // AnyOf is the union case — we don't model union types in the per-trailmap writer either,
    // so route to `unknown` via the unknown-type-name branch. Keeps both writers in lockstep.
    is ToolParameterType.AnyOf -> jsonSchemaToTsType(null, null, propertyContext)
  }

  /**
   * Expands a [TrailmapScriptedToolFile] into 1..N [ToolEntry] values:
   *
   *  - Single-tool descriptor (top-level `name:` + `inputSchema:`) → one entry.
   *  - Multi-tool descriptor (`tools: [...]`) → one entry per `tools:` entry, each carrying
   *    its own name / description / inputSchema. File-wide shortcuts (`supportedPlatforms`,
   *    `requiresHost`, `_meta`) don't surface in the typed `.d.ts` client surface — they're
   *    framework-level routing concerns, not author-facing tool args.
   */
  private fun TrailmapScriptedToolFile.toEntries(): List<ToolEntry> {
    val multiToolEntries = tools
    if (multiToolEntries != null) {
      return multiToolEntries.map { entry ->
        ToolEntry(
          name = entry.name,
          description = entry.description?.takeUnless { it.isBlank() },
          params = entry.inputSchema.map { (key, prop) -> prop.toParam(key, owner = entry.name) },
        )
      }
    }
    val singleName = requireNotNull(name) {
      "Single-tool scripted-tool descriptor for script '$script' is missing the required `name:` field. " +
        "Either set `name:` or use the multi-tool shape with `tools:`."
    }
    return listOf(
      ToolEntry(
        name = singleName,
        description = description?.takeUnless { it.isBlank() },
        params = inputSchema.map { (key, prop) -> prop.toParam(key, owner = singleName) },
      ),
    )
  }

  /**
   * Sister of [collectEntries] for the post-compile [InlineScriptToolConfig] shape. The merge
   * semantics are identical (Kotlin descriptors first, scripted tools second, first-write-
   * wins) — only the input shape differs. Pulled out so the per-target wire-in (which has
   * resolved-target [InlineScriptToolConfig]s already produced by `TrailblazeCompiler`) can
   * skip the author→runtime round-trip.
   */
  internal fun collectEntriesFromResolved(
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<InlineScriptToolConfig>,
    typedToolOverrides: Map<String, TypedToolOverride> = emptyMap(),
    frameworkMetadataByName: Map<String, ToolFrameworkMetadata> = emptyMap(),
  ): List<ToolEntry> {
    val byName = LinkedHashMap<String, ToolEntry>()
    toolDescriptors.forEach { td ->
      byName.putIfAbsent(td.name, td.toEntry(frameworkMetadataByName[td.name]))
    }
    scriptedTools.forEach { st ->
      val override = typedToolOverrides[st.name]
      val entry = if (override != null) st.toEntry(override) else st.toEntry()
      byName.putIfAbsent(st.name, entry)
    }
    return byName.values.sortedBy { it.name }
  }

  /**
   * Convert a runtime-shape [InlineScriptToolConfig] (post-compile, `inputSchema` is JSON
   * Schema `{ type: object, properties: { ... }, required: [...] }`) back into the flat
   * [ToolEntry] vocabulary the renderer consumes.
   *
   * The walk handles three cases the renderer cares about:
   *  - `type: object` with `properties: {...}` — every property becomes a [ToolParam]; the
   *    `required: [...]` array determines which are non-optional.
   *  - `type: object` with no properties — the tool takes no args (renders as
   *    `Record<string, never>`).
   *  - anything else — degrades to "no params" and the renderer emits the same empty shape.
   *    (The trailmap loader rejects scripted tools that don't conform, so this branch is a
   *    defensive fallback — not a path real authored tools land in.)
   */
  private fun InlineScriptToolConfig.toEntry(): ToolEntry {
    val schemaProperties = inputSchema["properties"] as? JsonObject
    val requiredNames: Set<String> = (inputSchema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.toSet()
      .orEmpty()
    val params = schemaProperties?.entries.orEmpty().mapNotNull { (key, propEl) ->
      // Defensive: a non-object property entry violates JSON Schema, but the kdoc above
      // promises graceful degradation. Use a safe cast and skip the property — the
      // alternative (`propEl.jsonObject` throws `IllegalArgumentException`) would crash
      // codegen for the entire workspace because of one malformed legacy descriptor.
      val propObj = propEl as? JsonObject ?: return@mapNotNull null
      val type = (propObj["type"] as? JsonPrimitive)?.contentOrNull
      val description = (propObj["description"] as? JsonPrimitive)?.contentOrNull
      val enumValues = (propObj["enum"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ToolParam(
        name = key,
        tsType = jsonSchemaToTsType(
          type = type,
          enumValues = enumValues,
          propertyContext = "Scripted tool '$name' property '$key'",
        ),
        description = description?.takeUnless { it.isBlank() },
        optional = key !in requiredNames,
      )
    }
    return ToolEntry(
      name = name,
      description = description?.takeUnless { it.isBlank() },
      params = params,
    )
  }

  /**
   * Analyzer-aware variant of [toEntry] — replaces the args / result rendering with TS
   * literals serialized from the analyzer's JSON Schemas. The literals are indented to
   * line up with the surrounding `args: {...}` block at the renderer's 6-space depth
   * (matches [renderToolMapEntry]'s output shape).
   *
   * Description precedence: the analyzer's description (TSDoc on the exported `const`)
   * wins over the YAML-derived `description:` because the analyzer-aware path treats the
   * TS source as canonical (see the PR description's "TS source is the single source of
   * truth" framing).
   */
  private fun InlineScriptToolConfig.toEntry(override: TypedToolOverride): ToolEntry {
    // `TypedToolOverride` carries `JsonObject` schemas by contract (tightened from
    // `JsonElement` after lead-dev review #1), so the definitions lookup needs no
    // type-narrowing cast — every analyzer-shaped schema has a sibling `definitions`
    // / `$defs` bag at the top level when nested refs are present, and that bag is
    // either present-as-JsonObject or absent.
    val argsLiteral = JsonSchemaToTsRich.render(
      schema = override.inputSchema,
      definitions = override.inputSchema["definitions"] as? JsonObject
        ?: override.inputSchema["\$defs"] as? JsonObject,
      baseIndent = 6,
    )
    val resultLiteral = JsonSchemaToTsRich.render(
      schema = override.outputSchema,
      definitions = override.outputSchema["definitions"] as? JsonObject
        ?: override.outputSchema["\$defs"] as? JsonObject,
      baseIndent = 6,
    )
    val effectiveDescription = override.description?.takeUnless { it.isBlank() }
      ?: description?.takeUnless { it.isBlank() }
    return ToolEntry(
      name = name,
      description = effectiveDescription,
      params = emptyList(),
      argsLiteralTsType = argsLiteral,
      resultTsType = resultLiteral,
    )
  }

  private fun ScriptedToolProperty.toParam(key: String, owner: String): ToolParam = ToolParam(
    name = key,
    tsType = jsonSchemaToTsType(
      type = type,
      enumValues = enum,
      propertyContext = "Scripted tool '$owner' property '$key'",
    ),
    description = description?.takeUnless { it.isBlank() },
    optional = !required,
  )

  /**
   * Rendered shape mirrors the bundler's per-trailmap output: a single `declare module` block
   * augmenting `TrailblazeToolMap` with one entry per registered tool. Empty-input case
   * still emits a valid TS module (`export {}` + an empty `interface TrailblazeToolMap`)
   * so a downstream import never fails at type-check.
   *
   * **JSDoc-comment escape (asterisk-slash to asterisk-space-slash):** an embedded
   * asterisk-slash in a tool description would close the surrounding JSDoc block
   * prematurely, syntax-erroring the file. Same defense the per-trailmap writer uses.
   */
  private fun renderClientDts(entries: List<ToolEntry>): String = buildString {
    appendLine("// GENERATED BY TRAILBLAZE DAEMON. DO NOT EDIT.")
    appendLine("// Re-runs on daemon restart. Idempotent — content-stable across restarts of the same toolset.")
    appendLine("// Safe to commit (treat as API contract) OR gitignore (treat as derived output).")
    appendLine()
    appendLine("declare module \"@trailblaze/scripting\" {")
    appendLine("  interface TrailblazeToolMap {")
    entries.forEachIndexed { index, entry ->
      if (index > 0) appendLine()
      // Per-entry shape is shared with `TrailblazeTrailmapBundler` via [renderToolMapEntry];
      // both files declaration-merge into the same `TrailblazeToolMap`, so they MUST
      // produce identical block shapes per tool.
      append(renderToolMapEntry(entry.toRendererEntry()))
    }
    appendLine("  }")
    appendLine("}")
    appendLine()
    appendLine("export {};")
  }

  private fun ToolEntry.toRendererEntry(): ToolMapEntry = ToolMapEntry(
    name = name,
    description = description,
    params = params.map { ToolMapParam(it.name, it.tsType, it.description, it.optional) },
    sourceAttribution = null,
    argsLiteralTsType = argsLiteralTsType,
    resultTsType = resultTsType,
    frameworkMetadata = frameworkMetadata,
  )

  /**
   * Internal in-memory shape — NOT a public API surface.
   *
   * **Parallel shape.** Generator-local input to the shared renderer; the bundler has its
   * own [ScriptedToolEntry] (carrying a non-nullable `sourcePath`), and both adapt to
   * [ToolMapEntry] via a per-emitter `toRendererEntry()` extension. Keep all three shapes
   * in sync when adding fields.
   */
  internal data class ToolEntry(
    val name: String,
    val description: String?,
    val params: List<ToolParam>,
    /**
     * When non-null, replaces the per-[params] decomposition at render time. Populated by
     * the analyzer-aware path so a typed-authored tool's `I` shape — including nested
     * objects and TSDoc — flows through verbatim.
     */
    val argsLiteralTsType: String? = null,
    /** Analyzer-derived `O` shape; when null the renderer emits the today-default `string`. */
    val resultTsType: String? = null,
    /**
     * `TrailblazeToolClass` annotation values for this tool (when supplied by the caller).
     * Flows into [ToolMapEntry.frameworkMetadata] for the renderer to emit as
     * `@trailblaze<Concept>` JSDoc tags. Always `null` for scripted-tool-only entries
     * today.
     */
    val frameworkMetadata: ToolFrameworkMetadata? = null,
  )

  internal data class ToolParam(
    val name: String,
    val tsType: String,
    val description: String?,
    val optional: Boolean,
  )

  /**
   * Analyzer-derived override carrying the TS-source-authoritative typed surface for one
   * scripted tool. The bundler's daemon-time consumer
   * ([xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter]) runs the
   * [xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer] per trailmap, turns each
   * extracted [xyz.block.trailblaze.scripting.ScriptedToolDefinition] into one of these,
   * and hands the map to [generateForTrailmapFromResolved].
   *
   *  - [description] — TSDoc on the exported `const`. Wins over the YAML-derived
   *    `description:` because the typed-authoring path makes the TS source canonical.
   *    Null when no TSDoc was attached.
   *  - [inputSchema] / [outputSchema] — JSON Schemas produced by `ts-json-schema-generator`
   *    via the analyzer. Typed as `JsonObject` (NOT the looser `JsonElement`) so the
   *    field signature matches the analyzer's own
   *    [xyz.block.trailblaze.scripting.ScriptedToolDefinition.init] contract — both
   *    sides agree the top-level schema is always an object. Top-level shape is the
   *    inlined root type (the analyzer uses `topRef: false`); nested complex types
   *    live under a sibling `definitions:` / `$defs:` bag that [JsonSchemaToTsRich]
   *    resolves recursively.
   */
  data class TypedToolOverride(
    val description: String?,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
  )

  companion object {
    /** Subdirectory under each trailmap where the generator writes — matches authoring convention. */
    const val TRAILMAP_TOOLS_SUBDIR: String = "tools"

    /**
     * Output filename — kebab-case to match the repo's `.d.ts` naming convention
     * (`built-in-tools.ts`, `runtime-globals.d.ts`). The `trailblaze-` prefix makes it
     * self-describing in an author's editor sidebar. Written directly into
     * `<trailmapDir>/tools/` (not into a hidden `.trailblaze/` subdirectory) so a clone
     * that commits this file doesn't have to wrestle gitignore arithmetic — the
     * directory name accurately conveys "this is tracked source-of-truth typed surface."
     */
    const val GENERATED_FILE_NAME: String = "trailblaze-client.d.ts"

    /**
     * Pre-rename codegen location — kept here as a constant only for the migration
     * cleanup in [writeRendered]. New code should never reference these; once every
     * monorepo checkout has run `trailblaze check` at least once post-rename, the
     * legacy file is gone everywhere and the cleanup is a no-op.
     */
    internal const val LEGACY_GENERATED_DIR_NAME: String = ".trailblaze"
    internal const val LEGACY_GENERATED_FILE_NAME: String = "client.d.ts"
  }
}

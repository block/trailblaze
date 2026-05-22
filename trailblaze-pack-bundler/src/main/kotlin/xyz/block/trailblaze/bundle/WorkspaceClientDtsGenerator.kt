package xyz.block.trailblaze.bundle

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.PackScriptedToolFile
import xyz.block.trailblaze.config.project.ScriptedToolProperty

/**
 * Daemon-time codegen that emits per-pack `client.d.ts` files declaring typed
 * `client.callTool` overloads for the tools authored TS code in that pack can dispatch —
 * the Kotlin-defined tools resolved through the pack's own platform `tool_sets:`, plus
 * pack-local scripted tools, plus scripted tools transitively inherited via deps' `exports:`.
 *
 * **Per-pack slicing — three-layer pack-typing model.** Each pack gets a typed surface
 * scoped to *what its `.ts` authors can call*: the pack's OWN `platforms.<p>.tool_sets:`
 * declarations (Kotlin half) plus the union of pack-local scripted tools and
 * transitively-inherited scripted tools from `exports:`. This is the *typed surface* layer
 * — distinct from the runtime registry (transitive union of every pack-in-closure's
 * `tool_sets:`) and the agent toolbox (closest-wins on the target's `tool_sets:` + library
 * `exports:`). See [PackRuntimeRegistryResolver]'s kdoc for the full model and gradle
 * analogy.
 *
 * **Output path:** `<packDir>/tools/.trailblaze/client.d.ts` — one file per pack. The
 * `.trailblaze/` parent dir is named after the framework so authors can either commit it
 * (treat the typed surface as a checked-in API contract) OR add it to `.gitignore` (treat
 * it as derived output) — both choices are supported because the file is idempotent: a
 * re-run with the same registered toolset writes the same bytes.
 *
 * **Companion to [TrailblazePackBundler].** That class emits `tools.d.ts` per build for the
 * scripted tools a *single pack* declares — useful when authoring inside a pack's source
 * tree before the daemon is running. THIS class emits `client.d.ts` per pack at *daemon
 * startup* / `trailblaze compile` time. Both files declaration-merge into the same
 * `TrailblazeToolMap` interface in `@trailblaze/scripting`.
 *
 * **Reuses [jsonSchemaToTsType]** (top-level `internal` in this package) so the schema → TS
 * vocabulary stays identical to the per-pack writer. New schema types added there flow
 * through automatically.
 */
class WorkspaceClientDtsGenerator {
  /**
   * Generate the pack's `client.d.ts` under `<packDir>/tools/.trailblaze/client.d.ts`
   * using author-shape scripted tools ([PackScriptedToolFile] — the flat `inputSchema`
   * map). Used by tests and any caller working directly off the pack-manifest YAML shape.
   *
   * For the post-compile flow (after `TrailblazeCompiler.compile()` has resolved per-target
   * tool closures), prefer [generateForPackFromResolved] — its inputs are already the
   * runtime shape, no need to round-trip through author shape.
   *
   * Writes only if the rendered content differs from any existing output, so a daemon
   * restart that finds the same tool registry doesn't churn mtimes (and downstream tooling
   * — IDE TS servers, file watchers — doesn't churn cache).
   *
   * Returns the absolute path of the output file. The parent dir is created on demand.
   */
  fun generateForPack(
    packDir: Path,
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<PackScriptedToolFile>,
  ): Path {
    val entries = collectEntries(toolDescriptors, scriptedTools)
    return writeRendered(entries, packDir)
  }

  /**
   * Generate the pack's `client.d.ts` from resolved [InlineScriptToolConfig] entries (the
   * shape `TrailblazeCompiler.compile()` emits, with `target.tools:` already inlined and
   * JSON Schema already shaped). Primary daemon-init / `trailblaze compile` wire-in path.
   */
  fun generateForPackFromResolved(
    packDir: Path,
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<InlineScriptToolConfig>,
  ): Path {
    val entries = collectEntriesFromResolved(toolDescriptors, scriptedTools)
    return writeRendered(entries, packDir)
  }

  /**
   * Shared write path — idempotent (skip-write-if-content-matches).
   *
   * Defense in depth: even though both production call sites currently pass a [packDir]
   * derived from `PackSource.Filesystem.packDir` (which the pack loader has already
   * canonicalized + containment-validated), the generator is a `public class` so a
   * future caller could pass a relative or `..`-laced path. The `require` below ensures
   * an absolute, existing directory — the same containment posture as the
   * `SAFE_FILENAME_PATTERN` guard the previous workspace-wide emitter used, just moved
   * to the new boundary (the path now comes from `packDir`, not from a user-authored
   * filename template).
   */
  private fun writeRendered(entries: List<ToolEntry>, packDir: Path): Path {
    require(packDir.isAbsolute && Files.isDirectory(packDir)) {
      "WorkspaceClientDtsGenerator: packDir must be an absolute path to an existing " +
        "directory (got '$packDir'). Callers should pass `PackSource.Filesystem.packDir` " +
        "or an equivalently-canonicalized path."
    }
    val rendered = renderClientDts(entries)
    val outputPath = packDir
      .resolve(PACK_TOOLS_SUBDIR)
      .resolve(GENERATED_DIR_NAME)
      .resolve(GENERATED_FILE_NAME)
      .toAbsolutePath()
    Files.createDirectories(outputPath.parent)
    val existing = if (Files.isRegularFile(outputPath)) Files.readString(outputPath) else null
    if (existing != rendered) {
      Files.writeString(outputPath, rendered)
    }
    return outputPath
  }

  /**
   * Visible for testing — flat sorted-by-name list of `(toolName, description?, params)`
   * entries collected from both the Kotlin descriptor list and the scripted-tool YAML list.
   *
   * Across-source duplicate names are silently de-duplicated by name with the *Kotlin*
   * descriptor winning. Rationale: the Kotlin side is the runtime-of-record (its schema is
   * what the dispatcher actually validates against); a scripted-tool YAML claiming the same
   * name would be the runtime-confusing case. The bundler's per-pack writer fails loudly on
   * duplicate scripted-tool names (declaration-merging silent collision); here at daemon
   * time we accept the tradeoff because the daemon may legitimately register the same name
   * across reloads / pack remounts and we'd rather emit a single typed entry than throw.
   */
  internal fun collectEntries(
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<PackScriptedToolFile>,
  ): List<ToolEntry> {
    val byName = LinkedHashMap<String, ToolEntry>()
    toolDescriptors.forEach { td ->
      // First-write-wins so later registrations of the same name (e.g. dynamic re-add)
      // don't shadow the one that was first registered. Stable-by-name is the contract.
      byName.putIfAbsent(td.name, td.toEntry())
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

  private fun ToolDescriptor.toEntry(): ToolEntry = ToolEntry(
    name = name,
    description = description.takeUnless { it.isBlank() },
    params = (
      requiredParameters.map { it.toParam(optional = false, contextOwner = name) } +
        optionalParameters.map { it.toParam(optional = true, contextOwner = name) }
      ),
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
   * per-pack writer uses — by routing through [jsonSchemaToTsType] for every variant whose
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
    // AnyOf is the union case — we don't model union types in the per-pack writer either,
    // so route to `unknown` via the unknown-type-name branch. Keeps both writers in lockstep.
    is ToolParameterType.AnyOf -> jsonSchemaToTsType(null, null, propertyContext)
  }

  /**
   * Expands a [PackScriptedToolFile] into 1..N [ToolEntry] values:
   *
   *  - Single-tool descriptor (top-level `name:` + `inputSchema:`) → one entry.
   *  - Multi-tool descriptor (`tools: [...]`) → one entry per `tools:` entry, each carrying
   *    its own name / description / inputSchema. File-wide shortcuts (`supportedPlatforms`,
   *    `requiresHost`, `_meta`) don't surface in the typed `.d.ts` client surface — they're
   *    framework-level routing concerns, not author-facing tool args.
   */
  private fun PackScriptedToolFile.toEntries(): List<ToolEntry> {
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
  ): List<ToolEntry> {
    val byName = LinkedHashMap<String, ToolEntry>()
    toolDescriptors.forEach { td -> byName.putIfAbsent(td.name, td.toEntry()) }
    scriptedTools.forEach { st -> byName.putIfAbsent(st.name, st.toEntry()) }
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
   *    (The pack loader rejects scripted tools that don't conform, so this branch is a
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
   * Rendered shape mirrors the bundler's per-pack output: a single `declare module` block
   * augmenting `TrailblazeToolMap` with one entry per registered tool. Empty-input case
   * still emits a valid TS module (`export {}` + an empty `interface TrailblazeToolMap`)
   * so a downstream import never fails at type-check.
   *
   * **JSDoc-comment escape (asterisk-slash to asterisk-space-slash):** an embedded
   * asterisk-slash in a tool description would close the surrounding JSDoc block
   * prematurely, syntax-erroring the file. Same defense the per-pack writer uses.
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
      appendLine("    /**")
      if (entry.description != null) {
        entry.description.lines().forEach { line ->
          appendLine("     * ${escapeComment(line)}")
        }
      }
      appendLine("     */")
      val tsName = if (isSafeIdentifier(entry.name)) entry.name else "\"${entry.name}\""
      if (entry.params.isEmpty()) {
        appendLine("    $tsName: Record<string, never>;")
      } else {
        appendLine("    $tsName: {")
        entry.params.forEach { param ->
          if (param.description != null) {
            appendLine("      /** ${escapeComment(param.description)} */")
          }
          val maybeOptional = if (param.optional) "?" else ""
          val key = if (isSafeIdentifier(param.name)) param.name else "\"${param.name}\""
          appendLine("      $key$maybeOptional: ${param.tsType};")
        }
        appendLine("    };")
      }
    }
    appendLine("  }")
    appendLine("}")
    appendLine()
    appendLine("export {};")
  }

  // Prevent an embedded JSDoc closer in a tool description from prematurely closing the
  // JSDoc block in the generated output (mirrors `TrailblazePackBundler.escapeComment`).
  private fun escapeComment(text: String): String = text.replace("*/", "* /")

  /**
   * TypeScript identifier rule (subset): start with `[A-Za-z_$]`, then `[A-Za-z0-9_$]*`. The
   * conservative subset rather than the full Unicode-aware definition because tool/property
   * names in this codebase are ASCII snake/camelCase. A name failing this check is emitted as
   * a quoted property (`"weird-name": ...`). Mirrors the per-pack writer in
   * [TrailblazePackBundler] so authors see consistent output across both files.
   */
  private fun isSafeIdentifier(name: String): Boolean {
    if (name.isEmpty()) return false
    val first = name[0]
    if (!(first.isLetter() || first == '_' || first == '$')) return false
    return name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
  }

  /** Internal in-memory shape — NOT a public API surface. */
  internal data class ToolEntry(
    val name: String,
    val description: String?,
    val params: List<ToolParam>,
  )

  internal data class ToolParam(
    val name: String,
    val tsType: String,
    val description: String?,
    val optional: Boolean,
  )

  companion object {
    /** Subdirectory under each pack where the generator writes — matches authoring convention. */
    const val PACK_TOOLS_SUBDIR: String = "tools"

    /** Hidden subdirectory the generator writes into so a `.gitignore` can target the whole tree. */
    const val GENERATED_DIR_NAME: String = ".trailblaze"

    /** Output filename — `client.d.ts` so an author's tsconfig finds it via the standard glob. */
    const val GENERATED_FILE_NAME: String = "client.d.ts"
  }
}

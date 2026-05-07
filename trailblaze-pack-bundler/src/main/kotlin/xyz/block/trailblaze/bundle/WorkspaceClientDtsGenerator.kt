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
 * Daemon-time codegen that emits workspace-local `.d.ts` files declaring typed `client.callTool`
 * overloads for the tools registered in this Trailblaze daemon — both Kotlin-defined
 * (`ToolDescriptor` from `TrailblazeToolRepo.getCurrentToolDescriptors()`) and per-pack
 * scripted tools (either the author-shape [PackScriptedToolFile] parsed from pack manifests'
 * `target.tools:` entries, or the post-compile [InlineScriptToolConfig] shape produced by
 * `TrailblazeCompiler.compile()`).
 *
 * **Per-target slicing.** Each resolved app target gets its own `client.<target-id>.d.ts`
 * file containing only the tools that target's resolved toolsets expose, so an author
 * editing a pack imports the matching slice and gets autocomplete scoped to that target.
 * For example, a workspace with `clock` and `wikipedia` target packs will produce both
 * `client.clock.d.ts` and `client.wikipedia.d.ts`. Cross-target tool autocomplete pollution
 * is avoided. Within a target's binding every platform/driver variant is included — a
 * cross-platform conditional tool sees `*_android_*` and `*_ios_*` siblings together at
 * authoring time.
 *
 * **Output path:** `<workspace>/trails/config/tools/.trailblaze/<filename>`. Default filename
 * (single-file legacy callers): `client.d.ts`. Per-target callers pass
 * `outputFileName = "client.<target-id>.d.ts"`. The `.trailblaze/` parent dir is named after
 * the framework so authors can either commit it (treat the typed surface as a checked-in API
 * contract) OR add it to `.gitignore` (treat it as derived output) — both choices are
 * supported because the file is idempotent: a re-run with the same registered toolset writes
 * the same bytes.
 *
 * **Companion to [TrailblazePackBundler].** That class emits `tools.d.ts` per build for the
 * scripted tools a *single pack* declares — useful when authoring inside a pack's source
 * tree before the daemon is running. THIS class emits per-target `client.<target>.d.ts` at
 * *daemon startup* covering the resolved toolset (built-ins + every pack's scripted tools
 * resolved across the workspace, sliced per target). Authors get autocomplete at author-time
 * (per-pack file) AND at runtime-aware time (per-target workspace file).
 *
 * **Reuses [jsonSchemaToTsType]** (top-level `internal` in this package) so the schema → TS
 * vocabulary stays identical to the per-pack writer. New schema types added there flow
 * through automatically.
 */
class WorkspaceClientDtsGenerator(
  private val workspaceRoot: Path,
) {
  /**
   * Generate the `.d.ts` file using author-shape scripted tools ([PackScriptedToolFile] —
   * the flat `inputSchema` map). Used by tests and any caller working directly off the
   * pack-manifest YAML shape.
   *
   * For the post-compile flow (after `TrailblazeCompiler.compile()` has resolved per-target
   * tool closures), prefer the [InlineScriptToolConfig] overload below — its inputs are
   * already the runtime shape, no need to round-trip through author shape.
   *
   * Writes only if the rendered content differs from any existing output, so a daemon
   * restart that finds the same tool registry doesn't churn mtimes (and downstream tooling
   * — IDE TS servers, file watchers — doesn't churn cache).
   *
   * Returns the absolute path of the output file. The parent dir is created on demand.
   */
  fun generate(
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<PackScriptedToolFile>,
    outputFileName: String = GENERATED_FILE_NAME,
  ): Path {
    val entries = collectEntries(toolDescriptors, scriptedTools)
    return writeRendered(entries, outputFileName)
  }

  /**
   * Generate a per-target `.d.ts` from resolved [InlineScriptToolConfig] entries (the shape
   * `TrailblazeCompiler.compile()` emits, with `target.tools:` already inlined and JSON
   * Schema already shaped). This is the primary daemon-init / `trailblaze compile` wire-in
   * path.
   *
   * @param outputFileName typically `"client.<target-id>.d.ts"` so each target gets its own
   *   sliced binding. Defaults to [GENERATED_FILE_NAME] for callers that genuinely want the
   *   legacy single-file workspace-wide shape.
   */
  fun generateFromResolved(
    toolDescriptors: List<ToolDescriptor>,
    scriptedTools: List<InlineScriptToolConfig>,
    outputFileName: String = GENERATED_FILE_NAME,
  ): Path {
    val entries = collectEntriesFromResolved(toolDescriptors, scriptedTools)
    return writeRendered(entries, outputFileName)
  }

  /** Shared write path — idempotent (skip-write-if-content-matches). */
  private fun writeRendered(entries: List<ToolEntry>, outputFileName: String): Path {
    requireSafeFilename(outputFileName)
    val rendered = renderClientDts(entries)
    val outputPath = workspaceRoot
      .resolve(WORKSPACE_CONFIG_TOOLS_SUBDIR)
      .resolve(GENERATED_DIR_NAME)
      .resolve(outputFileName)
      .toAbsolutePath()
    Files.createDirectories(outputPath.parent)
    val existing = if (Files.isRegularFile(outputPath)) Files.readString(outputPath) else null
    if (existing != rendered) {
      Files.writeString(outputPath, rendered)
    }
    return outputPath
  }

  /**
   * Path-traversal guard. The `outputFileName` parameter flows from caller-supplied target
   * ids (`client.${target.id}.d.ts`), and target ids are user-authored in pack manifests.
   * Without this check, an id like `../../../etc/passwd` would let the generator write
   * outside `<workspaceRoot>/config/tools/.trailblaze/`.
   *
   * The accepted shape is the conservative subset that covers every real-world case:
   * letters / digits / underscores / hyphens / dots, with no leading dot. Hyphens and dots
   * are valid in both target ids (kebab-case) and the conventional `client.<id>.d.ts`
   * filename, so the regex permits them but rejects path separators (`/`, `\\`),
   * traversal sequences (`..`), and absolute-path indicators.
   */
  private fun requireSafeFilename(outputFileName: String) {
    require(outputFileName.isNotEmpty() && SAFE_FILENAME_PATTERN.matches(outputFileName)) {
      "Refusing to write generator output: filename '$outputFileName' is not a safe filename. " +
        "Expected a plain filename matching $SAFE_FILENAME_PATTERN (no path separators, no `..`, " +
        "no leading dot). This usually means a caller passed a tainted target id — sanitize at " +
        "the call site or constrain the upstream id contract."
    }
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
      byName.putIfAbsent(st.name, st.toEntry())
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

  private fun PackScriptedToolFile.toEntry(): ToolEntry = ToolEntry(
    name = name,
    description = description?.takeUnless { it.isBlank() },
    params = inputSchema.map { (key, prop) -> prop.toParam(key, owner = name) },
  )

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
    /** Subdirectory under the workspace root where Trailblaze authors keep their pack/tool config. */
    const val WORKSPACE_CONFIG_TOOLS_SUBDIR: String = "config/tools"

    /** Hidden subdirectory the generator writes into so a `.gitignore` can target the whole tree. */
    const val GENERATED_DIR_NAME: String = ".trailblaze"

    /** Output filename — `client.d.ts` so an author's tsconfig finds it via the standard glob. */
    const val GENERATED_FILE_NAME: String = "client.d.ts"

    /**
     * Plain-filename pattern enforced at write time to prevent path-traversal via the
     * caller-supplied `outputFileName` parameter. Letters, digits, underscores, hyphens,
     * and dots — with no leading dot, no path separators, no traversal sequences. Covers
     * the conventional `client.<id>.d.ts` shape and all real-world target ids.
     */
    val SAFE_FILENAME_PATTERN: Regex = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]*$")
  }
}

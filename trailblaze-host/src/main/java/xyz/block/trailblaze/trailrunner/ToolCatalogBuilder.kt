package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionException
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.buildToolDescriptorIgnoringSurface
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import java.io.File

object ToolSourceFiles {
  private val SKIP = setOf("build", "node_modules", ".gradle", ".git", ".idea", "dist")
  private const val TRAILMAPS_MARKER = "trails/config/trailmaps/"
  private const val TRAILMAPS_SUBDIR = "trailmaps"

  private const val MAX_INDEX_DEPTH = 25

  private val index: Map<String, File> by lazy { buildIndex(File(System.getProperty("user.dir"))) }

  fun sourceFor(fqn: String): String? = fileForClass(fqn)?.let { runCatching { it.readText() }.getOrNull() }

  // Kotlin tool sources are repo-resident (compiled onto the daemon classpath), so they always
  // resolve through the CWD/classpath index — the workspace overlay below never carries `.kt`.
  fun fileForClass(fqn: String): File? {
    if (!fqn.matches(Regex("[\\w.$]+"))) return null
    return index[fqn.replace('.', '/') + ".kt"]
  }

  // The active workspace's `trailmaps/` directory (`<workspace>/trails/config/trailmaps`), resolved
  // live through the same process-global hook the catalogs use. Null when no workspace resolves
  // (classpath-only), in which case resource lookups fall back to the CWD/classpath index below.
  private fun workspaceTrailmapsDir(): File? =
    WorkspaceConfigDirHolder.resolver()?.let { File(it, TRAILMAPS_SUBDIR) }?.takeIf { it.isDirectory }

  // Resolve [rel] under [base] with a canonical-containment check (reject `..` escapes), mirroring
  // the create/mkdir write paths. Returns the file only when it exists.
  private fun resolveContained(base: File, rel: String): File? {
    if (rel.isEmpty()) return null
    val f = File(base, rel)
    val baseCanon = runCatching { base.canonicalPath }.getOrNull() ?: return null
    val fCanon = runCatching { f.canonicalPath }.getOrNull() ?: return null
    if (fCanon != baseCanon && !fCanon.startsWith(baseCanon + File.separator)) return null
    return if (f.exists()) f else null
  }

  fun fileForResource(relPath: String): File? {
    val after = relPath.substringAfter(TRAILMAPS_MARKER, "")
    if (after.isEmpty()) return null
    // Prefer the active workspace so source open/reveal works for a workspace OUTSIDE the daemon's
    // launch dir. `after` is "<id>/<rest>"; resolve it under <workspace>/trailmaps with containment.
    workspaceTrailmapsDir()?.let { wsTrailmaps ->
      resolveContained(wsTrailmaps, after)?.let { return it }
    }
    // Fall back to the CWD/classpath index (the in-repo default, or when no workspace resolves).
    index[TRAILMAPS_MARKER + after]?.let { return it }
    // Last resort for files created after the lazy index was built: resolve via the trailmap base
    // dir + the remaining path, containment-checked the same way as the workspace branch above.
    val id = after.substringBefore('/')
    val rest = after.substringAfter('/', "")
    if (id.isNotEmpty() && rest.isNotEmpty()) {
      val base = trailmapBaseDir(id) ?: return null
      resolveContained(base, rest)?.let { return it }
    }
    return null
  }

  // Where a NOT-yet-existing trailmap would live in the active workspace — the create-target
  // counterpart to [trailmapBaseDir], which requires the directory to already exist. Returns the
  // (possibly absent) `<workspace>/trailmaps/<id>` dir, or null when no workspace resolves or the
  // id escapes the trailmaps root under the same canonical-containment check the read paths apply.
  // Callers own the mkdirs.
  fun newTrailmapBaseDir(id: String): File? {
    val wsTrailmaps = WorkspaceConfigDirHolder.resolver()?.let { File(it, TRAILMAPS_SUBDIR) } ?: return null
    val base = File(wsTrailmaps, id)
    val wsCanon = runCatching { wsTrailmaps.canonicalPath }.getOrNull() ?: return null
    val baseCanon = runCatching { base.canonicalPath }.getOrNull() ?: return null
    return base.takeIf { baseCanon.startsWith(wsCanon + File.separator) }
  }

  // The on-disk directory of a trailmap (e.g. <workspace>/trails/config/trailmaps/myapp). Used to
  // place newly-scaffolded files and to locate a trailmap's `tools/` dir for scripted-tool analysis.
  fun trailmapBaseDir(id: String): File? {
    // Prefer the active workspace's trailmaps dir; containment-check the single-segment id so a
    // crafted `..` can't walk out of the trailmaps root.
    workspaceTrailmapsDir()?.let { wsTrailmaps ->
      val base = File(wsTrailmaps, id)
      val wsCanon = runCatching { wsTrailmaps.canonicalPath }.getOrNull()
      val baseCanon = runCatching { base.canonicalPath }.getOrNull()
      if (wsCanon != null && baseCanon != null &&
        baseCanon.startsWith(wsCanon + File.separator) && base.isDirectory
      ) {
        return base
      }
    }
    // Fall back to the CWD/classpath index, derived from any already-indexed file under the trailmap.
    val prefix = "$TRAILMAPS_MARKER$id/"
    val entry = index.entries.firstOrNull { it.key.startsWith(prefix) } ?: return null
    val p = entry.value.absolutePath.replace(File.separatorChar, '/')
    val needle = "/$TRAILMAPS_MARKER$id"
    val idx = p.indexOf(needle)
    if (idx < 0) return null
    return File(p.substring(0, idx + needle.length))
  }

  internal fun buildIndex(root: File): Map<String, File> {
    val out = HashMap<String, File>()
    val visited = HashSet<String>()
    val stack = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
    while (stack.isNotEmpty()) {
      val (dir, depth) = stack.removeLast()
      if (depth > MAX_INDEX_DEPTH) continue
      if (!visited.add(runCatching { dir.canonicalPath }.getOrDefault(dir.path))) continue
      val entries = dir.listFiles() ?: continue
      for (e in entries) {
        if (e.isDirectory) {
          if (e.name !in SKIP && !e.name.startsWith(".")) stack.add(e to depth + 1)
          continue
        }
        val p = e.path.replace(File.separatorChar, '/')
        when {
          e.name.endsWith(".kt") -> {
            val key = listOf("/kotlin/", "/java/").firstNotNullOfOrNull { m ->
              p.lastIndexOf(m).takeIf { it >= 0 }?.let { p.substring(it + m.length) }
            }
            if (key != null) out.putIfAbsent(key, e)
          }
          p.contains("/$TRAILMAPS_MARKER") -> {
            out.putIfAbsent(TRAILMAPS_MARKER + p.substringAfterLast("/$TRAILMAPS_MARKER"), e)
          }
        }
      }
    }
    return out
  }
}

object ToolCatalogBuilder {

  suspend fun build(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): List<ToolCatalogEntry> {
    val out = mutableListOf<ToolCatalogEntry>()
    out += discoverYamlTools(resourceSource)
    out += discoverScriptedTools(resourceSource)
    return out.sortedWith(compareBy({ it.trailmap }, { it.id }))
  }

  private fun discoverYamlTools(resourceSource: ConfigResourceSource): List<ToolCatalogEntry> {
    val matches = runCatching {
      resourceSource.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".tool.yaml")
    }.getOrElse {
      Console.log("[ToolCatalogBuilder] tool YAML discovery failed: ${it.message}")
      emptyMap()
    }
    return matches.mapNotNull { (relPath, content) ->
      val segments = relPath.split('/')
      if (segments.size < 3 || segments[1] != "tools") return@mapNotNull null
      val trailmap = segments[0]
      val sourcePath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$relPath"
      val cfg = runCatching {
        TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), content)
      }.getOrNull()
      if (cfg == null) {
        // An unparseable .tool.yaml is a real authoring bug, not a tool kind. Log loudly and DROP
        // it (rather than surfacing a bogus "unknown" catalog entry) so the UI never lists a tool
        // that doesn't actually work; the log names the offending file so it can be fixed.
        Console.log("[ToolCatalogBuilder] could not parse tool YAML, dropping: $relPath")
        return@mapNotNull null
      }
      val isKotlin = cfg.toolClass != null
      // For Kotlin-backed tools, read params + the LLM-facing description from the actual
      // parsed tool descriptor (reflection over the @TrailblazeToolClass / @LLMDescription
      // annotations), not by regex-parsing the .kt source — the descriptor is the same
      // representation the framework resolves at runtime.
      val kotlinDescriptor = if (isKotlin) descriptorForClass(cfg.toolClass!!) else null
      ToolCatalogEntry(
        id = cfg.id,
        flavor = if (isKotlin) ToolFlavor.KOTLIN else ToolFlavor.YAML,
        trailmap = trailmap,
        sourcePath = sourcePath,
        description = cfg.description,
        className = cfg.toolClass,
        source = content,
        parameters = cfg.parameters.map {
          ToolParamDto(
            name = it.name,
            type = it.type,
            required = it.required,
            description = it.description,
          )
        }.ifEmpty { kotlinDescriptor?.let(::paramsFromDescriptor) ?: emptyList() },
        llmDescription = if (isKotlin) (kotlinDescriptor?.description ?: cfg.description) else cfg.description,
      )
    }
  }

  /** Public param schema for a Kotlin tool by FQN; empty when the class isn't loadable. */
  fun paramsForToolClass(fqn: String): List<ToolParamDto> =
    descriptorForClass(fqn)?.let(::paramsFromDescriptor) ?: emptyList()

  // A scripted tool dispatches a sibling tool via `ctx.tools.<id>(...)`; a Kotlin orchestrator does
  // it via `invokeFrameworkTool(toolName = "<id>")` (or positionally). These two patterns are the
  // tool->tool composition edges the trail-usage count can't see — a helper that's only ever reached
  // through another tool has 0 trail uses yet is load-bearing.
  // The `ctx.tools.<id>` match requires a trailing `(` so a bare property reference (or a value read)
  // isn't mistaken for a dispatch; comments/docstrings are stripped first (see [stripCommentsForScan])
  // so a `ctx.tools.foo(...)` mentioned in a `//` comment doesn't inflate `foo`'s caller count.
  private val CTX_TOOLS_RX = Regex("""ctx\.tools\.([A-Za-z0-9_]+)\s*\(""")
  private val INVOKE_FRAMEWORK_RX = Regex("""invokeFrameworkTool\s*\(\s*(?:toolName\s*=\s*)?"([A-Za-z0-9_]+)"""")

  // Blank out `/* … */` and `// …` comments before scanning so a commented/docstring mention of a
  // dispatch isn't counted as a real edge. Not a full lexer (a `//` inside a string literal is also
  // blanked), but the extractor only looks for dispatch call-sites, so over-blanking a string is
  // harmless — it could only ever DROP a phantom edge, never invent one.
  private val BLOCK_COMMENT_RX = Regex("""/\*[\s\S]*?\*/""")
  private val LINE_COMMENT_RX = Regex("""(?m)//.*$""")

  private fun stripCommentsForScan(source: String): String =
    LINE_COMMENT_RX.replace(BLOCK_COMMENT_RX.replace(source, " "), " ")

  /**
   * Tool ids a tool's own implementation dispatches to, derived purely from its source — no file IO,
   * so it's unit-testable. Scripted tools expose edges via `ctx.tools.*`; Kotlin tools via
   * `invokeFrameworkTool`. YAML-declarative tools wrap framework primitives (maestro), not other
   * catalog tools, so they contribute no edges here. Comments are stripped first so a doc/comment
   * reference doesn't create a phantom edge. The tool's own id is dropped (no self-edge).
   */
  internal fun referencedToolIdsIn(flavor: ToolFlavor, source: String, selfId: String): Set<String> {
    val scannable = stripCommentsForScan(source)
    return when (flavor) {
      ToolFlavor.SCRIPTED -> CTX_TOOLS_RX.findAll(scannable).map { it.groupValues[1] }
      ToolFlavor.KOTLIN -> INVOKE_FRAMEWORK_RX.findAll(scannable).map { it.groupValues[1] }
      ToolFlavor.YAML -> emptySequence()
    }.toSet() - selfId
  }

  /**
   * Tool ids [entry] dispatches to. Resolves the implementation source: a scripted tool already
   * carries its `.ts` in [ToolCatalogEntry.source]; a Kotlin tool's `.kt` is read from disk by FQN.
   * Empty when the source can't be resolved.
   */
  fun referencedToolIds(entry: ToolCatalogEntry): Set<String> {
    val source = when (entry.flavor) {
      ToolFlavor.SCRIPTED -> entry.source
      ToolFlavor.KOTLIN -> entry.className?.let { ToolSourceFiles.sourceFor(it) }
      ToolFlavor.YAML -> null
    } ?: return emptySet()
    return referencedToolIdsIn(entry.flavor, source, entry.id)
  }

  /**
   * Caller edges from Kotlin tools that are REGISTERED (via a toolset / trailhead) but ship NO
   * `.tool.yaml` descriptor — so [build] doesn't surface them as catalog entries, yet they still
   * dispatch other tools via `invokeFrameworkTool` (e.g. the `myapp_android_signInViaUI` orchestrator
   * dispatches `myapp_android_enterCredentials` / `myapp_android_waitForHome`). Without this, those
   * dispatched helpers show an empty "used by other tools" and a 0 tool-count even though they're
   * load-bearing.
   *
   * Returns caller-id -> referenced tool ids, for callers NOT in [excludeIds] (the catalog ids,
   * already covered by [referencedToolIds] over [build]). Enumerated from the toolset catalog's
   * registered classes; a class whose `.kt` source can't be resolved, or that dispatches nothing,
   * contributes nothing. Best-effort: any failure yields an empty map rather than failing the route.
   */
  fun registeredKotlinCallerEdges(excludeIds: Set<String>): Map<String, Set<String>> =
    runCatching {
      val registered = TrailblazeToolSetCatalog.defaultEntries()
        .asSequence()
        .flatMap { it.toolClasses.asSequence() }
        .distinct()
        .mapNotNull { kClass ->
          val id = runCatching { kClass.toolName().toolName }.getOrNull() ?: return@mapNotNull null
          val fqn = kClass.qualifiedName ?: return@mapNotNull null
          id to fqn
        }
        .toList()
      registeredKotlinCallerEdgesFrom(registered, excludeIds, ToolSourceFiles::sourceFor)
    }.getOrElse {
      Console.log("[ToolCatalogBuilder] registered-Kotlin caller-edge scan failed: ${it.message}")
      emptyMap()
    }

  /**
   * Pure core of [registeredKotlinCallerEdges] — the side effects (catalog enumeration, `.kt` source
   * read) are injected so the exclude/empty-edge/composition logic is unit-testable without the live
   * toolset catalog or filesystem. [registered] is (toolId, fqn) for every registered Kotlin tool;
   * [sourceFor] resolves a fqn to its Kotlin source (or null). Callers in [excludeIds] and callers
   * that dispatch nothing are dropped.
   */
  internal fun registeredKotlinCallerEdgesFrom(
    registered: List<Pair<String, String>>,
    excludeIds: Set<String>,
    sourceFor: (String) -> String?,
  ): Map<String, Set<String>> =
    registered.mapNotNull { (id, fqn) ->
      if (id in excludeIds) return@mapNotNull null
      val edges = sourceFor(fqn)?.let { referencedToolIdsIn(ToolFlavor.KOTLIN, it, id) } ?: return@mapNotNull null
      edges.ifEmpty { null }?.let { id to it }
    }.toMap()

  private val scriptedAnalyzer: ScriptedToolDefinitionAnalyzer? by lazy {
    runCatching {
      val bun = BunBinaryResolver.resolveBunBinary() ?: return@runCatching null
      val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir() ?: return@runCatching null
      val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir) ?: return@runCatching null
      if (!ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(sdkDir)) return@runCatching null
      ScriptedToolDefinitionAnalyzer(bunBinary = bun, extractorShim = shim, sdkDir = sdkDir)
    }.getOrNull()
  }
  /**
   * Analyzer-extracted params for every tool in [toolsDir], keyed by tool name.
   *
   * No JVM-lifetime cache here: delegates straight to [ScriptedToolDefinitionAnalyzer.analyze], whose
   * own cache is keyed by file CONTENT hash (cheap hash-and-lookup on a hit, no bun subprocess), so an
   * edited `.ts` file gets fresh params on the very next call instead of a stale directory-keyed result.
   *
   * Checks [toolsDir] BEFORE resolving [scriptedAnalyzer] so a trailmap with no scripted tools (or no
   * resolvable directory at all — a classpath-bundled trailmap) never triggers the analyzer's one-time
   * environment probe (bun/SDK/shim resolution).
   *
   * Best-effort on a per-tool analyzer error: [ScriptedToolDefinitionException.partialTools] is used
   * when present, so one unsupported construct in an unrelated `.ts` file doesn't blank out param
   * completion for every OTHER tool in the same trailmap. [ScriptedToolDefinitionAnalyzer]'s own kdoc
   * documents this as the canonical "best-effort" call pattern.
   */
  private suspend fun scriptedParamsByToolName(toolsDir: File): Map<String, List<ToolParamDto>> {
    if (!toolsDir.isDirectory) return emptyMap()
    val analyzer = scriptedAnalyzer ?: return emptyMap()
    val defs = try {
      analyzer.analyze(toolsDir)
    } catch (e: ScriptedToolDefinitionException) {
      // Name each broken file so an operator can act on the log alone, not just a count.
      val brokenFiles = e.errors.joinToString { "${it.file} (${it.toolName}): ${it.message}" }
      Console.log(
        "[ToolCatalogBuilder] scripted param analyze had ${e.errors.size} error(s) under " +
          "${toolsDir.absolutePath}; using ${e.partialTools.size} cleanly-extracted tool(s). $brokenFiles",
      )
      e.partialTools
    } catch (e: Exception) {
      Console.log("[ToolCatalogBuilder] scripted param analyze failed under ${toolsDir.absolutePath}: ${e.message}")
      emptyList()
    }
    return defs.associate { it.name to paramsFromInputSchema(it.inputSchemaObject) }
  }

  /** Analyzer-extracted params for a single scripted (`.ts`) tool. Empty when the trailmap, its `tools/`
   * directory, or the tool itself isn't found — see [scriptedParamsByToolName] for the caching design. */
  suspend fun scriptedToolParams(trailmap: String, toolId: String): List<ToolParamDto> {
    val base = ToolSourceFiles.trailmapBaseDir(trailmap) ?: return emptyList()
    return scriptedParamsByToolName(File(base, "tools"))[toolId] ?: emptyList()
  }

  private fun paramsFromInputSchema(schema: JsonObject): List<ToolParamDto> {
    val props = (schema["properties"] as? JsonObject) ?: return emptyList()
    val defs = schema["definitions"] as? JsonObject
    val required = (schema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
    return props.flatMap { (name, el) ->
      val site = el as? JsonObject
      val o = resolveLocalRef(site, defs)
      unionParams(name, o, isRequired = name in required)
        ?: listOf(
          ToolParamDto(
            name = name,
            type = (o?.get("type") as? JsonPrimitive)?.contentOrNull ?: "any",
            required = name in required,
            description = ((site?.get("description") ?: o?.get("description")) as? JsonPrimitive)?.contentOrNull,
            validValues = enumValues(o),
          ),
        )
    }
  }

  // ts-json-schema-generator emits named nested types as `$ref: "#/definitions/X"` (only the ROOT
  // type is inlined via topRef:false); the union lowering below needs the resolved shape.
  private fun resolveLocalRef(o: JsonObject?, defs: JsonObject?): JsonObject? {
    val ref = (o?.get("\$ref") as? JsonPrimitive)?.contentOrNull ?: return o
    return (defs?.get(ref.substringAfterLast('/')) as? JsonObject) ?: o
  }

  private fun enumValues(o: JsonObject?): List<String>? =
    (o?.get("enum") as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.takeIf { it.isNotEmpty() }

  /**
   * A discriminated-union property (`anyOf` of object variants, each carrying a `type` const)
   * flattens into dotted params: one `<name>.type` discriminator (dropdown over the variant
   * consts) plus each variant field as `<name>.<field>` gated by [ToolParamDto.visibleWhen] -
   * the same lowering the MCP tool-discovery surface applies, so the web picker and the desktop
   * picker agree on shape and the dotted names group back into nested YAML. Null when the
   * property isn't that shape (callers fall through to the flat rendering).
   */
  private fun unionParams(parent: String, o: JsonObject?, isRequired: Boolean): List<ToolParamDto>? {
    val anyOf = (o?.get("anyOf") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
    if (anyOf.isEmpty()) return null
    data class Variant(val const: String, val desc: String?, val props: JsonObject, val required: Set<String>)
    val variants = anyOf.map { v ->
      val props = v["properties"] as? JsonObject ?: return null
      val t = props["type"] as? JsonObject ?: return null
      val const = (t["const"] as? JsonPrimitive)?.contentOrNull ?: return null
      Variant(
        const = const,
        desc = (t["description"] as? JsonPrimitive)?.contentOrNull,
        props = props,
        required = (v["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
      )
    }
    val discriminatorName = "$parent.type"
    val out = mutableListOf(
      ToolParamDto(
        name = discriminatorName,
        type = "string",
        required = isRequired,
        description = variants.mapNotNull { v -> v.desc?.let { "${v.const}: $it" } }
          .joinToString(" ")
          .ifBlank { null },
        validValues = variants.map { it.const },
        validValueDescriptions = variants.map { it.desc.orEmpty() }.takeIf { l -> l.any { it.isNotBlank() } },
      ),
    )
    // One flattened entry per distinct field name, accumulating every variant it appears in.
    // `required` means "required while visible": true only when every variant that shows the
    // field also requires it.
    class Field(val dto: ToolParamDto, val forValues: MutableList<String> = mutableListOf(), var requiredWhileVisible: Boolean = true)
    val fields = LinkedHashMap<String, Field>()
    variants.forEach { v ->
      v.props.forEach { (fname, fel) ->
        if (fname == "type") return@forEach
        val fo = fel as? JsonObject
        val field = fields.getOrPut(fname) {
          Field(
            ToolParamDto(
              name = "$parent.$fname",
              type = (fo?.get("type") as? JsonPrimitive)?.contentOrNull ?: "any",
              description = (fo?.get("description") as? JsonPrimitive)?.contentOrNull,
              validValues = enumValues(fo),
            ),
          )
        }
        field.forValues += v.const
        if (fname !in v.required) field.requiredWhileVisible = false
      }
    }
    fields.values.forEach { f ->
      out += f.dto.copy(
        required = f.requiredWhileVisible,
        visibleWhen = ToolParamVisibilityDto(parameterName = discriminatorName, values = f.forValues.distinct()),
      )
    }
    return out
  }

  /**
   * Builds the parsed tool descriptor for a Kotlin tool by its fully-qualified class name,
   * reflecting over the live class on the daemon classpath. Returns null (and logs) when the
   * class isn't loadable or can't be lowered to a descriptor (e.g. it's missing the required
   * class-level `@LLMDescription`); callers fall back to the YAML-declared metadata.
   */
  private fun descriptorForClass(fqn: String): TrailblazeToolDescriptor? = runCatching {
    @Suppress("UNCHECKED_CAST")
    val kClass = Class.forName(fqn).kotlin as kotlin.reflect.KClass<out TrailblazeTool>
    kClass.buildToolDescriptorIgnoringSurface().toTrailblazeToolDescriptor()
  }.getOrElse {
    Console.log("[ToolCatalogBuilder] could not build descriptor for $fqn: ${it.message}")
    null
  }

  private fun paramsFromDescriptor(d: TrailblazeToolDescriptor): List<ToolParamDto> =
    d.requiredParameters.map { it.toParamDto(required = true) } +
      d.optionalParameters.map { it.toParamDto(required = false) }

  private fun TrailblazeToolParameterDescriptor.toParamDto(required: Boolean): ToolParamDto = ToolParamDto(
    name = name,
    type = type.toDisplayType(),
    required = required,
    description = description,
    validValues = validValues,
    visibleWhen = visibleWhen?.let { ToolParamVisibilityDto(it.parameterName, it.values) },
  )

  // Koog descriptor types come through uppercased (STRING, ARRAY, INT…); lowercase them so
  // Kotlin-tool params read the same as the lowercase types YAML-defined tools already show.
  private fun String.toDisplayType(): String = lowercase()

  // Param enrichment is grouped by trailmap (one [scriptedParamsByToolName] call per DISTINCT
  // trailmap, i.e. one analyzer pass over its whole `tools/` dir) rather than looked up per tool: a
  // real trailmap can have 50+ scripted tools, and calling the per-tool [scriptedToolParams] in this
  // loop would re-hash every `.ts` file in the directory once per tool sharing it (an N-tools ×
  // N-files pass) instead of once for the whole directory. It's free for a trailmap with no
  // resolvable workspace `tools/` directory (a classpath-BUNDLED trailmap, packaged into the JAR with
  // no on-disk source at runtime): `trailmapBaseDir` returns null and those entries simply keep
  // today's behavior (an open arg object, no param completion).
  private suspend fun discoverScriptedTools(resourceSource: ConfigResourceSource): List<ToolCatalogEntry> {
    val matches = runCatching {
      resourceSource.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".ts")
    }.getOrElse {
      Console.log("[ToolCatalogBuilder] scripted tool discovery failed: ${it.message}")
      emptyMap()
    }
    val entries = matches.mapNotNull { (relPath, content) ->
      val segments = relPath.split('/')
      if (segments.size < 3 || segments[1] != "tools") return@mapNotNull null
      val fileName = segments.last()
      if (fileName.endsWith(".d.ts") || fileName.endsWith(".test.ts")) return@mapNotNull null
      val stem = fileName.removeSuffix(".ts")
      if (fileName.startsWith("_") || stem.endsWith("_shared") || stem == "tools") return@mapNotNull null
      val declaresTool = content.contains("trailblaze.tool") ||
        content.contains("function $stem") ||
        content.contains("const $stem")
      if (!declaresTool) return@mapNotNull null
      ToolCatalogEntry(
        id = stem,
        flavor = ToolFlavor.SCRIPTED,
        trailmap = segments[0],
        sourcePath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$relPath",
        description = extractLeadingDoc(content),
        source = content,
      )
    }
    // Group by trailmap, then one scriptedParamsByToolName call per distinct trailmap (see kdoc above).
    val distinctTrailmaps = entries.map { it.trailmap }.distinct()
    val paramsByTrailmap = distinctTrailmaps.associateWith { trailmap ->
      ToolSourceFiles.trailmapBaseDir(trailmap)?.let { scriptedParamsByToolName(File(it, "tools")) }.orEmpty()
    }
    return entries.map { entry ->
      val trailmapParams = paramsByTrailmap[entry.trailmap]
      // A file whose filename stem doesn't match any analyzer-extracted tool name passes discovery
      // above (which also accepts a generic `trailblaze.tool` content match) but has no entry here
      // under its filename-derived catalog id — silently no params rather than a loud error. Two known
      // causes, not distinguished here: a `.ts` file that declares MULTIPLE tools under other names
      // (e.g. `myapp_card_reader.ts` exports 11 differently-named tools — this catalog's one-entry-
      // per-file id convention doesn't fit that shape) or a single tool whose `export const` name
      // genuinely differs from its filename. Logged once so either case is discoverable instead of
      // just "completion doesn't work for this one entry."
      if (trailmapParams != null && trailmapParams.isNotEmpty() && entry.id !in trailmapParams) {
        Console.log(
          "[ToolCatalogBuilder] '${entry.sourcePath}' declares a tool but no extracted tool in " +
            "'${entry.trailmap}' is named '${entry.id}' (its filename) — likely multiple tools declared " +
            "in this file under other names, or an `export const` name differing from the filename. " +
            "This catalog entry will show no param completion.",
        )
      }
      entry.copy(parameters = trailmapParams?.get(entry.id) ?: emptyList())
    }
  }

  private fun extractLeadingDoc(ts: String): String? {
    val start = ts.indexOf("/**")
    if (start < 0 || start > 400) return null
    val end = ts.indexOf("*/", start)
    if (end < 0) return null
    val body = ts.substring(start + 3, end)
      .lines()
      .joinToString(" ") { it.trim().removePrefix("*").trim() }
      .trim()
      .replace(Regex("\\s+"), " ")
    if (body.isBlank()) return null
    val firstSentence = body.substringBefore(". ").trim()
    return (if (firstSentence.length in 1..280) firstSentence else body.take(280)).ifBlank { null }
  }
}

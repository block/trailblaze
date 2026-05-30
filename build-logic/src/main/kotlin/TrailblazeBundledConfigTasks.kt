import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.block.trailblaze.bundle.yaml.YamlEmitter

/**
 * Minimal typed shape for the trailmap-manifest fields the generator consumes. Mirrors a subset of
 * the runtime [xyz.block.trailblaze.config.project.TrailmapTargetConfig] schema so this build-logic
 * task can read system-prompt configuration through the same field names as the runtime, via
 * kaml's typed decode, instead of looking them up as inline string keys on a generic
 * `Map<String, Any?>`.
 *
 * Only the fields needed for build-time resolution are captured here — kaml ignores the rest
 * because [Yaml.configuration]'s `strictMode` is false in this task. The rest of the generator
 * keeps walking the trailmap manifest as a generic Map so unknown / future fields still flow through
 * to the emitted target YAML untouched.
 *
 * Build-logic intentionally does NOT depend on `:trailblaze-models` (avoids inflating the
 * Gradle classpath with the multiplatform graph), so this shape is duplicated here. Keep the
 * `@SerialName` values aligned with `TrailmapTargetConfig` — same drift contract as
 * `mergeInheritedDefaults` keeps with `TrailmapDependencyResolver`.
 */
@Serializable
private data class TrailmapManifestPromptShape(
  val target: TrailmapTargetPromptShape? = null,
) {
  @Serializable
  data class TrailmapTargetPromptShape(
    @SerialName("system_prompt_file") val systemPromptFile: String? = null,
  )
}

// Subset of `TrailmapScriptedToolFile` (in :trailblaze-models) sufficient for the generator to
// resolve trailmap-relative `target.tools:` path refs into the full `InlineScriptToolConfig`
// shape that `AppTargetYamlConfig.tools` expects at runtime. Mirrors the same fields the
// canonical `TrailmapScriptedToolFile.toInlineScriptToolConfig()` reads — keep aligned with
// `:trailblaze-models`'s shape when fields move (same drift contract as the
// `mergeInheritedDefaults` ↔ `TrailmapDependencyResolver` parity note above).
//
// Build-logic intentionally doesn't depend on `:trailblaze-models`, so the descriptor is
// parsed via kaml's tree API rather than typed decode — kaml's typed-decode path doesn't
// bridge to `kotlinx.serialization.json.JsonObject` (the type the runtime `_meta` uses),
// and rolling a kaml-specific @Contextual serializer just to capture `_meta` is more
// invasive than walking the YamlMap directly.

abstract class TrailblazeBundledConfigExtension @Inject constructor(objects: ObjectFactory) {
  val trailmapsDir: DirectoryProperty = objects.directoryProperty()
  val targetsDir: DirectoryProperty = objects.directoryProperty()
  /**
   * Anchor that scripted-tool `script:` paths in the generated target.yaml are emitted relative
   * to. The runtime `InlineScriptToolServerSynthesizer` resolves these paths against JVM CWD via
   * `McpSubprocessSpawner.resolveScriptPath`, so this anchor should be the directory the user
   * invokes `./trailblaze` from — typically the repo root. Set via
   * `bundledTrailblazeConfig { scriptRootDir.set(rootProject.layout.projectDirectory) }`.
   *
   * When unset, the generator falls through to emitting the descriptor's own `script:` value
   * unchanged — preserving the legacy behavior for callers that don't yet have classpath-bundled
   * scripted tools to worry about.
   */
  val scriptRootDir: DirectoryProperty = objects.directoryProperty()
  val regenerateCommand: Property<String> = objects.property(String::class.java)
}

abstract class GenerateBundledTrailblazeConfigTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val trailmapsDir: DirectoryProperty

  @get:OutputDirectory
  abstract val targetsDir: DirectoryProperty

  /**
   * Marked `@Internal` rather than `@InputDirectory`: this is a path-computation anchor
   * (typically `rootProject.layout.projectDirectory`), NOT a content input. Treating it as
   * an input would have Gradle hash every file under the repo root for up-to-date checks,
   * and the resulting task graph would require explicit dependencies on every sibling-module
   * compilation. The script content itself IS tracked — via [trailmapsDir], where the .js files
   * live and where any author edit invalidates this task.
   */
  @get:org.gradle.api.tasks.Internal
  abstract val scriptRootDir: DirectoryProperty

  @get:Input
  abstract val regenerateCommand: Property<String>

  @TaskAction
  fun generate() {
    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir.asFile.get(),
      targetsDir = targetsDir.asFile.get(),
      scriptRootDir = scriptRootDir.orNull?.asFile,
      regenerateCommand = regenerateCommand.get(),
    )
    val expected = generator.buildExpectedTargets()
    generator.deleteStaleGeneratedTargets(expected.keys)
    expected.forEach { (targetFile, content) ->
      targetFile.parentFile.mkdirs()
      if (!targetFile.exists() || targetFile.readText() != content) {
        targetFile.writeText(content)
      }
    }
  }
}

abstract class VerifyBundledTrailblazeConfigTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val trailmapsDir: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val targetsDir: DirectoryProperty

  /**
   * Marked `@Internal` rather than `@InputDirectory`: this is a path-computation anchor
   * (typically `rootProject.layout.projectDirectory`), NOT a content input. Treating it as
   * an input would have Gradle hash every file under the repo root for up-to-date checks,
   * and the resulting task graph would require explicit dependencies on every sibling-module
   * compilation. The script content itself IS tracked — via [trailmapsDir], where the .js files
   * live and where any author edit invalidates this task.
   */
  @get:org.gradle.api.tasks.Internal
  abstract val scriptRootDir: DirectoryProperty

  @get:Input
  abstract val regenerateCommand: Property<String>

  @TaskAction
  fun verify() {
    val generator = TrailmapTargetGenerator(
      trailmapsDir = trailmapsDir.asFile.get(),
      targetsDir = targetsDir.asFile.get(),
      scriptRootDir = scriptRootDir.orNull?.asFile,
      regenerateCommand = regenerateCommand.get(),
    )
    val expected = generator.buildExpectedTargets()
    val staleFiles = mutableListOf<String>()

    expected.forEach { (targetFile, content) ->
      when {
        !targetFile.exists() ->
          staleFiles += "Missing generated target: ${targetFile.relativeTo(project.projectDir).invariantSeparatorsPath}"
        targetFile.readText() != content ->
          staleFiles += "Out-of-date generated target: ${targetFile.relativeTo(project.projectDir).invariantSeparatorsPath}"
      }
    }

    generator.findManagedTargetFiles()
      .filter { it !in expected.keys }
      .forEach { stale ->
        staleFiles += "Stale generated target with no trailmap source: ${stale.relativeTo(project.projectDir).invariantSeparatorsPath}"
      }

    if (staleFiles.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Bundled Trailblaze config is out of date:")
          staleFiles.forEach { appendLine(" - $it") }
          append("Regenerate with: ${regenerateCommand.get()}")
        },
      )
    }
  }
}

// Generates flat target YAMLs from authored trailmap manifests for the bundled-config plugin.
//
// **YAML library: kaml.** SnakeYAML was the original choice but Trailblaze code is
// standardizing on kaml across the codebase. The generic-Map walker pattern below uses
// kaml's tree API (`YamlNode` / `YamlMap` / `YamlList` / `YamlScalar`) since trailmap manifests
// are walked structurally rather than decoded into typed `@Serializable` classes — the
// generator preserves arbitrary fields when re-emitting target YAMLs, so a typed schema
// would either drop or hard-code unknown keys.
//
// **Custom YAML rendering** (rather than kaml's encode-to-string). The output format here
// matches the human-authored YAML convention used elsewhere in the repo: 2-space map
// indent, with list indicators ("-") sitting 2 spaces deeper than their parent key.
// kaml's `YamlConfiguration` doesn't expose the indicator-indent knob the way SnakeYAML's
// `DumperOptions` did, and even SnakeYAML couldn't actually produce this exact layout
// (it required indicatorIndent < indent, forcing the map indent up to 4). The hand-rolled
// renderer below preserves the existing format byte-for-byte. kaml is used only for
// *parsing* trailmap manifests + emitting properly-quoted scalars on the dump path.
internal class TrailmapTargetGenerator(
  private val trailmapsDir: File,
  private val targetsDir: File,
  private val regenerateCommand: String,
  private val scriptRootDir: File? = null,
) {
  /** Used for parsing trailmap manifests — strict mode off so unknown keys flow through. */
  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
  )

  fun buildExpectedTargets(): Map<File, String> {
    val expected = linkedMapOf<File, String>()
    val seenTargetIds = mutableSetOf<String>()

    // Pre-pass: load every discoverable trailmap and index by id so dep walks below can
    // look up `defaults:` from sibling trailmaps. Build-logic intentionally does NOT depend
    // on `:trailblaze-common` (avoids inflating every Gradle build's classpath with the
    // koog/MCP graph), so the canonical [TrailmapDependencyResolver] isn't reachable here.
    // This generator implements the same closest-wins inheritance against trailmap manifests
    // it walks itself; the equivalence test in `:trailblaze-common`'s compile-test suite
    // is the drift guard between this build-time mini-resolver and the runtime resolver.
    val trailmapsById = linkedMapOf<String, TrailmapInfo>()
    discoverTrailmapFiles().forEach { trailmapFile ->
      val trailmap = loadMap(trailmapFile)
      val trailmapId = trailmap.requiredString("id", trailmapFile)
      check(trailmapsById.put(trailmapId, TrailmapInfo(trailmapFile, trailmap)) == null) {
        "Duplicate trailmap id '$trailmapId' across trailmap manifests under ${trailmapsDir.absolutePath} " +
          "(also at ${trailmapsById[trailmapId]?.trailmapFile?.relativeTo(trailmapsDir)})"
      }
    }

    trailmapsById.values.forEach { (trailmapFile, trailmap) ->
      val trailmapId = trailmap.requiredString("id", trailmapFile)
      val target = trailmap["target"] as? Map<*, *> ?: return@forEach
      val normalizedTarget = resolveSystemPromptFile(normalizeMap(target), trailmapFile)
      // If `target.id` is set, it must be a string. Silently falling back to the trailmap
      // id when the author wrote `id: 123` (or anything non-string) would lose their
      // intent without warning — better to fail loudly so the misuse is fixable at
      // edit time rather than appearing as drift in a later regen.
      val explicitTargetId = normalizedTarget["id"]
      if (explicitTargetId != null && explicitTargetId !is String) {
        throw GradleException(
          "Trailmap manifest ${trailmapFile.absolutePath} has 'target.id' of type " +
            "${explicitTargetId::class.simpleName} ($explicitTargetId). target.id must " +
            "be a string. Remove the field to default to the trailmap id ('$trailmapId').",
        )
      }
      val targetId = (explicitTargetId as? String) ?: trailmapId
      check(seenTargetIds.add(targetId)) {
        "Duplicate generated target id '$targetId' from ${trailmapFile.relativeTo(trailmapsDir)}"
      }

      val ownDeps = (trailmap["dependencies"] as? List<*>)
        ?.mapIndexed { index, value ->
          value as? String ?: throw GradleException(
            "Trailmap manifest ${trailmapFile.absolutePath} has non-string dependency at index $index ($value)",
          )
        }
        .orEmpty()
      val resolvedTarget = mergeInheritedDefaults(
        ownTarget = normalizedTarget,
        ownDeps = ownDeps,
        ownTrailmapId = trailmapId,
        ownTrailmapFile = trailmapFile,
        trailmapsById = trailmapsById,
      )

      val orderedTarget = linkedMapOf<String, Any?>("id" to targetId)
      resolvedTarget.forEach { (key, value) ->
        if (key == "id") return@forEach
        // `tools:` in trailmap manifests holds path refs (e.g. `tools/myTool.yaml`) pointing at
        // `TrailmapScriptedToolFile` descriptors with flat `inputSchema`. `AppTargetYamlConfig.tools`
        // expects `List<InlineScriptToolConfig>` (with JSON-Schema `inputSchema`), so we have
        // to resolve each path ref here at build time — mirrors what `TrailblazeProjectConfigLoader.
        // resolveTrailmapSiblings` + `TrailmapScriptedToolFile.toInlineScriptToolConfig` do at runtime
        // when loading trailmap.yaml. The runtime daemon discovers targets from the generated
        // target.yaml (via `AppTargetYamlLoader.discoverAndLoadAll`), so without resolution
        // here `targetTestApp.getInlineScriptTools()` would return empty and scripted-tool
        // dispatch would fall through to `OtherTrailblazeTool` at trail-run time.
        if (key == "tools") {
          val resolved = resolveScriptedToolList(value, trailmapFile)
          if (resolved.isNotEmpty()) orderedTarget["tools"] = resolved
          return@forEach
        }
        orderedTarget[key] = value
      }

      val sourceRoot = trailmapsDir.parentFile?.parentFile ?: trailmapsDir.parentFile ?: trailmapsDir
      val sourcePath = trailmapFile.invariantPathRelativeTo(sourceRoot)
      val rendered = buildString {
        appendLine("# GENERATED FILE. DO NOT EDIT.")
        appendLine("# Source: $sourcePath")
        appendLine("# Regenerate with: $regenerateCommand")
        appendLine()
        append(YamlEmitter.renderMap(orderedTarget))
        appendLine()
      }
      expected[File(targetsDir, "$targetId.yaml")] = rendered
    }

    return expected
  }

  /**
   * Walks [ownDeps] (depth-first, declaration-order) collecting per-platform per-field
   * default contributions, then fills in any field the consumer didn't set on a platform
   * it explicitly declared. Mirrors the closest-wins-no-list-concat semantics documented
   * on `TrailmapDependencyResolver` in `:trailblaze-common`.
   *
   * Platforms the consumer didn't declare are NOT added — declaring `ios: {}` says "this
   * platform exists, fill in everything from defaults," while omitting the platform key
   * says "this target doesn't run on iOS." The runtime resolver enforces the same
   * distinction (see `TrailmapDependencyResolver.resolveTarget`).
   *
   * ## PARITY CONTRACT (do not skim)
   *
   * This function is a build-time mirror of
   * `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/TrailmapDependencyResolver.kt`'s
   * `resolveTarget` — same closest-wins inheritance rules, same cycle / missing-dep
   * behavior, same "platform set comes from the consumer" semantics. Build-logic has a
   * second implementation because Gradle includedBuild + build-classpath constraints
   * forbid pulling in `:trailblaze-common`'s heavy graph (koog, jackson, MCP). Both
   * implementations MUST stay aligned: if you change rules here, change them there too,
   * and rerun BOTH `TrailmapTargetGeneratorTest` (this file's tests) AND
   * `TrailmapDependencyResolverTest` (`:trailblaze-common`'s tests) — the two test suites
   * pin the same behavioral contract from each side.
   */
  private fun mergeInheritedDefaults(
    ownTarget: Map<String, Any?>,
    ownDeps: List<String>,
    ownTrailmapId: String,
    ownTrailmapFile: File,
    trailmapsById: Map<String, TrailmapInfo>,
  ): Map<String, Any?> {
    if (ownDeps.isEmpty()) return ownTarget

    val perPlatformContributions = linkedMapOf<String, LinkedHashMap<String, Any?>>()
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun visit(trailmapId: String, viaPath: List<String>) {
      if (trailmapId in visited) return
      if (trailmapId in visiting) {
        throw GradleException(
          "Cycle in trailmap dependencies: ${(viaPath + trailmapId).joinToString(" -> ")}",
        )
      }
      val info = trailmapsById[trailmapId] ?: throw GradleException(
        "Trailmap '$trailmapId' (referenced by dependency chain ${viaPath.joinToString(" -> ")}) " +
          "was not found under ${trailmapsDir.absolutePath}",
      )
      visiting += trailmapId

      // Collect this trailmap's own per-platform defaults BEFORE recursing into its deps,
      // so closer entries in the walk win when the same (platform, field) pair appears
      // in multiple trailmaps. Matches TrailmapDependencyResolver's "closest-wins" rule.
      val trailmapDefaults = info.manifest["defaults"] as? Map<*, *>
      if (trailmapDefaults != null) {
        normalizeMap(trailmapDefaults).forEach { (platform, platformDefaults) ->
          val platformMap = platformDefaults as? Map<*, *> ?: return@forEach
          val accum = perPlatformContributions.getOrPut(platform) { linkedMapOf() }
          platformMap.forEach { (field, value) ->
            val fieldKey = field as? String ?: return@forEach
            if (fieldKey !in accum) accum[fieldKey] = value
          }
        }
      }

      val nextDeps = (info.manifest["dependencies"] as? List<*>)
        ?.filterIsInstance<String>()
        .orEmpty()
      nextDeps.forEach { dep -> visit(dep, viaPath + trailmapId) }

      visiting -= trailmapId
      visited += trailmapId
    }

    ownDeps.forEach { dep -> visit(dep, listOf(ownTrailmapId)) }

    val ownPlatforms = ownTarget["platforms"] as? Map<*, *> ?: return ownTarget
    val mergedPlatforms = linkedMapOf<String, Any?>()
    ownPlatforms.forEach { (platformKey, ownPlatformValue) ->
      val platform = platformKey as? String ?: throw GradleException(
        "Trailmap manifest ${ownTrailmapFile.absolutePath} has non-string platform key '$platformKey'",
      )
      val ownPlatformMap = when (ownPlatformValue) {
        null -> linkedMapOf<String, Any?>()
        is Map<*, *> -> normalizeMap(ownPlatformValue)
        else -> throw GradleException(
          "Trailmap manifest ${ownTrailmapFile.absolutePath} has non-map value for platform " +
            "'$platform': $ownPlatformValue",
        )
      }
      val merged = linkedMapOf<String, Any?>()
      merged.putAll(ownPlatformMap)
      perPlatformContributions[platform]?.forEach { (field, value) ->
        if (field !in merged) merged[field] = value
      }
      mergedPlatforms[platform] = merged
    }

    val out = linkedMapOf<String, Any?>()
    ownTarget.forEach { (key, value) ->
      out[key] = if (key == "platforms") mergedPlatforms else value
    }
    return out
  }

  /**
   * Resolves a `system_prompt_file:` field on the trailmap target into an inlined `system_prompt:`
   * value, mirroring the runtime [TrailmapTargetConfig.toAppTargetYamlConfig] behavior. The file
   * path is resolved against the trailmap manifest's parent directory; missing or unreadable files
   * fail the build with a clear message so authoring errors don't slip into a stale generated
   * target. Returns the target map unchanged if neither field is present.
   *
   * The read side is typed via [TrailmapManifestPromptShape] (kaml typed decode against the same
   * `@SerialName` keys the runtime uses); the write side rebuilds a [LinkedHashMap] because the
   * generator's outer pipeline preserves arbitrary unknown fields by passing them through as a
   * generic map.
   */
  private fun resolveSystemPromptFile(target: Map<String, Any?>, trailmapFile: File): Map<String, Any?> {
    val promptShape =
      yaml.decodeFromString(TrailmapManifestPromptShape.serializer(), trailmapFile.readText())
    val promptFile = promptShape.target?.systemPromptFile ?: return target
    val trailmapDir = trailmapFile.parentFile
      ?: throw GradleException("Trailmap manifest ${trailmapFile.absolutePath} has no parent directory")
    val resolved = trailmapDir.resolve(promptFile).canonicalFile
    val trailmapDirCanonical = trailmapDir.canonicalFile
    // Path-element containment (not character-prefix). Without the trailing separator, a
    // sibling directory sharing the trailmap name as a prefix (e.g. `trailmap-extras/foo.md` against a
    // trailmap root `/.../trailmap`) would pass a raw startsWith check because they share the literal
    // prefix string. Comparing `path + separator` makes the check require a real directory
    // boundary, so siblings sharing a prefix can't escape the trailmap root.
    val trailmapDirPath = trailmapDirCanonical.path + File.separator
    if (resolved == trailmapDirCanonical || !resolved.path.startsWith(trailmapDirPath)) {
      throw GradleException(
        "system_prompt_file '$promptFile' in ${trailmapFile.absolutePath} resolves outside the trailmap " +
          "directory (${trailmapDir.absolutePath}); only trailmap-relative paths are allowed.",
      )
    }
    if (!resolved.isFile) {
      throw GradleException(
        "system_prompt_file '$promptFile' referenced by ${trailmapFile.absolutePath} not found at " +
          "${resolved.absolutePath}.",
      )
    }
    val content = resolved.readText()
    val out = linkedMapOf<String, Any?>()
    target.forEach { (key, value) ->
      when (key) {
        SYSTEM_PROMPT_FILE_KEY -> out[SYSTEM_PROMPT_KEY] = content
        else -> out[key] = value
      }
    }
    return out
  }


  private data class TrailmapInfo(val trailmapFile: File, val manifest: Map<String, Any?>)

  fun findManagedTargetFiles(): List<File> {
    if (!targetsDir.isDirectory) return emptyList()
    return targetsDir.listFiles()
      ?.filter { it.isFile && it.extension == "yaml" && it.readText().startsWith(GENERATED_HEADER) }
      ?.sortedBy { it.name }
      .orEmpty()
  }

  fun deleteStaleGeneratedTargets(expectedFiles: Set<File>) {
    findManagedTargetFiles()
      .filter { it !in expectedFiles }
      .forEach(File::delete)
  }

  private fun discoverTrailmapFiles(): List<File> {
    if (!trailmapsDir.isDirectory) return emptyList()
    return trailmapsDir.walkTopDown()
      .filter { it.isFile && it.name == "trailmap.yaml" }
      .sortedBy { it.relativeTo(trailmapsDir).invariantSeparatorsPath }
      .toList()
  }

  /**
   * Parse a trailmap manifest into a generic Map<String, Any?> shape via kaml's tree API.
   * The kaml-tree-to-Kotlin-tree conversion + plain-scalar resolution lives in
   * [YamlEmitter.yamlMapToMutable]; this method just wraps it with the manifest-specific
   * "must decode to a map" guard.
   */
  private fun loadMap(file: File): Map<String, Any?> {
    val node = yaml.parseToYamlNode(file.readText())
    val mapNode = node as? YamlMap
      ?: throw GradleException("Trailmap manifest must decode to a YAML map: ${file.absolutePath}")
    return YamlEmitter.yamlMapToMutable(mapNode)
  }

  private fun Map<String, Any?>.requiredString(key: String, sourceFile: File): String {
    val value = this[key] as? String
    if (value.isNullOrBlank()) {
      throw GradleException("Trailmap manifest ${sourceFile.absolutePath} is missing required '$key'")
    }
    return value
  }

  private fun normalizeMap(map: Map<*, *>): LinkedHashMap<String, Any?> {
    val normalized = linkedMapOf<String, Any?>()
    map.forEach { (key, value) ->
      val stringKey = key as? String
        ?: throw GradleException("Trailmap manifest map key must be a string, found '$key'")
      normalized[stringKey] = normalizeValue(value)
    }
    return normalized
  }

  private fun normalizeValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> normalizeMap(value)
    is List<*> -> value.map(::normalizeValue)
    else -> value
  }

  private fun File.invariantPathRelativeTo(root: File): String =
    relativeTo(root).invariantSeparatorsPath

  companion object {
    private const val GENERATED_HEADER = "# GENERATED FILE. DO NOT EDIT."

    // String keys for the trailmap-target YAML fields read/written by `resolveSystemPromptFile`.
    // Pulled out so the resolver doesn't sprinkle inline string literals at field boundaries —
    // single source of truth, kept in step with `TrailmapManifestPromptShape`'s @SerialName values
    // (and ultimately the runtime `TrailmapTargetConfig.system_prompt_file` /
    // `AppTargetYamlConfig.system_prompt` `@SerialName`s).
    const val SYSTEM_PROMPT_FILE_KEY = "system_prompt_file"
    const val SYSTEM_PROMPT_KEY = "system_prompt"

    /** Trailmap-relative directory that holds scripted-tool descriptor YAMLs. */
    private const val SCRIPTED_TOOLS_DIR = "tools"

    /**
     * Filename suffixes that mark an operational tool YAML rather than a scripted-tool
     * descriptor. Kept in lockstep with the runtime / bundler exclude lists.
     */
    private val OPERATIONAL_TOOL_YAML_SUFFIXES = listOf(
      ".tool.yaml",
      ".shortcut.yaml",
      ".trailhead.yaml",
      ".waypoint.yaml",
    )
  }

  /**
   * Resolves a trailmap manifest's `target.tools:` value (a list of trailmap-relative path refs into
   * `TrailmapScriptedToolFile` descriptors) into the full inline `InlineScriptToolConfig` shape
   * that `AppTargetYamlConfig.tools` expects at runtime. Empty/null input → empty list.
   *
   * Mirrors `TrailmapScriptedToolFile.toInlineScriptToolConfig()` from `:trailblaze-models`:
   *  - flat `inputSchema` is rewritten into a JSON-Schema `object` with `properties` and
   *    `required` arrays, dropping the per-property `required` flag the flat shape uses.
   *  - `supportedPlatforms` and `requiresHost` shortcuts fold into `_meta` under the
   *    `trailblaze/` namespace, with top-level shortcuts winning over conflicting `_meta`.
   *
   * Path refs ending in `.tool.yaml` are rejected with the same error message the runtime
   * resolver emits, so author mistakes surface at build time with file/line context.
   */
  private fun resolveScriptedToolList(
    rawTools: Any?,
    trailmapFile: File,
  ): List<Map<String, Any?>> {
    val list = rawTools as? List<*> ?: return emptyList()
    if (list.isEmpty()) return emptyList()
    val trailmapDir = trailmapFile.parentFile
      ?: throw GradleException("Trailmap manifest ${trailmapFile.absolutePath} has no parent directory")
    // Discover scripted-tool descriptors under `<trailmap>/tools/` and key them by `name:`.
    // `target.tools:` then names which discovered tools the target exposes — names, not
    // file paths. Mirrors the runtime resolution in `TrailblazeProjectConfigLoader` and
    // the build-time emission in `TrailblazeTrailmapBundler`.
    //
    // Each descriptor expands to 1..N entries:
    //   - Single-tool shape (`script: + name:`)         → 1 entry
    //   - Multi-tool shape    (`script: + tools: [...]`) → N entries (one per entry)
    // The runtime synthesizer (`InlineScriptToolServerSynthesizer`) and QuickJS bundler
    // both group by `script:` path, so a group of 8 tools costs one subprocess / one bundle
    // regardless of how the descriptor is structured.
    val discovery = buildTrailmapScriptedToolRegistry(trailmapDir, trailmapFile)
    val registry = discovery.registry
    // Detect duplicates inside `target.tools:` itself — without this guard, listing the same
    // name twice silently emits two inline-tool maps in the generated target YAML; the runtime
    // tool repo then fails with a confusing "tool already registered" message that points away
    // from the manifest. SISTER-IMPL-TAG: trailmap-target-tools-dup-detection.
    val seenInTarget = mutableSetOf<String>()
    return list.flatMap { entry ->
      val toolName = entry as? String ?: throw GradleException(
        "Trailmap manifest ${trailmapFile.absolutePath} has non-string entry in `target.tools:`: $entry",
      )
      if (!seenInTarget.add(toolName)) {
        throw GradleException(
          "Trailmap manifest ${trailmapFile.absolutePath}: `target.tools:` lists '$toolName' more than " +
            "once. Each scripted-tool name must appear at most once in `target.tools:`.",
        )
      }
      val match = registry[toolName] ?: throw GradleException(
        buildString {
          append("Trailmap scripted tool name '$toolName' not found under ")
          append("${trailmapDir.resolve(SCRIPTED_TOOLS_DIR).absolutePath} ")
          append("(referenced by `target.tools:` in ${trailmapFile.absolutePath}). ")
          if (registry.isEmpty()) {
            append("No scripted-tool descriptors discovered under <trailmap>/tools/.")
          } else {
            append("Available tool names: [${registry.keys.sorted().joinToString(", ")}].")
          }
          if (toolName.endsWith(".yaml") || toolName.contains('/')) {
            append(" Hint: '$toolName' looks like a file path; this field used to hold paths ")
            append("but now holds tool names — open the descriptor at that path and copy its ")
            append("`name:` field here.")
          }
          // Point at the skipped descriptor (if any) whose filename matches the unknown
          // name — see lead-dev round 3 #I2.
          val likelyCulprit = discovery.skipped.firstOrNull { it.name == "$toolName.yaml" }
          if (likelyCulprit != null) {
            append(" Note: descriptor '${likelyCulprit.absolutePath}' was skipped during ")
            append("discovery (see earlier stderr warning for the parse error). Fix that ")
            append("file to register the '$toolName' name.")
          } else if (discovery.skipped.isNotEmpty()) {
            append(" Note: ${discovery.skipped.size} other descriptor(s) under ")
            append("<trailmap>/tools/ were skipped during discovery (see earlier stderr ")
            append("warnings); one of them may have been intended to declare '$toolName'.")
          }
        },
      )
      trailmapScriptedToolToInlineMaps(match.descriptor, match.toolFile)
        .filter { it["name"] == toolName }
    }
  }

  /**
   * Pairs a scripted-tool descriptor with the file it was decoded from. Used by the
   * registry built in [buildTrailmapScriptedToolRegistry] so the downstream
   * [trailmapScriptedToolToInlineMaps] step can resolve script paths against the descriptor's
   * parent directory.
   */
  private data class ScriptedToolDescriptorMatch(
    val toolFile: File,
    val descriptor: YamlMap,
  )

  /**
   * Discovery result: name-keyed registry plus the list of descriptor files that were
   * skipped because their decode failed. Plumbed downstream to the unknown-name diagnostic
   * so an author whose `target.tools:` references a skipped file's intended name gets
   * pointed at it directly. See lead-dev round 3 #I2.
   */
  private data class TrailmapScriptedToolDiscoveryResult(
    val registry: Map<String, ScriptedToolDescriptorMatch>,
    val skipped: List<File>,
  )

  /**
   * Scans `<trailmapDir>/tools/` for scripted-tool descriptors (`*.yaml` files whose name
   * doesn't carry an operational suffix) and indexes each one by every declared tool
   * name (top-level `name:` for single-tool descriptors; each entry's `name:` for
   * multi-tool descriptors). Duplicate names across files in the same trailmap fail loudly
   * with both contributing file names.
   *
   * SISTER IMPLEMENTATIONS — same algorithm lives in three other places, keep all four in
   * lockstep:
   *   - `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/TrailblazeProjectConfigLoader.kt`
   *     `discoverTrailmapScriptedTools` — runtime trailmap loader.
   *   - `trailblaze-trailmap-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/TrailblazeTrailmapBundler.kt`
   *     `buildScriptedToolRegistry` — build-time `.d.ts` augmentation generator.
   *   - `trailblaze-host/src/main/java/xyz/block/trailblaze/scripting/DaemonScriptedToolBundler.kt`
   *     `discoverScriptedToolDescriptors` — daemon-time esbuild bundler.
   * Build-logic stays free of `:trailblaze-models` (Gradle plugin classpath concern); we
   * use kaml's raw `YamlMap`/`YamlList` here rather than the typed `TrailmapScriptedToolFile`
   * serializer. Keep the parse shape in sync with the typed sister implementations.
   *
   * Search tag for grepping all four sister implementations at once (resilient against
   * future file moves): `SISTER-IMPL-TAG: trailmap-scripted-tool-discovery`.
   */
  private fun buildTrailmapScriptedToolRegistry(
    trailmapDir: File,
    trailmapFile: File,
  ): TrailmapScriptedToolDiscoveryResult {
    val toolsDir = trailmapDir.resolve(SCRIPTED_TOOLS_DIR)
    if (!toolsDir.isDirectory) return TrailmapScriptedToolDiscoveryResult(emptyMap(), emptyList())
    // Canonical-path containment mirrors the runtime loader's `TrailmapSource.readFilesystemSibling`
    // guarantee — a `<trailmap>/tools/foo.yaml` symlink that resolves outside the trailmap must be
    // rejected, not silently followed. Without this check the Gradle generator would happily
    // decode escape symlinks the runtime loader would refuse to read, producing inline-tool
    // configs in the generated `dist/targets/*.yaml` that the loader then rejects at start time.
    val canonicalTrailmapDir = trailmapDir.canonicalFile.toPath()
    val candidates = toolsDir.listFiles()
      .orEmpty()
      .filter { it.isFile && it.name.endsWith(".yaml") }
      .filter { file -> OPERATIONAL_TOOL_YAML_SUFFIXES.none { file.name.endsWith(it) } }
      .filter { file ->
        // Translate IOException from canonicalize (symlink loop, FS quirk) into a typed
        // GradleException with the descriptor name in the message rather than an opaque NIO
        // stack trace from inside `.filter { }`.
        val canonicalFile = try {
          file.canonicalFile
        } catch (e: java.io.IOException) {
          throw GradleException(
            "Trailmap '${trailmapDir.name}' (${trailmapFile.absolutePath}): scripted-tool descriptor " +
              "candidate '${file.name}' under <trailmap>/tools/ could not be canonicalized " +
              "(likely a symlink loop or other filesystem error): ${e.message}",
            e,
          )
        }
        if (!canonicalFile.toPath().startsWith(canonicalTrailmapDir)) {
          throw GradleException(
            "Trailmap '${trailmapDir.name}' (${trailmapFile.absolutePath}): scripted-tool descriptor " +
              "candidate '${file.name}' under <trailmap>/tools/ resolves outside the trailmap directory " +
              "(canonical path: ${canonicalFile.absolutePath}, trailmap at: $canonicalTrailmapDir). " +
              "Symlinked descriptors must stay inside the trailmap.",
          )
        }
        true
      }
      .sortedBy { it.name }
    val registry = linkedMapOf<String, ScriptedToolDescriptorMatch>()
    val skipped = mutableListOf<File>()
    candidates.forEach { toolFile ->
      // Per-descriptor decode wrapped in try/log/skip so a single malformed (or half-written
      // WIP) file under `<trailmap>/tools/` doesn't tank the entire Gradle generator run. Sibling
      // descriptors still register; any `target.tools:` reference that names a tool from the
      // skipped file surfaces downstream as the unknown-name GradleException. See lead-dev
      // review #2 (round 2).
      // Narrow the catch to author-side failure modes (malformed YAML and shape mismatches).
      // CancellationException, OutOfMemoryError, and other VirtualMachineErrors propagate;
      // IOException from `readText` (permission denied, disk eject, file deleted mid-build)
      // is NOT an author-malformed-descriptor problem and surfaces as a GradleException so the
      // Gradle generator fails the build rather than logging-and-continuing past a real I/O
      // fault.
      val toolText = try {
        toolFile.readText()
      } catch (e: java.io.IOException) {
        throw GradleException(
          "Trailmap '${trailmapDir.name}' (${trailmapFile.absolutePath}): could not read scripted-tool " +
            "descriptor candidate '${toolFile.name}' under <trailmap>/tools/: ${e.message}",
          e,
        )
      }
      val descriptorNode = try {
        yaml.parseToYamlNode(toolText) as? YamlMap
      } catch (e: com.charleskorn.kaml.YamlException) {
        System.err.println(skippedMalformedMessage(toolFile, trailmapDir, e))
        skipped += toolFile
        return@forEach
      } catch (e: IllegalArgumentException) {
        // kaml raises IllegalArgumentException on a handful of shape mismatches at parse time —
        // same author-fixable failure class as YamlException.
        System.err.println(skippedMalformedMessage(toolFile, trailmapDir, e))
        skipped += toolFile
        return@forEach
      }
      if (descriptorNode == null) {
        System.err.println(
          "trailblaze: skipping scripted-tool descriptor ${toolFile.absolutePath} " +
            "(trailmap '${trailmapDir.name}') — top-level YAML must be a map. Sibling descriptors still register.",
        )
        skipped += toolFile
        return@forEach
      }
      val toolsNode = descriptorNode.entries.entries.firstOrNull { it.key.content == "tools" }?.value
      val declaredNames: List<String> = when {
        toolsNode != null -> {
          val toolsList = toolsNode as? com.charleskorn.kaml.YamlList
            ?: throw GradleException(
              "Tool descriptor $toolFile: `tools:` must be a list of maps, each declaring one " +
                "tool entry's `name:`.",
            )
          toolsList.items.map { entry ->
            (entry as? YamlMap)?.requireScalarString("name", toolFile)
              ?: throw GradleException(
                "Tool descriptor $toolFile: every entry under `tools:` must be a map with `name:`.",
              )
          }
        }
        else -> {
          val name = (descriptorNode.entries.entries.firstOrNull { it.key.content == "name" }?.value
            as? com.charleskorn.kaml.YamlScalar)?.content
          if (name == null) {
            // Skip-and-log: a typical WIP shape (`script: ./foo.ts` and nothing else) is
            // invisible rather than build-fatal; `target.tools:` references surface as
            // unknown-name downstream.
            System.err.println(
              "trailblaze: skipping scripted-tool descriptor ${toolFile.absolutePath} " +
                "(trailmap '${trailmapDir.name}') — must declare either a top-level `name:` (single-tool " +
                "shape) or `tools:` (multi-tool shape). Sibling descriptors still register.",
            )
            skipped += toolFile
            return@forEach
          }
          listOf(name)
        }
      }
      declaredNames.forEach { name ->
        // Symmetric with the bundler's `BlankToolName` guard — see SISTER-IMPL-TAG: trailmap-
        // scripted-tool-discovery. `name: ""` decodes successfully but would register under
        // the empty key, masking author errors.
        if (name.isBlank()) {
          throw GradleException(
            "Trailmap '${trailmapDir.name}' (${trailmapFile.absolutePath}): scripted-tool descriptor " +
              "'${toolFile.name}' declares a blank tool name. Tool names must be non-empty " +
              "and contain at least one non-whitespace character.",
          )
        }
        val previous = registry[name]
        if (previous != null) {
          throw GradleException(
            "Trailmap '${trailmapDir.name}' (${trailmapFile.absolutePath}): two scripted-tool descriptors " +
              "under <trailmap>/tools/ declare the same tool name '$name': " +
              "'${previous.toolFile.name}' and '${toolFile.name}'. Tool names must be unique " +
              "within a trailmap — rename one of the descriptors' `name:` field (or, for a " +
              "multi-tool descriptor, the offending entry under `tools:`).",
          )
        }
        registry[name] = ScriptedToolDescriptorMatch(toolFile, descriptorNode)
      }
    }
    return TrailmapScriptedToolDiscoveryResult(registry, skipped)
  }

  private fun skippedMalformedMessage(
    toolFile: File,
    trailmapDir: File,
    cause: Throwable,
  ): String =
    "trailblaze: skipping malformed scripted-tool descriptor ${toolFile.absolutePath} " +
      "(trailmap '${trailmapDir.name}'): ${cause.message}. Sibling descriptors still register; any " +
      "`target.tools:` entry naming a tool from this file will fail downstream until the " +
      "file is fixed."


  /**
   * Builds the runtime-shaped inline `InlineScriptToolConfig` map(s) from a `YamlMap` descriptor.
   *
   *  - **Single-tool shape** (`script: + name: + inputSchema:` at top level) → one inline map.
   *  - **Multi-tool shape** (`script: + tools: [...]`) → one inline map per entry, each carrying
   *    the descriptor's `script:` / `runtime:` and the entry's own name / description / inputSchema.
   *    File-wide `requiresHost` / `supportedPlatforms` / `_meta` apply to every entry unless
   *    the entry overrides them.
   *
   * The emitted YAML decodes cleanly against `AppTargetYamlConfig.tools`'s element type at
   * runtime — that's the contract `YamlConfigValidationTest` pins. The multi-tool shape is
   * purely an authoring sugar at this layer; the runtime never sees it, only the flattened
   * list of inline maps. Downstream dedup (one subprocess / one bundle per `script:`) happens
   * in the synthesizer + bundler, not here.
   */
  private fun trailmapScriptedToolToInlineMaps(
    descriptor: YamlMap,
    toolFile: File,
  ): List<Map<String, Any?>> {
    val rawScript = descriptor.requireScalarString("script", toolFile)
    // Descriptor `script:` is descriptor-relative (`./foo.js`) per the trailmap-author convention.
    // At runtime the synthesizer resolves it against JVM CWD (which is the repo root when the
    // user invokes `./trailblaze` from there). To make the generated target.yaml carry a path
    // that round-trips correctly, rewrite to the script file's path relative to [scriptRootDir]
    // — the consuming module sets this to `rootProject.layout.projectDirectory`.
    //
    // Falls back to the raw descriptor value when no `scriptRootDir` is configured: workspace
    // trailmaps that author scripted tools authored a path relative to where they invoke trailblaze
    // and don't need the rewrite.
    val script = if (scriptRootDir != null) {
      val scriptFile = toolFile.parentFile.resolve(rawScript).canonicalFile
      try {
        // Slash-normalise for portability — the generated YAML is committed and read on macOS
        // / Linux CI agents and Windows dev machines alike, so a single canonical form keeps the
        // file byte-identical across platforms.
        scriptFile.relativeTo(scriptRootDir.canonicalFile).invariantSeparatorsPath
      } catch (_: IllegalArgumentException) {
        // Script file outside the configured root — fall through to the raw value rather than
        // emit an absolute path that would fail on the next machine. The runtime will error
        // with the original ambiguous path so authors notice.
        rawScript
      }
    } else {
      rawScript
    }
    // `runtime:` override (subprocess|inProcess) — flows through to the generated target.yaml
    // verbatim. The runtime side ([TrailblazeHostYamlRunner]) reads it to override the legacy
    // extension-based routing; we validate the spelling here so a typo fails at build time
    // with a descriptor-aware message rather than silently falling through to the extension
    // heuristic at runtime.
    val runtime = descriptor.optionalScalarString("runtime")
    if (runtime != null) {
      require(runtime == "subprocess" || runtime == "inProcess") {
        "Tool descriptor $toolFile: `runtime:` must be `subprocess` or `inProcess` (got '$runtime')."
      }
    }

    // File-wide defaults (apply to every entry when the entry doesn't override).
    val fileWideRequiresHost = descriptor.optionalScalarBoolean("requiresHost") ?: false
    val fileWideSupportedPlatforms = descriptor.optionalStringList("supportedPlatforms", toolFile)
    val fileWideExplicitMeta = descriptor.optionalMap("_meta", toolFile).toExplicitMetaMap()

    // Multi-tool shape: presence of `tools:` toggles the multi-tool path. File-wide shortcuts
    // (`requiresHost` / `supportedPlatforms` / `_meta`) apply as defaults; per-entry overrides
    // take precedence. The top-level `name:` / `description:` / `inputSchema:` fields MUST NOT
    // appear at the file-wide level — each entry declares its own.
    val toolsNode = descriptor.entries.entries.firstOrNull { it.key.content == "tools" }?.value
    if (toolsNode != null) {
      val toolsList = toolsNode as? com.charleskorn.kaml.YamlList
        ?: throw GradleException(
          "Tool descriptor $toolFile: `tools:` must be a list of maps, each declaring one " +
            "tool entry's `name:` / `description:` / `inputSchema:`.",
        )
      require(toolsList.items.isNotEmpty()) {
        "Tool descriptor $toolFile: `tools:` is empty. Either remove `tools:` and use the " +
          "single-tool shape, or list at least one entry."
      }
      require(descriptor.entries.entries.none { it.key.content == "name" }) {
        "Tool descriptor $toolFile: multi-tool shape (`tools:` is set) must NOT also declare a " +
          "top-level `name:` — each entry's name lives under `tools:`."
      }
      require(descriptor.entries.entries.none { it.key.content == "description" }) {
        "Tool descriptor $toolFile: multi-tool shape (`tools:` is set) must NOT also declare a " +
          "top-level `description:` — each entry's description lives under `tools:`."
      }
      require(descriptor.entries.entries.none { it.key.content == "inputSchema" }) {
        "Tool descriptor $toolFile: multi-tool shape (`tools:` is set) must NOT also declare a " +
          "top-level `inputSchema:` — each entry's schema lives under `tools:`."
      }
      return toolsList.items.map { entryNode ->
        val entryMap = entryNode as? YamlMap
          ?: throw GradleException(
            "Tool descriptor $toolFile: every entry under `tools:` must be a map.",
          )
        val entryRequiresHostOverride = entryMap.optionalScalarBoolean("requiresHost")
        val entrySupportedPlatformsOverride =
          entryMap.optionalStringList("supportedPlatforms", toolFile)?.takeIf { it.isNotEmpty() }
        val entryExplicitMeta = entryMap.optionalMap("_meta", toolFile).toExplicitMetaMap()
        // Per-entry `_meta` keys win over file-wide defaults on conflict.
        val mergedExplicitMeta = linkedMapOf<String, Any?>().apply {
          putAll(fileWideExplicitMeta)
          putAll(entryExplicitMeta)
        }
        buildToolInlineMap(
          script = script,
          runtime = runtime,
          name = entryMap.requireScalarString("name", toolFile),
          description = entryMap.optionalScalarString("description"),
          requiresHost = entryRequiresHostOverride ?: fileWideRequiresHost,
          supportedPlatforms = entrySupportedPlatformsOverride ?: fileWideSupportedPlatforms,
          explicitMeta = mergedExplicitMeta,
          inputSchemaSource = entryMap.optionalMap("inputSchema", toolFile),
          toolFile = toolFile,
        )
      }
    }

    // Single-tool shape — the field placement that pre-dates the multi-tool shape, kept for
    // backwards compat. `name:` and `inputSchema:` live at the top level alongside `script:`.
    val name = descriptor.requireScalarString("name", toolFile)
    val description = descriptor.optionalScalarString("description")
    val inputSchemaSource = descriptor.optionalMap("inputSchema", toolFile)
    return listOf(
      buildToolInlineMap(
        script = script,
        runtime = runtime,
        name = name,
        description = description,
        requiresHost = fileWideRequiresHost,
        supportedPlatforms = fileWideSupportedPlatforms,
        explicitMeta = fileWideExplicitMeta,
        inputSchemaSource = inputSchemaSource,
        toolFile = toolFile,
      ),
    )
  }

  /**
   * Common per-tool builder shared by the single-tool path and the multi-tool entry path.
   * Returns one inline-tool map ready to embed under `target.tools:` in the generated YAML.
   */
  private fun buildToolInlineMap(
    script: String,
    runtime: String?,
    name: String,
    description: String?,
    requiresHost: Boolean,
    supportedPlatforms: List<String>?,
    explicitMeta: Map<String, Any?>,
    inputSchemaSource: YamlMap?,
    toolFile: File,
  ): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    out["script"] = script
    out["name"] = name
    if (description != null) out["description"] = description
    if (requiresHost) out["requiresHost"] = true
    if (runtime != null) out["runtime"] = runtime

    val mergedMeta = mergeMetaShortcuts(
      explicitMeta = explicitMeta,
      supportedPlatforms = supportedPlatforms,
      requiresHost = requiresHost,
    )
    if (mergedMeta.isNotEmpty()) out["_meta"] = mergedMeta

    out["inputSchema"] = buildJsonSchemaObject(inputSchemaSource, toolFile)
    return out
  }

  /** Convert an optional `_meta:` YamlMap into the plain-Kotlin explicit-meta map shape. */
  private fun YamlMap?.toExplicitMetaMap(): Map<String, Any?> =
    this
      ?.let { yamlNodeToPlain(it) as? Map<*, *> }
      ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
      ?.toMap()
      ?: emptyMap()

  /**
   * Translates the flat `inputSchema: { propName: { type, description?, enum?, required? } }`
   * shape from `TrailmapScriptedToolFile` into the JSON-Schema `{ type: object, properties: ...,
   * required: [...] }` shape `InlineScriptToolConfig.inputSchema` expects. Matches the runtime
   * conversion in `:trailblaze-models`'s `buildInputSchemaObject`.
   */
  private fun buildJsonSchemaObject(
    inputSchema: YamlMap?,
    toolFile: File,
  ): Map<String, Any?> {
    val properties = linkedMapOf<String, Any?>()
    val required = mutableListOf<String>()
    inputSchema?.entries?.forEach { (keyNode, propNode) ->
      val propName = keyNode.content
      val propMap = propNode as? YamlMap ?: throw GradleException(
        "inputSchema property '$propName' in $toolFile must be a map",
      )
      val p = linkedMapOf<String, Any?>()
      val type = propMap.requireScalarString("type", toolFile)
      p["type"] = type
      propMap.optionalScalarString("description")?.let { p["description"] = it }
      propMap.optionalStringList("enum", toolFile)?.let { values ->
        require(values.isNotEmpty()) {
          "Tool inputSchema property '$propName' in $toolFile: `enum` must declare at least " +
            "one value (JSON Schema rejects an empty enum array)."
        }
        p["enum"] = values
      }
      properties[propName] = p
      // Default `required: true` mirrors `ScriptedToolProperty.required = true` on the runtime side.
      if (propMap.optionalScalarBoolean("required") ?: true) required += propName
    }
    val out = linkedMapOf<String, Any?>()
    out["type"] = "object"
    // Emit `properties` ONLY when non-empty. SnakeYAML serializes an empty
    // `linkedMapOf<String, Any?>()` as `properties:` (no value), which kaml then
    // decodes to `JsonNull`, and the runtime descriptor builder
    // (`LazyYamlScriptedToolRegistration.buildDescriptor`) crashes with
    // `Element class kotlinx.serialization.json.JsonNull is not a JsonArray` on
    // the first downstream `.jsonObject` / `.jsonArray` access. Tools that take
    // no arguments simply have no `properties` key at all in the emitted
    // `target.yaml`; the runtime path handles the missing key cleanly via the
    // existing `schema["properties"]?.jsonObject ?: JsonObject(emptyMap())` fallback.
    if (properties.isNotEmpty()) {
      out["properties"] = properties
    }
    // Same reasoning for `required` — empty list emits as `required:` (null) and
    // explodes on `.jsonArray`. Only emit when at least one required field exists.
    // This also matches the runtime-side `buildInputSchemaObject` in
    // `:trailblaze-models` which has always been conditional on `requiredNames.isNotEmpty()`.
    if (required.isNotEmpty()) {
      out["required"] = required
    }
    return out
  }

  /**
   * Folds the top-level shortcut fields `supportedPlatforms` and `requiresHost` into the
   * explicit `_meta` map under their namespaced keys. Top-level wins on conflict — same rule
   * as the runtime `mergeMetaShortcuts` in `:trailblaze-models`.
   */
  private fun mergeMetaShortcuts(
    explicitMeta: Map<String, Any?>,
    supportedPlatforms: List<String>?,
    requiresHost: Boolean,
  ): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    out.putAll(explicitMeta)
    if (!supportedPlatforms.isNullOrEmpty()) {
      out["trailblaze/supportedPlatforms"] = supportedPlatforms
    }
    if (requiresHost) {
      out["trailblaze/requiresHost"] = true
    }
    return out
  }

  // ---------------------------------------------------------------------------------------------
  // kaml YamlNode helpers — local, untyped accessors that surface a `toolFile`-aware GradleException
  // when an author types the wrong shape. Equivalent to typed decode but with build-time error
  // context (kaml's default messages don't name the file path).
  // ---------------------------------------------------------------------------------------------

  private fun YamlMap.requireScalarString(key: String, source: File): String {
    val node = entries.entries.firstOrNull { it.key.content == key }?.value
      ?: throw GradleException("Required field '$key' missing in $source")
    return (node as? com.charleskorn.kaml.YamlScalar)?.content
      ?: throw GradleException("Field '$key' in $source must be a scalar string")
  }

  private fun YamlMap.optionalScalarString(key: String): String? {
    val node = entries.entries.firstOrNull { it.key.content == key }?.value ?: return null
    return (node as? com.charleskorn.kaml.YamlScalar)?.content
  }

  private fun YamlMap.optionalScalarBoolean(key: String): Boolean? {
    val node = entries.entries.firstOrNull { it.key.content == key }?.value ?: return null
    return (node as? com.charleskorn.kaml.YamlScalar)?.toBoolean()
  }

  private fun YamlMap.optionalStringList(key: String, source: File): List<String>? {
    val node = entries.entries.firstOrNull { it.key.content == key }?.value ?: return null
    val list = node as? com.charleskorn.kaml.YamlList
      ?: throw GradleException("Field '$key' in $source must be a list")
    return list.items.map { (it as? com.charleskorn.kaml.YamlScalar)?.content
      ?: throw GradleException("Entries of '$key' in $source must be scalar strings") }
  }

  private fun YamlMap.optionalMap(key: String, source: File): YamlMap? {
    val node = entries.entries.firstOrNull { it.key.content == key }?.value ?: return null
    return node as? YamlMap
      ?: throw GradleException("Field '$key' in $source must be a map")
  }

  /**
   * Best-effort `YamlNode` → plain-Kotlin conversion so the emitted YAML structure can
   * carry author-declared `_meta` content. Strings/booleans/numbers unwrap to native types;
   * lists + maps recurse. Falls back to `content` (string form) when the scalar can't be
   * coerced cleanly.
   */
  private fun yamlNodeToPlain(node: com.charleskorn.kaml.YamlNode): Any? = when (node) {
    is com.charleskorn.kaml.YamlNull -> null
    is com.charleskorn.kaml.YamlScalar -> {
      val text = node.content
      when {
        text == "true" -> true
        text == "false" -> false
        text.toLongOrNull() != null -> text.toLong()
        text.toDoubleOrNull() != null -> text.toDouble()
        else -> text
      }
    }
    is com.charleskorn.kaml.YamlList -> node.items.map { yamlNodeToPlain(it) }
    is YamlMap -> node.entries.entries.associate { (k, v) -> k.content to yamlNodeToPlain(v) }
    else -> node.toString()
  }
}

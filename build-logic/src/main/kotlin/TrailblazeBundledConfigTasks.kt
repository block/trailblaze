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
 * Minimal typed shape for the pack-manifest fields the generator consumes. Mirrors a subset of
 * the runtime [xyz.block.trailblaze.config.project.PackTargetConfig] schema so this build-logic
 * task can read system-prompt configuration through the same field names as the runtime, via
 * kaml's typed decode, instead of looking them up as inline string keys on a generic
 * `Map<String, Any?>`.
 *
 * Only the fields needed for build-time resolution are captured here — kaml ignores the rest
 * because [Yaml.configuration]'s `strictMode` is false in this task. The rest of the generator
 * keeps walking the pack manifest as a generic Map so unknown / future fields still flow through
 * to the emitted target YAML untouched.
 *
 * Build-logic intentionally does NOT depend on `:trailblaze-models` (avoids inflating the
 * Gradle classpath with the multiplatform graph), so this shape is duplicated here. Keep the
 * `@SerialName` values aligned with `PackTargetConfig` — same drift contract as
 * `mergeInheritedDefaults` keeps with `PackDependencyResolver`.
 */
@Serializable
private data class PackManifestPromptShape(
  val target: PackTargetPromptShape? = null,
) {
  @Serializable
  data class PackTargetPromptShape(
    @SerialName("system_prompt_file") val systemPromptFile: String? = null,
  )
}

abstract class TrailblazeBundledConfigExtension @Inject constructor(objects: ObjectFactory) {
  val packsDir: DirectoryProperty = objects.directoryProperty()
  val targetsDir: DirectoryProperty = objects.directoryProperty()
  val regenerateCommand: Property<String> = objects.property(String::class.java)
}

abstract class GenerateBundledTrailblazeConfigTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packsDir: DirectoryProperty

  @get:OutputDirectory
  abstract val targetsDir: DirectoryProperty

  @get:Input
  abstract val regenerateCommand: Property<String>

  @TaskAction
  fun generate() {
    val generator = PackTargetGenerator(
      packsDir = packsDir.asFile.get(),
      targetsDir = targetsDir.asFile.get(),
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
  abstract val packsDir: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val targetsDir: DirectoryProperty

  @get:Input
  abstract val regenerateCommand: Property<String>

  @TaskAction
  fun verify() {
    val generator = PackTargetGenerator(
      packsDir = packsDir.asFile.get(),
      targetsDir = targetsDir.asFile.get(),
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
        staleFiles += "Stale generated target with no pack source: ${stale.relativeTo(project.projectDir).invariantSeparatorsPath}"
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

// Generates flat target YAMLs from authored pack manifests for the bundled-config plugin.
//
// **YAML library: kaml.** SnakeYAML was the original choice but Trailblaze code is
// standardizing on kaml across the codebase. The generic-Map walker pattern below uses
// kaml's tree API (`YamlNode` / `YamlMap` / `YamlList` / `YamlScalar`) since pack manifests
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
// *parsing* pack manifests + emitting properly-quoted scalars on the dump path.
internal class PackTargetGenerator(
  private val packsDir: File,
  private val targetsDir: File,
  private val regenerateCommand: String,
) {
  /** Used for parsing pack manifests — strict mode off so unknown keys flow through. */
  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
  )

  fun buildExpectedTargets(): Map<File, String> {
    val expected = linkedMapOf<File, String>()
    val seenTargetIds = mutableSetOf<String>()

    // Pre-pass: load every discoverable pack and index by id so dep walks below can
    // look up `defaults:` from sibling packs. Build-logic intentionally does NOT depend
    // on `:trailblaze-common` (avoids inflating every Gradle build's classpath with the
    // koog/MCP graph), so the canonical [PackDependencyResolver] isn't reachable here.
    // This generator implements the same closest-wins inheritance against pack manifests
    // it walks itself; the equivalence test in `:trailblaze-common`'s compile-test suite
    // is the drift guard between this build-time mini-resolver and the runtime resolver.
    val packsById = linkedMapOf<String, PackInfo>()
    discoverPackFiles().forEach { packFile ->
      val pack = loadMap(packFile)
      val packId = pack.requiredString("id", packFile)
      check(packsById.put(packId, PackInfo(packFile, pack)) == null) {
        "Duplicate pack id '$packId' across pack manifests under ${packsDir.absolutePath} " +
          "(also at ${packsById[packId]?.packFile?.relativeTo(packsDir)})"
      }
    }

    packsById.values.forEach { (packFile, pack) ->
      val packId = pack.requiredString("id", packFile)
      val target = pack["target"] as? Map<*, *> ?: return@forEach
      val normalizedTarget = resolveSystemPromptFile(normalizeMap(target), packFile)
      // If `target.id` is set, it must be a string. Silently falling back to the pack
      // id when the author wrote `id: 123` (or anything non-string) would lose their
      // intent without warning — better to fail loudly so the misuse is fixable at
      // edit time rather than appearing as drift in a later regen.
      val explicitTargetId = normalizedTarget["id"]
      if (explicitTargetId != null && explicitTargetId !is String) {
        throw GradleException(
          "Pack manifest ${packFile.absolutePath} has 'target.id' of type " +
            "${explicitTargetId::class.simpleName} ($explicitTargetId). target.id must " +
            "be a string. Remove the field to default to the pack id ('$packId').",
        )
      }
      val targetId = (explicitTargetId as? String) ?: packId
      check(seenTargetIds.add(targetId)) {
        "Duplicate generated target id '$targetId' from ${packFile.relativeTo(packsDir)}"
      }

      val ownDeps = (pack["dependencies"] as? List<*>)
        ?.mapIndexed { index, value ->
          value as? String ?: throw GradleException(
            "Pack manifest ${packFile.absolutePath} has non-string dependency at index $index ($value)",
          )
        }
        .orEmpty()
      val resolvedTarget = mergeInheritedDefaults(
        ownTarget = normalizedTarget,
        ownDeps = ownDeps,
        ownPackId = packId,
        ownPackFile = packFile,
        packsById = packsById,
      )

      val orderedTarget = linkedMapOf<String, Any?>("id" to targetId)
      resolvedTarget.forEach { (key, value) ->
        if (key != "id") orderedTarget[key] = value
      }

      val sourceRoot = packsDir.parentFile?.parentFile ?: packsDir.parentFile ?: packsDir
      val sourcePath = packFile.invariantPathRelativeTo(sourceRoot)
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
   * on `PackDependencyResolver` in `:trailblaze-common`.
   *
   * Platforms the consumer didn't declare are NOT added — declaring `ios: {}` says "this
   * platform exists, fill in everything from defaults," while omitting the platform key
   * says "this target doesn't run on iOS." The runtime resolver enforces the same
   * distinction (see `PackDependencyResolver.resolveTarget`).
   *
   * ## PARITY CONTRACT (do not skim)
   *
   * This function is a build-time mirror of
   * `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/PackDependencyResolver.kt`'s
   * `resolveTarget` — same closest-wins inheritance rules, same cycle / missing-dep
   * behavior, same "platform set comes from the consumer" semantics. Build-logic has a
   * second implementation because Gradle includedBuild + build-classpath constraints
   * forbid pulling in `:trailblaze-common`'s heavy graph (koog, jackson, MCP). Both
   * implementations MUST stay aligned: if you change rules here, change them there too,
   * and rerun BOTH `PackTargetGeneratorTest` (this file's tests) AND
   * `PackDependencyResolverTest` (`:trailblaze-common`'s tests) — the two test suites
   * pin the same behavioral contract from each side.
   */
  private fun mergeInheritedDefaults(
    ownTarget: Map<String, Any?>,
    ownDeps: List<String>,
    ownPackId: String,
    ownPackFile: File,
    packsById: Map<String, PackInfo>,
  ): Map<String, Any?> {
    if (ownDeps.isEmpty()) return ownTarget

    val perPlatformContributions = linkedMapOf<String, LinkedHashMap<String, Any?>>()
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun visit(packId: String, viaPath: List<String>) {
      if (packId in visited) return
      if (packId in visiting) {
        throw GradleException(
          "Cycle in pack dependencies: ${(viaPath + packId).joinToString(" -> ")}",
        )
      }
      val info = packsById[packId] ?: throw GradleException(
        "Pack '$packId' (referenced by dependency chain ${viaPath.joinToString(" -> ")}) " +
          "was not found under ${packsDir.absolutePath}",
      )
      visiting += packId

      // Collect this pack's own per-platform defaults BEFORE recursing into its deps,
      // so closer entries in the walk win when the same (platform, field) pair appears
      // in multiple packs. Matches PackDependencyResolver's "closest-wins" rule.
      val packDefaults = info.manifest["defaults"] as? Map<*, *>
      if (packDefaults != null) {
        normalizeMap(packDefaults).forEach { (platform, platformDefaults) ->
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
      nextDeps.forEach { dep -> visit(dep, viaPath + packId) }

      visiting -= packId
      visited += packId
    }

    ownDeps.forEach { dep -> visit(dep, listOf(ownPackId)) }

    val ownPlatforms = ownTarget["platforms"] as? Map<*, *> ?: return ownTarget
    val mergedPlatforms = linkedMapOf<String, Any?>()
    ownPlatforms.forEach { (platformKey, ownPlatformValue) ->
      val platform = platformKey as? String ?: throw GradleException(
        "Pack manifest ${ownPackFile.absolutePath} has non-string platform key '$platformKey'",
      )
      val ownPlatformMap = when (ownPlatformValue) {
        null -> linkedMapOf<String, Any?>()
        is Map<*, *> -> normalizeMap(ownPlatformValue)
        else -> throw GradleException(
          "Pack manifest ${ownPackFile.absolutePath} has non-map value for platform " +
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
   * Resolves a `system_prompt_file:` field on the pack target into an inlined `system_prompt:`
   * value, mirroring the runtime [PackTargetConfig.toAppTargetYamlConfig] behavior. The file
   * path is resolved against the pack manifest's parent directory; missing or unreadable files
   * fail the build with a clear message so authoring errors don't slip into a stale generated
   * target. Returns the target map unchanged if neither field is present.
   *
   * The read side is typed via [PackManifestPromptShape] (kaml typed decode against the same
   * `@SerialName` keys the runtime uses); the write side rebuilds a [LinkedHashMap] because the
   * generator's outer pipeline preserves arbitrary unknown fields by passing them through as a
   * generic map.
   */
  private fun resolveSystemPromptFile(target: Map<String, Any?>, packFile: File): Map<String, Any?> {
    val promptShape =
      yaml.decodeFromString(PackManifestPromptShape.serializer(), packFile.readText())
    val promptFile = promptShape.target?.systemPromptFile ?: return target
    val packDir = packFile.parentFile
      ?: throw GradleException("Pack manifest ${packFile.absolutePath} has no parent directory")
    val resolved = packDir.resolve(promptFile).canonicalFile
    val packDirCanonical = packDir.canonicalFile
    // Path-element containment (not character-prefix). Without the trailing separator, a
    // sibling directory sharing the pack name as a prefix (e.g. `pack-extras/foo.md` against a
    // pack root `/.../pack`) would pass a raw startsWith check because they share the literal
    // prefix string. Comparing `path + separator` makes the check require a real directory
    // boundary, so siblings sharing a prefix can't escape the pack root.
    val packDirPath = packDirCanonical.path + File.separator
    if (resolved == packDirCanonical || !resolved.path.startsWith(packDirPath)) {
      throw GradleException(
        "system_prompt_file '$promptFile' in ${packFile.absolutePath} resolves outside the pack " +
          "directory (${packDir.absolutePath}); only pack-relative paths are allowed.",
      )
    }
    if (!resolved.isFile) {
      throw GradleException(
        "system_prompt_file '$promptFile' referenced by ${packFile.absolutePath} not found at " +
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


  private data class PackInfo(val packFile: File, val manifest: Map<String, Any?>)

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

  private fun discoverPackFiles(): List<File> {
    if (!packsDir.isDirectory) return emptyList()
    return packsDir.walkTopDown()
      .filter { it.isFile && it.name == "pack.yaml" }
      .sortedBy { it.relativeTo(packsDir).invariantSeparatorsPath }
      .toList()
  }

  /**
   * Parse a pack manifest into a generic Map<String, Any?> shape via kaml's tree API.
   * The kaml-tree-to-Kotlin-tree conversion + plain-scalar resolution lives in
   * [YamlEmitter.yamlMapToMutable]; this method just wraps it with the manifest-specific
   * "must decode to a map" guard.
   */
  private fun loadMap(file: File): Map<String, Any?> {
    val node = yaml.parseToYamlNode(file.readText())
    val mapNode = node as? YamlMap
      ?: throw GradleException("Pack manifest must decode to a YAML map: ${file.absolutePath}")
    return YamlEmitter.yamlMapToMutable(mapNode)
  }

  private fun Map<String, Any?>.requiredString(key: String, sourceFile: File): String {
    val value = this[key] as? String
    if (value.isNullOrBlank()) {
      throw GradleException("Pack manifest ${sourceFile.absolutePath} is missing required '$key'")
    }
    return value
  }

  private fun normalizeMap(map: Map<*, *>): LinkedHashMap<String, Any?> {
    val normalized = linkedMapOf<String, Any?>()
    map.forEach { (key, value) ->
      val stringKey = key as? String
        ?: throw GradleException("Pack manifest map key must be a string, found '$key'")
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

    // String keys for the pack-target YAML fields read/written by `resolveSystemPromptFile`.
    // Pulled out so the resolver doesn't sprinkle inline string literals at field boundaries —
    // single source of truth, kept in step with `PackManifestPromptShape`'s @SerialName values
    // (and ultimately the runtime `PackTargetConfig.system_prompt_file` /
    // `AppTargetYamlConfig.system_prompt` `@SerialName`s).
    const val SYSTEM_PROMPT_FILE_KEY = "system_prompt_file"
    const val SYSTEM_PROMPT_KEY = "system_prompt"
  }
}

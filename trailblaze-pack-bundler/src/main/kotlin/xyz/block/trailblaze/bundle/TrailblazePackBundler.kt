package xyz.block.trailblaze.bundle

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Walks each `pack.yaml` under [packsDir], collects the runnable target's scripted-tool
 * entries (resolved relative to the pack), reads each per-tool YAML descriptor, and emits a
 * single `tools.d.ts` under [outputDir] that augments `@trailblaze/scripting`'s
 * `TrailblazeToolMap` interface.
 *
 * **What scripted tools look like on disk.** Each entry under `target.tools:` is a path
 * (relative to the pack manifest) to a YAML file with the shape defined by trailblaze-models'
 * `PackScriptedToolFile` — `name`, `description`, optional `_meta`, and an `inputSchema`
 * map of property → `{ type, description?, enum?, required? }`. The bundler does the
 * minimum JSON-Schema → TS translation needed to cover the schema vocabulary actually used
 * in the codebase (`string`, `number`/`integer`, `boolean`, `enum`, `array`, `object`).
 * Anything outside that set falls back to `unknown` rather than failing the build —
 * keeps the bundler unblocked on schema additions.
 *
 * **Why not depend on trailblaze-models directly?** The bundler is consumed by build-logic
 * (Gradle plugin) AND the trailblaze CLI. Pulling trailblaze-models — which transitively
 * brings in koog and MCP — onto build-logic's configuration classpath would inflate every
 * Gradle build's startup. The bundler's own slim `@Serializable` schema (see
 * [BundlerYamlSchema]) covers exactly the fields it reads; kaml's `strictMode = false`
 * tolerates the additional fields trailblaze-models defines but the bundler doesn't care
 * about.
 *
 * **Pack dependency resolution is intentionally NOT applied here.** This bundler only
 * augments [TrailblazeToolMap] with *scripted* tools the pack itself declares — it does
 * not walk inherited `tool_sets:` from a `dependencies:` chain. Built-in tools resolved via
 * inheritance are covered by the SDK's vendored `built-in-tools.ts`; cross-pack scripted
 * tool inheritance is uncommon today and tracked as future work alongside pack-resolution
 * test coverage.
 */
class TrailblazePackBundler(
  private val packsDir: File,
  private val outputDir: File,
) {
  fun generate() {
    val entries = collectScriptedToolEntries()
    val rendered = renderBindings(entries)
    val outputFile = File(outputDir, GENERATED_FILE_NAME)
    outputFile.parentFile.mkdirs()
    if (!outputFile.exists() || outputFile.readText() != rendered) {
      outputFile.writeText(rendered)
    }
  }

  /**
   * Visible for testing — flat list of `(toolName, description?, params)` entries collected
   * from every `pack.yaml` under [packsDir].
   */
  internal fun collectScriptedToolEntries(): List<ScriptedToolEntry> {
    // Throw rather than return empty — a missing packsDir is almost always a config typo,
    // not "this consumer has zero packs." The Gradle plugin's `@InputDirectory` annotation
    // enforces this at task wiring time, but the bundler is also callable directly from
    // the CLI and daemon-startup paths; those callers need the same fast feedback.
    if (!packsDir.isDirectory) {
      throw TrailblazePackBundleException.MissingPacksDir(
        "TrailblazePackBundler: packsDir does not exist or is not a directory: " +
          "${packsDir.absolutePath}. Check the configuration of the bundler's `packsDir` input.",
      )
    }
    val yaml = lenientYaml()
    val entries = mutableListOf<ScriptedToolEntry>()
    val seenNames = mutableSetOf<String>()
    discoverPackFiles().forEach { packFile ->
      val packLabel = packFile.relativeTo(packsDir).invariantSeparatorsPath
      val pack = decodeManifest(yaml, packFile, packLabel)
      val toolPaths = pack.target?.tools ?: return@forEach
      val packDir = packFile.parentFile
      toolPaths.forEach { toolPath ->
        val toolFile = resolvePackRelativeToolFile(packDir, packLabel, toolPath)
        if (!toolFile.isFile) {
          throw TrailblazePackBundleException.MissingScriptedToolFile(
            "Pack manifest $packLabel references scripted tool at '$toolPath' but the file " +
              "does not exist (resolved to $toolFile).",
          )
        }
        val tool = decodeToolFile(yaml, toolFile)
        // Reject blank/whitespace-only tool names — kaml accepts an empty `name:` and the
        // bundler would otherwise emit `"": Record<string, never>;` in tools.d.ts (valid
        // TS, semantically broken). Surface the typo at build time with the offending
        // file.
        if (tool.name.isBlank()) {
          throw TrailblazePackBundleException.BlankToolName(
            "Scripted tool ${toolFile.absolutePath} has a blank 'name' field. Tool names must " +
              "be non-empty and contain at least one non-whitespace character.",
          )
        }
        if (!seenNames.add(tool.name)) {
          // Two packs declaring a scripted tool with the same name would collide in the
          // augmented TrailblazeToolMap (declaration merging requires identical types per
          // key). Better to fail the build than emit a `.d.ts` with quietly-merged
          // properties that don't match the runtime schema.
          throw TrailblazePackBundleException.DuplicateToolName(
            "Duplicate scripted tool name '${tool.name}' encountered while generating bindings " +
              "(in pack $packLabel). Tool names must be unique across all packs walked by this bundler.",
          )
        }
        val params = tool.inputSchema.map { (key, prop) ->
          ScriptedToolParam(
            name = key,
            tsType = jsonSchemaToTsType(prop, "Scripted tool ${tool.name} property '$key'"),
            description = prop.description,
            optional = !prop.required,
          )
        }
        entries +=
          ScriptedToolEntry(
            name = tool.name,
            description = tool.description,
            params = params,
            sourcePath = toolFile.relativeTo(packsDir).invariantSeparatorsPath,
          )
      }
    }
    return entries.sortedBy { it.name }
  }

  /**
   * Renders the augmentation file. Output is a single block — declaration merging on
   * `TrailblazeToolMap` happens automatically because every pack contributes to the same
   * interface and TypeScript merges them across files.
   *
   * The trailing `export {}` is what makes this file a TS module rather than a global
   * script — required for `declare module "@trailblaze/scripting"` to be treated as
   * augmentation rather than re-declaration. Same pattern as the SDK's vendored
   * `built-in-tools.ts`.
   */
  private fun renderBindings(entries: List<ScriptedToolEntry>): String = buildString {
    appendLine("// GENERATED FILE. DO NOT EDIT.")
    appendLine("// Source: pack manifests under ${packsDir.name}/ (see 'sourcePath' on each entry).")
    appendLine("// Regenerate with: ./gradlew bundleTrailblazePack")
    appendLine("//")
    appendLine("// Augments the @trailblaze/scripting `TrailblazeToolMap` interface with one entry per")
    appendLine("// scripted tool declared in this app's pack manifests, so `client.callTool(name, args)`")
    appendLine("// gets autocomplete on tool names and type-checking on args.")
    appendLine()
    if (entries.isEmpty()) {
      appendLine("// No scripted tools found in any pack under this module's packsDir.")
      appendLine("export {};")
      return@buildString
    }
    appendLine("declare module \"@trailblaze/scripting\" {")
    appendLine("  interface TrailblazeToolMap {")
    entries.forEachIndexed { index, entry ->
      if (index > 0) appendLine()
      appendLine("    /**")
      if (entry.description != null) {
        entry.description.lines().forEach { line ->
          appendLine("     * ${escapeComment(line)}")
        }
        appendLine("     *")
      }
      appendLine("     * Source: ${entry.sourcePath}")
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

  // Prevent embedded JSDoc closer in a tool description from prematurely closing the JSDoc
  // block in the generated output.
  private fun escapeComment(text: String): String = text.replace("*/", "* /")

  /**
   * TypeScript identifier rule (subset): start with `[A-Za-z_$]`, then `[A-Za-z0-9_$]*`. The
   * conservative subset rather than the full Unicode-aware definition because tool names in
   * this codebase are conventionally ASCII snake/camelCase. A name failing this check is
   * emitted as a quoted property name (`"weird-name": ...`).
   */
  private fun isSafeIdentifier(name: String): Boolean {
    if (name.isEmpty()) return false
    val first = name[0]
    if (!(first.isLetter() || first == '_' || first == '$')) return false
    return name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
  }

  private fun jsonSchemaToTsType(
    prop: BundlerScriptedToolProperty,
    propertyContext: String,
  ): String {
    val enumValues = prop.enum
    if (enumValues != null) {
      // An author who wrote `enum:` with no values almost certainly meant something else.
      // Silently emitting `unknown` would degrade autocomplete without surfacing the typo.
      if (enumValues.isEmpty()) {
        throw TrailblazePackBundleException.InvalidInputSchema(
          "$propertyContext: 'enum' must contain at least one value.",
        )
      }
      return enumValues.joinToString(" | ") { "\"${it.replace("\"", "\\\"")}\"" }
    }
    return when (prop.type) {
      "string" -> "string"
      "number", "integer" -> "number"
      "boolean" -> "boolean"
      "array" -> "unknown[]"
      "object" -> "Record<string, unknown>"
      "null" -> "null"
      null -> "unknown"
      else -> "unknown"
    }
  }

  /**
   * Validate a pack-relative tool ref and resolve it to a file under the pack dir. Mirrors
   * the runtime contract enforced by `PackSource.requirePackRelativePath` in
   * `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/PackSource.kt`
   * — blank, absolute, `/`- or `\\`-prefixed, `%`-encoded, and `..`-bearing paths are
   * rejected at the textual layer, and the canonical-resolved path must lie strictly under
   * the canonical pack directory.
   *
   * SISTER IMPLEMENTATION: `trailblaze-common/.../PackSource.kt`'s
   * `requirePackRelativePath` enforces the same textual rules at the runtime read path.
   * Drift would silently produce typed bindings for tool paths the runtime then refuses
   * to load — keep both rule sets in lockstep when editing either side.
   */
  private fun resolvePackRelativeToolFile(packDir: File, packLabel: String, toolPath: String): File {
    fun reject(reason: String): Nothing =
      throw TrailblazePackBundleException.InvalidPackRelativePath(
        "Pack manifest $packLabel has invalid scripted-tool ref '$toolPath': $reason",
      )
    if (toolPath.isBlank()) reject("path must not be blank")
    if (toolPath.startsWith("/") || toolPath.startsWith("\\")) {
      reject("path must not start with '/' or '\\'")
    }
    if (File(toolPath).isAbsolute) reject("path must not be absolute")
    if (toolPath.contains('%')) reject("path must not contain '%' (URL encoding is not allowed)")
    val segments = toolPath.split('/', '\\')
    if (segments.any { it == ".." }) reject("path must not contain '..' segments")

    // Containment via NIO `Path.startsWith` (element-wise) — protects against pathological
    // prefix-overlap escapes (`<packDir-evil>/x` sharing a textual prefix with `<packDir>`).
    // Equality check is load-bearing: Path.startsWith returns false for equal paths, so a
    // toolPath that resolves exactly to the pack dir itself would otherwise slip through.
    val canonicalTargetFile = try {
      File(packDir, toolPath).canonicalFile
    } catch (e: IOException) {
      reject("path could not be canonicalized: ${e.message}")
    }
    val canonicalPackDirFile = try {
      packDir.canonicalFile
    } catch (e: IOException) {
      reject("pack directory could not be canonicalized: ${e.message}")
    }
    val canonicalTarget = canonicalTargetFile.toPath()
    val canonicalPackDir = canonicalPackDirFile.toPath()
    if (canonicalTarget == canonicalPackDir) reject("path must not resolve to the pack directory itself")
    if (!canonicalTarget.startsWith(canonicalPackDir)) {
      reject(
        "path resolves outside the pack directory " +
          "(resolved to ${canonicalTarget.invariantSeparatorsPathString})",
      )
    }
    return canonicalTargetFile
  }

  /**
   * Discover `pack.yaml` files via NIO so we can opt OUT of symlink following. Kotlin's
   * default `walkTopDown` follows links, which can wedge the build on a cycle
   * (`packs/foo -> packs/foo/../foo`). NIO's `Files.walk` with no [java.nio.file.FileVisitOption]
   * varargs does NOT follow links — desired behavior here, since pack manifests should live
   * in the source tree they're authored in, not be reached via symlinks.
   */
  private fun discoverPackFiles(): List<File> {
    val root: Path = packsDir.toPath()
    return Files.walk(root).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.fileName?.toString() == "pack.yaml" }
        .map { it.toFile() }
        .sorted(compareBy { it.relativeTo(packsDir).invariantSeparatorsPath })
        .toList()
    }
  }

  private fun decodeManifest(yaml: Yaml, packFile: File, packLabel: String): BundlerPackManifest = try {
    yaml.decodeFromString(BundlerPackManifest.serializer(), packFile.readText())
  } catch (e: YamlException) {
    throw TrailblazePackBundleException.MalformedManifest(
      "Pack manifest $packLabel is not a valid YAML object the bundler can parse: ${e.message}",
      e,
    )
  }

  private fun decodeToolFile(yaml: Yaml, toolFile: File): BundlerToolFile = try {
    yaml.decodeFromString(BundlerToolFile.serializer(), toolFile.readText())
  } catch (e: YamlException) {
    throw TrailblazePackBundleException.MalformedScriptedTool(
      "Scripted tool ${toolFile.absolutePath} is not a valid YAML object the bundler can parse: ${e.message}",
      e,
    )
  }

  companion object {
    const val GENERATED_DIR_NAME = ".trailblaze"
    const val GENERATED_FILE_NAME = "tools.d.ts"

    /**
     * Shared kaml configuration for the bundler. `strictMode = false` so a pack manifest
     * (or scripted-tool YAML) carrying fields outside the bundler's slim
     * `@Serializable` view (e.g. `dependencies:`, `_meta:`, `requiresHost:`) is decoded
     * cleanly. Mirrors `TrailblazeConfigYaml.instance` from trailblaze-models without
     * adding a dependency on that module.
     */
    private fun lenientYaml(): Yaml =
      Yaml(
        configuration =
          YamlConfiguration(
            strictMode = false,
            encodeDefaults = false,
          ),
      )
  }
}

/** One scripted-tool entry — keyed by [name], with rendered TS [params]. */
data class ScriptedToolEntry(
  val name: String,
  val description: String?,
  val params: List<ScriptedToolParam>,
  val sourcePath: String,
)

data class ScriptedToolParam(
  val name: String,
  val tsType: String,
  val description: String?,
  val optional: Boolean,
)

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
 * Walks each `pack.yaml` under [packsDir] and emits one `.d.ts` per pack at
 * `<packDir>/tools/.trailblaze/tools.d.ts`, each augmenting `@trailblaze/scripting`'s
 * `TrailblazeToolMap` interface with the scripted tools available to that pack — i.e.
 * the pack's own `target.tools:` plus every scripted tool inherited transitively through
 * `dependencies:`.
 *
 * **Per-pack output, dep-aware aggregation.** Every pack — target or library — gets its
 * own bindings file scoped to the union of its own scripted tools and the scripted-tool
 * lists of every pack reachable through its `dependencies:` chain. Built-in (Kotlin)
 * tools are still covered by the SDK's vendored `built-in-tools.ts`; this bundler only
 * touches the scripted-tool half.
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
 * about. The bundler also re-implements a minimal `dependencies:` DFS rather than reusing
 * `PackDependencyResolver` for the same reason — that resolver operates on the
 * `:trailblaze-common` config types (`AppTargetYamlConfig`, `PlatformConfig`), and the
 * walk the bundler needs is small and orthogonal (scripted-tool aggregation, not
 * platform-defaults closest-wins overlay).
 *
 * **Scope of the dep walk.** Only workspace packs under [packsDir] participate. A
 * `dependencies:` entry that doesn't resolve to a sibling pack in [packsDir] is silently
 * skipped — at build time the bundler can't see classpath-shipped packs (e.g. the
 * framework `trailblaze` stdlib pack, which ships no scripted tools anyway). The runtime
 * loader's stricter "missing dependency" check still applies when the daemon starts. A
 * cycle in the workspace dep graph fails loudly with the offending chain.
 */
class TrailblazePackBundler(
  private val packsDir: File,
) {
  /**
   * Walks each `pack.yaml` under [packsDir] and emits one `.d.ts` per pack at
   * `<packDir>/tools/.trailblaze/tools.d.ts`. Each pack's bindings cover the union of
   * its own `target.tools:` and the scripted tools of every pack reachable through its
   * `dependencies:` chain (transitively, with cycle detection).
   *
   * Packs whose closure has no scripted tools (a library pack with no `target:` block
   * and no deps that declare any) are skipped — no empty `.d.ts` is written. Idempotent:
   * re-running with unchanged inputs leaves output bytes untouched.
   *
   * **Orphan cleanup.** After (re)writing the expected outputs, the bundler walks
   * [packsDir] for any `tools/.trailblaze/tools.d.ts` files that weren't written this
   * run and deletes them. Covers the rename-a-pack and delete-its-tools cases — a
   * stale `.d.ts` from a previous configuration would otherwise feed an orphaned
   * `TrailblazeToolMap` augmentation into the next `tsc` invocation. Cleanup is best-
   * effort: failures to delete (e.g. file-system permission) don't abort `generate`.
   */
  fun generate() {
    if (!packsDir.isDirectory) {
      throw TrailblazePackBundleException.MissingPacksDir(
        "TrailblazePackBundler: packsDir does not exist or is not a directory: " +
          "${packsDir.absolutePath}. Check the configuration of the bundler's `packsDir` input.",
      )
    }
    val yaml = lenientYaml()
    val packsById = loadManifestsByPackId(yaml)
    val writtenOutputs = mutableSetOf<File>()
    packsById.values.forEach { parsed ->
      val entries = collectScriptedToolEntriesForClosure(yaml, parsed, packsById)
      if (entries.isEmpty()) return@forEach
      val packDir = parsed.packFile.parentFile
      val rendered = renderBindings(parsed.packLabel, entries)
      val outputFile = File(packDir, "tools/$GENERATED_DIR_NAME/$GENERATED_FILE_NAME")
      outputFile.parentFile.mkdirs()
      if (!outputFile.exists() || outputFile.readText() != rendered) {
        outputFile.writeText(rendered)
      }
      // Best-effort tracking. `canonicalFile` raises IOException on weird filesystem
      // states (symlink loops, permission flips, dangling links). The file is already
      // written above — failing to record it for orphan-cleanup purposes is acceptable;
      // failing the whole build because we couldn't canonicalize our own output isn't.
      try {
        writtenOutputs += outputFile.canonicalFile
      } catch (_: IOException) {
        // Orphan cleanup may skip this entry on its own try/catch path; consistent.
      }
    }
    cleanOrphanedOutputs(writtenOutputs)
  }

  /**
   * Compute the set of output directories the bundler would (re)populate on a [generate]
   * call against the current on-disk state. One entry per pack whose dependency-closure
   * scripted-tool list is non-empty — i.e. the pack either declares its own
   * `target.tools:` or transitively inherits scripted tools via `dependencies:`. Each
   * entry points at `<packDir>/tools/.trailblaze/`.
   *
   * Exposed for the Gradle plugin's `@OutputDirectories` wiring — Gradle resolves this
   * lazily at task-up-to-date check time, so the walk + closure parse happens once per
   * task invocation rather than at configuration time.
   *
   * **Error handling.** Workspace-level failures (duplicate pack id, malformed `pack.yaml`,
   * I/O error reading the manifest set) propagate to the caller — the same error
   * [generate] would throw on the same workspace. This is intentional: silently swallowing
   * a workspace-level error here and returning `emptyList()` would feed Gradle an empty
   * output snapshot that compares UP-TO-DATE against the previous empty snapshot, masking
   * a broken build. The Gradle plugin's `discoverOutputDirectories` wraps the call so
   * Gradle still surfaces a clean error rather than a raw stack trace.
   *
   * **Per-pack failures during closure walk** (a `dependencies:` chain that itself contains
   * a malformed manifest, a cross-pack tool-name collision, a cycle) are caught per-pack
   * and that pack contributes no entry. The @TaskAction will re-walk and surface the
   * actual error message; here we just need to avoid declaring an output we know won't
   * be written.
   *
   * Must stay in lockstep with [generate]'s emission rule: any pack that gets a
   * `.d.ts` written must also appear here, otherwise Gradle's output snapshot misses
   * the file and UP-TO-DATE checks go wrong. The closure walk done here is therefore
   * the same one [generate] uses (via [collectScriptedToolEntriesForClosure]).
   */
  fun discoverExpectedOutputDirs(): List<File> {
    if (!packsDir.isDirectory) return emptyList()
    val yaml = lenientYaml()
    // Workspace-level errors (duplicate pack id, malformed manifest, I/O) intentionally
    // propagate — see kdoc above. The plugin wraps this call so the user gets a clean
    // GradleException with context rather than a raw stack trace.
    val packsById = loadManifestsByPackId(yaml)
    return packsById.values.mapNotNull { parsed ->
      val entries = try {
        collectScriptedToolEntriesForClosure(yaml, parsed, packsById)
      } catch (_: TrailblazePackBundleException) {
        // Per-pack closure error (cross-pack tool-name collision, cycle reachable only
        // from THIS pack's deps). The @TaskAction re-walks and surfaces with full context;
        // here we just avoid pre-declaring an output that won't be written.
        return@mapNotNull null
      } catch (_: IOException) {
        return@mapNotNull null
      }
      if (entries.isEmpty()) return@mapNotNull null
      File(parsed.packFile.parentFile, "tools/$GENERATED_DIR_NAME")
    }
  }

  /**
   * Delete `tools.d.ts` files under [packsDir] that aren't in [writtenOutputs] — i.e.
   * stale outputs left over from a previous run where a pack was renamed, deleted, or
   * had its scripted tools removed. Walks the same NIO `Files.walk` (no symlink follow)
   * used by [discoverPackFiles] so symlink loops don't wedge cleanup either.
   *
   * Also removes the empty `.trailblaze` parent dir when its only file was the orphaned
   * `tools.d.ts` — keeps the tree tidy for grep / `tsc include` sweeps over the pack.
   */
  private fun cleanOrphanedOutputs(writtenOutputs: Set<File>) {
    val rootPath: Path = packsDir.toPath()
    val candidates: List<File> = Files.walk(rootPath).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.fileName?.toString() == GENERATED_FILE_NAME }
        .filter { path ->
          // Match the bundler's own output shape: `<packDir>/tools/.trailblaze/tools.d.ts`.
          // Checking only `.trailblaze/` as the parent would catch any sibling
          // `.trailblaze/tools.d.ts` an author happens to create elsewhere under the
          // pack tree — verify the grandparent is `tools/` too so cleanup stays scoped to
          // what the bundler actually writes.
          val parent = path.parent ?: return@filter false
          val grandparent = parent.parent ?: return@filter false
          parent.fileName?.toString() == GENERATED_DIR_NAME &&
            grandparent.fileName?.toString() == GENERATED_PARENT_DIR_NAME
        }
        .map { it.toFile() }
        .toList()
    }
    candidates.forEach { file ->
      // `canonicalFile` and `delete()` both raise IOException on permission failures,
      // missing intermediate dirs, etc. The bundler kdoc documents cleanup as best-
      // effort, so swallow any I/O failure for a single orphan rather than letting it
      // abort the whole `generate()` call — but log it so the developer has a breadcrumb
      // when a stale `.d.ts` mysteriously keeps showing up in `tsc` runs.
      try {
        if (file.canonicalFile !in writtenOutputs) {
          file.delete()
          val parent = file.parentFile
          if (parent != null && parent.list()?.isEmpty() == true) {
            parent.delete()
          }
        }
      } catch (e: IOException) {
        // stderr (not stdout) so the line is visible without affecting the bundler's
        // "no side-effects on stdout" contract for CLI consumers. Gradle pipes task
        // stderr to its own console output, so the message surfaces in build logs.
        System.err.println(
          "TrailblazePackBundler: best-effort orphan cleanup failed for " +
            "${file.absolutePath}: ${e.message ?: e::class.simpleName}",
        )
      }
    }
  }

  /**
   * Visible for testing — collects scripted-tool entries for **all** packs under [packsDir]
   * (own declarations only, no dep-walk, no cross-pack closure dedup). Within-pack
   * dedup *is* still enforced by [collectScriptedToolEntriesForPack] — a single pack
   * declaring the same `name:` twice still fails loudly here. The per-pack emission path
   * additionally walks `dependencies:` and layers cross-pack collision detection on top.
   * Result is sorted by tool name across all packs for deterministic test assertions.
   */
  internal fun collectScriptedToolEntries(): List<ScriptedToolEntry> {
    if (!packsDir.isDirectory) {
      throw TrailblazePackBundleException.MissingPacksDir(
        "TrailblazePackBundler: packsDir does not exist or is not a directory: " +
          "${packsDir.absolutePath}. Check the configuration of the bundler's `packsDir` input.",
      )
    }
    val yaml = lenientYaml()
    val all = mutableListOf<ScriptedToolEntry>()
    discoverPackFiles().forEach { packFile ->
      val packLabel = packFile.relativeTo(packsDir).invariantSeparatorsPath
      all += collectScriptedToolEntriesForPack(yaml, packFile, packLabel)
    }
    return all.sortedBy { it.name }
  }

  /**
   * Parse every pack manifest under [packsDir] into [ParsedPack] entries keyed by the
   * manifest's `id` field. Sorted by id for deterministic iteration order so emission
   * order is reproducible across runs.
   *
   * Fails loudly when two packs share an id — pack ids are the dependency-graph key and
   * a collision would make `dependencies: [<shared-id>]` ambiguous.
   */
  private fun loadManifestsByPackId(yaml: Yaml): Map<String, ParsedPack> {
    val byId = sortedMapOf<String, ParsedPack>()
    discoverPackFiles().forEach { packFile ->
      val packLabel = packFile.relativeTo(packsDir).invariantSeparatorsPath
      val manifest = decodeManifest(yaml, packFile, packLabel)
      val existing = byId[manifest.id]
      if (existing != null) {
        throw TrailblazePackBundleException.DuplicatePackId(
          "Two pack manifests under ${packsDir.absolutePath} declare id '${manifest.id}': " +
            "${existing.packLabel} and $packLabel. Pack ids must be unique within a workspace — " +
            "they are the routing key used by `dependencies:` references.",
        )
      }
      byId[manifest.id] = ParsedPack(manifest, packFile, packLabel)
    }
    return byId
  }

  /**
   * Returns every scripted-tool entry reachable from [root] — its own `target.tools:`
   * union'd with every transitively-inherited pack's `target.tools:` — sorted by tool
   * name for deterministic output.
   *
   * **Walk semantics.**
   * - DFS over `dependencies:` starting from [root]'s own deps. [root] itself is
   *   recorded as visited up-front so a self-referential `dependencies: [<own-id>]`
   *   declaration is caught as a cycle rather than producing duplicate self-inclusion.
   * - Each visited pack contributes its own scripted-tool list once (diamond deps don't
   *   double-count — a pack reached via two paths is walked once).
   * - Missing deps (the dependency id is not a sibling pack in [packsDir]) are silently
   *   skipped. Build-time bundler can't see classpath-shipped packs; the runtime loader
   *   has its own missing-dep error path that fires when the daemon starts.
   * - A cycle within the workspace dep graph throws [TrailblazePackBundleException.CyclicDependencies]
   *   with the offending chain — same fail-loud shape as `PackDependencyResolver`.
   * - Tool-name collisions across the closure throw [TrailblazePackBundleException.DuplicateToolName]
   *   naming both contributing pack ids. Within-pack dedup is enforced by
   *   [collectScriptedToolEntriesForPack] before its entries reach this aggregator.
   */
  private fun collectScriptedToolEntriesForClosure(
    yaml: Yaml,
    root: ParsedPack,
    packsById: Map<String, ParsedPack>,
  ): List<ScriptedToolEntry> {
    val aggregated = mutableListOf<ScriptedToolEntry>()
    val ownerByToolName = mutableMapOf<String, String>()
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun visit(current: ParsedPack) {
      if (!visited.add(current.manifest.id)) return
      val entries = collectScriptedToolEntriesForPack(yaml, current.packFile, current.packLabel)
      entries.forEach { entry ->
        // Each pack is visited at most once (visited guard above) and within-pack dedup
        // ran in collectScriptedToolEntriesForPack, so a non-null previousOwner here
        // necessarily names a DIFFERENT pack — i.e. a cross-pack name collision.
        val previousOwner = ownerByToolName.put(entry.name, current.manifest.id)
        if (previousOwner != null) {
          throw TrailblazePackBundleException.DuplicateToolName(
            "Scripted tool name '${entry.name}' is declared by both pack " +
              "'$previousOwner' and pack '${current.manifest.id}', both of which are in the " +
              "dependency closure of pack '${root.manifest.id}'. Tool names must be unique across " +
              "a pack's entire dependency closure so the generated `TrailblazeToolMap` augmentation " +
              "has a single shape per name.",
          )
        }
        aggregated += entry
      }
      visiting += current.manifest.id
      current.manifest.dependencies.forEach { depId ->
        if (depId in visiting) {
          throw TrailblazePackBundleException.CyclicDependencies(
            "Cycle detected in pack dependencies of '${root.manifest.id}' involving '$depId' " +
              "(chain: ${visiting.joinToString(" -> ")} -> $depId).",
          )
        }
        // Missing deps are silently skipped — build-time bundler doesn't see classpath
        // packs (e.g. framework `trailblaze` stdlib pack); the runtime loader's strict
        // missing-dep check runs when the daemon starts.
        val depPack = packsById[depId] ?: return@forEach
        visit(depPack)
      }
      visiting -= current.manifest.id
    }

    visit(root)
    return aggregated.sortedBy { it.name }
  }

  /**
   * Collects scripted-tool entries owned by a single pack. Per-pack tool-name dedup is
   * enforced here so authoring a typo'd duplicate inside one pack surfaces with a clear
   * error rather than silently producing a malformed `.d.ts`.
   */
  private fun collectScriptedToolEntriesForPack(
    yaml: Yaml,
    packFile: File,
    packLabel: String,
  ): List<ScriptedToolEntry> {
    val pack = decodeManifest(yaml, packFile, packLabel)
    val toolPaths = pack.target?.tools ?: return emptyList()
    if (toolPaths.isEmpty()) return emptyList()
    val packDir = packFile.parentFile
    val entries = mutableListOf<ScriptedToolEntry>()
    val seenNames = mutableSetOf<String>()
    toolPaths.forEach { toolPath ->
      val toolFile = resolvePackRelativeToolFile(packDir, packLabel, toolPath)
      if (!toolFile.isFile) {
        throw TrailblazePackBundleException.MissingScriptedToolFile(
          "Pack manifest $packLabel references scripted tool at '$toolPath' but the file " +
            "does not exist (resolved to $toolFile).",
        )
      }
      val tool = decodeToolFile(yaml, toolFile)
      if (tool.name.isBlank()) {
        throw TrailblazePackBundleException.BlankToolName(
          "Scripted tool ${toolFile.absolutePath} has a blank 'name' field. Tool names must " +
            "be non-empty and contain at least one non-whitespace character.",
        )
      }
      if (!seenNames.add(tool.name)) {
        throw TrailblazePackBundleException.DuplicateToolName(
          "Duplicate scripted tool name '${tool.name}' encountered in pack $packLabel. " +
            "Tool names must be unique within a pack.",
        )
      }
      val params = tool.inputSchema.map { (key, prop) ->
        ScriptedToolParam(
          name = key,
          tsType = jsonSchemaToTsType(
            type = prop.type,
            enumValues = prop.enum,
            propertyContext = "Scripted tool ${tool.name} property '$key'",
          ),
          description = prop.description,
          optional = !prop.required,
        )
      }
      entries += ScriptedToolEntry(
        name = tool.name,
        description = tool.description,
        params = params,
        sourcePath = toolFile.relativeTo(packsDir).invariantSeparatorsPath,
      )
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
  private fun renderBindings(packLabel: String, entries: List<ScriptedToolEntry>): String = buildString {
    appendLine("// GENERATED FILE. DO NOT EDIT.")
    appendLine("// Source: pack manifest $packLabel (see 'sourcePath' on each entry).")
    appendLine("// Regenerate with: ./gradlew bundleTrailblazePack")
    appendLine("//")
    appendLine("// Augments the @trailblaze/scripting `TrailblazeToolMap` interface with one entry per")
    appendLine("// scripted tool available to this pack — its own `target.tools:` plus every scripted")
    appendLine("// tool inherited transitively through `dependencies:`. `client.callTool(name, args)`")
    appendLine("// gets autocomplete on tool names and type-checking on args.")
    appendLine("//")
    appendLine("// If two packs declare a tool with the same name and both .d.ts files land in the")
    appendLine("// same tsconfig include scope, TypeScript surfaces a declaration-merging error. Scope")
    appendLine("// each pack's tsconfig to its own tools/ directory to avoid this — that's the default.")
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
    /** Parent of [GENERATED_DIR_NAME] in the per-pack output layout (`tools/.trailblaze/`). */
    const val GENERATED_PARENT_DIR_NAME = "tools"

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

/**
 * One parsed pack manifest with the on-disk anchors needed to re-resolve its scripted
 * tools later. Held in the `loadManifestsByPackId` map so [TrailblazePackBundler] can
 * walk dependencies by id without re-parsing manifest YAML on every visit.
 */
internal data class ParsedPack(
  val manifest: BundlerPackManifest,
  val packFile: File,
  val packLabel: String,
)

data class ScriptedToolParam(
  val name: String,
  val tsType: String,
  val description: String?,
  val optional: Boolean,
)

/**
 * Translate a single JSON-Schema-shaped property descriptor (`type` + optional `enum`) into the
 * equivalent TypeScript type literal. The vocabulary mirrors the JSON-Schema subset the
 * codebase actually uses (`string`, `number`/`integer`, `boolean`, `array`, `object`, `null`),
 * plus `enum` rendered as a string-literal union. Anything outside that set falls back to
 * `unknown` rather than failing the build — keeps the bundler unblocked on schema additions.
 *
 * Hoisted to a top-level `internal` function so the bundler's twin `.d.ts` writers
 * ([TrailblazePackBundler] for build-time per-pack output and [WorkspaceClientDtsGenerator]
 * for daemon-time workspace output) share one mapping with no risk of drift.
 */
internal fun jsonSchemaToTsType(
  type: String?,
  enumValues: List<String>?,
  propertyContext: String,
): String {
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
  return when (type) {
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

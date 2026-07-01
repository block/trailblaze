package xyz.block.trailblaze.bundle

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Walks each `trailmap.yaml` under [trailmapsDir] and emits one `.d.ts` per trailmap at
 * `<trailmapDir>/tools/.trailblaze/tools.d.ts`, each augmenting `@trailblaze/scripting`'s
 * `TrailblazeToolMap` interface with the scripted tools available to that trailmap — i.e.
 * the trailmap's own `target.tools:` plus every scripted tool inherited transitively through
 * `dependencies:`.
 *
 * **Per-trailmap output, dep-aware aggregation.** Every trailmap — target or library — gets its
 * own bindings file scoped to the union of its own scripted tools and the scripted-tool
 * lists of every trailmap reachable through its `dependencies:` chain. Built-in (Kotlin)
 * tools are still covered by the SDK's vendored `built-in-tools.ts`; this bundler only
 * touches the scripted-tool half.
 *
 * **What scripted tools look like on disk.** Each entry under `target.tools:` is a tool
 * *name* (not a file path). The bundler scans every `.yaml` file under `<trailmap>/tools/`
 * (excluding operational suffixes — see the discovery walk below) for descriptors with
 * the shape defined by trailblaze-models' `TrailmapScriptedToolFile` — `name`, `description`,
 * optional `_meta`, and an `inputSchema` map of property → `{ type, description?, enum?,
 * required? }` — and indexes each by its declared `name:` (single-tool shape) or by each
 * entry's `name:` (multi-tool shape). Operational tool YAMLs (`.tool.yaml`,
 * `.shortcut.yaml`, `.trailhead.yaml`, `.waypoint.yaml`) in the same directory are
 * skipped during scripted-tool discovery. The bundler does the minimum JSON-Schema → TS
 * translation needed to cover the schema vocabulary actually used in the codebase
 * (`string`, `number`/`integer`, `boolean`, `enum`, `array`, `object`). Anything outside
 * that set falls back to `unknown` rather than failing the build — keeps the bundler
 * unblocked on schema additions.
 *
 * **Why not depend on trailblaze-models directly?** The bundler is consumed by build-logic
 * (Gradle plugin) AND the trailblaze CLI. Pulling trailblaze-models — which transitively
 * brings in koog and MCP — onto build-logic's configuration classpath would inflate every
 * Gradle build's startup. The bundler's own slim `@Serializable` schema (see
 * [BundlerYamlSchema]) covers exactly the fields it reads; kaml's `strictMode = false`
 * tolerates the additional fields trailblaze-models defines but the bundler doesn't care
 * about. The bundler also re-implements a minimal `dependencies:` DFS rather than reusing
 * `TrailmapDependencyResolver` for the same reason — that resolver operates on the
 * `:trailblaze-common` config types (`AppTargetYamlConfig`, `PlatformConfig`), and the
 * walk the bundler needs is small and orthogonal (scripted-tool aggregation, not
 * platform-defaults closest-wins overlay).
 *
 * **Scope of the dep walk.** Only workspace trailmaps under [trailmapsDir] participate. A
 * `dependencies:` entry that doesn't resolve to a sibling trailmap in [trailmapsDir] is silently
 * skipped — at build time the bundler can't see classpath-shipped trailmaps (e.g. the
 * framework `trailblaze` stdlib trailmap, which ships no scripted tools anyway). The runtime
 * loader's stricter "missing dependency" check still applies when the daemon starts. A
 * cycle in the workspace dep graph fails loudly with the offending chain.
 */
class TrailblazeTrailmapBundler(
  private val trailmapsDir: File,
) {
  /**
   * Walks each `trailmap.yaml` under [trailmapsDir] and emits one `.d.ts` per trailmap at
   * `<trailmapDir>/tools/.trailblaze/tools.d.ts`. Each trailmap's bindings cover the union of
   * its own `target.tools:` and the scripted tools of every trailmap reachable through its
   * `dependencies:` chain (transitively, with cycle detection).
   *
   * Trailmaps whose closure has no scripted tools (a library trailmap with no `target:` block
   * and no deps that declare any) are skipped — no empty `.d.ts` is written. Idempotent:
   * re-running with unchanged inputs leaves output bytes untouched.
   *
   * **Orphan cleanup.** After (re)writing the expected outputs, the bundler walks
   * [trailmapsDir] for any `tools/.trailblaze/tools.d.ts` files that weren't written this
   * run and deletes them. Covers the rename-a-trailmap and delete-its-tools cases — a
   * stale `.d.ts` from a previous configuration would otherwise feed an orphaned
   * `TrailblazeToolMap` augmentation into the next `tsc` invocation. Cleanup is best-
   * effort: failures to delete (e.g. file-system permission) don't abort `generate`.
   */
  fun generate() {
    if (!trailmapsDir.isDirectory) {
      throw TrailblazeTrailmapBundleException.MissingTrailmapsDir(
        "TrailblazeTrailmapBundler: trailmapsDir does not exist or is not a directory: " +
          "${trailmapsDir.absolutePath}. Check the configuration of the bundler's `trailmapsDir` input.",
      )
    }
    val yaml = lenientYaml()
    val trailmapsById = loadManifestsByTrailmapId(yaml)
    val writtenOutputs = mutableSetOf<File>()
    trailmapsById.values.forEach { parsed ->
      val entries = collectScriptedToolEntriesForClosure(yaml, parsed, trailmapsById)
      if (entries.isEmpty()) return@forEach
      val trailmapDir = parsed.trailmapFile.parentFile
      val rendered = renderBindings(parsed.trailmapLabel, entries)
      val outputFile = File(trailmapDir, "tools/$GENERATED_DIR_NAME/$GENERATED_FILE_NAME")
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
   * call against the current on-disk state. One entry per trailmap whose dependency-closure
   * scripted-tool list is non-empty — i.e. the trailmap either declares its own
   * `target.tools:` or transitively inherits scripted tools via `dependencies:`. Each
   * entry points at `<trailmapDir>/tools/.trailblaze/`.
   *
   * Exposed for the Gradle plugin's `@OutputDirectories` wiring — Gradle resolves this
   * lazily at task-up-to-date check time, so the walk + closure parse happens once per
   * task invocation rather than at configuration time.
   *
   * **Error handling.** Workspace-level failures (duplicate trailmap id, malformed `trailmap.yaml`,
   * I/O error reading the manifest set) propagate to the caller — the same error
   * [generate] would throw on the same workspace. This is intentional: silently swallowing
   * a workspace-level error here and returning `emptyList()` would feed Gradle an empty
   * output snapshot that compares UP-TO-DATE against the previous empty snapshot, masking
   * a broken build. The Gradle plugin's `discoverOutputDirectories` wraps the call so
   * Gradle still surfaces a clean error rather than a raw stack trace.
   *
   * **Per-trailmap failures during closure walk** (a `dependencies:` chain that itself contains
   * a malformed manifest, a cross-trailmap tool-name collision, a cycle) are caught per-trailmap
   * and that trailmap contributes no entry. The @TaskAction will re-walk and surface the
   * actual error message; here we just need to avoid declaring an output we know won't
   * be written.
   *
   * Must stay in lockstep with [generate]'s emission rule: any trailmap that gets a
   * `.d.ts` written must also appear here, otherwise Gradle's output snapshot misses
   * the file and UP-TO-DATE checks go wrong. The closure walk done here is therefore
   * the same one [generate] uses (via [collectScriptedToolEntriesForClosure]).
   */
  fun discoverExpectedOutputDirs(): List<File> {
    if (!trailmapsDir.isDirectory) return emptyList()
    val yaml = lenientYaml()
    // Workspace-level errors (duplicate trailmap id, malformed manifest, I/O) intentionally
    // propagate — see kdoc above. The plugin wraps this call so the user gets a clean
    // GradleException with context rather than a raw stack trace.
    val trailmapsById = loadManifestsByTrailmapId(yaml)
    return trailmapsById.values.mapNotNull { parsed ->
      val entries = try {
        collectScriptedToolEntriesForClosure(yaml, parsed, trailmapsById)
      } catch (_: TrailblazeTrailmapBundleException) {
        // Per-trailmap closure error (cross-trailmap tool-name collision, cycle reachable only
        // from THIS trailmap's deps). The @TaskAction re-walks and surfaces with full context;
        // here we just avoid pre-declaring an output that won't be written.
        return@mapNotNull null
      } catch (_: IOException) {
        return@mapNotNull null
      }
      if (entries.isEmpty()) return@mapNotNull null
      File(parsed.trailmapFile.parentFile, "tools/$GENERATED_DIR_NAME")
    }
  }

  /**
   * Delete `tools.d.ts` files under [trailmapsDir] that aren't in [writtenOutputs] — i.e.
   * stale outputs left over from a previous run where a trailmap was renamed, deleted, or
   * had its scripted tools removed. Walks the same NIO `Files.walk` (no symlink follow)
   * used by [discoverTrailmapFiles] so symlink loops don't wedge cleanup either.
   *
   * Also removes the empty `.trailblaze` parent dir when its only file was the orphaned
   * `tools.d.ts` — keeps the tree tidy for grep / `tsc include` sweeps over the trailmap.
   */
  private fun cleanOrphanedOutputs(writtenOutputs: Set<File>) {
    val rootPath: Path = trailmapsDir.toPath()
    val candidates: List<File> = Files.walk(rootPath).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.fileName?.toString() == GENERATED_FILE_NAME }
        .filter { path ->
          // Match the bundler's own output shape: `<trailmapDir>/tools/.trailblaze/tools.d.ts`.
          // Checking only `.trailblaze/` as the parent would catch any sibling
          // `.trailblaze/tools.d.ts` an author happens to create elsewhere under the
          // trailmap tree — verify the grandparent is `tools/` too so cleanup stays scoped to
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
          "TrailblazeTrailmapBundler: best-effort orphan cleanup failed for " +
            "${file.absolutePath}: ${e.message ?: e::class.simpleName}",
        )
      }
    }
  }

  /**
   * Visible for testing — collects scripted-tool entries for **all** trailmaps under [trailmapsDir]
   * (own declarations only, no dep-walk, no cross-trailmap closure dedup). Within-trailmap
   * dedup *is* still enforced by [collectScriptedToolEntriesForTrailmap] — a single trailmap
   * declaring the same `name:` twice still fails loudly here. The per-trailmap emission path
   * additionally walks `dependencies:` and layers cross-trailmap collision detection on top.
   * Result is sorted by tool name across all trailmaps for deterministic test assertions.
   */
  internal fun collectScriptedToolEntries(): List<ScriptedToolEntry> {
    if (!trailmapsDir.isDirectory) {
      throw TrailblazeTrailmapBundleException.MissingTrailmapsDir(
        "TrailblazeTrailmapBundler: trailmapsDir does not exist or is not a directory: " +
          "${trailmapsDir.absolutePath}. Check the configuration of the bundler's `trailmapsDir` input.",
      )
    }
    val yaml = lenientYaml()
    val all = mutableListOf<ScriptedToolEntry>()
    discoverTrailmapFiles().forEach { trailmapFile ->
      val trailmapLabel = trailmapFile.relativeTo(trailmapsDir).invariantSeparatorsPath
      all += collectScriptedToolEntriesForTrailmap(yaml, trailmapFile, trailmapLabel)
    }
    return all.sortedBy { it.name }
  }

  /**
   * Parse every trailmap manifest under [trailmapsDir] into [ParsedTrailmap] entries keyed by the
   * manifest's `id` field. Sorted by id for deterministic iteration order so emission
   * order is reproducible across runs.
   *
   * Fails loudly when two trailmaps share an id — trailmap ids are the dependency-graph key and
   * a collision would make `dependencies: [<shared-id>]` ambiguous.
   */
  private fun loadManifestsByTrailmapId(yaml: Yaml): Map<String, ParsedTrailmap> {
    val byId = sortedMapOf<String, ParsedTrailmap>()
    discoverTrailmapFiles().forEach { trailmapFile ->
      val trailmapLabel = trailmapFile.relativeTo(trailmapsDir).invariantSeparatorsPath
      val manifest = decodeManifest(yaml, trailmapFile, trailmapLabel)
      val existing = byId[manifest.id]
      if (existing != null) {
        throw TrailblazeTrailmapBundleException.DuplicateTrailmapId(
          "Two trailmap manifests under ${trailmapsDir.absolutePath} declare id '${manifest.id}': " +
            "${existing.trailmapLabel} and $trailmapLabel. Trailmap ids must be unique within a workspace — " +
            "they are the routing key used by `dependencies:` references.",
        )
      }
      byId[manifest.id] = ParsedTrailmap(manifest, trailmapFile, trailmapLabel)
    }
    return byId
  }

  /**
   * Returns every scripted-tool entry reachable from [root] — its own `target.tools:`
   * union'd with every transitively-inherited trailmap's `target.tools:` — sorted by tool
   * name for deterministic output.
   *
   * **Walk semantics.**
   * - DFS over `dependencies:` starting from [root]'s own deps. [root] itself is
   *   recorded as visited up-front so a self-referential `dependencies: [<own-id>]`
   *   declaration is caught as a cycle rather than producing duplicate self-inclusion.
   * - Each visited trailmap contributes its own scripted-tool list once (diamond deps don't
   *   double-count — a trailmap reached via two paths is walked once).
   * - Missing deps (the dependency id is not a sibling trailmap in [trailmapsDir]) are silently
   *   skipped. Build-time bundler can't see classpath-shipped trailmaps; the runtime loader
   *   has its own missing-dep error path that fires when the daemon starts.
   * - A cycle within the workspace dep graph throws [TrailblazeTrailmapBundleException.CyclicDependencies]
   *   with the offending chain — same fail-loud shape as `TrailmapDependencyResolver`.
   * - Tool-name collisions across the closure throw [TrailblazeTrailmapBundleException.DuplicateToolName]
   *   naming both contributing trailmap ids. Within-trailmap dedup is enforced by
   *   [collectScriptedToolEntriesForTrailmap] before its entries reach this aggregator.
   */
  private fun collectScriptedToolEntriesForClosure(
    yaml: Yaml,
    root: ParsedTrailmap,
    trailmapsById: Map<String, ParsedTrailmap>,
  ): List<ScriptedToolEntry> {
    val aggregated = mutableListOf<ScriptedToolEntry>()
    val ownerByToolName = mutableMapOf<String, String>()
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun visit(current: ParsedTrailmap) {
      if (!visited.add(current.manifest.id)) return
      val entries = collectScriptedToolEntriesForTrailmap(yaml, current.trailmapFile, current.trailmapLabel)
      entries.forEach { entry ->
        // Each trailmap is visited at most once (visited guard above) and within-trailmap dedup
        // ran in collectScriptedToolEntriesForTrailmap, so a non-null previousOwner here
        // necessarily names a DIFFERENT trailmap — i.e. a cross-trailmap name collision.
        val previousOwner = ownerByToolName.put(entry.name, current.manifest.id)
        if (previousOwner != null) {
          throw TrailblazeTrailmapBundleException.DuplicateToolName(
            "Scripted tool name '${entry.name}' is declared by both trailmap " +
              "'$previousOwner' and trailmap '${current.manifest.id}', both of which are in the " +
              "dependency closure of trailmap '${root.manifest.id}'. Tool names must be unique across " +
              "a trailmap's entire dependency closure so the generated `TrailblazeToolMap` augmentation " +
              "has a single shape per name.",
          )
        }
        aggregated += entry
      }
      visiting += current.manifest.id
      current.manifest.dependencies.forEach { depId ->
        if (depId in visiting) {
          throw TrailblazeTrailmapBundleException.CyclicDependencies(
            "Cycle detected in trailmap dependencies of '${root.manifest.id}' involving '$depId' " +
              "(chain: ${visiting.joinToString(" -> ")} -> $depId).",
          )
        }
        // Missing deps are silently skipped — build-time bundler doesn't see classpath
        // trailmaps (e.g. framework `trailblaze` stdlib trailmap); the runtime loader's strict
        // missing-dep check runs when the daemon starts.
        val depTrailmap = trailmapsById[depId] ?: return@forEach
        visit(depTrailmap)
      }
      visiting -= current.manifest.id
    }

    visit(root)
    return aggregated.sortedBy { it.name }
  }

  /**
   * Collects scripted-tool entries owned by a single trailmap.
   *
   * Discovery model: walk `<trailmapDir>/tools/` for `*.yaml` files (direct children only),
   * skipping operational tool YAMLs (`*.tool.yaml`, `*.shortcut.yaml`, `*.trailhead.yaml`,
   * `*.waypoint.yaml`). Each remaining descriptor declares either a single tool name
   * (`name:` at the top level) or N tool names (one per entry under `tools:`). Build a
   * trailmap-local registry keyed by tool name, with duplicate-name detection naming both
   * contributing files.
   *
   * The trailmap manifest's `target.tools:` list then selects which discovered names this
   * target exposes — names, not file paths. Names that don't match any descriptor entry
   * fail loudly with the discovered name list in the error.
   */
  private fun collectScriptedToolEntriesForTrailmap(
    yaml: Yaml,
    trailmapFile: File,
    trailmapLabel: String,
  ): List<ScriptedToolEntry> {
    val trailmap = decodeManifest(yaml, trailmapFile, trailmapLabel)
    val toolNames = trailmap.target?.tools ?: return emptyList()
    if (toolNames.isEmpty()) return emptyList()
    val trailmapDir = trailmapFile.parentFile
    val (registry, skipped) = buildScriptedToolRegistry(yaml, trailmapDir, trailmapLabel, trailmap.id)
    val entries = mutableListOf<ScriptedToolEntry>()
    // SISTER-IMPL-TAG: trailmap-target-tools-dup-detection.
    val seenInTarget = mutableSetOf<String>()
    toolNames.forEach { toolName ->
      if (!seenInTarget.add(toolName)) {
        // Wording matches the sister implementations in TrailblazeProjectConfigLoader /
        // DaemonScriptedToolBundler / TrailblazeBundledConfigTasks so the same author error
        // produces an identical diagnostic regardless of which entry point caught it.
        throw TrailblazeTrailmapBundleException.DuplicateToolName(
          "Trailmap manifest $trailmapLabel: `target.tools:` lists '$toolName' more than once. " +
            "Each scripted-tool name must appear at most once in `target.tools:`.",
        )
      }
      val entry = registry[toolName]
        ?: throw TrailblazeTrailmapBundleException.UnknownScriptedToolName(
          buildString {
            append("Trailmap manifest $trailmapLabel references scripted tool name '$toolName' in ")
            append("`target.tools:`, but no scripted-tool descriptor with that name was ")
            append("discovered under <trailmap>/tools/. ")
            append(describeAvailableNames(registry))
            append(" Tool names must match the `name:` field inside a `<trailmap>/tools/<file>.yaml` ")
            append("descriptor (or one of its `tools:` entries) — `target.tools:` is a list of ")
            append("names, not file paths.")
            if (toolName.endsWith(".yaml") || toolName.contains('/')) {
              append(" Hint: '$toolName' looks like a file path; this field used to hold paths ")
              append("but now holds tool names — open the descriptor at that path and copy its ")
              append("`name:` field here.")
            }
            // If a descriptor was skipped during discovery whose filename matches the
            // unknown tool name (the conventional `<name>.yaml`), the author is almost
            // certainly looking for THAT file — point them at it directly so they don't
            // have to grep back through stderr warnings to find the cause.
            val likelyCulprit = skipped.firstOrNull { it.name == "$toolName.yaml" }
            if (likelyCulprit != null) {
              append(" Note: descriptor '${likelyCulprit.absolutePath}' was skipped during ")
              append("discovery (see earlier stderr warning for the parse error). Fix that ")
              append("file to register the '$toolName' name.")
            } else if (skipped.isNotEmpty()) {
              append(" Note: ${skipped.size} other descriptor(s) under <trailmap>/tools/ were ")
              append("skipped during discovery (see earlier stderr warnings); one of them may ")
              append("have been intended to declare '$toolName'.")
            }
          },
        )
      val params = entry.inputSchema.map { (key, prop) ->
        ScriptedToolParam(
          name = key,
          tsType = jsonSchemaToTsType(
            type = prop.type,
            enumValues = prop.enum,
            propertyContext = "Scripted tool $toolName property '$key'",
          ),
          description = prop.description,
          optional = !prop.required,
        )
      }
      entries += ScriptedToolEntry(
        name = toolName,
        description = entry.description,
        params = params,
        sourcePath = entry.sourceFile.relativeTo(trailmapsDir).invariantSeparatorsPath,
      )
    }
    return entries.sortedBy { it.name }
  }

  /** One entry in the per-trailmap scripted-tool registry: enough to render a [ScriptedToolEntry]. */
  private data class ScriptedToolRegistryEntry(
    val sourceFile: File,
    val description: String?,
    val inputSchema: Map<String, BundlerScriptedToolProperty>,
  )

  /**
   * Walks `<trailmapDir>/tools/` for `*.yaml` files (direct children only, operational suffixes
   * excluded), decodes each into [BundlerToolFile], and indexes every declared tool name
   * (single-tool descriptors register their top-level `name:`; multi-tool descriptors
   * register each entry's `name:`). Duplicate names across files throw with both
   * contributing file paths.
   *
   * SISTER IMPLEMENTATIONS — same algorithm lives in three other places, keep all four in
   * lockstep:
   *   - `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/TrailblazeProjectConfigLoader.kt`
   *     `discoverTrailmapScriptedTools` — runtime trailmap loader.
   *   - `trailblaze-host/src/main/java/xyz/block/trailblaze/scripting/DaemonScriptedToolBundler.kt`
   *     `discoverScriptedToolDescriptors` — daemon-time esbuild bundler.
   *   - `build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt`
   *     `buildTrailmapScriptedToolRegistry` — Gradle bundled-config generator.
   *
   * Search tag for grepping all four sister implementations at once (resilient against
   * future file moves): `SISTER-IMPL-TAG: trailmap-scripted-tool-discovery`.
   */
  /**
   * Discovery result: the name-keyed registry plus the list of descriptor files that were
   * skipped because their decode failed. The skip list is plumbed downstream to the
   * UnknownScriptedToolName diagnostic so an author whose `target.tools:` references the
   * skipped file's name gets pointed at it directly rather than just seeing a stderr warning
   * earlier in the build log.
   */
  private data class ScriptedToolDiscoveryResult(
    val registry: Map<String, ScriptedToolRegistryEntry>,
    val skipped: List<File>,
  )

  private fun buildScriptedToolRegistry(
    yaml: Yaml,
    trailmapDir: File,
    trailmapLabel: String,
    trailmapId: String,
  ): ScriptedToolDiscoveryResult {
    val toolsDir = File(trailmapDir, SCRIPTED_TOOLS_DIR)
    if (!toolsDir.isDirectory) return ScriptedToolDiscoveryResult(emptyMap(), emptyList())
    // Canonical-path containment for every discovered descriptor — a `<trailmap>/tools/foo.yaml`
    // symlink that resolves outside the trailmap must be rejected, not silently followed. Mirrors
    // the loader's `TrailmapSource.readFilesystemSibling` guarantee (which the typed runtime path
    // already enforces on referenced sibling reads); restoring it here so the bundler's typed
    // surface and the runtime agree on what counts as a valid descriptor and don't drift on
    // symlinked escapes.
    val canonicalTrailmapDir = trailmapDir.canonicalFile.toPath()
    // Recursive so descriptors organized into `tools/<subdir>/` are discovered (matching the
    // analyzer + the sister impls). `Files.walk` does not follow symlinks (same rationale as
    // discoverTrailmapFiles), so it can't wedge on a cycle; the per-file canonical-containment
    // check below still rejects escapes. SISTER-IMPL-TAG: trailmap-scripted-tool-discovery.
    val candidateFiles = Files.walk(toolsDir.toPath()).use { stream ->
      stream.filter { Files.isRegularFile(it) }.map { it.toFile() }.toList()
    }
      .filter { it.name.endsWith(".yaml") }
      .filter { file -> OPERATIONAL_TOOL_YAML_SUFFIXES.none { file.name.endsWith(it) } }
      .filter { file ->
        // `canonicalFile` raises IOException on symlink loops and on some filesystem quirks
        // (Windows short-name resolution, broken intermediate symlink, etc.). Translate to
        // the typed EscapesTrailmapDirectory so the failure surfaces with the descriptor name
        // in the message rather than as an opaque NIO stack trace from inside `.filter { }`.
        val canonicalFile = try {
          file.canonicalFile
        } catch (e: IOException) {
          throw TrailblazeTrailmapBundleException.EscapesTrailmapDirectory(
            "Trailmap $trailmapLabel: scripted-tool descriptor candidate '${file.name}' under " +
              "<trailmap>/tools/ could not be canonicalized (likely a symlink loop or other " +
              "filesystem error): ${e.message}",
            cause = e,
          )
        }
        if (!canonicalFile.toPath().startsWith(canonicalTrailmapDir)) {
          throw TrailblazeTrailmapBundleException.EscapesTrailmapDirectory(
            "Trailmap $trailmapLabel: scripted-tool descriptor candidate '${file.name}' under " +
              "<trailmap>/tools/ resolves outside the trailmap directory (canonical path: " +
              "${canonicalFile.absolutePath}, trailmap at: $canonicalTrailmapDir). " +
              "Symlinked descriptors must stay inside the trailmap.",
          )
        }
        true
      }
      .sortedBy { it.name }
    val registry = linkedMapOf<String, ScriptedToolRegistryEntry>()
    val skipped = mutableListOf<File>()
    candidateFiles.forEach { toolFile ->
      // Per-descriptor decode wrapped in try/log/skip so a single malformed (or half-written
      // WIP) file under `<trailmap>/tools/` doesn't tank the entire trailmap at build time. Sibling
      // descriptors still register; any `target.tools:` reference that names a tool from the
      // skipped file surfaces downstream as UnknownScriptedToolName (which also gets a hint
      // pointing at the skipped file when the convention `<name>.yaml` is followed). See
      // lead-dev review #2 (round 2) and #I2 (round 3) for the rationale.
      val tool = try {
        decodeToolFile(yaml, toolFile)
      } catch (e: TrailblazeTrailmapBundleException.MalformedScriptedTool) {
        // Best-effort log via stderr so the warning is visible in Gradle / CLI output without
        // polluting stdout (CLI consumers contract stdout for structured output).
        System.err.println(
          "trailblaze: skipping malformed scripted-tool descriptor " +
            "${toolFile.absolutePath} (trailmap $trailmapLabel): ${e.message}. Sibling descriptors " +
            "still register; any `target.tools:` entry naming a tool from this file will fail " +
            "with UnknownScriptedToolName until the file is fixed.",
        )
        skipped += toolFile
        return@forEach
      }
      // Phase D (#3181): reject `.js` / `.mjs` / `.cjs` script references. TypeScript is the
      // only supported authoring language for the bundler's typed `.d.ts` codegen — the
      // schema-extractor can only statically analyse `.ts`. The check runs eagerly on every
      // discovered descriptor so a trailmap-author error fails at build time even if the
      // descriptor isn't referenced from `target.tools:`.
      //
      // NOT a SISTER-IMPL parity check: the runtime loader / daemon bundler / Gradle
      // generator all tolerate `.js` (Bun and tsx run both extensions natively). JS rejection
      // is a bundler-only authoring-time policy, not a runtime contract. Don't mirror this
      // into the other three discovery walks without explicit follow-up.
      //
      // HARD-FAIL by Phase D contract — do NOT wrap in the skip-and-log resilience used by
      // the `decodeToolFile` block above. A `.js` script is an author error that must surface
      // as a build failure rather than a silent stderr warning.
      rejectJsScriptIfPresent(toolFile, tool, trailmapId)
      // Each descriptor produces 1..N entries (each gets its own typed `client.tools.<name>`
      // surface). Multi-tool descriptors with `tools: [...]` register one entry per entry;
      // single-tool descriptors register one entry. The script source is shared across a
      // multi-tool file and is recorded once per entry via [sourceFile] — that's fine; the
      // downstream typed surface is per-tool-name, not per-file.
      data class Member(
        val name: String,
        val description: String?,
        val inputSchema: Map<String, BundlerScriptedToolProperty>,
      )
      val members: List<Member> = when {
        tool.tools != null -> tool.tools.map { entry ->
          Member(entry.name, entry.description, entry.inputSchema)
        }
        tool.name != null -> listOf(Member(tool.name, tool.description, tool.inputSchema))
        else -> {
          // Skip-and-log treatment for a descriptor missing both `name:` and `tools:`. A
          // typical WIP shape (`script: ./foo.ts` and nothing else) is invisible rather than
          // trailmap-fatal; `target.tools:` references surface as UnknownScriptedToolName.
          System.err.println(
            "trailblaze: skipping scripted-tool descriptor ${toolFile.absolutePath} " +
              "(trailmap $trailmapLabel) — must declare either a top-level `name:` (single-tool shape) " +
              "or `tools:` (multi-tool shape). Sibling descriptors still register.",
          )
          skipped += toolFile
          return@forEach
        }
      }
      members.forEach { member ->
        if (member.name.isBlank()) {
          throw TrailblazeTrailmapBundleException.BlankToolName(
            "Scripted tool ${toolFile.absolutePath} has a blank 'name' field. Tool names must " +
              "be non-empty and contain at least one non-whitespace character.",
          )
        }
        val previous = registry[member.name]
        if (previous != null) {
          throw TrailblazeTrailmapBundleException.DuplicateToolName(
            "Trailmap $trailmapLabel: two scripted-tool descriptors under <trailmap>/tools/ declare the " +
              "same tool name '${member.name}': '${previous.sourceFile.name}' and " +
              "'${toolFile.name}'. Tool names must be unique within a trailmap — rename one of " +
              "the descriptors' `name:` field (or, for a multi-tool descriptor, the offending " +
              "entry under `tools:`).",
          )
        }
        registry[member.name] = ScriptedToolRegistryEntry(
          sourceFile = toolFile,
          description = member.description,
          inputSchema = member.inputSchema,
        )
      }
    }
    return ScriptedToolDiscoveryResult(registry, skipped)
  }

  private fun describeAvailableNames(registry: Map<String, ScriptedToolRegistryEntry>): String {
    if (registry.isEmpty()) {
      return "No scripted-tool descriptors discovered under <trailmap>/tools/."
    }
    val names = registry.keys.sorted().joinToString(", ")
    return "Available tool names: [$names]."
  }

  /**
   * Renders the augmentation file. Output is a single block — declaration merging on
   * `TrailblazeToolMap` happens automatically because every trailmap contributes to the same
   * interface and TypeScript merges them across files.
   *
   * The trailing `export {}` is what makes this file a TS module rather than a global
   * script — required for `declare module "@trailblaze/scripting"` to be treated as
   * augmentation rather than re-declaration. Same pattern as the SDK's vendored
   * `built-in-tools.ts`.
   */
  private fun renderBindings(trailmapLabel: String, entries: List<ScriptedToolEntry>): String = buildString {
    appendLine("// GENERATED FILE. DO NOT EDIT.")
    appendLine("// Source: trailmap manifest $trailmapLabel (see 'sourcePath' on each entry).")
    appendLine("// Regenerate with: ./gradlew bundleTrailblazeTrailmap")
    appendLine("//")
    appendLine("// Augments the @trailblaze/scripting `TrailblazeToolMap` interface with one entry per")
    appendLine("// scripted tool available to this trailmap — its own `target.tools:` plus every scripted")
    appendLine("// tool inherited transitively through `dependencies:`. `client.callTool(name, args)`")
    appendLine("// gets autocomplete on tool names and type-checking on args.")
    appendLine("//")
    appendLine("// If two trailmaps declare a tool with the same name and both .d.ts files land in the")
    appendLine("// same tsconfig include scope, TypeScript surfaces a declaration-merging error. Scope")
    appendLine("// each trailmap's tsconfig to its own tools/ directory to avoid this — that's the default.")
    appendLine()
    appendLine("declare module \"@trailblaze/scripting\" {")
    appendLine("  interface TrailblazeToolMap {")
    entries.forEachIndexed { index, entry ->
      if (index > 0) appendLine()
      // Per-entry shape is shared with `WorkspaceClientDtsGenerator` via [renderToolMapEntry];
      // both files declaration-merge into the same `TrailblazeToolMap`, so they MUST
      // produce identical block shapes per tool.
      append(renderToolMapEntry(entry.toRendererEntry()))
    }
    appendLine("  }")
    appendLine("}")
    appendLine()
    appendLine("export {};")
  }

  private fun ScriptedToolEntry.toRendererEntry(): ToolMapEntry = ToolMapEntry(
    name = name,
    description = description,
    params = params.map { ToolMapParam(it.name, it.tsType, it.description, it.optional) },
    sourceAttribution = sourcePath,
  )

  /**
   * Discover `trailmap.yaml` files via NIO so we can opt OUT of symlink following. Kotlin's
   * default `walkTopDown` follows links, which can wedge the build on a cycle
   * (`trailmaps/foo -> trailmaps/foo/../foo`). NIO's `Files.walk` with no [java.nio.file.FileVisitOption]
   * varargs does NOT follow links — desired behavior here, since trailmap manifests should live
   * in the source tree they're authored in, not be reached via symlinks.
   */
  private fun discoverTrailmapFiles(): List<File> {
    val root: Path = trailmapsDir.toPath()
    return Files.walk(root).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.fileName?.toString() == "trailmap.yaml" }
        .map { it.toFile() }
        .sorted(compareBy { it.relativeTo(trailmapsDir).invariantSeparatorsPath })
        .toList()
    }
  }

  private fun decodeManifest(yaml: Yaml, trailmapFile: File, trailmapLabel: String): BundlerTrailmapManifest = try {
    yaml.decodeFromString(BundlerTrailmapManifest.serializer(), trailmapFile.readText())
  } catch (e: YamlException) {
    throw TrailblazeTrailmapBundleException.MalformedManifest(
      "Trailmap manifest $trailmapLabel is not a valid YAML object the bundler can parse: ${e.message}",
      e,
    )
  }

  /**
   * Reject any scripted-tool descriptor whose `script:` field references a JavaScript
   * source (`.js`/`.mjs`/`.cjs`). TypeScript is the only supported authoring language for
   * scripted tools; the bundler refuses to emit per-trailmap `tools.d.ts` for a trailmap that
   * still ships JS sources so an author's runtime (`bun`, the daemon-spawned subprocess)
   * and the typed-surface contract stay in lockstep.
   *
   * The typed-surface shape itself is derived from the descriptor YAML's `inputSchema:` /
   * `description:` — the bundler does not parse the script source. The TS-only policy
   * exists so the script the author edits in their IDE matches the language the runtime
   * loads; a mixed `.js`/`.ts` workspace makes the codegen + tsconfig contract ambiguous
   * (which file does `script: ./foo.js` refer to when both `foo.js` and `foo.ts` exist?).
   *
   * Checks the file once (not per-member of a multi-tool descriptor) — `script:` applies
   * to the whole file. The error message names the descriptor, the trailmap id, the offending
   * script path, and the rename hint so the author has one-click context for the fix.
   */
  private fun rejectJsScriptIfPresent(
    toolFile: File,
    tool: BundlerToolFile,
    trailmapId: String,
  ) {
    val script = tool.script ?: return
    val lower = script.lowercase()
    if (!(lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs"))) return
    // Best-effort name for the error message — single-tool descriptors expose `name:` at
    // the top level; multi-tool descriptors carry per-entry names under `tools:`. Fall
    // back to the descriptor's file path so the message stays useful even if the YAML is
    // malformed enough that neither name nor tools is present.
    val displayName: String = tool.name?.takeIf { it.isNotBlank() }
      ?: tool.tools?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
      ?: toolFile.nameWithoutExtension
    // The suffix-check above guarantees `script` ends with `.js`/`.mjs`/`.cjs`, so the
    // dot-bearing path is the only branch the helper ever takes.
    val tsHint: String = script.substringBeforeLast('.') + ".ts"
    throw TrailblazeTrailmapBundleException.JsToolFileNotAllowed(
      "Scripted tool '$displayName' in trailmap '$trailmapId' references '$script' — TypeScript is " +
        "the only supported authoring language. Rename '$script' → '$tsHint' and update the " +
        "descriptor's `script:` field.",
    )
  }

  private fun decodeToolFile(yaml: Yaml, toolFile: File): BundlerToolFile = try {
    yaml.decodeFromString(BundlerToolFile.serializer(), toolFile.readText())
  } catch (e: YamlException) {
    throw TrailblazeTrailmapBundleException.MalformedScriptedTool(
      "Scripted tool ${toolFile.absolutePath} is not a valid YAML object the bundler can parse: ${e.message}",
      e,
    )
  }

  companion object {
    const val GENERATED_DIR_NAME = ".trailblaze"
    const val GENERATED_FILE_NAME = "tools.d.ts"
    /** Parent of [GENERATED_DIR_NAME] in the per-trailmap output layout (`tools/.trailblaze/`). */
    const val GENERATED_PARENT_DIR_NAME = "tools"

    /** Trailmap-relative directory that owns both scripted-tool descriptors and operational tools. */
    private const val SCRIPTED_TOOLS_DIR = "tools"

    /**
     * Filename suffixes that mark an operational tool YAML rather than a scripted-tool
     * descriptor. The discovery scan in [buildScriptedToolRegistry] skips these so an
     * operational YAML accidentally living next to scripted tools isn't decoded as one.
     * Mirrors the same exclude list in `TrailblazeProjectConfigLoader` — keep both in
     * lockstep so the bundler's typed surface matches what the runtime resolves.
     */
    private val OPERATIONAL_TOOL_YAML_SUFFIXES = listOf(
      ".tool.yaml",
      ".shortcut.yaml",
      ".trailhead.yaml",
      ".waypoint.yaml",
    )

    /**
     * Shared kaml configuration for the bundler. `strictMode = false` so a trailmap manifest
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

/**
 * One scripted-tool entry — keyed by [name], with rendered TS [params].
 *
 * **Parallel shape.** This is the bundler-local input to the shared renderer; the
 * generator has its own `WorkspaceClientDtsGenerator.ToolEntry`, and both adapt to
 * [ToolMapEntry] via a per-emitter `toRendererEntry()` extension. The three shapes
 * carry slightly different nullability semantics for source attribution
 * (bundler `sourcePath` non-null, generator `null`, shared `nullable-with-blank-skip`).
 * Keep all three in sync when adding fields.
 */
data class ScriptedToolEntry(
  val name: String,
  val description: String?,
  val params: List<ScriptedToolParam>,
  val sourcePath: String,
)

/**
 * One parsed trailmap manifest with the on-disk anchors needed to re-resolve its scripted
 * tools later. Held in the `loadManifestsByTrailmapId` map so [TrailblazeTrailmapBundler] can
 * walk dependencies by id without re-parsing manifest YAML on every visit.
 */
internal data class ParsedTrailmap(
  val manifest: BundlerTrailmapManifest,
  val trailmapFile: File,
  val trailmapLabel: String,
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
 * ([TrailblazeTrailmapBundler] for build-time per-trailmap output and [WorkspaceClientDtsGenerator]
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
      throw TrailblazeTrailmapBundleException.InvalidInputSchema(
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

package xyz.block.trailblaze.scripting

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Daemon-init-time bundler for inline scripted tools declared in trailmap manifests.
 *
 * Auto-discovers every `.yaml` scripted-tool descriptor under `<trailmapDir>/tools/` (skipping operational
 * tool YAMLs by suffix), indexes them by their declared tool name, then resolves each entry
 * in `trailmap.target?.tools` — which now holds tool *names*, not file paths — against that
 * registry. For every resolved descriptor it reads the referenced `script:` source and runs
 * `esbuild` with the same flag set as the build-time
 * `BundleAuthorToolsTask` plugin (`build-logic/src/main/kotlin/TrailblazeAuthorToolBundleTasks.kt:267-278`).
 *
 * Output is cached by SHA-256 of the source `.ts` file bytes; a second call with
 * unchanged source returns the existing cached path without re-bundling. This
 * matches the "TS no longer needs to be valid JS" relaxation tracked in #2749 —
 * authors edit the `.ts`, the daemon picks up the change at restart, the bundler
 * produces a fresh cache entry, downstream consumers (Sub-PR-A3) pick up the new
 * bundle without any other plumbing.
 *
 * **Esbuild binary resolution is the caller's responsibility.** This class takes
 * the binary as a constructor argument and leaves discovery (PATH lookup,
 * configured location, env var, etc.) to the wiring code in Sub-PR-A3. The
 * build-time `defaultEsbuildBinary()` helper in the Gradle plugin is layout-aware
 * for the framework root and isn't appropriate for daemon-time use.
 */
class DaemonScriptedToolBundler(
  private val esbuildBinary: File,
  /**
   * The slim `@trailblaze/scripting` in-process entry (`sdks/typescript/src/in-process.ts`) esbuild
   * aliases the package to. The CALLER resolves this — INDEPENDENT of where [esbuildBinary] lives —
   * so the slim profile applies even when esbuild is resolved from PATH (Homebrew, `npm -g`) rather
   * than the SDK's own `node_modules` (see [xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry]).
   * When null, [inProcessSdkEntry] falls back to the legacy walk-up from [esbuildBinary] (which only
   * works when esbuild IS the SDK's bundled binary), then to the full SDK. Resolution being the
   * caller's responsibility mirrors how [esbuildBinary] itself is wired.
   */
  private val inProcessSdkEntryOverride: File? = null,
  private val cacheDir: File = File(
    TrailblazeDesktopUtil.getDefaultAppDataDirectory(),
    TrailblazeDesktopUtil.SCRIPTED_BUNDLES_CACHE_SUBDIR,
  ),
) {

  /**
   * Bundles every entry in each trailmap's `target.tools:` list. Returns a map from the tool's
   * declared `name` (in its YAML descriptor) to the cached bundle path on disk. Idempotent
   * on unchanged source bytes.
   *
   * **Test-only entry point.** The production session-start path
   * ([TrailblazeHostYamlRunner]) calls [bundleOne] per pre-resolved
   * [xyz.block.trailblaze.config.InlineScriptToolConfig] — the loader has already done the
   * `<trailmap>/tools/` discovery walk and emitted typed configs by then. This method exists
   * to pin the daemon-time parity contract with the loader's discovery walk (matching
   * directory scan + duplicate detection + symlink containment + skip-and-log + unknown-
   * name diagnostic) so behavioral drift between resolvers is caught by tests rather than
   * surfacing as a daemon-vs-loader mismatch at session start. SISTER-IMPL-TAG: trailmap-
   * scripted-tool-discovery.
   *
   * @param trailmaps Discovered trailmap manifests.
   * @param trailmapBaseDirs The on-disk base directory for each trailmap (so the bundler can scan
   *   `<baseDir>/tools/` for scripted-tool descriptors). A trailmap missing from this map
   *   contributes nothing to the result.
   */
  suspend fun bundleAll(
    trailmaps: List<TrailblazeTrailmapManifest>,
    trailmapBaseDirs: Map<TrailblazeTrailmapManifest, File>,
  ): Map<String, File> = withContext(Dispatchers.IO) {
    val result = LinkedHashMap<String, File>()
    for (trailmap in trailmaps) {
      val toolNames = trailmap.target?.tools.orEmpty()
      if (toolNames.isEmpty()) continue
      val baseDir = trailmapBaseDirs[trailmap] ?: continue
      // Detect duplicates inside `target.tools:` itself BEFORE the cross-trailmap collision check
      // below — otherwise listing the same name twice in one trailmap's `target.tools:` would trip
      // the cross-trailmap collision message which incorrectly blames a sibling trailmap.
      // SISTER-IMPL-TAG: trailmap-target-tools-dup-detection.
      val seenInTrailmap = mutableSetOf<String>()
      for (toolName in toolNames) {
        if (!seenInTrailmap.add(toolName)) {
          throw IOException(
            "Trailmap '${trailmap.id}': `target.tools:` lists '$toolName' more than once. " +
              "Each scripted-tool name must appear at most once in `target.tools:`.",
          )
        }
      }
      val discovery = discoverScriptedToolDescriptors(trailmap.id, baseDir)
      val registry = discovery.registry
      for (toolName in toolNames) {
        val match = registry[toolName]
          ?: throw IOException(
            buildString {
              append("Trailmap '${trailmap.id}': `target.tools:` references '$toolName' but no ")
              append("scripted-tool descriptor with that name was discovered under ")
              append("${File(baseDir, SCRIPTED_TOOLS_DIR).absolutePath}. ")
              append(describeAvailableNames(registry))
              // Mirrors the loader / build-time bundler / Gradle generator hint so an author
              // who triggers session start with a stale path-shaped `target.tools:` entry sees
              // the same migration nudge regardless of which entry point caught it.
              if (toolName.endsWith(".yaml") || toolName.contains('/')) {
                append(" Hint: '$toolName' looks like a file path; this field used to hold ")
                append("paths but now holds tool names — open the descriptor at that path and ")
                append("copy its `name:` field here.")
              }
              // Point at the skipped descriptor (if any) whose filename matches the
              // unknown name — see lead-dev round 3 #I2.
              val likelyCulprit = discovery.skipped.firstOrNull { it.name == "$toolName.yaml" }
              if (likelyCulprit != null) {
                append(" Note: descriptor '${likelyCulprit.absolutePath}' was skipped during ")
                append("discovery (see earlier log warning for the parse error). Fix that ")
                append("file to register the '$toolName' name.")
              } else if (discovery.skipped.isNotEmpty()) {
                append(" Note: ${discovery.skipped.size} other descriptor(s) under ")
                append("<trailmap>/tools/ were skipped during discovery (see earlier log ")
                append("warnings); one of them may have been intended to declare '$toolName'.")
              }
            },
          )
        val descriptor = match.descriptor
        val toolYamlFile = match.descriptorFile
        // Resolve `script:` relative to the descriptor YAML file's parent directory so the
        // implementation lives next to the descriptor inside the trailmap. Absolute paths pass
        // through unchanged. `Path.normalize()` collapses `./` and `../` segments via pure
        // string manipulation (no filesystem I/O, no IOException) so error messages embed
        // the clean form `trailmapDir/tools/foo.ts` rather than `trailmapDir/tools/./foo.ts`,
        // matching the loader's mcp_servers path-rewrite contract.
        val scriptFile = File(descriptor.script).let {
          if (it.isAbsolute) {
            it
          } else {
            File(toolYamlFile.parentFile, descriptor.script).toPath().normalize().toFile().absoluteFile
          }
        }
        requirePathInsideTrailmap(
          trailmapId = trailmap.id,
          baseDir = baseDir,
          path = scriptFile,
          rawPath = descriptor.script,
          kind = "tool '$toolName' script",
          rawIsAbsolute = File(descriptor.script).isAbsolute,
        )
        if (!scriptFile.isFile) {
          throw IOException(
            "Trailmap '${trailmap.id}': tool '$toolName' references script '${descriptor.script}' " +
              "(resolved to ${scriptFile.absolutePath}), but no such file exists.",
          )
        }
        // Bundle once per (script, name) pair. The cache is content-addressed on the script
        // bytes + tool name, so two `target.tools:` entries pointing at the same script via
        // a multi-tool descriptor produce distinct bundles for distinct names.
        val bundledFile = bundleOneInternal(scriptFile, toolName)
        // Fail loudly on duplicate tool names across trailmaps. A LinkedHashMap put would silently
        // overwrite the earlier entry, and the per-tool dispatch downstream would route to
        // whichever bundle won the race — confusing at best, broken at worst when the two tools
        // have different schemas. TrailblazeTrailmapBundler enforces the same uniqueness guarantee.
        val existing = result[toolName]
        if (existing != null) {
          throw IOException(
            "Duplicate scripted-tool name '$toolName' across trailmaps. " +
              "Each trailmap manifest's `target.tools:` entry must resolve to a globally unique " +
              "tool name. Previously bundled at ${existing.absolutePath}; conflicting source " +
              "is ${scriptFile.absolutePath}.",
          )
        }
        result[toolName] = bundledFile
      }
    }
    result
  }

  /** Pairs a [TrailmapScriptedToolFile] with the on-disk file it was decoded from. */
  private data class DescriptorRegistryEntry(
    val descriptorFile: File,
    val descriptor: TrailmapScriptedToolFile,
  )

  /**
   * Scans `<baseDir>/tools/` for scripted-tool descriptor YAMLs (direct children only,
   * operational suffixes excluded) and returns a name → (file, descriptor) registry. Both
   * single-tool and multi-tool descriptors register one entry per declared tool name.
   *
   * Duplicate names across files in the same trailmap throw with both contributing file names.
   *
   * SISTER IMPLEMENTATIONS — same algorithm lives in three other places, keep all four in
   * lockstep:
   *   - `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/project/TrailblazeProjectConfigLoader.kt`
   *     `discoverTrailmapScriptedTools` — runtime trailmap loader.
   *   - `trailblaze-trailmap-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/TrailblazeTrailmapBundler.kt`
   *     `buildScriptedToolRegistry` — build-time `.d.ts` augmentation generator.
   *   - `build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt`
   *     `buildTrailmapScriptedToolRegistry` — Gradle bundled-config generator.
   *
   * Search tag for grepping all four sister implementations at once (resilient against
   * future file moves): `SISTER-IMPL-TAG: trailmap-scripted-tool-discovery`.
   */
  /**
   * Discovery result: name-keyed registry plus the list of descriptor files that were
   * skipped because their decode failed. Plumbed downstream to UnknownScriptedToolName so
   * an author whose `target.tools:` references a skipped file's intended name gets pointed
   * at it directly. See lead-dev round 3 #I2.
   */
  private data class ScriptedToolDiscoveryResult(
    val registry: Map<String, DescriptorRegistryEntry>,
    val skipped: List<File>,
  )

  private fun discoverScriptedToolDescriptors(
    trailmapId: String,
    baseDir: File,
  ): ScriptedToolDiscoveryResult {
    val toolsDir = File(baseDir, SCRIPTED_TOOLS_DIR)
    if (!toolsDir.isDirectory) return ScriptedToolDiscoveryResult(emptyMap(), emptyList())
    // Canonical-path containment mirrors the loader's `TrailmapSource.readFilesystemSibling`
    // guarantee — a `<trailmap>/tools/foo.yaml` symlink that resolves outside the trailmap must be
    // rejected, not silently followed. Without this check the daemon-time bundler would
    // happily decode escape symlinks the runtime loader would refuse to read, surfacing as
    // load-vs-daemon drift at session start.
    val canonicalTrailmapDir = baseDir.canonicalFile.toPath()
    val candidateFiles = toolsDir.listFiles()
      .orEmpty()
      .filter { it.isFile && it.name.endsWith(".yaml") }
      .filter { file -> OPERATIONAL_TOOL_YAML_SUFFIXES.none { file.name.endsWith(it) } }
      .filter { file ->
        // Translate IOException from canonicalize (symlink loop, FS quirk) into a typed
        // failure with the descriptor name in the message rather than an opaque NIO stack
        // trace from inside `.filter { }`.
        val canonicalFile = try {
          file.canonicalFile
        } catch (e: IOException) {
          throw IOException(
            "Trailmap '$trailmapId': scripted-tool descriptor candidate '${file.name}' under " +
              "<trailmap>/tools/ could not be canonicalized (likely a symlink loop or other " +
              "filesystem error): ${e.message}",
            e,
          )
        }
        if (!canonicalFile.toPath().startsWith(canonicalTrailmapDir)) {
          throw IOException(
            "Trailmap '$trailmapId': scripted-tool descriptor candidate '${file.name}' under " +
              "<trailmap>/tools/ resolves outside the trailmap directory (canonical path: " +
              "${canonicalFile.absolutePath}, trailmap at: $canonicalTrailmapDir). " +
              "Symlinked descriptors must stay inside the trailmap.",
          )
        }
        true
      }
      .sortedBy { it.name }
    val registry = linkedMapOf<String, DescriptorRegistryEntry>()
    val skipped = mutableListOf<File>()
    for (toolYamlFile in candidateFiles) {
      // Per-descriptor decode wrapped in try/log/skip — a single malformed (or half-written
      // WIP) file under `<trailmap>/tools/` doesn't tank session start. Sibling descriptors still
      // register; any `target.tools:` entry that references a tool from the skipped file will
      // surface downstream as the unknown-name IOException. See lead-dev review #2 (round 2).
      // Catch only the YAML / serialization / shape failures author edits produce; let
      // CancellationException (from the surrounding `withContext(Dispatchers.IO)`),
      // OutOfMemoryError, and other VirtualMachineErrors propagate. A broad `catch (Exception)`
      // here would also swallow IOException from a file-system fault (permission flip mid-build,
      // disk eject) and mis-attribute it as an author-fixable malformed-descriptor warning.
      val descriptor = try {
        decodeDescriptor(toolYamlFile)
      } catch (e: com.charleskorn.kaml.YamlException) {
        Console.log(skippedMalformedMessage(toolYamlFile, trailmapId, e))
        skipped += toolYamlFile
        continue
      } catch (e: kotlinx.serialization.SerializationException) {
        Console.log(skippedMalformedMessage(toolYamlFile, trailmapId, e))
        skipped += toolYamlFile
        continue
      } catch (e: IllegalArgumentException) {
        // kaml's `Yaml.decodeFromString` raises IllegalArgumentException on a handful of shape
        // mismatches (missing required field, wrong scalar type for an enum). Same author-side
        // failure class as YamlException — log and skip rather than propagate.
        Console.log(skippedMalformedMessage(toolYamlFile, trailmapId, e))
        skipped += toolYamlFile
        continue
      }
      val multi = descriptor.tools
      val single = descriptor.name
      val declaredNames: List<String> = when {
        multi != null -> multi.map { it.name }
        single != null -> listOf(single)
        else -> {
          // Skip-and-log treatment for a descriptor missing both `name:` and `tools:`. A
          // typical WIP shape (`script: ./foo.ts` and nothing else) is invisible rather than
          // session-fatal; `target.tools:` references surface as UnknownScriptedToolName.
          Console.log(
            "Note: skipping scripted-tool descriptor ${toolYamlFile.absolutePath} (trailmap " +
              "'$trailmapId') — must declare either a top-level `name:` (single-tool shape) or " +
              "`tools:` (multi-tool shape). Sibling descriptors still register.",
          )
          skipped += toolYamlFile
          continue
        }
      }
      for (declaredName in declaredNames) {
        // Symmetric with the bundler's `BlankToolName` guard — see SISTER-IMPL-TAG: trailmap-
        // scripted-tool-discovery. `name: ""` decodes successfully but would register under
        // the empty key, masking author errors.
        if (declaredName.isBlank()) {
          throw IOException(
            "Trailmap '$trailmapId': scripted-tool descriptor '${toolYamlFile.absolutePath}' declares " +
              "a blank tool name. Tool names must be non-empty and contain at least one " +
              "non-whitespace character.",
          )
        }
        val previous = registry[declaredName]
        if (previous != null) {
          throw IOException(
            "Trailmap '$trailmapId': two scripted-tool descriptors under <trailmap>/tools/ declare the " +
              "same tool name '$declaredName': '${previous.descriptorFile.name}' and " +
              "'${toolYamlFile.name}'. Tool names must be unique within a trailmap.",
          )
        }
        registry[declaredName] = DescriptorRegistryEntry(toolYamlFile, descriptor)
      }
    }
    return ScriptedToolDiscoveryResult(registry, skipped)
  }

  private fun skippedMalformedMessage(
    toolYamlFile: File,
    trailmapId: String,
    cause: Throwable,
  ): String =
    "Note: skipping malformed scripted-tool descriptor ${toolYamlFile.absolutePath} " +
      "(trailmap '$trailmapId'): ${cause.message}. Sibling descriptors still register; any " +
      "`target.tools:` entry naming a tool from this file will fail with " +
      "UnknownScriptedToolName until the file is fixed."

  private fun describeAvailableNames(
    registry: Map<String, DescriptorRegistryEntry>,
  ): String = if (registry.isEmpty()) {
    "No scripted-tool descriptors discovered under <trailmap>/tools/."
  } else {
    "Available tool names: [${registry.keys.sorted().joinToString(", ")}]."
  }

  /**
   * Bundles a single source file. Used by [bundleAll] and exposed as a unit-test seam.
   * Honors the cache: a cache hit returns the existing bundle file without spawning esbuild.
   *
   * @param scriptPath user's `.ts` file (or `.js` — bundling treats them the same).
   * @param toolName the registered tool name. Used as both the named-export key the synthesized
   *   wrapper imports from the user's file AND the registry key under which the wrapper
   *   self-registers on `globalThis.__trailblazeTools`. Convention: the user's `.ts` exports a
   *   function whose name matches `toolName`.
   */
  suspend fun bundleOne(scriptPath: File, toolName: String): File = withContext(Dispatchers.IO) {
    bundleOneInternal(scriptPath, toolName)
  }

  private fun bundleOneInternal(scriptPath: File, toolName: String): File {
    if (!scriptPath.isFile) {
      throw IOException("Scripted-tool source not found: ${scriptPath.absolutePath}")
    }
    // Defense-in-depth: validate the tool name even though `InlineScriptToolConfig`'s init
    // block already enforces the same pattern at YAML decode time. The bundler can be
    // invoked from test fixtures or future programmatic paths that bypass the config-class
    // construction site, so re-checking here keeps the wrapper synthesis below safe in
    // isolation. Source-of-truth pattern lives on `InlineScriptToolConfig.TOOL_NAME_PATTERN`
    // so any future tightening is a one-place change.
    require(InlineScriptToolConfig.TOOL_NAME_PATTERN.matches(toolName)) {
      "Invalid scripted-tool name '$toolName' for ${scriptPath.absolutePath}: must match " +
        "${InlineScriptToolConfig.TOOL_NAME_PATTERN} (letters, digits, _, -, ., starting with a " +
        "letter or _). Update the per-tool YAML descriptor's `name:` field to use a supported " +
        "character set."
    }
    // esbuild's cwd is the wrapper's parent (= the script's dir), so metafile input paths — and the
    // dep manifest derived from them — are expressed relative to here. Used to resolve those paths
    // back to files when fingerprinting the dependency closure. A real trailmap tool always resolves
    // to an absolute file with a parent; fail loudly on the degenerate parent-less case rather than
    // silently resolving relative deps against the JVM cwd.
    val scriptDir = scriptPath.parentFile
      ?: throw IOException("Scripted-tool source has no parent directory: ${scriptPath.absolutePath}")

    // Two-level, content-addressed cache key (the ccache "direct mode" shape):
    //  - cheapKey identifies the ENTRY: SHA(source bytes + tool name + bundler-profile fingerprint).
    //    Source bytes so two tools pointing at the same .ts under different names get distinct
    //    entries; the profile fingerprint so a bundling-config change (slim alias, flag set, SDK
    //    entry content) invalidates — see [bundlerProfileFingerprint].
    //  - the per-entry dep MANIFEST ([depManifestFile]) records, from the last build, the set of
    //    files esbuild actually bundled (discovered via `--metafile`, NOT a hand-rolled resolver).
    //  - fullKey = SHA(cheapKey + fingerprint of those dep files' CURRENT bytes). Folding the dep
    //    content INTO the key keeps the cache content-addressed (a given fullKey always maps to
    //    identical bytes — preserving the concurrent-write safety the atomic rename relies on) AND
    //    busts when a shared imported module changes even though the importing tool's own bytes
    //    didn't. A self-contained tool has an empty dep set → fullKey is a pure function of cheapKey.
    val sourceBytes = scriptPath.readBytes()
    val cheapKey = sha256Hex(sourceBytes + toolName.toByteArray(Charsets.UTF_8) + bundlerProfileFingerprint)
    val depManifestFile = File(cacheDir, "$cheapKey.deps")
    // Cache hit: if we know this entry's dep set from a prior build, re-derive the fullKey from those
    // files' current bytes and return the artifact if it's present. A changed dep → different
    // fullKey → miss → rebuild below (which rewrites the manifest). The manifest can only go stale
    // when a dep's content changes, which itself changes the fullKey → miss → self-heals.
    readDepManifest(depManifestFile)?.let { depPaths ->
      val cached = File(cacheDir, "${fullCacheKey(cheapKey, depPaths, scriptDir)}.bundle.js")
      if (cached.isFile && cached.length() > 0L) {
        return cached
      }
    }
    // Sweep stale wrapper files left by previous abrupt JVM exits (SIGKILL, OOM, hard daemon
    // crash) — the `finally { wrapperFile.delete() }` below only runs on normal returns and
    // exception propagation, not on hard process termination. Without this, a crashed daemon
    // leaves `.trailblaze-wrapper-*.ts` files alongside the user's source where they can
    // accidentally end up in the user's commits.
    //
    // Run the sweep **once per (JVM, directory)** rather than on every bundle call.
    // Two concurrent bundle calls for scripts in the same directory previously raced —
    // worker A would create its wrapper, hand it to esbuild; worker B would then sweep
    // and delete A's wrapper out from under A's running esbuild, surfacing as
    // `[ERROR] Could not resolve ".../.trailblaze-wrapper-<sha>-<rand>.ts"`. (Observed
    // deterministically under N=2 parallel test workers.) Stale wrappers only originate
    // from *previous* JVMs that crashed before their `finally { delete() }` ran — those
    // are on disk before this JVM starts and never appear mid-flight. So a single sweep
    // per (JVM, directory) is sufficient.
    //
    // The dedup is implemented via [ConcurrentHashMap.computeIfAbsent] specifically
    // because losing callers **must block** until the winning caller's sweep
    // completes — they cannot race past the gate and start creating their own wrapper
    // while the winner is still mid-`delete()`, or the winner's `listFiles` would see
    // (and delete) the loser's in-flight wrapper. Using `Set.add()` as the gate
    // (an earlier revision of this fix) marked the directory as "swept" *before* the
    // sweep actually ran, leaving exactly this window open. `computeIfAbsent` runs the
    // lambda exactly once per key and synchronously blocks subsequent callers on the
    // same key until the lambda returns — closing the window without serializing
    // unrelated directories against each other.
    // Key on `canonicalFile`, not `absoluteFile`, so symlinked trailmap directories
    // collapse to a single sweep gate (this class already uses `.canonicalFile` for
    // its containment checks — same path-equality semantics here).
    //
    // The sweep lambda is wrapped in `runCatching` because per the
    // `ConcurrentHashMap.computeIfAbsent` Javadoc, if the mapping function throws,
    // the mapping is **not** established and the next caller re-runs it. We don't
    // want a transient `listFiles` I/O error (permission denied, etc.) to keep
    // re-triggering the sweep on every subsequent first-bundle. Individual delete
    // failures are already swallowed by the inner `runCatching` so wrapping the
    // outer block is purely defensive against `listFiles` itself throwing.
    // Cross-JVM guard for the multi-daemon case: the per-JVM `sweptParentDirs` dedup only
    // serializes sweeps WITHIN one daemon. When several daemons (separate JVMs) bundle in the
    // same source dir — e.g. one daemon per parallel CI copy — each JVM still sweeps once, and
    // those sweeps race across processes. So only delete wrappers OLDER than the esbuild
    // timeout: a peer's live wrapper is at most ~2 min old; a crash-leaked one is far older.
    val staleWrapperFloorMs = System.currentTimeMillis() - STALE_WRAPPER_MIN_AGE_MS
    scriptPath.parentFile?.canonicalFile?.let { parentDir ->
      sweptParentDirs.computeIfAbsent(parentDir) {
        runCatching {
          val swept = parentDir
            .listFiles { f ->
              f.name.startsWith(WRAPPER_FILENAME_PREFIX) && f.name.endsWith(".ts") &&
                f.lastModified() < staleWrapperFloorMs
            }
            ?.filter { runCatching { it.delete() }.getOrDefault(false) }
            ?.size
            ?: 0
          if (swept > 0) {
            Console.log(
              "[DaemonScriptedToolBundler] swept $swept stale wrapper file(s) in ${parentDir.absolutePath} " +
                "(left over from a previous JVM that exited before its finally{} could clean up)"
            )
          }
        }
        Unit
      }
    }
    // Concurrent-safe mkdir: under N parallel bundle calls hitting a cold cacheDir, two
    // threads can both observe `!isDirectory` and race into `mkdirs()` — the winner returns
    // true, the loser returns false (the dir now exists, so mkdirs refuses to create over
    // it). Without the post-`mkdirs()` recheck below, the loser would throw IOException
    // and fail the bundle even though the cache dir is fine. Recheck after `mkdirs()` so
    // a winner's creation satisfies all concurrent attempts.
    if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
      throw IOException("Could not create scripted-bundles cache dir: ${cacheDir.absolutePath}")
    }
    if (!esbuildBinary.isFile) {
      throw IOException(
        "esbuild binary not found at ${esbuildBinary.absolutePath}. The daemon needs a " +
          "working esbuild executable to bundle inline scripted tools.",
      )
    }
    // Write esbuild output to a tmp file and atomically rename into place on success.
    // Without this, an esbuild process killed mid-write (Ctrl-C, OOM, daemon SIGKILL)
    // would leave a partial `<sha>.bundle.js` behind that the next session's cache hit
    // (`outFile.isFile && outFile.length() > 0L`) would happily return as a "good"
    // bundle. Using a unique tmp file per attempt also makes concurrent writes from two
    // daemons targeting the same SHA benign (last-writer-wins on the rename, content is
    // identical because content-addressed by SHA-256).
    // Synthesize a wrapper entry file co-located with the user's script. The wrapper:
    //   1. Imports the named export `<toolName>` from the user's script.
    //   2. Builds a `client` object that wraps `__trailblazeCall` (the host-installed async
    //      JS binding documented on `QuickJsToolHost.HOST_CALL_BINDING`). The user's handler
    //      receives this `client` as its third arg and uses it for `client.callTool(...)`
    //      composition with host-side Kotlin tools.
    //   3. Self-registers under `globalThis.__trailblazeTools[toolName]` with a `handler`
    //      that forwards `(args, ctx)` to the user's function with `client` injected. This
    //      is the registry shape `QuickJsToolHost.callTool` looks up at dispatch time.
    //
    // Without this wrapper, an `esbuild --bundle --format=iife` of the user's `.ts` produces
    // a self-contained IIFE that runs the user's module-init code but never registers the
    // exported function — the host then fails dispatch with `Tool not registered: <name>`.
    // This is the daemon-time analog of the build-time `BundleAuthorToolsTask`'s
    // SDK-aliasing approach, simplified for the named-export shape #2750 introduced.
    // Per-attempt unique filename via createTempFile so two concurrent bundling attempts
    // for the same SHA (parallel sessions, two daemons racing) can't collide on the wrapper
    // file — without this, one process's `finally { delete() }` could pull the file out
    // from under the other process's esbuild invocation, surfacing as intermittent
    // "could not resolve" or partial-read failures. Co-located with the user's script so
    // esbuild's relative-import resolution finds the named export.
    val wrapperFile = File.createTempFile("$WRAPPER_FILENAME_PREFIX$cheapKey-", ".ts", scriptPath.parentFile)
    wrapperFile.writeText(synthesizeWrapper(scriptPath, toolName))
    val tmpFile = File.createTempFile("$cheapKey.", ".bundle.js.tmp", cacheDir)
    // esbuild writes the exact set of files it bundled here (`--metafile`); we read it to fingerprint
    // the dependency closure (and persist it as the manifest) instead of re-deriving imports ourselves.
    val metafileFile = File.createTempFile("$cheapKey.", ".meta.json", cacheDir)
    try {
      runEsbuild(entry = wrapperFile, output = tmpFile, metafileOut = metafileFile, userSource = scriptPath)
      if (!tmpFile.isFile || tmpFile.length() == 0L) {
        throw IOException(
          "esbuild reported success but ${tmpFile.absolutePath} is missing or empty " +
            "(source: ${scriptPath.absolutePath}).",
        )
      }
      // Discover the dependency closure from esbuild's authoritative metafile, then content-address
      // the artifact by (cheapKey + those files' current bytes) so a later edit to any of them busts
      // the cache. The bundle is renamed into place FIRST, then the manifest is written — a crash in
      // between leaves the bundle un-findable (next call sees no manifest → rebuilds) rather than
      // serving a bundle whose dependency set we can no longer validate.
      val depPaths = parseMetafileDepPaths(metafileFile)
      // Content-addressed by fullKey: as dependencies change, new fullKeys are written and prior
      // <fullKey>.bundle.js entries are orphaned. The cache has no GC (true of this content-addressed
      // cache before this change too) — acceptable for a dev-time cache; revisit if cacheDir growth
      // ever becomes a problem.
      val outFile = File(cacheDir, "${fullCacheKey(cheapKey, depPaths, scriptDir)}.bundle.js")
      atomicMove(tmpFile, outFile)
      writeDepManifest(depManifestFile, depPaths)
      return outFile
    } catch (e: Throwable) {
      // Clean up the tmp bundle on any failure path so the cache dir doesn't accumulate
      // half-built bundles. Best-effort — if delete fails the file is still tmp-suffixed
      // and won't be picked up as a cache hit.
      tmpFile.delete()
      throw e
    } finally {
      // Always delete the synthesized wrapper + throwaway metafile. The wrapper is co-located with
      // the user's script (so esbuild's relative-import resolution finds the named export), so a leak
      // would clutter the user's source tree — best-effort cleanup keeps the tree tidy.
      wrapperFile.delete()
      metafileFile.delete()
    }
  }

  /**
   * Render the synthetic wrapper TS source for [toolName] sourced from [scriptPath]. The
   * relative import resolves the user's named export when esbuild walks the wrapper as the
   * entry point (the wrapper is co-located with the user's script in [bundleOneInternal]).
   *
   * The wrapper body — the `__client` callTool/Proxy shim, `__normalizeResult`, and the
   * `globalThis.__trailblazeTools` registration — comes from the ONE committed template,
   * `sdks/typescript/tools/in-process-wrapper-template.mjs`, read from the classpath via
   * [InProcessScriptedToolWrapperTemplate]. This is the daemon-time SINGLE-export form: it
   * imports the user file as a namespace, looks up the YAML-declared [toolName] export, and
   * registers only that one handler. The build-time bundlers
   * (`BundleAuthorToolsTask.synthesizeInProcessScriptedToolWrapper` in build-logic and
   * `:trailblaze-common`'s framework bundler) render the SAME template with an empty prelude
   * and the multi-export enumeration footer. Keeping the JS in one file is what retired the
   * old three-way SISTER-IMPL-TAG duplication.
   *
   * **Tool names with non-identifier characters.** Tool names can contain hyphens
   * (`clock-android-launchApp`) — `WorkspaceClientDtsGenerator` / `TrailblazeTrailmapBundler`
   * already accept them. A direct `import { foo-bar as __h } from "..."` is invalid TS
   * because hyphens aren't legal identifiers. Workaround: namespace import, then
   * bracket-access the export by string key. Bracket access works for any string,
   * including hyphens / dots / digits-leading. The prelude's runtime check also turns a
   * misspelled export name into a clear error instead of a `TypeError: undefined is not
   * a function` at first dispatch.
   *
   * **Failure propagation** (encoded in the shared template's `__client`): `__trailblazeCall`
   * is installed as an async function on QuickJS by the host (see
   * `QuickJsToolHost.HOST_CALL_BINDING`) and reports execution failures as a JSON envelope, NOT
   * a transport exception — either `{isError:true, error:...}` (from
   * `SessionScopedHostBinding.errorEnvelope`) or a `type` containing "Error" (the serialized
   * `TrailblazeToolResult.Error.*` discriminated union). The shim detects both and throws so
   * authors get the fetch/`await`-style try/catch contract instead of silently proceeding
   * against state an unchecked error envelope never produced.
   */
  internal fun synthesizeWrapper(scriptPath: File, toolName: String): String {
    val fileName = scriptPath.name
    val toolNameLiteral = jsStringLiteral(toolName)
    val header = buildString {
      appendLine("// Synthetic entry generated by DaemonScriptedToolBundler.")
      appendLine("// Imports the author's `$toolName` named export from `$fileName`,")
      appendLine("// builds a `client` shim over the host's `__trailblazeCall` binding, and")
      appendLine("// registers a handler on `globalThis.__trailblazeTools[\"$toolName\"]` so")
      appendLine("// QuickJsToolHost.callTool can dispatch it.")
    }
    // Single-export prelude: bracket-access the YAML-declared export by string key (tolerates
    // hyphens/dots) and fail loudly when it isn't a function. The trailing blank line is part of
    // the token-line replacement so the daemon form's "import / blank / prelude / blank / client"
    // layout matches what the previous hand-built string emitted. The multi-export Gradle forms
    // pass an empty prelude, removing the whole token line.
    val prelude = buildString {
      appendLine("const __userHandler = __userModule[$toolNameLiteral];")
      appendLine("if (typeof __userHandler !== \"function\") {")
      appendLine("  throw new Error(")
      appendLine("    \"Scripted tool \" + $toolNameLiteral + \" must export a function with that exact name. \" +")
      appendLine("    \"Found: \" + (typeof __userHandler) + \". Check the per-tool YAML descriptor's `name:` matches a named export in `$fileName`.\"")
      appendLine("  );")
      appendLine("}")
      appendLine()
    }
    val registration = buildString {
      appendLine("globalThis.__trailblazeTools[$toolNameLiteral] = {")
      appendLine("  handler: async (args, ctx) => {")
      appendLine("    const result = await __userHandler(args, ctx, __client);")
      appendLine("    return __normalizeResult(result);")
      appendLine("  },")
      appendLine("};")
    }
    return InProcessScriptedToolWrapperTemplate.render(
      header = header,
      importSource = "./$fileName",
      prelude = prelude,
      registration = registration,
    )
  }

  // QuickJS on-device runtime cannot resolve `node:process`. The MCP SDK's stdio transport
  // calls `require("node:process")` in a constructor default; substituting a throw-only
  // shim at bundle time strips that import from the per-tool IIFE. The on-device runner
  // never instantiates the shim — `pickTransport()` returns the in-process callback
  // transport before any stdio fallback could fire — so a runtime `Error` body is fine.
  // The shim is written once into `cacheDir` and reused across every per-tool bundle.
  private val onDeviceStdioStubFile: File by lazy {
    File(cacheDir, "_ondevice-stdio-stub.ts").apply {
      parentFile.mkdirs()
      writeText(
        "/* GENERATED by DaemonScriptedToolBundler — esbuild --alias target for the " +
          "on-device QuickJS path. */\n" +
          "export class StdioServerTransport { " +
          "constructor() { throw new Error(\"StdioServerTransport unavailable on-device\"); } }\n",
      )
    }
  }

  /**
   * The slim `@trailblaze/scripting` in-process entry (`sdks/typescript/src/in-process.ts`) that
   * esbuild aliases `@trailblaze/scripting` to. This is the daemon-time IN-PROCESS bundler — tools
   * it bundles run in QuickJS via the synthesized wrapper, never as an MCP server — so resolving
   * the SDK to the typed-only slim entry (no `run`/MCP, no eager ajv) keeps each per-tool bundle
   * KB-scale instead of inlining the full ~1.2 MB SDK. Subprocess tools (`runtime: subprocess`)
   * never reach this bundler; they go through `InlineScriptToolServerSynthesizer` against the full
   * SDK.
   *
   * Resolution order:
   *  1. [inProcessSdkEntryOverride] — what the caller resolved INDEPENDENT of the esbuild binary
   *     location (the correct, robust path; production always supplies this).
   *  2. Legacy fallback: walk up from [esbuildBinary] to the `sdks/typescript` package dir and take
   *     `src/in-process.ts`. This ONLY works when esbuild is the SDK's bundled binary — when esbuild
   *     came from PATH (Homebrew, `npm -g`) the walk-up lands in an unrelated tree and finds nothing.
   *     That gap is exactly why the override exists; this remains for callers (e.g. tests) whose
   *     esbuild IS in-tree and that don't bother resolving the entry separately.
   *
   * Null if neither yields a file; the alias is then omitted and `@trailblaze/scripting` resolves
   * from node_modules (the full ~1.2 MB SDK) — a heavier bundle, but no failure. Logged so it isn't
   * silent.
   */
  private val inProcessSdkEntry: File? by lazy {
    // A caller-supplied override that ISN'T a readable file (resolved-then-deleted, or a misconfigured
    // TRAILBLAZE_SDK_DIR) is dropped — but say so explicitly, else the not-found message below ("no
    // usable caller-supplied entry") understates that one was supplied and reads as a no-op during triage.
    inProcessSdkEntryOverride?.takeUnless { it.isFile }?.let {
      Console.log(
        "[DaemonScriptedToolBundler] caller-supplied slim in-process SDK entry ${it.absolutePath} is not " +
          "a readable file — ignoring it and falling back to the esbuild walk-up.",
      )
    }
    val resolved = inProcessSdkEntryOverride?.takeIf { it.isFile } ?: legacyInProcessSdkEntryFromEsbuildWalkup()
    if (resolved != null) {
      // Positive breadcrumb so an operator can confirm the slim profile took effect (this fix's whole
      // point) without inspecting bundle sizes. Suppressed in CLI quiet mode like the rest.
      Console.log(
        "[DaemonScriptedToolBundler] slim in-process SDK entry resolved at ${resolved.absolutePath}; " +
          "on-device bundles use the slim @trailblaze/scripting profile (no full MCP SDK).",
      )
    } else {
      Console.log(
        "[DaemonScriptedToolBundler] slim in-process SDK entry (sdks/typescript/src/in-process.ts) not " +
          "found (no usable caller-supplied entry, and the walk-up from esbuild at ${esbuildBinary.absolutePath} " +
          "found no SDK tree). `@trailblaze/scripting` will resolve from node_modules (full SDK) — bundles " +
          "will be heavier. Expected only when the SDK source isn't reachable (e.g. an installed CLI).",
      )
    }
    resolved
  }

  /**
   * Legacy fallback for [inProcessSdkEntry]: walk up from the esbuild binary to the `sdks/typescript`
   * package dir and take `src/in-process.ts`. Only finds the entry when esbuild IS the SDK's bundled
   * binary — when esbuild came from PATH (Homebrew, `npm -g`) it lands in an unrelated tree and finds
   * nothing. That gap is why [inProcessSdkEntryOverride] exists; this remains for callers (e.g. tests)
   * whose esbuild is in-tree and that don't resolve the entry separately.
   */
  private fun legacyInProcessSdkEntryFromEsbuildWalkup(): File? {
    var dir: File? = esbuildBinary.parentFile
    while (dir != null) {
      if (dir.name == "typescript" && dir.parentFile?.name == "sdks") {
        return File(dir, "src/in-process.ts").takeIf { it.isFile }
      }
      dir = dir.parentFile
    }
    return null
  }

  /**
   * Fingerprint of the bundling profile, mixed into the content-addressed cache key so a change to
   * HOW we bundle invalidates stale entries. Without it, a tool cached as a full-SDK bundle BEFORE
   * the slim `@trailblaze/scripting` alias landed would keep being served from the `outFile.isFile`
   * early return and never re-slim until its source bytes changed. (Flagged by an automated PR
   * review on #3838.)
   *
   * Combines a manual version token — bump [BUNDLER_PROFILE_VERSION] whenever the esbuild flag set
   * changes in a way that affects output — with the in-process SDK entry's bytes (so editing
   * `in-process.ts` auto-invalidates) AND the wrapper template's bytes (so editing
   * `in-process-wrapper-template.mjs` auto-invalidates too — the daemon renders the wrapper from
   * that template, so a template fix must bust stale cached bundles even when the tool source is
   * unchanged; the Gradle bundlers get this for free by declaring the template as a task input).
   * Computed once per bundler instance.
   */
  private val bundlerProfileFingerprint: ByteArray by lazy {
    val versionToken = BUNDLER_PROFILE_VERSION.toByteArray(Charsets.UTF_8)
    val sdkBytes = inProcessSdkEntry?.let { runCatching { it.readBytes() }.getOrNull() } ?: ByteArray(0)
    val templateBytes = runCatching {
      InProcessScriptedToolWrapperTemplate.rawTemplate.toByteArray(Charsets.UTF_8)
    }.getOrDefault(ByteArray(0))
    sha256Hex(versionToken + sdkBytes + templateBytes).toByteArray(Charsets.UTF_8)
  }

  /**
   * The content-addressed cache key: `SHA(cheapKey + dependency-content fingerprint)`. Folding the
   * dependency closure's CURRENT content into the key keeps the cache content-addressed (a given key
   * always maps to identical bytes — preserving the concurrent-write safety [atomicMove] relies on)
   * while busting whenever a shared imported module changes even though the importing tool's own
   * bytes did not. A self-contained tool has an empty [depPaths] → an empty fingerprint → the fullKey
   * is a pure function of the cheapKey.
   */
  private fun fullCacheKey(cheapKey: String, depPaths: List<String>, scriptDir: File): String =
    sha256Hex(cheapKey.toByteArray(Charsets.UTF_8) + depFingerprint(depPaths, scriptDir))

  /**
   * SHA-256 (as ASCII-hex bytes) over the CURRENT bytes of [depPaths], each prefixed by its path and
   * taken in sorted order for determinism; an EMPTY array when [depPaths] is empty. [depPaths] are
   * the manifest entries esbuild recorded, relative to [scriptDir] (esbuild's cwd); an absolute entry
   * is used as-is. A path that no longer resolves contributes empty content, so deleting a dependency
   * still changes the key.
   */
  private fun depFingerprint(depPaths: List<String>, scriptDir: File): ByteArray {
    if (depPaths.isEmpty()) return ByteArray(0)
    val acc = ByteArrayOutputStream()
    for (path in depPaths.sorted()) {
      // Resolve a relative metafile path against the script dir (esbuild's cwd); absolute paths are
      // used as-is. The path is always folded into the hash; the file's content is added when it
      // resolves to a readable file (a deleted dep → empty content → still changes the key).
      val file = File(path).let { if (it.isAbsolute) it else File(scriptDir, path) }
      acc.write(path.toByteArray(Charsets.UTF_8))
      acc.write(0)
      acc.write(
        try {
          file.readBytes()
        } catch (_: IOException) {
          ByteArray(0)
        },
      )
      acc.write(0)
    }
    return sha256Hex(acc.toByteArray()).toByteArray(Charsets.UTF_8)
  }

  /**
   * Reads the dependency manifest written by the previous build of this entry — the files esbuild
   * actually bundled, one path per line (see [writeDepManifest]) — or null when no manifest exists
   * yet (first build) or it can't be read. Paths are relative to the script dir (esbuild's cwd),
   * exactly as the metafile expressed them.
   */
  private fun readDepManifest(manifestFile: File): List<String>? {
    if (!manifestFile.isFile) return null
    return try {
      manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: IOException) {
      null
    }
  }

  /**
   * Atomically writes the dependency manifest (sorted, one path per line) for an entry. tmp-then-
   * rename so a crash mid-write can't leave a half-written manifest a later cache check would misread.
   */
  private fun writeDepManifest(manifestFile: File, depPaths: List<String>) {
    val tmp = File.createTempFile("${manifestFile.name}.", ".tmp", manifestFile.parentFile)
    try {
      tmp.writeText(depPaths.sorted().joinToString("\n"))
      atomicMove(tmp, manifestFile)
    } finally {
      tmp.delete()
    }
  }

  /**
   * Extracts the set of files esbuild actually bundled from its `--metafile` JSON: every key under
   * `inputs`, EXCLUDING the synthesized wrapper entry (an ephemeral temp file — see
   * [WRAPPER_FILENAME_PREFIX]) and anything under `node_modules` (third-party deps are lockfile-
   * pinned and would bloat the per-build hashing; the bundler-profile fingerprint already covers
   * SDK/toolchain changes). Paths are returned verbatim from the metafile — relative to esbuild's
   * cwd (the script dir), portable across checkouts of the same repo layout. A malformed/unreadable
   * metafile yields an empty list (degrading to entry-only keying) rather than throwing.
   *
   * Using esbuild's own resolved input set is what makes the cache key track EXACTLY the files the
   * real bundle depends on — relative imports transitively, plus tsconfig `paths` aliases,
   * `package.json` `exports`, the `.js`→`.ts` rewrite, asset loaders, and the SDK's own transitive
   * sources — with no hand-rolled resolver to drift from esbuild. ([ScriptedToolImportAnalyzer] walks
   * the same metafile shape for a different purpose: host-only dependency detection.)
   *
   * `internal` for direct unit testing of the filtering, without driving a full esbuild bundle.
   */
  internal fun parseMetafileDepPaths(metafileFile: File): List<String> {
    val inputKeys = try {
      JSON_LENIENT.parseToJsonElement(metafileFile.readText())
        .jsonObject["inputs"]
        ?.jsonObject
        ?.keys
        ?: emptySet()
    } catch (_: Throwable) {
      Console.log(
        "[DaemonScriptedToolBundler] could not parse esbuild metafile ${metafileFile.absolutePath}; " +
          "the bundle cache will key on the tool source only until the next successful build",
      )
      return emptyList()
    }
    return inputKeys
      .filter { path ->
        // The wrapper temp file is always co-located with the script, so a basename match suffices;
        // node_modules can sit at any depth, so match it as a path SEGMENT (not a substring, which
        // would also drop a legitimate directory merely containing "node_modules" in its name).
        !path.substringAfterLast('/').startsWith(WRAPPER_FILENAME_PREFIX) &&
          !path.split('/').contains("node_modules")
      }
      .sorted()
  }

  /**
   * Atomic rename of [src] onto [dst]. ATOMIC_MOVE may fall back to a copy on filesystems that don't
   * support rename semantics (rare on the OSes we run); REPLACE_EXISTING covers the concurrent-write
   * race where another daemon produced the same content-addressed file first (identical bytes, so the
   * overwrite is benign).
   */
  private fun atomicMove(src: File, dst: File) {
    try {
      java.nio.file.Files.move(
        src.toPath(),
        dst.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      java.nio.file.Files.move(
        src.toPath(),
        dst.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private fun runEsbuild(entry: File, output: File, metafileOut: File, userSource: File) {
    // Followup #2749: unify esbuild flags with BundleAuthorToolsTask.
    // The flag set below mirrors `BundleAuthorToolsTask:267-278`. Risk #4 in the
    // 2749 plan documents that duplicating these constants is acceptable for A1;
    // a follow-up will extract a shared util consumed by both the Gradle plugin
    // and this daemon-time bundler so flag changes can't drift between them.
    val argv = buildList {
      add(esbuildBinary.absolutePath)
      add(entry.absolutePath)
      add("--bundle")
      add("--platform=neutral")
      add("--format=iife")
      add("--target=es2020")
      add("--main-fields=module,main")
      // Emit the metafile: esbuild records the exact set of input files it resolved + bundled, which
      // [parseMetafileDepPaths] reads to fingerprint the dependency closure for the cache key. This is
      // the authoritative dependency list (esbuild's own resolver), so the cache tracks precisely the
      // files the real bundle depends on — no hand-rolled import resolution to drift from esbuild.
      add("--metafile=${metafileOut.absolutePath}")
      add("--external:node:process")
      // Slim in-process profile: resolve `@trailblaze/scripting` to the typed-only slim entry so a
      // tool importing it doesn't drag the full ~1.2 MB MCP SDK into its on-device bundle. Omitted
      // (→ node_modules full-SDK resolution) only when the slim entry can't be located.
      inProcessSdkEntry?.let { add("--alias:@trailblaze/scripting=${it.absolutePath}") }
      add("--alias:@modelcontextprotocol/sdk/server/stdio.js=${onDeviceStdioStubFile.absolutePath}")
      add("--outfile=${output.absolutePath}")
    }
    val proc = ProcessBuilder(argv)
      .directory(entry.parentFile)
      .redirectErrorStream(true)
      .start()
    // 2 minutes mirrors `BundleAuthorToolsTask` — esbuild itself is fast on a typical
    // single-tool entry but we keep the same upper bound for parity.
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      val partialLog = drainProcessOutput(proc)
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
      throw IOException(
        "esbuild did not finish within 2 minutes bundling scripted-tool source " +
          "${userSource.absolutePath} (synthesized wrapper: ${entry.absolutePath}).\n" +
          "esbuild stderr/stdout (combined):\n$partialLog",
      )
    }
    val log = drainProcessOutput(proc)
    if (proc.exitValue() != 0) {
      // The author cares about THEIR source path, not the synthesized wrapper. Surface both
      // — the wrapper path is useful when an esbuild error references a line in the wrapper
      // (the relative `import { ... }` line, the `client` shim, etc.). Drop a hint to clean
      // up the wrapper if the failure leaves it behind so the author doesn't accidentally
      // commit it.
      throw IOException(
        "esbuild failed (exit ${proc.exitValue()}) bundling scripted-tool source " +
          "${userSource.absolutePath}.\n" +
          "Synthesized wrapper entry was ${entry.absolutePath} — line/column references in the " +
          "esbuild output below may point at the wrapper rather than your source.\n" +
          "esbuild stderr/stdout (combined):\n$log",
      )
    }
  }

  private fun drainProcessOutput(proc: Process): String =
    try {
      proc.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Throwable) {
      ""
    }

  private fun decodeDescriptor(yamlFile: File): TrailmapScriptedToolFile =
    yaml.decodeFromString(TrailmapScriptedToolFile.serializer(), yamlFile.readText())

  /**
   * Canonical-startsWith containment check applied to per-tool `script:` paths. Mirrors
   * the load-time loader's `resolveMcpServerScripts` check — relative paths must resolve
   * strictly under the trailmap directory once symlinks are canonicalized away. Absolute paths
   * bypass (consistent with the loader's `mcp_servers:` behavior and the contract
   * documented on [TrailmapScriptedToolFile.script]).
   */
  private fun requirePathInsideTrailmap(
    trailmapId: String,
    baseDir: File,
    path: File,
    rawPath: String,
    kind: String,
    rawIsAbsolute: Boolean,
  ) {
    if (rawIsAbsolute) return
    val baseDirCanonical = try {
      baseDir.canonicalFile
    } catch (e: IOException) {
      throw IOException(
        "Trailmap '$trailmapId': failed to canonicalize trailmap directory ${baseDir.absolutePath}: ${e.message}",
        e,
      )
    }
    val pathCanonical = try {
      path.canonicalFile
    } catch (e: IOException) {
      throw IOException(
        "Trailmap '$trailmapId': failed to canonicalize $kind '$rawPath' → ${path.absolutePath}: ${e.message}",
        e,
      )
    }
    if (!pathCanonical.toPath().startsWith(baseDirCanonical.toPath())) {
      throw IOException(
        "Trailmap '$trailmapId': $kind '$rawPath' resolves outside the trailmap directory " +
          "(resolved to ${pathCanonical.absolutePath}, trailmap at ${baseDirCanonical.absolutePath}). " +
          "Relative paths must stay inside the trailmap.",
      )
    }
  }

  /**
   * Encode an arbitrary string as a JS string literal. Just `\` and `"` need escaping for
   * a typical tool name (declared in YAML, restricted to a sane character set); the few
   * other code points that legally appear in a JS string literal but aren't bracket-safe
   * (newlines, U+2028/U+2029) are not realistic in tool names declared via YAML, so we
   * keep this minimal. If a future tool-name validator widens the allowed character set,
   * extend this in lockstep.
   */
  private fun jsStringLiteral(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
  }

  companion object {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /** Lenient JSON reader for esbuild's `--metafile` output — see [parseMetafileDepPaths]. */
    private val JSON_LENIENT = Json { ignoreUnknownKeys = true }

    /**
     * Version token folded into the per-tool cache key via [bundlerProfileFingerprint]. Bump this
     * whenever the esbuild flag set / bundling profile changes in a way that affects output, so
     * existing content-addressed cache entries (keyed on source + tool name + this fingerprint)
     * invalidate instead of serving a stale bundle. `v2` = the slim `@trailblaze/scripting` alias.
     */
    private const val BUNDLER_PROFILE_VERSION = "v2-slim-inprocess-alias"

    /** Trailmap-relative directory that owns scripted-tool descriptor YAMLs. */
    private const val SCRIPTED_TOOLS_DIR = "tools"

    /**
     * Filename suffixes that mark an operational tool YAML rather than a scripted-tool
     * descriptor. Mirrored from `TrailblazeProjectConfigLoader` so the daemon and loader
     * agree on which files in `<trailmap>/tools/` are scripted-tool descriptors.
     */
    private val OPERATIONAL_TOOL_YAML_SUFFIXES = listOf(
      ".tool.yaml",
      ".shortcut.yaml",
      ".trailhead.yaml",
      ".waypoint.yaml",
    )

    /**
     * Filename prefix for synthesized wrapper `.ts` files we drop next to the user's script.
     * Used both at write-time ([File.createTempFile] prefix) and at sweep-time (sibling
     * cleanup of files left by abrupt JVM exits) — keeping the constant in one place means
     * the two stay in sync.
     */
    internal const val WRAPPER_FILENAME_PREFIX: String = ".trailblaze-wrapper-"

    /**
     * Minimum age before the stale-wrapper sweep deletes a `.trailblaze-wrapper-*.ts`. Must
     * exceed the esbuild bundle timeout (2 min, see [runEsbuild]) so that when several daemons
     * (separate JVMs) bundle in one source dir, no daemon sweeps a live peer's wrapper.
     */
    private val STALE_WRAPPER_MIN_AGE_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /**
     * Map of script-parent directories this JVM has already swept for stale wrapper
     * files. Used to ensure the sweep at the top of [bundleOneInternal] runs **once
     * per (JVM, directory)** instead of every bundle call. See the kdoc at the
     * callsite for the concurrency race the previous every-call sweep produced.
     *
     * The map is consulted exclusively via [ConcurrentHashMap.computeIfAbsent], whose
     * documented contract — "the entire method invocation is performed atomically …
     * Some attempted update operations on this map by other threads may be blocked
     * while computation is in progress" — provides the per-key serialization the fix
     * relies on: the winning thread executes the sweep lambda; every other thread
     * calling `computeIfAbsent` on the same directory key blocks inside that call
     * until the lambda returns. Without this blocking, a loser thread could observe
     * "already swept" and continue past the gate while the winner is still mid-
     * `listFiles().forEach(delete)` — the winner's subsequent `listFiles` would then
     * pick up the loser's in-flight wrapper and delete it. The map value is [Unit]
     * because the key's presence (not the value) is the signal.
     *
     * Keys are `File.canonicalFile` (not `absoluteFile`) so symlinked trailmap
     * directories collapse to a single sweep gate. This matches the convention used
     * elsewhere in this class for path-equality checks. Individual file deletions
     * are best-effort (failures silently caught); stale files held open by an
     * external tool (e.g. on Windows) may persist across daemon restarts.
     */
    private val sweptParentDirs: ConcurrentHashMap<File, Unit> = ConcurrentHashMap()

    private fun sha256Hex(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val sb = StringBuilder(digest.size * 2)
      for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
      }
      return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
  }
}

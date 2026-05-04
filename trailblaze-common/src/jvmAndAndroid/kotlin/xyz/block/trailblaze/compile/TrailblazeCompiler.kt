package xyz.block.trailblaze.compile

import java.io.File
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.DriverTypeKey
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TargetEntry
import xyz.block.trailblaze.config.project.ToolEntry
import xyz.block.trailblaze.config.project.ToolsetEntry
import xyz.block.trailblaze.config.project.TrailblazePackManifestLoader
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeResolvedConfig
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Compiles Trailblaze pack manifests into resolved per-id target YAMLs.
 *
 * The mental model is `javac` for packs: source pack manifests
 * (`packs/<id>/pack.yaml`) declare libraries and entrypoints; the compiler
 * walks the dependency graph, runs closest-wins inheritance via
 * [TrailblazeProjectConfigLoader], and emits one materialized
 * `targets/<id>.yaml` per app pack — a pack with a `target:` block. Library
 * packs (no `target:`) contribute defaults but produce no output.
 *
 * Output `AppTargetYamlConfig` shape mirrors what consumers already see today
 * via flat target authoring: toolset and tool **names** stay as references
 * (the daemon's existing toolset/tool pool resolves them at run time, late-
 * bound the same way the JVM late-binds class refs). Per-platform inherited
 * `tool_sets:` and `drivers:` ARE flattened in from the dep graph so the
 * emitted target file is self-contained against the toolset pool — no
 * residual `dependencies:` references for the runtime to chase.
 *
 * The compiler enforces three classes of static checks the runtime resolver
 * intentionally does not:
 * - **App-pack gap detection.** Any pack that declared `target:` but failed
 *   dependency resolution is reported by name, not silently dropped.
 * - **Reference validation.** Every `tool_sets:`, `drivers:`, `tools:`, and
 *   `excluded_tools:` entry on every resolved platform is checked against the
 *   discovered pool. Typos surface at compile time, not at runtime as a
 *   silently-missing capability.
 * - **Orphan cleanup.** Files in the output directory that this compiler
 *   wrote on a previous run but no longer correspond to any current pack are
 *   deleted, mirroring the build-logic [PackTargetGenerator]'s behavior so
 *   build-time and runtime paths produce the same output tree.
 *
 * Tracked follow-ups: daemon-init lazy rebundle of workspace packs, and
 * scripted-tool TS typecheck/bundle as part of the compile step. Both live
 * as issues in the project tracker.
 */
object TrailblazeCompiler {

  /**
   * Result of [compile]. On any resolution / parse error, [errors] is
   * populated and no files are emitted; pre-existing output files in
   * [CompileResult.outputDir] are left untouched on failure so a botched
   * compile never destroys the previous good output.
   */
  data class CompileResult(
    val emittedTargets: List<File>,
    val deletedOrphans: List<File>,
    val errors: List<String>,
  ) {
    val isSuccess: Boolean get() = errors.isEmpty()
  }

  /**
   * Compiles every pack under [packsDir] into resolved target YAMLs in
   * [outputDir].
   *
   * @param packsDir directory whose immediate subdirectories each contain a
   *   `pack.yaml`. Subdirectories without a `pack.yaml` are silently skipped.
   * @param outputDir directory to write `<id>.yaml` files into. Created if
   *   missing. Stale `<id>.yaml` files written by a previous compile but no
   *   longer corresponding to a current pack are deleted (orphan cleanup).
   *   Files NOT bearing the [GENERATED_BANNER] header are left alone — the
   *   compiler only manages files it owns.
   * @param referenceSource where to look up the available pool for reference
   *   validation (toolset names, tool names). Defaults to JVM classpath
   *   scanning so the CLI "just works" against whatever's reachable. Tests
   *   can pass a synthetic source. Pass `null` to skip reference validation
   *   entirely (used by callers that can't see the full multi-module
   *   classpath, e.g. cross-module build-logic bridges).
   *
   * Driver-name validation does not consult [referenceSource]; drivers are an
   * enum-defined set and use [DriverTypeKey.knownKeys]. Driver validation IS
   * always performed even when [referenceSource] is null.
   *
   * ## Classpath dependency
   *
   * The internal call to [TrailblazeProjectConfigLoader.resolveRuntime] passes
   * `includeClasspathPacks = true`, so workspace packs that declare
   * `dependencies: [trailblaze]` (or any other classpath-shipped pack) resolve
   * against the JVM classpath at compile time. This matches runtime behavior:
   * a pack that compiles cleanly here also loads cleanly when the daemon
   * starts.
   *
   * **Caller contract**: the framework `trailblaze` stdlib pack must be
   * reachable on the calling JVM's classpath. The CLI and daemon entry points
   * always satisfy this; if you're invoking the compiler from a sandbox or an
   * on-device runtime where framework JARs aren't loaded, expect "Pack
   * 'trailblaze' not found" failures for any workspace pack with
   * `dependencies: [trailblaze]`. Consider exposing a parameter here if a
   * sandboxed call site appears.
   */
  fun compile(
    packsDir: File,
    outputDir: File,
    referenceSource: ConfigResourceSource? = ClasspathConfigResourceSource,
  ): CompileResult {
    require(packsDir.isDirectory) {
      "Input directory does not exist or is not a directory: ${packsDir.absolutePath}"
    }

    val packsListing = packsDir.listFiles { f -> f.isDirectory }
      ?: return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = listOf(
          "Failed to list contents of input directory ${packsDir.absolutePath}; " +
            "check permissions / I/O.",
        ),
      )
    val packRefs = packsListing
      .filter { File(it, "pack.yaml").isFile }
      .map { "packs/${it.name}/pack.yaml" }
      .sorted()

    if (packRefs.isEmpty()) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = emptyList(),
      )
    }

    // Build a synthetic workspace config: list every discovered pack as a `packs:` ref.
    // The anchor file's parent is the directory that those refs are resolved against,
    // so it must equal `packsDir.parentFile` for `packs/<id>/pack.yaml` to land at the
    // actual on-disk path.
    val anchorParent = packsDir.parentFile
      ?: error("packsDir has no parent: ${packsDir.absolutePath}")
    val loaded = LoadedTrailblazeProjectConfig(
      raw = TrailblazeProjectConfig(packs = packRefs),
      sourceFile = File(anchorParent, ANCHOR_FILE_NAME),
    )

    // Pre-pass: parse each pack manifest directly so we can identify app packs (those
    // with a `target:` block) by their structured shape, not by a fragile text scan
    // for `target:` line prefixes. This makes the gap-detection below name-aware
    // (errors say WHICH pack failed) and immune to comments / nested keys / future
    // schema additions where `target:` appears at non-root depth.
    val expectedAppPackIds = mutableSetOf<String>()
    val parseErrors = mutableListOf<String>()
    for (ref in packRefs) {
      val packFile = File(anchorParent, ref)
      try {
        val manifest = TrailblazePackManifestLoader.load(packFile).manifest
        if (manifest.target != null) expectedAppPackIds += manifest.id
      } catch (e: TrailblazeProjectConfigException) {
        parseErrors += "pack '$ref' failed to parse: ${e.message ?: e.javaClass.simpleName}"
      }
    }
    if (parseErrors.isNotEmpty()) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = parseErrors,
      )
    }

    // Include the JAR's classpath-bundled framework `trailblaze` stdlib pack so workspace
    // packs that declare `dependencies: [trailblaze]` resolve at compile time the same way
    // they do at runtime. Without this, any workspace pack that inherits the standard
    // per-platform defaults fails compile with "Pack 'trailblaze' not found" — the JDK
    // analogy in PackDependencyResolver's kdoc only holds if the JDK is actually on the
    // classpath. Any user-named pack that isn't in the workspace nor on classpath still
    // surfaces as a missing-dep failure (`compile names the failing pack...` test).
    val resolved = try {
      TrailblazeProjectConfigLoader.resolveRuntime(loaded, includeClasspathPacks = true)
    } catch (e: TrailblazeProjectConfigException) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = listOf(e.message ?: e.toString()),
      )
    }

    // The runtime loader's "atomic-per-pack failure-isolation" contract logs a warning
    // and drops a broken pack's target rather than throwing — graceful for runtime
    // discovery, wrong for a compile step. Detect the gap by diffing expected app
    // pack ids against what the resolver actually emitted; any pack in the gap failed
    // dependency resolution and the user gets named guidance.
    val resolvedTargetIds = resolved.projectConfig.targets
      .map { (it as TargetEntry.Inline).config.id }
      .toSet()
    val missingAppPacks = (expectedAppPackIds - resolvedTargetIds).toSortedSet()
    if (missingAppPacks.isNotEmpty()) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = missingAppPacks.map { id ->
          "pack '$id' declared a `target:` block but failed dependency resolution " +
            "(likely missing or cyclic dependencies — check the runtime loader's " +
            "warnings above for the offending dependency)."
        },
      )
    }

    val referenceErrors = collectUnresolvedReferences(resolved, referenceSource)
    if (referenceErrors.isNotEmpty()) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = referenceErrors,
      )
    }

    if (!outputDir.exists() && !outputDir.mkdirs()) {
      return CompileResult(
        emittedTargets = emptyList(),
        deletedOrphans = emptyList(),
        errors = listOf("Failed to create output directory: ${outputDir.absolutePath}"),
      )
    }

    val yaml = TrailblazeConfigYaml.instance
    val emitted = mutableListOf<File>()
    val emittedNames = mutableSetOf<String>()
    for (entry in resolved.projectConfig.targets) {
      val target = (entry as TargetEntry.Inline).config
      val outFile = File(outputDir, "${target.id}.yaml")
      val rendered = buildString {
        appendLine(GENERATED_BANNER)
        appendLine("# Source pack: packs/${target.id}/pack.yaml")
        appendLine("# Regenerate with: trailblaze compile")
        appendLine()
        append(yaml.encodeToString(AppTargetYamlConfig.serializer(), target))
      }
      val finalText = if (rendered.endsWith("\n")) rendered else rendered + "\n"
      outFile.writeText(finalText)
      emitted += outFile
      emittedNames += outFile.name
    }

    val deletedOrphans = deleteOrphanOutputs(outputDir, emittedNames)

    return CompileResult(
      emittedTargets = emitted.sortedBy { it.name },
      deletedOrphans = deletedOrphans.sortedBy { it.name },
      errors = emptyList(),
    )
  }

  // ---------------------------------------------------------------------------
  // Reference validation
  // ---------------------------------------------------------------------------

  /**
   * Walks every resolved target's per-platform reference fields (`tool_sets:`,
   * `drivers:`, `tools:`, `excluded_tools:`) and returns an error message for
   * each name that doesn't appear in the appropriate pool. Returns an empty
   * list when every reference resolves.
   *
   * The referenceSource is used for toolset and tool discovery; drivers always
   * validate against [DriverTypeKey.knownKeys] (an enum-defined static set).
   * Skipping reference validation entirely requires `referenceSource = null`;
   * driver validation is unaffected because it doesn't depend on classpath I/O.
   */
  private fun collectUnresolvedReferences(
    resolved: TrailblazeResolvedConfig,
    referenceSource: ConfigResourceSource?,
  ): List<String> {
    val toolsetPool: Set<String>? = referenceSource?.let {
      val packToolsets = resolved.projectConfig.toolsets
        .map { (it as ToolsetEntry.Inline).config.id }
        .toSet()
      val classpathToolsets = discoverIdsFromClasspath(
        it,
        TrailblazeConfigPaths.TOOLSETS_DIR,
        ToolSetYamlConfig.serializer(),
        idExtractor = ToolSetYamlConfig::id,
        kindLabel = "toolset",
      )
      packToolsets + classpathToolsets
    }
    val toolPool: Set<String>? = referenceSource?.let {
      val packTools = resolved.projectConfig.tools
        .map { (it as ToolEntry.Inline).config.id }
        .toSet()
      val classpathTools = discoverIdsFromClasspath(
        it,
        TrailblazeConfigPaths.TOOLS_DIR,
        ToolYamlConfig.serializer(),
        idExtractor = ToolYamlConfig::id,
        kindLabel = "tool",
      )
      packTools + classpathTools
    }
    val driverPool: Set<String> = DriverTypeKey.knownKeys

    val errors = mutableListOf<String>()
    for (entry in resolved.projectConfig.targets) {
      val target = (entry as TargetEntry.Inline).config
      target.platforms?.forEach { (platform, platformConfig) ->
        if (toolsetPool != null) {
          platformConfig.toolSets?.forEach { name ->
            if (name !in toolsetPool) {
              errors += unresolvedRefError(target.id, platform, "tool_sets", name, "toolset")
            }
          }
        }
        if (toolPool != null) {
          platformConfig.tools?.forEach { name ->
            if (name !in toolPool) {
              errors += unresolvedRefError(target.id, platform, "tools", name, "tool")
            }
          }
          platformConfig.excludedTools?.forEach { name ->
            if (name !in toolPool) {
              errors += unresolvedRefError(target.id, platform, "excluded_tools", name, "tool")
            }
          }
        }
        platformConfig.drivers?.forEach { name ->
          if (name.lowercase() !in driverPool) {
            errors += "target '${target.id}': platforms.$platform.drivers references " +
              "unknown driver '$name' (valid drivers: ${driverPool.sorted().joinToString(", ")})."
          }
        }
      }
    }
    return errors
  }

  private fun unresolvedRefError(
    targetId: String,
    platform: String,
    field: String,
    name: String,
    kindLabel: String,
  ): String =
    "target '$targetId': platforms.$platform.$field references unknown $kindLabel '$name' " +
      "(no $kindLabel with this id is declared in the workspace packs or available on the " +
      "classpath under trailblaze-config/${kindLabel}s/)."

  /**
   * Lightweight name-only listing of artifacts reachable through [source] under
   * [directoryPath]. Logs at warning level when a YAML fails to parse so the
   * user has signal that their classpath state is degraded — silently
   * returning empty would let typo'd references pass validation against an
   * empty pool, masking the real failure.
   */
  private fun <T : Any> discoverIdsFromClasspath(
    source: ConfigResourceSource,
    directoryPath: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    idExtractor: (T) -> String,
    kindLabel: String,
  ): Set<String> {
    val yaml = TrailblazeConfigYaml.instance
    val yamlContents = try {
      source.discoverAndLoad(directoryPath = directoryPath, suffix = ".yaml")
    } catch (e: Exception) {
      Console.log(
        "trailblaze compile: WARNING: failed to scan $directoryPath for $kindLabel " +
          "definitions (${e::class.simpleName}: ${e.message}). Reference validation for " +
          "$kindLabel names will only check against pack-declared ids; classpath ${kindLabel}s " +
          "won't be in the pool.",
      )
      return emptySet()
    }
    return yamlContents.entries
      .mapNotNull { (filename, content) ->
        try {
          idExtractor(yaml.decodeFromString(serializer, content))
        } catch (e: Exception) {
          Console.log(
            "trailblaze compile: WARNING: failed to parse $directoryPath/$filename.yaml " +
              "(${e::class.simpleName}: ${e.message}). This file will not contribute to the " +
              "$kindLabel pool used for reference validation.",
          )
          null
        }
      }
      .toSet()
  }

  // ---------------------------------------------------------------------------
  // Orphan cleanup
  // ---------------------------------------------------------------------------

  /**
   * Deletes any `<id>.yaml` files in [outputDir] that this compiler wrote on a
   * previous run (identified by the [GENERATED_BANNER] header) but are not in
   * [keepNames]. Hand-authored YAMLs without the banner are left alone — the
   * compiler only manages files it owns. Mirrors build-logic
   * [PackTargetGenerator.deleteStaleGeneratedTargets] so build-time and runtime
   * paths converge on the same output tree.
   */
  private fun deleteOrphanOutputs(outputDir: File, keepNames: Set<String>): List<File> {
    val candidates = outputDir.listFiles { f ->
      f.isFile && f.extension == "yaml" && f.name !in keepNames
    } ?: return emptyList()
    val deleted = mutableListOf<File>()
    for (file in candidates) {
      val firstLine = try {
        file.bufferedReader().use { it.readLine().orEmpty() }
      } catch (_: Exception) {
        continue
      }
      if (firstLine == GENERATED_BANNER && file.delete()) {
        deleted += file
      }
    }
    return deleted
  }

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  private const val ANCHOR_FILE_NAME = "trailblaze.compile.synthetic.yaml"

  /**
   * First line of every target YAML this compiler emits. Used at the top of
   * each generated file (so editors see "do not edit") AND as the orphan-
   * cleanup signature so we only delete files we know we own.
   */
  internal const val GENERATED_BANNER = "# GENERATED BY trailblaze compile. DO NOT EDIT."
}

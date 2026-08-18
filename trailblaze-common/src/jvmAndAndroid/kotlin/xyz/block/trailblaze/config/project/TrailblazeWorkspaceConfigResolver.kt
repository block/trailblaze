package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Shared resolver for the Trailblaze workspace anchor (a `trailblaze.yaml` inside
 * `trailblaze-config/` or the legacy `trails/config/`) and its payload directory.
 *
 * This is the single runtime decision point for "where does project-level config live?".
 * Callers that need LLM config, targets, toolsets, or tools should resolve the workspace
 * through this object rather than reimplementing their own cwd / env-var / subdir rules.
 */
object TrailblazeWorkspaceConfigResolver {

  const val CONFIG_DIR_ENV_VAR = "TRAILBLAZE_CONFIG_DIR"

  fun resolve(
    fromPath: Path,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): ResolvedTrailblazeWorkspaceConfig {
    val envOverride = envReader()?.takeIf { it.isNotBlank() }
    if (envOverride != null) {
      val envDir = File(envOverride)
      if (envDir.isDirectory) {
        // An explicit TRAILBLAZE_CONFIG_DIR is authoritative for the ENTIRE workspace, not
        // just the file-scan directory. When the override dir carries its own
        // `trailblaze.yaml`, that anchor — and the targets / trailmaps it declares — wins
        // over whatever cwd walk-up would have found. Without this, the env var would move
        // only the payload dir while the anchor still came from the cwd, so a cwd that is
        // itself a workspace (a monorepo / repo root) silently shadows the env-pointed
        // workspace's trailmaps — the trail-run tool-registration bug this guards against.
        // See TrailblazeWorkspaceConfigResolverTest.
        val envWorkspaceRoot = workspaceRootFromConfigDir(envDir)
        if (envWorkspaceRoot != null) {
          return ResolvedTrailblazeWorkspaceConfig(
            workspaceRoot = envWorkspaceRoot,
            configFile = envWorkspaceRoot.configFile.toFile(),
            configDir = envDir,
          )
        }
        // Override dir has no `trailblaze.yaml` of its own: keep the legacy split — anchor
        // from cwd walk-up, payload dir from the override — so a bare config-dir override
        // still resolves something usable.
        val walkUpRoot = findWorkspaceRoot(fromPath)
        return ResolvedTrailblazeWorkspaceConfig(
          workspaceRoot = walkUpRoot,
          configFile = (walkUpRoot as? WorkspaceRoot.Configured)?.configFile?.toFile(),
          configDir = envDir,
        )
      }
      Console.log("$CONFIG_DIR_ENV_VAR='$envOverride' is not a directory — ignoring.")
    }
    val workspaceRoot = findWorkspaceRoot(fromPath)
    val configFile = (workspaceRoot as? WorkspaceRoot.Configured)?.configFile?.toFile()
    val workspaceConfigDir = when (workspaceRoot) {
      // The payload dir is wherever the anchor file was found (`trails/config/` or
      // `trailblaze-config/`) — carried on the root rather than recomposed from a
      // constant so the two layouts can't diverge here.
      is WorkspaceRoot.Configured -> workspaceRoot.configDir.toFile().takeIf { it.isDirectory }
      is WorkspaceRoot.Scratch -> null
    }
    return ResolvedTrailblazeWorkspaceConfig(
      workspaceRoot = workspaceRoot,
      configFile = configFile,
      configDir = workspaceConfigDir,
    )
  }

  /**
   * Resolves the workspace anchor file (`trails/config/trailblaze.yaml`).
   *
   * Honors [CONFIG_DIR_ENV_VAR] consistently with [resolve]: when the env var names a
   * directory that carries its own `trailblaze.yaml`, that anchor wins; otherwise this falls
   * back to walking up from [fromPath]. Keeping this in lockstep with [resolve] avoids a
   * split where the anchor-only callers (LLM config, MCP, CLI info) read a different
   * workspace than the trail runner.
   */
  fun resolveConfigFile(fromPath: Path): File? = resolve(fromPath).configFile

  /**
   * The workspace's declared `defaults.target` id, blank-normalized to null, from the workspace
   * anchor discovered at [fromPath]. The single read path for callers that only need the id (the
   * CLI target displays + `resolveCliTarget`); callers that also need the anchor file for
   * diagnostics (the daemon's cached, logged resolution) use [loadWorkspaceDefaults] directly.
   * Owning the `takeIf { isNotBlank() }` normalization here keeps a blank `defaults.target:` from
   * being surfaced as a real id at any one call site.
   */
  fun workspaceDefaultTarget(
    fromPath: Path,
    consumer: String,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): String? =
    loadWorkspaceDefaults(fromPath, consumer, envReader)?.defaults?.target?.takeIf { it.isNotBlank() }

  /**
   * The neutral-"default" target sentinel: rung 2 of effective-target precedence, shared by the
   * CLI target surfaces (`resolveCliTarget`, `config get target`, `config target` listing) and
   * the daemon's run resolution (`TrailblazeSettingsRepo.getCurrentSelectedTargetApp`).
   *
   * A persisted [selectedTargetAppId] counts as an *authoritative* user selection only when it is
   * non-blank AND not equal to [neutralDefaultId]. Legacy CLI code auto-persisted the neutral
   * default without any user intent, so a stored neutral id is indistinguishable from a fabricated
   * one and must never mask a committed workspace [workspaceDefaultTarget]. Returns the
   * authoritative id, or `null` when the caller should fall through to its workspace-default /
   * built-in tiers.
   *
   * [neutralDefaultId] is a parameter rather than a hardcoded constant precisely because the two
   * callers source it differently: the CLI passes the compile-time OSS static
   * (`TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id`), while the daemon passes the
   * runtime-injected `defaultHostAppTarget.id` from its distribution's app config. Routing both
   * through this one function means the *sentinel logic* can't drift; keeping the two ids equal is
   * the distribution's contract (see the KDoc on `TrailblazeDesktopAppConfig.defaultAppTarget`).
   */
  fun authoritativeSelectedTargetId(selectedTargetAppId: String?, neutralDefaultId: String): String? =
    selectedTargetAppId?.takeIf { it.isNotBlank() && it != neutralDefaultId }

  /**
   * The `trails:` declaration of the workspace anchored at [fromPath], or null when it declares
   * nothing, no workspace resolves, or the declared directory is unusable.
   *
   * Null means "no opinion" — callers keep whatever root they would have used anyway. Only an
   * explicit declaration returns non-null: this deliberately does NOT fall back to the
   * `<workspace-root>/trails` convention, because that would let merely launching inside any
   * workspace re-anchor a user who has deliberately pointed their app somewhere else. Opting
   * in costs one committed line, and workspaces that already follow the convention need
   * nothing.
   *
   * The result carries the declaring anchor alongside the directory, because a declaration
   * **decouples the trails dir from the config dir**. Callers that previously recovered the
   * config dir by walking up from the trails dir (`resolveWorkspaceConfigDir`) or seeded the
   * workspace walk-up from it must read [WorkspaceTrailsDeclaration.configDir] /
   * [WorkspaceTrailsDeclaration.configFile] instead — the declared directory may sit outside
   * the config dir's ancestry entirely, in which case a walk-up finds nothing.
   *
   * Path rules:
   *  - A **relative** value resolves against [WorkspaceRoot.Configured.workspaceRootDir], so the
   *    same committed string means the same directory under either config-dir layout, and must
   *    stay inside that root. A committed file is shared by everyone who clones the repo; letting
   *    `../..` walk out of it would silently point the whole team's app — and its recording
   *    writes — somewhere unrelated to the checkout.
   *  - An **absolute** value is taken at face value. It can't be portable across machines, so it
   *    is unambiguously a deliberate local choice rather than a typo.
   *  - The filesystem root is rejected outright: recursive trail scanning from `/` is never what
   *    anyone meant.
   *
   * Anything unusable is logged and treated as absent rather than propagated: a typo, or a path
   * valid only on a teammate's machine, should not strand the app pointing somewhere it can't
   * read.
   */
  fun workspaceTrailsDeclaration(
    fromPath: Path,
    consumer: String,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): WorkspaceTrailsDeclaration? = readWorkspace(fromPath, consumer, envReader) { resolved, config ->
    val root = resolved.workspaceRoot as? WorkspaceRoot.Configured ?: return@readWorkspace null
    val configFile = resolved.configFile ?: return@readWorkspace null
    val declared = config.trails?.trim()?.takeIf { it.isNotEmpty() } ?: return@readWorkspace null
    val workspaceRootDir = root.workspaceRootDir.toFile()
    val declaredFile = File(declared)
    val candidate = (if (declaredFile.isAbsolute) declaredFile else File(workspaceRootDir, declared)).canonicalFile

    fun reject(why: String): WorkspaceTrailsDeclaration? {
      Console.log(
        "Ignoring `trails: $declared` from ${configFile.absolutePath} for $consumer: " +
          "${candidate.absolutePath} $why.",
      )
      return null
    }

    if (candidate.parentFile == null) return@readWorkspace reject("is the filesystem root")
    if (!declaredFile.isAbsolute && !candidate.isInside(workspaceRootDir.canonicalFile)) {
      return@readWorkspace reject("escapes the workspace root ${workspaceRootDir.absolutePath} — use an absolute path if that is intentional")
    }
    if (!candidate.isDirectory) return@readWorkspace reject("is not a directory")

    WorkspaceTrailsDeclaration(
      trailsDir = candidate,
      configDir = root.configDir.toFile(),
      configFile = configFile,
    )
  }

  /**
   * Convenience over [workspaceTrailsDeclaration] for callers that only need the directory.
   * Callers that also resolve a config dir or a workspace anchor must use the full declaration
   * — see its kdoc for why deriving those from the trails dir stops working once one is declared.
   */
  fun workspaceTrailsDir(
    fromPath: Path,
    consumer: String,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): File? = workspaceTrailsDeclaration(fromPath, consumer, envReader)?.trailsDir

  /** True when this file is [ancestor] itself or sits beneath it. Both must be canonical. */
  private fun File.isInside(ancestor: File): Boolean =
    this == ancestor || path.startsWith(ancestor.path + File.separator)

  /**
   * Resolves the workspace anchor from [fromPath] and loads its raw `defaults:` block,
   * paired with the anchor file for caller diagnostics. Returns null when no anchor
   * resolves or the file declares no `defaults:`. Load failures (malformed YAML, I/O)
   * are logged — attributed to [consumer] — and degrade to null, so a broken workspace
   * file never crashes the calling feature; the caller falls through to its next
   * precedence tier instead.
   *
   * This is the single shared read path for `defaults.*` consumers (`defaults.target`,
   * `defaults.maxLlmCalls`, …) — add new consumers here rather than re-implementing the
   * resolve → load → swallow-and-log shape. [readWorkspace] owns that shape; add a new
   * top-level key's reader beside this one over the same primitive.
   */
  fun loadWorkspaceDefaults(
    fromPath: Path,
    consumer: String,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): LoadedWorkspaceDefaults? = readWorkspace(fromPath, consumer, envReader) { resolved, config ->
    val configFile = resolved.configFile ?: return@readWorkspace null
    val defaults = config.defaults ?: return@readWorkspace null
    LoadedWorkspaceDefaults(configFile = configFile, defaults = defaults)
  }

  /**
   * Resolve → load → hand the caller the parsed config, swallowing and logging any failure.
   *
   * Broad catch by design: callers sit on hot paths (Compose recomposition, per-dispatch MCP,
   * desktop launch), so ANY throw from the walk-up or the YAML layer must degrade to "no
   * workspace opinion", not crash the calling feature. TrailblazeProjectConfigException and
   * IOException are the expected shapes; the rest is the safety net. Cancellation and interrupts
   * are NOT failures to degrade from: a coroutine cancellation must propagate (swallowing it
   * leaves the caller running through its own cancellation), and a thread interrupt must stay
   * visible to the caller's next interruptible operation.
   *
   * [read] runs inside the catch, so a caller's own filesystem probes degrade the same way.
   */
  private fun <T> readWorkspace(
    fromPath: Path,
    consumer: String,
    envReader: () -> String?,
    read: (ResolvedTrailblazeWorkspaceConfig, TrailblazeProjectConfig) -> T?,
  ): T? {
    return try {
      val resolved = resolve(fromPath, envReader)
      val configFile = resolved.configFile ?: return null
      val config = TrailblazeProjectConfigLoader.load(configFile)?.raw ?: return null
      read(resolved, config)
    } catch (e: CancellationException) {
      throw e
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      Console.log("Skipping workspace trailblaze.yaml for $consumer: interrupted")
      null
    } catch (e: Exception) {
      Console.log("Skipping workspace trailblaze.yaml for $consumer: ${e.message}")
      null
    }
  }
}

/** A workspace anchor's raw `defaults:` block plus the anchor file for diagnostics. */
data class LoadedWorkspaceDefaults(
  val configFile: File,
  val defaults: ProjectDefaults,
)

/**
 * A workspace's resolved `trails:` declaration: the directory it names, plus the config dir and
 * anchor file that named it.
 *
 * The anchor travels with the directory because a declaration is precisely the case where the
 * trails dir and the config dir are no longer derivable from one another. `legacy-trails/` and
 * `trailblaze-config/` can be siblings, and an absolute declaration can leave the repo entirely
 * — so a caller holding only [trailsDir] cannot recover [configDir] by walking up, and would
 * silently resolve an empty workspace instead.
 */
data class WorkspaceTrailsDeclaration(
  val trailsDir: File,
  val configDir: File,
  val configFile: File,
)

data class ResolvedTrailblazeWorkspaceConfig(
  val workspaceRoot: WorkspaceRoot,
  val configFile: File?,
  val configDir: File?,
) {
  fun loadProjectConfig(): TrailblazeProjectConfig? =
    configFile?.let(TrailblazeProjectConfigLoader::loadResolved)

  /**
   * Full resolved view including dereferenced [AppTargetYamlConfig] target objects on
   * [TrailblazeResolvedConfig.targets]. Use this when you need the actual target configs
   * (target discovery, CLI surfaces, the compiler) — [loadProjectConfig] only returns
   * the schema-shape view (id list).
   *
   * Pass a non-null [scriptedToolEnrichment] to allow meta-only scripted-tool descriptors
   * (YAML files with `script:` + `_meta:` only) to resolve via analyzer extraction of the
   * sibling `.ts`. JVM host callers wire the analyzer-backed implementation here; on-device
   * runtime / build-time callers leave it null and rely on full-YAML descriptors.
   */
  fun loadResolvedRuntime(
    scriptedToolEnrichment: ScriptedToolEnrichment? = null,
  ): TrailblazeResolvedConfig? =
    configFile?.let {
      TrailblazeProjectConfigLoader.load(it)?.let { loaded ->
        TrailblazeProjectConfigLoader.resolveRuntime(
          loaded = loaded,
          includeClasspathTrailmaps = true,
          scriptedToolEnrichment = scriptedToolEnrichment,
        )
      }
    }
}

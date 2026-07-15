package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Shared resolver for the Trailblaze workspace anchor (`trails/config/trailblaze.yaml`) and
 * its payload directory (`trails/config/`).
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
      is WorkspaceRoot.Configured ->
        File(workspaceRoot.dir.toFile(), TrailblazeConfigPaths.WORKSPACE_CONFIG_SUBDIR)
          .takeIf { it.isDirectory }
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
   * Resolves the workspace anchor from [fromPath] and loads its raw `defaults:` block,
   * paired with the anchor file for caller diagnostics. Returns null when no anchor
   * resolves or the file declares no `defaults:`. Load failures (malformed YAML, I/O)
   * are logged — attributed to [consumer] — and degrade to null, so a broken workspace
   * file never crashes the calling feature; the caller falls through to its next
   * precedence tier instead.
   *
   * This is the single shared read path for `defaults.*` consumers (`defaults.target`,
   * `defaults.maxLlmCalls`, …) — add new consumers here rather than re-implementing the
   * resolve → load → swallow-and-log shape.
   */
  fun loadWorkspaceDefaults(
    fromPath: Path,
    consumer: String,
    envReader: () -> String? = { System.getenv(CONFIG_DIR_ENV_VAR) },
  ): LoadedWorkspaceDefaults? {
    // Broad catch by design: callers sit on hot paths (Compose recomposition, per-dispatch
    // MCP), so ANY throw from the walk-up or the YAML layer must degrade to "no workspace
    // defaults", not crash the calling feature. TrailblazeProjectConfigException and
    // IOException are the expected shapes; the rest is the safety net. Cancellation and
    // interrupts are NOT failures to degrade from: a coroutine cancellation must propagate
    // (swallowing it leaves the caller running through its own cancellation), and a thread
    // interrupt must stay visible to the caller's next interruptible operation.
    return try {
      val configFile = resolve(fromPath, envReader).configFile ?: return null
      val defaults = TrailblazeProjectConfigLoader.load(configFile)?.raw?.defaults ?: return null
      LoadedWorkspaceDefaults(configFile = configFile, defaults = defaults)
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

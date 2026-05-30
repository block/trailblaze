package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Path
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
}

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

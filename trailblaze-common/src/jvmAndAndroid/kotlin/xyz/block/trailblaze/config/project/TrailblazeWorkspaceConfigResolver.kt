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
    val workspaceRoot = findWorkspaceRoot(fromPath)
    val configFile = (workspaceRoot as? WorkspaceRoot.Configured)?.configFile?.toFile()
    val envOverride = envReader()?.takeIf { it.isNotBlank() }
    if (envOverride != null) {
      val envDir = File(envOverride)
      if (envDir.isDirectory) {
        return ResolvedTrailblazeWorkspaceConfig(
          workspaceRoot = workspaceRoot,
          configFile = configFile,
          configDir = envDir,
        )
      }
      Console.log("$CONFIG_DIR_ENV_VAR='$envOverride' is not a directory — ignoring.")
    }
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
   * Resolves only the workspace anchor file (`trails/config/trailblaze.yaml`), ignoring
   * [CONFIG_DIR_ENV_VAR]. The env var overrides the workspace config directory
   * (`trails/config/`), but it does not define a different workspace root.
   */
  fun resolveConfigFile(fromPath: Path): File? =
    (findWorkspaceRoot(fromPath) as? WorkspaceRoot.Configured)?.configFile?.toFile()
}

data class ResolvedTrailblazeWorkspaceConfig(
  val workspaceRoot: WorkspaceRoot,
  val configFile: File?,
  val configDir: File?,
) {
  fun loadProjectConfig(): TrailblazeProjectConfig? =
    configFile?.let(TrailblazeProjectConfigLoader::loadResolved)
}

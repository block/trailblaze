package xyz.block.trailblaze.llm.config

/**
 * Shared constants for Trailblaze configuration file paths.
 *
 * All classpath-bundled configuration lives under [CONFIG_DIR] (`trailblaze-config/`):
 * - `trailblaze-config/trailblaze.yaml` — project-level defaults
 * - `trailblaze-config/providers/{provider_id}.yaml` — LLM provider definitions
 *
 * On desktop, the user-level config lives at `~/.trailblaze/trailblaze.yaml`.
 *
 * In a project workspace, Trailblaze now treats `trails/` as the anchor directory and
 * expects workspace config under `trails/config/trailblaze.yaml`.
 *
 * On Android, AGP strips dot-prefixed directories from both assets and Java resources,
 * so classpath resources use [CONFIG_DIR] instead of `.trailblaze`.
 */
object TrailblazeConfigPaths {

  /** Config filename used across all platforms. */
  const val CONFIG_FILENAME = "trailblaze.yaml"

  /** Desktop user-level config directory (e.g., `~/.trailblaze/`). */
  const val DOT_TRAILBLAZE_DIR = ".trailblaze"

  /** Workspace root directory for project-local Trailblaze assets and trails. */
  const val WORKSPACE_TRAILS_DIR = "trails"

  /** Project-local config subdirectory under [WORKSPACE_TRAILS_DIR]. */
  const val WORKSPACE_CONFIG_SUBDIR = "config"

  /** Relative path from repo/workspace root to the project-local config directory. */
  const val WORKSPACE_CONFIG_DIR = "$WORKSPACE_TRAILS_DIR/$WORKSPACE_CONFIG_SUBDIR"

  /** Relative path from repo/workspace root to the project-local config file. */
  const val WORKSPACE_CONFIG_FILE = "$WORKSPACE_CONFIG_DIR/$CONFIG_FILENAME"

  /** Relative directory under `trails/config/` that owns authored local packs. */
  const val PACKS_SUBDIR = "packs"

  /** Relative path from repo/workspace root to the project-local pack directory. */
  const val WORKSPACE_PACKS_DIR = "$WORKSPACE_CONFIG_DIR/$PACKS_SUBDIR"

  /** Canonical filename for a local pack manifest. */
  const val PACK_MANIFEST_FILENAME = "pack.yaml"

  /** Classpath resource directory for on-device / bundled config. */
  const val CONFIG_DIR = "trailblaze-config"

  /** Full classpath resource path to the bundled config. */
  const val CONFIG_RESOURCE_PATH = "$CONFIG_DIR/$CONFIG_FILENAME"

  /** Classpath resource directory for LLM provider YAML definitions. */
  const val PROVIDERS_DIR = "$CONFIG_DIR/providers"

  /** Classpath resource directory for app target YAML definitions. */
  const val TARGETS_DIR = "$CONFIG_DIR/targets"

  /** Classpath resource directory for pack manifests. */
  const val PACKS_DIR = "$CONFIG_DIR/packs"

  /** Classpath resource directory for toolset YAML definitions. */
  const val TOOLSETS_DIR = "$CONFIG_DIR/toolsets"

  /** Classpath resource directory for per-tool YAML definitions. */
  const val TOOLS_DIR = "$CONFIG_DIR/tools"

  /**
   * Subpath under [WORKSPACE_CONFIG_DIR] that owns workspace compile outputs.
   * Both `trailblaze compile` and the daemon-init lazy rebundle write here.
   */
  const val WORKSPACE_DIST_SUBDIR = "dist"

  /** Subpath under [WORKSPACE_CONFIG_DIR] that holds materialized target YAMLs. */
  const val WORKSPACE_DIST_TARGETS_SUBPATH = "$WORKSPACE_DIST_SUBDIR/targets"
}

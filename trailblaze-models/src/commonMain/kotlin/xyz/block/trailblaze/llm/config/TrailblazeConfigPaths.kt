package xyz.block.trailblaze.llm.config

/**
 * Shared constants for Trailblaze configuration file paths.
 *
 * Classpath and workspace use the **same path string** — [CONFIG_DIR] equals
 * [WORKSPACE_CONFIG_DIR] (`trails/config/`). Bundled jars contribute entries at
 * `trails/config/...`; users author files in `<workspace>/trails/config/...`. The
 * two layers merge by stripped filename in [CompositeConfigResourceSource] with
 * workspace winning on collision.
 *
 * - `trails/config/trailblaze.yaml` — project-level defaults
 * - `trails/config/providers/{provider_id}.yaml` — LLM provider definitions
 * - `trails/config/trailmaps/<id>/...` — trailmap-scoped tools, toolsets, shortcuts
 *
 * On desktop, the user-level config lives at `~/.trailblaze/trailblaze.yaml`.
 *
 * On Android, AGP strips dot-prefixed directories from both assets and Java resources,
 * so the classpath prefix is the non-dot [CONFIG_DIR] (`trails/config`) rather than
 * something dot-prefixed like `.trailblaze`.
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

  /** Relative directory under `trails/config/` that owns authored local trailmaps. */
  const val TRAILMAPS_SUBDIR = "trailmaps"

  /** Relative path from repo/workspace root to the project-local trailmap directory. */
  const val WORKSPACE_TRAILMAPS_DIR = "$WORKSPACE_CONFIG_DIR/$TRAILMAPS_SUBDIR"

  /** Canonical filename for a local trailmap manifest. */
  const val TRAILMAP_MANIFEST_FILENAME = "trailmap.yaml"

  /**
   * Classpath resource directory for on-device / bundled config.
   *
   * **Same string as [WORKSPACE_CONFIG_DIR]** — bundled jars contribute entries at
   * `trails/config/...` so the framework's authoring layout matches what a user
   * sees in their own workspace's `trails/config/`. The two layers (classpath +
   * workspace filesystem) merge by stripped filename in `CompositeConfigResourceSource`;
   * having one path eliminates the cognitive split prior naming imposed
   * (the classpath bundle was historically `trailblaze-config/` because the
   * original `.trailblaze/` was stripped by Android AGP; this path now matches
   * the workspace convention since `trails/config/` is also non-dot-prefixed).
   */
  const val CONFIG_DIR = WORKSPACE_CONFIG_DIR

  /** Full classpath resource path to the bundled config. */
  const val CONFIG_RESOURCE_PATH = "$CONFIG_DIR/$CONFIG_FILENAME"

  /** Classpath resource directory for LLM provider YAML definitions. */
  const val PROVIDERS_DIR = "$CONFIG_DIR/providers"

  /** Classpath resource directory for app target YAML definitions. */
  const val TARGETS_DIR = "$CONFIG_DIR/targets"

  /** Classpath resource directory for trailmap manifests. */
  const val TRAILMAPS_DIR = "$CONFIG_DIR/trailmaps"

  /**
   * Subpath under [WORKSPACE_CONFIG_DIR] that owns workspace compile outputs.
   * Both `trailblaze compile` and the daemon-init lazy rebundle write here.
   */
  const val WORKSPACE_DIST_SUBDIR = "dist"

  /** Subpath under [WORKSPACE_CONFIG_DIR] that holds materialized target YAMLs. */
  const val WORKSPACE_DIST_TARGETS_SUBPATH = "$WORKSPACE_DIST_SUBDIR/targets"

  /**
   * Single source of truth for which trailmap subdirectory owns each operational tool YAML
   * suffix. Each suffix lives under exactly one top-level trailmap dir; subdirectories below
   * that are organizational only and walked recursively at any depth.
   *
   * Both the classpath-bundled discovery path (`ToolYamlLoader.discoverTrailmapBundledToolContents`
   * in `trailblaze-models`) and the workspace filesystem-trailmap discovery path
   * (`TrailblazeProjectConfigLoader.resolveTrailmapSiblings` in `trailblaze-common`) read this
   * list. Adding a fourth operational class is a single edit here — both loaders pick it up.
   */
  val TRAILMAP_TOOL_LAYOUT: List<TrailmapToolLayoutEntry> = listOf(
    TrailmapToolLayoutEntry(dir = "tools", suffix = ".tool.yaml"),
    TrailmapToolLayoutEntry(dir = "shortcuts", suffix = ".shortcut.yaml"),
    TrailmapToolLayoutEntry(dir = "trailheads", suffix = ".trailhead.yaml"),
  )

  /** A (dir, suffix) pair from [TRAILMAP_TOOL_LAYOUT]. */
  data class TrailmapToolLayoutEntry(val dir: String, val suffix: String)
}

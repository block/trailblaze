package xyz.block.trailblaze.llm.config

/**
 * Shared constants for Trailblaze configuration file paths.
 *
 * All classpath-bundled configuration lives under [CONFIG_DIR] (`trailblaze-config/`):
 * - `trailblaze-config/trailblaze.yaml` — project-level defaults
 * - `trailblaze-config/providers/{provider_id}.yaml` — LLM provider definitions
 *
 * On desktop, the user-level config lives at `~/.trailblaze/trailblaze.yaml`.
 * On Android, AGP strips dot-prefixed directories from both assets and Java resources,
 * so classpath resources use [CONFIG_DIR] instead of `.trailblaze`.
 */
object TrailblazeConfigPaths {

  /** Config filename used across all platforms. */
  const val CONFIG_FILENAME = "trailblaze.yaml"

  /** Desktop user-level config directory (e.g., `~/.trailblaze/`). */
  const val DOT_TRAILBLAZE_DIR = ".trailblaze"

  /** Classpath resource directory for on-device / bundled config. */
  const val CONFIG_DIR = "trailblaze-config"

  /** Full classpath resource path to the bundled config. */
  const val CONFIG_RESOURCE_PATH = "$CONFIG_DIR/$CONFIG_FILENAME"

  /** Classpath resource directory for LLM provider YAML definitions. */
  const val PROVIDERS_DIR = "$CONFIG_DIR/providers"

  /** Classpath resource directory for app target YAML definitions. */
  const val TARGETS_DIR = "$CONFIG_DIR/targets"

  /** Classpath resource directory for toolset YAML definitions. */
  const val TOOLSETS_DIR = "$CONFIG_DIR/toolsets"

  /** Classpath resource directory for per-tool YAML definitions. */
  const val TOOLS_DIR = "$CONFIG_DIR/tools"
}

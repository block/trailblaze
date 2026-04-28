package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
data class TrailConfig(
  val context: String? = null,
  val id: String? = null,
  val title: String? = null,
  val description: String? = null,
  val priority: String? = null,
  val source: TrailSource? = null,
  val metadata: Map<String, String>? = null,
  /**
   * Optional target identifier. This can be an alias if custom tools provided by your organization
   * use a short name for the target, a package ID (e.g., "com.example.app"), or a URL for web
   * targets. Not required.
   */
  val target: String? = null,
  /**
   * Optional platform hint for device selection. When set, the CLI will auto-select a device
   * matching this platform. This is a freeform string used as a first classifier — it does not
   * need to map to [xyz.block.trailblaze.devices.TrailblazeDevicePlatform] values since internal
   * platforms may exist. Common values: "android", "ios", "web".
   */
  val platform: String? = null,
  /**
   * Optional driver type for device selection. When set, the CLI will select a device with the
   * matching driver. This is more explicit than [platform] and takes precedence over it. Valid
   * values correspond to [xyz.block.trailblaze.devices.TrailblazeDriverType] names (e.g.,
   * "PLAYWRIGHT_NATIVE", "ANDROID_ONDEVICE_INSTRUMENTATION", "IOS_HOST").
   */
  val driver: String? = null,
  /** Optional Electron app configuration for [TrailblazeDriverType.PLAYWRIGHT_ELECTRON] trails. */
  val electron: ElectronAppConfig? = null,
  /**
   * Optional list of prerequisite trail IDs that must run before this trail.
   * Each entry is the ID of another trail that will be auto-executed prior to this trail.
   * Nested prerequisites (prerequisites of prerequisites) are resolved recursively
   * across all execution paths (Desktop UI, host tests, MCP). A visited set prevents
   * circular dependencies.
   */
  val prerequisites: List<String>? = null,
)

@Serializable
data class ElectronAppConfig(
  /** Path to the Electron app executable. If null, [cdpUrl] must be set. */
  val command: String? = null,
  /** Additional command-line arguments for the Electron app. */
  val args: List<String> = emptyList(),
  /** Environment variables to set when launching the Electron app. */
  val env: Map<String, String> = emptyMap(),
  /** CDP remote debugging port (default 9222). */
  val cdpPort: Int = 9222,
  /** If set, attach to an already-running app at this CDP URL (skip launch). */
  val cdpUrl: String? = null,
  /** Seconds to wait for the CDP endpoint to become available. */
  val cdpTimeoutSeconds: Int = 30,
  /** Run the Electron app without showing a visible window. Sets ELECTRON_HEADLESS=true env var. */
  val headless: Boolean = false,
)

@Serializable
data class TrailSource(
  val type: TrailSourceType? = null,
  val reason: String? = null
)

@Serializable
enum class TrailSourceType {
  HANDWRITTEN,
}
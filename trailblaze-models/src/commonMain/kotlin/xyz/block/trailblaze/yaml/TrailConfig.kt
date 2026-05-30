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
   * Optional labels for grouping and filtering trails (e.g., `[smoke, login, flaky]`). Free-form
   * strings; informal namespacing is allowed (`flaky:retry-once`). Honored by future CLI tag
   * filters; today this field is metadata-only.
   */
  val tags: List<String>? = null,
  /**
   * When non-null, mark this trail as skipped with the given reason. A skipped trail is parsed
   * and validated but not executed — the CLI prints `Skipping <path>: <reason>` and exits 0 for
   * that file. Empty strings are treated as "not skipped" so an accidental `skip: ""` doesn't
   * silently disable a trail. The reason is required by convention (no `skip: true` shortcut)
   * so every disabled trail carries a tracked reason — typically an issue reference like
   * `"Compact element list regression — see #2194"`.
   */
  val skip: String? = null,
  /**
   * Pre-seed [xyz.block.trailblaze.AgentMemory] before any step runs. Each entry becomes
   * a remembered variable visible to `{{name}}` / `${'$'}{name}` interpolation in NL and
   * tool params and to scripted tools that read `ctx.memory.get("name")`. CLI
   * `--memory KEY=VAL` flags override entries with the same key in this block.
   *
   * Numeric and boolean YAML scalars are silently coerced to their string form by the
   * underlying YAML decoder (kaml) — `accountTier: 5` becomes `"5"`, `enabled: true`
   * becomes `"true"`. This matches user expectation that YAML is permissive about
   * primitives and avoids a quoting requirement that would make hand-edited trails
   * brittle. Downstream consumers (interpolation, scripting `ctx.memory.get`, the
   * `SessionStatus.Started` snapshot) always see strings.
   *
   * Appended at the end of the data class so existing positional component
   * accessors (component1..N) and binary-compatibility baselines for earlier
   * fields stay stable.
   */
  val memory: Map<String, String>? = null,
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
package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * The Trail Runner extension seam — the plugin surface a downstream desktop build fills in to layer
 * its own behavior onto the (otherwise open-source) Trail Runner web UI backend, without the Trail
 * Runner route code referencing anything downstream-internal.
 *
 * This mirrors how [xyz.block.trailblaze.ui.models.AppIconProvider] /
 * [xyz.block.trailblaze.ui.composables.DeviceClassifierIconProvider] are done: an open-source
 * interface with an open-source [DefaultTrailRunnerExtension] default, overridable by a downstream
 * build. It's exposed on [xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig.trailRunnerExtension] and consumed by
 * `TrailRunnerEndpoint.register(...)`.
 *
 * Every member is optional (defaults to a no-op / `null`); the open-source build supplies none of
 * them and Trail Runner degrades gracefully (integrations empty, analytics unavailable, the Blaze
 * "Create"/"Review" LLM assists disabled, etc.). Providers that need live runtime collaborators
 * (device manager, MCP bridge, LLM client) are assembled by the downstream desktop app where those
 * collaborators exist and passed to `register(...)`.
 */
interface TrailRunnerExtension {
  /** Real status for the Trail Runner "Integrations" screen (external tools a downstream build wires up). */
  val integrationsProvider: (() -> List<IntegrationDto>)?
    get() = null

  /**
   * Runs an integration's action, addressed generically by `(integrationId, actionId)`. A downstream
   * build wires its own launchers behind this seam; `null` disables integration actions. The handler
   * throws to signal failure — the run route reports it as an unsuccessful action.
   */
  val integrationActionHandler: (suspend (integrationId: String, actionId: String) -> Unit)?
    get() = null

  /** Supplies analytics events in `[startMs, endMs]` for the run-detail timeline. */
  val analyticsProvider: ((startMs: Long, endMs: Long) -> List<AnalyticsEventDto>)?
    get() = null

  /** Starts an analytics capture for a device; the returned [AutoCloseable] stops it. */
  val analyticsCaptureStarter: ((deviceId: TrailblazeDeviceId) -> AutoCloseable?)?
    get() = null

  /**
   * Executes a single [TrailblazeTool] against the given device, blocking until it completes, and
   * returns the tool's result string. Drives the Trailmaps "Run on device" tab.
   */
  val toolExecutor: (suspend (TrailblazeTool, TrailblazeDeviceId?) -> String)?
    get() = null

  /**
   * Blaze "Create": turns an objective into an ordered, platform-neutral step list. Plan-only when
   * `ground=false`; device-grounded when `ground=true`.
   */
  val proposeStepsProvider: (
    suspend (
      objective: String,
      target: String?,
      platform: String?,
      ground: Boolean,
      deviceId: TrailblazeDeviceId?,
    ) -> List<ProposedStep>
  )?
    get() = null

  /** "Review my trail": critiques a recorded trail's YAML for missing assertions / fragile selectors. */
  val reviewTrailProvider: (suspend (recordedYaml: String, target: String?, platform: String?) -> List<ReviewSuggestionDto>)?
    get() = null

  /** Re-resolves app-target ids against the CURRENT workspace (for the workspace-target-drift check). */
  val appTargetIdsProvider: (() -> Set<String>)?
    get() = null

  /**
   * Optional per-run event-stream capture, keyed by the run's session id (the long-lived daemon
   * dispatches concurrent runs, so a process-wide flag would let them clobber each other). Called
   * with `captureEvents=true` to start capture for a run; the returned [AutoCloseable] is closed when
   * the run ends. The open-source build has no event stream, so this defaults to `null` and the run
   * path skips it. A downstream build wires an implementation that drives its own capture gate.
   */
  val eventCaptureController: ((sessionId: String, captureEvents: Boolean) -> AutoCloseable?)?
    get() = null
}

/**
 * The open-source default: no integrations, no analytics, no LLM authoring assists, no pluggable
 * capture. Every member takes the interface default. Used by any build that doesn't override
 * [xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig.trailRunnerExtension].
 */
object DefaultTrailRunnerExtension : TrailRunnerExtension

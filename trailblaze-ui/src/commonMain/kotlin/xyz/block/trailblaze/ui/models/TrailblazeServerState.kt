package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.SELF_HEAL_DEFAULT
import xyz.block.trailblaze.ui.editors.yaml.YamlVisualEditorView
import xyz.block.trailblaze.ui.editors.yaml.YamlEditorMode
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode

@Serializable
data class TrailblazeServerState(
  val appConfig: SavedTrailblazeAppConfig,
) {
  @Serializable
  data class SavedTrailblazeAppConfig(
    val selectedTrailblazeDriverTypes: Map<TrailblazeDevicePlatform, TrailblazeDriverType>,
    val autoLaunchGoose: Boolean = false,
    val showDebugPopOutWindow: Boolean = false,
    val alwaysOnTop: Boolean = false,
    val serverPort: Int = HTTP_PORT,
    val serverHttpsPort: Int = HTTPS_PORT,
    val serverUrl: String = "http://localhost:$HTTP_PORT",
    val lastSelectedDeviceInstanceIds: List<String> = emptyList(),
    val terminateTargetAppBeforeRunning: Boolean = false,
    val selectedTargetAppId: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    // Default to the NONE sentinel so the OSS distro never auto-claims a user's
    // OPENAI_API_KEY / ANTHROPIC_API_KEY / etc. — external CLI users driving
    // Trailblaze through Claude Code, Codex, etc. opt in explicitly via
    // `trailblaze config llm <provider/model>` or per-run `--llm`. Downstream
    // distributions can override this in their own DesktopAppConfig subclass
    // when they ship a managed default.
    val llmProvider: String = TrailblazeLlmProvider.NONE.id,
    val llmModel: String = TrailblazeLlmProvider.NONE.id,
    val selfHealEnabled: Boolean = SELF_HEAL_DEFAULT,
    /** Agent implementation to use. Defaults to [AgentImplementation.DEFAULT]. */
    val agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
    val yamlContent: String = """
- prompts:
    - step: click back
""".trimIndent(), // Default YAML content
    // YAML editor UI preferences
    val yamlEditorMode: YamlEditorMode = YamlEditorMode.VISUAL,
    val yamlVisualEditorView: YamlVisualEditorView = YamlVisualEditorView.STEPS,
    // Session detail UI preferences
    val sessionDetailZoomOffset: Int = 0, // Zoom offset for card size
    val sessionDetailFontScale: Float = 1f, // Font size scale
    // Current session navigation state (cleared on app restart)
    val currentSessionId: String? = null, // Currently selected session
    val currentSessionViewMode: String = SessionViewMode.DEFAULT.name, // Current view mode
    // UI Inspector preferences
    val uiInspectorScreenshotWidth: Int = DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH,
    val uiInspectorDetailsWeight: Float = DEFAULT_UI_INSPECTOR_DETAILS_WEIGHT,
    val uiInspectorHierarchyWeight: Float = DEFAULT_UI_INSPECTOR_HIERARCHY_WEIGHT,
    val uiInspectorFontScale: Float = DEFAULT_UI_INSPECTOR_FONT_SCALE,
    // Window position and size
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
    // Logs directory path (null means use default: ~/.trailblaze/logs)
    val logsDirectory: String? = null,
    // Trails directory path (null means use default: ~/.trailblaze/trails)
    val trailsDirectory: String? = null,
    // Per-project Trailblaze config directory — layered on top of the classpath-bundled
    // framework config. When null, the settings repo discovers `trails/config/` via the
    // workspace walk-up rule, then falls back to null (classpath-only).
    // `TRAILBLAZE_CONFIG_DIR` env var wins over this when set.
    val trailblazeConfigDirectory: String? = null,
    // Root app data directory path (null means use default: ~/.trailblaze)
    val appDataDirectory: String? = null,
    // Tab visibility settings
    val showTrailsTab: Boolean = true, // Default true for backward compatibility
    val showDevicesTab: Boolean = false, // Default hidden - can be enabled in Settings
    val showWaypointsTab: Boolean = true, // Default visible — Block app overrides via initialConfig to hide as experimental
    val showDeviceStatusPanel: Boolean = false, // Floating device status overlay in bottom-right
    // Navigation rail expanded/collapsed state
    val navRailExpanded: Boolean = true, // Default to expanded
    val testingEnvironment: TestingEnvironment? = null,
    // Web browser visibility for MCP sessions (true = show browser window, false = headless)
    // Defaults to true so desktop app users can see the browser as tests run.
    val showWebBrowser: Boolean = true,
    /**
     * Desktop-app's stored viewport / device emulation spec for the Playwright web
     * browser. Accepts the same forms as the `trailblaze device create web --emulate`
     * / `--viewport` CLI flags and the desktop viewport picker: a Playwright
     * `devices` preset (e.g. `"iPhone 14"`) or raw `WIDTHxHEIGHT` (e.g. `"375x812"`).
     * Null = use the Playwright default (1280x800 desktop). Applied at browser
     * launch — close and re-launch to pick up a change. Use the `web_resize` tool
     * to change the viewport box mid-session without rebuilding the context.
     */
    val webViewport: String? = null,
    // Capture settings. Logcat on by default (filtered to the app under test, written to device.log).
    val captureLogcat: Boolean = true,
    // Capture the iOS Simulator system log. On by default: IosLogCapture scopes the stream to
    // the app under test at --level info (logcat-equivalent), not the system-wide firehose.
    val captureIosLogs: Boolean = true,
    /**
     * When true, every supported session auto-starts the framework network
     * capture engine — events stream to `<session-dir>/network.ndjson` with no
     * per-trail capture-start call required. Currently honored by Playwright
     * (web + Electron); on-device mobile engines plug into the same flag (a no-op
     * until an activator is registered). On by default so network traffic is
     * available in the run timeline and report without having to remember to opt
     * in; turn it off here (or via `--no-capture-network`) if the per-event I/O
     * cost on the engine's callback thread matters for a given run.
     */
    val captureNetworkTraffic: Boolean = true,
    /**
     * When true, the desktop app connects the device's analytics agent for the
     * duration of a run so events emitted during the trail can be surfaced in the
     * run timeline. Only yields events for an instrumented build; a plain app
     * captures nothing. Off by default. The actual capture mechanism is wired in
     * by the desktop app (kept out of this generic model).
     */
    val captureAnalytics: Boolean = false,
    // Last navigation route (restored on app restart)
    val lastRoute: String? = null, // Qualified class name of the last visited route
    // Self-test server: expose the live desktop window as a Compose RPC test target
    val enableSelfTestServer: Boolean = true,
    // CLI working mode: "trail" for authoring reproducible trails, "blaze" for exploration
    val cliMode: String? = null,
    // CLI device platform: "ANDROID", "IOS", or "WEB" — used as default when -d is not passed
    val cliDevicePlatform: String? = null,
    // Agent execution location: true = host controls via RPC, false = agent runs entirely on-device
    val preferHostAgent: Boolean = true,
    // Save the set-of-mark annotated screenshot variant to logs. The annotated
    // bytes are *always* sent to the LLM (set-of-mark improves model accuracy);
    // this flag only controls which variant gets persisted to `LogsRepo` for
    // inspection. When false, the un-annotated (raw) screenshot is saved
    // instead — useful when the saved screenshots feed downstream tooling that
    // wants clean pixels (e.g. waypoint authoring promotes raw screenshots as
    // committed examples).
    val saveAnnotatedScreenshots: Boolean = true,
    /**
     * Persisted per-machine cap on LLM calls per objective for the legacy TRAILBLAZE_RUNNER
     * agent. Set via `trailblaze config max-llm-calls <N>`. The CLI flag, the
     * `TRAILBLAZE_MAX_LLM_CALLS` env var, and workspace `trailblaze.yaml`
     * `defaults.max-llm-calls` all take precedence over this field; it kicks in only when
     * the higher tiers are silent. Null = inherit from those tiers (or fall back to the
     * runner's built-in default when they are all silent). See
     * `TrailCommand.resolveEffectiveMaxLlmCalls` for the full chain.
     */
    val maxLlmCalls: Int? = null,
    /**
     * Per-machine screenshot scaling overrides. Each field is null when the user has not
     * customized it; `screenshotScalingConfig()` materializes a full
     * [ScreenshotScalingConfig] by filling in unset fields from the framework defaults.
     * Workspace `trailblaze.yaml` `defaults.screenshot` still wins for any LLM model that
     * resolves through the workspace config.
     */
    val screenshotImageFormat: TrailblazeImageFormat? = null,
    val screenshotMaxLongerSide: Int? = null,
    val screenshotMaxShorterSide: Int? = null,
    val screenshotCompressionQuality: Float? = null,
    /**
     * When true, action commands (`tool`, `step`, `ask`, `verify`) reject calls
     * without a per-step natural-language description (`-s`/`--step`). The
     * description is the durable contract self-heal uses to retry a recorded
     * step when the UI changes. Off by default (permissive) so first-time tire-
     * kicking doesn't hit a wall; downstream distributions can flip the default
     * by setting this to true in their own desktop-app config subclass.
     */
    val requireSteps: Boolean = false,
  ) {
    /**
     * Materializes the user's effective [ScreenshotScalingConfig], substituting framework
     * defaults for any field the user hasn't overridden.
     */
    fun screenshotScalingConfig(): ScreenshotScalingConfig {
      val base = ScreenshotScalingConfig.DEFAULT
      // Coerce/validate persisted overrides so a hand-edited or corrupt settings file
      // can't crash downstream encoders (e.g. Skia's WebP path, which does no input
      // validation on the `(quality * 100).toInt()` conversion) or feed non-positive
      // dimensions into BufferedImageUtils.scale().
      val safeLonger = screenshotMaxLongerSide?.takeIf { it > 0 } ?: base.maxDimension1
      val safeShorter = screenshotMaxShorterSide?.takeIf { it > 0 } ?: base.maxDimension2
      val safeQuality = screenshotCompressionQuality?.coerceIn(0f, 1f) ?: base.compressionQuality
      return ScreenshotScalingConfig(
        maxDimension1 = safeLonger,
        maxDimension2 = safeShorter,
        imageFormat = screenshotImageFormat ?: base.imageFormat,
        compressionQuality = safeQuality,
      )
    }

    /** True when the user has customized at least one screenshot scaling field. */
    fun hasAnyScreenshotOverride(): Boolean =
      screenshotImageFormat != null ||
        screenshotMaxLongerSide != null ||
        screenshotMaxShorterSide != null ||
        screenshotCompressionQuality != null

    /**
     * Like [screenshotScalingConfig] but returns `null` when the user has overridden nothing,
     * preserving the "unset" signal for [EffectiveScreenshotScalingConfig.setEffectiveDefault].
     * That lets the web path ([EffectiveScreenshotScalingConfig.effectiveForWeb]) distinguish
     * "no user config → use the web default" from "user explicitly set values that happen to
     * equal the framework defaults → honor them". [effective] is unaffected (`null` still resolves
     * to [ScreenshotScalingConfig.DEFAULT], which is what a fully-unset config materializes to).
     */
    fun screenshotScalingConfigOrNull(): ScreenshotScalingConfig? =
      if (hasAnyScreenshotOverride()) screenshotScalingConfig() else null
  }

  @Serializable
  enum class ThemeMode {
    Light,
    Dark,
    System
  }

  @Serializable
  enum class TestingEnvironment(val displayName: String) {
    MOBILE("Mobile"),
    WEB("Web"),
  }

  companion object {
    const val HTTP_PORT = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
    const val HTTPS_PORT = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT

    // UI Inspector default panel dimensions
    // UI Inspector is opened by clicking on LLM request screenshots to debug what the LLM "saw".
    // These values provide a balanced initial layout for the three-panel inspection interface.
    // The screenshot panel has a fixed width, while details and hierarchy panels use weight ratios
    // to automatically fill remaining space. Users can adjust the weight ratio by dragging the
    // resizer between details and hierarchy panels, and their preferences are persisted.

    // Default width for screenshot panel showing app UI with overlays (fixed, in dp)
    const val DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH = 600

    // Default weight for details panel showing element properties (relative to hierarchy)
    const val DEFAULT_UI_INSPECTOR_DETAILS_WEIGHT = 1f

    // Default weight for hierarchy panel showing UI element tree (relative to details)
    const val DEFAULT_UI_INSPECTOR_HIERARCHY_WEIGHT = 1f

    // Default font scale (1.0 = 100%, adjustable from 0.5 to 2.0)
    const val DEFAULT_UI_INSPECTOR_FONT_SCALE = 1f
  }
}

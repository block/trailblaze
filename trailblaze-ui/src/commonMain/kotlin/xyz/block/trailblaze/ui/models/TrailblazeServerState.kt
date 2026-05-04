package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
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
    val llmProvider: String = DEFAULT_DESKTOP_APP_MODEL_LLM_MODEL.trailblazeLlmProvider.id,
    val llmModel: String = DEFAULT_DESKTOP_APP_MODEL_LLM_MODEL.modelId, // Default to GPT-4.1 model
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
    val showRecordTab: Boolean = false, // Default hidden - can be enabled in Settings
    val showWaypointsTab: Boolean = true, // Default visible — Block app overrides via initialConfig to hide as experimental
    val showDeviceStatusPanel: Boolean = false, // Floating device status overlay in bottom-right
    // Navigation rail expanded/collapsed state
    val navRailExpanded: Boolean = true, // Default to expanded
    val testingEnvironment: TestingEnvironment? = null,
    // Web browser visibility for MCP sessions (true = show browser window, false = headless)
    // Defaults to true so desktop app users can see the browser as tests run.
    val showWebBrowser: Boolean = true,
    // Local dev capture settings
    val captureLogcat: Boolean = false,
    // Capture iOS Simulator system logs (off by default — extremely high volume).
    val captureIosLogs: Boolean = false,
    /**
     * When true, every supported session auto-starts the framework network
     * capture engine — events stream to `<session-dir>/network.ndjson` with no
     * per-trail capture-start call required. Currently honored by Playwright
     * (web + Electron); on-device mobile engines plug
     * into the same flag. Off by default because the per-event I/O adds cost
     * on the engine's callback thread; flip on when investigating analytics
     * signals or any cross-cutting network behavior.
     */
    val captureNetworkTraffic: Boolean = false,
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
  ) {

    companion object {
      /** Centralized definition of our default LLM model for the desktop app */
      private val DEFAULT_DESKTOP_APP_MODEL_LLM_MODEL = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1
    }
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

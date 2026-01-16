package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.model.AI_FALLBACK_DEFAULT

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
    val serverUrl: String = "http://localhost:$HTTP_PORT",
    val lastSelectedTestRailAppName: String? = null,
    val lastSelectedTestRailSuiteId: Int? = null,
    val lastSelectedTestRailTestCaseId: Int? = null,
    val lastSelectedDeviceInstanceIds: List<String> = emptyList(),
    val terminateTargetAppBeforeRunning: Boolean = false,
    val lastSelectedTargetAppName: String? = null,
    val selectedTargetAppName: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val llmProvider: String = DEFAULT_DESKTOP_APP_MODEL_LLM_MODEL.trailblazeLlmProvider.id,
    val llmModel: String = DEFAULT_DESKTOP_APP_MODEL_LLM_MODEL.modelId, // Default to GPT-4.1 model
    val setOfMarkEnabled: Boolean = true,
    val aiFallbackEnabled: Boolean = AI_FALLBACK_DEFAULT,
    val yamlContent: String = """
- prompts:
    - step: click back
""".trimIndent(), // Default YAML content
    // Session detail UI preferences
    val sessionDetailZoomOffset: Int = 0, // Zoom offset for card size
    val sessionDetailFontScale: Float = 1f, // Font size scale
    // Current session navigation state (cleared on app restart)
    val currentSessionId: String? = null, // Currently selected session
    val currentSessionViewMode: String = "List", // Current view mode (List/Grid/LlmUsage/Recording)
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
    // Root app data directory path (null means use default: ~/.trailblaze)
    val appDataDirectory: String? = null,
    // Tab visibility settings
    val showTrailsTab: Boolean = true, // Default true for backward compatibility
    // Last navigation route (restored on app restart)
    val lastRoute: String? = null, // Qualified class name of the last visited route
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

  companion object {
    const val HTTP_PORT = 52525

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

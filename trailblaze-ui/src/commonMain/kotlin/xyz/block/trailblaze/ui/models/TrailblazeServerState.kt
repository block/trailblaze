package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeServerState(
  val appConfig: SavedTrailblazeAppConfig,
) {
  @Serializable
  data class SavedTrailblazeAppConfig(
    val hostModeEnabled: Boolean = false,
    val autoLaunchGoose: Boolean = false,
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
    val availableFeatures: AvailableFeatures,
    val experimentalFeatures: ExperimentalFeatures = ExperimentalFeatures(),
    val llmProvider: String = "openai", // Default to OpenAI provider
    val llmModel: String = "gpt-4.1", // Default to GPT-4.1 model
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
    val uiInspectorDetailsWidth: Int = DEFAULT_UI_INSPECTOR_DETAILS_WIDTH,
    val uiInspectorHierarchyWidth: Int = DEFAULT_UI_INSPECTOR_HIERARCHY_WIDTH,
    val uiInspectorFontScale: Float = DEFAULT_UI_INSPECTOR_FONT_SCALE,
    // Window position and size
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
  ) {
    @Serializable
    data class AvailableFeatures(
      val hostMode: Boolean,
    )

    @Serializable
    data class ExperimentalFeatures(
      val exportToRepoEnabled: Boolean = false,
    )
  }

  @Serializable
  enum class ThemeMode {
    Light,
    Dark,
    System
  }

  companion object {
    const val HTTP_PORT = 52525

    // UI Inspector default panel dimensions (in dp)
    // UI Inspector is opened by clicking on LLM request screenshots to debug what the LLM "saw".
    // These values provide a balanced initial layout for the three-panel inspection interface.
    // Users can adjust panel widths by dragging resizers, and their preferences are persisted.

    // Default width for screenshot panel showing app UI with overlays
    const val DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH = 600

    // Default width for details panel showing element properties
    const val DEFAULT_UI_INSPECTOR_DETAILS_WIDTH = 350

    // Default width for hierarchy panel showing UI element tree
    const val DEFAULT_UI_INSPECTOR_HIERARCHY_WIDTH = 450

    // Default font scale (1.0 = 100%, adjustable from 0.5 to 2.0)
    const val DEFAULT_UI_INSPECTOR_FONT_SCALE = 1f
  }
}

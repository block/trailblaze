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
    val themeMode: ThemeMode = ThemeMode.System,
    val availableFeatures: AvailableFeatures,
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
  }

  @Serializable
  enum class ThemeMode {
    Light,
    Dark,
    System
  }

  companion object {
    const val HTTP_PORT = 52525
  }
}

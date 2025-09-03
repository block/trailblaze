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
    val themeMode: ThemeMode = ThemeMode.System,
    val availableFeatures: AvailableFeatures,
    val llmProvider: String = "openai", // Default to OpenAI provider
    val llmModel: String = "gpt-4.1", // Default to GPT-4.1 model
    val yamlContent: String = """
- prompts:
    - step: click back
""".trimIndent(), // Default YAML content
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

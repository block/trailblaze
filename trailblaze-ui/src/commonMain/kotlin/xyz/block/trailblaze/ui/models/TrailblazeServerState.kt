package xyz.block.trailblaze.ui.models

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeServerState(
  val appConfig: SavedTrailblazeAppConfig,
) {
  @Serializable
  data class SavedTrailblazeAppConfig(
    val hostModeEnabled: Boolean = false,
    val autoLaunchBrowser: Boolean = false,
    val autoLaunchGoose: Boolean = false,
    val serverPort: Int = HTTP_PORT,
    val serverUrl: String = "http://localhost:$HTTP_PORT",
    val lastSelectedTestRailAppName: String? = null,
    val lastSelectedTestRailSuiteId: Int? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val availableFeatures: AvailableFeatures,
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

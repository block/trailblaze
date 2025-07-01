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
    val availableFeatures: AvailableFeatures,
  ) {
    @Serializable
    data class AvailableFeatures(
      val hostMode: Boolean,
    )
  }

  companion object {
    const val HTTP_PORT = 52525
  }
}

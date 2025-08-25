package xyz.block.trailblaze.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable

data class TrailblazeRoute(
  val id: String,
  val displayName: String,
  val isEnabled: Boolean = true,
  val icon: @Composable () -> Unit,
) {
  companion object {
    val Sessions = TrailblazeRoute("sessions", "Sessions") {
      Icon(Icons.Filled.List, contentDescription = "Sessions")
    }
    val YamlRoute = TrailblazeRoute("yaml", "Yaml") {
      Icon(Icons.Filled.Code, contentDescription = "Yaml")
    }
    val Settings = TrailblazeRoute("settings", "Settings") {
      Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
  }
}

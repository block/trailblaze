package xyz.block.trailblaze.ui.model

import androidx.compose.runtime.Composable

/**
 * Represents a navigation tab with its route, content, and display properties
 */
data class NavigationTab(
  val route: TrailblazeRoute,
  val content: @Composable () -> Unit,
  val label: String,
  val icon: @Composable () -> Unit,
  val isEnabled: Boolean = true,
)
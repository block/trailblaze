package xyz.block.trailblaze.ui.model

import androidx.compose.runtime.Composable

/**
 * Represents a navigation tab with its route and display properties.
 * Content for each route is defined in the NavHost composable routing.
 */
data class NavigationTab(
  val route: TrailblazeRoute,
  val label: String,
  val icon: @Composable () -> Unit,
  val isEnabled: Boolean = true,
)

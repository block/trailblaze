package xyz.block.trailblaze.ui.model

import androidx.compose.runtime.Composable

data class TrailblazeAppTab(
  val route: TrailblazeRoute,
  val content: @Composable () -> Unit,
)

package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.graphics.Color

/** Semantic color palette for the session progress view. */
internal object SessionProgressColors {
  val succeeded = Color(0xFF28A745)
  val inProgressAccentLight = Color(0xFFFFF3CD)
  val inProgressAccentBar = Color(0xFFFFC107)

  // Timeline tick colors by type
  val screenshotTick = Color(0xFF42A5F5) // blue
  val llmTick = Color(0xFFAB47BC) // purple
  val driverTick = Color(0xFF66BB6A) // green

  // Event marker colors by action kind (matches overlay annotation colors)
  val markerTap = Color(0xFF26A69A) // teal — warm but distinct from error red
  val markerSwipe = Color(0xFF1E88E5) // blue — matches swipe arrow overlay
  val markerAssert = Color(0xFF43A047) // green — matches assert border overlay
  val markerInput = Color(0xFFFB8C00) // orange
  val markerNav = Color(0xFF8E24AA) // purple
  val markerTool = Color(0xFF546E7A) // blue-gray
  val markerScreenshot = Color(0xFF42A5F5) // blue — matches screenshot tick
}

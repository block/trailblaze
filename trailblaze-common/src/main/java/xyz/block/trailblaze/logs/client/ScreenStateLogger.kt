package xyz.block.trailblaze.logs.client

fun interface ScreenStateLogger {
  fun logScreenState(screenState: TrailblazeScreenStateLog): String
}

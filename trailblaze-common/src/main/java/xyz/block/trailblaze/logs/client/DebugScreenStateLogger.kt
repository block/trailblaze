package xyz.block.trailblaze.logs.client

object DebugScreenStateLogger : ScreenStateLogger {
  override fun logScreenState(screenState: TrailblazeScreenStateLog) = "DebugScreenStateLogger: Logging screen state $screenState"
}

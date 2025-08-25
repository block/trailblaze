package xyz.block.trailblaze.ui.tabs.session

interface TrailblazeSessionListener {
  val trailblazeSessionId: String
  fun onSessionStarted()
  fun onUpdate(message: String)
  fun onSessionEnded()
}
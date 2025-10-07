package xyz.block.trailblaze.ui.tabs.session

enum class SessionViewMode {
  Grid,
  List,
  LlmUsage,
  Recording;

  fun toStringValue(): String = when (this) {
    Grid -> "Grid"
    List -> "List"
    LlmUsage -> "LlmUsage"
    Recording -> "Recording"
  }

  companion object {
    const val DEFAULT_VIEW_MODE = "List"

    fun fromString(value: String): SessionViewMode = when (value) {
      "Grid" -> Grid
      "LlmUsage" -> LlmUsage
      "Recording" -> Recording
      else -> List
    }
  }
}
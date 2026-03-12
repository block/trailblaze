package xyz.block.trailblaze.ui.tabs.session

enum class SessionViewMode {
  Timeline,
  Grid,
  LlmUsage,
  Recording;

  companion object {
    val DEFAULT = Timeline

    fun fromString(value: String): SessionViewMode = when (value) {
      "List" -> Timeline // Legacy migration
      else -> entries.find { it.name == value } ?: DEFAULT
    }
  }
}
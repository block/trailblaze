package xyz.block.trailblaze.ui.tabs.session.models

data class LogCardData(
  val title: String,
  val duration: Long?,
  val screenshotFile: String? = null,
  val deviceWidth: Int? = null,
  val deviceHeight: Int? = null,
  val preformattedText: String? = null,
)
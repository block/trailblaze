package xyz.block.trailblaze.logs.model

interface HasScreenshot {
  val deviceHeight: Int
  val deviceWidth: Int
  val screenshotFile: String?
}

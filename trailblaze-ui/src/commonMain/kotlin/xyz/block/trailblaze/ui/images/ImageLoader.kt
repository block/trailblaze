package xyz.block.trailblaze.ui.images

interface ImageLoader {
  fun getImageModel(sessionId: String, screenshotFile: String?): Any?
}

package xyz.block.trailblaze.ui.images

import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.getCurrentUrl
import xyz.block.trailblaze.ui.getPlatform

class NetworkImageLoader : ImageLoader {

  override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
    return screenshotFile?.let { filename ->
      if (filename.startsWith("http")) {
        // Already a full URL
        filename
      } else {
        // Check current URL to determine appropriate base URL
        // Jvm Impl returns 'null'

        val defaultLocalhostBaseUrl = "http://localhost:52525"
        val localhostStaticUrl = "$defaultLocalhostBaseUrl/static/$sessionId/$filename"
        return when (getPlatform()) {
          Platform.WASM -> {
            val currentUrl: String? = getCurrentUrl()
            if (currentUrl?.startsWith("http") == true) {
              localhostStaticUrl
            } else {
              // Relative Image
              "$sessionId/$filename"
            }
          }
          Platform.JVM -> localhostStaticUrl
        }
      }
    }
  }
}

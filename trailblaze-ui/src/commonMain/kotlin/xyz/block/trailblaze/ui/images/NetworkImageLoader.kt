package xyz.block.trailblaze.ui.images

import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.getCurrentUrl
import xyz.block.trailblaze.ui.getPlatform
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class NetworkImageLoader : ImageLoader {

  @OptIn(ExperimentalEncodingApi::class)
  override fun getImageModel(sessionId: String, screenshotFile: String?): Any? {
    return screenshotFile?.let { filename ->
      // Check if we have base64-encoded image data embedded in the HTML (WASM only)
      if (filename.startsWith("data:")) {
        val base64Data = filename.split("png;base64,").last()
        if (base64Data.isNotEmpty()) {
          return Base64.decode(base64Data)
        }
      }

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

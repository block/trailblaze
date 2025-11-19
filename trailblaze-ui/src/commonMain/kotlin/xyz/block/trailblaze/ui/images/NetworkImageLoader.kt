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
                // Handle both PNG and JPEG data URLs
                val base64Data = when {
                    filename.contains("png;base64,") -> filename.split("png;base64,", limit = 2).last()
                    filename.contains("jpeg;base64,") -> filename.split("jpeg;base64,", limit = 2).last()
                    else -> filename.substringAfter("base64,", "")
                }

                if (base64Data.isNotEmpty()) {
                    try {
                        // Clean the base64 string (remove any whitespace/newlines)
                        val cleanBase64 = base64Data.trim().replace("\n", "").replace("\r", "")
                        val decodedBytes = Base64.decode(cleanBase64)
                  return decodedBytes
              } catch (e: Exception) {
                  println("❌ NetworkImageLoader: Failed to decode base64: ${e.message}")
                  println("   Data URL preview: ${filename.take(100)}...")
                  println("   Base64 preview: ${base64Data.take(50)}...")
                  return null
              }
          } else {
              println("⚠️  NetworkImageLoader: Empty base64 data for data URL")
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
                // Check if we're on Buildkite artifacts
                if (currentUrl.contains("buildkiteartifacts.com")) {
                    // For Buildkite, construct full URL using the base path of current URL
                    // Current URL format: https://web.buildkiteartifacts.com/ba3.../trailblaze_report.html
                    // Image URL should be: https://web.buildkiteartifacts.com/ba3.../$sessionId/$filename
                    val baseUrl = currentUrl.substringBeforeLast('/')
                    val fullUrl = "$baseUrl/$filename"
                    println("🔗 Buildkite artifact URL constructed: $fullUrl")
                    fullUrl
                } else {
                    // Use localhost static server
                    localhostStaticUrl
                }
            } else {
                // Relative Image (for local file:// URLs)
              "$sessionId/$filename"
            }
          }
          Platform.JVM -> localhostStaticUrl
        }
      }
    }
  }
}

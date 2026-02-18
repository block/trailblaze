package xyz.block.trailblaze.ui.images

import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.getCurrentUrl
import xyz.block.trailblaze.ui.getPlatform
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import xyz.block.trailblaze.util.Console

class NetworkImageLoader(
    private val serverBaseUrl: String = currentServerBaseUrl,
) : ImageLoader {

    companion object {
        /**
         * The current server base URL used by all default-constructed [NetworkImageLoader] instances.
         * Updated at app startup when port overrides are applied.
         */
        var currentServerBaseUrl: String = "http://localhost:${TrailblazeServerState.HTTP_PORT}"
    }

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
                  Console.log("❌ NetworkImageLoader: Failed to decode base64: ${e.message}")
                  Console.log("   Data URL preview: ${filename.take(100)}...")
                  Console.log("   Base64 preview: ${base64Data.take(50)}...")
                  return null
              }
          } else {
              Console.log("⚠️  NetworkImageLoader: Empty base64 data for data URL")
        }
      }

      if (filename.startsWith("http")) {
        // Already a full URL
        filename
      } else {
        // Check current URL to determine appropriate base URL
        // Jvm Impl returns 'null'

        val localhostStaticUrl = "$serverBaseUrl/static/$sessionId/$filename"
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
                    Console.log("🔗 Buildkite artifact URL constructed: $fullUrl")
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

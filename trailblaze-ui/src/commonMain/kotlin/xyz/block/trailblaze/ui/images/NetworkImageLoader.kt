package xyz.block.trailblaze.ui.images

import xyz.block.trailblaze.ui.models.TrailblazeServerState

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

    override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
        return screenshotFile?.let { filename ->
            if (filename.startsWith("http")) {
                // Already a full URL
                filename
            } else {
                // filename may already carry the sessionId/ prefix, depending on which report wrote it.
                if (filename.startsWith("$sessionId/")) {
                    "$serverBaseUrl/static/$filename"
                } else {
                    "$serverBaseUrl/static/$sessionId/$filename"
                }
            }
        }
    }
}

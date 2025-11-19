// Use external functions to call JS functions defined in global scope

external fun getTrailblazeReportJsonFromBrowser(key: String, callback: (String) -> Unit)

/**
 * Optional JavaScript hook to transform image URLs before resolution.
 * Default implementation returns the URL unchanged.
 * Can be overridden in HTML to provide custom transformations (e.g., for Buildkite artifacts).
 */
external fun transformImageUrl(screenshotRef: String): String

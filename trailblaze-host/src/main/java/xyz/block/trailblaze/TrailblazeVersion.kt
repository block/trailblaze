package xyz.block.trailblaze

import java.util.Properties

/**
 * Provides version information for the Trailblaze application.
 *
 * The version is read from a generated version.properties file that is created
 * during the Gradle build. If the file is not found (e.g., running from IDE
 * without a full build), it falls back to "Developer Build".
 */
object TrailblazeVersion {
  /**
   * The current version string.
   * Format: YYYYMMDD.HHMMSS.commithash (e.g., 20250205.143021.a1b2c3d)
   * Falls back to "Developer Build" if version info is not available.
   */
  val version: String by lazy {
    try {
      val props = Properties()
      val stream = TrailblazeVersion::class.java.getResourceAsStream("/version.properties")
      if (stream != null) {
        props.load(stream)
        stream.close()
        props.getProperty("version") ?: "Developer Build"
      } else {
        "Developer Build"
      }
    } catch (_: Exception) {
      "Developer Build"
    }
  }

  /**
   * Returns a display-friendly version string for the UI.
   */
  val displayVersion: String
    get() = if (version == "Developer Build") version else "v$version"
}

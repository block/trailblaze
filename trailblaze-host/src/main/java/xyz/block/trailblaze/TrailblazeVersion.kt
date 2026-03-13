package xyz.block.trailblaze

import java.util.Properties

/**
 * Provides version information for the Trailblaze application.
 *
 * Version and variant are read from a generated version.properties file created
 * during the Gradle build. If the file is not found (e.g., running from IDE
 * without a full build), it falls back to "Developer Build".
 *
 * The variant property is set via `trailblaze.variant` in gradle.properties.
 * Internal builds set it to "Internal"; open source builds omit it entirely.
 */
object TrailblazeVersion {
  private val props: Properties by lazy {
    val p = Properties()
    try {
      TrailblazeVersion::class.java.getResourceAsStream("/version.properties")?.use { p.load(it) }
    } catch (_: Exception) {}
    p
  }

  /**
   * The current version string.
   * For release builds: semver from the git tag (e.g., 1.2.3)
   * For dev builds: YYYYMMDD.HHMMSS.commithash (e.g., 20250205.143021.a1b2c3d)
   * Falls back to "Developer Build" if version info is not available.
   */
  val version: String by lazy {
    props.getProperty("version") ?: "Developer Build"
  }

  /**
   * The build variant, or null for open source builds.
   * Set via `trailblaze.variant` in gradle.properties (e.g., "Internal").
   */
  val variant: String? by lazy {
    props.getProperty("variant")
  }

  /**
   * Returns a display-friendly version string for the UI.
   * Examples: "v1.2.3 (Internal)", "v1.2.3", "Developer Build"
   */
  val displayVersion: String
    get() {
      val base = if (version == "Developer Build") version else "v$version"
      return if (variant != null) "$base ($variant)" else base
    }
}

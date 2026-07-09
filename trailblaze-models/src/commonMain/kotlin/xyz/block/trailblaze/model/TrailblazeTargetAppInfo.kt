package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable

/**
 * Identity and version of the app under test, captured at runtime from the device the session
 * runs against. Complements [xyz.block.trailblaze.devices.TrailblazeDeviceInfo] (which describes
 * the device) by describing the *target app* actually installed when the session started, so
 * session summaries and reports can answer "which build was this run against?".
 *
 * For Android:
 * - versionName: user-visible version string (e.g. "5.58.0.0")
 * - versionCode: internal version number (e.g. "67500009")
 *
 * For iOS:
 * - versionName: CFBundleShortVersionString (e.g. "6.94")
 * - versionCode: CFBundleVersion (e.g. "6940515")
 * - buildNumber: app-specific build number (e.g. "6515")
 *
 * Capture is best-effort: any field the platform can't provide at run time stays null.
 */
@Serializable
data class TrailblazeTargetAppInfo(
  /** Resolved package name (Android) or bundle identifier (iOS). */
  val appId: String,
  val versionName: String? = null,
  val versionCode: String? = null,
  val buildNumber: String? = null,
  /** Extra platform-specific values captured at runtime. */
  val metadata: Map<String, String> = emptyMap(),
)

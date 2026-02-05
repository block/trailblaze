package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Contains version information for an installed app.
 *
 * For Android:
 * - versionCode: The internal version number (e.g., "67500009")
 * - versionName: The user-visible version string (e.g., "6.75.0")
 * - minOsVersion: Minimum OS version required (e.g., 28 for Android SDK)
 *
 * For iOS:
 * - versionCode: CFBundleVersion (e.g., "6940515")
 * - versionName: CFBundleShortVersionString (e.g., "6.94")
 * - buildNumber: App-specific build number (e.g., "6515")
 * - appBundlePath: Path to the app bundle (iOS only), allows reading additional plist keys
 */
@Serializable
data class AppVersionInfo(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val versionCode: String,
  val versionName: String? = null,
  val minOsVersion: Int? = null,
  val buildNumber: String? = null,
  /** Path to the app bundle (iOS) or APK data directory (Android). Used by app-specific code to read custom metadata. */
  val appBundlePath: String? = null,
)

package xyz.block.trailblaze.device

import kotlinx.serialization.Serializable

/**
 * The framework's **deep, cross-platform record** for an installed app — the per-app element the
 * `mobile_listInstalledAppsDetailed` primitive returns. Lighter surfaces project from it: the
 * `mobile_listInstalledApps` tool is just the app ids (`apps.map { it.appId }`), and other tools
 * delegate the same way. New cross-platform fields are added here as the gatherers learn to populate
 * them; growing the record is non-structural — every consumer picks the new field up for free.
 *
 * Declaring this as a `@Serializable` class lets kotlinx.serialization produce the JSON for the tool
 * result instead of hand-assembling it, so the wire shape stays in lockstep with this type. Metadata
 * fields are nullable and, with the shared Json's `encodeDefaults = false`, a `null` value is omitted
 * — so a field a given platform/path can't supply simply doesn't appear in the JSON.
 *
 * @property appId Android package name or iOS bundle identifier. Always present.
 * Only [label] is ever absent: on the **Android host/adb path** it needs resource resolution that
 * adb can't do cheaply. Everything else is populated on all three paths (host adb, Android
 * on-device, iOS) — the host path now reads them from a single `dumpsys package packages` call.
 *
 * @property appId Android package name or iOS bundle identifier. Always present.
 * @property isSystemApp `true` for an OS / platform app (Android `FLAG_SYSTEM`, including updated
 *   system apps; iOS `ApplicationType = System`), `false` for a user-/third-party-installed app.
 *   Always populated — classification comes from the device's own report (`dumpsys` `flags`/
 *   `ApplicationInfo.FLAG_SYSTEM` on Android, iOS `ApplicationType`), so there's no curated
 *   allow-list to drift; the only non-standard case (an iOS `ApplicationType` neither `System` nor
 *   `User`, essentially never seen) is treated as `false` rather than guessed into the system bucket.
 * @property label Human-readable display name (Android `PackageManager.getApplicationLabel`; iOS
 *   `CFBundleDisplayName`, falling back to `CFBundleName`). **The one host-null field**: `null` on
 *   the Android host/adb path, which has no cheap label lookup (it would mean `aapt dump badging` on
 *   a pulled APK). Populated on the Android on-device driver and on iOS.
 * @property version User-visible version string (Android `versionName`; iOS
 *   `CFBundleShortVersionString`, falling back to `CFBundleVersion`). Populated on every path.
 * @property buildNumber Machine version / build number (Android `versionCode`; iOS
 *   `CFBundleVersion`). The monotonic counter behind the user-visible [version]. Populated on every path.
 * @property installPath Absolute path to the installed app on the device's filesystem — the APK /
 *   code path on Android (`dumpsys codePath` on host, `ApplicationInfo.sourceDir` on-device) or the
 *   `.app` bundle path on iOS (`Path`). Populated on every path.
 */
@Serializable
data class InstalledApp(
  val appId: String,
  val isSystemApp: Boolean,
  val label: String? = null,
  val version: String? = null,
  val buildNumber: String? = null,
  val installPath: String? = null,
)

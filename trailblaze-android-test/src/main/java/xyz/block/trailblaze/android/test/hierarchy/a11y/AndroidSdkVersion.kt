// Mirrors trailblaze-android's AndroidSdkVersion.kt, so the in-process driver maps the platform's accessibility
// tree exactly as the accessibility driver does without depending on that driver's module.
// Keep the two in step; consolidating them into one shared module is a follow-up.
package xyz.block.trailblaze.android.test.hierarchy.a11y

import android.os.Build

/**
 * Centralized API-level checks. Use this instead of raw `Build.VERSION.SDK_INT` comparisons
 * so all version gates are discoverable by searching for [AndroidSdkVersion].
 */
object AndroidSdkVersion {

  /** Returns `true` when the device is running API [level] or higher. */
  fun isAtLeast(level: Int): Boolean = Build.VERSION.SDK_INT >= level

  /** Returns `true` when the device is running below API [level]. */
  fun isBelow(level: Int): Boolean = Build.VERSION.SDK_INT < level
}

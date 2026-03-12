package xyz.block.trailblaze.android

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

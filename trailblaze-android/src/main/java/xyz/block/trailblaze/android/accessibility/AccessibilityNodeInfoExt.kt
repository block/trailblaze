package xyz.block.trailblaze.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import xyz.block.trailblaze.android.AndroidSdkVersion

/**
 * Prepares this node copy for snapshot conversion and returns its screen bounds.
 *
 * Call before reading its fields or children so both snapshot projections use the refreshed copy.
 */
internal fun AccessibilityNodeInfo.prepareForSnapshot(): Rect {
  // Older caches can retain stale descendants when a subtree-change event comes from an
  // uncached ancestor (AOSP b/111554539; the cache-clearing fix is present in Android 10):
  // https://android.googlesource.com/platform/frameworks/base/+/a4002c8daf272f550787d94bed8e81749a891e66
  // Refresh this copy as it is converted: the earlier walk recycles its refreshed copies
  // without updating the client cache.
  if (!AndroidSdkVersion.isAtLeast(29)) refresh()
  return Rect().apply { getBoundsInScreen(this) }
}

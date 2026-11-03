// Mirrors trailblaze-android's accessibility/AccessibilityNodeInfoExt.kt, so the in-process driver maps the platform's accessibility
// tree exactly as the accessibility driver does without depending on that driver's module.
// Keep the two in step; consolidating them into one shared module is a follow-up.
package xyz.block.trailblaze.android.test.hierarchy.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Prepares this node copy for snapshot conversion and returns its screen bounds.
 *
 * Call before reading its fields or children so both snapshot projections use the refreshed copy.
 */
internal fun AccessibilityNodeInfo.prepareForSnapshot(refreshEach: Boolean = false, tally: TreeCaptureTally? = null): Rect {
  // Older caches can retain stale descendants when a subtree-change event comes from an
  // uncached ancestor (AOSP b/111554539; the cache-clearing fix is present in Android 10):
  // https://android.googlesource.com/platform/frameworks/base/+/a4002c8daf272f550787d94bed8e81749a891e66
  // Refresh this copy as it is converted: the earlier walk recycles its refreshed copies
  // without updating the client cache.
  //
  // [refreshEach] refreshes on every version: a caller that must not read a value the cache kept
  // past the app's last frame, where the platform offers no cache clear.
  //
  // A [refreshEach] refresh that fails leaves the cached values, which may describe a node that is
  // gone, so it counts as a lost fetch in [tally]: the capture then reads as incomplete.
  if (refreshEach || !AndroidSdkVersion.isAtLeast(29)) {
    if (!refresh() && refreshEach) tally?.recordDroppedNode()
  }
  return Rect().apply { getBoundsInScreen(this) }
}

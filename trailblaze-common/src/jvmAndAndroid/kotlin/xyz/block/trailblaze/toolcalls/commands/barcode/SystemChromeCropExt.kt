package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.LuminanceSource
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.util.Console

/**
 * View class-name substrings that identify system or browser chrome across the top of an iOS
 * screenshot. The battery indicator and browser-tab favicons in those bars read as QR finder
 * patterns, so ZXing spends its full-image attempt on them instead of on the real barcode.
 *
 * - `StatusBar` — the iOS system status bar (clock, signal, battery)
 * - `NavigationBar` — native navigation bars, and the Safari/Chrome address bar
 * - `TabBar` — a browser tab strip
 *
 * Deliberately separate from the repo's other system-UI detectors — `ViewHierarchyFilter`'s
 * Android `status_bar_container` resource ids, and `IosCompactElementList`'s bounds-based
 * `isSystemUi`. Those decide what to *show* the LLM and are tuned to be conservative; this one
 * decides which pixels to *skip*, where over-cropping is cheap and under-cropping breaks the
 * decode. If a fifth caller appears, that is the point to hoist a shared "content region" onto
 * `ScreenState` instead of adding another list.
 */
private val SYSTEM_CHROME_CLASS_KEYWORDS = listOf("StatusBar", "NavigationBar", "TabBar")

/**
 * Returns [this] with any top-of-screen system chrome cropped off, measured from [viewHierarchy]
 * rather than guessed at a fixed offset — chrome height varies by device, by orientation, and by
 * whether a browser tab strip is showing.
 *
 * Only chrome ending within the top quarter of the screen counts, so a bottom tab bar never
 * triggers a crop that would throw away the whole screenshot. Returns [this] unchanged when no
 * chrome is found, when the source cannot be cropped, or when the crop would be empty.
 *
 * @param deviceHeight logical screen height, used with [LuminanceSource.height] to convert
 *   view-hierarchy points into screenshot pixels.
 */
internal fun LuminanceSource.cropAwaySystemChrome(
  viewHierarchy: ViewHierarchyTreeNode,
  deviceHeight: Int,
): LuminanceSource {
  if (!isCropSupported || deviceHeight <= 0) return this

  val topQuarterLogical = deviceHeight / 4
  val chromeBottomLogical = viewHierarchy.aggregate()
    .filter { node ->
      val className = node.className ?: return@filter false
      SYSTEM_CHROME_CLASS_KEYWORDS.any { keyword -> className.contains(keyword) }
    }
    .mapNotNull { node -> node.bounds?.y2 }
    .filter { bottomY -> bottomY <= topQuarterLogical }
    .maxOrNull()
    ?: return this

  // Logical (view-hierarchy) points to physical screenshot pixels.
  val scale = height.toFloat() / deviceHeight
  val cropY = (chromeBottomLogical * scale).toInt().coerceAtMost(height - 1)
  val cropHeight = height - cropY
  if (cropY <= 0 || cropHeight <= 0) return this

  Console.log(
    "Cropping ${cropY}px of system chrome off the top before scanning for a barcode " +
      "(chrome ends at ${chromeBottomLogical}pt, ${scale}x scale)",
  )
  return crop(0, cropY, width, cropHeight)
}

package xyz.block.trailblaze.android.test

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import java.io.ByteArrayOutputStream
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * One non-overlapping snapshot of a mixed Android View and Compose screen.
 *
 * Classic View regions are collected in-process from the live view objects and carry the
 * `androidView` node vocabulary; Compose-host regions are replaced with the app's native Compose
 * test semantics nodes. This keeps the hierarchy aligned with the two action backends instead of
 * exposing duplicate accessibility + native representations of the same control.
 *
 * [viewByNodeId] and [semanticsIdByNodeId] are specific to this driver and deliberately not part
 * of [ScreenState]: they hold references into the live UI, so they are only meaningful to code
 * running in the same process against this exact snapshot.
 *
 * @param screenshotScalingConfig how [screenshotBytes] is scaled and encoded. Non-null when the
 *   caller was handed an output contract to honor — the on-device RPC captor forwards the host's
 *   `GetScreenStateRequest` fields. Null keeps the unscaled, lossless PNG the farm replay path has
 *   always produced: those frames go straight into the report, and no caller declares a size or
 *   format for them.
 * @param includeTree whether to build the hybrid hierarchy at all. The collector's walk runs on
 *   the app's main thread and costs ~4ms fixed plus ~2us per View, against ~2us for a tree-less
 *   snapshot; the host's device mirror pays it 5x/sec for a tree nobody reads. `false` therefore
 *   SKIPS the walk rather than deferring it: the (screenshot, tree) pair must be atomic whenever a
 *   tree exists (see `GetScreenStateRequest.includeTree`), so a tree-less snapshot has no tree
 *   ever rather than one walked late.
 *
 *   The [ScreenState] surface then reads exactly as it does on the other two Android drivers —
 *   [viewHierarchy] empty, [trailblazeNodeTree] null — so the flag means one thing across all of
 *   them and a generic consumer cannot crash on this driver alone. The members below that are NOT
 *   part of [ScreenState] ([requiredNodeTree], [viewByNodeId], [semanticsIdByNodeId]) still throw:
 *   only in-process driver code can reach them, and each exists to act on a live node, which an
 *   empty answer would silently degrade into "element not found".
 */
class AndroidTestScreenState(
  private val target: AndroidTestTarget,
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  includeScreenshot: Boolean = true,
  private val screenshotScalingConfig: ScreenshotScalingConfig? = null,
  includeTree: Boolean = true,
) : ScreenState {
  private val activity = target.currentActivity()
  private val collected: AndroidHybridHierarchyCollector.Collected? =
    if (includeTree) AndroidHybridHierarchyCollector.collect(activity, target) else null
  private val bitmap: Bitmap? = if (includeScreenshot) target.captureScreenshot() else null

  override val trailblazeNodeTree: TrailblazeNode? = collected?.trailblazeTree
  override val viewHierarchy: ViewHierarchyTreeNode =
    collected?.legacyViewTree ?: ViewHierarchyTreeNode()

  /**
   * [trailblazeNodeTree] for the driver's own tools, which resolve selectors against it and have
   * no meaningful answer without one. Throws rather than returning null so a tree-less snapshot
   * reaching a tool is reported as the capture mistake it is, not as an unmatched selector.
   */
  val requiredNodeTree: TrailblazeNode get() = requireTree().trailblazeTree

  /** The live `View` each classic-View node was built from. */
  val viewByNodeId: Map<Long, View> get() = requireTree().viewByNodeId

  /** The `SemanticsNode.id` each Compose node was built from. */
  val semanticsIdByNodeId: Map<Long, Int> get() = requireTree().semanticsIdByNodeId

  private fun requireTree(): AndroidHybridHierarchyCollector.Collected = checkNotNull(collected) {
    "This snapshot was captured with includeTree=false, so it has no hierarchy. Capturing one " +
      "now would break the atomic (screenshot, tree) contract — take a new snapshot with " +
      "includeTree=true instead."
  }
  override val deviceWidth: Int = bitmap?.width ?: activity.resources.displayMetrics.widthPixels
  override val deviceHeight: Int = bitmap?.height ?: activity.resources.displayMetrics.heightPixels
  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID

  override val screenshotBytes: ByteArray? by lazy {
    bitmap?.let { source ->
      val config = screenshotScalingConfig ?: return@let source.encode(
        format = Bitmap.CompressFormat.PNG,
        quality = 100,
      )
      val (width, height) = config.computeScaledDimensions(source.width, source.height)
      // A scaled COPY, not `AndroidBitmapUtils.scale`'s recycle-the-original: `source` is this
      // snapshot's screenshot, and re-reading it after a recycle would throw.
      val scaled = if (width == source.width && height == source.height) {
        null
      } else {
        Bitmap.createScaledBitmap(source, width, height, true)
      }
      try {
        (scaled ?: source).encode(
          format = config.imageFormat.toCompressFormat(),
          // Android takes 0..100; the config carries 0.0..1.0.
          quality = (config.compressionQuality * 100).toInt().coerceIn(0, 100),
        )
      } finally {
        scaled?.recycle()
      }
    }
  }

  private val compactElements: CompactScreenElements? by lazy {
    val tree = trailblazeNodeTree ?: return@lazy null
    CompactScreenElements.buildForAndroid(tree, screenHeight = deviceHeight)
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    compactElements?.buildTextRepresentation(
      foregroundAppId = activity.packageName,
      currentActivity = activity.javaClass.name,
    )
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }
}

private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray {
  val bitmap = this
  ByteArrayOutputStream().use { output ->
    check(bitmap.compress(format, quality, output)) { "Failed to encode screenshot as $format" }
    return output.toByteArray()
  }
}

/**
 * Duplicates the mapping in `AndroidBitmapUtils`, which lives in `trailblaze-android` — the
 * accessibility driver's module, which this one deliberately does not depend on. Hoisting it to a
 * shared module is worth doing once a second thing here needs bitmap work; the scaling math itself
 * is already shared, via [ScreenshotScalingConfig.computeScaledDimensions].
 */
private fun TrailblazeImageFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
  TrailblazeImageFormat.PNG -> Bitmap.CompressFormat.PNG
  TrailblazeImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
  TrailblazeImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Bitmap.CompressFormat.WEBP_LOSSY
  } else {
    @Suppress("DEPRECATION")
    Bitmap.CompressFormat.WEBP
  }
}

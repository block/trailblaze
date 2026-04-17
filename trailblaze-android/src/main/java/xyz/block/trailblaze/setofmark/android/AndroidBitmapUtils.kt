package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.android.MemoryDiagnostics
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.io.ByteArrayOutputStream

object AndroidBitmapUtils {

  fun Bitmap.toByteArray(
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    quality: Int = 100,
  ): ByteArray {
    val bitmap = this
    ByteArrayOutputStream().use {
      check(bitmap.compress(format, quality, it)) { "Failed to compress bitmap" }
      return it.toByteArray()
    }
  }

  /**
   * Converts a Bitmap to a byte array using ScreenshotScalingConfig format settings.
   */
  fun Bitmap.toByteArray(
    format: TrailblazeImageFormat,
    compressionQuality: Float,
  ): ByteArray {
    val androidFormat = when (format) {
      TrailblazeImageFormat.PNG -> Bitmap.CompressFormat.PNG
      TrailblazeImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
      TrailblazeImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
      } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
      }
    }
    // Android quality is 0-100, our config is 0.0-1.0
    val androidQuality = (compressionQuality * 100).toInt().coerceIn(0, 100)
    return toByteArray(androidFormat, androidQuality)
  }

  fun Bitmap.scale(
    scale: Float,
  ): Bitmap {
    if (scale == 1f) {
      return this // No need to scale
    }
    val newWidth = (width * scale).toInt()
    val newHeight = (height * scale).toInt()
    // Avoid unnecessary scaling if dimensions do not change
    if (newWidth == width && newHeight == height) {
      return this
    }
    val scaledBitmap = Bitmap.createScaledBitmap(
      this,
      newWidth,
      newHeight,
      true,
    )
    this.recycle() // Recycle the original bitmap to free up memory
    return scaledBitmap
  }

  fun Bitmap.scale(
    maxDim1: Int,
    maxDim2: Int,
  ): Bitmap {
    val targetLong = maxOf(maxDim1, maxDim2)
    val targetShort = minOf(maxDim1, maxDim2)

    val imageLong = maxOf(width, height)
    val imageShort = minOf(width, height)

    // Only scale down if image exceeds bounds
    if (imageLong <= targetLong && imageShort <= targetShort) {
      return this // Already fits, no scaling needed
    }

    // Calculate scale factors for both dimensions
    val scaleLong = targetLong.toFloat() / imageLong
    val scaleShort = targetShort.toFloat() / imageShort
    val scaleAmount = minOf(scaleLong, scaleShort)

    return this.scale(scaleAmount)
  }

  /**
   * Scales a raw bitmap per [config] dimensions, encodes using the config's format and quality,
   * and recycles the bitmap. Returns the compressed byte array.
   */
  fun Bitmap.scaleAndEncode(config: ScreenshotScalingConfig): ByteArray {
    val scaled = this.scale(maxDim1 = config.maxDimension1, maxDim2 = config.maxDimension2)
    // Note: scale() already recycles `this` when it creates a new bitmap,
    // so we only need to recycle `scaled` here (which may be `this` if no scaling occurred).
    val bytes = scaled.toByteArray(config.imageFormat, config.compressionQuality)
    scaled.recycle()
    return bytes
  }

  /**
   * Decodes [screenshotBytes], applies set-of-mark annotations, and re-encodes using [config].
   *
   * Shared by [AndroidOnDeviceUiAutomatorScreenState] and [AccessibilityServiceScreenState]
   * to avoid duplicating the decode → annotate → encode pipeline.
   *
   * When [annotationElements] is provided, uses those to draw ref labels (e.g., "y778")
   * that match the text representation sent to the LLM. Falls back to deriving elements
   * from [viewHierarchy] with numeric nodeIds when annotation elements are unavailable.
   *
   * @return Annotated bytes, or [screenshotBytes] unchanged if decoding/copy fails.
   */
  fun annotateScreenshotBytes(
    screenshotBytes: ByteArray,
    config: ScreenshotScalingConfig,
    viewHierarchy: ViewHierarchyTreeNode,
    deviceWidth: Int,
    deviceHeight: Int,
    annotationElements: List<AnnotationElement>? = null,
    oomContext: String = "annotateScreenshotBytes",
  ): ByteArray {
    if (screenshotBytes.isEmpty()) return screenshotBytes

    var bitmap: Bitmap? = null
    var annotatedBitmap: Bitmap? = null
    try {
      bitmap = BitmapFactory.decodeByteArray(screenshotBytes, 0, screenshotBytes.size)
        ?: return screenshotBytes

      annotatedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
      bitmap.recycle()
      bitmap = null

      // bitmap.copy() can return null under memory pressure
      if (annotatedBitmap == null) return screenshotBytes

      if (annotationElements != null && annotationElements.isNotEmpty()) {
        AndroidCanvasSetOfMark.drawAnnotationsOnBitmap(
          originalScreenshotBitmap = annotatedBitmap,
          annotations = annotationElements,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
        )
      } else {
        val filtered = ViewHierarchyFilter.create(
          screenHeight = deviceHeight,
          screenWidth = deviceWidth,
          platform = TrailblazeDevicePlatform.ANDROID,
        ).filterInteractableViewHierarchyTreeNodes(viewHierarchy)
        AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
          originalScreenshotBitmap = annotatedBitmap,
          elements = ViewHierarchyTreeNodeUtils.from(
            filtered,
            DeviceInfo(
              platform = Platform.ANDROID,
              widthPixels = deviceWidth,
              heightPixels = deviceHeight,
              widthGrid = deviceWidth,
              heightGrid = deviceHeight,
            ),
          ),
          includeLabel = true,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
        )
      }

      val bytes = annotatedBitmap.toByteArray(config.imageFormat, config.compressionQuality)
      annotatedBitmap.recycle()
      annotatedBitmap = null
      return bytes
    } catch (e: OutOfMemoryError) {
      bitmap?.recycle()
      annotatedBitmap?.recycle()
      MemoryDiagnostics.dumpOnOom(e, oomContext)
      throw e
    } catch (e: Exception) {
      bitmap?.recycle()
      annotatedBitmap?.recycle()
      throw e
    }
  }
}

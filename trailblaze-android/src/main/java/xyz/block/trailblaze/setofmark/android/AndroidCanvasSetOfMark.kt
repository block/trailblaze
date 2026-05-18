package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.setofmark.SetOfMarkLayout
import xyz.block.trailblaze.setofmark.SetOfMarkPalette
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * See https://github.com/takahirom/arbigent/blob/11b7887248ee131ab91a222f0f6d4ef80328853c/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt#L32
 *
 * Cross-platform atoms (base text size, base padding, color palette) live in
 * [SetOfMarkPalette] so the Android and Host (AWT) implementations of set-of-mark stay
 * in sync on those values.
 *
 * **Per-platform scale math is still local.** The host-side equivalent
 * `HostCanvasSetOfMark` (in `trailblaze-common`) uses a step function on total image area
 * (`COMPACT_MODE_IMAGE_AREA = 1_000_000L` flips text from 24f to 14f). Android scales
 * continuously by the bitmap-to-device downscale ratio (see [drawScaleFor]). The two are
 * not aligned today; if an LLM ever consumes Android and host screenshots in the same
 * context and label-size mismatch becomes a quality issue, pull the dimension-picking math
 * into `trailblaze-common` and have both classes consume it.
 */
object AndroidCanvasSetOfMark {

  private const val BASE_BOX_OUTLINE_THICKNESS = 4.0f

  /**
   * Base label text size in **device** pixels. Multiplied by [drawScaleFor]'s return value
   * to get the per-bitmap size passed to `Paint.textSize`. Units are pixels, not points:
   * Android's `Paint.textSize` field documents pixels as its unit (see
   * [android.graphics.Paint.setTextSize]). When the bitmap reaching the draw path matches
   * the source device resolution (no upstream downscale), labels render at exactly 24px;
   * when the bitmap was downscaled by `scaleAndEncode` upstream, the same 24-device-pixel
   * label is rendered at the proportionally smaller bitmap-pixel size so the label retains
   * the same visual relationship to UI elements (which were downscaled by the same factor).
   *
   * Sourced from [SetOfMarkPalette] so the Host (AWT) implementation renders at the same
   * base size — see that file for the cross-platform contract.
   */
  private val BASE_TEXT_SIZE = SetOfMarkPalette.BASE_TEXT_SIZE
  private val BASE_PADDING = SetOfMarkPalette.BASE_PADDING.toFloat()

  /**
   * Compute the bitmap-to-device downscale ratio that label-sizing scales against.
   *
   * **The story.** Bitmaps reaching this code are pre-downscaled upstream by
   * `ScreenshotScalingConfig.DEFAULT` (maxDimension1=1536, maxDimension2=768) inside
   * [xyz.block.trailblaze.android.accessibility.AccessibilityServiceScreenState] /
   * [xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState],
   * so a 1920×1080 tablet screenshot becomes ~1366×768 by the time it lands here.
   * Everything in that downscaled bitmap — UI elements, icons, text — is at the same
   * `bitmap.width / device.width` proportion. Labels should follow.
   *
   * Pre-fix this code used `bitmap.width / 1080f` (a magic constant), which made sense
   * when the algorithm assumed a 1080-wide canonical phone screenshot, but broke on any
   * device whose bitmap dimensions deviated from that assumption — landscape tablets at
   * 1366×768 got 1.27× labels (text 24px → 30px), and a brief earlier pass at `min(w,h) /
   * 1080f` still relied on the 1080 magic. Both worked for the dominant phone-portrait
   * case but were derived from an arbitrary reference.
   *
   * The version here uses the information already available — both bitmap and device
   * dimensions, both passed into `drawSetOfMarkOnBitmap` / `drawAnnotationsOnBitmap` — to
   * compute the actual downscale ratio. No magic constants. The label renders at a
   * consistent **device-pixel size** regardless of which device's screenshot is being
   * annotated or how aggressively `scaleAndEncode` downscaled it.
   *
   * Concrete answers across the matrix (all are `min(bitmap_w / device_w, bitmap_h / device_h)`,
   * which equals the `scaleAmount` `ScreenshotScalingConfig.scale()` itself used upstream):
   *
   *   Phone   1080×1920 device → 1080×1920 bitmap (no downscale)        → scale=1.00 → text=24px
   *   Phone    720×1280 device →  720×1280 bitmap (no downscale)        → scale=1.00 → text=24px
   *   Tablet 1920×1080 device → 1366× 768 bitmap (scaleAndEncode 0.71×) → scale=0.71 → text=17px  ← was 1.27, the bug
   *   Tablet 2560×1600 device → 1228× 768 bitmap (scaleAndEncode 0.48×) → scale=0.48 → text=12px
   *   Phone  1920×1080 device → 1366× 768 bitmap (landscape phone)      → scale=0.71 → text=17px
   *
   * The 720×1280 phone case is the one behavior change with measurable spillover from
   * earlier rounds of this PR: my prior `min(w, h) / 1080` algorithm produced
   * scale=0.67 → text=16px there, while the bitmap=device math gives scale=1.00 → text=24px.
   * That's the right answer in principle — a 720-wide phone is a real device, not a
   * downscale of something bigger, so labels should render at the same 24-device-pixel size
   * users would see if they looked at the device. In practice this affects only low-density
   * Android devices we don't actually use on CI today.
   */
  private fun Bitmap.drawScale(deviceWidth: Int?, deviceHeight: Int?): Float =
    if (deviceWidth != null && deviceHeight != null) {
      drawScaleFor(
        bitmapWidth = width,
        bitmapHeight = height,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
      )
    } else {
      // Bitmap-as-device fallback for callers that don't supply device dimensions (the
      // androidTest smoke test in this file, primarily). drawScale=1.0 means labels render
      // at base size in raw bitmap pixels — the same answer the bitmap-as-device case would
      // give. Production callers always supply both dimensions via `AndroidBitmapUtils
      // .annotateScreenshotBytes`, so this fallback is for in-process testing only.
      1.0f
    }

  /**
   * Pure-math helper exposed for JVM unit tests. Returns the same scale factor the drawing
   * path multiplies [BASE_TEXT_SIZE] / [BASE_BOX_OUTLINE_THICKNESS] / [BASE_PADDING] by. The
   * production paths funnel through `Bitmap.drawScale()` which delegates here, so the JVM
   * tests pin the exact formula the prod path executes — no parallel inline calculation that
   * could drift.
   */
  internal fun drawScaleFor(
    bitmapWidth: Int,
    bitmapHeight: Int,
    deviceWidth: Int,
    deviceHeight: Int,
  ): Float = minOf(
    bitmapWidth.toFloat() / deviceWidth.toFloat(),
    bitmapHeight.toFloat() / deviceHeight.toFloat(),
  )

  private fun drawRectOutline(
    canvas: Canvas,
    r: ViewHierarchyFilter.Bounds,
    color: Int,
    strokeWidth: Float,
  ) {
    val paint = Paint().apply {
      this.color = color
      style = Paint.Style.STROKE
      this.strokeWidth = strokeWidth
    }
    canvas.drawRect(r.x1.toFloat(), r.y1.toFloat(), r.x2.toFloat(), r.y2.toFloat(), paint)
  }

  /**
   * Will draw on TOP of the bitmap instance provided
   */
  fun drawSetOfMarkOnBitmap(
    originalScreenshotBitmap: Bitmap,
    elements: List<ViewHierarchyTreeNode>,
    includeLabel: Boolean = true,
    deviceWidth: Int? = null,
    deviceHeight: Int? = null,
  ): Bitmap {
    val canvas = Canvas(originalScreenshotBitmap)

    // Production path routes through [drawScaleFor] (via the [drawScale] extension) so the
    // JVM unit tests are pinning the exact formula run here — not a parallel inline copy that
    // could drift from the tested helper.
    val drawScale = originalScreenshotBitmap.drawScale(deviceWidth, deviceHeight)
    val outlineThickness = BASE_BOX_OUTLINE_THICKNESS * drawScale
    val textSize = BASE_TEXT_SIZE * drawScale
    val padding = BASE_PADDING * drawScale

    // Calculate scale factors if device dimensions are provided and different from bitmap size
    val scaleX = if (deviceWidth != null && deviceWidth != originalScreenshotBitmap.width) {
      originalScreenshotBitmap.width.toFloat() / deviceWidth.toFloat()
    } else {
      1.0f
    }
    val scaleY = if (deviceHeight != null && deviceHeight != originalScreenshotBitmap.height) {
      originalScreenshotBitmap.height.toFloat() / deviceHeight.toFloat()
    } else {
      1.0f
    }

    elements.forEachIndexed { index, element ->
      val bounds = element.bounds
      if (bounds == null) return@forEachIndexed

      // Scale bounds to match bitmap coordinates
      val scaledBounds = ViewHierarchyFilter.Bounds(
        x1 = (bounds.x1 * scaleX).toInt(),
        y1 = (bounds.y1 * scaleY).toInt(),
        x2 = (bounds.x2 * scaleX).toInt(),
        y2 = (bounds.y2 * scaleY).toInt(),
      )

      val text = element.nodeId.toString()
      val color = argbForIndex(index)
      drawRectOutline(canvas, scaledBounds, color, outlineThickness)

      if (includeLabel) {
        val textPaint = Paint().apply {
          setColor(Color.WHITE)
          this.textSize = textSize
          style = Paint.Style.FILL
          setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
        }
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.fontMetricsInt.run { bottom - top }

        val textBackgroundPaint = Paint().apply {
          setColor(color)
          style = Paint.Style.FILL
        }
        val boxWidth = textWidth.toInt() + (padding * 2).toInt()
        val boxHeight = textHeight + (padding * 2).toInt()
        val labelBounds = SetOfMarkLayout.bottomRightLabelRect(scaledBounds, boxWidth, boxHeight)
        val textRect = Rect(labelBounds.x1, labelBounds.y1, labelBounds.x2, labelBounds.y2)
        canvas.drawRect(textRect, textBackgroundPaint)
        canvas.drawText(text, textRect.left + padding, textRect.bottom.toFloat() - padding, textPaint)
      }
    }
    return originalScreenshotBitmap
  }

  /**
   * Draws set-of-mark annotations from [AnnotationElement] list.
   * Uses [AnnotationElement.refLabel] as the label text when available, falling back to nodeId.
   */
  fun drawAnnotationsOnBitmap(
    originalScreenshotBitmap: Bitmap,
    annotations: List<AnnotationElement>,
    deviceWidth: Int? = null,
    deviceHeight: Int? = null,
  ): Bitmap {
    val canvas = Canvas(originalScreenshotBitmap)

    // Production path routes through [drawScaleFor] (via the [drawScale] extension) so the
    // JVM unit tests are pinning the exact formula run here — not a parallel inline copy.
    val drawScale = originalScreenshotBitmap.drawScale(deviceWidth, deviceHeight)
    val outlineThickness = BASE_BOX_OUTLINE_THICKNESS * drawScale
    val textSize = BASE_TEXT_SIZE * drawScale
    val padding = BASE_PADDING * drawScale

    val scaleX = if (deviceWidth != null && deviceWidth != originalScreenshotBitmap.width) {
      originalScreenshotBitmap.width.toFloat() / deviceWidth.toFloat()
    } else {
      1.0f
    }
    val scaleY = if (deviceHeight != null && deviceHeight != originalScreenshotBitmap.height) {
      originalScreenshotBitmap.height.toFloat() / deviceHeight.toFloat()
    } else {
      1.0f
    }

    annotations.forEachIndexed { index, element ->
      val scaledBounds = ViewHierarchyFilter.Bounds(
        x1 = (element.bounds.left * scaleX).toInt(),
        y1 = (element.bounds.top * scaleY).toInt(),
        x2 = (element.bounds.right * scaleX).toInt(),
        y2 = (element.bounds.bottom * scaleY).toInt(),
      )

      val text = element.refLabel ?: element.nodeId.toString()
      val color = argbForIndex(index)
      drawRectOutline(canvas, scaledBounds, color, outlineThickness)

      val textPaint = Paint().apply {
        setColor(Color.WHITE)
        this.textSize = textSize
        style = Paint.Style.FILL
        setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
      }
      val textWidth = textPaint.measureText(text)
      val textHeight = textPaint.fontMetricsInt.run { bottom - top }

      val textBackgroundPaint = Paint().apply {
        setColor(color)
        style = Paint.Style.FILL
      }
      val boxWidth = textWidth.toInt() + (padding * 2).toInt()
      val boxHeight = textHeight + (padding * 2).toInt()
      val labelBounds = SetOfMarkLayout.bottomRightLabelRect(scaledBounds, boxWidth, boxHeight)
      val textRect = Rect(labelBounds.x1, labelBounds.y1, labelBounds.x2, labelBounds.y2)
      canvas.drawRect(textRect, textBackgroundPaint)
      canvas.drawText(text, textRect.left + padding, textRect.bottom.toFloat() - padding, textPaint)
    }
    return originalScreenshotBitmap
  }

  /**
   * Looks up the palette color at [index] (modulo-wrapped) and ORs in the full-alpha byte
   * to produce the opaque ARGB int that Android's [Paint.setColor]`(Int)` expects. Host's
   * `java.awt.Color(int)` constructor consumes the shared RGB directly with no conversion,
   * so the alpha lift is Android-only.
   *
   * Pure int math — safe in JVM static init / unit tests, no `Color.parseColor` call. The
   * previous implementation (pre-#3033) invoked `Color.parseColor("#…")` 10× in static init,
   * which threw under the Android stub jar at JVM unit-test time and forced a `by lazy`
   * workaround in #3032; folding the palette into [SetOfMarkPalette] removed both the eager
   * `parseColor` calls and the need for the workaround.
   */
  private fun argbForIndex(index: Int): Int =
    0xFF000000.toInt() or SetOfMarkPalette.colorRgbAtIndex(index)
}

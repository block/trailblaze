package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * See https://github.com/takahirom/arbigent/blob/11b7887248ee131ab91a222f0f6d4ef80328853c/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt#L32
 */
object AndroidCanvasSetOfMark {

  private const val REFERENCE_WIDTH = 1080f
  private const val BASE_BOX_OUTLINE_THICKNESS = 4.0f
  private const val BASE_TEXT_SIZE = 24f
  private const val BASE_PADDING = 5f

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

    // Scale drawing parameters proportionally to bitmap size
    val drawScale = originalScreenshotBitmap.width.toFloat() / REFERENCE_WIDTH
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
      val color = BORDER_AND_BACKGROUND_COLORS[index % BORDER_AND_BACKGROUND_COLORS.size]
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
        val textRect = Rect(
          scaledBounds.x2 - textWidth.toInt() - (padding * 2).toInt(),
          scaledBounds.y2 - textHeight - (padding * 2).toInt(),
          scaledBounds.x2,
          scaledBounds.y2,
        )
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

    val drawScale = originalScreenshotBitmap.width.toFloat() / REFERENCE_WIDTH
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
      val color = BORDER_AND_BACKGROUND_COLORS[index % BORDER_AND_BACKGROUND_COLORS.size]
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
      val textRect = Rect(
        scaledBounds.x2 - textWidth.toInt() - (padding * 2).toInt(),
        scaledBounds.y2 - textHeight - (padding * 2).toInt(),
        scaledBounds.x2,
        scaledBounds.y2,
      )
      canvas.drawRect(textRect, textBackgroundPaint)
      canvas.drawText(text, textRect.left + padding, textRect.bottom.toFloat() - padding, textPaint)
    }
    return originalScreenshotBitmap
  }

  private val BORDER_AND_BACKGROUND_COLORS = listOf(
    Color.parseColor("#3F9101"), // Green
    Color.parseColor("#0E4A8E"), // Blue
    Color.parseColor("#BCBF01"), // Yellowish
    Color.parseColor("#BC0BA2"), // Pink
    Color.parseColor("#61AA0D"), // Light Green
    Color.parseColor("#3D017A"), // Purple
    Color.parseColor("#D6A60A"), // Orange-Yellow
    Color.parseColor("#7710A3"), // Deep Purple
    Color.parseColor("#A502CE"), // Magenta
    Color.parseColor("#EB5A00"), // Orange
  )
}

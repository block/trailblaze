package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * See https://github.com/takahirom/arbigent/blob/11b7887248ee131ab91a222f0f6d4ef80328853c/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt#L32
 */
object AndroidCanvasSetOfMark {

  private const val BOX_OUTLINE_THICKNESS = 4.0f

  private fun drawRectOutline(canvas: Canvas, r: ViewHierarchyFilter.Bounds, color: Int) {
    val paint = Paint().apply {
      this.color = color
      style = Paint.Style.STROKE
      strokeWidth = BOX_OUTLINE_THICKNESS
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
  ): Bitmap {
    val canvas = Canvas(originalScreenshotBitmap)
    elements.forEachIndexed { index, element ->
      val bounds = element.bounds
      if (bounds == null) return@forEachIndexed
      val text = element.nodeId.toString()
      val color = BORDER_AND_BACKGROUND_COLORS[index % BORDER_AND_BACKGROUND_COLORS.size]
      drawRectOutline(canvas, bounds, color)

      if (includeLabel) {
        val textPaint = Paint().apply {
          setColor(Color.WHITE)
          textSize = 24f
          style = Paint.Style.FILL
          setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
        }
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.fontMetricsInt.run { bottom - top }

        val textBackgroundPaint = Paint().apply {
          setColor(color)
          style = Paint.Style.FILL
        }
        val padding = 5f
        val textRect = Rect(
          bounds.x2 - textWidth.toInt() - (padding * 2).toInt(),
          bounds.y2 - textHeight - (padding * 2).toInt(),
          bounds.x2,
          bounds.y2,
        )
        canvas.drawRect(textRect, textBackgroundPaint)
        canvas.drawText(text, textRect.left + padding, textRect.bottom.toFloat() - padding, textPaint)
      }
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

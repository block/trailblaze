package xyz.block.trailblaze.host.setofmark

import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.round

/** https://github.com/takahirom/arbigent/blob/5871d8a92a499423b00c1c5c280a55be9e5561cd/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt */
class HostCanvasSetOfMark(
  private val bufferedImage: BufferedImage,
  private val deviceInfo: DeviceInfo? = null,
) {

  enum class CompositeMode {
    SrcOver,
    Src,
  }

  internal val colors = listOf(
    0x3F9101,
    0x0E4A8E,
    0xBCBF01,
    0xBC0BA2,
    0x61AA0D,
    0x3D017A,
    0xD6A60A,
    0x7710A3,
    0xA502CE,
    0xeb5a00,
  )

  companion object {
    const val NUMBER_FONT_SIZE = 24f
    const val BOX_OUTLINE_THICKNESS = 2.0f
  }

  /**
   * For iOS, scale coordinates from logical points to physical pixels.
   * iOS provides view hierarchy coordinates in logical points (e.g., 375x667)
   * but screenshots are in physical pixels (e.g., 750x1334).
   */
  private fun scaleCoordinateForPlatform(coordinate: Int): Int = if (deviceInfo?.platform == Platform.IOS) {
    (coordinate * getScaleFactorForPlatform()).toInt()
  } else {
    coordinate
  }

  /**
   * Get the integer scale factor for iOS coordinate scaling.
   * Validates that X and Y scale factors are consistent.
   */
  private fun getScaleFactorForPlatform(): Int = if (deviceInfo?.platform == Platform.IOS) {
    val scaleX = bufferedImage.width.toFloat() / deviceInfo?.widthGrid?.toFloat()!!
    val scaleY = bufferedImage.height.toFloat() / deviceInfo?.heightGrid?.toFloat()!!

    // Round to nearest integer
    val roundedScaleX = round(scaleX).toInt()
    val roundedScaleY = round(scaleY).toInt()

    // Validate that both scales are the same
    if (roundedScaleX != roundedScaleY) {
      println(
        "Warning: iOS scale factors differ - X: $scaleX ($roundedScaleX), Y: $scaleY ($roundedScaleY). Using X scale.",
      )
    }

    roundedScaleX
  } else {
    1
  }

  /**
   * Scale bounds coordinates for iOS coordinate system mismatch
   */
  private fun scaleBoundsForPlatform(bounds: ViewHierarchyFilter.Bounds): ViewHierarchyFilter.Bounds = if (deviceInfo?.platform == Platform.IOS) {
    ViewHierarchyFilter.Bounds(
      x1 = scaleCoordinateForPlatform(bounds.x1),
      y1 = scaleCoordinateForPlatform(bounds.y1),
      x2 = scaleCoordinateForPlatform(bounds.x2),
      y2 = scaleCoordinateForPlatform(bounds.y2),
    )
  } else {
    bounds
  }

  fun drawImage(image: BufferedImage, multiply: Double, compositeMode: CompositeMode = CompositeMode.SrcOver) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.setComposite(
        when (compositeMode) {
          CompositeMode.SrcOver -> AlphaComposite.SrcOver
          CompositeMode.Src -> AlphaComposite.Src
        },
      )
      graphics2D.drawImage(
        image,
        0,
        0,
        (image.width * multiply).toInt(),
        (image.height * multiply).toInt(),
        null,
      )
    }
  }

  fun draw(elements: List<ViewHierarchyTreeNode>) {
    if (deviceInfo?.platform == Platform.IOS) {
      val scaleX = bufferedImage.width.toFloat() / deviceInfo?.widthGrid?.toFloat()!!
      val scaleY = bufferedImage.height.toFloat() / deviceInfo?.heightGrid?.toFloat()!!
    }

    bufferedImage.graphics { graphics2D ->
      elements.forEachIndexed { index, element ->
        val bounds = element.bounds ?: return@forEachIndexed

        // Scale bounds for iOS coordinate system
        val scaledBounds = scaleBoundsForPlatform(bounds)

        val text = element.nodeId.toString()
        val color = Color(colors[index % colors.size])
        drawRectOutline(scaledBounds, color)

        val (rawBoxWidth, rawBoxHeight) = textCalc(listOf(text))
        val textPadding = 5
        val boxWidth = rawBoxWidth + textPadding * 2
        val boxHeight = rawBoxHeight + textPadding * 2
        val bottomRightTextRect = ViewHierarchyFilter.Bounds(
          scaledBounds.x2 - boxWidth,
          scaledBounds.y2 - boxHeight,
          scaledBounds.x2,
          scaledBounds.y2,
        )
        drawRect(bottomRightTextRect, color)
        drawText(
          (bottomRightTextRect.x1 + textPadding).toFloat(),
          (bottomRightTextRect.y1 + textPadding + rawBoxHeight).toFloat(),
          listOf(text),
          Color.WHITE,
        )
      }
    }
  }

  fun drawNodes(nodes: List<ViewHierarchyTreeNode>) {
    bufferedImage.graphics { graphics2D ->
      nodes
        .filter { it.bounds != null }
        .forEach { viewHierarchyNode ->
          val bounds = viewHierarchyNode.bounds!!
          // Scale bounds for iOS coordinate system
          val scaledBounds = scaleBoundsForPlatform(bounds)
          val text = viewHierarchyNode.nodeId.toString()
          val color = Color(colors[viewHierarchyNode.nodeId.toInt() % colors.size])

          drawRectOutline(
            topLeftX = scaledBounds.x1,
            topLeftY = scaledBounds.y1,
            width = scaledBounds.width,
            height = scaledBounds.height,
            color = color,
          )

          val (textWidth, textHeight) = textCalc(listOf(text))
          val textPadding = 5
          val textBoxWidth = textWidth + (textPadding * 2)
          val textBoxHeight = textHeight + (textPadding * 2)

          drawRect(
            topLeftX = scaledBounds.x1 + scaledBounds.width - textBoxWidth,
            topLeftY = scaledBounds.y1 + scaledBounds.height - textBoxHeight,
            width = textBoxWidth,
            height = textBoxHeight,
            color = color,
          )

          drawText(
            (scaledBounds.x1 + scaledBounds.width - textBoxWidth + textPadding).toFloat(),
            (scaledBounds.y1 + scaledBounds.height - textPadding).toFloat(),
            listOf(text),
            Color.WHITE,
          )
        }
    }
  }

  private fun textCalc(texts: List<String>): Pair<Int, Int> = bufferedImage.graphics { graphics2D ->
    // Set the font size for layout calculation
    val currentFont = graphics2D.font
    graphics2D.font = currentFont.deriveFont(NUMBER_FONT_SIZE)
    val frc: FontRenderContext = graphics2D.getFontRenderContext()
    val longestLineWidth = texts.map {
      calcTextLayout(
        it,
        graphics2D,
        frc,
      ).getPixelBounds(frc, 0F, 0F).width
    }.maxBy {
      it
    }
    longestLineWidth to (
      texts.sumOf {
        calcTextLayout(it, graphics2D, frc).bounds.height + 1
      }
      ).toInt()
  }

  private fun drawRect(bounds: ViewHierarchyFilter.Bounds, color: Color) {
    drawRect(
      topLeftX = bounds.x1,
      topLeftY = bounds.y1,
      width = bounds.width,
      height = bounds.height,
      color = color,
    )
  }

  private fun drawRect(topLeftX: Int, topLeftY: Int, width: Int, height: Int, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      graphics2D.fillRect(
        topLeftX,
        topLeftY,
        width,
        height,
      )
    }
  }

  private fun drawText(textPointX: Float, textPointY: Float, texts: List<String>, color: Color) {
    bufferedImage.graphics {
      val graphics2D = bufferedImage.createGraphics()
      graphics2D.color = color
      // Set the font size for numbers
      val currentFont = graphics2D.font
      graphics2D.font = currentFont.deriveFont(NUMBER_FONT_SIZE)

      val frc: FontRenderContext = graphics2D.getFontRenderContext()

      var nextY = textPointY
      for (text in texts) {
        val layout = calcTextLayout(text, graphics2D, frc)
        val height = layout.bounds.height
        layout.draw(
          graphics2D,
          textPointX,
          nextY,
        )
        nextY += (height + 1).toInt()
      }
    }
  }

  private fun drawRectOutline(r: ViewHierarchyFilter.Bounds, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      val stroke = BasicStroke(BOX_OUTLINE_THICKNESS)
      graphics2D.setStroke(stroke)
      graphics2D.drawRect(
        r.x1,
        r.y1,
        (r.x2 - r.x1),
        (r.y2 - r.y1),
      )
    }
  }

  private fun drawRectOutline(topLeftX: Int, topLeftY: Int, width: Int, height: Int, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      val stroke = BasicStroke(BOX_OUTLINE_THICKNESS)
      graphics2D.stroke = stroke
      graphics2D.drawRect(
        topLeftX,
        topLeftY,
        width,
        height,
      )
    }
  }

  private val textCache = hashMapOf<String, TextLayout>()

  private fun calcTextLayout(
    text: String,
    graphics2D: Graphics2D,
    frc: FontRenderContext,
  ) = textCache.getOrPut(text) {
    TextLayout(text, graphics2D.font, frc)
  }

  private fun BufferedImage.toByteArrayWithQuality(format: String = "JPEG", quality: Float = 0.85f): ByteArray = ByteArrayOutputStream().use { baos ->
    val writer = ImageIO.getImageWritersByFormatName(format).next()
    val writeParam = writer.defaultWriteParam

    if (format.equals("jpeg", ignoreCase = true)) {
      writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
      writeParam.compressionQuality = quality // 1.0f is maximum quality, 0.0f is minimum
    }

    writer.output = ImageIO.createImageOutputStream(baos)
    writer.write(null, IIOImage(this, null, null), writeParam)
    writer.dispose()

    baos.toByteArray()
  }

  fun toByteArray(): ByteArray = bufferedImage.toByteArrayWithQuality()

  private fun <T> BufferedImage.graphics(block: (Graphics2D) -> T): T {
    val graphics = createGraphics()
    val result = block(graphics)
    graphics.dispose()
    return result
  }
}

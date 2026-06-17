package xyz.block.trailblaze.setofmark

import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.TrailblazeNode
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
import xyz.block.trailblaze.util.Console

/**
 * https://github.com/takahirom/arbigent/blob/5871d8a92a499423b00c1c5c280a55be9e5561cd/arbigent-core/src/main/java/io/github/takahirom/arbigent/ArbigentCanvas.kt
 *
 * Cross-platform atoms (base text size, base padding, color palette) live in
 * [SetOfMarkPalette] so this AWT-based implementation stays in sync with
 * `AndroidCanvasSetOfMark` on those shared values. The per-platform scale math and
 * outline thickness stay local — see [SetOfMarkPalette]'s kdoc for the rationale on
 * what's shared vs. local.
 */
class HostCanvasSetOfMark(
  private val bufferedImage: BufferedImage,
  private val deviceInfo: DeviceInfo? = null,
) {

  enum class CompositeMode {
    SrcOver,
    Src,
  }

  companion object {
    /**
     * Base label font size. Routed through [SetOfMarkPalette] so Android and Host stay
     * in lockstep on what "default text" is sized to — Android's `Paint.textSize`
     * (pixels) and AWT's `Font.deriveFont(Float)` (points-but-typically-pixels at 72 DPI)
     * accept the same numeric value.
     */
    const val NUMBER_FONT_SIZE: Float = SetOfMarkPalette.BASE_TEXT_SIZE
    const val BOX_OUTLINE_THICKNESS = 2.0f

    /** Compact font for high-resolution screenshots (desktop / landscape tablet). */
    const val COMPACT_NUMBER_FONT_SIZE = 14f

    /** Thinner stroke pairs with [COMPACT_NUMBER_FONT_SIZE] — full-width borders eat content. */
    const val COMPACT_BOX_OUTLINE_THICKNESS = 1.25f

    /**
     * Image area (width × height) at or above which we switch to compact-mode sizing.
     * `1280 × 800 = 1.024M` so any desktop-class viewport trips it; mobile portrait
     * (`390 × 844 ≈ 329k`) stays on the original 24pt font.
     */
    private const val COMPACT_MODE_IMAGE_AREA = 1_000_000L

    /**
     * Cap on how many annotation boxes we paint in compact mode. The interactive-only
     * filter on the Playwright side usually keeps the count well below this; this is
     * a safety net for pathological dense pages (huge admin grids, monitoring dashboards).
     * Items past the cap still appear in the text representation — they just don't get
     * a painted box, which is preferable to a wall of overlapping rectangles.
     */
    private const val MAX_ANNOTATIONS_COMPACT_MODE = 60
  }

  /**
   * For iOS and Web, scale coordinates from logical points to physical pixels.
   * iOS provides view hierarchy coordinates in logical points (e.g., 375x667)
   * but screenshots are in physical pixels (e.g., 750x1334).
   * Web with Playwright uses deviceScaleFactor (e.g., 2.0 for Retina) where
   * getBoundingClientRect() returns logical coordinates but screenshots are physical pixels.
   */
  private fun scaleCoordinateForPlatform(coordinate: Int): Int =
    if (deviceInfo?.platform == Platform.IOS || deviceInfo?.platform == Platform.WEB) {
      (coordinate * getScaleFactorForPlatform()).toInt()
    } else {
      coordinate
    }

  /**
   * Get the integer scale factor for iOS and Web coordinate scaling.
   * Validates that X and Y scale factors are consistent.
   */
  private fun getScaleFactorForPlatform(): Int =
    if (deviceInfo?.platform == Platform.IOS || deviceInfo?.platform == Platform.WEB) {
      val scaleX = bufferedImage.width.toFloat() / deviceInfo.widthGrid.toFloat()!!
      val scaleY = bufferedImage.height.toFloat() / deviceInfo.heightGrid.toFloat()!!

      // Round to nearest integer
      val roundedScaleX = round(scaleX).toInt()
      val roundedScaleY = round(scaleY).toInt()

      // Validate that both scales are the same
      if (roundedScaleX != roundedScaleY) {
        val platformName = if (deviceInfo.platform == Platform.IOS) "iOS" else "Web"
        Console.log(
          "Warning: $platformName scale factors differ - X: $scaleX ($roundedScaleX), Y: $scaleY ($roundedScaleY). Using X scale.",
        )
      }

      roundedScaleX
    } else {
      1
    }

  /**
   * Scale bounds coordinates for iOS and Web coordinate system mismatch
   */
  private fun scaleBoundsForPlatform(bounds: ViewHierarchyFilter.Bounds): ViewHierarchyFilter.Bounds =
    if (deviceInfo?.platform == Platform.IOS || deviceInfo?.platform == Platform.WEB) {
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
        val color = Color(SetOfMarkPalette.colorRgbAtIndex(index))
        drawRectOutline(scaledBounds, color)

        val (rawBoxWidth, rawBoxHeight) = textCalc(listOf(text))
        val textPadding = SetOfMarkPalette.BASE_PADDING
        val boxWidth = rawBoxWidth + textPadding * 2
        val boxHeight = rawBoxHeight + textPadding * 2
        val bottomRightTextRect = SetOfMarkLayout.bottomRightLabelRect(scaledBounds, boxWidth, boxHeight)
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

  /**
   * Draws set-of-mark annotations from [AnnotationElement] list.
   *
   * Uses the same nodeId + bounds that the compact element list emits in the text
   * representation, ensuring the numbers drawn on the screenshot exactly match
   * the `[nID]` refs in the LLM prompt text.
   *
   * On high-resolution images (≥ [COMPACT_MODE_IMAGE_AREA]) switches to compact-mode
   * sizing and applies three rendering tweaks aimed at dense landscape/desktop views:
   * 1. **Smaller font + thinner stroke** so labels don't dominate small UI atoms.
   * 2. **Density cap** ([MAX_ANNOTATIONS_COMPACT_MODE]) so a 200-row admin table
   *    doesn't paint 200 boxes.
   * 3. **Collision-avoiding label placement** — when a label badge would overlap a
   *    previously-painted badge, tries top-right / bottom-left / top-left of the
   *    element's bounding box before falling back to the default bottom-right.
   *
   * Mobile portrait screenshots (~390 × 844 ≈ 329k px) stay on the original sizing
   * and unbounded element count — they don't have the density problem.
   */
  fun drawAnnotations(elements: List<AnnotationElement>) {
    val compactMode = isCompactMode()
    val fontSize = if (compactMode) COMPACT_NUMBER_FONT_SIZE else NUMBER_FONT_SIZE
    val outlineThickness = if (compactMode) COMPACT_BOX_OUTLINE_THICKNESS else BOX_OUTLINE_THICKNESS
    val drawable = if (compactMode && elements.size > MAX_ANNOTATIONS_COMPACT_MODE) {
      elements.take(MAX_ANNOTATIONS_COMPACT_MODE)
    } else {
      elements
    }
    val paintedLabelRects = mutableListOf<ViewHierarchyFilter.Bounds>()

    bufferedImage.graphics { graphics2D ->
      drawable.forEachIndexed { index, element ->
        val bounds = ViewHierarchyFilter.Bounds(
          x1 = element.bounds.left,
          y1 = element.bounds.top,
          x2 = element.bounds.right,
          y2 = element.bounds.bottom,
        )
        val scaledBounds = scaleBoundsForPlatform(bounds)

        val text = element.refLabel ?: element.nodeId.toString()
        val color = Color(SetOfMarkPalette.colorRgbAtIndex(index))
        drawRectOutline(scaledBounds, color, outlineThickness)

        val (rawBoxWidth, rawBoxHeight) = textCalc(listOf(text), fontSize)
        val textPadding = SetOfMarkPalette.BASE_PADDING
        val boxWidth = rawBoxWidth + textPadding * 2
        val boxHeight = rawBoxHeight + textPadding * 2
        val labelRect = if (compactMode) {
          pickNonCollidingLabelRect(scaledBounds, boxWidth, boxHeight, paintedLabelRects)
        } else {
          // Mobile/non-compact: keep the historical bottom-right placement (a 4 × 1
          // change shouldn't bother existing snapshot tests / golden screenshots).
          SetOfMarkLayout.bottomRightLabelRect(scaledBounds, boxWidth, boxHeight)
        }
        paintedLabelRects.add(labelRect)
        drawRect(labelRect, color)
        drawText(
          (labelRect.x1 + textPadding).toFloat(),
          (labelRect.y1 + textPadding + rawBoxHeight).toFloat(),
          listOf(text),
          Color.WHITE,
          fontSize,
        )
      }
    }
  }

  /**
   * Whether the current target should render in compact mode.
   *
   * Uses the **logical** viewport area (`deviceInfo.widthGrid × heightGrid`) when
   * available, falling back to the raw bitmap area only when no DeviceInfo is supplied.
   * This matters on iOS / Web with deviceScaleFactor > 1: a 390×844 mobile-portrait
   * viewport produces a 780×1688 bitmap (≈1.32M px) at DPR=2, which would falsely
   * trigger compact mode if we used pixel area — and would silently apply the
   * 60-element cap + smaller labels on phones, exactly the regression we promised
   * not to ship. The logical-coords path keeps mobile portrait at 329k regardless
   * of DPR.
   */
  private fun isCompactMode(): Boolean {
    val area = if (deviceInfo != null && deviceInfo.widthGrid > 0 && deviceInfo.heightGrid > 0) {
      deviceInfo.widthGrid.toLong() * deviceInfo.heightGrid
    } else {
      bufferedImage.width.toLong() * bufferedImage.height
    }
    return area >= COMPACT_MODE_IMAGE_AREA
  }

  /**
   * Pick a label badge rectangle that doesn't overlap any previously-painted label rect.
   *
   * Tries four corner positions on the element's bounding box in priority order
   * (bottom-right → top-right → bottom-left → top-left). Falls back to bottom-right
   * if all four collide — at that point the page is dense enough that some overlap
   * is unavoidable, and a consistent default is more readable than something random.
   *
   * The badge rect is clamped to the image canvas so labels at the page edge don't
   * draw partially off-screen.
   */
  private fun pickNonCollidingLabelRect(
    elementBounds: ViewHierarchyFilter.Bounds,
    boxWidth: Int,
    boxHeight: Int,
    paintedLabelRects: List<ViewHierarchyFilter.Bounds>,
  ): ViewHierarchyFilter.Bounds {
    val candidates = listOf(
      // Bottom-right (default — preserves the original visual mapping)
      SetOfMarkLayout.bottomRightLabelRect(elementBounds, boxWidth, boxHeight),
      // Top-right
      ViewHierarchyFilter.Bounds(
        elementBounds.x2 - boxWidth, elementBounds.y1,
        elementBounds.x2, elementBounds.y1 + boxHeight,
      ),
      // Bottom-left
      ViewHierarchyFilter.Bounds(
        elementBounds.x1, elementBounds.y2 - boxHeight,
        elementBounds.x1 + boxWidth, elementBounds.y2,
      ),
      // Top-left
      ViewHierarchyFilter.Bounds(
        elementBounds.x1, elementBounds.y1,
        elementBounds.x1 + boxWidth, elementBounds.y1 + boxHeight,
      ),
    )
    val firstFree = candidates.firstOrNull { candidate ->
      paintedLabelRects.none { it.overlaps(candidate) }
    }
    return clampToCanvas(firstFree ?: candidates[0])
  }

  /** Keep a label rect inside the canvas so it doesn't get clipped at the page edge. */
  private fun clampToCanvas(r: ViewHierarchyFilter.Bounds): ViewHierarchyFilter.Bounds {
    val w = r.x2 - r.x1
    val h = r.y2 - r.y1
    val maxX = bufferedImage.width
    val maxY = bufferedImage.height
    var x1 = r.x1.coerceIn(0, maxOf(0, maxX - w))
    var y1 = r.y1.coerceIn(0, maxOf(0, maxY - h))
    return ViewHierarchyFilter.Bounds(x1, y1, x1 + w, y1 + h)
  }

  private fun ViewHierarchyFilter.Bounds.overlaps(other: ViewHierarchyFilter.Bounds): Boolean =
    x1 < other.x2 && other.x1 < x2 && y1 < other.y2 && other.y1 < y2

  fun drawNodes(nodes: List<ViewHierarchyTreeNode>) {
    bufferedImage.graphics { graphics2D ->
      nodes
        .filter { it.bounds != null }
        .forEach { viewHierarchyNode ->
          val bounds = viewHierarchyNode.bounds!!
          // Scale bounds for iOS coordinate system
          val scaledBounds = scaleBoundsForPlatform(bounds)
          val text = viewHierarchyNode.nodeId.toString()
          val color = Color(SetOfMarkPalette.colorRgbAtIndex(viewHierarchyNode.nodeId.toInt()))

          drawRectOutline(
            topLeftX = scaledBounds.x1,
            topLeftY = scaledBounds.y1,
            width = scaledBounds.width,
            height = scaledBounds.height,
            color = color,
          )

          val (textWidth, textHeight) = textCalc(listOf(text))
          val textPadding = SetOfMarkPalette.BASE_PADDING
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

  private fun textCalc(
    texts: List<String>,
    fontSize: Float = NUMBER_FONT_SIZE,
  ): Pair<Int, Int> = bufferedImage.graphics { graphics2D ->
    // Set the font size for layout calculation
    val currentFont = graphics2D.font
    graphics2D.font = currentFont.deriveFont(fontSize)
    val frc: FontRenderContext = graphics2D.getFontRenderContext()
    val longestLineWidth = texts.map {
      calcTextLayout(
        it,
        graphics2D,
        frc,
        fontSize,
      ).getPixelBounds(frc, 0F, 0F).width
    }.maxBy {
      it
    }
    longestLineWidth to (
      texts.sumOf {
        calcTextLayout(it, graphics2D, frc, fontSize).bounds.height + 1
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

  private fun drawText(
    textPointX: Float,
    textPointY: Float,
    texts: List<String>,
    color: Color,
    fontSize: Float = NUMBER_FONT_SIZE,
  ) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      // Set the font size for numbers
      val currentFont = graphics2D.font
      graphics2D.font = currentFont.deriveFont(fontSize)

      val frc: FontRenderContext = graphics2D.getFontRenderContext()

      var nextY = textPointY
      for (text in texts) {
        val layout = calcTextLayout(text, graphics2D, frc, fontSize)
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

  private fun drawRectOutline(
    r: ViewHierarchyFilter.Bounds,
    color: Color,
    thickness: Float = BOX_OUTLINE_THICKNESS,
  ) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      val stroke = BasicStroke(thickness)
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

  private val textCache = hashMapOf<Pair<String, Float>, TextLayout>()

  private fun calcTextLayout(
    text: String,
    graphics2D: Graphics2D,
    frc: FontRenderContext,
    fontSize: Float = NUMBER_FONT_SIZE,
  ) = textCache.getOrPut(text to fontSize) {
    TextLayout(text, graphics2D.font.deriveFont(fontSize), frc)
  }

  private fun BufferedImage.toByteArrayWithQuality(format: String = "JPEG", quality: Float = 0.85f): ByteArray = ByteArrayOutputStream().use { baos ->
    // Convert to JPEG-compatible colorspace to avoid "Bogus input colorspace" errors
    val imageToWrite = if (format.equals("jpeg", ignoreCase = true) && type != BufferedImage.TYPE_3BYTE_BGR) {
      convertToJpegCompatible(this)
    } else {
      this
    }

    val writer = ImageIO.getImageWritersByFormatName(format).next()
    val writeParam = writer.defaultWriteParam

    if (format.equals("jpeg", ignoreCase = true)) {
      writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
      writeParam.compressionQuality = quality // 1.0f is maximum quality, 0.0f is minimum
    }

    writer.output = ImageIO.createImageOutputStream(baos)
    writer.write(null, IIOImage(imageToWrite, null, null), writeParam)
    writer.dispose()

    baos.toByteArray()
  }

  /**
   * Converts an image to JPEG-compatible format (TYPE_3BYTE_BGR).
   * This fixes "Bogus input colorspace" errors when encoding images with
   * non-RGB colorspaces (e.g., PNG with alpha or CMYK images) to JPEG.
   */
  private fun convertToJpegCompatible(image: BufferedImage): BufferedImage {
    val converted = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
    val g = converted.createGraphics()
    g.drawImage(image, 0, 0, null)
    g.dispose()
    return converted
  }

  fun toByteArray(): ByteArray = bufferedImage.toByteArrayWithQuality()

  private fun <T> BufferedImage.graphics(block: (Graphics2D) -> T): T {
    val graphics = createGraphics()
    val result = block(graphics)
    graphics.dispose()
    return result
  }
}

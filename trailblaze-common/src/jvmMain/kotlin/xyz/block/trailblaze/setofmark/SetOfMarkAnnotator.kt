package xyz.block.trailblaze.setofmark

import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Shared utility for applying set-of-mark annotations to screenshot bytes.
 *
 * Used by all JVM-side [xyz.block.trailblaze.api.ScreenState] implementations
 * (Host/Maestro, Playwright, Compose) to annotate screenshots for LLM requests.
 */
object SetOfMarkAnnotator {

  /**
   * Annotates screenshot bytes with set-of-mark overlays on interactable elements.
   *
   * Returns the original [screenshotBytes] unchanged if decoding/annotation fails.
   *
   * @param screenshotBytes Raw screenshot bytes (PNG/JPEG/WEBP) to annotate.
   * @param viewHierarchy The view hierarchy to extract interactable elements from.
   * @param screenWidth Logical screen width (for filtering out-of-bounds elements).
   * @param screenHeight Logical screen height.
   * @param platform The device platform (affects coordinate scaling and element filtering).
   * @param deviceInfo Optional Maestro DeviceInfo for iOS/Web coordinate scaling.
   *   When null, a synthetic DeviceInfo is created from the other parameters.
   */
  fun annotate(
    screenshotBytes: ByteArray?,
    viewHierarchy: ViewHierarchyTreeNode,
    screenWidth: Int,
    screenHeight: Int,
    platform: TrailblazeDevicePlatform,
    deviceInfo: DeviceInfo? = null,
  ): ByteArray? {
    screenshotBytes ?: return null
    if (screenshotBytes.isEmpty()) return screenshotBytes

    return try {
      val original = ByteArrayInputStream(screenshotBytes).use { ImageIO.read(it) }
        ?: return screenshotBytes

      val effectiveDeviceInfo = deviceInfo ?: DeviceInfo(
        platform = platform.toMaestroPlatform(),
        widthPixels = original.width,
        heightPixels = original.height,
        widthGrid = screenWidth,
        heightGrid = screenHeight,
      )

      // Create a copy for annotation
      val imageForAnnotation = BufferedImage(
        original.width,
        original.height,
        original.type,
      )
      val graphics = imageForAnnotation.createGraphics()
      graphics.drawImage(original, 0, 0, null)
      graphics.dispose()

      // Filter to interactable elements and draw
      val filtered = ViewHierarchyFilter.create(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        platform = platform,
      ).filterInteractableViewHierarchyTreeNodes(viewHierarchy)
      val elementList = ViewHierarchyTreeNodeUtils.from(filtered, effectiveDeviceInfo)

      val canvas = HostCanvasSetOfMark(imageForAnnotation, effectiveDeviceInfo)
      canvas.draw(elementList)

      imageForAnnotation.toPngBytes()
    } catch (_: Exception) {
      screenshotBytes
    }
  }

  private fun BufferedImage.toPngBytes(): ByteArray {
    val baos = java.io.ByteArrayOutputStream()
    ImageIO.write(this, "PNG", baos)
    return baos.toByteArray()
  }

  private fun TrailblazeDevicePlatform.toMaestroPlatform(): Platform = when (this) {
    TrailblazeDevicePlatform.ANDROID -> Platform.ANDROID
    TrailblazeDevicePlatform.IOS -> Platform.IOS
    TrailblazeDevicePlatform.WEB -> Platform.WEB
  }
}

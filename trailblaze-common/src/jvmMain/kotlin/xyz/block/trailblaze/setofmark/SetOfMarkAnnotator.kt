package xyz.block.trailblaze.setofmark

import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
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
   * @param screenWidth Logical screen width.
   * @param screenHeight Logical screen height.
   * @param platform The device platform (affects coordinate scaling and element filtering).
   * @param deviceInfo Optional Maestro DeviceInfo for iOS/Web coordinate scaling.
   *   When null, a synthetic DeviceInfo is created from the other parameters.
   * @param annotationElements Required to actually draw anything. Labels use each
   *   element's [AnnotationElement.refLabel] (e.g. `[e1]`) so they line up with the
   *   `[refLabel]` IDs the LLM sees in
   *   [xyz.block.trailblaze.api.ScreenState.viewHierarchyTextRepresentation]. When
   *   null/empty, the screenshot is returned without annotations — callers that
   *   used to rely on a `viewHierarchy` -> nodeId fallback must now produce
   *   `[refLabel]`-based annotation elements themselves.
   */
  fun annotate(
    screenshotBytes: ByteArray?,
    screenWidth: Int,
    screenHeight: Int,
    platform: TrailblazeDevicePlatform,
    deviceInfo: DeviceInfo? = null,
    annotationElements: List<AnnotationElement>? = null,
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

      // Only the `[refLabel]` path is supported now — drawing raw nodeIds lined up
      // labels that didn't match the IDs the LLM sees in the text representation,
      // and the LLM contract is fully ref-based. Callers that don't supply
      // annotationElements get back the unannotated screenshot.
      if (!annotationElements.isNullOrEmpty()) {
        HostCanvasSetOfMark(imageForAnnotation, effectiveDeviceInfo)
          .drawAnnotations(annotationElements)
      }

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
    // Set-of-mark annotation runs against Maestro screenshots; the Compose desktop
    // driver doesn't go through Maestro so this conversion shouldn't fire. Fail loud
    // rather than route a desktop screenshot through the Maestro annotator path.
    TrailblazeDevicePlatform.DESKTOP ->
      error("SetOfMarkAnnotator does not support TrailblazeDevicePlatform.DESKTOP — Compose driver bypasses Maestro screenshots.")
  }
}

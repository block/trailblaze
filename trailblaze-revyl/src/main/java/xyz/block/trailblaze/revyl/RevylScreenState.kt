package xyz.block.trailblaze.revyl

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [ScreenState] backed by Revyl CLI screenshots.
 *
 * Since Revyl uses AI-powered visual grounding (not accessibility trees),
 * the view hierarchy is a minimal root node. The LLM agent relies on
 * screenshot-based reasoning instead of element trees.
 *
 * Screen dimensions are resolved in priority order:
 * 1. Session-reported dimensions from the worker health endpoint.
 * 2. PNG IHDR header extraction from the captured screenshot.
 * 3. Default fallback constants from [RevylDefaults].
 *
 * @param cliClient CLI client used to capture screenshots.
 * @param platform The device platform ("ios" or "android").
 * @param sessionScreenWidth Session-reported width in pixels (0 = unknown).
 * @param sessionScreenHeight Session-reported height in pixels (0 = unknown).
 */
class RevylScreenState(
  private val cliClient: RevylCliClient,
  private val platform: String,
  sessionScreenWidth: Int = 0,
  sessionScreenHeight: Int = 0,
) : ScreenState {

  private val capturedScreenshot: ByteArray? = try {
    cliClient.screenshot()
  } catch (_: Exception) {
    null
  }

  private val defaultDimensions = RevylDefaults.dimensionsForPlatform(platform)

  private val dimensions: Pair<Int, Int> = when {
    sessionScreenWidth > 0 && sessionScreenHeight > 0 ->
      Pair(sessionScreenWidth, sessionScreenHeight)
    else ->
      capturedScreenshot?.let { extractPngDimensions(it) }
        ?: defaultDimensions
  }

  override val screenshotBytes: ByteArray? = capturedScreenshot

  override val deviceWidth: Int = dimensions.first

  override val deviceHeight: Int = dimensions.second

  private val rootViewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
    nodeId = 1,
    text = "RevylRootNode",
    className = "RevylCloudDevice",
    dimensions = "${deviceWidth}x$deviceHeight",
    centerPoint = "${deviceWidth / 2},${deviceHeight / 2}",
    clickable = true,
    enabled = true,
  )

  override val viewHierarchy: ViewHierarchyTreeNode = rootViewHierarchy

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = when (platform.lowercase()) {
    RevylCliClient.PLATFORM_IOS -> TrailblazeDevicePlatform.IOS
    else -> TrailblazeDevicePlatform.ANDROID
  }

  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = listOf(
    trailblazeDevicePlatform.asTrailblazeDeviceClassifier(),
    TrailblazeDeviceClassifier("revyl-cloud"),
  )

  companion object {
    /**
     * Extracts width and height from a PNG file's IHDR chunk header.
     *
     * @param data Raw PNG bytes.
     * @return (width, height) pair, or null if the data is not valid PNG.
     */
    private fun extractPngDimensions(data: ByteArray): Pair<Int, Int>? {
      if (data.size < 24) return null
      val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
      )
      for (i in pngSignature.indices) {
        if (data[i] != pngSignature[i]) return null
      }
      val buffer = ByteBuffer.wrap(data, 16, 8).order(ByteOrder.BIG_ENDIAN)
      val width = buffer.int
      val height = buffer.int
      if (width <= 0 || height <= 0) return null
      return Pair(width, height)
    }
  }
}

package xyz.block.trailblaze.host.revyl

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
 * @property cliClient CLI client used to capture screenshots.
 * @property platform The device platform ("ios" or "android").
 */
class RevylScreenState(
  private val cliClient: RevylCliClient,
  private val platform: String,
) : ScreenState {

  private val capturedScreenshot: ByteArray? = try {
    cliClient.screenshot()
  } catch (_: Exception) {
    null
  }

  private val dimensions: Pair<Int, Int> = capturedScreenshot?.let { extractPngDimensions(it) }
    ?: Pair(DEFAULT_WIDTH, DEFAULT_HEIGHT)

  override val screenshotBytes: ByteArray? = capturedScreenshot

  override val deviceWidth: Int = dimensions.first

  override val deviceHeight: Int = dimensions.second

  override val viewHierarchyOriginal: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
    nodeId = 1,
    text = "RevylRootNode",
    className = "RevylCloudDevice",
    dimensions = "${deviceWidth}x$deviceHeight",
    centerPoint = "${deviceWidth / 2},${deviceHeight / 2}",
    clickable = true,
    enabled = true,
  )

  override val viewHierarchy: ViewHierarchyTreeNode = viewHierarchyOriginal

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = when (platform.lowercase()) {
    "ios" -> TrailblazeDevicePlatform.IOS
    else -> TrailblazeDevicePlatform.ANDROID
  }

  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = listOf(
    trailblazeDevicePlatform.asTrailblazeDeviceClassifier(),
    TrailblazeDeviceClassifier("revyl-cloud"),
  )

  companion object {
    private const val DEFAULT_WIDTH = 1080
    private const val DEFAULT_HEIGHT = 2340

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

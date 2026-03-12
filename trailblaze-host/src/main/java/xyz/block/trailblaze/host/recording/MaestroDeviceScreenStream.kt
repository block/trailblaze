package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode

/**
 * [DeviceScreenStream] backed by a Maestro [Driver] (Android or iOS).
 * Screenshots are captured via [Driver.takeScreenshot] at a configurable interval.
 * Input forwarding uses the Maestro driver API.
 */
class MaestroDeviceScreenStream(
  private val driver: Driver,
  private val frameIntervalMs: Long = 200,
) : DeviceScreenStream {

  private val deviceInfo = driver.deviceInfo()

  override val deviceWidth: Int get() = deviceInfo.widthGrid
  override val deviceHeight: Int get() = deviceInfo.heightGrid

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      try {
        val bytes = captureScreenshotBytes()
        if (bytes != null) emit(bytes)
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Device may be transitioning; skip this frame
      }
      delay(frameIntervalMs)
    }
  }

  override suspend fun tap(x: Int, y: Int) {
    driver.tap(Point(x, y))
  }

  override suspend fun longPress(x: Int, y: Int) {
    driver.longPress(Point(x, y))
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int) {
    // Use a short duration for fling-like behavior; the Maestro SwipeCommand
    // with relative coordinates will handle the actual recorded gesture.
    val dx = (endX - startX).toFloat()
    val dy = (endY - startY).toFloat()
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    val screenDiagonal = kotlin.math.sqrt(
      deviceWidth.toFloat() * deviceWidth + deviceHeight.toFloat() * deviceHeight,
    )
    // Scale duration: short flick = 100ms, long drag = 400ms
    val durationMs = (100 + (distance / screenDiagonal * 300)).toLong().coerceIn(100, 400)

    driver.swipe(
      start = Point(startX, startY),
      end = Point(endX, endY),
      durationMs = durationMs,
    )
  }

  override suspend fun inputText(text: String) {
    driver.inputText(text)
  }

  override suspend fun pressKey(key: String) {
    val keyCode = when (key) {
      "Back" -> KeyCode.BACK
      "Enter" -> KeyCode.ENTER
      "Backspace" -> KeyCode.BACKSPACE
      "Tab" -> null // Maestro doesn't have a Tab keycode
      "Escape" -> null // Maestro doesn't have an Escape keycode
      else -> null
    }
    if (keyCode != null) {
      driver.pressKey(keyCode)
    }
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode {
    val treeNode = driver.contentDescriptor(false)
      .filterOutOfBounds(width = deviceWidth, height = deviceHeight)
    return treeNode?.toViewHierarchyTreeNode()
      ?: ViewHierarchyTreeNode(nodeId = 0)
  }

  override suspend fun getScreenshot(): ByteArray {
    return captureScreenshotBytes() ?: ByteArray(0)
  }

  private fun captureScreenshotBytes(): ByteArray? {
    return try {
      Buffer().use { sink ->
        driver.takeScreenshot(sink, compressed = false)
        sink.readByteArray()
      }
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      null
    }
  }
}

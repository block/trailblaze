package xyz.block.trailblaze.android.uiautomator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.scale
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.io.ByteArrayOutputStream

/**
 * Snapshot in time of what the screen has in it.
 * 
 * Memory Optimization:
 * - Only stores the clean (non-annotated) screenshot in memory
 * - Only stores the original (unfiltered) view hierarchy in memory
 * - Set-of-mark annotations are generated on-demand
 * - Filtered view hierarchy is generated on-demand
 */
class AndroidOnDeviceUiAutomatorScreenState(
  private val filterViewHierarchy: Boolean = false,
  private val screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
  private val setOfMarkEnabled: Boolean = true,
  maxAttempts: Int = 1,
  includeScreenshot: Boolean = true,
) : ScreenState {

  override var deviceWidth: Int = -1
  override var deviceHeight: Int = -1
  override var viewHierarchyOriginal: ViewHierarchyTreeNode

  // Store only the clean screenshot bytes (compressed)
  private var _screenshotBytes: ByteArray = ByteArray(0)

  init {
    val (displayWidth, displayHeight) = withUiDevice { displayWidth to displayHeight }
    deviceWidth = displayWidth
    deviceHeight = displayHeight

    var matched = false
    var attempts = 0
    var lastViewHierarchyOriginal: ViewHierarchyTreeNode? = null
    var lastScreenshotBytes: ByteArray? = null

    while (!matched && attempts < maxAttempts) {
      val vh1Original = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyAsSerializableTreeNodes(
        xmlHierarchy = dumpViewHierarchy(),
        excludeKeyboardElements = false,
      ).relabelWithFreshIds()

      val bytes = if (includeScreenshot) {
        // Use original hierarchy for screenshot capture (filtering happens lazily later)
        getScreenshot(vh1Original, screenshotScalingConfig)
      } else {
        null
      }

      val vh2Original = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyAsSerializableTreeNodes(
        xmlHierarchy = dumpViewHierarchy(),
        excludeKeyboardElements = false,
      )

      lastViewHierarchyOriginal = vh1Original
      lastScreenshotBytes = bytes

      // Create a copy of the view hierarchies with the node ids all set to 0 and then compare
      val vh1Zeroed = vh1Original.copy(nodeId = 0)
      val vh2Zeroed = vh2Original.copy(nodeId = 0)

      if (vh1Zeroed == vh2Zeroed) {
        matched = true
      } else {
        attempts++
        if (attempts < maxAttempts) {
          Thread.sleep((attempts * 100).toLong())
        }
      }
    }

    // Ensure these are set after the loop
    viewHierarchyOriginal = lastViewHierarchyOriginal ?: throw IllegalStateException("Failed to get view hierarchy")
    _screenshotBytes = lastScreenshotBytes ?: ByteArray(0)
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID

  /**
   * Returns the filtered view hierarchy.
   * Generates filtered hierarchy on-demand without caching - used for LLM requests.
   */
  override val viewHierarchy: ViewHierarchyTreeNode
    get() {
      if (!filterViewHierarchy) {
        return viewHierarchyOriginal
      }

      val viewHierarchyFilter = ViewHierarchyFilter.create(
        screenHeight = deviceHeight,
        screenWidth = deviceWidth,
        platform = TrailblazeDevicePlatform.ANDROID,
      )
      return viewHierarchyFilter.filterInteractableViewHierarchyTreeNodes(viewHierarchyOriginal)
    }

  /**
   * Returns the clean screenshot without any annotations.
   * This is always stored in memory and used for logging and snapshots.
   */
  override val screenshotBytes: ByteArray
    get() = _screenshotBytes

  /**
   * Returns screenshot bytes with set-of-mark annotations applied.
   * Generates annotations on-demand without caching - used only for LLM requests.
   * 
   * Decodes the stored screenshot bytes back to a bitmap, applies annotations, then re-encodes.
   * This avoids storing the uncompressed bitmap in memory.
   */
  override val annotatedScreenshotBytes: ByteArray
    get() {
      // If set-of-mark is disabled, return the clean screenshot
      if (!setOfMarkEnabled) {
        return _screenshotBytes
      }

      // If no screenshot bytes, return empty
      if (_screenshotBytes.isEmpty()) {
        return _screenshotBytes
      }

      // Decode the compressed bytes back to a bitmap
      val bitmap = android.graphics.BitmapFactory.decodeByteArray(_screenshotBytes, 0, _screenshotBytes.size)
        ?: return _screenshotBytes
      
      try {
        // Create a mutable copy for annotation
        val annotatedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle() // Recycle the decoded bitmap
        
        // Apply set-of-mark annotations
        AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
          originalScreenshotBitmap = annotatedBitmap,
          elements = ViewHierarchyTreeNodeUtils.from(
            viewHierarchy,
            DeviceInfo(
              platform = Platform.ANDROID,
              widthPixels = annotatedBitmap.width,
              heightPixels = annotatedBitmap.height,
              widthGrid = annotatedBitmap.width,
              heightGrid = annotatedBitmap.height,
            ),
          ),
          includeLabel = true,
        )
        
        // Convert to bytes and clean up
        val bytes = annotatedBitmap.toByteArray()
        annotatedBitmap.recycle()
        
        return bytes
      } catch (e: Exception) {
        bitmap.recycle()
        throw e
      }
    }

  companion object {

    private inline fun <T> traceOnDeviceUiAutomatorScreenState(name: String, block: () -> T): T = traceRecorder.trace(name, "OnDeviceUiAutomatorScreenState", emptyMap(), block)

    fun dumpViewHierarchy(): String = traceOnDeviceUiAutomatorScreenState("dumpViewHierarchy") {
      ByteArrayOutputStream().use { outputStream ->
        withUiDevice {
          setCompressedLayoutHierarchy(false)
          dumpWindowHierarchy(outputStream)
        }
        outputStream.toString()
      }
    }

    /**
     * Creates a blank bitmap with the current window size.
     *
     * Used when a screenshot comes back as "null" which occurs on secure screens.
     */
    fun createBlankBitmapForCurrentWindowSize(): Bitmap = withUiDevice {
      // Create a bitmap with the specified width and height
      val bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
      // Create a canvas to draw on the bitmap
      val canvas = Canvas(bitmap)
      // Fill the canvas with a black background
      canvas.drawColor(Color.BLACK)
      bitmap
    }

    /**
     * Takes the screenshot with UiAutomator.
     * If the screenshot is blank, we'll draw bounding boxes based on view hierarchy data.
     */
    fun takeScreenshot(
      viewHierarchy: ViewHierarchyTreeNode?,
      setOfMarkEnabled: Boolean = true,
    ): Bitmap? = traceOnDeviceUiAutomatorScreenState("${AndroidOnDeviceUiAutomatorScreenState::class.simpleName}.takeScreenshot") {
      val screenshotBitmap = traceOnDeviceUiAutomatorScreenState("takeScreenshot") {
        withUiAutomation { takeScreenshot() }
      }
      try {
        if (setOfMarkEnabled && screenshotBitmap != null) {
          viewHierarchy?.let {
            val markedBitmap = addSetOfMark(screenshotBitmap, viewHierarchy)
            if (screenshotBitmap != markedBitmap) {
              // Recycle the original bitmap to free up memory
              screenshotBitmap.recycle()
            }
            return markedBitmap
          }
        }
        return screenshotBitmap
      } catch (e: Exception) {
        screenshotBitmap?.recycle()
        throw e
      }
    }

    private fun addSetOfMark(screenshotBitmap: Bitmap, viewHierarchy: ViewHierarchyTreeNode): Bitmap = traceOnDeviceUiAutomatorScreenState("addSetOfMark") {
      val mutableBitmap = if (!screenshotBitmap.isMutable) {
        screenshotBitmap.copy(Bitmap.Config.ARGB_8888, true)
      } else {
        screenshotBitmap
      }
      AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
        originalScreenshotBitmap = mutableBitmap,
        elements = ViewHierarchyTreeNodeUtils.from(
          viewHierarchy,
          DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = mutableBitmap.width,
            heightPixels = mutableBitmap.height,
            widthGrid = mutableBitmap.width,
            heightGrid = mutableBitmap.height,
          ),
        ),
        includeLabel = true,
      )
      return mutableBitmap
    }
  }

  /**
   * Captures the clean screenshot and returns the compressed bytes.
   * The bitmap is converted to bytes and recycled immediately to minimize memory usage.
   */
  private fun getScreenshot(
    viewHierarchy: ViewHierarchyTreeNode?,
    screenshotScalingConfig: ScreenshotScalingConfig?,
  ): ByteArray? {
    // Capture only the clean screenshot (without set-of-mark)
    val cleanBitmap = takeScreenshot(
      viewHierarchy = viewHierarchy,
      setOfMarkEnabled = false,
    ) ?: return null
    
    // Scale if needed
    val bitmap = if (screenshotScalingConfig != null) {
      val scaled = cleanBitmap.scale(
        maxDim1 = screenshotScalingConfig.maxDimension1,
        maxDim2 = screenshotScalingConfig.maxDimension2,
      )
      // Recycle original if scaling created a new bitmap
      if (scaled != cleanBitmap) {
        cleanBitmap.recycle()
      }
      scaled
    } else {
      cleanBitmap
    }
    
    // Convert to bytes and recycle bitmap immediately
    val bytes = bitmap.toByteArray()
    bitmap.recycle()
    
    return bytes
  }
}

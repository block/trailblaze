package xyz.block.trailblaze.mcp.handlers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.handlers.GetScreenStateRequestHandler.Companion.buildResponse

/**
 * JVM-only tests for the pure response-builder extracted from
 * [GetScreenStateRequestHandler]. The full handler requires the Android
 * framework to capture a screen state, so we only exercise the branching that
 * decides whether annotation bytes are produced.
 *
 * The key guarantee these tests lock in: when the caller passes
 * `includeAnnotatedScreenshot = false`, [ScreenState.annotatedScreenshotBytes]
 * must NOT be read — otherwise the whole point of the flag (saving the CPU /
 * memory / bandwidth cost of rendering the set-of-mark overlay) is lost.
 */
class GetScreenStateRequestHandlerTest {

  @Test
  fun `buildResponse does not read annotatedScreenshotBytes when flag is false`() {
    val screenState = ThrowingAnnotatedScreenState()
    val request = GetScreenStateRequest(
      includeScreenshot = true,
      includeAnnotatedScreenshot = false,
    )

    val response = buildResponse(request, screenState)

    assertNotNull(response.screenshotBase64, "Clean screenshot should still be produced")
    assertNull(response.annotatedScreenshotBase64, "Annotated screenshot should be null when flag is false")
    // If annotatedScreenshotBytes had been touched, the getter would have thrown
    // before we got here — reaching this point proves it was skipped.
  }

  @Test
  fun `buildResponse reads annotatedScreenshotBytes when flag is true`() {
    val screenState = FixedBytesScreenState(clean = byteArrayOf(1, 2), annotated = byteArrayOf(9, 9))
    val request = GetScreenStateRequest(
      includeScreenshot = true,
      includeAnnotatedScreenshot = true,
    )

    val response = buildResponse(request, screenState)

    assertNotNull(response.screenshotBase64)
    assertNotNull(response.annotatedScreenshotBase64)
  }

  @Test
  fun `buildResponse carries trailblazeNodeTree from the screen state`() {
    // Guards that the pure builder faithfully forwards the captured tree — the
    // on-device handler passes `includeAllElements` to the screen state
    // constructor, and the builder must not independently filter or swap the
    // tree after the fact.
    val capturedTree = TrailblazeNode(
      nodeId = 42,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val screenState = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val annotatedScreenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeNodeTree: TrailblazeNode = capturedTree
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }
    val request = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = false,
      includeAllElements = true,
    )

    val response = buildResponse(request, screenState)

    assertEquals(capturedTree.nodeId, response.trailblazeNodeTree?.nodeId)
  }

  @Test
  fun `buildResponse skips both screenshots when includeScreenshot is false`() {
    // Even if the caller asked for annotation, no-screenshot requests must not
    // render either. This guards against accidentally re-enabling the annotated
    // path when the caller turned off screenshots entirely.
    val screenState = ThrowingScreenshotsScreenState()
    val request = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = true,
    )

    val response = buildResponse(request, screenState)

    assertNull(response.screenshotBase64)
    assertNull(response.annotatedScreenshotBase64)
  }

  // ── fakes ────────────────────────────────────────────────────────────────

  /**
   * [ScreenState] whose [annotatedScreenshotBytes] getter throws if accessed.
   * Used to assert that the response builder does not evaluate it when
   * `includeAnnotatedScreenshot = false`.
   */
  private class ThrowingAnnotatedScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = byteArrayOf(1, 2, 3)
    override val annotatedScreenshotBytes: ByteArray?
      get() = error("annotatedScreenshotBytes must not be evaluated when the flag is false")
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  /**
   * [ScreenState] whose clean AND annotated getters both throw. Used to assert
   * that neither is evaluated when `includeScreenshot = false`.
   */
  private class ThrowingScreenshotsScreenState : ScreenState {
    override val screenshotBytes: ByteArray?
      get() = error("screenshotBytes must not be evaluated when includeScreenshot is false")
    override val annotatedScreenshotBytes: ByteArray?
      get() = error("annotatedScreenshotBytes must not be evaluated when includeScreenshot is false")
    override val deviceWidth: Int = 0
    override val deviceHeight: Int = 0
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private class FixedBytesScreenState(
    private val clean: ByteArray,
    private val annotated: ByteArray,
  ) : ScreenState {
    override val screenshotBytes: ByteArray = clean
    override val annotatedScreenshotBytes: ByteArray = annotated
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}

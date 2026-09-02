package xyz.block.trailblaze.mcp.handlers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceCapturedScreenState
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.handlers.GetScreenStateRequestHandler.Companion.buildResponse
import xyz.block.trailblaze.mcp.handlers.GetScreenStateRequestHandler.Companion.buildBinaryResponse

/**
 * JVM-only tests for [GetScreenStateRequestHandler]: the pure response-builders, plus the
 * captor seam — the handler is constructible on the JVM now that HOW a frame is captured is
 * injected rather than hardcoded to the Android accessibility/UiAutomator classes.
 *
 * The key guarantee the builder tests lock in: when the caller passes
 * `includeAnnotatedScreenshot = false`, [ScreenState.annotatedScreenshotBytes]
 * must NOT be read — otherwise the whole point of the flag (saving the CPU /
 * memory / bandwidth cost of rendering the set-of-mark overlay) is lost.
 */
class GetScreenStateRequestHandlerTest {

  @Test
  fun `a captor result flows through handle onto a Success response`() = runTest {
    val handler = GetScreenStateRequestHandler(
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("test-classifier")),
      captor = {
        OnDeviceCapturedScreenState(
          screenState = FixedBytesScreenState(byteArrayOf(1), byteArrayOf(2)),
          driverMigrationTreeNode = null,
          capturedAtDeviceMs = 77L,
        )
      },
    )

    val result = handler.handle(GetScreenStateRequest(includeScreenshot = false))

    val response = (result as RpcResult.Success).data
    assertEquals(77L, response.capturedAtDeviceMs)
    assertEquals(listOf("test-classifier"), response.deviceClassifiers)
  }

  @Test
  fun `a throwing captor maps onto Failure so readiness polling keeps waiting`() = runTest {
    // The host's waitForReady keys on Success vs Failure, so the handler must map ANY captor
    // throw onto Failure rather than letting it escape and kill the serve loop.
    val handler = GetScreenStateRequestHandler(
      captor = { error("hierarchy dump crashed") },
    )

    val result = handler.handle(GetScreenStateRequest(requireAndroidAccessibilityService = true))

    val failure = result as RpcResult.Failure
    assertTrue(
      failure.message.contains("hierarchy dump crashed"),
      "The captor's message must survive onto the Failure, got: ${failure.message}",
    )
    assertNotNull(failure.details, "A genuine capture failure keeps its stack trace for operators")
  }

  @Test
  fun `a not-ready captor answers without stack-trace details`() = runTest {
    // waitForReady polls this request up to 120 times during a cold start, and concatenates
    // message + details into the failure it retains. A stack trace on that expected answer
    // would spam logcat and bloat the host's terminal error, so the typed not-ready throw must
    // come back as message-only.
    val handler = GetScreenStateRequestHandler(
      captor = { throw OnDeviceScreenStateNotReadyException("Accessibility service not yet bound") },
    )

    val result = handler.handle(GetScreenStateRequest(requireAndroidAccessibilityService = true))

    val failure = result as RpcResult.Failure
    assertEquals("Accessibility service not yet bound", failure.message)
    assertNull(failure.details, "The expected readiness answer must carry no stack trace")
  }

  @Test
  fun `a captor that fails an assertion still maps onto Failure`() = runTest {
    // The in-process driver captures through Espresso/Compose, which report failure as
    // AssertionError — an Error, not an Exception. Catching only Exception would let it escape
    // into Ktor, so the host would see a transport error instead of a reportable RpcResult.
    val handler = GetScreenStateRequestHandler(
      captor = { throw AssertionError("no compose roots found") },
    )

    val result = handler.handle(GetScreenStateRequest(includeScreenshot = false))

    val failure = result as RpcResult.Failure
    assertTrue(
      failure.message.contains("no compose roots found"),
      "The assertion's message must survive onto the Failure, got: ${failure.message}",
    )
  }

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
  fun `buildBinaryResponse keeps screenshots raw and leaves base64 empty`() {
    val screenState = FixedBytesScreenState(clean = byteArrayOf(1, 2), annotated = byteArrayOf(9, 9))
    val request = GetScreenStateRequest(
      includeScreenshot = true,
      includeAnnotatedScreenshot = true,
    )

    val response = buildBinaryResponse(request, screenState)

    assertContentEquals(byteArrayOf(1, 2), response.screenshotBytes)
    assertContentEquals(byteArrayOf(9, 9), response.annotatedScreenshotBytes)
    assertNull(response.screenshotBase64)
    assertNull(response.annotatedScreenshotBase64)
  }

  @Test
  fun `both builders carry the capture timestamp onto the wire`() {
    val screenState = FixedBytesScreenState(clean = byteArrayOf(1), annotated = byteArrayOf(2))
    val request = GetScreenStateRequest(includeScreenshot = false)

    // The stream-screenshot gate reads this stamp on whichever transport served the response,
    // so the JSON and binary builders must both forward it.
    assertEquals(1_234L, buildResponse(request, screenState, capturedAtDeviceMs = 1_234L).capturedAtDeviceMs)
    assertEquals(1_234L, buildBinaryResponse(request, screenState, capturedAtDeviceMs = 1_234L).capturedAtDeviceMs)

    // Back-compat default: callers that don't stamp produce a null (older-server) response.
    assertNull(buildResponse(request, screenState).capturedAtDeviceMs)
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

  @Test
  fun `neither builder reads tree-derived fields when includeTree is false`() {
    // The mirror frame loop asks for pixels only. The fake's throwing getters prove the builders
    // never touch tree-derived properties on such a request, so a tree-less response cannot
    // depend on the captor having skipped its walk — the same guarantee the includeScreenshot
    // fakes above lock in for pixels.
    val screenState = ThrowingTreeScreenState()
    val request = GetScreenStateRequest(includeScreenshot = true, includeTree = false)

    val response = buildResponse(request, screenState)
    val binaryResponse = buildBinaryResponse(request, screenState)

    for (r in listOf(response, binaryResponse)) {
      assertEquals(ViewHierarchyTreeNode(), r.viewHierarchy)
      assertNull(r.trailblazeNodeTree)
      assertNull(r.pageContextSummary)
    }
    assertNotNull(response.screenshotBase64, "Pixels are the point of a tree-less frame")
    assertContentEquals(byteArrayOf(1, 2, 3), binaryResponse.screenshotBytes)
  }

  @Test
  fun `both builders read tree-derived fields when includeTree is true`() {
    // The control for the gate above: a defaulted request must keep forwarding the state's own
    // hierarchy, or every recording capture would come back empty. Every gated field is
    // deliberately non-empty, so none of these can pass by matching the gate's own substitute,
    // and both builders are checked because the gate is duplicated in each.
    val capturedHierarchy = ViewHierarchyTreeNode(nodeId = 42, accessibilityText = "captured")
    val capturedTree = TrailblazeNode(
      nodeId = 7,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val screenState = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val annotatedScreenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = capturedHierarchy
      override val trailblazeNodeTree: TrailblazeNode = capturedTree
      // pageContextSummary's default reads this and keeps everything before the first blank
      // line, so the trailing block is what proves the summary was derived rather than copied.
      override val viewHierarchyTextRepresentation: String = "Login screen\nEmail field\n\nfull tree"
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }
    val request = GetScreenStateRequest(includeScreenshot = false)

    for (r in listOf(buildResponse(request, screenState), buildBinaryResponse(request, screenState))) {
      assertEquals(capturedHierarchy, r.viewHierarchy)
      assertEquals(capturedTree.nodeId, r.trailblazeNodeTree?.nodeId)
      assertEquals("Login screen\nEmail field", r.pageContextSummary)
    }
  }

  // ── fakes ────────────────────────────────────────────────────────────────

  /**
   * [ScreenState] whose tree-derived getters all throw. Used to assert the response builders
   * never evaluate them on an `includeTree = false` request. [pageContextSummary] is covered via
   * [viewHierarchyTextRepresentation], which its default implementation reads.
   */
  private class ThrowingTreeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = byteArrayOf(1, 2, 3)
    override val annotatedScreenshotBytes: ByteArray? = byteArrayOf(4)
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode
      get() = error("viewHierarchy must not be evaluated when includeTree is false")
    override val trailblazeNodeTree: TrailblazeNode
      get() = error("trailblazeNodeTree must not be evaluated when includeTree is false")
    override val viewHierarchyTextRepresentation: String
      get() = error("pageContextSummary must not be evaluated when includeTree is false")
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

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

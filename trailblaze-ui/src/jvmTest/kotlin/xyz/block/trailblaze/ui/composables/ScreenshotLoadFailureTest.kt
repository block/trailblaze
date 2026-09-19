package xyz.block.trailblaze.ui.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.ColorImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.test.FakeImageLoaderEngine
import xyz.block.trailblaze.api.AgentDriverAction
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.ScreenshotLoadLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import coil3.ImageLoader as CoilImageLoader

/**
 * Pins what the screenshot panes say when they cannot show a screenshot.
 *
 * The failure these guard against is silence. Before, a screenshot that would not load left a
 * blank rectangle with no message anywhere — the shape of the ProGuard/Coil breakage
 * (block/trailblaze#194), where Coil loses every fetcher and returns an error result for even a
 * local file. `ScreenshotImage` made it worse than blank: it kept drawing the click annotation, so
 * a reader saw a crosshair or a failed-assertion banner floating on an empty pane and went looking
 * at the trail instead of at the image.
 *
 * `ScreenshotDiagnosticsTest` covers the wording in isolation; these prove the panes are wired to
 * it, that a failed load takes the annotation down with the image, and that the failure is not
 * final.
 *
 * The image pipeline itself is Coil's own fake engine rather than a real fetch of a real missing
 * file: which request fails is then a property of the test instead of a wall-clock race, and the
 * cause the pane prints is one the test chose, so it can assert the cause reached the pane rather
 * than just that some sentence appeared.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ScreenshotLoadFailureTest {

  /** Requests made for this model succeed; anything else fails. */
  private val loadable = "trailblaze-test://loadable"

  /** Fails the first time it is requested and succeeds after that, so a retry has something to do. */
  private val flaky = "trailblaze-test://flaky"

  private val flakyAttempts = AtomicInteger(0)

  @BeforeTest
  fun installFakeImagePipeline() {
    flakyAttempts.set(0)
    val screenshot = ColorImage(width = 100, height = 200)
    val engine = FakeImageLoaderEngine.Builder()
      .intercept(loadable, screenshot)
      .intercept(
        { data: Any -> data == flaky },
        object : FakeImageLoaderEngine.OptionalInterceptor {
          override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            if (flakyAttempts.getAndIncrement() == 0) chain.failed() else chain.succeeded(screenshot)
        },
      )
      .default(Interceptor { chain -> chain.failed() })
      .build()
    SingletonImageLoader.setUnsafe(
      CoilImageLoader.Builder(PlatformContext.INSTANCE).components { add(engine) }.build(),
    )
    // The reporting is deduplicated for the life of the process, and these tests share one.
    ScreenshotLoadLog.resetForTest()
  }

  @AfterTest
  fun removeFakeImagePipeline() {
    SingletonImageLoader.reset()
  }

  private fun Interceptor.Chain.failed() =
    ErrorResult(image = null, request = request, throwable = IllegalStateException(FAILURE_DETAIL))

  private fun Interceptor.Chain.succeeded(image: ColorImage) =
    SuccessResult(image = image, request = request, dataSource = DataSource.MEMORY)

  /** The loader breakage: a reference is named, and nothing comes back for it. */
  private object NothingToLoad : ImageLoader {
    override fun getImageModel(sessionId: String, screenshotFile: String?): Any? = null
  }

  /** A loader that hands back one model, whatever it is asked for. */
  private class FixedModelLoader(private val model: String) : ImageLoader {
    override fun getImageModel(sessionId: String, screenshotFile: String?): Any = model
  }

  /**
   * An annotation with words of its own, so its absence is assertable. The default crosshair draws
   * no text and no content description, which is exactly why it misleads.
   */
  private val failedAssertion = AgentDriverAction.AssertCondition(
    conditionDescription = "Total is visible",
    x = 50,
    y = 100,
    isVisible = true,
    textToDisplay = "Total",
    succeeded = false,
  )

  private val annotationText = """Expected "Total" not found"""

  /**
   * The control for every "no annotation" assertion below. Without it they would keep passing if
   * the annotation stopped rendering for some unrelated reason, and would prove nothing.
   */
  @Test
  fun `a screenshot that does load keeps its annotation`() = runComposeUiTest {
    setContent { Pane(imageLoader = FixedModelLoader(loadable)) }

    awaitText(annotationText)
    assertNoFailureMessage()
  }

  @Test
  fun `a screenshot with nothing to load says so, and names the screenshot`() = runComposeUiTest {
    setContent { Pane(imageLoader = NothingToLoad) }

    // Naming the screenshot is the point: "Failed to load screenshot" alone does not say whether
    // the reference was wrong or the loader was, and the two are looked at in different places.
    onNodeWithText("Failed to load screenshot: nothing to load for screenshot-1234.png")
      .assertIsDisplayed()
    assertNoAnnotation()
  }

  @Test
  fun `a step with no screenshot at all stays silent`() = runComposeUiTest {
    setContent { Pane(screenshotFile = null, imageLoader = NothingToLoad) }

    // Most logs have no screenshot. Saying "failed" for every one of them would bury the rows
    // where something is actually wrong.
    assertNoFailureMessage()
  }

  @Test
  fun `a blank screenshot reference stays silent too`() = runComposeUiTest {
    // A blank reference reaches the loaders as a real name, and they answer with a URL for the
    // session directory — as this loader does. That URL fails in the decoder, and the row would
    // report a screenshot that could not be loaded when there was never one to load.
    setContent { Pane(screenshotFile = "   ", imageLoader = FixedModelLoader(UNFETCHABLE)) }

    assertNoFailureMessage()
  }

  @Test
  fun `a screenshot that fails to load says why, and drops its annotation`() = runComposeUiTest {
    setContent { Pane(imageLoader = FixedModelLoader(UNFETCHABLE)) }

    // The cause, not just the sentence: the pane exists to say which half of the pipeline broke.
    awaitText("Failed to load screenshot: IllegalStateException: $FAILURE_DETAIL")

    // The annotation is what made this worse than a blank pane: over nothing, a failed-assertion
    // banner reads as a trail that asserted against the wrong screen.
    assertNoAnnotation()
  }

  @Test
  fun `a failed screenshot can be retried`() = runComposeUiTest {
    setContent { Pane(imageLoader = FixedModelLoader(flaky)) }

    // A live session writes screenshots while the timeline is already on screen, so a row can fail
    // on a file that lands a moment later. Without this the row is stuck on a stale "failed" for
    // as long as the session view is open.
    awaitText(RETRY_PROMPT)
    onNodeWithText(RETRY_PROMPT).performClick()

    awaitText(annotationText)
    assertNoFailureMessage()
  }

  @Test
  fun `a screenshot with no device size still shows the reason`() = runComposeUiTest {
    // The fallback takes the screenshot's aspect ratio so a row keeps its shape — which is a
    // divide by zero for a step recorded without a device size, and a frame of no height at all.
    setContent { Pane(deviceWidth = 0, deviceHeight = 0, imageLoader = NothingToLoad) }

    onNodeWithText("Failed to load screenshot", substring = true).assertIsDisplayed()
  }

  @Test
  fun `a screenshot with nothing to load is not offered a retry`() = runComposeUiTest {
    setContent { Pane(imageLoader = NothingToLoad) }

    // Retry re-issues the request behind a model. With no model there is nothing to re-issue, so
    // the button would redraw the same sentence and read as a report that keeps refusing to fix
    // itself — the opposite of what an offered retry promises.
    assertTrue(
      onAllNodesWithText(RETRY_PROMPT, substring = true).fetchSemanticsNodes().isEmpty(),
      "a retry that cannot change anything must not be offered",
    )
  }

  @Test
  fun `the zoom dialog says why instead of showing an empty frame`() = runComposeUiTest {
    setContent {
      MaterialTheme {
        Surface {
          ImagePreviewDialog(
            imageModel = UNFETCHABLE,
            deviceWidth = 100,
            deviceHeight = 200,
            clickX = 50,
            clickY = 100,
            action = failedAssertion,
            onDismiss = {},
          )
        }
      }
    }

    // Without this the dialog is an empty bordered frame with live zoom controls, which reads as
    // broken zoom rather than a screenshot that never arrived.
    awaitText("Failed to load screenshot: IllegalStateException: $FAILURE_DETAIL")
    assertNoAnnotation()
  }

  @Composable
  private fun Pane(
    screenshotFile: String? = "screenshot-1234.png",
    deviceWidth: Int = 100,
    deviceHeight: Int = 200,
    imageLoader: ImageLoader,
  ) {
    MaterialTheme {
      Surface {
        ScreenshotImage(
          sessionId = "session-1",
          screenshotFile = screenshotFile,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          clickX = 50,
          clickY = 100,
          action = failedAssertion,
          imageLoader = imageLoader,
        )
      }
    }
  }

  /**
   * Waits for [text] to appear rather than pinning how fast a load resolves.
   *
   * The bound is hang containment, not a performance budget: the engine answers from memory, so
   * anything approaching it means the pane never recomposed at all.
   */
  private fun ComposeUiTest.awaitText(text: String) {
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MS) {
      onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun ComposeUiTest.assertNoAnnotation() {
    assertTrue(
      onAllNodesWithText(annotationText, substring = true).fetchSemanticsNodes().isEmpty(),
      "the click annotation must not be drawn over a screenshot that is not there",
    )
  }

  private fun ComposeUiTest.assertNoFailureMessage() {
    assertTrue(
      onAllNodesWithText("Failed to load screenshot", substring = true)
        .fetchSemanticsNodes()
        .isEmpty(),
      "nothing here failed to load, so nothing may say it did",
    )
  }

  companion object {
    /** Any model the fake engine was not told to load, which it fails with [FAILURE_DETAIL]. */
    private const val UNFETCHABLE = "trailblaze-test://unfetchable"

    private const val FAILURE_DETAIL = "the fake engine has no fetcher for this request"
    private const val RENDER_TIMEOUT_MS = 60_000L
  }
}

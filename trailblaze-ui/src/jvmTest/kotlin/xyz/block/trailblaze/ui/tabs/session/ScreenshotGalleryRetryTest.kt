package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlinx.datetime.Instant
import xyz.block.trailblaze.ui.composables.RETRY_PROMPT
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.ScreenshotLoadLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import coil3.ImageLoader as CoilImageLoader

/**
 * The gallery draws its thumbnails through the same pane as every other surface, and then wraps
 * each one in its own selection handling. This pins that the wrapping leaves the pane's retry
 * reachable — an overlay covering the thumbnail would take the click, and the reader would press
 * "Click to retry" and watch the selection move instead.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ScreenshotGalleryRetryTest {

  private val attempts = AtomicInteger(0)

  @BeforeTest
  fun installFakeImagePipeline() {
    attempts.set(0)
    val screenshot = ColorImage(width = 100, height = 200)
    val engine = FakeImageLoaderEngine.Builder()
      .intercept(
        { _: Any -> true },
        object : FakeImageLoaderEngine.OptionalInterceptor {
          override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            if (attempts.getAndIncrement() == 0) {
              ErrorResult(
                image = null,
                request = chain.request,
                throwable = IllegalStateException("the fake engine failed this one on purpose"),
              )
            } else {
              SuccessResult(image = screenshot, request = chain.request, dataSource = DataSource.MEMORY)
            }
        },
      )
      .build()
    SingletonImageLoader.setUnsafe(
      CoilImageLoader.Builder(PlatformContext.INSTANCE).components { add(engine) }.build(),
    )
    ScreenshotLoadLog.resetForTest()
  }

  @AfterTest
  fun removeFakeImagePipeline() {
    SingletonImageLoader.reset()
    ScreenshotLoadLog.resetForTest()
  }

  private object FixedModelLoader : ImageLoader {
    override fun getImageModel(sessionId: String, screenshotFile: String?): Any =
      "trailblaze-test://gallery"
  }

  private fun item(label: String) = ScreenshotTimelineItem(
    timestamp = Instant.fromEpochMilliseconds(0),
    screenshotFile = "$label.png",
    deviceWidth = 100,
    deviceHeight = 200,
    label = label,
    action = null,
    clickX = null,
    clickY = null,
  )

  @Test
  fun `a failed thumbnail's retry is not taken by the gallery's selection handling`() =
    runComposeUiTest {
      var selectedIndex = 0
      val selections = mutableListOf<Int>()

      setContent {
        MaterialTheme {
          Surface {
            ScreenshotGallery(
              items = listOf(item("first"), item("second")),
              sessionStartTime = Instant.fromEpochMilliseconds(0),
              selectedIndex = selectedIndex,
              onSelectedIndexChanged = {
                selections += it
                selectedIndex = it
              },
              sessionId = "session-1",
              imageLoader = FixedModelLoader,
              onFullScreenClick = { _, _, _, _, _ -> },
            )
          }
        }
      }

      waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithText(RETRY_PROMPT, substring = true).fetchSemanticsNodes().isNotEmpty()
      }
      onAllNodesWithText(RETRY_PROMPT, substring = true)[0].performClick()

      // The retry has to reach the pane. If the gallery's own click target covers the thumbnail,
      // this click only moves the selection and the thumbnail stays failed forever.
      waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithText("Failed to load screenshot", substring = true)
          .fetchSemanticsNodes()
          .isEmpty()
      }
      assertTrue(
        selections.isEmpty(),
        "a retry click should retry, not change the selection, but selected: $selections",
      )
    }

  @Test
  fun `clicking a thumbnail still selects it`() = runComposeUiTest {
    val selections = mutableListOf<Int>()

    setContent {
      MaterialTheme {
        Surface {
          ScreenshotGallery(
            items = listOf(item("first"), item("second")),
            sessionStartTime = Instant.fromEpochMilliseconds(0),
            selectedIndex = 0,
            onSelectedIndexChanged = { selections += it },
            sessionId = "session-1",
            imageLoader = FixedModelLoader,
            onFullScreenClick = { _, _, _, _, _ -> },
          )
        }
      }
    }

    // The control: removing the overlay that used to swallow the retry must not cost the gallery
    // the selection click it was there for.
    onNodeWithText("second", substring = true).performClick()

    assertEquals(listOf(1), selections, "clicking a thumbnail should select it")
  }
}

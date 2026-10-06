package xyz.block.trailblaze.playwright.recording

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail

/**
 * A web recording places each frame by the time [PlaywrightScreencastFeed] stamps on it, so that
 * stamp must be when the page showed the frame. Against a live Chromium page, this changes the
 * page's colour step by step, records in the page when each colour was painted, and checks the
 * feed's first frame of each colour carries that time.
 *
 * Between steps the Playwright thread is held with other work, as a tool holds it while it encodes
 * a screenshot or builds a tree. Frames painted then are read only once it is free, so a stamp
 * taken on receipt lands [BUSY_MS] late — and the recording shows every step's screen that late.
 */
class PlaywrightScreencastFeedTimingTest {

  private lateinit var executor: ExecutorService
  private lateinit var dispatcher: ExecutorCoroutineDispatcher
  private lateinit var playwright: Playwright
  private lateinit var page: Page

  @Before
  fun setUp() {
    executor = Executors.newSingleThreadExecutor()
    dispatcher = executor.asCoroutineDispatcher()
    runBlocking(dispatcher) {
      playwright = Playwright.create()
      val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
      page = browser.newContext(Browser.NewContextOptions().setViewportSize(640, 400)).newPage()
      page.setContent(
        "<html><body style='margin:0'><div id=b style='width:100vw;height:100vh;background:rgb(0,0,0)'></div></body></html>",
      )
    }
  }

  @After
  fun tearDown() {
    runBlocking(dispatcher) { playwright.close() }
    dispatcher.close()
  }

  @Test
  fun `frames are stamped when the page painted them, even while the Playwright thread is busy`() {
    val feed = PlaywrightScreencastFeed(FakePageManager(page, dispatcher), PlaywrightScreencast.RECORDING_QUALITY)
    // (stamp, red channel at the centre) per frame.
    val frames = CopyOnWriteArrayList<Pair<Long, Int>>()
    val subscription = feed.subscribe { jpeg, stampMs ->
      val image = ImageIO.read(ByteArrayInputStream(jpeg))
      frames.add(stampMs to ((image.getRGB(image.width / 2, image.height / 2) shr 16) and 0xff))
    }
    try {
      runBlocking {
        delay(1_000) // let the screencast start
        for (step in 1..STEPS) {
          withContext(dispatcher) {
            page.evaluate(
              """k => {
                   document.getElementById('b').style.background = 'rgb(' + (k * $RED_STEP) + ',0,0)';
                   requestAnimationFrame(() => requestAnimationFrame(() => {
                     (window.__painted = window.__painted || {})[k] = Date.now();
                   }));
                 }""",
              step,
            )
            Thread.sleep(BUSY_MS)
          }
          delay(300)
        }
        delay(500)
      }
    } finally {
      subscription.close()
    }

    @Suppress("UNCHECKED_CAST")
    val painted = runBlocking(dispatcher) { page.evaluate("() => window.__painted") as Map<String, Number> }
    val lagsMs = (1..STEPS).mapNotNull { step ->
      val paintedAt = painted[step.toString()]?.toLong() ?: return@mapNotNull null
      val firstFrame = frames.firstOrNull { abs(it.second - step * RED_STEP) <= 3 } ?: return@mapNotNull null
      firstFrame.first - paintedAt
    }
    assertTrue(lagsMs.size >= STEPS - 2, "matched only ${lagsMs.size} of $STEPS steps to a frame: $lagsMs")
    assertTrue(
      lagsMs.all { abs(it) < TOLERANCE_MS },
      "frame stamps vs paint time (ms) must stay within $TOLERANCE_MS: $lagsMs",
    )
  }

  private class FakePageManager(
    private val page: Page,
    override val playwrightDispatcher: CoroutineDispatcher,
  ) : PlaywrightPageManager {
    override val currentPage: Page get() = page
    override val idlingConfig = PlaywrightNativeIdlingConfig()
    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = error("unused")
    override fun captureScreenStateForLogging(): ScreenState = error("unused")
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  private companion object {
    const val STEPS = 10
    const val RED_STEP = 20
    const val BUSY_MS = 300L

    /**
     * A few 60 Hz frames, independent of [BUSY_MS]: a swap stamp sits within a frame of the page's
     * own paint record (measured 0 to -16 ms), and the rest is margin for a loaded CI machine. A
     * receipt stamp misses by ~[BUSY_MS].
     */
    const val TOLERANCE_MS = 50L
  }
}

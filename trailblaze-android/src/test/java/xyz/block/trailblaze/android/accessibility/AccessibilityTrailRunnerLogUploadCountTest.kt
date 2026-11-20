package xyz.block.trailblaze.android.accessibility

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred

/**
 * The drain reports [AccessibilityTrailRunner.logUploadsInFlight] to the host as "log uploads left
 * pending", so it has to count actions, not the lane's jobs: each action also queues a screenshot
 * encode.
 */
class AccessibilityTrailRunnerLogUploadCountTest {

  @Test
  fun `an action still encoding and uploading counts as one pending upload`() {
    val encodeHeld = CompletableDeferred<Unit>()
    val uploadHeld = CompletableDeferred<Unit>()
    val before = AccessibilityTrailRunner.logUploadsInFlight()
    try {
      AccessibilityTrailRunner.queueActionLogs(encode = { encodeHeld.await() }, upload = { uploadHeld.await() })

      assertEquals(before + 1, AccessibilityTrailRunner.logUploadsInFlight(), "the host would report two uploads for one action")
    } finally {
      encodeHeld.complete(Unit)
      uploadHeld.complete(Unit)
      AccessibilityTrailRunner.flushLogs()
    }
    assertEquals(before, awaitUploadsInFlight(before), "the finished upload is still counted")
  }

  /**
   * The count drops in the upload job's completion handler, which can run just after [flushLogs]'s
   * join returns, so give it a moment rather than reading it once.
   */
  private fun awaitUploadsInFlight(expected: Int): Int {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (AccessibilityTrailRunner.logUploadsInFlight() != expected && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    return AccessibilityTrailRunner.logUploadsInFlight()
  }
}

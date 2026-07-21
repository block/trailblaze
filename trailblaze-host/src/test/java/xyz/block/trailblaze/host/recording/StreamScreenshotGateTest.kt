package xyz.block.trailblaze.host.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import xyz.block.trailblaze.host.recording.StreamScreenshotGate.Decision

class StreamScreenshotGateTest {

  // Fixed reference points. The tree clock (Android: the device clock) runs 5s ahead of the
  // host so offset bugs (dropping or double-applying it) can't cancel out to a passing test.
  private val nowHost = 1_000_000L
  private val offset = 5_000L

  private fun evaluate(
    lastFrameAtHost: Long? = nowHost - 200,
    lastChangeAtHost: Long? = nowHost - 600,
    treeCapturedAtDevice: Long? = (nowHost - 500) + offset,
    quietWindowMs: Long = 400,
    stallThresholdMs: Long = 3_000,
    latencyAllowanceMs: Long = 500,
    lastFeedAliveAtHost: Long? = null,
  ): Decision = StreamScreenshotGate.evaluate(
    nowHostMs = nowHost,
    lastFrameReceivedAtHostMs = lastFrameAtHost,
    lastContentChangeAtHostMs = lastChangeAtHost,
    treeCapturedAtMs = treeCapturedAtDevice,
    treeClockOffsetMs = offset,
    quietWindowMs = quietWindowMs,
    stallThresholdMs = stallThresholdMs,
    latencyAllowanceMs = latencyAllowanceMs,
    lastFeedAliveAtHostMs = lastFeedAliveAtHost,
  )

  @Test
  fun `no frame yet waits for the stream to warm up`() {
    assertEquals(Decision.AwaitFirstFrame, evaluate(lastFrameAtHost = null, lastChangeAtHost = null))
  }

  @Test
  fun `silence beyond the stall threshold is a dead stream, not a quiet screen`() {
    val decision = evaluate(lastFrameAtHost = nowHost - 4_000, lastChangeAtHost = nowHost - 4_000)
    assertEquals(Decision.Stalled(4_000), decision)
  }

  @Test
  fun `old frame with recent feed-alive pings is a static screen, not a stall`() {
    // Damage-driven encoder on a static screen: the last frame is well past the stall
    // threshold, but the drain loop keeps pinging — the frame still shows the current screen.
    val decision = evaluate(
      lastFrameAtHost = nowHost - 15_000,
      lastChangeAtHost = nowHost - 15_000,
      treeCapturedAtDevice = (nowHost - 500) + offset,
      lastFeedAliveAtHost = nowHost - 300,
    )
    assertIs<Decision.Accept>(decision)
  }

  @Test
  fun `stale feed-alive pings cannot rescue a dead pipeline`() {
    val decision = evaluate(
      lastFrameAtHost = nowHost - 15_000,
      lastChangeAtHost = nowHost - 15_000,
      lastFeedAliveAtHost = nowHost - 10_000,
    )
    assertEquals(Decision.Stalled(10_000), decision)
  }

  @Test
  fun `recent content change keeps waiting until the quiet window elapses`() {
    val decision = evaluate(lastFrameAtHost = nowHost - 50, lastChangeAtHost = nowHost - 100)
    assertEquals(Decision.AwaitQuiet(remainingQuietMs = 300), decision)
  }

  @Test
  fun `quiet and alive stream matching the tree stamp is accepted with the clock-mapped skew`() {
    val decision = evaluate()
    assertIs<Decision.Accept>(decision)
    // Frame received (host now-200) maps to device (now-200+offset); tree stamped at
    // device (now-500+offset) → frame is 300ms after the tree stamp.
    assertEquals(300L, decision.frameVsTreeSkewMs)
  }

  @Test
  fun `frame older than the tree stamp is still accepted when the screen stayed quiet`() {
    // Damage-driven stream: no new frame since before the tree capture just means the
    // screen didn't change. Negative skew, still a match.
    val decision = evaluate(
      lastFrameAtHost = nowHost - 900,
      lastChangeAtHost = nowHost - 900,
      quietWindowMs = 400,
    )
    assertIs<Decision.Accept>(decision)
    assertEquals(-400L, decision.frameVsTreeSkewMs)
  }

  @Test
  fun `content change after the tree capture is terminal for that tree`() {
    // Change observed at host now-600 → device (now-600+offset). Tree stamped 2s before
    // that, so even the 500ms latency allowance can't explain it away.
    val treeAtDevice = (nowHost - 2_600) + offset
    val decision = evaluate(treeCapturedAtDevice = treeAtDevice)
    assertEquals(Decision.ContentNewerThanTree(contentChangeAfterTreeMs = 1_500), decision)
  }

  @Test
  fun `content change within the latency allowance of the tree stamp is not a mismatch`() {
    // Change observed host-side 300ms after the tree stamp (device clock) — within the
    // 500ms encode+transport allowance, so it plausibly happened before the tree capture.
    val treeAtDevice = (nowHost - 900) + offset
    val decision = evaluate(treeCapturedAtDevice = treeAtDevice)
    assertIs<Decision.Accept>(decision)
  }

  @Test
  fun `unstamped tree from an older on-device server still gets dual-quiet matching`() {
    val decision = evaluate(treeCapturedAtDevice = null)
    assertIs<Decision.Accept>(decision)
    assertNull(decision.frameVsTreeSkewMs)
  }

  @Test
  fun `quiet check uses last frame receipt when no content change was recorded`() {
    val decision = evaluate(lastFrameAtHost = nowHost - 100, lastChangeAtHost = null)
    assertEquals(Decision.AwaitQuiet(remainingQuietMs = 300), decision)
  }
}

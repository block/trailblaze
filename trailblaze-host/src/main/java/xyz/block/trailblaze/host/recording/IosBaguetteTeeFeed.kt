package xyz.block.trailblaze.host.recording

import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Opens the iOS baguette H.264 feed as an [H264Tee] the caller can attach consumers to — the shared
 * plumbing behind both the live-viewer/agent-screenshot path ([DeviceStreamScreenshotSource.forIos])
 * and session video recording ([xyz.block.trailblaze.host.capture.BaguetteIosVideoCapture]).
 *
 * The baguette WebSocket ([streamIosLiveH264AccessUnits]) delivers Annex-B access units to a
 * [BlockingByteStream] FIFO, which a standalone [H264Tee] drains via [fifoProducer]. Attaching a
 * consumer to [tee] starts the tee's reader, which pulls from the FIFO the feed coroutine fills.
 * baguette forces an IDR at stream start, so the first consumer begins on a decodable keyframe.
 *
 * [onFeedEnded] fires when the WebSocket terminates on its own (baguette exit, network error) or on
 * cancellation — the signal the video path uses to restart recording via `simctl` for the
 * remainder, and the screenshot path uses to stop pairing against a frozen frame.
 *
 * **One WS per open.** Each [open] starts its own WebSocket; baguette's `serve` multiplexes many
 * clients server-side (see [IosBaguetteServer]), so the screenshot path and the video path each
 * holding their own feed shares the single simulator encoder without contention. Consolidating both
 * daemon-side consumers onto one refcounted WS is a possible follow-up; nothing here depends on it.
 */
internal class IosBaguetteTeeFeed private constructor(
  val tee: H264Tee,
  private val closer: () -> Unit,
) : AutoCloseable {

  override fun close() = closer()

  companion object {
    /**
     * Test-only: wrap an already-built [tee] with a [closer], skipping the WebSocket open. Lets a
     * unit test drive [BaguetteIosVideoCapture]'s baguette-present routing (feed death → simctl
     * remainder) without baguette or a simulator.
     */
    internal fun forTest(tee: H264Tee, closer: () -> Unit = {}): IosBaguetteTeeFeed =
      IosBaguetteTeeFeed(tee, closer)

    /**
     * Opens a baguette-backed tee for [deviceId], or returns null when baguette isn't installed
     * (caller falls back to its non-stream path). [onFeedEnded] is invoked exactly once when the
     * underlying WebSocket ends or the feed is cancelled.
     */
    fun open(deviceId: TrailblazeDeviceId, onFeedEnded: () -> Unit): IosBaguetteTeeFeed? {
      // Synchronously confirm a `baguette serve` is actually reachable before handing back a feed.
      // isAvailable() only proves the binary exists; ensureServing() blocks until the server answers
      // (or fails to come up). Deciding here — rather than letting the WS coroutine discover a dead
      // server asynchronously — is what lets the caller fall back cleanly: the video path to a
      // whole-session simctl recording, the screenshot path to on-device screenshots. Otherwise
      // start() would skip that fallback and the session's opening (or all of a short session) would
      // go unrecorded. ensureServing() is idempotent, so the stream's own later call is a no-op.
      if (IosBaguetteServer.ensureServing() == null) return null
      val byteStream = BlockingByteStream()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      // Construct the tee BEFORE launching the WS coroutine. standalone() only constructs (its
      // reader starts lazily on attach), so if it throws we must not already have a coroutine
      // streaming into byteStream that nothing drains — that would block the WS coroutine in
      // append() forever and leak the scope, since open() would propagate without handing back a
      // closeable handle.
      val tee =
        try {
          H264Tee.standalone(deviceId = deviceId, producerFactory = fifoProducer(byteStream))
        } catch (e: Exception) {
          scope.cancel()
          byteStream.close()
          throw e
        }
      scope.launch {
        try {
          streamIosLiveH264AccessUnits(deviceId) { accessUnit -> byteStream.append(accessUnit.bytes) }
        } finally {
          // WS ended (baguette exit, network error, or cancellation): notify, then close the FIFO
          // so the tee reader sees EOF and stops.
          onFeedEnded()
          byteStream.close()
        }
      }
      return IosBaguetteTeeFeed(tee) {
        runCatching { byteStream.close() }
        scope.cancel()
      }
    }

    /**
     * [H264Tee.ProducerFactory] that hands the tee an already-running FIFO. The feed coroutine owns
     * the FIFO's lifecycle; this wraps it as the producer's input so the tee's reader drains it, and
     * closes it so the reader unwinds on teardown.
     */
    private fun fifoProducer(stream: BlockingByteStream): H264Tee.ProducerFactory =
      object : H264Tee.ProducerFactory {
        override fun spawn(
          deviceId: TrailblazeDeviceId,
          videoSize: String,
          bitRate: String,
          unlimited: Boolean,
        ): H264Tee.ProducerHandle = object : H264Tee.ProducerHandle {
          override val input: InputStream = stream
          override fun close() {
            stream.close()
          }
        }
      }
  }
}

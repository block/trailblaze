package xyz.block.trailblaze.host.recording

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.LiveFrameConsumer
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.util.Console

/**
 * The one stream-sourced screenshot source for every platform's agent loop. Serves the
 * screenshot half of a screen-state capture from the SAME live device stream the `/devices`
 * viewer plays — Android's `screenrecord` H.264 tee, iOS's baguette H.264 WebSocket, web's
 * CDP screencast — instead of a per-turn on-device screenshot. One code path; the only
 * per-platform parts are how frames are acquired and how the tree clock relates to the host
 * clock, both captured in the [forAndroid]/[forIos]/[forWeb] factories:
 *
 * | platform | frames | decode | tree clock offset |
 * |---|---|---|---|
 * | Android | shared `screenrecord` [H264Tee] | [LiveFrameConsumer] ffmpeg mjpeg | measured via [DeviceClock] (device-stamped trees) |
 * | iOS | baguette WS ([streamIosLiveH264AccessUnits]) → standalone [H264Tee] | [LiveFrameConsumer] ffmpeg mjpeg | 0 (host-stamped trees) |
 * | web | CDP screencast ([PlaywrightDeviceScreenStream.streamScreencastJpegFrames]) | none (already JPEG) | 0 (host-stamped trees) |
 *
 * All pairing logic (dual-quiet + liveness + tree-clock ordering) lives in the shared
 * [StreamFrameMonitor]/[StreamScreenshotGate]; perceptual change confirmation
 * ([FrameChangeDetector]) makes quiescence re-encode-tolerant, which continuous encoders
 * (baguette) require and damage-driven encoders (screenrecord, CDP) are unaffected by.
 *
 * Lifecycle: [start] opens the feed lazily (callers invoke it off the first capture so
 * sessions that never read a screenshot don't hold a stream open) and returns false when the
 * platform declines (e.g. baguette not installed) — the caller stays on its on-device
 * screenshot path. [close] tears the feed down. One instance per (runner, device) session.
 */
class DeviceStreamScreenshotSource private constructor(
  private val streamDescription: String,
  /**
   * Measured/known `treeClockEpoch - hostEpoch`, invoked once inside [start] (Android's
   * measurement is an adb round-trip, so it must not run at construction time).
   */
  private val treeClockOffsetMs: () -> Long,
  /**
   * Opens the platform feed into the monitor; returns the handle [close] tears down, or null
   * to decline (caller keeps its on-device screenshot path). [onFeedEnded] marks the source
   * dead when a feed terminates on its own (baguette WS end, screencast teardown) so later
   * captures fall back instead of pairing against a frozen frame.
   */
  private val attachFeed: (monitor: StreamFrameMonitor, onFeedEnded: () -> Unit) -> AutoCloseable?,
) : AutoCloseable {

  private var monitor: StreamFrameMonitor? = null
  private var feed: AutoCloseable? = null
  private val started = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)

  /** Set when the feed terminates on its own: a dead feed can't produce a current frame. */
  @Volatile private var feedEnded = false

  /**
   * Idempotent. Returns true when the stream engaged; false when the platform declined so
   * the caller keeps its on-device screenshot path.
   */
  fun start(): Boolean {
    if (closed.get()) return false
    if (!started.compareAndSet(false, true)) return monitor != null
    val frameMonitor = StreamFrameMonitor(treeClockOffsetMs = treeClockOffsetMs())
    val handle = attachFeed(frameMonitor) { feedEnded = true }
    if (handle == null) {
      Console.log("[stream-screenshot] $streamDescription declined; staying on the on-device screenshot path")
      return false
    }
    monitor = frameMonitor
    feed = handle
    Console.log("[stream-screenshot] attached $streamDescription")
    return true
  }

  /**
   * See [StreamFrameMonitor.awaitFrameMatching]; [treeCapturedAtMs] is on the platform's tree
   * clock (device epoch on Android, host epoch on iOS/web). Returns
   * [StreamFrameMonitor.Result.Unavailable] — caller falls back to an on-device screenshot —
   * when the source is closed, the feed has ended, or the monitor can't match a frame.
   */
  suspend fun awaitFrameMatching(
    treeCapturedAtMs: Long?,
    timeoutMs: Long,
  ): StreamFrameMonitor.Result {
    if (closed.get()) return StreamFrameMonitor.Result.Unavailable("source closed")
    if (feedEnded) return StreamFrameMonitor.Result.Unavailable("$streamDescription ended")
    val frameMonitor = monitor
      ?: return StreamFrameMonitor.Result.Unavailable("source not started")
    return frameMonitor.awaitFrameMatching(
      treeCapturedAtMs = treeCapturedAtMs,
      timeoutMs = timeoutMs,
    )
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { feed?.close() }
    feed = null
    Console.log("[stream-screenshot] detached $streamDescription")
  }

  companion object {

    /** Matches the live-viewer tee parameters so both consumers share one screenrecord. */
    private const val ANDROID_STREAM_BIT_RATE = "4000000"

    /**
     * Android: attach to the same per-device screenrecord tee the `/devices` viewer uses
     * (same size/bitrate → one shared `screenrecord`). Android trees are stamped on the
     * *device* clock, so [start] measures `deviceEpoch - hostEpoch` once via `adb shell date`.
     * screenrecord is damage-driven — a static screen emits no frames — so the drain loop's
     * liveness pings keep the gate from misreading quiet as a dead stream.
     */
    fun forAndroid(
      deviceId: TrailblazeDeviceId,
      deviceWidth: Int,
      deviceHeight: Int,
    ): DeviceStreamScreenshotSource = DeviceStreamScreenshotSource(
      streamDescription = "Android screenrecord stream for ${deviceId.instanceId}",
      treeClockOffsetMs = {
        DeviceClock.nowMs(deviceId.instanceId) - System.currentTimeMillis()
      },
      attachFeed = { monitor, _ ->
        val tee = H264Tee.forDevice(
          deviceId = deviceId,
          videoSize = AndroidVideoCapture.scaleToRecordingSize(deviceWidth, deviceHeight),
          bitRate = ANDROID_STREAM_BIT_RATE,
        )
        LiveFrameConsumer(
          tee = tee,
          onFrame = monitor::recordFrame,
          onFeedAlive = monitor::recordFeedAlive,
        ).also { it.start() }
          .let { consumer -> AutoCloseable { consumer.stop() } }
      },
    )

    /**
     * iOS: the baguette WebSocket ([streamIosLiveH264AccessUnits]) feeds Annex-B bytes into a
     * [BlockingByteStream]-backed standalone [H264Tee] so the Android ffmpeg decode path is
     * reused verbatim. Declines (start() == false) when baguette isn't installed — same
     * fallback the `/devices` viewer uses. iOS trees are captured host-side, so the tree
     * clock IS the host clock (offset 0). baguette encodes continuously (~60 fps even for a
     * static screen), so frames alone prove liveness; the perceptual change detector is what
     * lets that continuous re-encode go content-quiet.
     */
    fun forIos(deviceId: TrailblazeDeviceId): DeviceStreamScreenshotSource = DeviceStreamScreenshotSource(
      streamDescription = "iOS baguette stream for ${deviceId.instanceId}",
      treeClockOffsetMs = { 0L },
      attachFeed = { monitor, onFeedEnded ->
        val feed = IosBaguetteTeeFeed.open(deviceId, onFeedEnded)
        if (feed == null) {
          null
        } else {
          // No onFeedAlive: baguette encodes continuously, so frames themselves prove liveness.
          // An idle-loop ping would keep the gate alive through a wedged upstream producer
          // (WebSocket open but silent) and let it accept a stale frame instead of falling back.
          val consumer = LiveFrameConsumer(
            tee = feed.tee,
            onFrame = monitor::recordFrame,
          )
          try {
            consumer.start()
          } catch (e: Exception) {
            runCatching { feed.close() }
            throw e
          }
          AutoCloseable {
            runCatching { consumer.stop() }
            runCatching { feed.close() }
          }
        }
      },
    )

    /**
     * Web: subscribe to the same CDP screencast the `/devices` viewer streams
     * ([PlaywrightDeviceScreenStream.streamScreencastJpegFrames]). Frames arrive as JPEG —
     * no decode. Chrome's screencast is damage-driven (no frames for a static page), so the
     * pump loop's liveness pings fill the same role as Android's drain-loop pings. Web trees
     * are captured host-side → offset 0.
     */
    fun forWeb(
      deviceId: TrailblazeDeviceId,
      stream: PlaywrightDeviceScreenStream,
    ): DeviceStreamScreenshotSource = DeviceStreamScreenshotSource(
      streamDescription = "web CDP screencast for ${deviceId.instanceId}",
      treeClockOffsetMs = { 0L },
      attachFeed = { monitor, onFeedEnded ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
          try {
            stream.streamScreencastJpegFrames(
              onPumpAlive = monitor::recordFeedAlive,
            ) { jpegBytes ->
              // Every screencast frame is damage-driven; the monitor's perceptual detector
              // confirms whether the damage is a real content change.
              monitor.recordFrame(jpegBytes, isContentChange = true)
            }
          } catch (_: Exception) {
            // Screencast teardown (page closed, CDP session gone) — handled below.
          } finally {
            onFeedEnded()
          }
        }
        AutoCloseable { scope.cancel() }
      },
    )
  }
}

package xyz.block.trailblaze.host.recording

import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.LiveFrameConsumer
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Android feed adapter for [StreamFrameMonitor]: serves screenshots for host-side screen-state
 * captures from the device's live screenrecord stream instead of a per-capture on-device
 * `UiAutomation.takeScreenshot`.
 *
 * Attaches a [LiveFrameConsumer] to the same per-device [H264Tee] the `/devices` live viewer
 * uses (same size/bitrate parameters, so the two share one `screenrecord` invocation) and
 * forwards each emitted frame — with the consumer's own change-vs-heartbeat classification —
 * into the monitor. All matching logic (dual-quiet + timestamp, see [StreamScreenshotGate])
 * lives in the platform-neutral monitor; the Android-specific parts here are the tee
 * attachment and the tree-clock offset: Android trees are stamped on the *device* clock, so
 * [start] measures `deviceEpoch - hostEpoch` once via `adb shell date` ([DeviceClock]).
 *
 * Lifecycle: [start] attaches (spawning `screenrecord` if this is the tee's first consumer);
 * [close] detaches (reaping it if last). One instance per (agent, device) session.
 */
class StreamScreenshotSource(
  private val deviceId: TrailblazeDeviceId,
  private val deviceWidth: Int,
  private val deviceHeight: Int,
) : AutoCloseable {

  private var monitor: StreamFrameMonitor? = null
  private var consumer: LiveFrameConsumer? = null
  private val started = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)

  /** Measured `deviceEpoch - hostEpoch` (ms), so `hostMs + offset ≈ deviceMs`. */
  var deviceClockOffsetMs: Long = 0L
    private set

  /**
   * Idempotent. Measures the device clock offset (one `adb shell date` round-trip) and
   * attaches the JPEG decoder to the shared tee. Callers should invoke this lazily off the
   * first capture rather than at connect time so sessions that never read a screenshot don't
   * hold a `screenrecord` open.
   */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    deviceClockOffsetMs = DeviceClock.nowMs(deviceId.instanceId) - System.currentTimeMillis()
    val frameMonitor = StreamFrameMonitor(treeClockOffsetMs = deviceClockOffsetMs)
    monitor = frameMonitor
    val tee = H264Tee.forDevice(
      deviceId = deviceId,
      videoSize = AndroidVideoCapture.scaleToRecordingSize(deviceWidth, deviceHeight),
      bitRate = STREAM_BIT_RATE,
    )
    val liveConsumer = LiveFrameConsumer(
      tee = tee,
      onFrame = frameMonitor::recordFrame,
      // screenrecord is damage-driven: a static screen emits no frames at all, so the drain
      // loop's liveness pings are what keep the gate from misreading quiet as a dead stream.
      onFeedAlive = frameMonitor::recordFeedAlive,
    )
    consumer = liveConsumer
    liveConsumer.start()
    Console.log(
      "[stream-screenshot] attached to ${deviceId.instanceId} " +
        "(deviceClockOffsetMs=$deviceClockOffsetMs)",
    )
  }

  /** See [StreamFrameMonitor.awaitFrameMatching]; [treeCapturedAtDeviceMs] is device-epoch. */
  suspend fun awaitFrameMatching(
    treeCapturedAtDeviceMs: Long?,
    timeoutMs: Long,
  ): StreamFrameMonitor.Result {
    if (closed.get()) return StreamFrameMonitor.Result.Unavailable("source closed")
    val frameMonitor = monitor
      ?: return StreamFrameMonitor.Result.Unavailable("source not started")
    return frameMonitor.awaitFrameMatching(
      treeCapturedAtMs = treeCapturedAtDeviceMs,
      timeoutMs = timeoutMs,
    )
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { consumer?.stop() }
    consumer = null
    Console.log("[stream-screenshot] detached from ${deviceId.instanceId}")
  }

  private companion object {
    /** Matches the live-viewer tee parameters so both consumers share one screenrecord. */
    const val STREAM_BIT_RATE = "4000000"
  }
}

package xyz.block.trailblaze.capture.video

import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge between [WebScreencastVideoCapture] (in this module) and the Playwright browser manager
 * (in `trailblaze-playwright`) — the web/Electron analog of [PlaywrightVideoRecordDir], but for
 * the *screencast* recording path rather than Playwright's built-in `setRecordVideoDir`.
 *
 * Needed because the capture-stream lifecycle ([xyz.block.trailblaze.capture.CaptureSession]) runs
 * in `trailblaze-capture`, which can't depend on Playwright, while the live CDP screencast lives
 * on the Playwright manager. The manager publishes a [Feed] here keyed by its device id; the
 * capture stream looks the feed up at [WebScreencastVideoCapture.start] and subscribes to its JPEG
 * frames.
 *
 * A [Feed] is a **single fanned-out screencast**: the manager opens one CDP screencast and every
 * subscriber (today just the session-video recorder; the `/devices` viewer and the
 * stream-sourced screenshot path are candidates to migrate onto it) reads the same frames. That's
 * the web counterpart to Android's `H264Tee` sharing one `screenrecord` — and why the marginal
 * cost of recording is small when a screencast is already running for another consumer.
 *
 * Keyed by device id (a WEB [xyz.block.trailblaze.devices.TrailblazeDeviceId.instanceId]), the same
 * key the rest of the capture pipeline uses, so parallel multi-device runs don't collide. When no
 * feed is registered for a device at [WebScreencastVideoCapture.start] time (e.g. the report-export
 * path, which drives a browser with no live screencast), the recorder falls back to the
 * Playwright-recorder path.
 */
object WebScreencastFeedRegistry {

  /** A single shared screencast a recorder can attach to. Implemented on the Playwright side. */
  interface Feed {
    /**
     * Attaches [onFrame] to the shared screencast. Each composited JPEG frame is delivered with
     * the host-clock timestamp ([System.currentTimeMillis]) at which it was observed — the same
     * clock the session log stamps events on, so the muxed video aligns to the report timeline.
     *
     * Frames are delivered off the Playwright pump thread so a slow subscriber (e.g. a disk write)
     * can't stall the screencast. Returns a handle; closing it detaches this subscriber and, when
     * it's the last one, stops the underlying screencast.
     */
    fun subscribe(onFrame: (jpeg: ByteArray, hostTimestampMs: Long) -> Unit): AutoCloseable
  }

  private val feeds = ConcurrentHashMap<String, Feed>()

  fun register(deviceId: String, feed: Feed) {
    feeds[deviceId] = feed
  }

  /** Removes [feed] for [deviceId] only if it's still the registered instance (identity match). */
  fun unregister(deviceId: String, feed: Feed) {
    feeds.remove(deviceId, feed)
  }

  fun get(deviceId: String): Feed? = feeds[deviceId]
}

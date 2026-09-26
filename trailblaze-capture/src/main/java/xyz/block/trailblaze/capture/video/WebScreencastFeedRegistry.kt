package xyz.block.trailblaze.capture.video

import xyz.block.trailblaze.util.Console

/**
 * Bridge between [WebScreencastVideoCapture] (in this module) and the Playwright browser manager
 * (in `trailblaze-playwright`) — the web/Electron analog of [PlaywrightVideoRecordDir], but for
 * the *screencast* recording path rather than Playwright's built-in `setRecordVideoDir`.
 *
 * Needed because the capture-stream lifecycle ([xyz.block.trailblaze.capture.CaptureSession]) runs
 * in `trailblaze-capture`, which can't depend on Playwright, while the live CDP screencast lives
 * on the Playwright manager. The manager publishes a [Feed] here keyed by its device id; a capture
 * stream either looks the feed up once at [WebScreencastVideoCapture.start] ([get]) or, for a
 * multi-device session's web companion, [watch]es the id and follows whichever feed it has.
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
 * path, which drives a browser with no live screencast), a recorder that has a Playwright-recorder
 * fallback uses it; one that watches waits for the feed instead.
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

  private val lock = Any()
  private val feeds = HashMap<String, Feed>()
  private val watchers = HashMap<String, MutableList<(Feed?) -> Unit>>()

  fun register(deviceId: String, feed: Feed) {
    synchronized(lock) {
      val previous = feeds.put(deviceId, feed)
      if (previous === feed) return
      if (previous != null) {
        // Two live browsers under one id: the older one's close will be ignored (identity match),
        // and anything recording this id now follows the newer one.
        Console.log("[WebScreencastFeedRegistry] a new screencast replaced a live one for deviceId=$deviceId")
      }
      notifyLocked(deviceId, feed)
    }
  }

  /** Removes [feed] for [deviceId] only if it's still the registered instance (identity match). */
  fun unregister(deviceId: String, feed: Feed) {
    synchronized(lock) {
      if (feeds[deviceId] !== feed) return
      feeds.remove(deviceId)
      notifyLocked(deviceId, null)
    }
  }

  fun get(deviceId: String): Feed? = synchronized(lock) { feeds[deviceId] }

  /**
   * Calls [onChange] with the feed registered for [deviceId] now, and again every time that changes
   * — a browser that launches after the watch began, closes, or is relaunched — until the returned
   * handle is closed. For a recorder that follows one device for a whole session and must never
   * fall back to Playwright's own recorder (see [WebScreencastVideoCapture]).
   *
   * Callbacks run under the registry's lock, so a watcher sees changes in the order they happened
   * and never a stale feed after a newer one. A callback must not call back into this registry.
   */
  fun watch(deviceId: String, onChange: (Feed?) -> Unit): AutoCloseable {
    synchronized(lock) {
      watchers.getOrPut(deviceId) { mutableListOf() }.add(onChange)
      deliver(deviceId, onChange, feeds[deviceId])
    }
    return AutoCloseable {
      synchronized(lock) {
        val forDevice = watchers[deviceId] ?: return@AutoCloseable
        forDevice.remove(onChange)
        if (forDevice.isEmpty()) watchers.remove(deviceId)
      }
    }
  }

  private fun notifyLocked(deviceId: String, feed: Feed?) {
    watchers[deviceId]?.toList()?.forEach { deliver(deviceId, it, feed) }
  }

  /** A watcher that throws must not fail the browser launch or close that registered the change. */
  private fun deliver(deviceId: String, onChange: (Feed?) -> Unit, feed: Feed?) {
    runCatching { onChange(feed) }.onFailure {
      Console.log("[WebScreencastFeedRegistry] a watcher of deviceId=$deviceId threw: $it")
    }
  }
}

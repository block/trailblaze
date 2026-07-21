package xyz.block.trailblaze.host.recording.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.recording.DeviceScreenStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the set of devices that the HTTP recording API has connected to. Each entry maps
 * a [TrailblazeDeviceId] to its live [DeviceScreenStream]. The stream is created by
 * [xyz.block.trailblaze.host.recording.DeviceConnectionService.connectToDevice] and held
 * here so that subsequent [GetHostDeviceScreenHandler] and [DeviceInteractionHandler] calls
 * can reach the already-running connection without reconnecting.
 *
 * Ownership: sessions created through [connectIfAbsent] are owned by this manager ([remove]
 * closes them). Sessions published through [attach] are owned by the caller — this manager
 * never closes them, whichever removal path drops the entry.
 *
 * Thread-safety:
 * - [get], [isConnected]: plain [ConcurrentHashMap] reads — no lock needed.
 * - [connectIfAbsent]: per-device [Mutex] prevents two concurrent connect calls from both
 *   racing past the "already connected?" check. Its final publication is atomic with [attach],
 *   which can publish an externally-owned stream while physical connection setup is suspended.
 * - [remove]: removes from the map and closes the stream if this manager owns it.
 */
class HostDeviceSessionManager {

  private class Session(val stream: DeviceScreenStream, val externallyOwned: Boolean)

  private val sessions = ConcurrentHashMap<TrailblazeDeviceId, Session>()
  private val connectMutexes = ConcurrentHashMap<TrailblazeDeviceId, Mutex>()

  fun get(deviceId: TrailblazeDeviceId): DeviceScreenStream? = sessions[deviceId]?.stream

  /**
   * Returns the existing stream for [deviceId] if one is already connected, otherwise
   * calls [connect] to produce a new stream, stores it, and returns it. The check-then-act is
   * protected by a per-device [Mutex] so only one connect attempt can run at a time for any given
   * device. If [attach] publishes first while [connect] is suspended, its stream wins and the
   * unused candidate is closed.
   */
  suspend fun connectIfAbsent(
    deviceId: TrailblazeDeviceId,
    connect: suspend () -> DeviceScreenStream?,
  ): DeviceScreenStream? {
    val mutex = connectMutexes.getOrPut(deviceId) { Mutex() }
    return mutex.withLock {
      sessions[deviceId]?.stream ?: run {
        val candidate = connect() ?: return@withLock sessions[deviceId]?.stream
        val winner = sessions.putIfAbsent(deviceId, Session(candidate, externallyOwned = false))
        if (winner == null) {
          candidate
        } else {
          if (candidate !== winner.stream) (candidate as? AutoCloseable)?.close()
          winner.stream
        }
      }
    }
  }

  /**
   * Publishes an already-open, **externally-owned** [stream] for [deviceId] so the streaming and
   * screen-poll handlers can reach it, without this manager taking over its lifecycle. Used by
   * Trail Runner's recorder, which holds the connection (and its interaction tool factory) in its
   * own registry and closes it itself — see [detach].
   *
   * If a session is already registered for [deviceId] (e.g. a viewer-owned one from
   * [connectIfAbsent]) this is a no-op: clobbering it would leak the displaced stream, and the
   * existing one serves the same device's pixels anyway. That also makes re-attaching on every
   * recorder connect safe, which is how the recorder self-heals after a viewer-side [remove].
   */
  fun attach(deviceId: TrailblazeDeviceId, stream: DeviceScreenStream) {
    sessions.putIfAbsent(deviceId, Session(stream, externallyOwned = true))
  }

  /**
   * Removes [deviceId]'s **externally-owned** entry ([attach]) from the registry **without**
   * closing its stream — the caller owns the lifecycle. A manager-owned session ([connectIfAbsent])
   * in the same slot is left untouched: it belongs to the viewer path, not the detaching caller.
   */
  fun detach(deviceId: TrailblazeDeviceId) {
    sessions.computeIfPresent(deviceId) { _, session -> if (session.externallyOwned) null else session }
  }

  /**
   * Drops [deviceId] from the registry, closing the stream only when this manager owns it (the
   * [connectIfAbsent] path). An externally-owned entry is removed without closing — its owner
   * (Trail Runner's recorder) keeps using it and re-publishes via [attach] on its next connect.
   */
  fun remove(deviceId: TrailblazeDeviceId) {
    val session = sessions.remove(deviceId) ?: return
    if (!session.externallyOwned) (session.stream as? AutoCloseable)?.close()
  }

  fun isConnected(deviceId: TrailblazeDeviceId): Boolean = sessions.containsKey(deviceId)
}

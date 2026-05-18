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
 * Thread-safety:
 * - [get], [isConnected]: plain [ConcurrentHashMap] reads — no lock needed.
 * - [connectIfAbsent]: per-device [Mutex] prevents two concurrent connect calls from both
 *   racing past the "already connected?" check and spinning up duplicate streams.
 * - [remove]: removes from the map and closes the stream if it implements [AutoCloseable].
 */
class HostDeviceSessionManager {

  private val sessions = ConcurrentHashMap<TrailblazeDeviceId, DeviceScreenStream>()
  private val connectMutexes = ConcurrentHashMap<TrailblazeDeviceId, Mutex>()

  fun get(deviceId: TrailblazeDeviceId): DeviceScreenStream? = sessions[deviceId]

  /**
   * Returns the existing stream for [deviceId] if one is already connected, otherwise
   * calls [connect] to produce a new stream, stores it, and returns it. The check-then-act
   * is protected by a per-device [Mutex] so only one connect attempt can run at a time for
   * any given device.
   */
  suspend fun connectIfAbsent(
    deviceId: TrailblazeDeviceId,
    connect: suspend () -> DeviceScreenStream?,
  ): DeviceScreenStream? {
    val mutex = connectMutexes.getOrPut(deviceId) { Mutex() }
    return mutex.withLock {
      sessions[deviceId] ?: connect()?.also { sessions[deviceId] = it }
    }
  }

  fun remove(deviceId: TrailblazeDeviceId) {
    val stream = sessions.remove(deviceId)
    (stream as? AutoCloseable)?.close()
  }

  fun isConnected(deviceId: TrailblazeDeviceId): Boolean = sessions.containsKey(deviceId)
}

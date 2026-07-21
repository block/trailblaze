package xyz.block.trailblaze.host

import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/** Owns one long-lived RPC client per device so daemon commands reuse the same connection. */
internal class OnDeviceRpcClientPool<T : AutoCloseable>(
  private val createClient: (TrailblazeDeviceId) -> T,
) : AutoCloseable {

  private val clients = ConcurrentHashMap<TrailblazeDeviceId, T>()

  fun get(deviceId: TrailblazeDeviceId): T =
    clients.computeIfAbsent(deviceId, createClient)

  fun evict(deviceId: TrailblazeDeviceId) {
    clients.remove(deviceId)?.close()
  }

  override fun close() {
    val snapshot = clients.values.toSet()
    clients.clear()
    snapshot.forEach { it.close() }
  }
}

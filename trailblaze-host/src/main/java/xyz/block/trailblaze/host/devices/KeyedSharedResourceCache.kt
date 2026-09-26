package xyz.block.trailblaze.host.devices

import java.util.concurrent.ConcurrentHashMap

/**
 * A cache of independently leased resources.
 *
 * Work for the same [K] is serialized, while different keys may initialize concurrently. Replacing
 * one key force-closes only that key's resource; resources held under other keys keep their leases
 * and remain reusable.
 *
 * Every caller gets [lease]'s handle over the resource, whose close releases only that caller's
 * hold on it (see [SharedLease]).
 */
internal class KeyedSharedResourceCache<K : Any, V : Any, R : Any, H>(
  private val closeResource: (key: K, variant: V, resource: R) -> Unit,
  private val lease: (resource: R, hold: AutoCloseable) -> H,
) {
  private class Entry<V : Any, R : Any>(
    val variant: V,
    val resource: R,
    val lease: SharedLease,
  )

  /** One key's state. Also the lock that serializes work for that key. */
  private class Slot<V : Any, R : Any> {
    var entry: Entry<V, R>? = null
    var lastBuiltVariant: V? = null
  }

  private val slots = ConcurrentHashMap<K, Slot<V, R>>()

  /**
   * A handle on the resource cached under [key] if it was built for [variant], is still open, and
   * passes [isReusable]; otherwise force-closes whatever is cached there and caches what [create]
   * builds. [create] is given the variant of the last resource built for [key], even one since
   * closed, or null if this is the first build for [key] in this cache's lifetime.
   */
  fun acquireOrCreate(
    key: K,
    variant: V,
    isReusable: (R) -> Boolean,
    create: (lastBuiltVariant: V?) -> R,
  ): H {
    val slot = slots.computeIfAbsent(key) { Slot() }
    synchronized(slot) {
      slot.entry?.let { existing ->
        if (existing.variant == variant && isReusable(existing.resource)) {
          existing.lease.acquire()?.let { hold -> return lease(existing.resource, hold) }
        }
        slot.entry = null
        existing.lease.closeNow()
      }

      val resource = create(slot.lastBuiltVariant)
      val replacement = Entry(variant, resource, SharedLease { closeResource(key, variant, resource) })
      slot.entry = replacement
      slot.lastBuiltVariant = variant
      val hold = checkNotNull(replacement.lease.acquire()) {
        "A freshly cached resource refused its first lease"
      }
      return lease(resource, hold)
    }
  }

  /**
   * Force-closes what is cached under [key] if its variant passes [ifVariant], so its next acquire
   * builds afresh. The check runs under [key]'s lock: a caller that decided to evict before taking
   * it may find the key has since replaced the resource it meant, and must leave the new one alone.
   */
  fun evict(key: K, ifVariant: (V) -> Boolean) {
    val slot = slots[key] ?: return
    synchronized(slot) {
      val existing = slot.entry?.takeIf { ifVariant(it.variant) } ?: return
      slot.entry = null
      existing.lease.closeNow()
    }
  }
}

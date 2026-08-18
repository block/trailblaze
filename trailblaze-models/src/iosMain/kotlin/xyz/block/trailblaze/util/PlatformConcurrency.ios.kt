package xyz.block.trailblaze.util

import platform.Foundation.NSLock

/**
 * Kotlin/Native runs on a genuinely multi-threaded runtime, so the Wasm actual's plain
 * `LinkedHashMap` would be a data race here rather than a shortcut. These actuals guard a
 * `LinkedHashMap` / `LinkedHashSet` with an [NSLock], which satisfies the contract the expect
 * declares: every single-key read and write is atomic with respect to every other one.
 *
 * Two deliberate differences from `ConcurrentHashMap`, both within the expect's documented
 * guarantees:
 *
 * - **The views ([keys]/[values]/[entries], and [MutableSet.iterator]) are snapshots, not live
 *   views.** Iterating one is therefore safe while another thread writes — closer to
 *   `ConcurrentHashMap`'s weakly-consistent iterators than to `LinkedHashMap`'s fail-fast ones —
 *   but mutating a view does NOT write through to the map. Callers mutate through the map/set
 *   API. Nothing in this repo mutates through a view.
 * - **Compound operations are not atomic.** `if (!containsKey(k)) put(k, v)` can interleave, same
 *   as on `ConcurrentHashMap` without its `putIfAbsent`, which this seam doesn't expose.
 *
 * Equality IS structural, matching every other actual: these seams hand back standard collections
 * elsewhere, so `AgentMemory.variables == mapOf(...)` holds, and a wrapper that inherited identity
 * equality from `Any` would quietly make that comparison false on this one platform. Both
 * comparison and hashing run against a snapshot taken under the lock rather than holding the lock
 * across the comparison, so comparing two of these collections can't deadlock on lock ordering.
 *
 * [NSLock] rather than a lock-free structure because there is no multiplatform concurrent-map in
 * the stdlib and this seam's contention is trivial: `AgentMemory` holds tens of keys.
 */
internal actual fun <K, V> concurrentMutableMap(): MutableMap<K, V> = LockGuardedMutableMap()

internal actual fun <E> concurrentMutableSet(): MutableSet<E> = LockGuardedMutableSet()

private inline fun <T> NSLock.guarding(body: () -> T): T {
  lock()
  try {
    return body()
  } finally {
    unlock()
  }
}

private class LockGuardedMutableMap<K, V> : MutableMap<K, V> {
  private val delegate = LinkedHashMap<K, V>()
  private val lock = NSLock()

  override val size: Int get() = lock.guarding { delegate.size }
  override fun isEmpty(): Boolean = lock.guarding { delegate.isEmpty() }
  override fun containsKey(key: K): Boolean = lock.guarding { delegate.containsKey(key) }
  override fun containsValue(value: V): Boolean = lock.guarding { delegate.containsValue(value) }
  override fun get(key: K): V? = lock.guarding { delegate[key] }
  override fun put(key: K, value: V): V? = lock.guarding { delegate.put(key, value) }
  override fun putAll(from: Map<out K, V>) = lock.guarding { delegate.putAll(from) }
  override fun remove(key: K): V? = lock.guarding { delegate.remove(key) }
  override fun clear() = lock.guarding { delegate.clear() }

  override val keys: MutableSet<K>
    get() = lock.guarding { LinkedHashSet(delegate.keys) }

  override val values: MutableCollection<V>
    get() = lock.guarding { ArrayList(delegate.values) }

  // The entries of a COPY, so they carry the stdlib's structural entry equality and a caller's
  // `setValue` lands on the copy instead of reaching the delegate outside the lock.
  override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
    get() = snapshot().entries

  override fun equals(other: Any?): Boolean = snapshot() == other
  override fun hashCode(): Int = snapshot().hashCode()
  override fun toString(): String = snapshot().toString()

  private fun snapshot(): LinkedHashMap<K, V> = lock.guarding { LinkedHashMap(delegate) }
}

private class LockGuardedMutableSet<E> : MutableSet<E> {
  private val delegate = LinkedHashSet<E>()
  private val lock = NSLock()

  override val size: Int get() = lock.guarding { delegate.size }
  override fun isEmpty(): Boolean = lock.guarding { delegate.isEmpty() }
  override fun contains(element: E): Boolean = lock.guarding { delegate.contains(element) }
  override fun containsAll(elements: Collection<E>): Boolean = lock.guarding { delegate.containsAll(elements) }
  override fun add(element: E): Boolean = lock.guarding { delegate.add(element) }
  override fun addAll(elements: Collection<E>): Boolean = lock.guarding { delegate.addAll(elements) }
  override fun remove(element: E): Boolean = lock.guarding { delegate.remove(element) }
  override fun removeAll(elements: Collection<E>): Boolean = lock.guarding { delegate.removeAll(elements) }
  override fun retainAll(elements: Collection<E>): Boolean = lock.guarding { delegate.retainAll(elements) }
  override fun clear() = lock.guarding { delegate.clear() }

  override fun iterator(): MutableIterator<E> = snapshot().iterator()

  override fun equals(other: Any?): Boolean = snapshot() == other
  override fun hashCode(): Int = snapshot().hashCode()
  override fun toString(): String = snapshot().toString()

  private fun snapshot(): LinkedHashSet<E> = lock.guarding { LinkedHashSet(delegate) }
}

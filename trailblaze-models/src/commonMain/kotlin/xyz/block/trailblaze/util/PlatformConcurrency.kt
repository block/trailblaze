package xyz.block.trailblaze.util

/**
 * A [MutableMap] safe for concurrent single-key reads and writes.
 *
 * That safety is the CONTRACT every actual must honor — it is what
 * [xyz.block.trailblaze.AgentMemory] documents and relies on — not an accident of the
 * current platforms:
 *
 * - JVM/Android: `java.util.concurrent.ConcurrentHashMap`.
 * - Wasm/JS: a plain `LinkedHashMap` — that satisfies the contract only because the runtime
 *   is single-threaded, so this is NOT the template for other non-JVM targets.
 * - iOS (Kotlin/Native): a lock-guarded `LinkedHashMap`, because that runtime IS
 *   multi-threaded. Its views are snapshots rather than live views; see
 *   `PlatformConcurrency.ios.kt`.
 *
 * Like `ConcurrentHashMap`, callers must not store null keys or values. Iteration guarantees
 * are the weakest across the actuals: `ConcurrentHashMap` iterators are weakly consistent
 * while `LinkedHashMap`'s fail fast on mutation, so callers must not iterate concurrently
 * with writes. Mutate through the map itself, not through a view — the iOS actual's views
 * don't write through.
 */
internal expect fun <K, V> concurrentMutableMap(): MutableMap<K, V>

/**
 * A [MutableSet] safe for concurrent adds and membership checks — the same contract and
 * platform split as [concurrentMutableMap] (`ConcurrentHashMap.newKeySet()` on JVM/Android;
 * `LinkedHashSet` on the single-threaded Wasm/JS runtime; a lock-guarded `LinkedHashSet` on
 * the multi-threaded Native runtime).
 *
 * The same view caveat applies, and here it covers [iterator]: on Native it walks a snapshot, so
 * it neither observes adds made after it was obtained nor removes through to the set. Equality is
 * structural on every actual.
 */
internal expect fun <E> concurrentMutableSet(): MutableSet<E>

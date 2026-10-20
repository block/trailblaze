package xyz.block.trailblaze.history

/** The Wasm runtime is single-threaded, so nothing else can hold the lock. */
internal actual inline fun <T> withHistoryLock(lock: Any, block: () -> T): T = block()

package xyz.block.trailblaze.history

internal actual inline fun <T> withHistoryLock(lock: Any, block: () -> T): T = synchronized(lock, block)

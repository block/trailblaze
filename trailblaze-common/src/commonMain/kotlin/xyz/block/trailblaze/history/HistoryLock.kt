package xyz.block.trailblaze.history

/** Runs [block] holding [lock]'s monitor, for [ToolCallHistory]'s state. */
internal expect inline fun <T> withHistoryLock(lock: Any, block: () -> T): T

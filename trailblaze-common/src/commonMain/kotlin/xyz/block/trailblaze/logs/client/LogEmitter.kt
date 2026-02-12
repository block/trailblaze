package xyz.block.trailblaze.logs.client

/**
 * Interface for emitting log events.
 * Implementations handle delivery to different backends (server, disk, console, etc.)
 *
 * ## Contract
 * - Implementations should handle errors gracefully and not throw exceptions
 * - If an error occurs, log it but continue processing
 * - Thread-safety is the responsibility of the implementation
 *
 * ## Usage
 * ```kotlin
 * val emitter = LogEmitter { log ->
 *     // Send to server, write to disk, etc.
 *     println("Log: ${log::class.simpleName}")
 * }
 * ```
 */
fun interface LogEmitter {
  /**
   * Emit a log event.
   * Implementations should handle errors gracefully and not throw exceptions.
   *
   * @param log The log event to emit
   */
  fun emit(log: TrailblazeLog)
}

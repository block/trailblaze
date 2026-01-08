package xyz.block.trailblaze.logs.client
/**
 * No-op emitter for testing or when logging is disabled.
 *
 * ## Usage
 * ```kotlin
 * val logger = TrailblazeLogger(
 *     logEmitter = NoOpLogEmitter,
 *     screenStateLogger = ...
 * )
 * ```
 */
object NoOpLogEmitter : LogEmitter {
  override fun emit(log: TrailblazeLog) {
    // Do nothing
  }
}

package xyz.block.trailblaze.logs.client

/**
 * Provides access to the current session for logging operations.
 * 
 * This is a functional interface that allows components to obtain the current
 * session without directly depending on session management infrastructure.
 * 
 * ## Usage
 * ```kotlin
 * class MyComponent(
 *     private val logger: TrailblazeLogger,
 *     private val sessionProvider: TrailblazeSessionProvider
 * ) {
 *     fun logSomething() {
 *         val session = sessionProvider.invoke()
 *         logger.log(session, myLog)
 *     }
 * }
 * ```
 */
fun interface TrailblazeSessionProvider {
  /**
   * Returns the current session. Must always return a valid session.
   */
  fun invoke(): TrailblazeSession
}

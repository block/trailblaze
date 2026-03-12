package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager
import xyz.block.trailblaze.util.Console
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handler for subscribing to real-time progress events via MCP.
 *
 * Manages subscriptions to progress events for a specific session or all sessions.
 * Uses a callback-based pattern that allows clients to receive events as they occur.
 *
 * ## Subscription Lifecycle
 *
 * 1. Client calls [subscribe] to register a listener callback
 * 2. Handler returns a unique subscription ID for tracking
 * 3. Client calls [unsubscribe] with the subscription ID to stop receiving events
 * 4. Handler cleans up resources associated with the subscription
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = SubscribeToProgressHandler(
 *   progressManager = progressManager,
 *   coroutineScope = scope
 * )
 *
 * // Subscribe to progress events for a session
 * val subscriptionId = handler.subscribe(
 *   sessionId = "my_session_id",
 *   listener = { event ->
 *     Console.log("Progress: $event")
 *   }
 * )
 *
 * // Later, unsubscribe
 * handler.unsubscribe(subscriptionId)
 * ```
 *
 * @param progressManager The progress session manager providing the event stream
 * @param coroutineScope The scope for launching subscription collection jobs
 */
class SubscribeToProgressHandler(
  private val progressManager: ProgressSessionManager,
  private val coroutineScope: CoroutineScope,
) {

  /** Tracks active subscriptions by ID */
  private val activeSubscriptions =
    ConcurrentHashMap<String, ProgressSubscription>()

  /**
   * Registers a listener for progress events on a specific session.
   *
   * The listener callback will be invoked each time a new progress event
   * is published for the session. The subscription runs in a background coroutine.
   *
   * @param sessionId The session ID to subscribe to
   * @param listener Callback to invoke with each progress event
   * @return A unique subscription ID that can be used to unsubscribe later
   */
  fun subscribe(
    sessionId: String,
    listener: (TrailblazeProgressEvent) -> Unit,
  ): String {
    val subscriptionId = UUID.randomUUID().toString()
    val sid = SessionId(sessionId)

    // Create a background job to collect events from the progress stream
    val job = coroutineScope.launch {
      try {
        progressManager.progressEvents.collect { event ->
          // Only forward events for the subscribed session
          if (event.sessionId == sid) {
            try {
              listener(event)
            } catch (e: Exception) {
              Console.error("[SubscribeToProgressHandler] Listener error for subscription $subscriptionId: ${e.message}")
            }
          }
        }
      } catch (e: Exception) {
        Console.error("[SubscribeToProgressHandler] Collection failed for subscription $subscriptionId: ${e.message}")
        activeSubscriptions.remove(subscriptionId)
      }
    }

    // Store subscription metadata
    val subscription = ProgressSubscription(
      subscriptionId = subscriptionId,
      sessionId = sessionId,
      job = job,
      listener = listener,
    )
    activeSubscriptions[subscriptionId] = subscription

    Console.log("[SubscribeToProgressHandler] Subscription created: $subscriptionId for session: $sessionId")

    return subscriptionId
  }

  /**
   * Unsubscribes from progress events.
   *
   * Cancels the background collection job and removes the subscription metadata.
   *
   * @param subscriptionId The subscription ID returned from [subscribe]
   */
  fun unsubscribe(subscriptionId: String) {
    val subscription = activeSubscriptions.remove(subscriptionId)
    if (subscription != null) {
      // Cancel the collection job
      subscription.job.cancel("Subscription ${subscription.subscriptionId} unsubscribed")
      Console.log("[SubscribeToProgressHandler] Subscription cancelled: $subscriptionId")
    }
  }

  /**
   * Gets the current list of active subscriptions.
   *
   * Useful for monitoring which sessions are being listened to.
   *
   * @return List of active subscription IDs
   */
  fun getActiveSubscriptions(): List<String> {
    return activeSubscriptions.keys.toList()
  }

  /**
   * Gets details about a specific subscription.
   *
   * @param subscriptionId The subscription ID
   * @return Subscription details, or null if not found
   */
  fun getSubscription(subscriptionId: String): ProgressSubscription? {
    return activeSubscriptions[subscriptionId]
  }

  /**
   * Cancels all active subscriptions.
   *
   * Useful for cleanup when the handler is being shut down.
   */
  fun cancelAll() {
    activeSubscriptions.forEach { (id, subscription) ->
      subscription.job.cancel("Handler shutting down")
    }
    activeSubscriptions.clear()
    Console.log("[SubscribeToProgressHandler] All subscriptions cancelled")
  }

  /**
   * Metadata for an active progress subscription.
   *
   * @param subscriptionId Unique identifier for this subscription
   * @param sessionId The session being listened to
   * @param job The background job collecting events
   * @param listener The callback to invoke with each event
   */
  data class ProgressSubscription(
    val subscriptionId: String,
    val sessionId: String,
    val job: Job,
    val listener: (TrailblazeProgressEvent) -> Unit,
  )
}

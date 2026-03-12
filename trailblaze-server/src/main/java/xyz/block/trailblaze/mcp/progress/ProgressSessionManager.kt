package xyz.block.trailblaze.mcp.progress

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import xyz.block.trailblaze.agent.ExecutionState
import xyz.block.trailblaze.agent.ExecutionStatus
import xyz.block.trailblaze.agent.ExecutionStatusBuilder
import xyz.block.trailblaze.agent.ProgressEventListener
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages progress events and execution status across sessions.
 *
 * This class serves as the central hub for:
 * - Receiving progress events from running executions
 * - Storing event history for late subscribers
 * - Providing execution status snapshots
 * - Broadcasting events to multiple subscribers
 *
 * ## Thread Safety
 *
 * This class is thread-safe and can be accessed from multiple coroutines.
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = ProgressSessionManager()
 *
 * // In your executor:
 * manager.onProgressEvent(event)
 *
 * // In your handler:
 * val events = manager.getEventsForSession(sessionId)
 * val status = manager.getExecutionStatus(sessionId)
 *
 * // For real-time streaming:
 * manager.progressEvents.collect { event ->
 *   sendToClient(event)
 * }
 * ```
 */
class ProgressSessionManager(
  /** Maximum number of events to retain per session */
  private val maxEventsPerSession: Int = 1000,
  /** Maximum age of completed sessions to retain (ms) */
  private val completedSessionRetentionMs: Long = 5 * 60 * 1000L, // 5 minutes
) : ProgressEventListener {

  /** Event history per session */
  private val sessionEvents = ConcurrentHashMap<SessionId, MutableList<TrailblazeProgressEvent>>()

  /** Execution status builders per session */
  private val statusBuilders = ConcurrentHashMap<SessionId, ExecutionStatusBuilder>()

  /** Timestamps when sessions completed (for cleanup) */
  private val completedSessionTimes = ConcurrentHashMap<SessionId, Long>()

  /** Broadcast flow for real-time event streaming */
  private val _progressEvents = MutableSharedFlow<TrailblazeProgressEvent>(
    replay = 10, // Keep last 10 events for late subscribers
    extraBufferCapacity = 100,
  )

  /** Flow of progress events for subscribers */
  val progressEvents: SharedFlow<TrailblazeProgressEvent> = _progressEvents.asSharedFlow()

  /**
   * Called when a progress event occurs.
   * Stores the event and broadcasts it to subscribers.
   */
  override fun onProgressEvent(event: TrailblazeProgressEvent) {
    val sessionId = event.sessionId

    // Store event in session history.
    // Use compute() to atomically get-or-create the list AND add the event in one step.
    // This prevents a race where removeSession() could delete the list between
    // getOrPut() and the synchronized block.
    sessionEvents.compute(sessionId) { _, existing ->
      val events = existing ?: mutableListOf()
      events.add(event)
      while (events.size > maxEventsPerSession) {
        events.removeAt(0)
      }
      events
    }

    // Update execution status based on event type
    updateStatusFromEvent(event)

    // Broadcast to subscribers
    _progressEvents.tryEmit(event)

    // Cleanup old completed sessions
    cleanupOldSessions()
  }

  /**
   * Gets all events for a session.
   *
   * @param sessionId The session ID
   * @param afterTimestamp Only return events after this timestamp (for pagination)
   * @return List of events, or empty list if session not found
   */
  fun getEventsForSession(
    sessionId: SessionId,
    afterTimestamp: Long? = null,
  ): List<TrailblazeProgressEvent> {
    // Take a snapshot inside compute() to guarantee we don't iterate a list
    // that's being mutated by a concurrent onProgressEvent() call.
    var snapshot: List<TrailblazeProgressEvent> = emptyList()
    sessionEvents.computeIfPresent(sessionId) { _, events ->
      snapshot = events.toList()
      events
    }
    return if (afterTimestamp != null) {
      snapshot.filter { it.timestamp > afterTimestamp }
    } else {
      snapshot
    }
  }

  /**
   * Gets the current execution status for a session.
   *
   * @param sessionId The session ID
   * @return ExecutionStatus or null if session not found
   */
  fun getExecutionStatus(sessionId: SessionId): ExecutionStatus? {
    return statusBuilders[sessionId]?.build(System.currentTimeMillis())
  }

  /**
   * Gets all active session statuses.
   *
   * @param includeCompleted Include recently completed sessions
   * @return List of execution statuses
   */
  fun getAllSessionStatuses(includeCompleted: Boolean = false): List<ExecutionStatus> {
    val currentTime = System.currentTimeMillis()
    return statusBuilders.mapNotNull { (sessionId, builder) ->
      val status = builder.build(currentTime)
      when {
        status.state == ExecutionState.RUNNING ||
          status.state == ExecutionState.PAUSED ||
          status.state == ExecutionState.NOT_STARTED -> status

        includeCompleted && completedSessionTimes[sessionId]?.let {
          currentTime - it < completedSessionRetentionMs
        } == true -> status

        else -> null
      }
    }
  }

  /**
   * Registers a new session for tracking.
   * Called automatically when ExecutionStarted event is received,
   * but can be called manually to pre-register a session.
   */
  fun registerSession(sessionId: SessionId, objective: String, agentImplementation: AgentImplementation) {
    statusBuilders.getOrPut(sessionId) {
      ExecutionStatusBuilder(sessionId, objective, agentImplementation).apply {
        setStartTime(System.currentTimeMillis())
      }
    }
  }

  /**
   * Removes a session from tracking.
   * Useful for manual cleanup.
   */
  fun removeSession(sessionId: SessionId) {
    sessionEvents.remove(sessionId)
    statusBuilders.remove(sessionId)
    completedSessionTimes.remove(sessionId)
  }

  /**
   * Clears all session data.
   * Use with caution - mainly for testing.
   */
  fun clear() {
    sessionEvents.clear()
    statusBuilders.clear()
    completedSessionTimes.clear()
  }

  private fun updateStatusFromEvent(event: TrailblazeProgressEvent) {
    when (event) {
      is TrailblazeProgressEvent.ExecutionStarted -> {
        val builder = statusBuilders.getOrPut(event.sessionId) {
          ExecutionStatusBuilder(event.sessionId, event.objective, event.agentImplementation)
        }
        builder.setState(ExecutionState.RUNNING)
        builder.setStartTime(event.timestamp)
      }

      is TrailblazeProgressEvent.ExecutionCompleted -> {
        statusBuilders[event.sessionId]?.apply {
          setState(if (event.success) ExecutionState.COMPLETED else ExecutionState.FAILED)
          if (event.errorMessage != null) {
            setError(event.errorMessage!!)
          }
        }
        completedSessionTimes[event.sessionId] = event.timestamp
      }

      is TrailblazeProgressEvent.StepStarted -> {
        statusBuilders[event.sessionId]?.apply {
          setCurrentStep(event.stepPrompt)
          setTotalSteps(event.totalSteps)
        }
      }

      is TrailblazeProgressEvent.StepCompleted -> {
        statusBuilders[event.sessionId]?.apply {
          setCompletedSteps(event.stepIndex + 1)
          incrementActions()
        }
      }

      is TrailblazeProgressEvent.SubtaskProgress -> {
        statusBuilders[event.sessionId]?.apply {
          setCurrentStep(event.subtaskName)
          setTotalSteps(event.totalSubtasks)
          setCompletedSteps(event.subtaskIndex)
        }
      }

      is TrailblazeProgressEvent.SubtaskCompleted -> {
        statusBuilders[event.sessionId]?.apply {
          setCompletedSteps(event.subtaskIndex + 1)
        }
      }

      is TrailblazeProgressEvent.ExceptionHandled -> {
        statusBuilders[event.sessionId]?.incrementExceptionsHandled()
      }

      is TrailblazeProgressEvent.FactStored -> {
        statusBuilders[event.sessionId]?.apply {
          // Increment fact count - we track this manually since we don't have
          // the actual memory state here
          val status = build(System.currentTimeMillis())
          setMemoryFactCount(status.memoryFactCount + 1)
        }
      }

      // These events don't update status
      is TrailblazeProgressEvent.ReflectionTriggered,
      is TrailblazeProgressEvent.BacktrackPerformed,
      is TrailblazeProgressEvent.TaskReplanned,
      is TrailblazeProgressEvent.FactRecalled -> {
        // No status update needed
      }
    }
  }

  private fun cleanupOldSessions() {
    val currentTime = System.currentTimeMillis()
    val toRemove = completedSessionTimes.filter { (_, completedTime) ->
      currentTime - completedTime > completedSessionRetentionMs
    }.keys

    toRemove.forEach { sessionId ->
      removeSession(sessionId)
    }
  }
}

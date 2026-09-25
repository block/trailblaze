package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Writes a [TrailblazeLog.TrailblazeToolCatalogLog] the first time a session offers a given
 * toolset, so the descriptors cost one log instead of one copy per LLM request.
 *
 * Used by both producers of a [TrailblazeLog.TrailblazeLlmRequestLog] — the agent runner through
 * [TrailblazeLogger], and MCP sampling, which writes its logs straight to disk instead of through
 * a [LogEmitter]. Each keeps its own instance, so a session both write to can hold two copies of a
 * catalog; readers key catalogs by id, and the id is a content hash, so the copies resolve alike.
 */
class TrailblazeToolCatalogEmitter(
  private val maxRetainedSessions: Int = DEFAULT_MAX_RETAINED_SESSIONS,
) {

  private val lock = Any()

  /**
   * Catalog ids already written, per session. Keyed by session because one emitter serves many,
   * and a catalog id is only meaningful next to the session whose log directory holds it.
   *
   * Bounded and least-recently-used: a process can outlive any number of sessions (the daemon
   * keeps one emitter for its lifetime) and nothing here gets a session-ended signal — MCP
   * sampling has none to give. Eviction is safe rather than merely tolerable: an evicted session
   * that keeps going re-writes a catalog it already wrote, and readers key catalogs by id, so the
   * duplicate resolves to the same descriptors.
   *
   * Access-ordered, so a long-running session is not evicted by a burst of short ones. That makes
   * reads structural mutations, which is part of why every path below holds [lock].
   */
  private val catalogIdsBySession = object : LinkedHashMap<String, MutableSet<String>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>): Boolean =
      size > maxRetainedSessions
  }

  /**
   * Returns the id of the catalog holding [toolOptions], writing it via [emit] if [sessionId] has
   * not written it yet.
   *
   * Null when [toolOptions] is empty — a request that was offered no tools has nothing to
   * catalog, and writing an empty one would add a log per session that resolves to exactly what
   * the absent id already means.
   *
   * Claiming and writing happen under one lock on purpose. If the claim were released first, a
   * second thread offering the same toolset would see "already written", and could persist its
   * request log before the winner had written the catalog — leaving a request pointing at a
   * catalog that appears later in the session, or not at all if the process dies between the two.
   * Callers write their request log after this returns, so the lock never covers that.
   */
  fun emitIfNew(
    sessionId: SessionId,
    toolOptions: List<TrailblazeToolDescriptor>,
    timestamp: Instant,
    emit: (TrailblazeLog.TrailblazeToolCatalogLog) -> Unit,
  ): String? {
    if (toolOptions.isEmpty()) return null
    val catalogId = TrailblazeToolCatalog.idFor(toolOptions)
    synchronized(lock) {
      val alreadyWritten = catalogIdsBySession.getOrPut(sessionId.value) { mutableSetOf() }
      if (alreadyWritten.add(catalogId)) {
        try {
          emit(
            TrailblazeLog.TrailblazeToolCatalogLog(
              toolCatalogId = catalogId,
              toolOptions = toolOptions,
              session = sessionId,
              timestamp = timestamp,
            ),
          )
        } catch (t: Throwable) {
          // A claim only counts once the catalog is actually on disk. Keeping it after a failed
          // write means every later request in the session points at a catalog nobody wrote and
          // resolves no tools — and the MCP sampling producer reaches exactly that: its error
          // path re-logs the same request, which would then skip the catalog as "already done".
          alreadyWritten.remove(catalogId)
          throw t
        }
      }
    }
    return catalogId
  }

  /**
   * Releases [catalogId]'s claim for [sessionId], so the next request offering it writes it again.
   *
   * For a sink that cannot throw but can still miss: an upload that failed and fell back to the
   * device's disk leaves the catalog somewhere the requests after it are not. The duplicate a
   * re-write produces is harmless for the same reason eviction is.
   *
   * Takes [lock], so call it from the thread that is emitting — a sink that hands the call to
   * another thread while [emitIfNew] waits on it deadlocks.
   */
  fun forget(sessionId: SessionId, catalogId: String) {
    synchronized(lock) { catalogIdsBySession[sessionId.value]?.remove(catalogId) }
  }

  /** How many sessions this emitter is currently remembering. For tests and diagnostics. */
  fun retainedSessionCount(): Int = synchronized(lock) { catalogIdsBySession.size }

  companion object {
    /**
     * Far more than any one process runs concurrently, and still only a few tens of KB — the
     * bound exists to stop unbounded growth, not to be tight.
     */
    const val DEFAULT_MAX_RETAINED_SESSIONS: Int = 512
  }
}

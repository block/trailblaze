package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStartFields
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * The trail each session in this process runs, kept from its `Started` log until its `Ended` one, so
 * something running mid-trail, such as a survey through `ctx.surveys`, sees the trail it is in (id,
 * title, metadata, app version) the way a reader of the finished session does.
 *
 * Fed by every place a session's status log is written: [TrailblazeLogger.log],
 * [TrailblazeSessionManager], and on the host `LogsRepo.saveLogToDisk`. A device that runs tools for
 * a host-driven session gets the host's fields through [record], since the host writes that
 * session's start and end. At most [MAX_SESSIONS] are held, the oldest going first, so a session
 * whose end is never seen here does not stay for the life of the process.
 */
object SessionStartRecords {
  private const val MAX_SESSIONS = 64

  private val started = object : LinkedHashMap<SessionId, SessionStartFields>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionId, SessionStartFields>?): Boolean = size > MAX_SESSIONS
  }

  fun observe(log: TrailblazeLog) {
    if (log !is TrailblazeLog.TrailblazeSessionStatusChangeLog) return
    when (val status = log.sessionStatus) {
      is SessionStatus.Started -> record(log.session, SessionStartFields.of(status))
      is SessionStatus.Ended -> synchronized(started) { started.remove(log.session) }
      else -> Unit
    }
  }

  fun record(sessionId: SessionId, fields: SessionStartFields) {
    val visible = fields.visible()
    synchronized(started) { started[sessionId] = visible }
  }

  fun forSession(sessionId: SessionId): SessionStartFields? = synchronized(started) { started[sessionId] }
}

package xyz.block.trailblaze.logs.client

import java.security.MessageDigest

/**
 * Builds a file name that a long session id cannot make unwritable.
 *
 * Session ids are deliberately untruncated — they carry a test's full
 * `__suite__section__case` identity — and real ones already run past 220 bytes. Past [NAME_MAX]
 * a host filesystem rejects the write with ENAMETOOLONG, and Android's MediaStore layer silently
 * renames the file instead, which breaks the name-to-content mapping readers depend on. Neither
 * failure is loud: the on-device writers swallow the exception and log a line.
 *
 * Only the on-disk name shortens. The full session id stays in the log's own `session` field, so
 * nothing that keys off the session id is affected.
 */
object BoundedLogFileName {

  /** `NAME_MAX` per POSIX / APFS / ext4 — the per-component file-name byte limit. */
  const val NAME_MAX: Int = 255

  /**
   * `<sessionId><suffix>`, or `<sessionHash8><suffix>` when that would not fit.
   *
   * [suffix] is never shortened: it is what makes the name unique within a session, so callers
   * must keep it short enough to leave room for a hashed prefix.
   */
  fun of(sessionId: String, suffix: String): String {
    val candidate = sessionId + suffix
    if (candidate.toByteArray(Charsets.UTF_8).size <= NAME_MAX) return candidate
    return sessionHash8(sessionId) + suffix
  }

  private fun sessionHash8(sessionId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(sessionId.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(8)
}

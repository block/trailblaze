package xyz.block.trailblaze.cli

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console

/**
 * Turns a user-typed session id or prefix into the sessions it names.
 *
 * Exact match wins before prefix match. Session ids are timestamps, so one id is routinely a
 * prefix of a longer one recorded later the same minute — matching on prefix alone reports the
 * exactly-typed id as ambiguous and refuses to run.
 */
internal object SessionIdResolver {

  /** Null when the id matched nothing or more than one session; the reason is already printed. */
  fun resolve(allIds: List<SessionId>, requested: String): List<SessionId>? {
    allIds.firstOrNull { it.value == requested }?.let { return listOf(it) }
    val matches = allIds.filter { it.value.startsWith(requested) }
    return when (matches.size) {
      0 -> {
        Console.error("Error: No session matching '$requested' found.")
        null
      }

      1 -> matches

      else -> {
        Console.error("Error: Session prefix '$requested' is ambiguous: ${matches.joinToString(", ") { it.value }}")
        null
      }
    }
  }
}

package xyz.block.trailblaze.trailhead

import java.util.concurrent.CopyOnWriteArrayList
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console

/**
 * Reasons a session's setup must never be handed to the next trail, whatever `teardown` says.
 * An integration that holds a session-scoped resource the session finalizer releases (a leased
 * persona, say) registers a veto: a reusable hand-off would give the next trail a signed-in app
 * whose account is already back in the pool.
 */
object TeardownReuseVetoes {

  /** Returns why [SessionId]'s setup can't be reused, or null when it can. */
  fun interface Veto {
    fun reason(sessionId: SessionId): String?
  }

  private val vetoes = CopyOnWriteArrayList<Veto>()

  fun register(veto: Veto): AutoCloseable {
    vetoes += veto
    return AutoCloseable { vetoes -= veto }
  }

  /** The first veto's reason, or null. A veto that throws counts as a veto. */
  fun vetoReason(sessionId: SessionId): String? {
    for (veto in vetoes) {
      val reason = try {
        veto.reason(sessionId)
      } catch (e: Exception) {
        Console.error("[Teardown] reuse veto threw ${e::class.simpleName}: ${e.message}")
        "a reuse check failed (${e::class.simpleName})"
      }
      if (reason != null) return reason
    }
    return null
  }
}

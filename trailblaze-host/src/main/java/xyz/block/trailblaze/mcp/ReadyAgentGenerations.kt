package xyz.block.trailblaze.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * A count per device of the times its on-device agent became ready, so a verdict that a runner
 * stopped serving can only clear the attachment it was reached about.
 *
 * The verdict comes from an adb probe, and a slow one can return after another call has already
 * forgotten and relaunched the runner. Clearing then would drop the new attachment — failing its
 * next RPC and costing another relaunch. Read [current] before probing, and forget through
 * [forgetIfStill] with what it returned.
 */
internal class ReadyAgentGenerations {

  private val generations = ConcurrentHashMap<String, Long>()

  fun current(key: String): Long = generations[key] ?: 0L

  /** Runs [markReady] and moves [key] on a generation, so verdicts read before it no longer apply. */
  fun becameReady(key: String, markReady: () -> Unit) {
    synchronized(this) {
      markReady()
      generations.merge(key, 1L, Long::plus)
    }
  }

  /** Runs [forget] only when [key] has not become ready since [checked] was read; returns whether it ran. */
  fun forgetIfStill(key: String, checked: Long, forget: () -> Unit): Boolean =
    synchronized(this) {
      if (current(key) != checked) return false
      forget()
      true
    }
}

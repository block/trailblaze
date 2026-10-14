package xyz.block.trailblaze.logs.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Keeps one MCP session's tool calls and a background rebuild of its scripted-tool runtime apart:
 * a [rebuild] starts only when no [call] is running, and a [call] that arrives during a rebuild
 * waits for it. Both sides claim the gate in one atomic step, so neither can start in the gap after
 * the other checked — a rebuild never shuts down a runtime a call is still using.
 *
 * A rebuild waits for a moment with no calls rather than holding new calls back while it waits, so
 * a long agent call delays the rebuild, not the session's other calls — and a call nested inside a
 * running call never meets a rebuild.
 */
internal class RuntimeRebuildGate {

  private data class State(val calls: Int = 0, val rebuilding: Boolean = false)

  private val state = MutableStateFlow(State())

  suspend fun <T> call(block: suspend () -> T): T {
    while (true) {
      val current = state.first { !it.rebuilding }
      if (state.compareAndSet(current, current.copy(calls = current.calls + 1))) break
    }
    try {
      return block()
    } finally {
      state.update { it.copy(calls = it.calls - 1) }
    }
  }

  suspend fun <T> rebuild(block: suspend () -> T): T {
    while (true) {
      val current = state.first { it.calls == 0 && !it.rebuilding }
      if (state.compareAndSet(current, current.copy(rebuilding = true))) break
    }
    try {
      return block()
    } finally {
      state.update { it.copy(rebuilding = false) }
    }
  }
}

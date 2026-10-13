package xyz.block.trailblaze.android.test

import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.util.Console

/**
 * Builds the process-wide registries a run needs on a background thread, while the test thread is
 * still waiting for the app.
 *
 * In-process, the instrumentation starts inside the app's own process, and before a test body
 * runs the test thread mostly waits: the Compose rule waits for the app's main thread to go idle,
 * then the launch waits for the first Activity. Those waits are spent on the app's startup, which
 * is main-thread work. The registries below are CPU work on the test thread, done afterwards
 * (the tool-class registry when the RPC server installs its JSON, the toolset catalog when the
 * first session builds its tool repo), and together they cost seconds on an emulator. Starting
 * them here overlaps them with the waits instead.
 *
 * Every task is a cached, thread-safe lazy, so this changes WHEN they are built and never what
 * they contain. A task that fails here is only logged: the real caller builds it again on first
 * use and gets the same failure where it can report it.
 */
internal class StartupWarmup(
  private val tasks: List<Pair<String, () -> Unit>>,
  private val startThread: (Runnable) -> Unit = { work ->
    Thread(work, "trailblaze-startup-warmup").apply { isDaemon = true }.start()
  },
) {
  private val started = AtomicBoolean(false)

  /** Starts the tasks once, in order, on one background thread. Later calls do nothing. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    startThread {
      tasks.forEach { (name, task) ->
        runCatching(task).onFailure { e ->
          Console.log("[startup-warmup] $name failed in the background; its first caller will rebuild it: $e")
        }
      }
    }
  }

  companion object {
    /** The registries every in-process run builds before its first tool. */
    val process = StartupWarmup(
      tasks = listOf(
        "tool-class registry" to { TrailblazeJsonInstance },
        "toolset catalog" to { TrailblazeToolSetCatalog.defaultEntries() },
      ),
    )
  }
}

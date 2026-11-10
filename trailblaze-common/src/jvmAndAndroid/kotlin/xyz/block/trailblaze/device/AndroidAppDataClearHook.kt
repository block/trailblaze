package xyz.block.trailblaze.device

import java.util.concurrent.CopyOnWriteArrayList
import xyz.block.trailblaze.util.Console

/**
 * Work that must follow every on-device `pm clear`, whichever tool or driver ran it.
 *
 * `pm clear` wipes an app's private storage, including files a test harness placed there for the
 * app to read at startup. A component that keeps such a file in place for the length of a run
 * registers a [Listener] and restores it here, after the clear and before the app can start again.
 * That covers `mobile_clearAppData`, a driver's own clear-and-launch, and any launch helper that
 * clears, without each having to know about the file.
 *
 * Only the on-device clear calls [notifyCleared]. A host run clears through adb and nothing
 * registers, so there it does nothing.
 */
object AndroidAppDataClearHook {

  fun interface Listener {
    /** Runs right after [appId]'s data was cleared. Must not start the app. */
    fun afterClear(appId: String)
  }

  private val listeners = CopyOnWriteArrayList<Listener>()

  fun add(listener: Listener) {
    listeners += listener
  }

  fun remove(listener: Listener) {
    listeners -= listener
  }

  /**
   * Runs every listener for [appId]. A listener that throws is logged and skipped: the clear
   * already happened, and what follows it must not turn a successful clear into a failure.
   */
  fun notifyCleared(appId: String) {
    for (listener in listeners) {
      runCatching { listener.afterClear(appId) }
        .onFailure { Console.log("[pm clear] after-clear listener failed for $appId: ${it.message}") }
    }
  }
}

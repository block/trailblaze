package xyz.block.trailblaze.ui.desktoputil

import kotlin.system.exitProcess

/**
 * Centralized app exit utility. All UI-initiated app exits should go through this object so that
 * future cleanup logic (flushing settings, closing connections, etc.) can be added in one place.
 */
object ExitApp {

  /** Exits the application with the given status code. */
  fun quit(status: Int = 0) {
    exitProcess(status)
  }
}

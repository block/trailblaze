package xyz.block.trailblaze.ui

import xyz.block.trailblaze.util.Console
import java.io.File

internal const val DESKTOP_GUI_READY_FILE_ENV_VAR = "TRAILBLAZE_DESKTOP_GUI_READY_FILE"

/** Signals the parent launcher after this process has installed its Compose window callback. */
internal fun signalDesktopGuiReady(environment: Map<String, String> = System.getenv()) {
  val markerPath = environment[DESKTOP_GUI_READY_FILE_ENV_VAR]?.takeIf { it.isNotBlank() } ?: return
  runCatching { File(markerPath).createNewFile() }
    .onFailure { error ->
      Console.error("Could not signal desktop GUI readiness: ${error.message}")
    }
}

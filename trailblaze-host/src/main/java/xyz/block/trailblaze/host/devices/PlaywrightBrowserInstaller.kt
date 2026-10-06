package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.block.trailblaze.playwright.PlaywrightDriverManager
import xyz.block.trailblaze.util.Console
import java.io.File

sealed class PlaywrightInstallState {
  data object Unknown : PlaywrightInstallState()
  data object Checking : PlaywrightInstallState()
  data object NotInstalled : PlaywrightInstallState()

  /** Browser download in progress. [progressPercent] is 0–100, [statusMessage] is human-readable. */
  data class Installing(
    val progressPercent: Int = 0,
    val statusMessage: String = "Preparing...",
  ) : PlaywrightInstallState()

  data object Installed : PlaywrightInstallState()
  data class Error(val message: String) : PlaywrightInstallState()
}

class PlaywrightBrowserInstaller {
  private val _installState = MutableStateFlow<PlaywrightInstallState>(PlaywrightInstallState.Unknown)
  val installState: StateFlow<PlaywrightInstallState> = _installState

  private val scope = CoroutineScope(Dispatchers.IO)

  fun checkInstallStatus() {
    scope.launch {
      try {
        _installState.value = PlaywrightInstallState.Checking
        Console.log("Checking Playwright browser installation status...")

        val cacheDir = getPlaywrightCacheDir()
        val isInstalled = isChromiumInstalled(cacheDir)

        if (isInstalled) {
          _installState.value = PlaywrightInstallState.Installed
          Console.log("Playwright browsers are already installed")
        } else {
          _installState.value = PlaywrightInstallState.NotInstalled
          Console.log("Playwright browsers not found, installation required")
        }
      } catch (e: Exception) {
        val errorMessage = "Failed to check Playwright installation: ${e.message}"
        Console.log(errorMessage)
        _installState.value = PlaywrightInstallState.Error(errorMessage)
      }
    }
  }

  private fun getPlaywrightCacheDir(): String {
    return PlaywrightDriverManager.getPlaywrightBrowsersCacheDir().absolutePath + File.separator
  }

  /**
   * Pushes an in-progress install state from an external installer (e.g. when
   * [PlaywrightDriverManager.ensureBrowserInstalled] triggers download during
   * [PlaywrightBrowserManager] init). Updates the [installState] flow.
   */
  fun reportInstallProgress(progressPercent: Int, statusMessage: String) {
    _installState.value = PlaywrightInstallState.Installing(
      progressPercent = progressPercent,
      statusMessage = statusMessage,
    )
  }

  /** Marks the install as complete from an external installer. */
  fun reportInstallComplete() {
    _installState.value = PlaywrightInstallState.Installed
  }

  /** Reports an install failure from an external installer so the UI doesn't appear stuck. */
  fun reportInstallError(message: String) {
    _installState.value = PlaywrightInstallState.Error(message)
  }

  fun close() {
    scope.cancel()
  }

  private fun isChromiumInstalled(cacheDir: String): Boolean {
    val cachePath = File(cacheDir)
    if (!cachePath.exists() || !cachePath.isDirectory) {
      return false
    }

    val dirs = cachePath.listFiles() ?: return false
    // Check for INSTALLATION_COMPLETE marker — Playwright writes this after a successful download.
    // Checking only directory names is insufficient (partial downloads leave empty directories).
    val hasChromium = dirs.any { file ->
      file.isDirectory &&
        file.name.startsWith("chromium-") &&
        File(file, INSTALLATION_COMPLETE_MARKER).exists()
    }
    val hasHeadlessShell = dirs.any { file ->
      file.isDirectory &&
        file.name.startsWith("chromium_headless_shell-") &&
        File(file, INSTALLATION_COMPLETE_MARKER).exists()
    }
    return hasChromium && hasHeadlessShell
  }

  companion object {
    private const val INSTALLATION_COMPLETE_MARKER = "INSTALLATION_COMPLETE"
  }
}

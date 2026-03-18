package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.playwright.PlaywrightDriverManager
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.TimeUnit

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
  private val installMutex = Mutex()

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

  /**
   * Installs Playwright Chromium browser via a subprocess.
   *
   * @param onComplete optional callback invoked (on [Dispatchers.IO]) after a successful install.
   *   Useful for auto-launching the browser once the download finishes.
   */
  fun installBrowsers(onComplete: (() -> Unit)? = null) {
    scope.launch {
      installMutex.withLock {
        val currentState = _installState.value
        if (currentState is PlaywrightInstallState.Installing) {
          Console.log("Browser installation already in progress")
          return@withLock
        }
        if (currentState is PlaywrightInstallState.Installed) {
          Console.log("Browser already installed")
          onComplete?.invoke()
          return@withLock
        }

        try {
          _installState.value = PlaywrightInstallState.Installing()
          Console.log("Starting Playwright browser installation via subprocess...")

          // Run the Playwright CLI in a separate process because CLI.main() calls System.exit()
          // which would kill the entire application.
          val javaHome = System.getProperty("java.home")
          val javaBin = File(javaHome, "bin/java").absolutePath
          val classPath = System.getProperty("java.class.path")

          // Forward Playwright system properties to the subprocess so it finds the driver
          // in the same location (stable cache or externally downloaded).
          val driverTmpdir = System.getProperty("playwright.driver.tmpdir")
          val cliDir = System.getProperty("playwright.cli.dir")
          val process = ProcessBuilder(
            buildList {
              add(javaBin)
              add("-cp")
              add(classPath)
              if (cliDir != null) {
                add("-Dplaywright.cli.dir=$cliDir")
              } else if (driverTmpdir != null) {
                add("-Dplaywright.driver.tmpdir=$driverTmpdir")
              }
              add("com.microsoft.playwright.CLI")
              add("install")
              add("chromium")
            }
          )
            .redirectErrorStream(true)
            .start()

          // Parse output from the subprocess, looking for progress percentage patterns.
          // Playwright CLI outputs lines like "Downloading Chromium 131.0.6778.33 (playwright build v1148)..."
          // and progress lines containing percentages or download sizes.
          process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
              Console.log("[Playwright Install] $line")
              val percent = parseProgressPercent(line)
              if (percent != null) {
                _installState.value = PlaywrightInstallState.Installing(
                  progressPercent = percent,
                  statusMessage = line.trim(),
                )
              } else if (line.contains("Downloading", ignoreCase = true)) {
                _installState.value = PlaywrightInstallState.Installing(
                  progressPercent = 0,
                  statusMessage = line.trim(),
                )
              }
            }
          }

          val finished = process.waitFor(10, TimeUnit.MINUTES)
          if (!finished) {
            process.destroyForcibly()
            val errorMessage = "Playwright install process timed out after 10 minutes"
            Console.log(errorMessage)
            _installState.value = PlaywrightInstallState.Error(errorMessage)
            return@withLock
          }

          val exitCode = process.exitValue()
          if (exitCode == 0) {
            _installState.value = PlaywrightInstallState.Installed
            Console.log("Playwright browser installation completed successfully")
            onComplete?.invoke()
          } else {
            val errorMessage = "Playwright install process exited with code $exitCode"
            Console.log(errorMessage)
            _installState.value = PlaywrightInstallState.Error(errorMessage)
          }
        } catch (e: Exception) {
          val errorMessage = "Failed to install Playwright browsers: ${e.message}"
          Console.log(errorMessage)
          _installState.value = PlaywrightInstallState.Error(errorMessage)
        }
      }
    }
  }

  /**
   * Parses a percentage value (0–100) from a Playwright CLI output line.
   * Matches patterns like "50%", "(50%)", or "50 %" in the output.
   */
  private fun parseProgressPercent(line: String): Int? {
    val match = PROGRESS_REGEX.find(line) ?: return null
    val value = match.groupValues[1].toIntOrNull() ?: return null
    return if (value in 0..100) value else null
  }

  private fun getPlaywrightCacheDir(): String {
    return PlaywrightDriverManager.getPlaywrightBrowsersCacheDir().absolutePath + File.separator
  }

  /**
   * Pushes an in-progress install state from an external installer (e.g. when
   * [PlaywrightDriverManager.ensureBrowserInstalled] triggers download during
   * [PlaywrightBrowserManager] init rather than via [installBrowsers]).
   * Updates the [installState] flow so the UI progress bar reflects the download.
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

    return cachePath.listFiles()?.any { file ->
      file.isDirectory &&
        (file.name.startsWith("chromium-") || file.name.startsWith("chromium_headless_shell-"))
    } ?: false
  }

  companion object {
    private val PROGRESS_REGEX = Regex("""(\d{1,3})\s*%""")
  }
}

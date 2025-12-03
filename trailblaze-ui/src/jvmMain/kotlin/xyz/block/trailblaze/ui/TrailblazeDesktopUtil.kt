package xyz.block.trailblaze.ui

import java.awt.Desktop
import java.awt.Taskbar
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import xyz.block.trailblaze.ui.models.TrailblazeServerState

object TrailblazeDesktopUtil {

  /**
   * The filename for Trailblaze settings.
   */
  const val SETTINGS_FILENAME = "trailblaze-settings.json"

  /**
   * Gets the default app data directory path.
   * @return The default app data directory: ~/.trailblaze
   */
  fun getDefaultAppDataDirectory(): String {
    return "${System.getProperty("user.home")}/.trailblaze"
  }

  /**
   * Gets the settings file in the default app data directory.
   * @return The settings file
   */
  fun getDefaultSettingsFile(): File {
    return File(getDefaultAppDataDirectory(), SETTINGS_FILENAME)
  }

  /**
   * Gets the effective app data directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective app data directory (configured or default)
   */
  fun getEffectiveAppDataDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.appDataDirectory ?: getDefaultAppDataDirectory()
  }

  /**
   * Gets the effective logs directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective logs directory (configured or default relative to app data directory)
   */
  fun getEffectiveLogsDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.logsDirectory ?: "${getEffectiveAppDataDirectory(appConfig)}/logs"
  }

  /**
   * Gets the effective trails directory based on the app config.
   * @param appConfig The current app configuration
   * @return The effective trails directory (configured or default relative to app data directory)
   */
  fun getEffectiveTrailsDirectory(appConfig: TrailblazeServerState.SavedTrailblazeAppConfig): String {
    return appConfig.trailsDirectory ?: "${getEffectiveAppDataDirectory(appConfig)}/trails"
  }
  /**
   * Sets the taskbar icon for macOS.
   *
   * This method sets the icon shown in the macOS Dock and app switcher.
   * It uses the image located at "icons/icon.png" in the classpath.
   */
  fun setAppConfigForTrailblaze() {
    if (Taskbar.isTaskbarSupported()) {
      // This sets the icon shown in the macOS Dock and app switcher

      Taskbar.getTaskbar().apply {
        iconImage = ImageIO.read(TrailblazeDesktopUtil::class.java.classLoader.getResource("icons/icon.png"))
      }
    }
  }

  fun openInDefaultBrowser(url: String) {
    try {
      if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(url))
      } else {
        println("Desktop is not supported on this platform.")
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun openInFileBrowser(file: File) {
    if (file.exists()) {
      Desktop.getDesktop().open(file)
    } else {
      println("File does not exist: ${file.absolutePath}")
    }
  }

  /**
   * Reveals a file in Finder (macOS) or opens its parent directory (other platforms).
   * On macOS, this will open Finder and select the file.
   * On other platforms, it will open the parent directory containing the file.
   */
  fun revealFileInFinder(file: File) {
    if (!file.exists()) {
      println("File does not exist: ${file.absolutePath}")
      return
    }

    try {
      val osName = System.getProperty("os.name").lowercase()
      when {
        osName.contains("mac") -> {
          // macOS: Use 'open -R' to reveal the file in Finder
          Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
        }

        osName.contains("win") -> {
          // Windows: Use explorer /select to highlight the file
          Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
        }

        else -> {
          // Linux and other platforms: Just open the parent directory
          if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file.parentFile)
          }
        }
      }
    } catch (e: Exception) {
      println("Failed to reveal file in Finder: ${e.message}")
      e.printStackTrace()
      // Fallback: just open the parent directory
      try {
        if (Desktop.isDesktopSupported()) {
          Desktop.getDesktop().open(file.parentFile)
        }
      } catch (fallbackException: Exception) {
        println("Fallback also failed: ${fallbackException.message}")
        fallbackException.printStackTrace()
      }
    }
  }

  fun openGoose() {
    val gooseRecipeJson = this::class.java.classLoader.getResource("trailblaze_goose_recipe.json").readText()

    @OptIn(ExperimentalEncodingApi::class)
    val gooseRecipeEncoded = Base64.encode(gooseRecipeJson.toByteArray())
    val gooseUrl = "goose://recipe?config=$gooseRecipeEncoded"
    openInDefaultBrowser(gooseUrl)
  }
}

package xyz.block.trailblaze.ui.desktoputil

import java.awt.Desktop
import java.io.File
import xyz.block.trailblaze.ui.DesktopOsType

/**
 * Desktop utility functions for file operations.
 */
object DesktopUtil {

  /**
   * Returns the user's shell profile file, preferring .zshrc on macOS and .bashrc on other systems.
   * Returns null if the home directory cannot be determined.
   */
  fun getShellProfileFile(): File? {
    val homeDir = System.getProperty("user.home") ?: return null
    val zshrc = File(homeDir, ".zshrc")
    val bashrc = File(homeDir, ".bashrc")

    return when {
      zshrc.exists() -> zshrc
      bashrc.exists() -> bashrc
      // Default to .zshrc on macOS, .bashrc elsewhere
      DesktopOsType.current() == DesktopOsType.MAC_OS -> zshrc
      else -> bashrc
    }
  }

  /**
   * Opens a file or directory in the system file browser.
   */
  fun openInFileBrowser(file: File) {
    if (file.exists()) {
      Desktop.getDesktop().open(file)
    } else {
      println("File does not exist: ${file.absolutePath}")
    }
  }

  /**
   * Reveals a file in Finder (macOS) or opens its parent directory (other platforms). On macOS,
   * this will open Finder and select the file. On other platforms, it will open the parent directory
   * containing the file.
   */
  fun revealFileInFinder(file: File) {
    if (!file.exists()) {
      println("File does not exist: ${file.absolutePath}")
      return
    }

    try {
      when (DesktopOsType.current()) {
        DesktopOsType.MAC_OS -> {
          // macOS: Use 'open -R' to reveal the file in Finder
          Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
        }

        DesktopOsType.WINDOWS -> {
          // Windows: Use explorer /select to highlight the file
          Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
        }

        DesktopOsType.LINUX -> {
          // Linux: Just open the parent directory
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
}

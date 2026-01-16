package xyz.block.trailblaze.ui

import java.awt.Desktop
import java.io.File

/**
 * Desktop utility functions for file operations.
 */
object DesktopUtil {

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
}

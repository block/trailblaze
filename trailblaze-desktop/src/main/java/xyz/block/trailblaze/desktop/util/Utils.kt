package xyz.block.trailblaze.desktop.util

import java.awt.Desktop
import java.io.File
import java.net.URI

object Utils {
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
}

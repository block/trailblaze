package xyz.block.trailblaze.ui

import java.awt.Desktop
import java.awt.Taskbar
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object TrailblazeDesktopUtil {
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

  fun openGoose() {
    val gooseRecipeJson = this::class.java.classLoader.getResource("trailblaze_goose_recipe.json").readText()

    @OptIn(ExperimentalEncodingApi::class)
    val gooseRecipeEncoded = Base64.encode(gooseRecipeJson.toByteArray())
    val gooseUrl = "goose://recipe?config=${gooseRecipeEncoded}"
    openInDefaultBrowser(gooseUrl)
  }
}

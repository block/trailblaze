package xyz.block.trailblaze.trailrunner

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser

/**
 * Opens the host operating system's directory chooser and blocks until the person chooses or
 * cancels. macOS uses [FileDialog], which is backed by the Finder-style native open panel. Other
 * desktop platforms fall back to Swing's directory chooser. The web request invokes this from an
 * IO dispatcher; all AWT/Swing work is marshalled onto the event-dispatch thread.
 */
internal fun pickDirectoryWithNativeDialog(initialDirectory: File?): File? {
  if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    pickDirectoryWithCachedMacShell(initialDirectory)?.let { return it }
    // A successful cancellation is represented separately from an unavailable/stale helper, so
    // check that before considering the in-process fallback below.
    if (cachedMacShellSupportsDirectoryPicking()) return null
  }
  if (GraphicsEnvironment.isHeadless()) {
    error(
      "A desktop folder picker is not available in this environment. " +
        "Run `trailblaze app` from the repository you want to use."
    )
  }
  val result = CompletableFuture<File?>()
  EventQueue.invokeLater {
    runCatching {
      val initial = initialDirectory?.takeIf { it.isDirectory }?.absoluteFile
      if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        pickMacDirectory(initial)
      } else {
        pickSwingDirectory(initial)
      }
    }.fold(result::complete, result::completeExceptionally)
  }
  return result.get()
}

private fun cachedMacShellSupportsDirectoryPicking(): Boolean {
  val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return false
  val dir = File(home, ".trailblaze/bin")
  val source = File(dir, "TrailblazeTrailRunner.swift")
  val binary = File(dir, "Trail Runner")
  return binary.canExecute() && source.isFile && source.lastModified() <= binary.lastModified() &&
    runCatching { source.readText().contains("--pick-directory") }.getOrDefault(false)
}

private fun pickDirectoryWithCachedMacShell(initialDirectory: File?): File? {
  if (!cachedMacShellSupportsDirectoryPicking()) return null
  val binary = File(System.getProperty("user.home"), ".trailblaze/bin/Trail Runner")
  val process = ProcessBuilder(
    listOf(binary.absolutePath, "--pick-directory", initialDirectory?.absolutePath.orEmpty()),
  ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  val selected = process.inputStream.bufferedReader().use { it.readText() }
  val exit = process.waitFor()
  if (exit != 0) error("The native folder picker exited with status $exit")
  if (selected.isBlank()) return null
  return File(selected).takeIf { it.isDirectory }
    ?: error("The native folder picker returned a directory that no longer exists")
}

private fun pickMacDirectory(initialDirectory: File?): File? {
  val property = "apple.awt.fileDialogForDirectories"
  val previous = System.getProperty(property)
  System.setProperty(property, "true")
  val dialog = FileDialog(null as Frame?, "Choose Trail Runner workspace", FileDialog.LOAD)
  return try {
    dialog.directory = initialDirectory?.absolutePath
    dialog.isAlwaysOnTop = true
    dialog.isVisible = true
    val parent = dialog.directory ?: return null
    val name = dialog.file ?: return null
    File(parent, name).takeIf { it.isDirectory }
  } finally {
    dialog.dispose()
    if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
  }
}

private fun pickSwingDirectory(initialDirectory: File?): File? {
  val chooser = JFileChooser(initialDirectory).apply {
    dialogTitle = "Choose Trail Runner workspace"
    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    isAcceptAllFileFilterUsed = false
  }
  return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

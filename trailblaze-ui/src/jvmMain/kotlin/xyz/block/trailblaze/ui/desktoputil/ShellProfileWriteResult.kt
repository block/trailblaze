package xyz.block.trailblaze.ui.desktoputil

import java.io.File

/**
 * Result of attempting to save a line or environment variable to the shell profile.
 */
sealed class ShellProfileWriteResult {
  data class Success(val filePath: String) : ShellProfileWriteResult()

  data class AlreadyPresent(val filePath: String) : ShellProfileWriteResult()

  data class Error(val message: String) : ShellProfileWriteResult()
}

/**
 * Appends an arbitrary line to the user's shell profile (e.g. ~/.zshrc).
 *
 * The function is idempotent: if [dedupMarker] is already present in the file, the write is skipped
 * and [ShellProfileWriteResult.AlreadyPresent] is returned.
 *
 * @param line The full line to append (e.g. an `export` statement).
 * @param shellProfile The shell profile file, typically from [DesktopUtil.getShellProfileFile].
 * @param comment A comment placed above the line for the user's reference.
 * @param dedupMarker A substring that, if already present in the profile, prevents duplicate
 *   writes. Defaults to [line] itself.
 */
fun appendLineToShellProfile(
  line: String,
  shellProfile: File?,
  comment: String = "Added by Trailblaze",
  dedupMarker: String = line,
): ShellProfileWriteResult {
  if (shellProfile == null) {
    return ShellProfileWriteResult.Error("Could not determine shell profile location")
  }
  return try {
    if (!shellProfile.exists()) {
      shellProfile.createNewFile()
    }
    val existingContent = shellProfile.readText()
    if (existingContent.contains(dedupMarker)) {
      return ShellProfileWriteResult.AlreadyPresent(shellProfile.absolutePath)
    }
    shellProfile.appendText("\n# $comment\n$line\n")
    ShellProfileWriteResult.Success(shellProfile.absolutePath)
  } catch (e: Exception) {
    ShellProfileWriteResult.Error("${e.javaClass.simpleName}: ${e.message}")
  }
}

package xyz.block.trailblaze.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File
import xyz.block.trailblaze.ui.DesktopOsType

/**
 * Common note about terminal launch that should be appended to restart dialogs.
 */
const val TERMINAL_LAUNCH_NOTE = "Note: If you launched Trailblaze from a terminal, you'll also need to open a new terminal window for it to pick up the environment variable changes."

/**
 * Alert dialog prompting the user to restart the application after saving credentials to shell profile.
 * Includes a note about terminal launches requiring a new terminal window.
 */
@Composable
fun ShellProfileRestartRequiredDialog(
  title: String,
  message: String,
  onDismiss: () -> Unit,
  onQuit: () -> Unit,
) {
  val fullMessage = "$message\n\n$TERMINAL_LAUNCH_NOTE"

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(fullMessage) },
    confirmButton = {
      Button(onClick = onQuit) {
        Text("Quit Trailblaze")
      }
    },
    dismissButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.outlinedButtonColors(),
      ) {
        Text("Later")
      }
    },
  )
}

/**
 * Result of attempting to save an environment variable to the shell profile.
 */
sealed class EnvVarSaveResult {
  data class Success(val filePath: String) : EnvVarSaveResult()
  data class Error(val message: String) : EnvVarSaveResult()
}

/**
 * Saves an environment variable to the user's shell profile by appending an export statement.
 *
 * @param variableName The name of the environment variable (e.g., "OPENAI_API_KEY")
 * @param value The value to set
 * @param shellProfile The shell profile file to write to (e.g., ~/.zshrc)
 * @param comment Optional comment to add above the export statement
 * @return [EnvVarSaveResult.Success] if saved successfully, [EnvVarSaveResult.Error] otherwise
 */
fun saveEnvVarToShellProfile(
  variableName: String,
  value: String,
  shellProfile: File?,
  comment: String = "Added by Trailblaze Desktop",
): EnvVarSaveResult {
  if (value.isBlank()) {
    return EnvVarSaveResult.Error("Value cannot be empty")
  }

  if (shellProfile == null) {
    return EnvVarSaveResult.Error("Could not determine shell profile location")
  }

  return try {
    // Create the file if it doesn't exist
    if (!shellProfile.exists()) {
      shellProfile.createNewFile()
    }

    // Check if the variable is already set in the file
    val existingContent = shellProfile.readText()
    if (existingContent.contains("export $variableName=")) {
      return EnvVarSaveResult.Error(
        "$variableName is already defined in ${shellProfile.name}. " +
          "Please edit the file manually to update it."
      )
    }

    // Append the export statement
    val exportLine = "\n# $comment\nexport $variableName=\"$value\"\n"
    shellProfile.appendText(exportLine)

    EnvVarSaveResult.Success(shellProfile.absolutePath)
  } catch (e: Exception) {
    EnvVarSaveResult.Error("${e.javaClass.simpleName}: ${e.message}")
  }
}

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

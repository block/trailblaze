package xyz.block.trailblaze.ui.desktoputil

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Common note about terminal launch that should be appended to restart dialogs.
 */
const val TERMINAL_LAUNCH_NOTE =
  "Note: If you launched Trailblaze from a terminal, you'll also need to open a new terminal window for it to pick up the environment variable changes."

/**
 * Alert dialog prompting the user to restart the application after saving credentials to shell
 * profile. Includes a note about terminal launches requiring a new terminal window.
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
    confirmButton = { Button(onClick = onQuit) { Text("Quit Trailblaze") } },
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

@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.editors.yaml

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Editor mode for the YAML tab - either text-based or visual editor.
 */
enum class YamlEditorMode {
  TEXT,
  VISUAL
}

/**
 * Sub-view mode within the visual editor - Configuration or Steps.
 */
enum class YamlVisualEditorView {
  CONFIG,
  STEPS
}

/**
 * Validates YAML content.
 * This does a "soft" validation that checks YAML syntax but doesn't fail on unknown tools
 * since the actual runner will have app-specific custom tools registered.
 */
fun validateYaml(content: String): String? {
  if (content.isBlank()) {
    return null // Empty is not an error, just disable the button
  }

  // Check for common indentation issues before parsing
  val indentationError = checkIndentationIssues(content)
  if (indentationError != null) {
    return indentationError
  }

  return try {
    // Try to parse with default tools
    val trailblazeYaml = TrailblazeYaml.Default
    trailblazeYaml.decodeTrail(content)
    null // No error
  } catch (e: Exception) {
    val message = e.message ?: "Invalid YAML format"
    // Only ignore errors that are specifically about unknown/unregistered tools
    // The exact error format is: "TrailblazeYaml could not TrailblazeTool found with name: X. Did you register it?"
    val isUnknownToolError = message.contains("TrailblazeYaml could not TrailblazeTool found with name:")

    if (isUnknownToolError) {
      null // Allow unknown tools - they'll be registered when the test runs
    } else {
      // Return all other errors including syntax, structure issues
      message
    }
  }
}

/**
 * Check for common indentation problems that the YAML parser might not catch.
 */
private fun checkIndentationIssues(yaml: String): String? {
  val lines = yaml.lines()
  var inRecording = false
  var recordingIndent = 0

  for ((index, line) in lines.withIndex()) {
    if (line.isBlank()) continue

    val trimmed = line.trim()
    val indent = line.takeWhile { it == ' ' }.length

    // Detect when we enter a recording block
    if (trimmed == "recording:") {
      inRecording = true
      recordingIndent = indent
      continue
    }

    // Check for misaligned "tools:" within recording
    if (inRecording && trimmed == "tools:") {
      val expectedIndent = recordingIndent + 2
      if (indent != expectedIndent && indent != recordingIndent + 4) {
        return "Line ${index + 1}: 'tools:' has incorrect indentation ($indent spaces). " +
            "Expected $expectedIndent spaces to align with 'recording' block."
      }
    }

    // Exit recording block when we de-indent
    if (inRecording && indent <= recordingIndent) {
      inRecording = false
    }
  }

  return null
}

/**
 * Dialog for confirming deletion of an item.
 */
@Composable
fun DeleteConfirmationDialog(
  itemType: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        Icons.Filled.Delete,
        contentDescription = "Delete",
        tint = MaterialTheme.colorScheme.error
      )
    },
    title = {
      Text("Delete $itemType?")
    },
    text = {
      Text("Are you sure you want to delete this $itemType? This action cannot be undone.")
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error
        )
      ) {
        Text("Delete")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

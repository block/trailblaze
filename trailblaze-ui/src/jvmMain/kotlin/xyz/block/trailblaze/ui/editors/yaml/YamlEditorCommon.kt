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
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Validates YAML content for the editor's Run/Save gate. A "soft" validation: it checks that the
 * file parses, but does NOT fail on unknown/app-specific tools — the decoder tolerates those (an
 * unregistered tool name decodes into a pass-through OtherTrailblazeTool), and the runner registers
 * the real tool at run time. Returns null when valid, or an error message to surface otherwise.
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
    // Version-aware parse — validates both legacy v1 (list) and unified (mapping) shapes. Uses
    // decodeTrailDocument, NOT decodeTrail: the latter throws for a unified file that carries
    // recordings when no device classifiers are supplied (it would silently drop them at run time),
    // which is a runtime guard, not a syntax error. Validation only cares that the file parses.
    val trailblazeYaml = createTrailblazeYaml()
    trailblazeYaml.decodeTrailDocument(content)
    null // No error
  } catch (e: Throwable) {
    // Unknown tools don't reach here (the decoder tolerates them), so a throw is a genuine
    // syntax/structure problem worth surfacing. Catch Throwable — decodeTrailDocument can throw a
    // non-Exception (e.g. from a require/check in the schema).
    e.message ?: "Invalid YAML format"
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

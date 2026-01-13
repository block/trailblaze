@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.tabs.sessions.editor.CodeEditorTextField
import xyz.block.trailblaze.ui.tabs.sessions.editor.TextEditHistory
import xyz.block.trailblaze.ui.tabs.sessions.editor.YamlSyntaxHighlighter
import xyz.block.trailblaze.ui.tabs.sessions.editor.rememberTextEditHistory
import xyz.block.trailblaze.ui.tabs.sessions.editor.YamlFormatter

/**
 * Text editor content for the YAML tab.
 * Provides a code editor with YAML syntax highlighting, proper indentation,
 * and undo/redo support.
 */
@Composable
fun YamlTextEditor(
  yamlContent: String,
  onYamlContentChange: (String) -> Unit,
  isRunning: Boolean,
  validationError: String?,
  isValidating: Boolean,
  modifier: Modifier = Modifier,
) {
  // Track the text field value internally - don't use yamlContent as remember key
  // because that would reset cursor position on every keystroke
  var textFieldValue by remember {
    mutableStateOf(
      TextFieldValue(
        text = yamlContent,
        selection = TextRange(yamlContent.length),
      ),
    )
  }

  // Only sync when external content changes (e.g., loading a file)
  // We detect this by checking if the incoming yamlContent differs from our current text
  // AND we didn't cause the change ourselves (tracked via a flag)
  var lastEmittedText by remember { mutableStateOf(yamlContent) }
  LaunchedEffect(yamlContent) {
    // Only update if this is an external change (not from our own edits)
    if (yamlContent != lastEmittedText && yamlContent != textFieldValue.text) {
      textFieldValue = TextFieldValue(
        text = yamlContent,
        selection = TextRange(yamlContent.length),
      )
      lastEmittedText = yamlContent
    }
  }

  // Create instances for editor features
  val syntaxHighlighter = remember { YamlSyntaxHighlighter() }
  val history = rememberTextEditHistory()

  // Reset history when content is loaded externally
  LaunchedEffect(yamlContent) {
    if (yamlContent != lastEmittedText && yamlContent != textFieldValue.text) {
      history.clear()
    }
  }

  // Helper to handle value changes and track emitted text
  fun handleValueChange(newValue: TextFieldValue) {
    textFieldValue = newValue
    lastEmittedText = newValue.text
    onYamlContentChange(newValue.text)
  }

  // Format YAML using the isolated YamlFormatter utility
  fun formatYaml(): Boolean {
    val currentText = textFieldValue.text
    if (currentText.isBlank()) return false

    val formatted = YamlFormatter.format(currentText) ?: return false

    if (formatted != currentText) {
      // Save current state for undo
      history.push(textFieldValue)
      // Apply formatted text
      val newValue = TextFieldValue(
        text = formatted,
        selection = TextRange(0), // Move cursor to start after formatting
      )
      handleValueChange(newValue)
    }
    return true
  }

  OutlinedCard(
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
    ) {
      // Header row with title and toolbar buttons
      EditorHeader(
        history = history,
        enabled = !isRunning,
        canFormat = yamlContent.isNotBlank(), // Allow formatting even with validation errors - formatting might fix them!
        textFieldValue = textFieldValue,
        onValueChange = { handleValueChange(it) },
        onFormat = { formatYaml() },
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Code editor with syntax highlighting
      CodeEditorTextField(
        value = textFieldValue,
        onValueChange = { handleValueChange(it) },
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        enabled = !isRunning,
        syntaxHighlighter = syntaxHighlighter,
        history = history,
        placeholder = "Enter your YAML test configuration here...",
        showLineNumbers = true,
        onFormat = { formatYaml() },
      )

      Spacer(modifier = Modifier.height(4.dp))

      // Validation status
      ValidationStatus(
        isValidating = isValidating,
        validationError = validationError,
        hasContent = yamlContent.isNotBlank(),
      )
    }
  }
}

/**
 * Header row with title and toolbar buttons (undo, redo, format).
 */
@Composable
private fun EditorHeader(
  history: TextEditHistory,
  enabled: Boolean,
  canFormat: Boolean,
  textFieldValue: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  onFormat: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Trailblaze YAML",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Medium,
    )

    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Format button
      TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
          Text("Format YAML (${if (isMac()) "⌘⌥F" else "Ctrl+Alt+F"})")
        },
        state = rememberTooltipState(),
      ) {
        IconButton(
          onClick = onFormat,
          enabled = enabled && canFormat,
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.FormatAlignLeft,
            contentDescription = "Format YAML",
            tint = if (enabled && canFormat) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // Undo button
      TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
          Text("Undo (${if (isMac()) "⌘Z" else "Ctrl+Z"})")
        },
        state = rememberTooltipState(),
      ) {
        IconButton(
          onClick = {
            val previousState = history.undo(textFieldValue)
            if (previousState != null) {
              onValueChange(previousState)
            }
          },
          enabled = enabled && history.canUndo,
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = if (enabled && history.canUndo) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
          )
        }
      }

      // Redo button
      TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
          Text("Redo (${if (isMac()) "⌘⇧Z" else "Ctrl+Y"})")
        },
        state = rememberTooltipState(),
      ) {
        IconButton(
          onClick = {
            val nextState = history.redo(textFieldValue)
            if (nextState != null) {
              onValueChange(nextState)
            }
          },
          enabled = enabled && history.canRedo,
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            tint = if (enabled && history.canRedo) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

/**
 * Check if running on macOS.
 */
private fun isMac(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

/**
 * Validation status indicator.
 */
@Composable
private fun ValidationStatus(
  isValidating: Boolean,
  validationError: String?,
  hasContent: Boolean,
) {
  when {
    isValidating -> {
      Text(
        text = "Validating...",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    validationError != null -> {
      Text(
        text = "Error: $validationError",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    hasContent -> {
      Text(
        text = "Valid YAML",
        color = Color(0xFF4CAF50),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

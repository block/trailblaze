@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.editors.yaml

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PlainTooltip
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

/**
 * Text editor content for the YAML tab.
 * Provides a code editor with YAML syntax highlighting, proper indentation,
 * and undo/redo support.
 *
 * @param yamlContent The current YAML content
 * @param onYamlContentChange Callback when content changes
 * @param validationError Current validation error message, or null if valid
 * @param isValidating Whether validation is currently in progress
 * @param enabled Whether the editor is enabled for input
 * @param showTitle Whether to show the "Trailblaze YAML" title header
 * @param modifier Modifier for the component
 */
@Composable
fun YamlTextEditor(
  yamlContent: String,
  onYamlContentChange: (String) -> Unit,
  validationError: String?,
  isValidating: Boolean,
  enabled: Boolean = true,
  showTitle: Boolean = true,
  modifier: Modifier = Modifier,
) {
  // Track the text field value internally - don't use yamlContent as remember key
  // because that would reset cursor position on every keystroke
  var textFieldValue by remember {
    mutableStateOf(
      TextFieldValue(
        text = yamlContent,
        selection = TextRange(0), // Start at beginning of file
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
        selection = TextRange(0), // Start at beginning of file
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
        enabled = enabled,
        textFieldValue = textFieldValue,
        onValueChange = { handleValueChange(it) },
        showTitle = showTitle,
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Code editor with syntax highlighting
      CodeEditorTextField(
        value = textFieldValue,
        onValueChange = { handleValueChange(it) },
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        enabled = enabled,
        syntaxHighlighter = syntaxHighlighter,
        history = history,
        placeholder = "Enter your YAML test configuration here...",
        showLineNumbers = true,
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
 * Backward-compatible overload for existing code that uses isRunning parameter.
 * Used by YamlTabComposables.kt.
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
  YamlTextEditor(
    yamlContent = yamlContent,
    onYamlContentChange = onYamlContentChange,
    validationError = validationError,
    isValidating = isValidating,
    enabled = !isRunning,
    showTitle = true,
    modifier = modifier,
  )
}

/**
 * Header row with title and toolbar buttons (undo, redo).
 */
@Composable
private fun EditorHeader(
  history: TextEditHistory,
  enabled: Boolean,
  textFieldValue: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  showTitle: Boolean = true,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (showTitle) Arrangement.SpaceBetween else Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (showTitle) {
      Text(
        text = "Trailblaze YAML",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
      )
    }

    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Undo button
      TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
          PlainTooltip {
            Text("Undo (${if (isMac()) "⌘Z" else "Ctrl+Z"})")
          }
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
          PlainTooltip {
            Text("Redo (${if (isMac()) "⌘⇧Z" else "Ctrl+Y"})")
          }
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

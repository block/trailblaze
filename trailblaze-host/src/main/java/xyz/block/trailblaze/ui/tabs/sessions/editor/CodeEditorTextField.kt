package xyz.block.trailblaze.ui.tabs.sessions.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.defaultMinSize

/**
 * A code editor text field with YAML syntax highlighting, proper tab handling,
 * auto-indentation, and undo/redo support.
 */
@Composable
fun CodeEditorTextField(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  syntaxHighlighter: YamlSyntaxHighlighter = remember { YamlSyntaxHighlighter() },
  history: TextEditHistory = rememberTextEditHistory(),
  onUndo: (() -> Unit)? = null,
  onRedo: (() -> Unit)? = null,
  onFormat: (() -> Boolean)? = null,
  placeholder: String = "",
  showLineNumbers: Boolean = true,
) {
  val verticalScrollState = rememberScrollState()
  val horizontalScrollState = rememberScrollState()
  val focusRequester = remember { FocusRequester() }

  val visualTransformation = remember(syntaxHighlighter) {
    YamlVisualTransformation(syntaxHighlighter)
  }

  val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
  val borderColor = if (enabled) {
    MaterialTheme.colorScheme.outline
  } else {
    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
  }

  // Helper to apply change with history tracking
  fun applyChange(newValue: TextFieldValue) {
    if (newValue.text != value.text) {
      history.push(value) // Save current state before change
    }
    onValueChange(newValue)
  }

  Box(
    modifier = modifier
      .border(1.dp, borderColor, RoundedCornerShape(8.dp))
      .background(backgroundColor, RoundedCornerShape(8.dp))
  ) {
    Row(
      modifier = Modifier.fillMaxSize()
    ) {
      // Line numbers gutter
      if (showLineNumbers) {
        LineNumberGutter(
          text = value.text,
          scrollState = verticalScrollState,
          modifier = Modifier.padding(vertical = 12.dp),
        )
      }

      // Editor area
      Box(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(verticalScrollState)
          .horizontalScroll(horizontalScrollState)
          .padding(12.dp)
      ) {
        BasicTextField(
          value = value,
          onValueChange = { newValue ->
            if (enabled) {
              applyChange(newValue)
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 800.dp) // Ensure horizontal scrolling works
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { keyEvent ->
              if (!enabled) return@onPreviewKeyEvent false
              handleKeyEvent(
                keyEvent = keyEvent,
                value = value,
                onValueChange = { applyChange(it) },
                history = history,
                onUndo = onUndo,
                onRedo = onRedo,
                onFormat = onFormat,
                rawOnValueChange = onValueChange, // For undo/redo (don't push to history)
              )
            },
          enabled = enabled,
          textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
          ),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          visualTransformation = visualTransformation,
          decorationBox = { innerTextField ->
            // Use fillMaxSize to make the entire area clickable/selectable
            // This helps with selecting newlines by clicking past line ends
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 200.dp)
            ) {
              if (value.text.isEmpty()) {
                Text(
                  text = placeholder,
                  style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                  ),
                )
              }
              innerTextField()
            }
          },
        )
      }
    }
  }
}

/**
 * Line number gutter that displays line numbers alongside the editor.
 */
@Composable
private fun LineNumberGutter(
  text: String,
  scrollState: androidx.compose.foundation.ScrollState,
  modifier: Modifier = Modifier,
) {
  val lineCount = text.count { it == '\n' } + 1
  val maxLineNumberWidth = lineCount.toString().length

  Box(
    modifier = modifier
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
      .padding(horizontal = 8.dp)
  ) {
    // We use a simple Text here since line numbers don't need to be individually clickable
    // The vertical scroll is synced via the shared scrollState
    Text(
      text = (1..lineCount).joinToString("\n") { lineNum ->
        lineNum.toString().padStart(maxLineNumberWidth, ' ')
      },
      style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      ),
    )
  }
}

/**
 * Handles key events for the code editor.
 * Returns true if the event was consumed.
 */
private fun handleKeyEvent(
  keyEvent: androidx.compose.ui.input.key.KeyEvent,
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  history: TextEditHistory,
  onUndo: (() -> Unit)?,
  onRedo: (() -> Unit)?,
  onFormat: (() -> Boolean)?,
  rawOnValueChange: (TextFieldValue) -> Unit,
): Boolean {
  if (keyEvent.type != KeyEventType.KeyDown) return false

  val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
  val isModifierPressed = if (isMac) keyEvent.isMetaPressed else keyEvent.isCtrlPressed
  val isAltPressed = keyEvent.isAltPressed

  return when {
    // Cmd+Option+L (Mac) or Ctrl+Alt+L (Windows/Linux) - Format
    isModifierPressed && isAltPressed && keyEvent.key == Key.L -> {
      onFormat?.invoke()
      true
    }

    // Tab key - indent (handles multi-line selection)
    keyEvent.key == Key.Tab && !keyEvent.isShiftPressed -> {
      indentLines(value, onValueChange)
      true
    }

    // Shift+Tab - unindent (handles multi-line selection)
    keyEvent.key == Key.Tab && keyEvent.isShiftPressed -> {
      unindentLines(value, onValueChange)
      true
    }

    // Enter - new line with auto-indent
    keyEvent.key == Key.Enter -> {
      insertNewlineWithIndent(value, onValueChange)
      true
    }

    // Ctrl/Cmd+Z - Undo
    isModifierPressed && keyEvent.key == Key.Z && !keyEvent.isShiftPressed && !isAltPressed -> {
      val previousState = history.undo(value)
      if (previousState != null) {
        rawOnValueChange(previousState) // Don't push to history when undoing
        onUndo?.invoke()
      }
      true
    }

    // Ctrl/Cmd+Shift+Z or Ctrl/Cmd+Y - Redo
    (isModifierPressed && keyEvent.key == Key.Z && keyEvent.isShiftPressed && !isAltPressed) ||
        (isModifierPressed && keyEvent.key == Key.Y && !isAltPressed) -> {
      val nextState = history.redo(value)
      if (nextState != null) {
        rawOnValueChange(nextState) // Don't push to history when redoing
        onRedo?.invoke()
      }
      true
    }

    else -> false
  }
}

/**
 * Insert text at the current cursor position (no selection).
 */
private fun insertTextAtCursor(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  textToInsert: String,
) {
  val text = value.text
  val selection = value.selection

  val newText = text.substring(0, selection.start) + textToInsert + text.substring(selection.end)
  val newCursorPos = selection.start + textToInsert.length

  onValueChange(
    value.copy(
      text = newText,
      selection = TextRange(newCursorPos),
    ),
  )
}

/**
 * Indent selected lines (or current line if no selection).
 * Adds 2 spaces to the beginning of each affected line.
 * Preserves the selection across the indented lines.
 */
private fun indentLines(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
) {
  val text = value.text
  val selection = value.selection
  val indent = "  " // 2 spaces

  // If no selection (cursor only), just insert spaces at cursor
  if (selection.collapsed) {
    insertTextAtCursor(value, onValueChange, indent)
    return
  }

  if (text.isEmpty()) return

  // Find the lines that are part of the selection
  val selStart = selection.min
  val selEnd = selection.max

  // Find start of first selected line
  val firstLineStart = if (selStart == 0) 0 else text.lastIndexOf('\n', selStart - 1) + 1

  // Find end of last selected line
  val lastLineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }

  // Safety check: ensure valid range
  if (firstLineStart > lastLineEnd || firstLineStart >= text.length) return

  // Get the selected region including full lines
  val selectedRegion = text.substring(firstLineStart, lastLineEnd)

  // Indent each line
  val indentedLines = selectedRegion.split('\n').joinToString("\n") { line ->
    indent + line
  }

  // Count how many lines were indented (to adjust selection)
  val lineCount = selectedRegion.count { it == '\n' } + 1
  val addedChars = indent.length * lineCount

  // Build the new text
  val newText = text.substring(0, firstLineStart) + indentedLines + text.substring(lastLineEnd)

  // Adjust selection to cover the same logical content
  val newSelStart = selStart + indent.length // First line got indented
  val newSelEnd = selEnd + addedChars

  onValueChange(
    value.copy(
      text = newText,
      selection = TextRange(newSelStart, newSelEnd),
    ),
  )
}

/**
 * Unindent selected lines (or current line if no selection).
 * Removes up to 2 spaces from the beginning of each affected line.
 * Preserves the selection across the unindented lines.
 */
private fun unindentLines(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
) {
  val text = value.text
  if (text.isEmpty()) return

  val selection = value.selection

  // Find the lines that are part of the selection (or just current line)
  val selStart = selection.min
  val selEnd = if (selection.collapsed) selStart else selection.max

  // Find start of first selected line
  val firstLineStart = if (selStart == 0) 0 else text.lastIndexOf('\n', selStart - 1) + 1

  // Find end of last selected line (find the newline at or after selEnd, or end of text)
  val lastLineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }

  // Safety check: ensure valid range
  if (firstLineStart > lastLineEnd || firstLineStart >= text.length) return

  // Get the selected region including full lines
  val selectedRegion = text.substring(firstLineStart, lastLineEnd)

  // Track total characters removed and per-line removal for selection adjustment
  var totalRemoved = 0
  var firstLineRemoved = 0
  var firstLineProcessed = false

  // Unindent each line (remove up to 2 leading spaces)
  val unindentedLines = selectedRegion.split('\n').mapIndexed { index, line ->
    var spacesToRemove = 0
    for (i in 0 until minOf(2, line.length)) {
      if (line[i] == ' ') {
        spacesToRemove++
      } else {
        break
      }
    }

    if (!firstLineProcessed) {
      firstLineRemoved = spacesToRemove
      firstLineProcessed = true
    }
    totalRemoved += spacesToRemove

    if (spacesToRemove > 0) {
      line.substring(spacesToRemove)
    } else {
      line
    }
  }.joinToString("\n")

  if (totalRemoved == 0) {
    // Nothing to unindent
    return
  }

  // Build the new text
  val newText = text.substring(0, firstLineStart) + unindentedLines + text.substring(lastLineEnd)

  // Adjust selection
  val newSelStart = maxOf(firstLineStart, selStart - firstLineRemoved)
  val newSelEnd = if (selection.collapsed) {
    newSelStart
  } else {
    maxOf(newSelStart, selEnd - totalRemoved)
  }

  onValueChange(
    value.copy(
      text = newText,
      selection = TextRange(newSelStart, newSelEnd),
    ),
  )
}

/**
 * Insert a new line with auto-indentation matching the current line,
 * plus extra indentation if the line ends with a colon.
 */
private fun insertNewlineWithIndent(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
) {
  val text = value.text
  val selection = value.selection

  // Find the start of the current line
  val lineStart = text.lastIndexOf('\n', selection.start - 1) + 1

  // Get the current line content up to the cursor
  val currentLineUpToCursor = text.substring(lineStart, selection.start)

  // Calculate the indentation of the current line
  val currentIndent = currentLineUpToCursor.takeWhile { it == ' ' }

  // Check if we should add extra indentation
  // Only add extra indent when:
  // 1. Line ends with ":" (starting a new YAML block)
  // 2. Line is exactly "-" (just typed a list marker, need to add content indented)
  val trimmedLine = currentLineUpToCursor.trim()
  val extraIndent = when {
    trimmedLine.endsWith(":") -> "  "
    trimmedLine == "-" -> "  "
    else -> ""
  }

  val newLineContent = "\n" + currentIndent + extraIndent
  insertTextAtCursor(value, onValueChange, newLineContent)
}

/**
 * Visual transformation that applies YAML syntax highlighting.
 */
private class YamlVisualTransformation(
  private val highlighter: YamlSyntaxHighlighter,
) : VisualTransformation {
  override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
    val highlighted = highlighter.highlight(text.text)
    return androidx.compose.ui.text.input.TransformedText(
      highlighted,
      androidx.compose.ui.text.input.OffsetMapping.Identity,
    )
  }
}

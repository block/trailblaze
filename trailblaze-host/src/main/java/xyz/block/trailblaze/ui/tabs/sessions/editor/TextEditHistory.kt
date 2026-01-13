package xyz.block.trailblaze.ui.tabs.sessions.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Manages undo/redo history for text editing.
 *
 * Simple stack-based implementation:
 * - Push saves the CURRENT state before making a change
 * - Undo returns the last saved state and moves current to redo stack
 * - Redo returns the last undone state
 */
@Stable
class TextEditHistory(
  private val maxHistorySize: Int = 100,
) {
  private val undoStack = ArrayDeque<TextState>()
  private val redoStack = ArrayDeque<TextState>()

  /**
   * Represents a snapshot of text state for undo/redo.
   */
  data class TextState(
    val text: String,
    val selection: TextRange,
  ) {
    fun toTextFieldValue(): TextFieldValue = TextFieldValue(
      text = text,
      selection = selection,
    )

    companion object {
      fun from(textFieldValue: TextFieldValue): TextState = TextState(
        text = textFieldValue.text,
        selection = textFieldValue.selection,
      )
    }
  }

  /**
   * Whether an undo operation is available.
   */
  var canUndo by mutableStateOf(false)
    private set

  /**
   * Whether a redo operation is available.
   */
  var canRedo by mutableStateOf(false)
    private set

  /**
   * Push a state onto the undo stack.
   * Call this BEFORE applying a change to save the current state.
   * Clears the redo stack since we're creating a new edit branch.
   */
  fun push(state: TextState) {
    // Don't push duplicates
    if (undoStack.lastOrNull()?.text == state.text) return

    undoStack.addLast(state)
    if (undoStack.size > maxHistorySize) {
      undoStack.removeFirst()
    }
    redoStack.clear()
    updateState()
  }

  /**
   * Push a state from a TextFieldValue.
   */
  fun push(textFieldValue: TextFieldValue) {
    push(TextState.from(textFieldValue))
  }

  /**
   * Undo the last edit.
   * Returns the previous state to restore, or null if nothing to undo.
   *
   * @param currentState The current state (will be saved for redo)
   */
  fun undo(currentState: TextState): TextState? {
    if (undoStack.isEmpty()) return null

    // Save current state to redo stack
    redoStack.addLast(currentState)

    // Pop and return the previous state
    val previousState = undoStack.removeLast()
    updateState()
    return previousState
  }

  /**
   * Undo from a TextFieldValue.
   */
  fun undo(currentTextFieldValue: TextFieldValue): TextFieldValue? {
    return undo(TextState.from(currentTextFieldValue))?.toTextFieldValue()
  }

  /**
   * Redo the last undone edit.
   * Returns the state to restore, or null if nothing to redo.
   *
   * @param currentState The current state (will be saved for undo)
   */
  fun redo(currentState: TextState): TextState? {
    if (redoStack.isEmpty()) return null

    // Save current state to undo stack
    undoStack.addLast(currentState)

    // Pop and return the redo state
    val redoState = redoStack.removeLast()
    updateState()
    return redoState
  }

  /**
   * Redo from a TextFieldValue.
   */
  fun redo(currentTextFieldValue: TextFieldValue): TextFieldValue? {
    return redo(TextState.from(currentTextFieldValue))?.toTextFieldValue()
  }

  /**
   * Clear all history.
   */
  fun clear() {
    undoStack.clear()
    redoStack.clear()
    updateState()
  }

  private fun updateState() {
    canUndo = undoStack.isNotEmpty()
    canRedo = redoStack.isNotEmpty()
  }
}

/**
 * Remember a TextEditHistory instance across recompositions.
 */
@Composable
fun rememberTextEditHistory(
  maxHistorySize: Int = 100,
): TextEditHistory {
  return remember { TextEditHistory(maxHistorySize) }
}

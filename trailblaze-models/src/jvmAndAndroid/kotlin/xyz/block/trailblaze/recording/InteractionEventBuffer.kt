package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Handles debouncing and batching of user input events during interactive recording.
 *
 * - Taps, long presses, swipes: emitted immediately.
 * - Text keystrokes: buffered and debounced at [textDebounceMs], emitting a single
 *   `inputText(text: "...")` for the net result. Backspace removes characters from
 *   the buffer. Special keys (Enter, Tab, Escape) flush pending text first.
 */
class InteractionEventBuffer(
  private val scope: CoroutineScope,
  private val textDebounceMs: Long = 500L,
  private val toolFactory: InteractionToolFactory,
  private val onInteraction: (RecordedInteraction) -> Unit,
) {
  private val lock = Any()
  private var textBuffer = StringBuilder()
  private var textDebounceJob: Job? = null
  private var textFieldScreenshot: ByteArray? = null
  private var textFieldHierarchyText: String? = null

  fun onTap(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    screenshot: ByteArray?,
    hierarchyText: String?,
  ) {
    flushText()
    val tool = toolFactory.createTapTool(node, x, y)
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  fun onLongPress(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    screenshot: ByteArray?,
    hierarchyText: String?,
  ) {
    flushText()
    val tool = toolFactory.createLongPressTool(node, x, y)
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  fun onSwipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    screenshot: ByteArray?,
    hierarchyText: String?,
  ) {
    flushText()
    val tool = toolFactory.createSwipeTool(startX, startY, endX, endY)
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  fun onCharacterTyped(char: Char, screenshot: ByteArray?, hierarchyText: String?) {
    synchronized(lock) {
      if (textBuffer.isEmpty()) {
        // Save the screenshot from the start of typing
        textFieldScreenshot = screenshot
        textFieldHierarchyText = hierarchyText
      }
      textBuffer.append(char)
      scheduleTextFlush()
    }
  }

  fun onBackspace() {
    synchronized(lock) {
      if (textBuffer.isNotEmpty()) {
        textBuffer.deleteAt(textBuffer.length - 1)
        scheduleTextFlush()
      }
    }
  }

  fun onSpecialKey(key: String, screenshot: ByteArray?, hierarchyText: String?) {
    flushText()
    val tool = toolFactory.createPressKeyTool(key) ?: return
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  /**
   * Submit a complete text string as a single inputText action.
   * Unlike [onCharacterTyped], this does not debounce — the full text is emitted immediately.
   */
  fun onTextSubmitted(text: String, screenshot: ByteArray?, hierarchyText: String?) {
    flushText()
    val tool = toolFactory.createInputTextTool(text)
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  /** Flush any pending text buffer. Call when recording stops. */
  fun flush() {
    flushText()
  }

  private fun flushText() {
    synchronized(lock) {
      textDebounceJob?.cancel()
      textDebounceJob = null
      val text = textBuffer.toString()
      if (text.isNotEmpty()) {
        val tool = toolFactory.createInputTextTool(text)
        emit(tool.first, tool.second, textFieldScreenshot, textFieldHierarchyText)
        textBuffer.clear()
        textFieldScreenshot = null
        textFieldHierarchyText = null
      }
    }
  }

  private fun scheduleTextFlush() {
    // Must be called under lock
    textDebounceJob?.cancel()
    textDebounceJob = scope.launch {
      delay(textDebounceMs)
      flushText()
    }
  }

  private fun emit(
    tool: TrailblazeTool,
    toolName: String,
    screenshot: ByteArray?,
    hierarchyText: String?,
  ) {
    onInteraction(
      RecordedInteraction(
        tool = tool,
        toolName = toolName,
        screenshotBytes = screenshot,
        viewHierarchyText = hierarchyText,
        timestamp = Clock.System.now().toEpochMilliseconds(),
      ),
    )
  }
}

/**
 * Factory interface for creating platform-specific tool instances from user input.
 * Playwright creates `playwright_click`, `playwright_type`, etc.
 * Maestro creates `tapOnElementWithText`, `inputText`, etc.
 */
interface InteractionToolFactory {
  /** Returns (TrailblazeTool, toolName) for a tap on the given node or coordinates. */
  fun createTapTool(node: ViewHierarchyTreeNode?, x: Int, y: Int): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for a long press. */
  fun createLongPressTool(node: ViewHierarchyTreeNode?, x: Int, y: Int): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for a swipe gesture. */
  fun createSwipeTool(startX: Int, startY: Int, endX: Int, endY: Int): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for text input. */
  fun createInputTextTool(text: String): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for a special key press, or null if unsupported. */
  fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>?
}

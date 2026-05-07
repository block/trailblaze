package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Handles debouncing and batching of user input events during interactive recording.
 *
 * - Taps, long presses, swipes: emitted immediately.
 * - Text keystrokes: buffered and debounced at [textDebounceMs], emitting a single
 *   `inputText(text: "...")` for the net result. Backspace removes characters from
 *   the buffer. Special keys (Enter, Tab, Escape) flush pending text first.
 *
 * ## Threading contract
 *
 * **Call all `on*` methods from the same dispatcher.** The recording UI passes a Compose
 * scope (`rememberCoroutineScope()`), which runs every `launch` body — including the debounce
 * coroutine that resumes after `delay(textDebounceMs)` — on the main thread. Under that
 * contract there is no real concurrency: a tap that arrives mid-debounce serializes with the
 * resumed flush via cooperative suspension, not preemption.
 *
 * The earlier `synchronized(lock)` blocks were defensive against a multi-thread caller that
 * never materialized; dropping them is what makes this class wasmJs-portable (Kotlin
 * `synchronized` is JVM-only). Future callers that DO want multi-threaded access should wrap
 * their own access in their own mutex; this class assumes single-dispatcher use.
 */
class InteractionEventBuffer(
  private val scope: CoroutineScope,
  private val textDebounceMs: Long = 500L,
  private val toolFactory: InteractionToolFactory,
  private val onInteraction: (RecordedInteraction) -> Unit,
) {
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
    trailblazeNodeTree: TrailblazeNode? = null,
  ) {
    flushText()
    val tool = toolFactory.createTapTool(node, x, y, trailblazeNodeTree)
    val candidates = toolFactory.findSelectorCandidates(trailblazeNodeTree, x, y)
    emit(
      tool = tool.first,
      toolName = tool.second,
      screenshot = screenshot,
      hierarchyText = hierarchyText,
      selectorCandidates = candidates,
      capturedTree = trailblazeNodeTree,
      tapPoint = x to y,
    )
  }

  fun onLongPress(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    screenshot: ByteArray?,
    hierarchyText: String?,
    trailblazeNodeTree: TrailblazeNode? = null,
  ) {
    flushText()
    val tool = toolFactory.createLongPressTool(node, x, y, trailblazeNodeTree)
    val candidates = toolFactory.findSelectorCandidates(trailblazeNodeTree, x, y)
    emit(
      tool = tool.first,
      toolName = tool.second,
      screenshot = screenshot,
      hierarchyText = hierarchyText,
      selectorCandidates = candidates,
      capturedTree = trailblazeNodeTree,
      tapPoint = x to y,
    )
  }

  fun onSwipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    screenshot: ByteArray?,
    hierarchyText: String?,
    durationMs: Long? = null,
  ) {
    flushText()
    val tool = toolFactory.createSwipeTool(startX, startY, endX, endY, durationMs)
    emit(tool.first, tool.second, screenshot, hierarchyText)
  }

  fun onCharacterTyped(char: Char, screenshot: ByteArray?, hierarchyText: String?) {
    if (textBuffer.isEmpty()) {
      // Save the screenshot from the start of typing
      textFieldScreenshot = screenshot
      textFieldHierarchyText = hierarchyText
    }
    textBuffer.append(char)
    scheduleTextFlush()
  }

  fun onBackspace() {
    if (textBuffer.isNotEmpty()) {
      textBuffer.deleteAt(textBuffer.length - 1)
      scheduleTextFlush()
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

  /**
   * Record a platform-specific tool that doesn't fit the abstract input cascade (tap, swipe,
   * text, key) — typically chrome-level actions like web URL navigation. Pending typed text is
   * flushed first so a sequence like `type "foo" → click Back` records the input before the
   * navigation, matching the order the user actually performed.
   */
  fun recordCustomTool(
    tool: TrailblazeTool,
    toolName: String,
    screenshot: ByteArray? = null,
    hierarchyText: String? = null,
  ) {
    flushText()
    emit(tool, toolName, screenshot, hierarchyText)
  }

  /** Flush any pending text buffer. Call when recording stops. */
  fun flush() {
    flushText()
  }

  private fun flushText() {
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

  private fun scheduleTextFlush() {
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
    selectorCandidates: List<TrailblazeNodeSelectorGenerator.NamedSelector> = emptyList(),
    capturedTree: TrailblazeNode? = null,
    tapPoint: Pair<Int, Int>? = null,
  ) {
    onInteraction(
      RecordedInteraction(
        tool = tool,
        toolName = toolName,
        screenshotBytes = screenshot,
        viewHierarchyText = hierarchyText,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        selectorCandidates = selectorCandidates,
        capturedTree = capturedTree,
        tapPoint = tapPoint,
      ),
    )
  }
}

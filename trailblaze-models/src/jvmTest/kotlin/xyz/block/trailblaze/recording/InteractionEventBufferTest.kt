package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractionEventBufferTest {

  private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  @Test
  fun `onTap emits immediately`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onTap(null, 100, 200, null, null)

    assertEquals(1, emissions.size)
    assertEquals("tap", emissions[0].toolName)
  }

  @Test
  fun `onLongPress emits immediately`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onLongPress(null, 100, 200, null, null)

    assertEquals(1, emissions.size)
    assertEquals("longPress", emissions[0].toolName)
  }

  @Test
  fun `onSwipe emits immediately`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onSwipe(100, 200, 100, 500, null, null)

    assertEquals(1, emissions.size)
    assertEquals("swipe", emissions[0].toolName)
  }

  @Test
  fun `special key flushes pending text first`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onCharacterTyped('a', null, null)
    buffer.onCharacterTyped('b', null, null)
    buffer.onSpecialKey("Enter", null, null)

    // Should have flushed text first, then emitted Enter
    assertEquals(2, emissions.size)
    assertEquals("inputText", emissions[0].toolName)
    assertEquals("pressKey", emissions[1].toolName)
  }

  @Test
  fun `tap flushes pending text first`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onCharacterTyped('x', null, null)
    buffer.onTap(null, 100, 200, null, null)

    assertEquals(2, emissions.size)
    assertEquals("inputText", emissions[0].toolName)
    assertEquals("tap", emissions[1].toolName)
  }

  @Test
  fun `flush emits pending text`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onCharacterTyped('t', null, null)
    buffer.onCharacterTyped('e', null, null)
    buffer.onCharacterTyped('s', null, null)
    buffer.onCharacterTyped('t', null, null)

    // Before flush, text is pending (debounce hasn't fired yet)
    buffer.flush()
    assertEquals(1, emissions.size)
    assertEquals("inputText", emissions[0].toolName)
  }

  @Test
  fun `flush with empty buffer does nothing`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.flush()
    assertTrue(emissions.isEmpty())
  }

  @Test
  fun `backspace removes character from buffer before flush`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onCharacterTyped('a', null, null)
    buffer.onCharacterTyped('b', null, null)
    buffer.onCharacterTyped('c', null, null)
    buffer.onBackspace() // remove 'c'
    buffer.flush()

    assertEquals(1, emissions.size)
    assertEquals("inputText", emissions[0].toolName)
  }

  @Test
  fun `backspace on empty buffer does not crash`() {
    val emissions = mutableListOf<RecordedInteraction>()
    val buffer = createBuffer(emissions)

    buffer.onBackspace()
    buffer.flush()
    assertTrue(emissions.isEmpty())
  }

  private fun createBuffer(
    emissions: MutableList<RecordedInteraction>,
  ): InteractionEventBuffer {
    return InteractionEventBuffer(
      scope = testScope,
      textDebounceMs = 500L,
      toolFactory = TestToolFactory(),
      onInteraction = { emissions.add(it) },
    )
  }
}

private class TestToolFactory : InteractionToolFactory {
  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> = TestTool("tap($x,$y)") to "tap"

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> = TestTool("longPress($x,$y)") to "longPress"

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): Pair<TrailblazeTool, String> = TestTool("swipe($startX,$startY,$endX,$endY)") to "swipe"

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> =
    TestTool("inputText($text)") to "inputText"

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String> =
    TestTool("pressKey($key)") to "pressKey"
}

private data class TestTool(val description: String) : TrailblazeTool

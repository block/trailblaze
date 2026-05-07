package xyz.block.trailblaze.recording

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the contract that [RecordedInteraction.selectorCandidates], [RecordedInteraction.capturedTree],
 * and [RecordedInteraction.tapPoint] are **excluded** from `equals` / `hashCode`. These are UI-
 * advisory metadata for the picker dropdown — captured tree + tap point feed on-demand selector
 * recompute for `tapOnPoint` cards, candidates feed precomputed dropdown contents — but none of
 * them are part of the interaction's identity. Two recordings of the same tool at the same
 * timestamp should compare equal regardless of what supplementary metadata the recorder
 * happened to compute. Without this guarantee, every selector swap would re-trigger downstream
 * consumers (LazyColumn keys, deduplication logic) that already track equality.
 */
class RecordedInteractionTest {

  private data class FakeTool(val tag: String) : TrailblazeTool

  private fun fakeCandidate(strategy: String): TrailblazeNodeSelectorGenerator.NamedSelector =
    TrailblazeNodeSelectorGenerator.NamedSelector(
      selector = TrailblazeNodeSelector(
        iosMaestro = DriverNodeMatch.IosMaestro(textRegex = strategy),
      ),
      strategy = strategy,
    )

  @Test
  fun `equals ignores selectorCandidates when other fields match`() {
    val tool = FakeTool("tap")
    val timestamp = 1_700_000_000_000L
    val a = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      selectorCandidates = listOf(fakeCandidate("text")),
    )
    val b = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      selectorCandidates = listOf(fakeCandidate("resourceId"), fakeCandidate("text + className")),
    )
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `equals still distinguishes different tools`() {
    val timestamp = 1_700_000_000_000L
    val a = RecordedInteraction(
      tool = FakeTool("tap"),
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
    )
    val b = RecordedInteraction(
      tool = FakeTool("longPress"),
      toolName = "longPress",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
    )
    assertNotEquals(a, b)
  }

  @Test
  fun `equals ignores capturedTree and tapPoint when other fields match`() {
    val tool = FakeTool("tap")
    val timestamp = 1_700_000_000_000L
    val treeA = TrailblazeNode(
      nodeId = 0L,
      driverDetail = DriverNodeDetail.AndroidMaestro(text = "A"),
    )
    val treeB = TrailblazeNode(
      nodeId = 0L,
      driverDetail = DriverNodeDetail.AndroidMaestro(text = "B"),
    )
    val a = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      capturedTree = treeA,
      tapPoint = 10 to 20,
    )
    val b = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      capturedTree = treeB,
      tapPoint = 99 to 99,
    )
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `equals ignores capturedTree and tapPoint when one is null`() {
    val tool = FakeTool("tap")
    val timestamp = 1_700_000_000_000L
    val a = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      capturedTree = null,
      tapPoint = null,
    )
    val b = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
      capturedTree = TrailblazeNode(
        nodeId = 0L,
        driverDetail = DriverNodeDetail.AndroidMaestro(),
      ),
      tapPoint = 5 to 5,
    )
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `equals still distinguishes different timestamps`() {
    val tool = FakeTool("tap")
    val a = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 1L,
    )
    val b = RecordedInteraction(
      tool = tool,
      toolName = "tap",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 2L,
    )
    assertNotEquals(a, b)
  }
}

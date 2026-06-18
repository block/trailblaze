package xyz.block.trailblaze.toolcalls.commands

import org.junit.Test
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the registration + visibility contract for the `dragTo` gesture tool. The ref-based
 * `dragTo` is what the LLM sees and chooses; it resolves source/target refs to coordinates and
 * delegates to the recordable, non-LLM `dragByPoints` (which lowers to a Maestro swipe).
 */
class DragToTrailblazeToolTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `dragTo resolves to DragToTrailblazeTool via its tool yaml`() {
    assertTrue(resolver.isKnown("dragTo"), "dragTo should be a known tool name")
    assertEquals(
      DragToTrailblazeTool::class,
      resolver.resolveOrNull("dragTo"),
      "dragTo.tool.yaml should map the id to DragToTrailblazeTool",
    )
  }

  @Test
  fun `dragTo is surfaced to the LLM and delegates rather than records directly`() {
    val annotation = DragToTrailblazeTool::class.trailblazeToolClassAnnotation()
    assertEquals("dragTo", annotation.name)
    assertTrue(annotation.surfaceToLlm, "dragTo must be visible to the LLM")
    assertEquals(false, annotation.isRecordable, "dragTo delegates to dragByPoints for recording")
    assertTrue(DragToTrailblazeTool(ref = "y1", toRef = "y2") is DelegatingTrailblazeTool)
  }

  @Test
  fun `dragByPoints is the recordable delegate, hidden from the LLM`() {
    val annotation = DragByPointsTrailblazeTool::class.trailblazeToolClassAnnotation()
    assertEquals("dragByPoints", annotation.name)
    assertEquals(false, annotation.surfaceToLlm, "the coordinate form must not be offered to the LLM")
    assertTrue(annotation.isRecordable, "the coordinate form is what lands in recordings")
    assertEquals(
      DragByPointsTrailblazeTool::class,
      resolver.resolveOrNull("dragByPoints"),
    )
  }

  @Test
  fun `dragTo rides in the always-enabled core_interaction toolset alongside tap`() {
    val coreInteraction = TrailblazeToolSetCatalog.entryToolClasses("core_interaction")
    assertTrue(
      DragToTrailblazeTool::class in coreInteraction,
      "dragTo should be in core_interaction so the agent sees it by default",
    )
    assertTrue(coreInteraction.any { it.toolName().toolName == "tap" }, "sanity: tap is a sibling")
  }
}

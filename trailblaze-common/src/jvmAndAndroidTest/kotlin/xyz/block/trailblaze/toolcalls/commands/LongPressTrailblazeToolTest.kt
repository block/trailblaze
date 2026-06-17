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
 * Pins the discoverability contract for the first-class `longPress` tool. The whole point of the
 * tool is that the LLM agent SEES a named long-press affordance (rather than a low-salience
 * boolean flag on `tap`), so these tests guard registration + LLM visibility, not the gesture
 * itself — the hold is exercised by the shared `tap` → `TapOnByElementSelector(longPress=true)`
 * path that this tool delegates into.
 */
class LongPressTrailblazeToolTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `longPress resolves to LongPressTrailblazeTool via its tool yaml`() {
    assertTrue(resolver.isKnown("longPress"), "longPress should be a known tool name")
    assertEquals(
      LongPressTrailblazeTool::class,
      resolver.resolveOrNull("longPress"),
      "longPress.tool.yaml should map the id to LongPressTrailblazeTool",
    )
  }

  @Test
  fun `longPress is surfaced to the LLM and is not separately recordable`() {
    val annotation = LongPressTrailblazeTool::class.trailblazeToolClassAnnotation()
    assertEquals("longPress", annotation.name)
    assertTrue(annotation.surfaceToLlm, "longPress must be visible to the LLM — that is the fix")
    assertEquals(false, annotation.isRecordable, "delegates to TapOnByElementSelector for recording")
  }

  @Test
  fun `longPress rides in the always-enabled core_interaction toolset alongside tap`() {
    val coreInteraction = TrailblazeToolSetCatalog.entryToolClasses("core_interaction")
    assertTrue(
      LongPressTrailblazeTool::class in coreInteraction,
      "longPress should be in core_interaction so the agent sees it by default",
    )
    assertTrue(
      coreInteraction.any { it.toolName().toolName == "tap" },
      "sanity: tap is the sibling tool in the same toolset",
    )
  }

  @Test
  fun `longPress is a delegating tool`() {
    assertTrue(LongPressTrailblazeTool(ref = "y1") is DelegatingTrailblazeTool)
  }
}

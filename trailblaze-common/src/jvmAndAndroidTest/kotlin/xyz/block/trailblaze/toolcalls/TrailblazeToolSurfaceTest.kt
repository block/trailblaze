package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [TrailblazeToolSurface.allToolNames] — the single union of the two tool
 * backings (class-backed and scripted). This is the accessor that replaced hand-rolled unions
 * that repeatedly dropped the scripted partition; pinning its semantics here localizes a
 * regression to the accessor itself instead of only surfacing it indirectly via the server-side
 * cross-surface parity test.
 */
class TrailblazeToolSurfaceTest {

  @TrailblazeToolClass("surfaceClassTool")
  private class SurfaceClassTool : TrailblazeTool

  private fun surface(
    classes: Set<KClass<out TrailblazeTool>> = emptySet(),
    scripted: Set<ToolName> = emptySet(),
  ): TrailblazeToolSurface = object : TrailblazeToolSurface {
    override val toolClasses = classes
    override val scriptedToolNames = scripted
  }

  @Test
  fun `allToolNames unions class-backed and scripted names`() {
    val s = surface(
      classes = setOf(SurfaceClassTool::class),
      scripted = setOf(ToolName("openUrl")),
    )
    assertEquals(
      setOf(ToolName("surfaceClassTool"), ToolName("openUrl")),
      s.allToolNames,
    )
  }

  @Test
  fun `allToolNames keeps the scripted partition when classes are also present`() {
    // The exact drop the accessor prevents: scripted names must not fall out when the class-backed
    // partition is non-empty (the shape every regressed surface had).
    val s = surface(
      classes = setOf(SurfaceClassTool::class),
      scripted = setOf(ToolName("openUrl")),
    )
    assertTrue(ToolName("openUrl") in s.allToolNames)
  }

  @Test
  fun `allToolNames is empty for an empty surface`() {
    assertTrue(surface().allToolNames.isEmpty())
  }

  @Test
  fun `allToolNameStrings mirrors allToolNames as raw strings`() {
    val s = surface(
      classes = setOf(SurfaceClassTool::class),
      scripted = setOf(ToolName("openUrl")),
    )
    assertEquals(setOf("surfaceClassTool", "openUrl"), s.allToolNameStrings)
  }

  @Test
  fun `the concrete TrailblazeToolSet hierarchy exposes allToolNames`() {
    // The accessor works on a real toolset, not just an anonymous surface.
    val toolSet = TrailblazeToolSet.DynamicToolSet(
      toolClasses = setOf(SurfaceClassTool::class),
      scriptedToolNames = setOf(ToolName("openUrl")),
    )
    assertEquals(
      setOf(ToolName("surfaceClassTool"), ToolName("openUrl")),
      toolSet.allToolNames,
    )
  }
}

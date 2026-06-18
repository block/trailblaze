package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [TrailblazeToolSurface.allToolNames] — the single union of the three tool
 * backings (class-backed, YAML-defined, scripted). This is the accessor that replaced the
 * hand-rolled `classes + yaml` unions that repeatedly dropped the scripted partition; pinning its
 * semantics here localizes a regression to the accessor itself instead of only surfacing it
 * indirectly via the server-side cross-surface parity test.
 */
class TrailblazeToolSurfaceTest {

  @TrailblazeToolClass("surfaceClassTool")
  private class SurfaceClassTool : TrailblazeTool

  private fun surface(
    classes: Set<KClass<out TrailblazeTool>> = emptySet(),
    yaml: Set<ToolName> = emptySet(),
    scripted: Set<ToolName> = emptySet(),
  ): TrailblazeToolSurface = object : TrailblazeToolSurface {
    override val toolClasses = classes
    override val yamlToolNames = yaml
    override val scriptedToolNames = scripted
  }

  @Test
  fun `allToolNames unions class-backed, YAML-defined, and scripted names`() {
    val s = surface(
      classes = setOf(SurfaceClassTool::class),
      yaml = setOf(ToolName("pressBack")),
      scripted = setOf(ToolName("openUrl")),
    )
    assertEquals(
      setOf(ToolName("surfaceClassTool"), ToolName("pressBack"), ToolName("openUrl")),
      s.allToolNames,
    )
  }

  @Test
  fun `allToolNames keeps the scripted partition when classes and yaml are also present`() {
    // The exact drop the accessor prevents: scripted names must not fall out when the other two
    // partitions are non-empty (the shape every regressed surface had).
    val s = surface(
      classes = setOf(SurfaceClassTool::class),
      yaml = setOf(ToolName("pressBack")),
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
      yaml = setOf(ToolName("pressBack")),
      scripted = setOf(ToolName("openUrl")),
    )
    assertEquals(setOf("pressBack", "openUrl"), s.allToolNameStrings)
  }

  @Test
  fun `the concrete TrailblazeToolSet hierarchy exposes allToolNames`() {
    // The accessor works on a real toolset, not just an anonymous surface.
    val toolSet = TrailblazeToolSet.DynamicToolSet(
      toolClasses = setOf(SurfaceClassTool::class),
      yamlToolNames = setOf(ToolName("pressBack")),
      scriptedToolNames = setOf(ToolName("openUrl")),
    )
    assertEquals(
      setOf(ToolName("surfaceClassTool"), ToolName("pressBack"), ToolName("openUrl")),
      toolSet.allToolNames,
    )
  }
}

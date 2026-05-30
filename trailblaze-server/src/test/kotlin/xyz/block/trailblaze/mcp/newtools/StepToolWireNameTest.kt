package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.Tool
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.mcp.McpToolProfile

/**
 * Pins the MCP wire-protocol contract for the agent-loop tool after the
 * `blaze → step` rename. Three layers must agree on the name `"step"`:
 *
 *   1. The [McpToolProfile.TOOL_STEP] constant the @Tool annotation reads from.
 *   2. The Kotlin function on [StepToolSet] that carries the @Tool annotation.
 *   3. The [McpToolProfile.MINIMAL_TOOL_NAMES] set that controls which tools
 *      external MCP clients see in MINIMAL profile.
 *
 * The hard-cut decision (no `"blaze"` wire alias) is also asserted by negative
 * checks below — a future refactor that re-adds the old name will fail here.
 *
 * No StepToolSet instance is constructed — reflection on the class itself
 * is enough to verify the @Tool annotation's `customName`, which is what
 * koog's `.asTools()` reflection uses at MCP-server registration time.
 */
class StepToolWireNameTest {
  @Test
  fun `TOOL_STEP constant resolves to wire name step`() {
    assertEquals("step", McpToolProfile.TOOL_STEP)
  }

  @Test
  fun `StepToolSet has a @Tool function registered under the step wire name`() {
    val toolFunctions =
      StepToolSet::class.declaredMemberFunctions.filter { it.findAnnotation<Tool>() != null }

    val stepFn =
      toolFunctions.singleOrNull { it.findAnnotation<Tool>()!!.customName == "step" }
    assertNotNull(
      stepFn,
      "Expected exactly one @Tool(customName = \"step\") declaration on StepToolSet. " +
        "Found: ${toolFunctions.map { it.name to it.findAnnotation<Tool>()?.customName }}",
    )

    // The Kotlin function name should match the wire name so reflection-based
    // tooling and grep-by-function-name lookups don't diverge from the wire.
    assertEquals(
      "step",
      stepFn.name,
      "The Kotlin function carrying @Tool(\"step\") should also be named `step` — " +
        "drift here means a follow-on rename was half-applied.",
    )
  }

  @Test
  fun `no @Tool function on StepToolSet is registered under the deprecated blaze name`() {
    val blazeFn =
      StepToolSet::class.declaredMemberFunctions
        .filter { it.findAnnotation<Tool>() != null }
        .firstOrNull { it.findAnnotation<Tool>()!!.customName == "blaze" }
    assertNull(
      blazeFn,
      "No @Tool(customName = \"blaze\") declaration should remain — the wire-protocol " +
        "rename was a hard cut (no alias). Re-introducing the old name would silently " +
        "make `blaze` resolve again at the MCP layer; this assertion locks the cut in.",
    )
  }

  @Test
  fun `MINIMAL_TOOL_NAMES exposes step and does not expose blaze`() {
    assertTrue(
      "step" in McpToolProfile.MINIMAL_TOOL_NAMES,
      "MINIMAL profile must include the renamed `step` tool so external MCP clients " +
        "(Claude Code, Cursor, Codex) using the minimal toolset still see the action loop.",
    )
    assertFalse(
      "blaze" in McpToolProfile.MINIMAL_TOOL_NAMES,
      "MINIMAL profile must not include the old `blaze` tool name after the hard-cut " +
        "rename — drift here would silently re-expose the deprecated name to external clients.",
    )
  }
}

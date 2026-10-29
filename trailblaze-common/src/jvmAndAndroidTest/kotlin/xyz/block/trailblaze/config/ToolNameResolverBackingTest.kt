package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the "toolsets list tools by bare name, regardless of backing" invariant: the resolver
 * knows class-backed and scripted tools by name, and refuses a name claimed by both.
 */
class ToolNameResolverBackingTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `assertWaypoint is registered class-backed so recorded trails resolve it`() {
    // Regression guard: a class-backed tool is only resolvable if it has a `*.tool.yaml`
    // descriptor (the registry is built from those, not a classpath annotation scan). Without
    // `assertWaypoint.tool.yaml`, the `verification` toolset would silently skip the name and a
    // recorded `- assertWaypoint:` step would deserialize to an unknown tool and fail at run time.
    assertTrue(resolver.isKnown("assertWaypoint"), "assertWaypoint must be registered via its .tool.yaml")
    assertEquals(
      xyz.block.trailblaze.toolcalls.commands.AssertWaypointTrailblazeTool::class,
      resolver.resolveOrNull("assertWaypoint"),
      "assertWaypoint must resolve to AssertWaypointTrailblazeTool",
    )
  }

  @Test
  fun `resolver reports hideKeyboard as known via the class-backed backing`() {
    assertTrue(resolver.isKnown("hideKeyboard"))
    assertNotNull(resolver.resolveOrNull("hideKeyboard"))
  }

  @Test
  fun `collision between class-backed and scripted names fails fast`() {
    val existingClass = resolver.resolveOrNull("hideKeyboard")
      ?: error("hideKeyboard should be class-backed for this test to make sense")
    val exception = assertFailsWith<IllegalArgumentException> {
      ToolNameResolver(
        knownTools = mapOf(ToolName("hideKeyboard") to existingClass),
        knownScriptedToolNames = setOf(ToolName("hideKeyboard")),
      )
    }
    assertTrue(
      exception.message?.contains("class-backed vs scripted") == true,
      "Expected a class-backed-vs-scripted collision error, got: ${exception.message}",
    )
  }
}

package xyz.block.trailblaze.logs.server

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins which tool names an MCP re-registration un-advertises.
 *
 * MCP tools live in one flat name-keyed map shared by the host surface and the
 * target-scoped TrailblazeTool surface, so "remove what the new surface dropped" has to be
 * computed against the host surface too — otherwise a name that moved from one surface to
 * the other gets removed right after it was registered.
 */
class ComputeStaleToolNamesToRemoveTest {

  @Test
  fun `drops names the new surface no longer contains`() {
    assertEquals(
      setOf("seedData"),
      computeStaleToolNamesToRemove(
        previouslyRegisteredToolNames = setOf("tap", "seedData"),
        newlyRegisteredToolNames = setOf("tap"),
        liveHostToolNames = emptySet(),
      ),
      "A target switch that drops a custom tool must stop advertising it — a client can " +
        "otherwise still call a tool the session can't run.",
    )
  }

  @Test
  fun `keeps names the new surface still contains`() {
    assertEquals(
      emptySet(),
      computeStaleToolNamesToRemove(
        previouslyRegisteredToolNames = setOf("tap", "tapOnPoint"),
        newlyRegisteredToolNames = setOf("tap", "tapOnPoint"),
        liveHostToolNames = emptySet(),
      ),
      "Re-registering an unchanged surface must remove nothing.",
    )
  }

  @Test
  fun `never removes a name the host registry is currently serving`() {
    assertEquals(
      emptySet(),
      computeStaleToolNamesToRemove(
        previouslyRegisteredToolNames = setOf("endSession"),
        newlyRegisteredToolNames = emptySet(),
        liveHostToolNames = setOf("endSession"),
      ),
      "The host tools are registered earlier in the same pass. A stale target-scoped name " +
        "that collides with a live host tool must not un-register the host tool that now " +
        "owns that name.",
    )
  }

  @Test
  fun `a host collision does not shield unrelated stale names`() {
    assertEquals(
      setOf("seedData"),
      computeStaleToolNamesToRemove(
        previouslyRegisteredToolNames = setOf("endSession", "seedData"),
        newlyRegisteredToolNames = emptySet(),
        liveHostToolNames = setOf("endSession"),
      ),
      "Protecting host names must stay scoped to the colliding name — everything else the " +
        "surface dropped still has to go.",
    )
  }
}

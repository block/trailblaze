package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.mcp.AgentImplementation

class CliRunAgentResolutionTest {

  @Test
  fun `unflagged delegated run honors the saved legacy agent`() {
    assertEquals(
      AgentImplementation.TRAILBLAZE_RUNNER,
      assertIs<CliRunAgentResolution.Resolved>(
          resolveRunAgentImplementation(
            requestedAgent = null,
            savedAgent = AgentImplementation.TRAILBLAZE_RUNNER,
          ),
        )
        .agentImplementation,
    )
  }

  @Test
  fun `a run with neither a request nor a saved choice gets the framework default`() {
    // The saved agent is a tri-state: null means the user never picked one, and absence must
    // resolve to the current framework default rather than a frozen historical one.
    assertEquals(
      AgentImplementation.DEFAULT,
      assertIs<CliRunAgentResolution.Resolved>(
          resolveRunAgentImplementation(requestedAgent = null, savedAgent = null),
        )
        .agentImplementation,
    )
  }

  @Test
  fun `explicit delegated agent wins over the saved agent`() {
    assertEquals(
      AgentImplementation.KOOG_STRATEGY_GRAPH,
      assertIs<CliRunAgentResolution.Resolved>(
          resolveRunAgentImplementation(
            requestedAgent = AgentImplementation.KOOG_STRATEGY_GRAPH.name,
            savedAgent = AgentImplementation.TRAILBLAZE_RUNNER,
          ),
        )
        .agentImplementation,
    )
  }

  @Test
  fun `unrecognized delegated agent names the invalid value and valid options`() {
    val resolution =
      assertIs<CliRunAgentResolution.Unrecognized>(
        resolveRunAgentImplementation(
          requestedAgent = "KOOG_TYPO",
          savedAgent = AgentImplementation.MULTI_AGENT_V3,
        ),
      )

    assertTrue(resolution.message.contains("KOOG_TYPO"))
    AgentImplementation.entries.forEach { agent ->
      assertTrue(resolution.message.contains(agent.name))
    }
  }
}

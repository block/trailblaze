package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.mcp.AgentImplementation

class InstrumentationArgUtilAgentImplementationTest {

  @Test
  fun `missing instrumentation arg uses the Koog default`() {
    assertEquals(
      AgentImplementation.KOOG_STRATEGY_GRAPH,
      InstrumentationArgUtil.parseAgentImplementation(null),
    )
  }

  @Test
  fun `explicit legacy instrumentation arg remains supported`() {
    assertEquals(
      AgentImplementation.TRAILBLAZE_RUNNER,
      InstrumentationArgUtil.parseAgentImplementation("TRAILBLAZE_RUNNER"),
    )
  }
}

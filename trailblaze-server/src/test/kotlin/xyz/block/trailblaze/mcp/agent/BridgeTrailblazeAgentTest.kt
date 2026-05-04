package xyz.block.trailblaze.mcp.agent

import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.executor.ConfigurableMockBridge
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.utils.NoOpElementComparator

class BridgeTrailblazeAgentTest {

  @Test
  fun `runTrailblazeTools forwards traceId to MCP bridge`() {
    val bridge = ConfigurableMockBridge()
    val agent = BridgeTrailblazeAgent(bridge)
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

    agent.runTrailblazeTools(
      tools = listOf(PressKeyTrailblazeTool(keyCode = PressKeyTrailblazeTool.PressKeyCode.BACK)),
      traceId = traceId,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = null,
    )

    assertEquals(traceId, bridge.lastTraceId)
  }
}

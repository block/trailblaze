package xyz.block.trailblaze

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.mcp.TrailblazeAndroidOnDeviceMcpServer

/**
 * This would be the single test that runs the MCP server on port 52526.  It blocks the instrumentation test
 * so we can send prompts/etc.
 */
class AndroidOnDeviceMcpServerTest {

  @get:Rule
  val trailblazeLoggingRule = TrailblazeAndroidLoggingRule()

  @Test
  fun mcpServer() {
    TrailblazeAndroidOnDeviceMcpServer.runSseMcpServerWithPlainConfiguration(
      port = 52526,
      wait = true,
    )
  }
}

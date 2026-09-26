package xyz.block.trailblaze.host.networkcapture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.llm.TrailblazeReferrer

/**
 * Which work may end with an empty capture: MCP work is interactive and may never open its target,
 * while a run's empty capture is missing evidence.
 */
class AndroidCaptureRequiresTrafficTest {

  @Test
  fun `MCP work does not require traffic`() {
    assertFalse(androidCaptureRequiresTraffic(TrailblazeReferrer.MCP))
  }

  @Test
  fun `a run requires traffic`() {
    assertTrue(androidCaptureRequiresTraffic(TrailblazeReferrer(id = "cli", display = "CLI")))
    assertTrue(androidCaptureRequiresTraffic(TrailblazeReferrer.YAML_TAB))
  }

  /** A referrer that crossed a serialization boundary is a different instance with the same id. */
  @Test
  fun `MCP is recognized by id`() {
    assertFalse(androidCaptureRequiresTraffic(TrailblazeReferrer(id = "mcp", display = "Other")))
  }
}

package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

class TrailCommandDeviceClassifierRejectionTest {

  @Test
  fun `ordinary runs do not require the capability`() {
    assertNull(
      TrailCommand.deviceClassifierRejection(
        requestsDeviceClassifier = false,
        daemonCapabilities = { emptySet() },
      ),
    )
  }

  @Test
  fun `capable daemon accepts locale-qualified classifier`() {
    assertNull(
      TrailCommand.deviceClassifierRejection(
        requestsDeviceClassifier = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `older daemon is rejected instead of silently selecting the physical recording`() {
    assertNotNull(
      TrailCommand.deviceClassifierRejection(
        requestsDeviceClassifier = true,
        daemonCapabilities = { emptySet() },
      ),
    )
  }
}

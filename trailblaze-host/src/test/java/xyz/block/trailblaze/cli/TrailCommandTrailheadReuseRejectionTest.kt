package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

class TrailCommandTrailheadReuseRejectionTest {

  @Test
  fun `a run that says nothing about reuse does not require the capability`() {
    assertNull(
      TrailCommand.trailheadReuseRejection(requestsTrailheadReuse = false, daemonCapabilities = { emptySet() }),
    )
  }

  @Test
  fun `a capable daemon takes the reuse answer`() {
    assertNull(
      TrailCommand.trailheadReuseRejection(
        requestsTrailheadReuse = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `an older daemon is refused instead of silently ignoring the reuse answer`() {
    assertNotNull(
      TrailCommand.trailheadReuseRejection(requestsTrailheadReuse = true, daemonCapabilities = { emptySet() }),
    )
  }
}

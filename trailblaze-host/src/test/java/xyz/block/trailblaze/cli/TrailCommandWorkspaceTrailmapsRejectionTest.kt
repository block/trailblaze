package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

class TrailCommandWorkspaceTrailmapsRejectionTest {

  @Test
  fun `a run from outside any trailmap workspace does not require the capability`() {
    assertNull(
      TrailCommand.workspaceTrailmapsRejection(
        callerHasWorkspaceTrailmaps = false,
        daemonCapabilities = { emptySet() },
      ),
    )
  }

  @Test
  fun `a capable daemon runs a workspace's trails`() {
    assertNull(
      TrailCommand.workspaceTrailmapsRejection(
        callerHasWorkspaceTrailmaps = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  /** It drops the caller's workspace from the run request and runs whatever copy it loaded. */
  @Test
  fun `an older daemon is rejected instead of running another copy of the workspace's trailmaps`() {
    assertNotNull(
      TrailCommand.workspaceTrailmapsRejection(
        callerHasWorkspaceTrailmaps = true,
        daemonCapabilities = { emptySet() },
      ),
    )
  }
}

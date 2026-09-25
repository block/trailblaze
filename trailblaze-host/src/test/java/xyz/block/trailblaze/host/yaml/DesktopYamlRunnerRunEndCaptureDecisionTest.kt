package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.RunEndCaptureAction
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.runEndCaptureAction

/**
 * Decision guard for what a finished run does with its session's capture.
 *
 * The distinction that matters: a run is not the same thing as a session. Interactive and MCP
 * sessions are many runs of one session, and a run that treats its own end as the session's end
 * stops the capture streams under a conversation that is still going — so nothing between that call
 * and the next is recorded. Whether the run owns the session end is a separate question that only
 * decides who runs the downstream finalizers.
 */
class DesktopYamlRunnerRunEndCaptureDecisionTest {

  @Test
  fun `a run that ends its session and owns the end finalizes`() {
    assertEquals(
      RunEndCaptureAction.FINALIZE,
      runEndCaptureAction(sendsSessionEndLog = true, ownsSessionEnd = true),
    )
  }

  @Test
  fun `a run that ends its session without owning the end stops capture but finalizes nothing`() {
    // The session id was discovered rather than created, so finalizing would tombstone a concurrent
    // run's registries. Its own capture still stops: the session it was running in is over.
    assertEquals(
      RunEndCaptureAction.STOP,
      runEndCaptureAction(sendsSessionEndLog = true, ownsSessionEnd = false),
    )
  }

  @Test
  fun `a run in a session that goes on leaves capture running`() {
    // Every MCP and interactive call takes this branch. Stopping here is what left the streams dark
    // between calls, so a crash or a memory spike in the gap went unrecorded.
    assertEquals(
      "owning the end does not make a continuing session end",
      RunEndCaptureAction.LEAVE_RUNNING,
      runEndCaptureAction(sendsSessionEndLog = false, ownsSessionEnd = true),
    )
    assertEquals(
      RunEndCaptureAction.LEAVE_RUNNING,
      runEndCaptureAction(sendsSessionEndLog = false, ownsSessionEnd = false),
    )
  }
}

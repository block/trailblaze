package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.model.TrailExecutionResult

/**
 * The run-dispatch glue that keys companion lifecycle events: which dispatched ids announce (and
 * to which rel path), and the exact run-finished status vocabulary. These are the M3 contract
 * promises ("primary root only", "raw YAML silent", "succeeded|failed|cancelled") - pinned here
 * so a regression in the parsing or the mapping fails a test, not just a live agent.
 */
class RunRoutesCompanionTest {

  @Test
  fun onlyPrimaryRootIdsYieldACompanionRel() {
    assertEquals("myapp/tos", companionRelFor(bundleId = "0/myapp/tos", trailId = null))
    assertEquals("myapp/tos/ios", companionRelFor(bundleId = null, trailId = "0/myapp/tos/ios"))
    // Extras roots are outside companion scope.
    assertNull(companionRelFor(bundleId = "1/myapp/tos", trailId = null))
    assertNull(companionRelFor(bundleId = "10/myapp/tos", trailId = "2/myapp/tos/ios"))
    // Raw-YAML dispatches carry no id at all - nothing to attribute, so silent.
    assertNull(companionRelFor(bundleId = null, trailId = null))
    assertNull(companionRelFor(bundleId = "", trailId = "  "))
    // Degenerate markers name no trail.
    assertNull(companionRelFor(bundleId = "0", trailId = null))
    assertNull(companionRelFor(bundleId = "0/", trailId = null))
  }

  @Test
  fun bundleIdWinsOverTrailIdButOnlyWhenItQualifies() {
    // A bundle recording sets both; the folder id (bundleId) is the announce key, not the
    // per-variant file id.
    assertEquals("myapp/tos", companionRelFor(bundleId = "0/myapp/tos", trailId = "0/myapp/tos/ios"))
    // A non-qualifying bundleId falls through to a qualifying trailId rather than silencing it -
    // including the degenerate "0/" marker that names no trail.
    assertEquals("myapp/tos/ios", companionRelFor(bundleId = "1/elsewhere", trailId = "0/myapp/tos/ios"))
    assertEquals("myapp/tos/ios", companionRelFor(bundleId = "0/", trailId = "0/myapp/tos/ios"))
  }

  @Test
  fun runFinishedStatusCoversTheContractVocabulary() {
    assertEquals("succeeded", runFinishedStatus(TrailExecutionResult.Success(null, null)))
    assertEquals("failed", runFinishedStatus(TrailExecutionResult.Failed("boom")))
    assertEquals("cancelled", runFinishedStatus(TrailExecutionResult.Cancelled))
  }
}

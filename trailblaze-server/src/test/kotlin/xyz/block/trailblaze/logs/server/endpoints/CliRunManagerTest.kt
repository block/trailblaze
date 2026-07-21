package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [CliRunManager.activeRunCount], the "is this daemon busy?" signal exposed on
 * `/cli/status` (as `activeRuns`) and logged by the shutdown endpoint. External tooling —
 * the dev launcher's stale-JAR restart, the CLI's version-mismatch restart — uses it to
 * avoid stopping a daemon mid-run, so it must count exactly the runs that a shutdown
 * would abandon: pending and running, never completed/failed/cancelled ones.
 */
class CliRunManagerTest {

  private val releaseRun = CompletableDeferred<Unit>()
  private val manager = CliRunManager(
    onRunRequest = { _, _ ->
      releaseRun.await()
      CliRunResponse(success = true)
    },
  )

  @AfterTest
  fun tearDown() {
    releaseRun.complete(Unit)
    manager.close()
  }

  @Test
  fun `in-flight run is counted until it completes`() {
    assertEquals(0, manager.activeRunCount())

    val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
    awaitUntil { manager.getStatus(runId)?.state == RunState.RUNNING }
    assertEquals(1, manager.activeRunCount())

    releaseRun.complete(Unit)
    awaitUntil { manager.getStatus(runId)?.state == RunState.COMPLETED }
    assertEquals(0, manager.activeRunCount())
  }

  @Test
  fun `active run summaries name the trail so operators can see who is using the daemon`() {
    assertEquals(emptyList(), manager.activeRunSummaries())

    val runId = manager.submitRun(CliRunRequest(trailFilePath = "trails/checkout/smoke.trail.yaml"))
    awaitUntil { manager.getStatus(runId)?.state == RunState.RUNNING }
    val summaries = manager.activeRunSummaries()
    assertEquals(1, summaries.size)
    assertTrue(summaries.single().contains("trails/checkout/smoke.trail.yaml"))

    releaseRun.complete(Unit)
    awaitUntil { manager.getStatus(runId)?.state == RunState.COMPLETED }
    assertEquals(emptyList(), manager.activeRunSummaries())
  }

  @Test
  fun `cancelled run stops counting as active`() {
    val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
    awaitUntil { manager.getStatus(runId)?.state == RunState.RUNNING }
    assertEquals(1, manager.activeRunCount())

    manager.cancelRun(runId)
    awaitUntil { manager.getStatus(runId)?.state == RunState.CANCELLED }
    assertEquals(0, manager.activeRunCount())
  }

  private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) = runBlocking {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
      kotlinx.coroutines.delay(10)
    }
  }
}

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

  /**
   * A run that dies on an [Error] rather than an [Exception] must still reach a terminal state.
   *
   * The CLI does not watch the daemon process; it polls `/cli/run-status` and abandons the run
   * only after a full no-progress window (600s). So a run left sitting in RUNNING forever is not
   * a fast failure with a bad message — it is ten minutes of a silent prompt, and then a timeout
   * that names the watchdog instead of the actual error.
   */
  @Test
  fun `a run whose body throws an Error fails instead of hanging in RUNNING`() {
    val manager = CliRunManager(onRunRequest = { _, _ -> throw NoClassDefFoundError("xyz/block/Missing") })
    try {
      val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
      awaitUntil { manager.getStatus(runId)?.state == RunState.FAILED }
      assertEquals(0, manager.activeRunCount())
      assertTrue(
        manager.getStatus(runId)?.result?.error?.contains("xyz/block/Missing") == true,
        "the error text should name what actually went wrong, not the poll watchdog",
      )
    } finally {
      manager.close()
    }
  }

  /**
   * One run's Error must not take the daemon's run scope with it. The scope is shared by every
   * later run, so a job that escapes cancels it and every subsequent `submitRun` returns a runId
   * whose body never executes — the run sits in PENDING and the CLI waits out the same window,
   * now for a trail that had nothing wrong with it.
   */
  @Test
  fun `an Error in one run does not wedge the runs submitted after it`() {
    var failNext = true
    val manager = CliRunManager(
      onRunRequest = { _, _ ->
        if (failNext) throw NoClassDefFoundError("xyz/block/Missing")
        CliRunResponse(success = true)
      },
    )
    try {
      val poisoned = manager.submitRun(CliRunRequest(trailFilePath = "poison.trail.yaml"))
      awaitUntil { manager.getStatus(poisoned)?.state == RunState.FAILED }

      failNext = false
      val healthy = manager.submitRun(CliRunRequest(trailFilePath = "healthy.trail.yaml"))
      awaitUntil { manager.getStatus(healthy)?.state == RunState.COMPLETED }
    } finally {
      manager.close()
    }
  }

  /**
   * A [StackOverflowError] carries a null message. Reporting it as "Unknown error" tells an
   * operator nothing they can act on, so the type name stands in when there is no message.
   */
  @Test
  fun `a throwable with no message is reported by type rather than as unknown`() {
    val manager = CliRunManager(onRunRequest = { _, _ -> throw StackOverflowError() })
    try {
      val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
      awaitUntil { manager.getStatus(runId)?.state == RunState.FAILED }
      assertEquals(
        "java.lang.StackOverflowError",
        manager.getStatus(runId)?.result?.error,
      )
    } finally {
      manager.close()
    }
  }

  /**
   * A local or anonymous throwable class has a null `qualifiedName` but a perfectly good JVM
   * name. Reading the Kotlin name would drop such a type back to "Unknown error" — the exact
   * outcome the type-name fallback exists to avoid.
   */
  @Test
  fun `an anonymous throwable is still named, not reported as unknown`() {
    val manager = CliRunManager(onRunRequest = { _, _ -> throw object : Throwable() {} })
    try {
      val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
      awaitUntil { manager.getStatus(runId)?.state == RunState.FAILED }
      val error = manager.getStatus(runId)?.result?.error
      assertTrue(
        error?.startsWith("xyz.block.trailblaze.logs.server.endpoints.CliRunManagerTest") == true,
        "expected a JVM type name, got: $error",
      )
    } finally {
      manager.close()
    }
  }

  /**
   * A throwable whose stack trace cannot be rendered. `stackTraceToString()` allocates, and this
   * catch handles `OutOfMemoryError` — so logging the trace before flipping the state let the
   * diagnostic itself strand the run in RUNNING, which is the hang the catch was added to fix.
   */
  private class UnrenderableTrace : Throwable("heap exhausted") {
    override fun printStackTrace(writer: java.io.PrintWriter): Unit =
      throw OutOfMemoryError("no room to render a trace")
  }

  @Test
  fun `a run still fails when rendering its stack trace throws`() {
    val manager = CliRunManager(onRunRequest = { _, _ -> throw UnrenderableTrace() })
    try {
      val runId = manager.submitRun(CliRunRequest(trailFilePath = "test.trail.yaml"))
      awaitUntil { manager.getStatus(runId)?.state == RunState.FAILED }
      assertEquals("heap exhausted", manager.getStatus(runId)?.result?.error)
      assertEquals(0, manager.activeRunCount(), "a run that failed must stop counting as active")
    } finally {
      manager.close()
    }
  }

  private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) = runBlocking {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
      kotlinx.coroutines.delay(10)
    }
  }
}

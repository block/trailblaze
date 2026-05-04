package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGroupingTest {

  @Test
  fun `latest returns the chronologically last attempt for a single-attempt group`() {
    val only = session("a", succeeded(), at(1000))
    val group = listOf(only).groupByTest().single()

    assertEquals(only, group.latest)
    assertEquals(only, group.best)
  }

  @Test
  fun `latest is the most recent attempt regardless of input order`() {
    val first = session("a", failed(), at(1000))
    val second = session("b", succeeded(), at(2000))
    val third = session("c", failed(), at(3000))

    // Feed in scrambled order — groupByTest must sort ascending so latest = last().
    val group = listOf(second, third, first).groupByTest().single()

    assertEquals(third, group.latest)
    assertEquals(listOf(first, second, third), group.allAttempts)
  }

  @Test
  fun `best prefers the latest passed attempt when any attempt passed`() {
    // Failed -> Succeeded -> Failed: best is the Succeeded run (CI-style "did it ever pass").
    val firstFail = session("1", failed(), at(1000))
    val pass = session("2", succeeded(), at(2000))
    val laterFail = session("3", failed(), at(3000))

    val group = listOf(firstFail, pass, laterFail).groupByTest().single()

    assertEquals(pass, group.best)
    assertEquals(laterFail, group.latest)
  }

  @Test
  fun `best falls back to the last attempt when no run passed`() {
    val first = session("1", maxCalls(), at(1000))
    val last = session("2", failed(), at(2000))

    val group = listOf(first, last).groupByTest().single()

    assertEquals(last, group.best)
    assertEquals(last, group.latest)
  }

  @Test
  fun `isPassed reflects the latest attempt status, not the best`() {
    // Succeeded -> Failed: latest is Failed, so isPassed must be false even though best=Succeeded.
    val pass = session("1", succeeded(), at(1000))
    val fail = session("2", failed(), at(2000))

    val group = listOf(pass, fail).groupByTest().single()

    assertEquals(pass, group.best)
    assertEquals(fail, group.latest)
    assertFalse(group.isPassed)
  }

  @Test
  fun `computeGroupedStats counts Failed then Succeeded retry as passed`() {
    val sessions = listOf(
      session("1", failed(), at(1000)),
      session("2", succeeded(), at(2000)),
    )

    val stats = sessions.groupByTest().computeGroupedStats()

    assertEquals(1, stats.uniqueTests)
    assertEquals(1, stats.passed)
    assertEquals(0, stats.failed)
    assertEquals(1, stats.retried)
  }

  @Test
  fun `computeGroupedStats counts Succeeded then Failed rerun as failed`() {
    // The PR's headline regression: with `best`-based stats this counted as passed, masking
    // a real failure in the report header.
    val sessions = listOf(
      session("1", succeeded(), at(1000)),
      session("2", failed(), at(2000)),
    )

    val stats = sessions.groupByTest().computeGroupedStats()

    assertEquals(1, stats.uniqueTests)
    assertEquals(0, stats.passed)
    assertEquals(1, stats.failed)
    assertEquals(1, stats.retried)
  }

  @Test
  fun `computeGroupedStats counts MaxCalls then Failed as failed not maxCalls`() {
    // Final attempt status wins — a MaxCalls early run that's followed by a Failed retry
    // ends up in the failed bucket, not the maxCalls bucket.
    val sessions = listOf(
      session("1", maxCalls(), at(1000)),
      session("2", failed(), at(2000)),
    )

    val stats = sessions.groupByTest().computeGroupedStats()

    assertEquals(1, stats.failed)
    assertEquals(0, stats.maxCalls)
  }

  @Test
  fun `computeGroupedStats counts pure Failed retries as failed and retried`() {
    val sessions = listOf(
      session("1", failed(), at(1000)),
      session("2", failed(), at(2000)),
    )

    val stats = sessions.groupByTest().computeGroupedStats()

    assertEquals(1, stats.failed)
    assertEquals(1, stats.retried)
    assertEquals(0, stats.passed)
  }

  @Test
  fun `groupByTest keeps same trail on different classifiers in separate groups`() {
    val ipad = session("1", failed(), at(1000), classifiers = listOf("ios", "ipad"))
    val iphone = session("2", failed(), at(2000), classifiers = listOf("ios", "iphone"))

    val groups = listOf(ipad, iphone).groupByTest()

    assertEquals(2, groups.size)
    assertTrue(groups.all { it.allAttempts.size == 1 })
  }

  @Test
  fun `groupByTest collapses same trail and classifiers across multiple runs`() {
    val first = session("1", failed(), at(1000), classifiers = listOf("ios", "ipad"))
    val second = session("2", failed(), at(2000), classifiers = listOf("ios", "ipad"))

    val groups = listOf(first, second).groupByTest()

    assertEquals(1, groups.size)
    assertEquals(2, groups.single().allAttempts.size)
    assertTrue(groups.single().wasRetried)
  }

  // --- helpers ---

  private fun at(epochMs: Long): Instant = Instant.fromEpochMilliseconds(epochMs)

  private fun succeeded() = SessionStatus.Ended.Succeeded(durationMs = 1_000L)
  private fun failed() = SessionStatus.Ended.Failed(durationMs = 1_000L, exceptionMessage = null)
  private fun maxCalls() = SessionStatus.Ended.MaxCallsLimitReached(
    durationMs = 1_000L,
    maxCalls = 50,
    objectivePrompt = "test",
  )

  private fun session(
    id: String,
    status: SessionStatus,
    timestamp: Instant,
    trailFilePath: String? = "trails/example.trail.yaml",
    classifiers: List<String> = listOf("ios"),
  ): SessionInfo = SessionInfo(
    sessionId = SessionId(id),
    latestStatus = status,
    timestamp = timestamp,
    durationMs = 1_000L,
    trailFilePath = trailFilePath,
    hasRecordedSteps = false,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      widthPixels = 100,
      heightPixels = 200,
      classifiers = classifiers.map { TrailblazeDeviceClassifier(it) },
    ),
  )
}

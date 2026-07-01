package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.waypoint.WaypointAssertion

/**
 * Pins the `assertWaypoint` tool's observable contract: its construction-time validation and the
 * verdict polarity of its [WaypointAssertion.Result] -> [TrailblazeToolResult] mapping (a match is
 * the only success; every other outcome is an error so a failed assertion fails the step). The
 * poll-until-match behavior itself is covered by `WaypointAssertionTest`.
 */
class AssertWaypointTrailblazeToolTest {

  @Test
  fun `matched result maps to Success`() {
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/home")
    val result = tool.toToolResult(
      WaypointAssertion.Result.Matched("app/home", matchResult("app/home", matched = true)),
    )
    assertTrue(result is TrailblazeToolResult.Success, "match must be a Success, got $result")
  }

  @Test
  fun `not-matched result maps to Error naming the waypoint`() {
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/home")
    val result = tool.toToolResult(
      WaypointAssertion.Result.NotMatched(
        definitionId = "app/home",
        lastResult = matchResult("app/home", matched = false),
        timeoutMs = 1_000L,
      ),
    )
    assertTrue(result is TrailblazeToolResult.Error, "mismatch must fail the step, got $result")
    assertTrue(result.errorMessage.contains("app/home"), "error should name the waypoint: ${result.errorMessage}")
  }

  @Test
  fun `unknown-waypoint result maps to Error naming the requested id`() {
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/nope")
    val result = tool.toToolResult(WaypointAssertion.Result.WaypointNotFound("app/nope"))
    assertTrue(result is TrailblazeToolResult.Error, "unknown waypoint must fail, got $result")
    assertTrue(result.errorMessage.contains("app/nope"), "error should name the id: ${result.errorMessage}")
  }

  @Test
  fun `no-screen-state result maps to Error`() {
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/home")
    val result = tool.toToolResult(WaypointAssertion.Result.NoScreenState("app/home", timeoutMs = 1_000L))
    assertTrue(result is TrailblazeToolResult.Error, "missing screen state must fail, got $result")
  }

  @Test
  fun `execute without a screen-state provider returns an Error`() = runBlocking {
    // The guard runs before any registry resolution, so this exercises only the missing-provider
    // path — no waypoint registry is touched. assertWaypoint is host-backed; a run path that
    // doesn't supply a live screen source must fail loudly rather than vacuously pass.
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/home")
    val result = tool.execute(contextWithoutScreenStateProvider())
    assertTrue(result is TrailblazeToolResult.Error, "expected Error, got $result")
    assertTrue(
      result.errorMessage.contains("screen-state provider"),
      "error should name the missing screen-state provider: ${result.errorMessage}",
    )
  }

  @Test
  fun `blank waypoint id fails construction`() {
    assertFailsWith<IllegalArgumentException> { AssertWaypointTrailblazeTool(waypoint = "  ") }
  }

  @Test
  fun `non-positive timeout fails construction`() {
    assertFailsWith<IllegalArgumentException> {
      AssertWaypointTrailblazeTool(waypoint = "app/home", timeoutMs = 0L)
    }
  }

  @Test
  fun `non-positive poll interval fails construction`() {
    assertFailsWith<IllegalArgumentException> {
      AssertWaypointTrailblazeTool(waypoint = "app/home", pollIntervalMs = -1L)
    }
  }

  @Test
  fun `default timeout and poll interval match the shared constants`() {
    val tool = AssertWaypointTrailblazeTool(waypoint = "app/home")
    assertEquals(WaypointAssertion.DEFAULT_TIMEOUT_MS, tool.timeoutMs)
    assertEquals(WaypointAssertion.DEFAULT_POLL_INTERVAL_MS, tool.pollIntervalMs)
  }

  private fun matchResult(id: String, matched: Boolean) = WaypointMatchResult(
    definitionId = id,
    matched = matched,
    matchedRequired = emptyList(),
    missingRequired = emptyList(),
    presentForbidden = emptyList(),
  )

  /** A minimal execution context whose `screenStateProvider` is left null (the default). */
  private fun contextWithoutScreenStateProvider() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}

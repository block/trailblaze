package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the pre-dispatch guards on [TapOnAccessibilityNodeTrailblazeTool]: rejects calls
 * with both regexes blank, rejects non-Android platforms, and rejects sessions whose
 * runtime agent doesn't advertise `usesAccessibilityDriver`. Each guard returns a typed
 * `Error.ExceptionThrown` with a remediation hint so the failure shows up at the script
 * author's call site rather than as a confused selector miss at dispatch time.
 */
class TapOnAccessibilityNodeTrailblazeToolTest {

  @Test
  fun `rejects when both contentDescriptionRegex and textRegex are blank`(): Unit = runBlocking {
    val tool = TapOnAccessibilityNodeTrailblazeTool()
    val context = androidContext(agent = AccessibilityCapableAgent())

    val result = tool.execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("at least one of contentDescriptionRegex or textRegex"),
      "Expected validator error, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `rejects when both regexes are whitespace-only`(): Unit = runBlocking {
    val tool = TapOnAccessibilityNodeTrailblazeTool(
      contentDescriptionRegex = "   ",
      textRegex = "\t\n",
    )
    val context = androidContext(agent = AccessibilityCapableAgent())

    val result = tool.execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("at least one of contentDescriptionRegex or textRegex"),
      "Expected validator error, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `rejects when platform is not Android`(): Unit = runBlocking {
    val tool = TapOnAccessibilityNodeTrailblazeTool(contentDescriptionRegex = "^1$")
    val context = nonAndroidContext(agent = AccessibilityCapableAgent())

    val result = tool.execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("only runs on Android"),
      "Expected platform-guard error, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `rejects when maestroTrailblazeAgent is null`(): Unit = runBlocking {
    val tool = TapOnAccessibilityNodeTrailblazeTool(contentDescriptionRegex = "^1$")
    val context = androidContext(agent = null)

    val result = tool.execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("requires the Android accessibility driver"),
      "Expected accessibility-driver error, got: ${error.errorMessage}",
    )
    assertTrue(
      error.errorMessage.contains("null"),
      "Expected null-agent diagnostic, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `rejects when agent does not advertise usesAccessibilityDriver`(): Unit = runBlocking {
    val tool = TapOnAccessibilityNodeTrailblazeTool(textRegex = "^1$")
    // NonAccessibilityAgent inherits the default `usesAccessibilityDriver = false` from
    // MaestroTrailblazeAgent — the production path that would silently lower the selector
    // to Maestro coordinate math if this guard weren't in place.
    val context = androidContext(agent = NonAccessibilityAgent())

    val result = tool.execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("requires the Android accessibility driver"),
      "Expected accessibility-driver error, got: ${error.errorMessage}",
    )
    assertTrue(
      error.errorMessage.contains("NonAccessibilityAgent"),
      "Expected agent class name in diagnostic, got: ${error.errorMessage}",
    )
  }

  // region fixtures

  private fun androidContext(agent: MaestroTrailblazeAgent?): TrailblazeToolExecutionContext =
    buildContext(
      platform = TrailblazeDevicePlatform.ANDROID,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      agent = agent,
    )

  private fun nonAndroidContext(agent: MaestroTrailblazeAgent?): TrailblazeToolExecutionContext =
    buildContext(
      platform = TrailblazeDevicePlatform.IOS,
      driverType = TrailblazeDriverType.IOS_HOST,
      agent = agent,
    )

  private fun buildContext(
    platform: TrailblazeDevicePlatform,
    driverType: TrailblazeDriverType,
    agent: MaestroTrailblazeAgent?,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test",
        trailblazeDevicePlatform = platform,
      ),
      trailblazeDriverType = driverType,
      widthPixels = 1000,
      heightPixels = 1000,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  /**
   * Agent that defaults to `usesAccessibilityDriver = false` — the Maestro-lowered runtime
   * path where the platform guard passes but the dispatcher would mis-target.
   */
  private class NonAccessibilityAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1000,
        heightPixels = 1000,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
    },
  ) {
    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  /** Agent that advertises `usesAccessibilityDriver = true` so the positive-guard branches pass. */
  private class AccessibilityCapableAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1000,
        heightPixels = 1000,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
    },
  ) {
    override val usesAccessibilityDriver: Boolean = true

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  // endregion
}

package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Contract tests for the keyboard-dismissal tools: which framework commands reach the device for
 * a given platform, sourced from static device info. Platform selection must never read
 * `context.screenState` — under lazy capture that read costs a full screen capture per call, and
 * a null read used to silently pick the Android dismissal on iOS
 * (https://github.com/block/trailblaze/issues/210).
 */
class HideKeyboardTrailblazeToolTest {

  /**
   * Minimal concrete [MaestroTrailblazeAgent] capturing dispatched commands — mirrors
   * `MaestroNestedToolCompositionTest.FixtureAgent`.
   */
  private class CommandCapturingAgent(
    driverType: TrailblazeDriverType,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = driverType.platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val dispatched = mutableListOf<Command>()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      dispatched += commands
      return TrailblazeToolResult.Success()
    }
  }

  private fun contextFor(agent: CommandCapturingAgent) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
    sessionProvider = agent.sessionProvider,
    // Reading `screenState` is a full device capture in production — fail loudly if any
    // keyboard-dismissal path touches it.
    screenStateProvider = { throw AssertionError("keyboard-dismissal paths must not capture screen state") },
    trailblazeLogger = agent.trailblazeLogger,
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  @Test
  fun `android dismisses via the native HideKeyboardCommand`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

      val result = HideKeyboardTrailblazeTool.execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(agent.dispatched).hasSize(1)
      assertThat(agent.dispatched.single()).isInstanceOf(HideKeyboardCommand::class)
    }
  }

  @Test
  fun `iOS dismisses via a gentle scroll instead of the native command`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.IOS_HOST)

      val result = HideKeyboardTrailblazeTool.execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(agent.dispatched).hasSize(1)
      assertThat(agent.dispatched.single()).isInstanceOf(SwipeCommand::class)
    }
  }

  @Test
  fun `inputText appends the platform's keyboard dismissal by default`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

      val result = InputTextTrailblazeTool(text = "hello").execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(agent.dispatched).hasSize(2)
      assertThat((agent.dispatched.first() as InputTextCommand).text).isEqualTo("hello")
      assertThat(agent.dispatched.last()).isInstanceOf(HideKeyboardCommand::class)
    }
  }

  @Test
  fun `inputText with hideKeyboardAfter=false types without dismissing`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

      val result = InputTextTrailblazeTool(text = "hello", hideKeyboardAfter = false)
        .execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(agent.dispatched).hasSize(1)
      assertThat(agent.dispatched.single()).isInstanceOf(InputTextCommand::class)
    }
  }
}

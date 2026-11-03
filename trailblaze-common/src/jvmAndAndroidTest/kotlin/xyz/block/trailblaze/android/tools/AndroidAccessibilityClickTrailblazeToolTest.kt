package xyz.block.trailblaze.android.tools

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** `android_accessibilityClick` is the only way a trail pins ACTION_CLICK, and only on Android. */
class AndroidAccessibilityClickTrailblazeToolTest {

  private data class Tap(val longPress: Boolean, val tapRoute: TapRouteOverride?, val timeoutMs: Long?)

  private class RecordingAgent(
    driverType: TrailblazeDriverType,
    override val usesAccessibilityDriver: Boolean,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId = "fixture", trailblazeDevicePlatform = driverType.platform),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val taps = mutableListOf<Tap>()

    override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult =
      error("android_accessibilityClick must not fall back to Maestro")

    override suspend fun executeNodeSelectorTap(
      nodeSelector: TrailblazeNodeSelector,
      longPress: Boolean,
      tapRoute: TapRouteOverride?,
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      taps += Tap(longPress, tapRoute, timeoutMs)
      return TrailblazeToolResult.Success()
    }
  }

  private val fileRow = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com.android.documentsui:id/item_root"),
  )

  private fun run(agent: RecordingAgent, selector: TrailblazeNodeSelector = fileRow) = runBlocking {
    AndroidAccessibilityClickTrailblazeTool(nodeSelector = selector, timeoutMs = 5_000).execute(
      TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
        sessionProvider = agent.sessionProvider,
        trailblazeLogger = agent.trailblazeLogger,
        memory = AgentMemory(),
        maestroTrailblazeAgent = agent,
      ),
    )
  }

  @Test
  fun `pins ACTION_CLICK on the accessibility driver`() {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, usesAccessibilityDriver = true)
    assertThat(run(agent)).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(agent.taps).isEqualTo(listOf(Tap(longPress = false, TapRouteOverride.ACTION_CLICK, 5_000)))
  }

  /** A row picked out by its child has no root match; the accessibility match is nested. */
  @Test
  fun `accepts a selector whose accessibility match is nested`() {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, usesAccessibilityDriver = true)
    val nested = TrailblazeNodeSelector(
      containsChild = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "All add-ons")),
    )
    assertThat(run(agent, nested)).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(agent.taps).isEqualTo(listOf(Tap(longPress = false, TapRouteOverride.ACTION_CLICK, 5_000)))
  }

  @Test
  fun `fails off Android rather than tapping some other way`() {
    val agent = RecordingAgent(TrailblazeDriverType.IOS_HOST, usesAccessibilityDriver = true)
    assertThat(run(agent)).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(agent.taps).isEmpty()
  }

  @Test
  fun `fails on an Android driver without accessibility routing`() {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, usesAccessibilityDriver = false)
    assertThat(run(agent)).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(agent.taps).isEmpty()
  }

  @Test
  fun `fails on a selector without an androidAccessibility match`() {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, usesAccessibilityDriver = true)
    val iosSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Row"))
    assertThat(run(agent, iosSelector)).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(agent.taps).isEmpty()
  }
}

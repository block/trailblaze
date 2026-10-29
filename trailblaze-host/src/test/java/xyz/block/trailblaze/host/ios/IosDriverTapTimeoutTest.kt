package xyz.block.trailblaze.host.ios

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeDeviceManager
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import kotlin.test.Test

/**
 * A recorded `tapOnElementBySelector` with `timeoutMs` waits that long for its target, from the
 * tool down through the agent into the AXe driver's poll — and fails once it has passed.
 */
class IosDriverTapTimeoutTest {

  private val pay = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "^Pay$"))

  /** A screen that shows "Loading" until [appearsAfterMs] have passed, then the Pay button. */
  private inner class LateButtonDevice(private val appearsAfterMs: Long) {
    private val startedAt = System.currentTimeMillis()
    val taps = mutableListOf<Pair<Int, Int>>()

    val manager = AxeDeviceManager(
      udid = "test-udid",
      deviceWidth = 402,
      deviceHeight = 874,
      describeUi = {
        val label = if (System.currentTimeMillis() - startedAt >= appearsAfterMs) "Pay" else "Loading"
        AxeCli.Result(exitCode = 0, stdout = screen(label), stderr = "")
      },
      tapXy = { x, y ->
        taps += x to y
        AxeCli.Result(exitCode = 0, stdout = "", stderr = "")
      },
    )
  }

  private fun tap(device: LateButtonDevice, timeoutMs: Long): TrailblazeToolResult {
    val agent = IosDriverTrailblazeAgent(
      deviceManager = device.manager,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = { TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()) },
      logDriverActions = false,
    )
    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = { TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()) },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
    )
    return runBlocking { TapOnByElementSelector(nodeSelector = pay, timeoutMs = timeoutMs).execute(context) }
  }

  @Test
  fun `the tap waits for an element that renders late, within its timeoutMs`() {
    val device = LateButtonDevice(appearsAfterMs = 1_200)

    val result = tap(device, timeoutMs = 4_000)

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    // The Pay button's center in [screen].
    assertThat(device.taps).isEqualTo(listOf(201 to 322))
  }

  @Test
  fun `the tap fails once its timeoutMs passes, without tapping`() {
    // The button arrives after the budget but well inside the driver's 5s default, so a tap that
    // ignored timeoutMs would find it and succeed.
    val device = LateButtonDevice(appearsAfterMs = 2_500)

    val startedAt = System.currentTimeMillis()
    val result = tap(device, timeoutMs = 500)
    val elapsed = System.currentTimeMillis() - startedAt

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(device.taps).isEmpty()
    assertThat(elapsed).isBetween(500L, 2_499L)
  }

  private companion object {
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-ios-sim",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
      widthPixels = 402,
      heightPixels = 874,
    )

    fun screen(label: String) = """
      [
        {
          "role": "AXApplication",
          "type": "Application",
          "AXLabel": "Shop",
          "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
          "children": [
            {
              "role": "AXButton",
              "type": "Button",
              "AXLabel": "$label",
              "frame": {"x": 16, "y": 300, "width": 370, "height": 44}
            }
          ]
        }
      ]
    """.trimIndent()
  }
}

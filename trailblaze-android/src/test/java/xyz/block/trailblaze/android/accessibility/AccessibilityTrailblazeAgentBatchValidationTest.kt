package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.InputTextCommand
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * Pins [AccessibilityTrailblazeAgent.runMaestroCommands]'s whole-batch validation — the entry
 * point every tool call site (`MapsToMaestroCommands`, `MaestroTrailblazeTool`, etc.) lands on.
 * An unconvertible command anywhere in the batch must fail the call BEFORE any command
 * dispatches; the base per-command loop would otherwise execute earlier commands' side effects
 * first. The failure path never touches the device, so this runs as a plain JVM test; the
 * conversion contract itself is covered at the converter seam
 * ([MaestroCommandConverterBatchTest]).
 */
class AccessibilityTrailblazeAgentBatchValidationTest {

  private fun buildAgent(): AccessibilityTrailblazeAgent = AccessibilityTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-android-emulator",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = { TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()) },
    trailblazeToolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(name = "Test Tools", toolClasses = emptySet()),
    ),
  )

  @Test
  fun `a batch with an unsupported command after a convertible one fails before any dispatch`() {
    val result = runBlocking {
      buildAgent().runMaestroCommands(
        maestroCommands = listOf(
          InputTextCommand(text = "abc"),
          // No points and no direction — unsupported by the accessibility driver. Placed AFTER
          // a convertible command: without whole-batch validation, the input would dispatch
          // (executing its side effects) before this command failed conversion.
          SwipeCommand(duration = 100L),
        ),
        traceId = null,
      )
    }
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(error.errorMessage.contains("SwipeCommand"))
    assertFalse(error.stackTrace.isNullOrBlank(), "conversion failures must carry a stack trace")
  }

  @Test
  fun `an empty batch succeeds without touching the device`() {
    val result = runBlocking { buildAgent().runMaestroCommands(emptyList(), traceId = null) }
    assertIs<TrailblazeToolResult.Success>(result)
  }
}

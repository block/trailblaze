package xyz.block.trailblaze.toolcalls.commands.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
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
import xyz.block.trailblaze.toolcalls.toLogPayload

class RememberSecretValueTrailblazeToolTest {
  @Test
  fun `generated setup values interpolate but stay redacted`() = runBlocking {
    val memory = AgentMemory()
    val tool =
      RememberSecretValueTrailblazeTool(
        variable = "generatedToken",
        value = "generated-test-secret",
      )
    val result = assertIs<TrailblazeToolResult.Success>(tool.execute(context(memory)))

    assertEquals("generated-test-secret", memory.interpolateVariables("{{generatedToken}}"))
    assertTrue("generatedToken" in memory.sensitiveKeys)
    assertFalse(result.message.orEmpty().contains("generated-test-secret"))
    assertFalse(tool.toLogPayload().toString().contains("generated-test-secret"))

    memory.remember("generatedToken", "replacement-secret")
    assertTrue("generatedToken" in memory.sensitiveKeys)
  }

  @Test
  fun `blank key fails without remembering a secret`() = runBlocking {
    val memory = AgentMemory()
    assertIs<TrailblazeToolResult.Error>(
      RememberSecretValueTrailblazeTool(variable = " ", value = "test-secret")
        .execute(context(memory))
    )
    assertTrue(memory.variables.isEmpty())
  }

  private fun context(memory: AgentMemory) =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo =
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId("test-device", TrailblazeDevicePlatform.ANDROID),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          widthPixels = 1080,
          heightPixels = 1920,
        ),
      sessionProvider =
        TrailblazeSessionProvider {
          TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
        },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = memory,
    )
}

package xyz.block.trailblaze.toolcalls.commands.memory

import kotlin.test.Test
import kotlin.test.assertEquals
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

/** After `execute`, the variable holds exactly the given value; a call that can't be recalled fails. */
class RememberValueTrailblazeToolTest {

  @Test
  fun `remembers the value exactly as given`() = runBlocking {
    val memory = AgentMemory()
    val result = RememberValueTrailblazeTool(variable = "squareSignInRetry", value = " true ").execute(context(memory))
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(" true ", memory.variables["squareSignInRetry"])
  }

  @Test
  fun `overwrites an earlier value`() = runBlocking {
    val memory = AgentMemory().apply { remember("flag", "false") }
    RememberValueTrailblazeTool(variable = "flag", value = "true").execute(context(memory))
    assertEquals("true", memory.variables["flag"])
  }

  @Test
  fun `blank variable fails loudly and remembers nothing`() = runBlocking {
    val memory = AgentMemory()
    val result = RememberValueTrailblazeTool(variable = "  ", value = "true").execute(context(memory))
    assertTrue(result is TrailblazeToolResult.Error, "a blank variable must be an error, but was $result")
    assertTrue(memory.variables.isEmpty(), "nothing should be remembered on a blank-variable error")
  }

  @Test
  fun `redacts the result when replacing a sensitive variable`() = runBlocking {
    val memory = AgentMemory().apply { rememberSensitive("token", "old") }
    val result = RememberValueTrailblazeTool(variable = "token", value = "new-secret").execute(context(memory))
    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("new-secret", memory.variables["token"])
    assertTrue(success.message?.contains("[REDACTED]") == true)
    assertTrue(success.message?.contains("new-secret") == false)
  }

  @Test
  fun `a value copied from a sensitive variable stays sensitive under the new key`() = runBlocking {
    // What the dispatch boundary hands execute for `value: '{{pin}}'`: the resolved secret.
    val memory = AgentMemory().apply { rememberSensitive("pin", "4821") }
    val result = RememberValueTrailblazeTool(variable = "copy", value = "pin=4821").execute(context(memory))
    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("pin=4821", memory.variables["copy"])
    assertTrue("copy" in memory.sensitiveKeys, "the copy must be marked sensitive")
    assertTrue(success.message?.contains("4821") == false)
  }

  private fun context(memory: AgentMemory): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
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
    memory = memory,
  )
}

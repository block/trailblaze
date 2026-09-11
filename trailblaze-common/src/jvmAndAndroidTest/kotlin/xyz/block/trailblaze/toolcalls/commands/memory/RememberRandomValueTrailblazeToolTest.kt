package xyz.block.trailblaze.toolcalls.commands.memory

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

/**
 * The observable contract of the non-typing generator is: after `execute`, the requested variable
 * holds a freshly-generated value of the requested shape. The pure digit generation is already
 * pinned by `InputTextRandomTrailblazeToolTest`; here we assert the remember-and-recall behavior
 * (and that a call that can't produce a recallable value fails loudly instead of silently).
 */
class RememberRandomValueTrailblazeToolTest {

  @Test
  fun `remembers a prefix-plus-decimal-digits value under the variable`() {
    val memory = AgentMemory()
    val result = runBlocking {
      RememberRandomValueTrailblazeTool(prefix = "TeamUploadDoc-", digitCount = 6, variable = "upload_name")
        .execute(context(memory))
    }
    assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
    val remembered = memory.variables["upload_name"]
    assertTrue(
      remembered != null && remembered.matches(Regex("^TeamUploadDoc-[0-9]{6}$")),
      "unexpected remembered value: $remembered",
    )
  }

  @Test
  fun `honors hex and suffix`() {
    val memory = AgentMemory()
    runBlocking {
      RememberRandomValueTrailblazeTool(prefix = "", digitCount = 8, suffix = "@example.com", hex = true, variable = "v")
        .execute(context(memory))
    }
    assertTrue(
      memory.variables["v"]?.matches(Regex("^[0-9a-f]{8}@example\\.com$")) == true,
      "unexpected remembered value: ${memory.variables["v"]}",
    )
  }

  @Test
  fun `generates varied values across runs`() {
    val values = (1..50).map {
      val memory = AgentMemory()
      runBlocking {
        RememberRandomValueTrailblazeTool(prefix = "TeamUploadDoc-", digitCount = 6, variable = "n").execute(context(memory))
      }
      memory.variables["n"]!!
    }
    assertTrue(values.toSet().size > 1, "expected varied values across runs, got ${values.toSet()}")
    assertTrue(values.all { it.matches(Regex("^TeamUploadDoc-[0-9]{6}$")) })
  }

  @Test
  fun `blank variable fails loudly and remembers nothing`() {
    val memory = AgentMemory()
    val result = runBlocking {
      RememberRandomValueTrailblazeTool(prefix = "TeamUploadDoc-", digitCount = 6, variable = "  ").execute(context(memory))
    }
    assertTrue(result is TrailblazeToolResult.Error, "a blank variable must be an error, but was $result")
    assertTrue(memory.variables.isEmpty(), "nothing should be remembered on a blank-variable error")
  }

  @Test
  fun `non-positive digitCount fails loudly and remembers nothing`() {
    val memory = AgentMemory()
    val result = runBlocking {
      RememberRandomValueTrailblazeTool(prefix = "TeamUploadDoc-", digitCount = 0, variable = "upload_name").execute(context(memory))
    }
    assertTrue(result is TrailblazeToolResult.Error, "digitCount 0 must be an error, but was $result")
    assertNull(memory.variables["upload_name"], "nothing should be remembered on a digitCount error")
  }

  @Test
  fun `redacts the result when replacing a sensitive variable`() = runBlocking {
    val memory = AgentMemory().apply { rememberSensitive("token", "old") }

    val result = RememberRandomValueTrailblazeTool(prefix = "secret-", digitCount = 6, variable = "token")
      .execute(context(memory))

    val success = assertIs<TrailblazeToolResult.Success>(result)
    val remembered = assertNotNull(memory.variables["token"])
    assertTrue(remembered.matches(Regex("^secret-[0-9]{6}$")))
    assertTrue(success.message?.contains("[REDACTED]") == true)
    assertTrue(success.message?.contains(remembered) == false)
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

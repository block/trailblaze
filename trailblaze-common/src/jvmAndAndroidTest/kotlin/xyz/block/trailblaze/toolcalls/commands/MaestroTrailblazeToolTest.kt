package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import org.junit.Test
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
import xyz.block.trailblaze.utils.Ext.asJsonObjects

/**
 * Tests for [MaestroTrailblazeTool] focusing on:
 * - Detecting and reporting command deserialization failures instead of silently dropping them
 */
class MaestroTrailblazeToolTest {

  private val testAgent =
    object :
      MaestroTrailblazeAgent(
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        trailblazeDeviceInfoProvider = {
          TrailblazeDeviceInfo(
            trailblazeDeviceId =
              TrailblazeDeviceId(
                instanceId = "test",
                trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
              ),
            trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
            widthPixels = 1080,
            heightPixels = 1920,
          )
        },
        sessionProvider =
          TrailblazeSessionProvider {
            TrailblazeSession(
              sessionId = SessionId("test-session"),
              startTime = Clock.System.now(),
            )
          },
      ) {
      override suspend fun executeMaestroCommands(
        commands: List<Command>,
        traceId: TraceId?,
      ): TrailblazeToolResult = TrailblazeToolResult.Success()
    }

  private fun createContext(): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = testAgent.trailblazeDeviceInfoProvider(),
      sessionProvider = testAgent.sessionProvider,
      trailblazeLogger = testAgent.trailblazeLogger,
      memory = testAgent.memory,
      maestroTrailblazeAgent = testAgent,
    )
  }

  @Test
  fun `valid command round-trips through serialization and executes successfully`() {
    runBlocking {
      // Create a command via the real Maestro API, serialize to JSON, and verify round-trip
      val command = TapOnElementCommand(selector = ElementSelector(textRegex = "OK"))
      val jsonObjects = listOf(command).asJsonObjects()
      val tool = MaestroTrailblazeTool(commands = jsonObjects)

      val result = tool.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    }
  }

  @Test
  fun `invalid command returns error instead of being silently dropped`() {
    runBlocking {
      // This JSON doesn't match any known Maestro command structure
      val invalidCommand = buildJsonObject {
        put(
          "totallyFakeCommand",
          buildJsonObject { put("bogus", JsonPrimitive("value")) },
        )
      }
      val tool = MaestroTrailblazeTool(commands = listOf(invalidCommand))

      val result = tool.execute(createContext())

      // Before the fix, this would return Success with 0 commands executed.
      // After the fix, it should return an error because the command failed to deserialize.
      assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
      val errorMessage = (result as TrailblazeToolResult.Error).errorMessage
      assertThat(errorMessage).contains("failed to deserialize")
    }
  }

  @Test
  fun `mix of valid and invalid commands returns error`() {
    runBlocking {
      // Create a valid command via Maestro API, and an invalid one as raw JSON
      val validJsonObjects = listOf(
        TapOnElementCommand(selector = ElementSelector(textRegex = "OK")),
      ).asJsonObjects()
      val invalidCommand = buildJsonObject {
        put(
          "notARealCommand",
          buildJsonObject { put("x", JsonPrimitive(42)) },
        )
      }
      val tool =
        MaestroTrailblazeTool(commands = validJsonObjects + listOf(invalidCommand))

      val result = tool.execute(createContext())

      // Should detect that 1 of 2 commands failed to deserialize
      assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
      val errorMessage = (result as TrailblazeToolResult.Error).errorMessage
      assertThat(errorMessage).contains("1 of 2")
    }
  }

  @Test
  fun `empty commands list returns success`() {
    runBlocking {
      val tool = MaestroTrailblazeTool(commands = emptyList())

      val result = tool.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    }
  }
}

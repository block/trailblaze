package xyz.block.trailblaze.llm

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.TrailblazeConfig

class RunYamlRequestAgentImplementationSerializationTest {

  private val json = TrailblazeJson.defaultWithoutToolsInstance

  private val request =
    RunYamlRequest(
      testName = "test",
      yaml = "",
      trailFilePath = null,
      targetAppName = null,
      useRecordedSteps = false,
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
      trailblazeLlmModel =
        TrailblazeLlmModel(
          trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
          modelId = "test-model",
          inputCostPerOneMillionTokens = 0.0,
          outputCostPerOneMillionTokens = 0.0,
          contextLength = 1000,
          maxOutputTokens = 1000,
          capabilityIds = emptyList(),
        ),
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
    )

  @Test
  fun `the agent is always encoded on the wire`() {
    // A receiver built when the agent was selectable reads a missing field as its own default,
    // which for older builds was the removed legacy runner.
    val encoded = json.encodeToString(request)

    assertTrue(encoded.contains("\"agentImplementation\": \"KOOG_STRATEGY_GRAPH\""))
  }

  @Test
  fun `removed agent names still decode`() {
    // Payloads from older senders carry these names; they must keep decoding rather than fail
    // the whole request.
    listOf("MULTI_AGENT_V3", "TRAILBLAZE_RUNNER").forEach { removedName ->
      val encoded =
        JsonObject(
          json.parseToJsonElement(json.encodeToString(request)).jsonObject +
            ("agentImplementation" to JsonPrimitive(removedName)),
        )
          .toString()

      assertEquals(
        AgentImplementation.KOOG_STRATEGY_GRAPH,
        json.decodeFromString<RunYamlRequest>(encoded).agentImplementation,
        removedName,
      )
    }
  }

  @Test
  fun `unknown wire names decode to the one agent`() {
    listOf("TRAILBLAZE_RUNNER", "MULTI_AGENT_V3", "", null).forEach { name ->
      assertEquals(AgentImplementation.KOOG_STRATEGY_GRAPH, AgentImplementation.fromWireName(name), "$name")
    }
  }
}

package xyz.block.trailblaze.llm

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
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
  fun `default agent implementation is always encoded on the wire`() {
    val encoded = json.encodeToString(request)

    assertTrue(encoded.contains("\"agentImplementation\": \"KOOG_STRATEGY_GRAPH\""))
  }

  @Test
  fun `absent agent implementation decodes to the receiver default`() {
    val encodedWithoutAgent =
      JsonObject(
        json.parseToJsonElement(json.encodeToString(request)).jsonObject - "agentImplementation",
      )
        .toString()

    assertEquals(
      AgentImplementation.DEFAULT,
      json.decodeFromString<RunYamlRequest>(encodedWithoutAgent).agentImplementation,
    )
  }
}

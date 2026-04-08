package xyz.block.trailblaze.llm.config

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

class OllamaModelDiscoveryTest {

  @Test
  fun `parseOllamaListOutput parses standard output`() {
    val output = """
      NAME                    ID              SIZE      MODIFIED
      qwen3-vl:8b             abc123def456    4.4 GB    2 days ago
      llama3.2:latest         def789abc012    2.1 GB    5 hours ago
    """.trimIndent()

    val models = OllamaModelDiscovery.parseOllamaListOutput(output)

    assertEquals(2, models.size)
    assertEquals("qwen3-vl:8b", models[0].modelId)
    assertEquals("llama3.2:latest", models[1].modelId)
    models.forEach { model ->
      assertEquals(TrailblazeLlmProvider.OLLAMA, model.trailblazeLlmProvider)
      assertEquals(0.0, model.inputCostPerOneMillionTokens)
      assertEquals(0.0, model.outputCostPerOneMillionTokens)
      assertTrue(model.contextLength > 0)
      assertTrue(model.maxOutputTokens > 0)
    }
  }

  @Test
  fun `parseOllamaListOutput handles empty output`() {
    val output = "NAME                    ID              SIZE      MODIFIED"
    val models = OllamaModelDiscovery.parseOllamaListOutput(output)
    assertTrue(models.isEmpty())
  }

  @Test
  fun `parseOllamaListOutput handles blank lines`() {
    val output = """
      NAME                    ID              SIZE      MODIFIED
      qwen3-vl:8b             abc123def456    4.4 GB    2 days ago

      llama3.2:latest         def789abc012    2.1 GB    5 hours ago

    """.trimIndent()

    val models = OllamaModelDiscovery.parseOllamaListOutput(output)
    assertEquals(2, models.size)
  }

  @Test
  fun `parseOllamaListOutput uses built-in metadata when available`() {
    // qwen3-vl:8b is in the built-in OllamaTrailblazeLlmModelList
    val output = """
      NAME                    ID              SIZE      MODIFIED
      qwen3-vl:8b             abc123def456    4.4 GB    2 days ago
    """.trimIndent()

    val models = OllamaModelDiscovery.parseOllamaListOutput(output)
    val model = models.single()

    // Should use built-in metadata if the model is in the registry
    val builtIn = BuiltInLlmModelRegistry.find("qwen3-vl:8b")
    if (builtIn != null) {
      assertEquals(builtIn.contextLength, model.contextLength)
      assertEquals(builtIn.capabilityIds, model.capabilityIds)
    }
  }

  @Test
  fun `parseOllamaListOutput creates model with defaults for unknown models`() {
    val output = """
      NAME                    ID              SIZE      MODIFIED
      my-custom-model:7b      abc123def456    3.2 GB    1 hour ago
    """.trimIndent()

    val models = OllamaModelDiscovery.parseOllamaListOutput(output)
    val model = models.single()

    assertEquals("my-custom-model:7b", model.modelId)
    assertEquals(131_072L, model.contextLength)
    assertEquals(8_192L, model.maxOutputTokens)
    assertEquals(0.0, model.inputCostPerOneMillionTokens)
  }
}

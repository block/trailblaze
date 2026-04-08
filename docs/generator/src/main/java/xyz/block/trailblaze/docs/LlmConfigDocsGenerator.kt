package xyz.block.trailblaze.docs

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import xyz.block.trailblaze.llm.LlmCapabilitiesUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.LlmConfig
import xyz.block.trailblaze.llm.config.LlmModelConfigEntry
import xyz.block.trailblaze.llm.config.LlmModelCostConfig
import xyz.block.trailblaze.llm.config.LlmProviderConfig
import xyz.block.trailblaze.llm.config.LlmProviderType
import java.io.File

/**
 * Generates documentation for all built-in LLM models that ship with Trailblaze.
 * Output: generated/LLM_MODELS.md with a table per provider showing model specs.
 */
class LlmConfigDocsGenerator(
  private val generatedDir: File,
) {

  private val yaml = Yaml(
    configuration = YamlConfiguration(
      encodeDefaults = false,
    ),
  )

  fun generate() {
    val modelsByProvider = BuiltInLlmModelRegistry.allModelLists()
      .sortedBy { it.provider.display }

    val content = buildString {
      appendLine("# Built-in LLM Models")
      appendLine()
      appendLine(
        "Trailblaze ships with the following built-in models. " +
          "When you reference a model by `id` in your `trailblaze.yaml`, " +
          "all specs below are inherited automatically."
      )
      appendLine()
      appendLine(
        "**These models can change between Trailblaze releases** " +
          "(models added/removed, pricing updated). " +
          "For stable, predictable configuration, set explicit values in your " +
          "workspace `trailblaze.yaml`."
      )
      appendLine()

      for (modelList in modelsByProvider) {
        appendLine("## ${modelList.provider.display}")
        appendLine()
        appendLine(
          "| Model ID | Context | Max Output | Input \$/1M | Output \$/1M " +
            "| Cached Input \$/1M | Capabilities |"
        )
        appendLine(
          "|----------|---------|------------|-----------|------------|" +
            "-------------------|--------------|"
        )

        for (model in modelList.entries.sortedBy { it.modelId }) {
          appendLine(formatModelRow(model))
        }
        appendLine()
      }

      appendLine("## Using Built-in Models in YAML Config")
      appendLine()
      appendLine("Reference any model above by its ID:")
      appendLine()
      val builtInExample = LlmConfig(
        providers = mapOf(
          "openai" to LlmProviderConfig(
            models = listOf(
              LlmModelConfigEntry(id = "gpt-4.1"),
              LlmModelConfigEntry(
                id = "gpt-4.1-mini",
                cost = LlmModelCostConfig(inputPerMillion = 0.30),
              ),
            ),
          ),
        ),
      )
      appendLine("```yaml")
      appendLine(yaml.encodeToString(LlmConfig.serializer(), builtInExample).trimEnd())
      appendLine("```")
      appendLine()
      appendLine(
        "When using a custom endpoint, specify the model specs explicitly. " +
          "See the tables above for reference values:"
      )
      appendLine()
      val customEndpointExample = LlmConfig(
        providers = mapOf(
          "my_gateway" to LlmProviderConfig(
            type = LlmProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://gateway.example.com/v1",
            models = listOf(
              LlmModelConfigEntry(
                id = "my-gpt4-deployment",
                vision = true,
                contextLength = 1048576,
                maxOutputTokens = 32768,
              ),
            ),
          ),
        ),
      )
      appendLine("```yaml")
      appendLine(yaml.encodeToString(LlmConfig.serializer(), customEndpointExample).trimEnd())
      appendLine("```")
      appendLine()
      appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
    }

    File(generatedDir, "LLM_MODELS.md").writeText(content)
  }

  private fun formatModelRow(model: TrailblazeLlmModel): String {
    val capabilities = model.capabilityIds
      .mapNotNull { id -> LlmCapabilitiesUtil.capabilityFromId(id) }
      .map { it.id }
      .sorted()
      .joinToString(", ")
      .ifEmpty { "-" }

    return "| `${model.modelId}` " +
      "| ${formatTokenCount(model.contextLength)} " +
      "| ${formatTokenCount(model.maxOutputTokens)} " +
      "| ${formatCost(model.inputCostPerOneMillionTokens)} " +
      "| ${formatCost(model.outputCostPerOneMillionTokens)} " +
      "| ${formatCost(model.cachedInputCostPerOneMillionTokens)} " +
      "| $capabilities |"
  }

  private fun formatTokenCount(tokens: Long): String {
    return when {
      tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
      tokens >= 1_000 -> "${tokens / 1_000}K"
      else -> tokens.toString()
    }
  }

  private fun formatCost(cost: Double): String {
    return if (cost == 0.0) "free" else "\$${String.format("%.2f", cost)}"
  }
}

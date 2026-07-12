---
title: LLM Support
---

## Koog Integration
Trailblaze makes API calls leverage the [koog.ai](https://koog.ai) library.

Trailblaze takes an instance of a [`LLMClient`](https://github.com/JetBrains/koog/blob/develop/prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/LLMClient.kt#L4) from https://github.com/JetBrains/koog.

### Koog currently has libraries for the following LLM providers

- Google
- OpenAI
- Anthropic
- OpenRouter
- Ollama

Any of these can be passed as the `llmClient` argument to the `TrailblazeRunner` constructor.

#### Example usage with OpenAI

Gradle Dependency: `ai.koog:prompt-executor-openai-client:VERSION`

```kotlin
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList

private val trailblazeLlmModel: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1
private val llmClient: LLMClient = OpenAILLMClient("API_KEY_HERE")
TrailblazeRunner(
    llmClient = llmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    // ... plus the required agent, screen-state provider, tool repo, and logger wiring
)
```

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

Any of these can be passed as the `llmClient` argument to `AndroidTrailblazeRule`.

#### Example usage with OpenAI

Gradle Dependency: `ai.koog:prompt-executor-openai-client:VERSION`

```kotlin
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList

private val trailblazeLlmModel: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_DEFAULT
private val llmClient: LLMClient = OpenAILLMClient("API_KEY_HERE")

@get:Rule
val trailblazeRule = AndroidTrailblazeRule(
    trailblazeLlmModel = trailblazeLlmModel,
    llmClient = llmClient,
)
```

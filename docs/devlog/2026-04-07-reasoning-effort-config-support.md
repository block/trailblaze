---
title: "Support reasoning_effort in LLM Config"
type: devlog
date: 2026-04-07
---

# Support `reasoning_effort` in LLM Config

## Summary

Discovered that Trailblaze only exposes `temperature` as a model-level inference parameter, but OpenAI's reasoning models (o-series, gpt-5+) use `reasoning_effort` to control how much internal reasoning the model performs. Koog 0.7.2 already has full support — we just aren't wiring it through.

## What We Learned

- **`reasoning_effort`** is the de facto standard name. OpenAI coined it; Google adopted the same name for Gemini 2.5. Values: `none`, `minimal`, `low`, `medium`, `high`.
- **Anthropic is the outlier** — they use `thinking.budget_tokens` (a token count, not an enum). Different enough that it likely needs its own field later.
- **Koog 0.7.2** already has `ReasoningEffort` enum and `OpenAIChatParams.reasoningEffort`. The enum maps directly to OpenAI's serialized values (`"none"`, `"minimal"`, `"low"`, `"medium"`, `"high"`).
- **Trailblaze currently constructs plain `LLMParams`** at `TrailblazeKoogLlmClientHelper.kt:300`, which only carries `temperature`. It would need to construct `OpenAIChatParams` instead to pass `reasoningEffort` through.

## What Needs to Change

The plumbing already exists in Koog. Trailblaze needs to thread it through the config → resolve → execute pipeline:

1. **`LlmModelConfigEntry`** — Add `reasoning_effort: String?` field (serialized from YAML)
2. **`TrailblazeLlmModel`** — Add `defaultReasoningEffort: String?` field
3. **`LlmConfigResolver`** — Map the config entry field to the resolved model
4. **`LlmConfigMerger.mergeModelEntry()`** — Add `reasoning_effort` to field-level merge (same pattern as `temperature`)
5. **`TrailblazeKoogLlmClientHelper`** — Construct `OpenAIChatParams(reasoningEffort = ...)` instead of plain `LLMParams` when reasoning_effort is set
6. **Docs** — Update `llm_configuration.md` schema reference

### User-facing YAML

```yaml
llm:
  providers:
    openai:
      models:
        - id: gpt-5
          reasoning_effort: medium  # merges with built-in cost/context/etc.
```

The existing deep merge behavior means users can override just `reasoning_effort` on a model without re-specifying cost, context_length, or other fields.

## Open Questions

- Should `reasoning_effort` also be settable at the `defaults:` level (apply to all models)?
- How to handle Anthropic's `thinking.budget_tokens` — separate field, or generalize into a provider-agnostic `reasoning:` block?
- Should we validate that `reasoning_effort` is only set on models/providers that support it, or pass it through and let the API error?

## Key Files

- `opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/llm/config/LlmModelConfigEntry.kt`
- `opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/llm/TrailblazeLlmModel.kt`
- `opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/llm/config/LlmConfigMerger.kt`
- `opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/llm/config/LlmConfigResolver.kt`
- `opensource/trailblaze-agent/src/main/java/xyz/block/trailblaze/agent/TrailblazeKoogLlmClientHelper.kt`
- Koog source: `ai.koog.prompt.executor.clients.openai.OpenAIParams` (OpenAIChatParams class)
- Koog source: `ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort` (enum)

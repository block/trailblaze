---
title: "Unified Provider Auto-Detection Across Host and Android"
type: decision
date: 2026-04-07
---

# Unified Provider Auto-Detection Across Host and Android

## Summary

When no default model is configured, Trailblaze should scan the environment for available provider credentials and automatically select a working provider. This logic exists on Android today but is missing from the host/desktop side. This plan proposes unifying the auto-detection into shared code that both platforms use.

## Problem

Today, Android and desktop handle "no model configured" very differently:

| Behavior | Android (`AndroidLlmClientResolver`) | Desktop (`TrailblazeDesktopAppConfig`) |
|---|---|---|
| Scan env for available tokens | Yes — `PROVIDER_PRIORITY` list | No |
| Fallback when nothing configured | First provider with a token | Hardcoded `OpenAI GPT-4.1` |
| Fresh install with only `ANTHROPIC_API_KEY` | Works — picks Anthropic | Fails — tries OpenAI, no key |

The desktop side already scans tokens for **UI filtering** (`getCurrentlyAvailableLlmModelLists()` via `JvmLLMProvidersUtil`), but doesn't use that same logic to select the **default model**. The `defaultLlmModel` constructor parameter is hardcoded per config class.

## Proposed Design

### 1. Extract shared auto-detection to common module

Create a `LlmProviderAutoDetector` (or similar) in `trailblaze-models` or `trailblaze-common` (wherever `TrailblazeLlmProvider` lives) that encapsulates the "scan for first available provider" logic.

```kotlin
object LlmProviderAutoDetector {
  /**
   * Default priority order for auto-detecting a provider from available credentials.
   * First provider with an available auth token wins.
   */
  val DEFAULT_PROVIDER_PRIORITY = listOf(
    TrailblazeLlmProvider.OPENAI,
    TrailblazeLlmProvider.OPEN_ROUTER,
    TrailblazeLlmProvider.ANTHROPIC,
    TrailblazeLlmProvider.GOOGLE,
    TrailblazeLlmProvider.OLLAMA,
  )

  fun detectFirstAvailable(
    providerPriority: List<TrailblazeLlmProvider> = DEFAULT_PROVIDER_PRIORITY,
    isProviderAvailable: (TrailblazeLlmProvider) -> Boolean,
    getFirstModel: (TrailblazeLlmProvider) -> TrailblazeLlmModel?,
  ): TrailblazeLlmModel? { ... }
}
```

The `isProviderAvailable` and `getFirstModel` lambdas keep the detector platform-agnostic — Android checks instrumentation args, desktop checks env vars, but the priority logic is shared.

### 2. Android: refactor `AndroidLlmClientResolver` to delegate

Replace the inline `PROVIDER_PRIORITY` loop in `resolveModel()` stage 3 with a call to the shared detector. The resolution chain stays the same (config → instrumentation arg → auto-detect), just the auto-detect step delegates.

Key file: `trailblaze-android/src/main/java/xyz/block/trailblaze/android/AndroidLlmClientResolver.kt`

### 3. Desktop: use auto-detection in `getCurrentLlmModel()` fallback

`TrailblazeDesktopAppConfig.getCurrentLlmModel()` currently falls back to `defaultLlmModel` (hardcoded OpenAI). Change this to:

1. Try saved settings (current behavior)
2. Try `llm.defaults.model` from YAML config
3. Auto-detect from environment using the shared detector
4. Error with a helpful message

This replaces the `defaultLlmModel` constructor parameter — or at minimum makes it the last resort after auto-detection.

Key files:
- `trailblaze-host/src/main/java/xyz/block/trailblaze/desktop/TrailblazeDesktopAppConfig.kt` — `getCurrentLlmModel()` at line 156
- `trailblaze-desktop/src/main/java/xyz/block/trailblaze/desktop/OpenSourceTrailblazeDesktopAppConfig.kt` — passes hardcoded `defaultLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1`
- Organization-specific subclasses of `TrailblazeDesktopAppConfig` follow the same pattern

### 4. Organization-specific overrides

Organization-specific config subclasses can include additional providers (e.g., a corporate LLM endpoint) in their provider priority by passing a custom `providerPriority` list to the shared detector rather than using the default.

## Open Questions

- **Should the YAML config gain a `provider_priority` field?** Current decision: no. Keep priority in implementation for now, document it, and link to source. Revisit if users actually need to customize it.
- **What happens in the desktop UI when auto-detect picks a provider?** The settings dropdown should probably reflect the auto-detected selection so the user sees what was chosen. Need to check if `TrailblazeSettingsRepo` handles "no saved provider" cleanly.
- **CLI error message** — `TrailblazeCli.kt:1061` already prints "No LLM configured" when `getCurrentLlmModel()` throws. With auto-detection, this error should only fire when genuinely no tokens are available, which is the right behavior.

## Scope

This is future work — not part of the current `externalize-llm-config` branch. The immediate change (removing hardcoded model from `ExamplesAndroidTrailblazeRule`) is safe because the YAML config handles that case. This plan addresses the broader inconsistency.

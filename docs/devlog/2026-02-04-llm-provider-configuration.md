---
title: "LLM Provider Configuration"
type: decision
date: 2026-02-04
---

# Trailblaze Decision 030: LLM Provider Configuration

## Context

Trailblaze is distributed as a binary application. Users need to configure LLM providers and models without modifying source code. Currently this is handled through environment variables and hardcoded model lists (`OpenAITrailblazeLlmModelList`, `AnthropicTrailblazeLlmModelList`, etc.), with defaults in `TrailblazeDesktopAppConfig`.

This works for development but creates friction for users who want to use enterprise endpoints (Azure OpenAI, custom proxies), add unlisted models, override pricing, or use self-hosted models (Ollama, vLLM).

## Decision

**Trailblaze supports user-configurable LLM providers and models via YAML configuration files.**

Configuration loads in order: built-in defaults → user-level (`~/.trailblaze/llm-config.yaml`) → project-level (`./trailblaze.yaml` under `llm:` key) → environment variables. Later sources override earlier ones.

```yaml
# ~/.trailblaze/llm-config.yaml
providers:
  openai:
    enabled: true
    auth:
      env_var: OPENAI_API_KEY
    models:
      - gpt-4.1
      - gpt-4.1-mini
      - id: gpt-5-mini
        cost:
          input_per_million: 0.20    # Enterprise rate override

  anthropic:
    enabled: true
    auth:
      env_var: ANTHROPIC_API_KEY
    models:
      - claude-sonnet-4.5
      - claude-haiku-4.5

  ollama:
    enabled: true
    models:
      - qwen3-vl:8b

  # OpenAI-compatible endpoints (Azure, vLLM, LM Studio, etc.)
  azure_openai:
    type: openai_compatible
    base_url: "https://my-resource.openai.azure.com/openai/deployments"
    auth:
      env_var: AZURE_OPENAI_API_KEY
    models:
      - id: my-gpt4-deployment
        inherits: gpt-4.1

defaults:
  inner_model: gpt-4.1-mini     # Screen analysis (cheap, fast, vision)
  outer_model: gpt-4.1          # Planning/reasoning (capable)
```

Models can be referenced by name (inheriting built-in specs), with field overrides, or as full definitions for custom models. The `inner`/`outer` tiers correspond to the two-tier agent architecture in [Decision 032](2026-02-04-trail-blaze-agent-architecture.md).

## Status

**Not yet implemented.** The codebase still uses hardcoded model lists per provider and environment variables for auth. There is no YAML config loading, no unified `BuiltInLlmModels` registry, and no `openai_compatible` custom endpoint support. Azure OpenAI (`AZURE_OPENAI_API_KEY`) is not currently supported in any provider code.

## Related Documents

- [012: Koog Library for LLM Communication](2026-01-28-koog-llm-client.md)
- [032: Trail/Blaze Agent Architecture](2026-02-04-trail-blaze-agent-architecture.md)

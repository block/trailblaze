---
title: "LLM Provider Configuration"
type: decision
date: 2026-02-04
---

# Trailblaze Decision 030: LLM Provider Configuration

## Context

Trailblaze is distributed as a binary application (see Decision 013: Distribution Model) that users install via package managers like Homebrew. These users need to configure which LLM providers and models to use without modifying source code or recompiling.

Currently, LLM configuration is handled through:
- **Environment variables** (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.)
- **Hardcoded model lists** (`OpenAITrailblazeLlmModelList`, `AnthropicTrailblazeLlmModelList`, etc.)
- **Default model selection** in `TrailblazeDesktopAppConfig`

This works for development but creates friction for binary distribution users who want to:
- Use enterprise endpoints (Azure OpenAI, custom proxies)
- Add models not in the predefined list
- Override pricing for negotiated enterprise rates
- Use self-hosted models (Ollama, vLLM, LM Studio)
- Configure multiple providers and switch between them

## Decision

**Trailblaze supports user-configurable LLM providers and models via YAML configuration files.**

### Configuration File Locations

Configuration is loaded from multiple locations with later sources overriding earlier ones:

| Priority | Location | Purpose |
|----------|----------|---------|
| 1 (lowest) | Built-in defaults | Ship with Trailblaze binary |
| 2 | `~/.trailblaze/llm-config.yaml` | User-level configuration |
| 3 | `./trailblaze.yaml` (under `llm:` key) | Project-level configuration |
| 4 (highest) | Environment variables | CI/CD and runtime overrides |

### YAML Schema

```yaml
# ~/.trailblaze/llm-config.yaml
# OR embedded in ./trailblaze.yaml under the `llm:` key

providers:
  # ═══════════════════════════════════════════════════════════════════════
  # Standard Providers
  # Just enable them and reference built-in models by name
  # ═══════════════════════════════════════════════════════════════════════

  openai:
    enabled: true
    auth:
      env_var: OPENAI_API_KEY
    models:
      # Reference built-in models by name (all specs inherited)
      - gpt-4.1
      - gpt-4.1-mini
      - gpt-5
      # Override specific fields (e.g., enterprise pricing)
      - id: gpt-5-mini
        cost:
          input_per_million: 0.20   # Negotiated enterprise rate
          output_per_million: 10.00

  anthropic:
    enabled: true
    auth:
      env_var: ANTHROPIC_API_KEY
    models:
      - claude-sonnet-4.5
      - claude-haiku-4.5
      # Add a model not in the predefined list
      - id: claude-opus-4
        tier: outer
        context_length: 200000
        max_output_tokens: 32000
        cost:
          input_per_million: 15.00
          output_per_million: 75.00

  google:
    enabled: true
    auth:
      env_var: GOOGLE_API_KEY
    models:
      - gemini-2.5-flash
      - gemini-2.5-pro

  # ═══════════════════════════════════════════════════════════════════════
  # Self-Hosted / Local Models
  # ═══════════════════════════════════════════════════════════════════════

  ollama:
    enabled: true
    # No auth needed for local Ollama
    models:
      - qwen3-vl:8b
      - id: llama-3.2-90b-vision
        tier: both
        context_length: 131072
        max_output_tokens: 8192

  # ═══════════════════════════════════════════════════════════════════════
  # Custom OpenAI-Compatible Endpoints
  # For Azure, vLLM, LM Studio, custom proxies, etc.
  # ═══════════════════════════════════════════════════════════════════════

  azure_openai:
    type: openai_compatible
    base_url: "https://my-resource.openai.azure.com/openai/deployments"
    headers:
      api-version: "2024-02-15-preview"
    auth:
      env_var: AZURE_OPENAI_API_KEY
    models:
      # Azure uses deployment names; inherit specs from built-in models
      - id: my-gpt4-deployment
        inherits: gpt-4.1
      - id: my-gpt4-mini-deployment
        inherits: gpt-4.1-mini

  local_vllm:
    type: openai_compatible
    base_url: "http://localhost:8000/v1"
    auth:
      required: false
    models:
      - id: mistral-large-instruct
        tier: outer
        vision: false
        context_length: 128000
        max_output_tokens: 8192
        cost:
          input_per_million: 0.0
          output_per_million: 0.0

# ═══════════════════════════════════════════════════════════════════════
# Default Model Selection
# ═══════════════════════════════════════════════════════════════════════

defaults:
  # For two-tier agent architecture (see Decision 032)
  inner_model: gpt-4.1-mini    # Screen analysis (cheap, fast, vision)
  outer_model: gpt-4.1         # Planning/reasoning (capable)
```

### Schema Reference

#### Provider Configuration

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `enabled` | boolean | No | Whether this provider is active (default: `true`) |
| `type` | string | No | Provider type: `openai_compatible`, `anthropic`, `google`, `ollama` (inferred from provider name if standard) |
| `base_url` | string | No | Custom API endpoint (required for custom providers) |
| `headers` | map | No | Additional HTTP headers sent with every request |
| `auth.env_var` | string | No | Environment variable containing API key |
| `auth.required` | boolean | No | Whether auth is mandatory (default: `true`) |
| `models` | list | Yes | List of models for this provider |

#### Model Configuration

Models can be specified in three ways:

**1. Reference by name (string shorthand)**
```yaml
models:
  - gpt-4.1          # Inherits all specs from built-in registry
  - claude-sonnet-4.5
```

**2. Reference with overrides (object)**
```yaml
models:
  - id: gpt-4.1-mini
    cost:
      input_per_million: 0.30   # Override just the pricing
      output_per_million: 1.20
```

**3. Full definition (new model)**
```yaml
models:
  - id: my-custom-model
    tier: outer                           # inner | outer | both
    context_length: 128000
    max_output_tokens: 16384
    cost:
      input_per_million: 2.50
      output_per_million: 10.00
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Model identifier (sent to API) |
| `inherits` | string | No | Built-in model to inherit specs from |
| `tier` | string | No | Agent tier: `inner`, `outer`, or `both` |
| `vision` | boolean | No | Whether the model supports image input (default: `true`) |
| `temperature` | number | No | Default temperature for this model |
| `context_length` | integer | No | Maximum context window in tokens |
| `max_output_tokens` | integer | No | Maximum output tokens |
| `cost.input_per_million` | number | No | Cost per 1M input tokens (USD) |
| `cost.output_per_million` | number | No | Cost per 1M output tokens (USD) |

### Built-in Model Registry

Trailblaze ships with a registry of well-known models from major providers. Users can reference these by `id` without specifying all details:

```kotlin
// Built-in registry (ships with Trailblaze)
object BuiltInLlmModels {
    private val registry: Map<String, TrailblazeLlmModel> = buildMap {
        // OpenAI
        OpenAITrailblazeLlmModelList.entries.forEach { put(it.modelId, it) }
        // Anthropic
        AnthropicTrailblazeLlmModelList.entries.forEach { put(it.modelId, it) }
        // Google
        GoogleTrailblazeLlmModelList.entries.forEach { put(it.modelId, it) }
        // OpenRouter
        OpenRouterTrailblazeLlmModelList.entries.forEach { put(it.modelId, it) }
    }

    fun find(modelId: String): TrailblazeLlmModel? = registry[modelId]
    fun all(): List<TrailblazeLlmModel> = registry.values.toList()
}
```

Benefits:
- **No boilerplate** — Just write `- gpt-4.1` instead of specifying all fields
- **Automatic updates** — New Trailblaze releases include updated specs
- **Override when needed** — Enterprise pricing, custom context lengths, etc.

### Provider Types

| Type | Description | Base URL Default |
|------|-------------|------------------|
| `openai` | OpenAI API | `https://api.openai.com/v1` |
| `anthropic` | Anthropic Claude API | `https://api.anthropic.com` |
| `google` | Google Gemini API | `https://generativelanguage.googleapis.com` |
| `ollama` | Ollama local server | `http://localhost:11434` |
| `openai_compatible` | Any OpenAI-compatible API | (required) |

The `openai_compatible` type works with:
- Azure OpenAI
- vLLM
- LM Studio
- text-generation-webui
- LocalAI
- Any endpoint implementing the OpenAI chat completions API

### Environment Variable Conventions

Standard environment variables for authentication:

| Provider | Environment Variable |
|----------|---------------------|
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Google | `GOOGLE_API_KEY` |
| Azure OpenAI | `AZURE_OPENAI_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |

These can be overridden in the YAML via `auth.env_var`.

### Model Tiers (Two-Tier Agent Architecture)

Trailblaze uses a two-tier agent architecture (see [Decision 032](2026-02-04-trail-blaze-agent-architecture.md)):

| Tier | Purpose | Ideal Model Characteristics |
|------|---------|----------------------------|
| `inner` | Screen analysis | Cheap, fast, vision-capable |
| `outer` | Planning/reasoning | Capable, strong reasoning |
| `both` | Either role | Versatile models |

The `defaults` section specifies which models to use for each tier:

```yaml
defaults:
  inner_model: gpt-4.1-mini    # References model by id
  outer_model: claude-sonnet-4.5
```

### Availability Detection

A provider is considered "available" when:

1. **Standard providers**: The configured `auth.env_var` is set in the environment
2. **Ollama**: The `ollama` command is available on PATH
3. **Custom providers with `auth.required: false`**: Always available
4. **Custom providers with `auth.required: true`**: The `auth.env_var` is set

The desktop app shows only available providers in the UI, but users can configure providers for future use.

### Configuration Loading

```kotlin
// Pseudocode for configuration resolution
fun loadLlmConfig(): LlmConfig {
    // 1. Start with built-in defaults
    var config = BuiltInLlmConfig.default()

    // 2. Merge user-level config
    val userConfig = File(System.getProperty("user.home"), ".trailblaze/llm-config.yaml")
    if (userConfig.exists()) {
        config = config.mergeWith(yaml.decodeFromString(userConfig.readText()))
    }

    // 3. Merge project-level config (llm: key in trailblaze.yaml)
    val projectConfig = File("trailblaze.yaml")
    if (projectConfig.exists()) {
        val project = yaml.decodeFromString<TrailblazeProjectConfig>(projectConfig.readText())
        project.llm?.let { config = config.mergeWith(it) }
    }

    // 4. Apply environment variable overrides
    System.getenv("TRAILBLAZE_DEFAULT_INNER_MODEL")?.let { config.defaults.innerModel = it }
    System.getenv("TRAILBLAZE_DEFAULT_OUTER_MODEL")?.let { config.defaults.outerModel = it }

    return config
}
```

### Minimal Configuration Examples

**Just OpenAI (most common)**
```yaml
providers:
  openai:
    enabled: true
    models:
      - gpt-4.1
      - gpt-4.1-mini
```
Then set `OPENAI_API_KEY` in your environment.

**Local Ollama only (no API keys)**
```yaml
providers:
  ollama:
    enabled: true
    models:
      - qwen3-vl:8b

defaults:
  inner_model: qwen3-vl:8b
  outer_model: qwen3-vl:8b
```

**Azure OpenAI (enterprise)**
```yaml
providers:
  azure_openai:
    type: openai_compatible
    base_url: "https://contoso.openai.azure.com/openai/deployments"
    auth:
      env_var: AZURE_OPENAI_API_KEY
      headers:
        api-version: "2024-02-15-preview"
    models:
      - id: gpt-4-turbo
        inherits: gpt-4.1

defaults:
  inner_model: gpt-4-turbo
  outer_model: gpt-4-turbo
```

### Integration with Project Configuration

When `llm:` is specified in `trailblaze.yaml`, it merges with user-level config:

```yaml
# trailblaze.yaml (project root)
target: rideshare_driver

# LLM configuration (this decision)
llm:
  providers:
    anthropic:
      enabled: true
      models:
        - claude-sonnet-4.5
  defaults:
    outer_model: claude-sonnet-4.5

# MCP server configuration (Decision 029)
mcpServers:
  - name: rideshare-tools
    command: ./gradlew
    args: [:rideshare-tools:runMcp]
```

## Consequences

**Positive:**

- **No recompilation** — Binary distribution users configure via YAML
- **Self-documenting** — Config file shows what env vars are needed
- **Extensible** — New providers added without code changes
- **Enterprise-friendly** — Supports Azure, proxies, custom auth
- **Local-first option** — Clear path for Ollama, vLLM, self-hosted
- **Cost transparency** — Users can set accurate pricing for their contracts
- **Gradual adoption** — Start with env vars, add YAML config when needed

**Negative:**

- **Configuration complexity** — More options means more to understand
- **Version drift** — User configs may reference deprecated models
- **Validation burden** — Need to validate YAML schema and model references
- **Documentation** — Must maintain docs for configuration options

## Related Documents

- [Decision 012: Koog Library for LLM Communication](2026-01-28-koog-llm-client.md) — The underlying LLM client library
- [Decision 032: Trail/Blaze Agent Architecture](2026-02-04-trail-blaze-agent-architecture.md) — How inner/outer model tiers are used
- Decision 013: Distribution Model — Binary distribution strategy

## References

- [LiteLLM Model Prices](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json) — Source for default pricing data
- [OpenAI Pricing](https://openai.com/pricing) — Official OpenAI pricing
- [Anthropic Pricing](https://www.anthropic.com/pricing) — Official Anthropic pricing
- [Google AI Pricing](https://ai.google.dev/pricing) — Official Google AI pricing

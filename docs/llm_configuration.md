---
title: LLM Configuration
---

Trailblaze supports configurable LLM providers and models via YAML files. This allows teams to use enterprise endpoints, custom gateways, self-hosted models, and project-specific defaults without modifying source code.

## Configuration Loading Order

Configuration is loaded from multiple locations. Later sources override earlier ones:

| Priority | Location | Purpose |
|----------|----------|---------|
| 1 (lowest) | Built-in defaults | Ship with Trailblaze binary |
| 2 | `~/.trailblaze/trailblaze.yaml` (under `llm:` key) | User-level preferences |
| 3 | `./trails/config/trailblaze.yaml` (under `llm:` key) | Project/workspace defaults |
| 4 (highest) | Environment variables | CI/CD and runtime overrides |

If no YAML config files exist, Trailblaze uses the built-in defaults with API keys from environment variables (same behavior as before YAML config was introduced).

## Quick Start

### Minimal: Just set an API key

No YAML needed. Set your provider's API key:

```bash
export OPENAI_API_KEY="sk-..."
```

Trailblaze will use the built-in model list for that provider.

### Project defaults via `trails/config/trailblaze.yaml`

Create `trails/config/trailblaze.yaml` in your workspace to set defaults for everyone on the team:

```yaml
llm:
  providers:
    openai:
      models:
        - id: gpt-4.1
        - id: gpt-4.1-mini
  defaults:
    model: gpt-4.1
```

When anyone clones the repo and launches Trailblaze, they get these models by default.

### Enterprise gateway

For organizations with a private Gen AI gateway (e.g., Azure OpenAI, a corporate proxy, or a managed LLM service):

```yaml
# trails/config/trailblaze.yaml
llm:
  providers:
    corp_gateway:
      type: openai_compatible
      base_url: "https://llm-gateway.internal.example.com/v1"
      headers:
        x-team-id: "ui-testing"
      auth:
        env_var: CORP_LLM_API_KEY
      models:
        - id: gpt-4.1
          context_length: 1048576
          max_output_tokens: 32768
        - id: gpt-4.1-mini
          context_length: 1048576
          max_output_tokens: 32768
  defaults:
    model: gpt-4.1
```

Team members only need to set `CORP_LLM_API_KEY` in their environment. The gateway URL, headers, and model selection are all defined in the project.

## YAML Schema Reference

### Full example

```yaml
providers:
  # Standard provider (uses built-in API endpoint)
  openai:
    enabled: true
    auth:
      env_var: OPENAI_API_KEY
    models:
      - id: gpt-4.1
      - id: gpt-4.1-mini
      - id: gpt-5
        cost:
          input_per_million: 1.25
          output_per_million: 10.00

  # Custom OpenAI-compatible endpoint
  azure_openai:
    type: openai_compatible
    base_url: "https://my-resource.openai.azure.com/openai/deployments"
    headers:
      api-version: "2024-02-15-preview"
    auth:
      env_var: AZURE_OPENAI_API_KEY
    models:
      - id: my-gpt4-deployment
        context_length: 1048576
        max_output_tokens: 32768

  # Local models (no API key needed)
  ollama:
    enabled: true
    models:
      - id: qwen3-vl:8b
      - id: llama3.2:latest
        context_length: 131072
        max_output_tokens: 8192

defaults:
  model: gpt-4.1
```

### Provider fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `enabled` | boolean | No | Whether this provider is active (default: `true`) |
| `type` | string | No | Provider type (see below). Inferred from key for standard providers. |
| `description` | string | No | Human-readable description (supports Markdown). Shown in UI and logs. |
| `base_url` | string | No | Custom API endpoint. Required for `openai_compatible`. |
| `chat_completions_path` | string | No | Custom path for chat completions (e.g., `serving-endpoints/{{model_id}}/invocations`) |
| `headers` | map | No | Additional HTTP headers sent with every request |
| `auth.env_var` | string | No | Environment variable containing the API key |
| `auth.required` | boolean | No | Whether auth is mandatory (default: `true`). Set `false` for local models. |
| `models` | list | Yes | List of model configurations for this provider |

### Provider types

| Type | Description | Default base URL |
|------|-------------|-----------------|
| `openai` | OpenAI API | `https://api.openai.com/v1` |
| `anthropic` | Anthropic Claude API | `https://api.anthropic.com` |
| `google` | Google Gemini API | `https://generativelanguage.googleapis.com` |
| `ollama` | Ollama local server | `http://localhost:11434` |
| `openrouter` | OpenRouter API | `https://openrouter.ai/api/v1` |
| `openai_compatible` | Any OpenAI-compatible API | (must specify `base_url`) |

Standard provider keys (`openai`, `anthropic`, `google`, `ollama`, `openrouter`) infer their type automatically. Use `openai_compatible` for Azure, vLLM, LM Studio, custom gateways, and similar.

### Model fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Model identifier sent to the API |
| `tier` | string | No | Model tier hint (e.g., `inner`, `outer`, `both`) |
| `vision` | boolean | No | Whether the model supports image input (default: `true`). Set `false` for text-only models. |
| `temperature` | number | No | Default temperature for this model. When set, used for all requests to this model. |
| `context_length` | integer | No | Maximum context window in tokens |
| `max_output_tokens` | integer | No | Maximum output tokens |
| `cost.input_per_million` | number | No | Cost per 1M input tokens (USD) |
| `cost.output_per_million` | number | No | Cost per 1M output tokens (USD) |
| `cost.cached_input_per_million` | number | No | Cost per 1M cached input tokens (USD) |
| `screenshot.max_dimensions` | string | No | Max screenshot dimensions as `WIDTHxHEIGHT` (e.g., `1536x768`). Overrides project default. |

When `id` matches a built-in model (see [Built-in Models](generated/LLM_MODELS.md)), all specs are used automatically and any fields you specify override them. For custom models not in the built-in registry, specify `context_length` and `max_output_tokens` explicitly (they default to 131K/8K if omitted).

### Default model selection

```yaml
defaults:
  model: gpt-4.1
  screenshot:
    max_dimensions: 1536x768   # Default screenshot scaling (default if omitted)
```

These can be overridden by environment variables:
- `TRAILBLAZE_DEFAULT_MODEL`

## Workspace Defaults

`trails/config/trailblaze.yaml` sets defaults for everyone working in that workspace. This is the recommended way for teams and organizations to configure LLM providers.

**Why set workspace defaults?**

- Built-in model lists can change between Trailblaze releases (models added, pricing updated, etc.)
- A workspace config pins your project to specific models and providers
- New team members get working defaults without manual setup
- Enterprise gateways are configured once, not per-developer

**Example:** An organization using a private gateway:

```yaml
# trails/config/trailblaze.yaml (committed to repo)
llm:
  providers:
    acme_gateway:
      type: openai_compatible
      base_url: "https://ai.acme.internal/v1"
      auth:
        env_var: ACME_AI_TOKEN
      models:
        - id: gpt-4.1
          context_length: 1048576
          max_output_tokens: 32768
        - id: gpt-4.1-mini
          context_length: 1048576
          max_output_tokens: 32768
  defaults:
    model: gpt-4.1
```

Individual developers can still override by creating `~/.trailblaze/trailblaze.yaml` in their home directory (user-level config takes lower priority, but environment variables take highest priority).

## Ollama (Local Models)

### Runtime discovery

When Ollama is installed, Trailblaze automatically discovers locally available models by running `ollama list`. These appear alongside any models configured in YAML.

### Project-configured models

You can list Ollama models in your workspace's `trails/config/trailblaze.yaml` even if they are not currently installed on the developer's machine. This is useful when a project recommends specific local models:

```yaml
llm:
  providers:
    ollama:
      models:
        - id: qwen3-vl:8b
        - id: qwen3.5:27b
        - id: llama3.2:latest
  defaults:
    model: qwen3-vl:8b
```

Models that are not installed will show a warning in the desktop app UI indicating the model is not available via Ollama. Developers can install them with:

```bash
ollama pull qwen3-vl:8b
```

Trailblaze will not auto-download models. The project config serves as documentation of which models the team recommends.

### Custom model specs

For Ollama models not in the built-in registry, provide context length and output token limits:

```yaml
- id: my-custom-gguf:latest
  context_length: 131072
  max_output_tokens: 8192
```

## Environment Variables

Standard environment variables for authentication:

| Provider | Environment Variable |
|----------|---------------------|
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Google | `GOOGLE_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| Ollama | *(none required)* |

Custom providers specify their env var via `auth.env_var` in the YAML config.

## On-Device Android Agent

On Android, LLM configuration works differently depending on the execution mode.

### Host-driven (desktop app or CLI)

When running trails from the desktop app or CLI, the host selects the LLM provider and model (from YAML config, built-in defaults, or the UI), then passes the full `TrailblazeLlmModel` to the device via the `RunYamlRequest` RPC message. The device agent uses whatever the host sends — no local configuration is needed on the Android side.

### Standalone instrumentation tests

When the Android agent runs standalone (e.g., `AndroidTrailblazeRule` in an instrumentation test), `AndroidLlmClientResolver` resolves the model automatically using this priority order:

| Priority | Source | Description |
|----------|--------|-------------|
| 1 (highest) | `trails/config/trailblaze.yaml` classpath resource | On-device config bundled in the test APK |
| 2 | `trailblaze.llm.default_model` instrumentation arg | Passed by the host at runtime |
| 3 | Auto-detect from provider tokens | First provider with an available API key wins |

**Recommended: Add a config file to your test module**

Create `src/androidTest/resources/trails/config/trailblaze.yaml` in your test module:

```yaml
llm:
  defaults:
    model: openai/gpt-4.1
```

The model key uses `provider/model_id` format (e.g., `openai/gpt-4.1`, `anthropic/claude-sonnet-4-6`). AGP strips dot-prefixed directories from classpath resources, so the config lives under `trails/config/` instead of `.trailblaze/`.

Then use `AndroidTrailblazeRule` with zero-arg defaults:

```kotlin
class MyTests {
  @get:Rule val rule = AndroidTrailblazeRule()

  @Test
  fun myTest() = rule.runFromAsset()
}
```

API keys are still passed as instrumentation arguments (the config file only selects the model, not the credentials):

```bash
adb shell am instrument \
  -e trailblaze.llm.auth.token.openai "sk-..." \
  -w com.example.test/androidx.test.runner.AndroidJUnitRunner
```

**Alternative: Instrumentation arg**

If you don't want a config file, pass the model as an instrumentation arg:

```bash
adb shell am instrument \
  -e trailblaze.llm.default_model "openai/gpt-4.1" \
  -e trailblaze.llm.auth.token.openai "sk-..." \
  -w com.example.test/androidx.test.runner.AndroidJUnitRunner
```

**Alternative: Auto-detection**

If neither a config file nor a `trailblaze.llm.default_model` arg is present, `AndroidLlmClientResolver` auto-detects the provider from the first available API key token. The provider priority order is: OpenAI, OpenRouter, Anthropic, Google, Ollama. The provider's `default_model` (defined in the built-in provider YAML) is used. This order is defined in [`PROVIDER_PRIORITY` in `AndroidLlmClientResolver`](https://github.com/block/trailblaze/blob/main/trailblaze-android/src/main/java/xyz/block/trailblaze/android/AndroidLlmClientResolver.kt).

### Custom `openai_compatible` providers on-device

> **Scope of this section.** The args below describe the contract honored by the **daemon-driven on-device path** — i.e. when the desktop app or CLI ships a trail to the device through `AndroidStandaloneServerTest`, which delegates to [`OnDeviceOpenAICompatibleLlmClientFactory`](https://github.com/block/trailblaze/blob/main/trailblaze-android/src/main/java/xyz/block/trailblaze/android/OnDeviceOpenAICompatibleLlmClientFactory.kt). The instrumentation-test path documented in "Standalone instrumentation tests" above (running `AndroidTrailblazeRule` directly without a host) goes through a separate, narrower openai_compatible code path in [`AndroidLlmClientResolver.createClient`](https://github.com/block/trailblaze/blob/main/trailblaze-android/src/main/java/xyz/block/trailblaze/android/AndroidLlmClientResolver.kt) that currently only honors `base_url` and the per-provider auth token — `provider.type`, `chat_completions_path`, `headers`, and `auth_required` are not yet wired through it. Consolidating those two on-device paths is tracked as a follow-up; until that lands, the args below apply only to the daemon-driven path.

A workspace `openai_compatible` provider (Azure, vLLM, LM Studio, custom gateways, etc.) reaches the on-device APK through a small set of additional instrumentation args. When you run trails via the desktop daemon, the host emits these args automatically. When you run `am instrument` directly (e.g. CI pipelines outside the daemon, custom test rigs), you can pass them yourself.

> **Security note.** Tokens and credential-bearing headers passed as `-e` args on the `am instrument` command line appear in `ps -ef` output (visible to other users on the device for the lifetime of the process), shell history files (`~/.bash_history`, `~/.zsh_history`), and CI build logs (which often persist for weeks). For real credentials, prefer the **desktop daemon path** — it resolves tokens from env vars via the `auth.env_var` field in your workspace `trailblaze.yaml` and never puts them on a command line. The direct `am instrument` recipe below is best treated as a debugging tool, not a production CI pattern. Header values passed via `provider.headers` can also carry secrets (tenant tokens, signed routing keys, etc.) — treat them with the same care.

```bash
adb shell am instrument \
  -e trailblaze.llm.default_model "my_gateway/some-model-id" \
  -e trailblaze.llm.provider.type "openai_compatible" \
  -e trailblaze.llm.provider.base_url "https://my-gateway.example.com" \
  -e trailblaze.llm.provider.chat_completions_path "v1/chat/completions" \
  -e trailblaze.llm.provider.headers '{"X-Tenant":"acme","X-Route":"default"}' \
  -e trailblaze.llm.provider.auth_required "true" \
  -e trailblaze.llm.auth.token.my_gateway "your-token-here" \
  -w com.example.test/androidx.test.runner.AndroidJUnitRunner
```

Each arg, in detail:

| Arg | Required | Value |
|-----|----------|-------|
| `trailblaze.llm.provider.type` | Yes | Must be `openai_compatible` (case-insensitive). Without this, the on-device factory skips custom-provider registration and falls back to the built-in OpenAI/Ollama/NONE map. |
| `trailblaze.llm.provider.base_url` | Yes | Gateway endpoint (e.g. `https://my-gateway.example.com`). |
| `trailblaze.llm.provider.chat_completions_path` | No | Custom path appended to `base_url`. Supports `{{model_id}}` substitution at the device — useful for per-deployment serving endpoints. |
| `trailblaze.llm.provider.headers` | No | JSON-encoded `Map<String, String>` of static request headers (e.g. tenant/routing keys). Malformed JSON is tolerated with a warning and proceeds with no static headers. |
| `trailblaze.llm.provider.auth_required` | No | `"true"` (default) or `"false"`. When `false`, the on-device client is constructed even if no auth token is present — useful for local LLM servers that don't need auth. |
| `trailblaze.llm.auth.token.<provider_id>` | Yes (unless `auth_required=false`) | The provider's auth token. The `<provider_id>` segment must match the `id` of the active provider (e.g. `trailblaze.llm.auth.token.my_gateway` if `default_model=my_gateway/...`). |

**Important:** the host already encodes provider metadata correctly when you use the desktop daemon — the args above are mostly relevant when you're running `am instrument` directly. The on-device factory ([`OnDeviceOpenAICompatibleLlmClientFactory`](https://github.com/block/trailblaze/blob/main/trailblaze-android/src/main/java/xyz/block/trailblaze/android/OnDeviceOpenAICompatibleLlmClientFactory.kt)) is the authoritative parser of these keys.

If `auth_required=true` but no token arg is passed, the on-device runner fails fast with a clear error message naming the provider id and pointing at the remediation paths (set the env var, or set `auth.required: false` in your workspace yaml). This is by design — without it, the failure would surface several frames later as a generic "Unsupported provider" error that's hard to diagnose without source access. Note: this fail-fast lives in `OnDeviceOpenAICompatibleLlmClientFactory`; the older `AndroidLlmClientResolver` openai_compatible branch used by `AndroidTrailblazeRule` doesn't yet have the equivalent guard — it silently skips registering the custom client when the token is missing, and `DefaultDynamicLlmClient.createLlmClient()` then throws the generic "Unsupported provider" error a few frames later. The consolidation follow-up above brings both paths into parity.

## Built-in Models

Trailblaze ships with a registry of models from major providers. See [Built-in LLM Models](generated/LLM_MODELS.md) for the full list with pricing and capabilities.

When referencing a built-in model by `id` in your YAML config, all specs (pricing, context length, capabilities) are inherited automatically. You only need to specify fields you want to override.

Built-in model specs are updated with each Trailblaze release. If you need stable, predictable pricing or specs, override them in your workspace config.

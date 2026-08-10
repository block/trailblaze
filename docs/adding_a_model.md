---
title: Adding a Model
---

# Adding a Model

**You do not need a new Trailblaze release to use a new model.** Model definitions are
config, not code. Add the model to your workspace's `trails/config/trailblaze.yaml` and
Trailblaze picks it up on the next run.

The built-in registry ([Built-in Models](generated/LLM_MODELS.md)) exists so common models
work with zero configuration and correct specs. It is a convenience, not a gate — a model
missing from it is not a model Trailblaze refuses to run. Model families ship faster than
release cycles, so this page is the escape hatch that keeps you off the upgrade treadmill.

## The 30-second version

```yaml
# trails/config/trailblaze.yaml
llm:
  providers:
    google:
      models:
        - id: gemma-4-31b-it
          context_length: 262144
          max_output_tokens: 32768
  defaults:
    model: gemma-4-31b-it
```

```bash
trailblaze config models          # confirm it's listed
trailblaze config llm google/gemma-4-31b-it
```

Set the provider's API key (`GOOGLE_API_KEY` here — see the
[env var table](llm_configuration.md#environment-variables)) and you're running.

## How your entry combines with the built-ins

Workspace config **adds to** the built-in registry — it does not replace it. Entries are
matched by `id`:

| Your `id` | Result |
|---|---|
| Not in the built-in registry | A new model, defined entirely by your entry |
| Already in the built-in registry | Field-by-field merge — the fields you set win, the rest are inherited |

So a workspace entry is equally the way to **add** a model and the way to **pin or correct**
one that ships in the box (stale pricing, a context window your gateway caps lower).

Anything you omit falls back to a safe default rather than failing: `context_length`
defaults to **131072** and `max_output_tokens` to **8192**. Both are conservative — set them
explicitly for any model whose real limits you know, or you'll silently leave context on the
table.

## Filling in the values

| Field | Set it when | Where to find it |
|---|---|---|
| `id` | Always | The exact model string the provider's API expects — copy it from the provider's model list, not from marketing copy |
| `context_length` | Always, in practice | The provider's model card / docs |
| `max_output_tokens` | Always, in practice | Same. If the provider publishes no figure, use the largest any host documents |
| `vision` | Text-only models | Set `false`. Defaults to `true` |
| `cost.*` | You want spend reported accurately | Provider pricing page. Local / free-tier models: `0.0` |
| `temperature` | The model needs a non-default | Provider guidance |
| `screenshot.max_dimensions` | The model has a tighter image limit | Provider image-input docs |

Full field reference: [LLM Configuration → Model fields](llm_configuration.md#model-fields).

**What Trailblaze actually needs from a model.** The agent loop drives a device by looking at
an annotated screenshot and emitting tool calls, so a model wants **image input** and
**function/tool calling** to be useful. A text-only model (`vision: false`) still works for
text-only flows, but it will struggle on anything that needs to read the screen. Smaller
local models frequently accept the tools and then ignore them — try before you commit a
team-wide default.

## Recipes

### A hosted model on a built-in provider

`openai`, `anthropic`, `google`, `ollama`, and `openrouter` already know their endpoint and
auth env var. You only supply the model:

```yaml
llm:
  providers:
    openrouter:
      models:
        - id: google/gemma-4-31b-it:free
          context_length: 262144
          max_output_tokens: 32768
          cost:
            input_per_million: 0.0
            output_per_million: 0.0
```

### A local Ollama model

```yaml
llm:
  providers:
    ollama:
      models:
        - id: "gemma4:31b"
          context_length: 262144
          max_output_tokens: 8192
  defaults:
    model: "gemma4:31b"
```

Quote Ollama ids — the `:` makes them look like YAML mappings otherwise. Trailblaze also
discovers whatever `ollama list` reports at runtime, so a model already pulled locally shows
up without any config; listing it explicitly is how you tell teammates which model the
project expects. Nothing is auto-downloaded — they run `ollama pull gemma4:31b`.

### A model behind your own gateway

A provider Trailblaze has never heard of is the same amount of work, plus the endpoint:

```yaml
llm:
  providers:
    acme_gateway:
      type: openai_compatible
      base_url: "https://ai.acme.example.com/v1"
      auth:
        env_var: ACME_AI_TOKEN
      models:
        - id: acme-vision-large
          context_length: 200000
          max_output_tokens: 32768
  defaults:
    model: acme-vision-large
```

See [Enterprise gateway](llm_configuration.md#enterprise-gateway) for headers, custom
completion paths, and the on-device story.

### Override a built-in model's specs

Specify only what you're changing:

```yaml
llm:
  providers:
    openai:
      models:
        - id: gpt-5.6-terra
          max_output_tokens: 16384   # our gateway caps output lower than the default
```

## Where to put the file

| Scope | Path |
|---|---|
| Whole team (commit it) | `<workspace>/trails/config/trailblaze.yaml` |
| Just you | `~/.trailblaze/trailblaze.yaml` |

The workspace file wins over the user file; environment variables win over both. Full
precedence table: [Configuration → Precedence](configuration.md#precedence).

Committing the workspace file is the recommended shape for teams — everyone who clones the
repo gets a working model with no per-machine setup, and the project is pinned to models you
have actually validated rather than to whatever the current release happens to ship.

## Verifying

```bash
trailblaze config models    # every model Trailblaze can see, per provider
trailblaze config llm       # the provider/model currently selected
trailblaze config show      # all persisted settings
```

If your model isn't listed, the usual causes are: the file isn't at
`trails/config/trailblaze.yaml` (Trailblaze walks up from the current directory looking for
exactly that path — see [Project Layout](project_layout.md)), the entry is nested under the
wrong provider key, or an unquoted `id` containing `:` parsed as a map.

## Contributing a model to the built-in registry

Once a model is worth having work out of the box for everyone, send it upstream — but note
you never have to wait for that to use it.

1. Add the entry to the matching provider file in
   [`trailblaze-models/src/commonMain/resources/trails/config/providers/`](https://github.com/block/trailblaze/tree/main/trailblaze-models/src/commonMain/resources/trails/config/providers).
2. Regenerate the docs — [`docs/generated/LLM_MODELS.md`](generated/LLM_MODELS.md) is
   generated from those provider files, so hand-editing it drifts and fails CI:

   ```bash
   ./gradlew :docs:generator:run
   ```

3. Commit both the provider YAML and the regenerated `LLM_MODELS.md`.

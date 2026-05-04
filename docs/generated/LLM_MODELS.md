# Built-in LLM Models

Trailblaze ships with the following built-in models. When you reference a model by `id` in your `trails/config/trailblaze.yaml`, all specs below are inherited automatically.

**These models can change between Trailblaze releases** (models added/removed, pricing updated). For stable, predictable configuration, set explicit values in your workspace `trails/config/trailblaze.yaml`.

## Anthropic

| Model ID | Context | Max Output | Input $/1M | Output $/1M | Cached Input $/1M | Capabilities |
|----------|---------|------------|-----------|------------|-------------------|--------------|
| `claude-haiku-4-5` | 200K | 64K | $1.00 | $5.00 | $0.10 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `claude-opus-4-6` | 1M | 128K | $5.00 | $25.00 | $0.50 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `claude-sonnet-4-6` | 1M | 64K | $3.00 | $15.00 | $0.30 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |

## Google

| Model ID | Context | Max Output | Input $/1M | Output $/1M | Cached Input $/1M | Capabilities |
|----------|---------|------------|-----------|------------|-------------------|--------------|
| `gemini-2.5-pro` | 1M | 65K | $1.25 | $10.00 | $0.13 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gemini-3-flash-preview` | 1M | 65K | $0.50 | $3.00 | $0.05 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gemini-3.1-flash-lite-preview` | 1M | 65K | $0.25 | $1.50 | $0.03 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gemini-3.1-pro-preview` | 1M | 65K | $2.00 | $12.00 | $0.20 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gemini-3.1-pro-preview-customtools` | 1M | 65K | $2.00 | $12.00 | $0.20 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |

## Ollama

| Model ID | Context | Max Output | Input $/1M | Output $/1M | Cached Input $/1M | Capabilities |
|----------|---------|------------|-----------|------------|-------------------|--------------|
| `gpt-oss:120b` | 131K | 65K | free | free | free | basic-json-schema, completion, document, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-oss:20b` | 131K | 65K | free | free | free | basic-json-schema, completion, document, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3-vl:2b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3-vl:30b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3-vl:4b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3-vl:8b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:0.8b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:122b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:27b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:2b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:35b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:4b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:9b` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen3.5:latest` | 131K | 8K | free | free | free | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |

## OpenAI

| Model ID | Context | Max Output | Input $/1M | Output $/1M | Cached Input $/1M | Capabilities |
|----------|---------|------------|-----------|------------|-------------------|--------------|
| `gpt-4.1` | 1M | 32K | $2.00 | $8.00 | $0.50 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-4.1-mini` | 1M | 32K | $0.40 | $1.60 | $0.10 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-5` | 400K | 128K | $1.25 | $10.00 | $0.13 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-5-mini` | 400K | 128K | $0.25 | $2.00 | $0.03 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-5-nano` | 400K | 128K | $0.05 | $0.40 | $0.01 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `gpt-5.2` | 400K | 128K | $1.75 | $14.00 | $0.18 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |

## OpenRouter

| Model ID | Context | Max Output | Input $/1M | Output $/1M | Cached Input $/1M | Capabilities |
|----------|---------|------------|-----------|------------|-------------------|--------------|
| `openai/gpt-oss-120b:free` | 131K | 131K | free | free | free | basic-json-schema, completion, document, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |
| `qwen/qwen3-vl-8b-instruct` | 131K | 32K | $0.08 | $0.50 | $0.08 | basic-json-schema, completion, document, image, multipleChoices, openai-endpoint-chat-completions, openai-endpoint-responses, speculation, standard-json-schema, temperature, toolChoice, tools |

## Using Built-in Models in YAML Config

Reference any model above by its ID:

```yaml
providers:
  "openai":
    models:
    - id: "gpt-4.1"
    - id: "gpt-4.1-mini"
      cost:
        input_per_million: 0.3
```

When using a custom endpoint, specify the model specs explicitly. See the tables above for reference values:

```yaml
providers:
  "my_gateway":
    type: "openai_compatible"
    base_url: "https://gateway.example.com/v1"
    models:
    - id: "my-gpt4-deployment"
      vision: true
      context_length: 1048576
      max_output_tokens: 32768
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION

---
title: "Koog Library for LLM Communication"
type: decision
date: 2026-01-28
---

# Koog Library for LLM Communication

Selecting a Kotlin-native library for LLM communication.

## Background

Trailblaze needs to communicate with Large Language Models (LLMs) to power its AI-driven test generation and execution. This requires:

1. A client library that handles LLM API communication
2. Support for multiple LLM providers (OpenAI, Anthropic, Azure, etc.)
3. Compatibility with our Kotlin codebase
4. Multiplatform support for on-device and host-based execution

## What we decided

**Trailblaze uses [Koog](https://github.com/JetBrains/koog)/[koog.ai](https://koog.ai) as its LLM client library.**

### What is KOOG

KOOG (Kotlin AI Orchestration and Operations Gateway) is JetBrains' Kotlin-native library for LLM interactions. It provides:

- A unified API for multiple LLM backends
- First-class Kotlin support with coroutines and type safety
- Kotlin Multiplatform (KMP) support
- Tool/function calling abstractions
- Streaming response support

### Why KOOG

#### 1. Kotlin-Native

KOOG is written in Kotlin for Kotlin developers. It leverages Kotlin idioms like coroutines, sealed classes, and extension functions. This aligns with Trailblaze being a Kotlin-first project (see [Kotlin Language](2026-01-28-kotlin-language.md)).

```kotlin
// KOOG provides idiomatic Kotlin APIs
val response = llm.chat {
    system("You are a UI testing agent...")
    user(buildPrompt(screenState, testStep))
    tools(availableTools)
}
```

#### 2. Standard for Kotlin

KOOG is developed by JetBrains, the creators of Kotlin. This makes it the de facto standard for LLM communication in the Kotlin ecosystem. Using a standard library means:

- Better community support and documentation
- More likely to receive long-term maintenance
- Easier to find developers familiar with the library
- Integration with other JetBrains tooling

#### 3. Multiple Backend Support

KOOG provides a unified interface across LLM providers:

| Provider | Support |
| :--- | :--- |
| OpenAI | Full support |
| Anthropic | Full support |
| Azure OpenAI | Full support |
| Google AI | Full support |
| Local models (Ollama) | Supported |

This allows Trailblaze to:

- Switch providers without code changes
- Use different providers for different use cases
- Support customer/enterprise requirements for specific providers

#### 4. Multiplatform Support

KOOG supports Kotlin Multiplatform (KMP), which is essential for Trailblaze's cross-platform goals:

- **JVM**: Host-based execution on developer machines and CI
- **Android**: On-device agent execution
- **iOS** (future): Potential iOS agent support

The same LLM communication code can run across all platforms.

#### 5. Tool Calling Abstractions

KOOG provides built-in support for function/tool calling, which is central to how Trailblaze agents work. The library handles:

- Tool schema generation from Kotlin types
- Parsing tool calls from LLM responses
- Serialization of tool results back to the LLM

### Integration with Trailblaze

KOOG integrates into the Trailblaze architecture at the LLM communication layer:

```
Agent Loop → KOOG Client → LLM Provider
    ↓            ↓
Tool Execution   Response Parsing
```

The agent loop (see [Agent Loop Implementation](2026-01-28-agent-loop-implementation.md)) calls KOOG to communicate with the LLM, passing screen state and receiving tool calls to execute.

### Authentication and Configuration

LLM authentication is handled through **environment variables** containing API keys. The specific variables depend on the provider:

- **OpenAI**: `OPENAI_API_KEY`
- **Anthropic**: `ANTHROPIC_API_KEY`
- **OpenAI-compatible endpoints**: `OPENAI_API_KEY` + `OPENAI_BASE_URL`

See the [Open Source LLM Documentation](https://block.github.io/trailblaze/llms) for the full list of supported providers and configuration options.

### Model Selection

The LLM model is selected **before test execution** — currently, a single model is used for all requests within a test run. Future enhancements may include:

- Using different models for different request types (e.g., cheaper models for simple decisions, more capable models for complex reasoning)
- Optimizing for cost and speed based on task complexity

### Rate Limiting and Retries

Rate limiting and retry logic is handled at the **agent loop level** rather than within KOOG. The agent loop implements iteration limits and handles transient failures. See [Agent Loop Implementation](2026-01-28-agent-loop-implementation.md) for details on execution control and termination conditions.

### Streaming

While KOOG supports streaming responses, Trailblaze does **not currently use streaming** for LLM responses. This is because the agent primarily operates through tool calls, which are typically returned as complete responses rather than streamed.

However, the **Trailblaze desktop app provides real-time updates** to users — logs and progress are displayed after every step. This "streaming" experience comes from the logging layer, not the LLM client itself. See [Logging and Reporting](2026-01-28-logging-and-reporting.md) for details.

### Token and Cost Tracking

Trailblaze maintains a **list of models and their pricing** to calculate and display:

- **Cost per test**: Individual test execution costs
- **Suite pricing**: Combined pricing for full test suite runs in CI

This helps teams understand and optimize their LLM usage costs.

### Local Models (Ollama)

Support for local models via Ollama serves the **open source community**. This allows developers to:

- Try Trailblaze without paying for or configuring a remote LLM provider
- Run tests in air-gapped or offline environments
- Experiment with different models locally

Ideally, local model support enables Trailblaze to be **used by anyone, anywhere** without requiring paid LLM services.

## What changed

**Positive:**

- Idiomatic Kotlin APIs that fit naturally in our codebase
- JetBrains backing provides confidence in long-term support
- Multi-provider support gives flexibility in LLM selection
- KMP support enables our cross-platform strategy
- Built-in tool calling reduces boilerplate

**Negative:**

- Younger library compared to Python alternatives (LangChain, etc.) — though as noted in [Kotlin Language](2026-01-28-kotlin-language.md), choosing Kotlin inherently means a smaller AI/ML ecosystem, and KOOG is the standard within that ecosystem
- Smaller ecosystem of extensions and integrations
- Must track KOOG releases and handle any breaking changes
- Some advanced features may lag behind Python-first libraries

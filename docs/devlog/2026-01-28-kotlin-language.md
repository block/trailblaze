---
title: "Kotlin as Primary Language"
type: decision
date: 2026-01-28
---

# Kotlin as Primary Language

Choosing a primary language that works across Android, desktop, and server environments.

## Background

Trailblaze is an AI-powered UI testing agent that needs to run in multiple environments:

1. **On Android devices** — directly within the Android instrumentation process, enabling execution on remote device farms without a connected host machine
2. **On host machines** — driving connected devices for local development and CI workflows

We needed a language that could satisfy these runtime requirements while also integrating well with the existing mobile testing ecosystem.

A key requirement was leveraging Block's existing Android device farm infrastructure, which enables massive parallelization for test execution. Running the agent on-device (rather than from a host) allows us to use this infrastructure without building custom connectivity solutions.

## What we decided

**Trailblaze is written in Kotlin.**

### Why Kotlin

#### 1. Android Runtime Compatibility

Kotlin runs natively in Android's runtime environment (ART). This is critical for our on-device execution mode, where the Trailblaze agent runs directly within the Android instrumentation process on the device being tested. No additional runtimes, interpreters, or bridges are needed—Kotlin code compiles to bytecode that runs on the device like any other Android application.

This enables two distinct execution modes:

- **On-Device Android Driver** — The agent runs entirely on the device within instrumentation, ideal for device farm execution
- **Android Host Driver** — The agent runs on a connected machine using standard Maestro mechanics, useful for local development

#### 2. Maestro Integration

The Maestro framework, which provides device drivers and commands for mobile platforms, is written in Kotlin. By choosing Kotlin for Trailblaze, we can:

- Directly leverage Maestro's APIs without cross-language bridges
- Extend and customize Maestro components easily
- Maintain a fork of Maestro's Orchestra code for on-device execution (see [Maestro Integration](2026-01-01-maestro-integration.md))
- Contribute upstream to Maestro when appropriate

For the on-device version of Trailblaze, we exclude Maestro dependencies that are not required for on-device execution (such as JavaScript engines and Playwright web drivers). This keeps the on-device artifact lean. See [Maestro Integration](2026-01-01-maestro-integration.md) for details on our Maestro integration strategy.

#### 3. Compose Web for Reporting

Kotlin enables us to use Compose Web (Wasm) for rendering test reports in the browser. This allows us to share models and UI components between the agent and the reporting interface without any modifications or translation layers. This is critical for delivering rich, interactive reports in CI/CD environments.

This would not be possible with Java, which lacks equivalent web compilation targets.

#### 4. Strong Typing and Developer Experience

Kotlin's type system catches errors at compile time, which is valuable for an agent framework where tool definitions, parameters, and LLM responses need careful handling. IDE support (IntelliJ/Android Studio) provides excellent autocomplete, refactoring, and debugging.

### Considered Alternatives

| Language | Pros | Why Not |
| :--- | :--- | :--- |
| Java | Mature ecosystem, Android-native | No Compose Web support for shared reporting UI |
| Python | Rich AI/ML ecosystem, rapid prototyping | No native Android execution, would require embedding an interpreter |
| TypeScript | Popular for tooling, async-native | Same runtime challenges as Python on Android |

## What changed

**Positive:**

- Single codebase runs on Android devices (via instrumentation) and host machines
- Enables execution on Block's device farm infrastructure without custom solutions
- Seamless integration with Maestro and Android ecosystem
- Shared models and UI components between agent and web-based reports via Compose Web
- Strong typing reduces runtime errors in tool definitions and LLM parsing
- Team can leverage existing Kotlin/JVM expertise

**Negative:**

- Smaller AI/ML library ecosystem compared to Python — however, [KOOG](https://github.com/JetBrains/koog) (JetBrains' Kotlin-native LLM library) enables LLM communication and multi-provider support within the Kotlin ecosystem. See [Koog LLM Client](2026-01-28-koog-llm-client.md).
- Learning curve for team members not familiar with Kotlin

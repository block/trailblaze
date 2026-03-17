---
title: Introduction
---

# 🧭 Trailblaze

[Trailblaze](https://github.com/block/trailblaze) is an AI-powered UI testing framework that lets you author and execute tests using natural language.

![trailblaze-with-goose-android.gif](images-opensource/trailblaze-with-goose-android.gif)

## Current Vision

Trailblaze enables adoption of AI powered tests in regular Android on-device instrumentation tests.
This allows leveraging existing execution environments and reporting systems, providing a path to gradually adopt
AI-driven tests at scale.

Because Trailblaze uses [Maestro](https://github.com/mobile-dev-inc/maestro) Command Models for UI interactions it
enables a [Longer Term Vision](roadmap.md#longer-term-vision) of cross-platform ui testing while reusing the same authoring, agent
and reporting capabilities.

### Core Features

- **AI-Powered Testing**: More resilient tests using natural language test steps
- **On-Device Execution**: Runs directly on Android devices using standard instrumentation tests (Espresso, UiAutomator)
- **[Custom Agent Tools](architecture.md#custom-tools)**: Extend functionality by providing app-specific `TrailblazeTool`s to the agent
- **[Detailed Reporting](logging.md)**: Comprehensive test execution reports
- **Maestro Integration**: Uses a custom on-device driver for Maestro to leverage intuitive, platform-agnostic UI interactions

### Multi-Agent V3 Features

Trailblaze implements cutting-edge features from [Mobile-Agent-v3](https://arxiv.org/abs/2508.15144) research:

- **Exception Handling**: Automatically handles popups, ads, loading states, and errors
- **Reflection & Self-Correction**: Detects stuck states and loops, backtracks when needed
- **Task Decomposition**: Breaks complex objectives into manageable subtasks
- **Cross-App Memory**: Remembers information across app switches for complex workflows
- **Enhanced Recording**: Captures pre/post conditions for more robust replay
- **Progress Reporting**: Real-time MCP progress events for IDE integrations

See the [Architecture](architecture.md#multi-agent-v3-architecture) page for details.

## License

Trailblaze is licensed under the [Apache License 2.0](LICENSE).

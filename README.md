<div style="text-align: center;">

# 🧭 Trailblaze

_[Trailblaze](https://github.com/block/trailblaze) is an AI-powered UI testing framework that lets you author and
execute tests using natural language._

<p style="text-align: center;">
  <a href="https://opensource.org/licenses/Apache-2.0">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg">
  </a>
</p>
</div>

![trailblaze-with-goose-android.gif](docs/images-opensource/trailblaze-with-goose-android.gif)

## Current Vision

Trailblaze enables adoption of AI powered tests in regular Android on-device instrumentation tests.
This allows leveraging existing execution environments and reporting systems, providing a path to gradually adopt
AI-driven tests at scale.

Because Trailblaze uses [Maestro](https://github.com/mobile-dev-inc/maestro) Command Models for UI interactions it
enables a longer term vision of cross-platform ui testing while reusing the same authoring, agent
and reporting capabilities.

### Core Features

- **AI-Powered Testing**: More resilient tests using natural language test steps
- **On-Device Execution**: Runs directly on Android devices using standard instrumentation tests (Espresso, UiAutomator)
- **Custom Agent Tools**: Extend functionality by providing app-specific `TrailblazeTool`s to the agent
- **Detailed Reporting**: Comprehensive test execution reports
- **Maestro Integration**: Uses a custom on-device driver for Maestro to leverage intuitive, platform-agnostic UI interactions

### Multi-Agent V3 Features (Mobile-Agent-v3 Inspired)

Trailblaze implements cutting-edge features from [Mobile-Agent-v3](https://arxiv.org/abs/2508.15144) research:

| Feature | Description |
|---------|-------------|
| **Exception Handling** | Automatically handles popups, ads, loading states, and errors |
| **Reflection & Self-Correction** | Detects stuck states and loops, backtracks when needed |
| **Task Decomposition** | Breaks complex objectives into manageable subtasks |
| **Cross-App Memory** | Remembers information across app switches for complex workflows |
| **Enhanced Recording** | Captures pre/post conditions for more robust replay |
| **Progress Reporting** | Real-time MCP progress events for IDE integrations |

### Trail & Blaze Architecture

Trailblaze's unique "**blaze once, trail forever**" workflow:

- **Blaze Mode**: AI explores the app to achieve objectives, discovering the path dynamically
- **Trail Mode**: Replay recorded actions deterministically with zero LLM cost
- **Hybrid Mode**: Use recordings where available, fall back to AI when needed

```
┌─────────────────────────────────────────────────────────────┐
│                    First Run: BLAZE                          │
│  AI explores → Records actions → Generates .trail.yaml      │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Future Runs: TRAIL                         │
│  Replay recordings → Zero LLM cost → Fast CI/CD execution   │
└─────────────────────────────────────────────────────────────┘
```

### Cloud Device Support (Revyl)

You can run Trailblaze against [Revyl](https://revyl.ai) cloud devices instead of local ADB or Maestro. Use
`RevylMcpServerFactory` to create an MCP server that provisions a device and maps Trailblaze tools to Revyl HTTP APIs.
See the [Revyl integration guide](docs/revyl-integration.md) for prerequisites, architecture, and usage.

## Documentation at <a href="https://block.github.io/trailblaze">block.github.io/trailblaze</a>

See [Mobile-Agent-v3 Features Guide](docs/mobile-agent-v3-features.md) for detailed usage examples.

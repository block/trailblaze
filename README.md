<div style="text-align: center;">

# 🧭 Trailblaze

_[Trailblaze](https://github.com/block/trailblaze) is an AI-powered mobile testing framework that lets you author and
execute tests using natural language._

<p style="text-align: center;">
  <a href="https://opensource.org/licenses/Apache-2.0">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg">
  </a>
</p>
</div>

![trailblaze-with-goose-android.gif](docs/assets/images/trailblaze-with-goose-android.gif)

## Current Vision

Trailblaze enables adoption of AI powered tests in regular Android on-device instrumentation tests.
This allows leveraging existing execution environments and reporting systems, providing a path to gradually adopt
AI-driven tests at scale.

Because Trailblaze uses [Maestro](https://github.com/mobile-dev-inc/maestro) Command Models for UI interactions it
enables a longer term vision of cross-platform ui testing while reusing the same authoring, agent
and reporting capabilities.

### Available Features

- AI-Powered Testing: More resilient tests using natural language test steps
- On-Device Execution: Runs directly on Android devices using standard instrumentation tests (Espresso, UiAutomator)
- Custom Agent Tools: Extend functionality by providing app-specific `TrailblazeTool`s to the agent
- Detailed Reporting: Comprehensive test execution reports
- Maestro Integration: Uses a custom build on-device driver for Maestro to leverage intuitive, platform-agnostic UI
  interactions.

## Documentation at <a href="https://block.github.io/trailblaze">block.github.io/trailblaze</a>

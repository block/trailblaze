# 🧭 Trailblaze

[Trailblaze](https://github.com/block/trailblaze) is an AI-powered mobile testing framework that lets you author and execute tests using natural language.

## Current Vision

Trailblaze enables adoption of AI powered tests in regular Android on-device instrumentation tests.
This allows leveraging existing execution environments and reporting systems, providing a path to gradually adopt
AI-driven tests at scale.

Because Trailblaze uses [Maestro](https://github.com/mobile-dev-inc/maestro) Command Models for UI interactions it
enables a [Longer Term Vision](#Longer-Term-Vision) of cross-platform ui testing while reusing the same authoring, agent
and reporting capabilities.

### Available Features

- AI-Powered Testing: More resilient tests using natural language test steps
- On-Device Execution: Runs directly on Android devices using standard instrumentation tests (Espresso, UiAutomator)
- [Custom Agent Tools](#Custom-Tools): Extend functionality by providing app-specific `TrailblazeTool`s to the agent
- [Detailed Reporting](#Log-Server): Comprehensive test execution reports
- Maestro Integration: Uses a custom build on-device driver for Maestro to leverage intuitive, platform-agnostic UI
  interactions.

## License

Trailblaze is licensed under the [Apache License 2.0](LICENSE).

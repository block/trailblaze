---
title: Getting Started
---
### System Requirements

| | macOS | Linux |
|---|---|---|
| **Desktop App (GUI)** | Supported | Not supported |
| **Headless / CLI / MCP** | Supported | Supported |

- **JDK 17+** is required on all platforms
- **Android SDK** with `adb` on your PATH for on-device testing

### Start the Trailblaze Desktop App
Clone the repo and run the desktop app locally.
```bash
./trailblaze
```
### Set your LLM Provider API Key
Trailblaze includes built-in support for the following providers:
- OpenAI (`OPENAI_API_KEY`)
- Anthropic (`ANTHROPIC_API_KEY`)
- Google (`GOOGLE_API_KEY`)
- OpenRouter (`OPENROUTER_API_KEY`)
- Ollama (no API key required)
1. Set up your provider API key on the development machine in your shell environment.
    ```bash
    export OPENAI_API_KEY="your_api_key_here"
    ```
The desktop app reads these values from your shell environment at startup, so make sure they are set before launching.

For advanced configuration (custom endpoints, enterprise gateways, workspace defaults), see [LLM Configuration](llm_configuration.md). For the full list of built-in models, see [Built-in LLM Models](generated/LLM_MODELS.md).
### Running Your First Trail
Drop a `.trail.yaml` file anywhere in your project and run it:
```bash
trailblaze trail path/to/login.trail.yaml
```
To run multiple trails, pass them explicitly or use a shell glob:
```bash
trailblaze trail flows/**/*.trail.yaml
```
No `trails/` directory required — put trail files wherever makes sense and glob them into the CLI. For the full rules (file naming, workspace config, symlinks), see [Project Layout](project_layout.md).
### Next: Android On-Device Testing
If you want to run tests on Android devices or emulators, see [Android On-Device Testing](android_on_device.md).
### Optional: Host JVM Unit Tests
If you need to run Trailblaze from JUnit (not the desktop app), see
[Host JVM Unit Tests](host_jvm_unit_tests.md).
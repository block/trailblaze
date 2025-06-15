# 🧭 Trailblaze

Trailblaze is an AI-powered mobile testing framework that lets you author and execute tests using natural language.

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

## Roadmap

### Upcoming Features

- Standardized YAML Format: This will provide a static way to express your Trailblaze tests mixing natural language
  objectives and static steps
- Recording: Save any agent tools and ui interactions, then play them back to save costs during future runs.
- Test Failure Recovery with AI: When a recorded test fails, use the AI Agent to recover and complete the objectives.
  Then leverage the reporting tools to show divergence from the original path.
- MCP Server: Interactively create new tests and manage your test suite.
- Bring your own LLM Provider: OpenAI support currently, but future plans for other providers.
- LLM call proxying support (optional): Provide support for proxying traffic to the LLM, removing the requirement of
  passing an API key the mobile device as an execution argument.

### Longer Term Vision

- iOS testing support
- Web testing support
- Host mode execution: Execute the tests in an environment connected to a device or emulator to allow more flexibility
  in test execution.

### Overall Vision

- Create an open platform for authoring and executing tests using agentic AI
- Enable teams to ship faster by lowering the bar to test execution

## Architecture

Trailblaze uses a completely on-device agent architecture that:

- Integrates with existing test infrastructure
- Provides a custom Maestro driver for UI interaction
- Supports custom, app-specific agent tools
- Uses OpenAI (other provider support coming soon) for natural language processing

## Getting Started

### Add the Trailblaze `androidTest` Gradle Dependency

Trailblaze was just open sourced and is not yet published as an artifact. You can try it out with the `examples` project
for now by cloning the repo and building as source.

### Set your OpenAI API Key

1. Set up your OpenAI API key on the development machine in your shell environment.

    ```bash
    export OPENAI_API_KEY="your_api_key_here"
    ```

2. Pass the `OPENAI_API_KEY` environment variable to the Android instrumentation process

   This passes your development machine environment variable to Android Instrumentation. This is recommended to avoid
   inadvertently committing API keys into `git`.

    ```groovy
    android {
        defaultConfig {
          System.getenv("OPENAI_API_KEY")?.let { apiKey ->
            testInstrumentationRunnerArguments["OPENAI_API_KEY"] = apiKey
          }
        }
    }
    ```

### Writing Your First Test

```kotlin
class MyTrailblazeTest {

  @get:Rule
  val trailblazeRule = TrailblazeRule()

  @Test
  fun testLoginFlow() {
    trailblazeRule.run(
      """
            Navigate to the login screen
            Enter email 'test@example.com'
            Enter password 'password123'
            Click the login button
            Verify we reach the home screen
        """.trimIndent()
    ).execute()
  }
}
```

## Custom Tools

Trailblaze allows you to create custom tools for app-specific testing needs.
Use this if your application has unique user interactions that do not fit existing tools, or provide
compound tools to perform multiple actions at once.
Here's an example:

```kotlin
@Serializable
@TrailblazeToolClass("signInWithEmailAndPassword")
@LLMDescription("Sign in to the application using email and password.  Prefer this tool over manual commands when you are on the page with just the 'Sign in' and 'Create account' options.")
data class SignInTrailblazeTool(
  val email: String,
  val password: String
) : MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> =
    MaestroYaml.parseToCommands(
      """
            - tapOn:
                text: "Sign in"
            - inputText:
                text: "$email"
            - tapOn:
                text: "Next"
            - inputText:
                text: "$password"
            - tapOn:
                text: "Sign in"
            - waitForAnimationToEnd
        """.trimIndent()
    )
}
```

## Log Server

Observability of the test execution and agent performance is crucial to adopting AI-based tests.
Trailblaze includes thorough logging capabilities in all phases of the test execution.

### Start the Server

Run the following to start the server:

```bash
# Runs ./gradlew :trailblaze-server:run
./server
```

This initializes a local web server that Trailblaze will send all the logs to on your local machine.
Logs _will not_ be collected if the server is not running.
To view them open [http://localhost:52525](http://localhost:52525) in your browser.

### Understanding Test Logs

As tests are executed locally the server will show a list of all Trailblaze Sessions (i.e. all the test runs).
Selecting an individual test will show all the logs which includes:

- Success or failure message
- The prompt provided to the agent
- The list of steps taken during the test
- LLM usage summary

The individual steps are broken up and color coded into a few buckets:

- Session status denotes the start and end of the test
- LLM Request represent each request sent to the LLM
- Trailblaze command denotes which tool call the LLM decided to use for the request
- Maestro command denotes Maestro commands sent to the device or emulator. Each Trailblaze command maps to one or many
  Maestro commands to execute.
- Maestro driver denotes the action Maestro took based on the provided Maestro Command

## License

Trailblaze is licensed under the [Apache License 2.0](LICENSE).

## Support

- [GitHub Issues](https://github.com/block/trailblaze/issues)

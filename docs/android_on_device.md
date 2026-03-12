---
title: Android On-Device Testing
---
### Add the Trailblaze `androidTestImplementation` Gradle Dependency
Trailblaze was just open sourced and is not yet published as an artifact. You can try it out with the `examples` project
for now by cloning the repo and building as source.
### Pass your LLM Provider API Key to Instrumentation
1. Set up your provider API key on the development machine in your shell environment.
   The environment variable names are defined in `LlmProviderEnvVarUtil`.
   If you are using Ollama, no API key is required.
2. Pass the environment variable to the Android instrumentation process.
   This passes your development machine environment variable to Android Instrumentation. This is recommended to avoid
   inadvertently committing API keys into `git`.
   Use the same key name (for example `OPENAI_API_KEY`) that you set in your shell.
```kotlin
android {
  defaultConfig {
    val providerKey = "OPENAI_API_KEY"
    System.getenv(providerKey)?.let { apiKey ->
      testInstrumentationRunnerArguments[providerKey] = apiKey
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
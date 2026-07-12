---
title: Android On-Device Testing
---
### Install the Trailblaze CLI
Install `trailblaze` on your `PATH` first — `brew install block/tap/trailblaze` is the
quickest path. The instructions below assume the CLI is reachable so on-device
instrumentation tests can be authored and run against the sample project under
`examples/`.
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
  val trailblazeRule = AndroidTrailblazeRule()
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
    )
  }
}
```

### Auto-generate JUnit shells from `.trail.yaml` files
When you have many trails recorded as `.trail.yaml` files, the
`xyz.block.trailblaze.android-gradle` Gradle plugin can
generate the one-liner JUnit shell for each trail automatically — drop a
`<methodName>.trail.yaml` under `src/androidTest/assets/trails/<ClassName>/`
and the matching `@Test fun <methodName>()` appears on the next build, no
Kotlin edit. Useful for modules with dozens of trails where every shell is the
same `rule.runFromAsset(...)` boilerplate.

See the plugin's
[README](https://github.com/block/trailblaze/tree/main/trailblaze-android-gradle)
for the application snippet and the inline-rule / extending-base emit modes.

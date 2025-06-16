### Add the Trailblaze `androidTestImplementation` Gradle Dependency

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

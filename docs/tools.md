
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

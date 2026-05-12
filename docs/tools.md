---
title: Trailblaze Tools
---

# Trailblaze Tools

Trailblaze ships a catalog of framework tools (`tap`, `inputText`, `pressBack`,
`assertVisible`, …) that cover most UI automation. When your app or test surface needs
something the catalog doesn't have, you add **custom tools** to a workspace pack.

There are three flavors of custom tool. Pick by what kind of body you need to write:

1. **Pure-YAML composed tool** (`<pack>/tools/<id>.tool.yaml`) — a declarative
   composition that substitutes parameters into existing framework tool calls. No code.
   Auto-discovered; reference it by name from a toolset. The most common shape — start
   here for thin wrappers like "press back twice" or "open the search URL for `<topic>`."

2. **Scripted (TypeScript) tool** (`<pack>/tools/<id>.yaml` paired with `<id>.ts`) — a
   handler function in JavaScript/TypeScript with access to `client.callTool(...)` for
   nested framework-tool dispatch. Use when you need branching, retries, async
   coordination, or anything else the YAML form can't express. Listed in `pack.yaml`'s
   `target.tools:`.

3. **Kotlin-backed tool** (`@TrailblazeToolClass`-annotated data class) — a Kotlin
   `TrailblazeTool` implementation. Use when you need to read host-side state that
   neither YAML composition nor the scripting SDK exposes today. Lives in your own
   Kotlin source tree (host build); registered via `ToolYamlConfig` `class:` mode under
   `trailblaze-config/tools/<id>.tool.yaml`.

Deep dives:

- **Pack authoring & both YAML flavors** — see [Packs](packs.md), specifically the
  "Tool flavors: which kind do I write?" rubric and the per-suffix schema reference.
- **TypeScript scripted tools end-to-end** — see
  [Author Your First Scripted Tool](scripted_tools.md), covering the descriptor schema,
  the `.ts` handler signature, the scripting SDK (`client.callTool`, `ctx.target`,
  memory), and the host-vs-on-device dispatch contract.

Kotlin-backed tool example:

```kotlin
@Serializable
@TrailblazeToolClass("signInWithEmailAndPassword")
@LLMDescription(
  "Sign in to the application using email and password. Prefer this tool over " +
    "manual commands when you are on the page with just the 'Sign in' and 'Create " +
    "account' options.",
)
data class SignInTrailblazeTool(
  val email: String,
  val password: String,
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
      """.trimIndent(),
    )
}
```

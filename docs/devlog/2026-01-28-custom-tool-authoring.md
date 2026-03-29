---
title: "Custom Tool Authoring"
type: decision
date: 2026-01-28
---

# Custom Tool Authoring

Enabling teams to extend the framework with domain-specific tools.

## Background

Trailblaze's AI agent interacts with applications through tools—discrete operations like "tap on element," "enter text," or "verify screen content." While general-purpose tools handle most UI interactions, **custom tools** are critical for:

1. **Speed** — A custom tool can perform complex multi-step operations in a single call, reducing LLM round-trips
2. **Reliability** — Custom tools can encode domain-specific knowledge, handle edge cases, and provide deterministic behavior where general tools might be brittle
3. **Abstraction** — Custom tools hide implementation complexity from the LLM, making prompts simpler and more focused

Example: A `login(username, password)` custom tool is faster and more reliable than instructing the LLM to "tap the username field, enter the username, tap the password field, enter the password, tap the login button."

## What we decided

**Custom tools are currently authored in Kotlin code and registered programmatically with the agent.**

### Current Approach

Tools are defined as Kotlin classes or functions that:

1. Implement a tool interface with name, description, and parameters
2. Contain execution logic that interacts with the device (via Maestro, ADB, etc.)
3. Return results that the LLM can interpret
4. Are registered with the agent at initialization time

```kotlin
// Simplified example of custom tool definition
class LoginTool : Tool {
    override val name = "login"
    override val description = "Log into the app with provided credentials"
    override val parameters = listOf(
        Parameter("username", "string", "The username to log in with"),
        Parameter("password", "string", "The password to use")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // Implementation using Maestro/device commands
    }
}
```

### Workflow for Adding Custom Tools

1. Write tool implementation in Kotlin
2. Add tool to the appropriate module (framework or internal)
3. Register tool with the agent
4. Rebuild and redeploy

### Recognized Limitations

This approach has significant friction:

- **Heavy process**: Adding a tool requires code changes, compilation, and redeployment
- **Developer expertise required**: Tool authors must know Kotlin and the Trailblaze internals
- **Slow iteration**: Testing and refining tools requires the full build cycle
- **Limited accessibility**: Non-developers (QA engineers, product managers) cannot easily create or modify tools

### Future Opportunities

We recognize this is an area for improvement. Potential future directions include:

- **Declarative tool definitions**: YAML/JSON specifications that can be loaded at runtime
- **Scripting layer**: Lightweight scripting (e.g., Kotlin Script, or an embedded language) for tool logic
- **Dynamic tool loading**: Hot-reload tools without agent restart
- **Visual tool builder**: UI for non-developers to compose tools from primitives
- **MCP tool sources**: Loading tools dynamically from MCP servers

These enhancements would lower the barrier to creating custom tools while preserving the power and flexibility of native Kotlin tools when needed.

## What changed

**Positive:**

- Full power of Kotlin for complex tool implementations
- Type safety and IDE support during development
- Tools can leverage any Kotlin/JVM library
- Consistent with the rest of the Trailblaze codebase

**Negative:**

- High barrier to entry for tool authoring
- Slow iteration cycle (code → compile → deploy → test)
- Requires developer involvement for all tool changes
- Not accessible to non-technical team members

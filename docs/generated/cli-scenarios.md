# CLI Usage Scenarios

Auto-generated from `@Scenario` test annotations. Each scenario has a passing test that verifies the behavior.

## Configuration

### Configure CLI settings

Read or write CLI configuration keys. Valid keys: llm, ai-fallback, agent, android-driver, ios-driver, mode, device, target. Values are validated before persisting.

**CLI:**

```bash
trailblaze config llm anthropic/claude-sonnet-4-6
trailblaze config ai-fallback true
trailblaze config agent MULTI_AGENT_V3
```

_Verified by: `CliCommandValidationTest.config executeConfig with unknown key returns USAGE`_

---

### Specify a target app for the session

The --target flag selects which app configuration to use, enabling target-specific tools and launch behavior.

**CLI:**

```bash
trailblaze blaze --target myapp Tap login
```

_Verified by: `CliCommandValidationTest.picocli parses blaze target flag`_

---

### Set or list the target app

The target subcommand sets the active app target when given an ID, or lists available targets when called without arguments.

**CLI:**

```bash
trailblaze config target myapp
trailblaze config target
```

_Verified by: `CliCommandValidationTest.picocli parses config target subcommand with ID`_

---

## Direct Tool Execution

### Execute tools directly with a natural language objective

Use `trailblaze tool` with `--yaml` for direct tool execution. The -o flag provides a natural language objective so the step is recorded with context for self-healing replays.

**CLI:**

```bash
trailblaze tool tap ref=p386 --device=android -o "Tap the Sign In button"
```

_Verified by: `CliCommandValidationTest.blaze without goal returns USAGE`_

---

### Verify a UI assertion without executing actions

The --verify flag runs observation-only: the agent checks whether a condition holds on the current screen without tapping or typing.

**CLI:**

```bash
trailblaze blaze --verify Check the login button is visible
```

_Verified by: `CliCommandValidationTest.picocli parses blaze verify flag`_

---

### MCP: Execute YAML tools directly via blaze

MCP clients pass YAML tool sequences to blaze(). Tools execute sequentially, bypassing the AI agent. The step is recorded with the NL objective for trail quality.

**MCP:**

```
blaze(objective="Sign in", tools="- tap: {x: 100, y: 200}\n- tap: {x: 300, y: 400}")
```

_Verified by: `StepToolSetDirectToolsTest.direct tools - happy path executes tools and returns success`_

---

## Tool Discovery

### Discover available tools for current target

Shows platform toolsets and target-specific tools. Use before constructing --yaml tool sequences.

**CLI:**

```bash
trailblaze toolbox
```

**MCP:**

```
toolbox()
```

_Verified by: `ToolDiscoveryToolSetTest.INDEX mode without target or platform shows platform toolsets and all other targets`_

---

### Get detailed tool descriptors with parameters

Expands each tool with full parameter descriptors (name, type, description). Useful for constructing --yaml tool sequences with correct parameter names.

**CLI:**

```bash
trailblaze toolbox --detail
```

**MCP:**

```
toolbox(detail=true)
```

_Verified by: `ToolDiscoveryToolSetTest.INDEX mode with detail=true includes full tool descriptors`_

---

### Look up a specific tool by name

Returns the full descriptor for a single tool, including which categories it belongs to.

**CLI:**

```bash
trailblaze toolbox --name tapOnPoint
```

**MCP:**

```
toolbox(name="tapOnPoint")
```

_Verified by: `ToolDiscoveryToolSetTest.NAME mode finds tool from available tools`_

---

### Query tools for a specific target app

Returns target-specific info including supported platforms and custom tools registered for that app.

**CLI:**

```bash
trailblaze toolbox --target myapp
```

**MCP:**

```
toolbox(target="myapp")
```

_Verified by: `ToolDiscoveryToolSetTest.TARGET mode returns target info for valid target`_

---

### Search tools by keyword

Searches tool names and descriptions for matching keywords. Results include full descriptors with source info.

**CLI:**

```bash
trailblaze toolbox --search launch
```

**MCP:**

```
toolbox(search="launch")
```

_Verified by: `ToolDiscoveryToolSetTest.SEARCH mode finds tools matching keyword in name`_

---

## Trail Management

### Save session as trail file

The --save flag writes the session to a trail file. Use --setup to mark leading steps as setup, or --no-setup to mark none. One of --setup or --no-setup is required with --save.

**CLI:**

```bash
trailblaze blaze --save trails/test.trail.yaml
trailblaze blaze --save trails/test.trail.yaml --setup 1-3
trailblaze blaze --save trails/test.trail.yaml --no-setup
```

_Verified by: `CliCommandValidationTest.blaze -- setup without save returns USAGE`_

---

### MCP: Recorded steps include tool call details

When recording is active, each blaze() call records the objective, executed tools, and success/failure status for trail replay.

**MCP:**

```
blaze(objective="Tap the login button", tools="- tapOnPoint:\n    x: 100\n    y: 200")
```

_Verified by: `StepToolSetDirectToolsTest.direct tools - successful execution records step with correct type and tool calls`_

---

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION

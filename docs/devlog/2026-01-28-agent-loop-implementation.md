---
title: "Handwritten Agent Loop"
type: decision
date: 2026-01-28
---

# Handwritten Agent Loop

A core architectural choice — why we hand-wrote the agent loop instead of using a framework.

## Background

AI agents require an execution loop that orchestrates:

1. Gathering context (screen state, test instructions, history)
2. Calling the LLM for reasoning and tool selection
3. Executing selected tools
4. Processing results and deciding next steps
5. Handling errors and retries
6. Recording successful runs

Many agent frameworks exist (LangChain, AutoGen, CrewAI, etc.) that provide abstractions for this loop. We needed to decide whether to adopt an existing framework or implement our own.

## What we decided

**Trailblaze uses a handwritten while loop for its core agent execution.**

### Implementation Overview

The agent loop is a straightforward `while` loop that continues until the test completes (success or failure) or a termination condition is met:

```kotlin
// Simplified conceptual representation
suspend fun runAgent(objective: Objective): TestResult {
    val completedTools = mutableListOf<CompletedTool>()

    while (completedTools.size < MAX_ITERATIONS) {
        // 1. Capture current screen state
        val screenshot = captureScreenshot()
        val viewHierarchy = captureViewHierarchy()

        // 2. Build fresh LLM request with current context
        val request = buildRequest(
            systemPrompt = SYSTEM_PROMPT,
            objective = objective,
            completedTools = completedTools,
            screenshot = screenshot,
            viewHierarchy = viewHierarchy
        )

        // 3. Call LLM for next action
        val response = llmClient.chat(request)

        // 4. Execute tool calls sequentially
        for (toolCall in response.toolCalls) {
            val result = driver.executeTool(toolCall)
            completedTools.add(CompletedTool(toolCall, result))

            // Check for terminal conditions
            if (result.isObjectiveComplete || result.isFailure) {
                return result.toTestResult()
            }
        }
    }

    return TestResult.Timeout("Exceeded $MAX_ITERATIONS iterations")
}
```

### Why Handwritten

#### 1. Simplicity and Transparency

A while loop is easy to understand, debug, and modify. New team members can read the code and understand exactly what the agent does. There's no framework abstraction layer to learn or work around.

#### 2. Control Over Execution

We have precise control over:

- When and how the LLM is called
- How context is constructed for each request
- Tool execution ordering and sequencing
- What gets recorded and when
- Termination conditions and limits

#### 3. Mobile-Specific Requirements

Trailblaze has unique requirements that existing agent frameworks don't address:

- On-device execution with resource constraints
- Integration with platform drivers for device interactions
- Trail recording format (see [Trail Recording Format](2025-10-01-trail-recording-format.md))
- Trail mode that replays recorded tool sequences without LLM calls

#### 4. Avoiding Dependency Risk

Agent frameworks are evolving rapidly. Depending on an external framework means:

- Tracking breaking changes in a fast-moving ecosystem
- Working around framework limitations
- Framework bugs becoming our bugs
- Potential abandonment or direction changes

### Loop Termination

The loop terminates under the following conditions:

- **Objective completion**: The agent calls the `objectiveStatus` tool with a `COMPLETED` or `FAILED` status, indicating the test objective has been achieved or cannot be completed
- **Assertion failure**: An assertion tool (e.g., `assertVisibleWithText`) fails, indicating an unexpected state
- **Element not found**: A required UI element cannot be located after the agent's attempts
- **Iteration limit**: A maximum of **50 LLM calls per step** prevents runaway execution

Future improvements may include more sophisticated loop detection to identify when the agent is stuck repeating ineffective actions.

### Tool Execution

Tools execute **sequentially**, one at a time. Parallel tool execution is not supported because Trailblaze interacts with a UI—only one interaction can happen at a time on a device.

Tools execute **once** without automatic retries at the loop level. If a tool needs retry logic, it must implement that internally. When a tool completes (successfully or not), the agent proceeds based on the result:

- For terminal results (assertions, objective status), the loop may end
- For non-terminal results, the agent continues and relies on subsequent steps to detect any issues

Tool calls delegate to platform drivers (Android or iOS) to perform actual device interactions. The Trailblaze tools provide a high-level abstraction, while drivers handle the device-specific implementation details.

### Context Window Management

Rather than maintaining a growing conversation history, Trailblaze constructs **each LLM request fresh**. On every iteration, the agent sends:

- System prompt with instructions
- Current objective
- List of previously completed tools (providing execution history)
- Latest screenshot
- Current view hierarchy

This "subagent" pattern keeps the context window manageable—typically under 10,000 input tokens—well within LLM limits. By always including the latest screen state and omitting stale information, we reduce LLM confusion and improve decision quality.

### Running Trails (Replay Mode)

When a test has a recorded trail, it can run in **trail mode** which bypasses the LLM entirely. The recorded tool sequence from the `.trail.yaml` file executes deterministically. See [Trail Recording Format](2025-10-01-trail-recording-format.md) for details on how trails are structured and when they're used.

### What the Loop Handles

- **Context construction**: Building fresh LLM requests with current screen state, objectives, and execution history
- **LLM communication**: Calling the LLM, parsing responses, extracting tool calls
- **Tool execution**: Invoking tools sequentially, delegating to platform drivers
- **Recording**: Capturing successful tool sequences for trail replay
- **Termination**: Recognizing completion, failure, and limit conditions

## What changed

**Positive:**

- Complete control over agent behavior
- Easy to understand, debug, and modify
- No external framework dependencies to manage
- Can optimize for mobile and on-device constraints
- Straightforward to add new capabilities

**Negative:**

- Must implement features that frameworks provide out-of-the-box
- No automatic benefit from framework improvements
- Requires more upfront implementation work
- Team must maintain all agent logic internally

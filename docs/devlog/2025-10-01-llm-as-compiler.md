---
title: "LLM as Compiler Architecture"
type: decision
date: 2025-10-01
---

# LLM as Compiler Architecture

The core architectural insight behind Trailblaze — treating the LLM as a compiler rather than a chatbot.

## Background

Traditional UI test frameworks require developers to write explicit, imperative test code. We want to enable natural language test authoring while maintaining deterministic execution.

## What we decided

Trailblaze treats the **LLM as a compiler** that transforms natural language test cases into deterministic tool sequences.

### The Compiler Metaphor

```
Natural Language  →  LLM + Agent + Tools  →  Trail Recording
   (Source)              (Compiler)           (Output/IR)
```

| Concept | Traditional Compiler | Trailblaze |
| :--- | :--- | :--- |
| Source | Code (.c, .kt) | Natural language test steps |
| Compiler | gcc, kotlinc | LLM + Trailblaze Agent |
| IR/Output | Assembly, bytecode | Trail YAML (tool sequence) |
| Runtime | CPU, JVM | Device + Maestro/Tools |

### Compilation Flow

```
Test Case Steps → LLM interprets steps → Execute tools on device
        ↓                    ↓                       ↓
  Natural Language    Agent orchestration    Success/Failure
        ↓                    ↓                       ↓
                      On failure: retry      Record successful run
                      with context           as .trail.yaml
```

### Key Properties

- **Compilation happens once**: First successful run is recorded
- **Replay is deterministic**: Subsequent runs use recording, no LLM needed
- **Self-healing on failure**: LLM can adapt and retry when UI changes
- **Recompilation on demand**: Force AI mode to generate new recording

### Agent Loop

1. LLM receives test step + current screen state
2. LLM selects and invokes tools
3. Tools execute via Maestro/device drivers
4. On success → record tool invocation
5. On failure → provide error context, retry
6. After all steps → save complete `.trail.yaml`

## What changed

**Positive:** Natural language authoring, deterministic replay, self-healing capability, familiar mental model for engineers.

**Negative:** Initial "compilation" requires LLM (cost/latency); recordings may need "recompilation" when UI changes significantly.

---
title: "Tool Execution Modes"
type: decision
date: 2026-01-01
---

# Tool Execution Modes

As the tool system grew, we needed to formalize how tools execute across different environments.

## Background

Tools in Trailblaze serve different purposes. Some are meant for LLM selection, while others are precise implementation details for recordings. We need a way to classify tools by their execution characteristics.

## What we decided

Tools declare two boolean properties that determine their execution mode:

```kotlin
@TrailblazeToolClass(
    name = "tapOnElementWithText",
    isForLlm = true,       // Can LLM select this tool?
    isRecordable = true,   // Can this tool appear in recordings?
)
```

### Property Definitions

| Property | Default | Meaning |
| :--- | :--- | :--- |
| `isForLlm` | `true` | Whether the LLM can select this tool. Set to `false` for implementation-detail tools that use unstable identifiers. |
| `isRecordable` | `true` | Whether this tool can appear in trail recordings. Set to `false` for wrapper tools that delegate to more precise tools. |

### Tool Type Matrix

| `isForLlm` | `isRecordable` | Type | Use Case |
| :--- | :--- | :--- | :--- |
| `true` | `true` | **Standard** | Normal tools (default) |
| `true` | `false` | **LLM-only** | Wrapper tools that delegate to more precise tools |
| `false` | `true` | **Recording-only** | Precise tools with unstable identifiers |
| `false` | `false` | **Internal** | Helper tools, not directly usable |

### Use Cases

**Standard (`isForLlm=true`, `isRecordable=true`)**: Most tools. LLM can select them, and they appear in recordings.

**LLM-only (`isForLlm=true`, `isRecordable=false`)**: High-level tools the LLM selects, but recordings capture the delegated call instead.

```kotlin
// LLM selects this (stable, text-based)
@TrailblazeToolClass(name = "tapOnElementWithText", isForLlm = true, isRecordable = false)
class TapOnElementWithText : Tool {
    override fun execute(args: Args): Result {
        val nodeId = findNodeIdByText(args.text)
        return tapOnElementByNodeId.execute(nodeId)  // Recording captures this
    }
}
```

**Recording-only (`isForLlm=false`, `isRecordable=true`)**: Precise tools that use unstable identifiers (node IDs, coordinates). Not LLM-selectable because the identifiers change between runs.

```kotlin
// Recording stores this (precise, but node IDs are unstable)
@TrailblazeToolClass(name = "tapOnElementByNodeId", isForLlm = false, isRecordable = true)
class TapOnElementByNodeId : Tool { ... }
```

## What changed

**Positive:** Clean separation between LLM-facing and implementation tools; recordings can use more precise identifiers than what LLM reasons about.

**Negative:** Requires understanding the delegation pattern; tool authors must choose modes deliberately.

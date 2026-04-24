---
title: "Trail/Blaze Agent Architecture"
type: decision
date: 2026-02-04
---

# Trail/Blaze Agent Architecture

## Status

**Accepted** - `MULTI_AGENT_V3` is the active modern agent path. `TWO_TIER_AGENT` has been removed.

| Implementation | Status | Description |
|----------------|--------|-------------|
| `TRAILBLAZE_RUNNER` | **Keep** | Battle-tested YAML-based loop. Do not modify. |
| `MULTI_AGENT_V3` | **Active** | This decision. Koog planners + trail/blaze modes. |

## Context

The two-tier agent architecture (Decision 025, superseded) separates screen analysis from planning but has limitations: fixed two tiers, custom agent loops instead of Koog's proven strategies, no distinction between executing known paths vs. exploring new ones, and limited composability.

Research from [Mobile-Agent-v3](https://arxiv.org/abs/2508.15144) demonstrates that multi-agent GUI automation with specialized components (planning, reflection, memory) achieves significantly better results — **73.3 on AndroidWorld** vs 66.4 with their foundational model alone.

Trailblaze serves two fundamentally different use cases:

| Use Case | Input | Goal | Recordings |
|----------|-------|------|------------|
| **Automated Testing** | `.trail.yaml` with steps + recordings | Execute known path reliably | Consumed |
| **Mobile Device Control** | Natural language objective | Accomplish goal | Generated (optional) |

## Decision

Implement a **Trail/Blaze agent architecture** using Koog's native strategy infrastructure:

- **`trail<>`** - Execute a known path from a trail file using **goal-oriented action planning**
- **`blaze<>`** - Explore and accomplish an objective using a **strategy graph**, optionally generating a trail

Both leverage Koog's `AIAgent` infrastructure rather than custom loops.

```
trail<>                              blaze<>
"Follow the path"                    "Cut a new path"
─────────────────                    ─────────────────
Input: .trail.yaml                   Input: Natural language
Pattern: Koog Goal Planner           Pattern: Koog Strategy Graph
Planning: A* through steps           Planning: Dynamic per-screen
Recordings: CONSUMED                 Recordings: GENERATED
Speed: Fast (cached paths)           Speed: Slower (exploring)

Workflow:
  blaze("objective") → generates → trail.yaml → trail(file) → executes
        ↑                                              │
        └──────── AI Fallback when recording fails ────┘
```

## trail<>: Goal Planner with Predefined Actions

**When a trail file has complete recordings for all steps, execution uses zero LLM calls.** The A* search through predefined steps is deterministic — no LLM needed when the plan is already known. LLM calls only happen for steps without recordings or recordings that fail at runtime (if `selfHealEnabled`).

| Scenario | Executor | LLM Calls |
|----------|----------|-----------|
| All steps have recordings, strict mode | `DeterministicTrailExecutor` | **0** |
| All steps have recordings, fallback enabled | `DeterministicTrailExecutor` | 0 (unless recording fails) |
| Some steps missing recordings | `GoalPlannerTrailExecutor` | Only for missing steps |
| Complex branching/conditional trails | `GoalPlannerTrailExecutor` | As needed |

Goal planner mapping: each `step:` prompt becomes an action with preconditions (step N requires N-1 done), `recording.tools` provide optimistic beliefs, cost model prefers recordings (cost 1.0) over AI (cost 5.0), and failed recordings trigger AI Fallback ([Decision 021](./2026-01-29-ai-fallback.md)).

## blaze<>: Strategy Graph for Exploration

For exploratory mobile control, we use Koog's [custom strategy graphs](https://docs.koog.ai/complex-workflow-agents/):

```
nodeStart → nodeCapture → nodeAnalyze → nodeDecide
                               ↑              │
                               │              ↓
                         nodeExecute ← [Continue]
                               │
                               ├─ [Complete] → nodeFinalize → nodeFinish
                               └─ [Failed] → nodeFinish

Optional tiers (Mobile-Agent-v3 inspired):
  • nodeReflect - Review actions, suggest corrections
  • nodeProgress - Track multi-step progress
  • nodeMemory - Persist cross-context information
```

Blaze accumulates `RecordedAction` entries during exploration. On success, these convert to trail steps via `toTrailSteps()`, enabling the blaze→trail workflow: explore once, replay deterministically.

## MCP Integration

When `blaze()` runs via MCP, progress callbacks report iteration status, action summaries, and objective progress to the MCP client. An optional interactive mode allows the client to inspect, redirect, or abort mid-execution.

## Dynamic Tool Management

`trail<>` uses a **fixed tool set** based on what the trail requires. `blaze<>` can **dynamically request additional tool categories** as it discovers needs, leveraging the existing `DynamicToolSetManager` infrastructure.

## What changed

**Positive:**
- Unified Koog infrastructure for both modes (same `AIAgent`, same state management)
- A* cost optimization naturally prefers recordings over AI
- Both modes support replanning and recovery from failures
- Extensible via strategy graph nodes (reflection, memory, progress tracking)
- Clear separation: `trail<>` for reliability, `blaze<>` for exploration

**Negative:**
- Additional Koog dependency (`koog-agents-planner`)
- Goal planner may be overkill for linear step sequences
- Two execution paths to maintain

## Related Documents

- [021: AI Fallback](./2026-01-29-ai-fallback.md) - Integrated into `trail<>` execution
- [002: Trail Recording Format](2025-10-01-trail-recording-format.md) - Trail file format used by both modes
- [011: Agent Loop Implementation](2026-01-28-agent-loop-implementation.md) - Custom loops being replaced
- [012: Koog LLM Client](2026-01-28-koog-llm-client.md) - Koog integration foundation
- [Mobile-Agent-v3 Paper](https://arxiv.org/abs/2508.15144) - Multi-agent GUI automation research
- [Koog Complex Workflow Agents](https://docs.koog.ai/complex-workflow-agents/) - Strategy graph documentation

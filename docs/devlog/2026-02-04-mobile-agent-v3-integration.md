---
title: "Mobile-Agent-v3 Integration Plan"
type: decision
date: 2026-02-04
---

# Trailblaze Decision 032b: Mobile-Agent-v3 Integration Plan

## Executive Summary

Integrates [Mobile-Agent-v3](https://arxiv.org/abs/2508.15144) innovations into Trailblaze while preserving our **trail/blaze** architecture. Key decisions:

- **Tiered model approach**: Frontier models for vision/reasoning, mini models for text-only planning. No fine-tuned models (e.g., GUI-Owl) — frontier models improve over time without maintenance.
- **"Blaze once, trail forever"**: After exploration, the recorded trail executes with **zero LLM calls**, making CI/CD runs free at any scale.

## Deployment Architecture

### Host Mode (Development)

Agent runs on the **desktop machine**, controls devices remotely via ADB/XCTest/DevTools.

**Use cases:** Local development, recording new trails, interactive testing.

### Remote Device Farm Mode (CI/CD)

Agent runs **on the Android device** inside the test APK. Same `trailblaze-agent` code, different execution context.

**Why:** Remote device farms (Firebase Test Lab, AWS Device Farm) only provide the device — no external host process. Test APKs must be self-contained.

| Component | Host Mode | Remote Device Farm Mode |
|-----------|-----------|------------------------|
| `MultiAgentV3Runner` | Desktop JVM | Android JVM (in APK) |
| `SamplingSource` | `LocalLlmSamplingSource` | `KoogLlmSamplingSource` |
| `UiActionExecutor` | Remote (ADB) | Local (UIAutomator) |
| LLM Access | Direct HTTP | Direct HTTP |

## Tiered Model Strategy

| Tier | Models | Use For | Cost Impact |
|------|--------|---------|-------------|
| **Frontier** | Claude Sonnet 4.5, GPT-5 | Screen analysis, complex decisions | HIGH (every iteration) |
| **Mid-Tier** | Claude Haiku 4.5, GPT-4.1 | General reasoning, fallback | MEDIUM |
| **Mini** | GPT-4.1-mini, GPT-5-mini | Task planning, decomposition (text-only) | LOW (once + replans) |
| **Zero Cost** | Trail mode (recordings), Reflection (heuristic) | CI/CD execution | ZERO |

Configured via `BlazeConfig.analyzerModel` (vision, called every iteration) and `BlazeConfig.plannerModel` (text-only, called at start + replans).

## Value Proposition: Blaze Once, Trail Forever

| Aspect | Mobile-Agent-v3 | Trailblaze |
|--------|-----------------|------------|
| Every execution | LLM calls required | **Zero LLM** (trail mode) |
| CI/CD at scale | Linear cost growth | **Constant $0** |
| Determinism | Non-deterministic | **100% reproducible** |
| Speed | 2-5s per action | **~100ms per action** (recordings) |

**Enterprise impact:** 1000 test runs/day with LLM = ~$500/day. Trail mode = $0/day.

## On-Device Execution

**Constraints:** No Python (all Kotlin), limited memory, self-contained APK, HTTP for LLM.

Memory-optimized config (`BlazeConfig.ON_DEVICE`): reduced iterations (20), frequent reflection (every 5), bounded backtrack (3 steps), limited subtasks (6), bounded screenshot retention (`WorkingMemory.MAX_SCREENSHOTS_ON_DEVICE = 3`).

| Device Farm | Timeout | Network | Notes |
|-------------|---------|---------|-------|
| Firebase Test Lab | 45 min | Available | Set `maxIterations` accordingly |
| AWS Device Farm | 60 min | Available | Supports custom environments |
| Sauce Labs | Varies | Available | Real devices available |

## Benchmarks

### AndroidWorld (Google Research)

116 programmatic tasks across 20 real Android apps. [GitHub](https://github.com/google-research/android_world) | [Paper](https://arxiv.org/abs/2405.14573)

| Agent | Score |
|-------|-------|
| Mobile-Agent-v3 | **73.3%** |
| **Trailblaze (target)** | **70%+** |
| AppAgent | 34.2% |

### OSWorld (XLang AI)

369 tasks across Ubuntu, Windows, macOS. [GitHub](https://github.com/xlang-ai/OSWorld) | [Paper](https://arxiv.org/abs/2404.07972)

| Agent | Score |
|-------|-------|
| Mobile-Agent-v3 | **37.7%** |
| **Trailblaze (target)** | **35%+** |

## Implementation Summary (All Phases Complete)

Six phases were implemented, inspired by Mobile-Agent-v3's multi-agent framework:

| Phase | Feature | Key Deliverables |
|-------|---------|-----------------|
| 1 | Exception Handling & Recovery | `ExceptionalScreenState`, `RecoveryAction`, `handleExceptionalState()` |
| 2 | Reflection & Self-Correction | `ReflectionNode`, loop detection, backtracking |
| 3 | Dynamic Task Decomposition | `PlanningNode`, `TaskPlan`/`Subtask`, replan support |
| 4 | Cross-Application Memory | `WorkingMemory`, `MemoryOperation`, `MemoryNode`, OCR extraction |
| 5 | Trail Recording Enhancement | `EnhancedRecording` with pre/post conditions, `RecordingValidator` |
| 6 | MCP Progress Reporting | 12 event types, `ExecutionStatus`, `ProgressEventListener` |

### Key Files

| File | Module | Description |
|------|--------|-------------|
| `ScreenAnalysis.kt` | trailblaze-models | ExceptionalScreenState, RecoveryAction |
| `TrailblazeModels.kt` | trailblaze-models | TaskPlan, Subtask, WorkingMemory, MemoryOperation, ReflectionResult |
| `TrailblazeConfig.kt` | trailblaze-models | Task decomposition config, presets |
| `ProgressReporting.kt` | trailblaze-models | Progress events and execution status |
| `ReflectionNode.kt` | trailblaze-agent/blaze | Reflection and self-correction |
| `PlanningNode.kt` | trailblaze-agent/blaze | Task decomposition |
| `MemoryNode.kt` | trailblaze-agent/blaze | Cross-app memory |
| `BlazeGoalPlanner.kt` | trailblaze-agent/blaze | Integration of all nodes |
| `EnhancedRecording.kt` | trailblaze-agent/trail | Smart recordings with validation |
| `MultiAgentV3Runner.kt` | trailblaze-agent | High-level orchestration |

## Architecture

```
MULTI_AGENT_V3 Architecture:
  Planning Node (Decomposition) → Decision Node (ScreenAnalyzer) → Execution Node (UiActionExecutor)
                                        ↓
                                  Exception Node (Popup/Ad/Error)
                                        ↓
                                  Reflection Node (Loop detection, progress, course correction)
                                        ↓
                                  Working Memory (Facts, key screenshots, cross-app clipboard)

Execution Modes:
  trail() — Zero LLM, deterministic recordings, fast CI/CD
  blaze() — Full agent loop, generates recordings for trail()
```

## Success Metrics

| Metric | Target |
|--------|--------|
| AndroidWorld benchmark | 70%+ |
| OSWorld benchmark | 35%+ |
| Trail (recorded) success | 99% |
| Trail (self-heal) success | 90% |
| Blaze → Trail conversion | 80% |
| Exception recovery | 90% |

## Parallel Work Assignments

### Priority Order

| Priority | Agents | Focus |
|----------|--------|-------|
| **P1 (Core)** | J (Device ID), G (ScreenAnalyzer), H (Trail Mode V3) | Core agent must work before benchmarking |
| **P2 (Validation)** | D (Unit Tests), F (MCP Integration, **after J**), K (On-Device Config) | Testing, config, validation |
| **P3 (Benchmarks/Docs)** | E (Benchmark Integration), I (Documentation) | Measure performance, document |

### Agent Summary

| Agent | Task | Dependencies | Creates/Modifies |
|-------|------|-------------|------------------|
| **D** | Unit Tests | None | New `*Test.kt` files only |
| **E** | AndroidWorld Benchmark | None | New `:benchmarks-androidworld` module |
| **F** | MCP Tool Integration | **Blocked by J** | `RunYamlRequestHandler.kt`, progress handlers |
| **G** | ScreenAnalyzer Enhancement | None | `ScreenAnalysis.kt`, `ScreenAnalyzerImpl.kt` |
| **H** | Trail Mode V3 | None (coordinate with J on `MultiAgentV3Runner.kt`) | `MultiAgentV3Runner.kt`, trail/*.kt |
| **I** | Documentation | None | New docs only |
| **J** | Device ID Threading | None (**blocks F**) | `ProgressReporting.kt`, `TrailblazeModels.kt`, `MultiAgentV3Runner.kt` |
| **K** | On-Device Config & Tiered Models | None | `TrailblazeConfig.kt`, `TrailblazeModels.kt` |

### Conflict Resolution

1. **H + J on `MultiAgentV3Runner.kt`**: J adds `deviceId` to `create()`, H adds `trail()` method — non-overlapping, second to merge rebases.
2. **J + K on `TrailblazeModels.kt`**: J adds `targetDeviceId` to state classes, K adds memory limits to `WorkingMemory` — different classes.
3. **D tests existing API**: Does not assume new fields from J/G/K.
4. **F depends on J**: F must not start until J merges (needs `deviceId` fields).

## Open Questions

1. How do we run AndroidWorld/OSWorld against Trailblaze's MCP interface?
2. Can we implement Mobile-Agent-v3's self-evolving trajectory production?
3. Can we auto-generate trail files from successful benchmark runs?
4. Do we need an iOS equivalent of AndroidWorld?
5. Should we support on-device model inference (Gemma, Phi) for fully offline blaze?
6. Should `WorkingMemory` facts persist to disk for crash recovery?

## Resolved Questions

1. **On-Device Execution**: Yes — all Kotlin, agent JVM code is a dependency in the test APK.
2. **Model Strategy**: No fine-tuned models. Tiered frontier/mini approach.
3. **Cost at Scale**: "Blaze once, trail forever." Trail mode = zero LLM calls.

## References

- [Mobile-Agent-v3 Paper](https://arxiv.org/abs/2508.15144)
- [X-PLUG/MobileAgent GitHub](https://github.com/X-PLUG/MobileAgent)
- [AndroidWorld Benchmark](https://github.com/google-research/android_world)
- [OSWorld Benchmark](https://github.com/xlang-ai/OSWorld)
- [Decision 032](./2026-02-04-trail-blaze-agent-architecture.md) — Original trail/blaze architecture

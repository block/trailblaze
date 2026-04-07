---
title: "Agent Resilience, Maestro Decoupling, and Driver-Specific Hierarchies"
type: decision
date: 2026-02-09
---

# Agent Resilience, Maestro Decoupling, and Driver-Specific Hierarchies

## Summary

Architectural evolution for Trailblaze's device interaction layer addressing three interconnected concerns: agent resilience when view hierarchies are insufficient, decoupling from Maestro's private APIs, and preserving full platform fidelity in view hierarchies. Organized as seven phases, each delivering standalone value.

## Context

### View Hierarchy Insufficiency

The agent relies on the accessibility tree for element identification. When the VH is incomplete (WebViews, custom-drawn UIs, React Native/Flutter with inconsistent accessibility, unsettled dynamic content), the agent enters a failure loop — picking bad nodeIds, failing, retrying with the same insufficient VH until max iterations.

There is **no mechanism** that compares the screenshot against the view hierarchy to detect insufficiency. Additionally, screen dimensions (`screenState.deviceWidth/Height`) are available but not included in LLM messages, degrading coordinate estimation quality.

### tapOnPoint Gap

`tapOnPoint` is treated as a last resort with no intelligence — it doesn't attempt to find a VH node at the coordinates or compute a recordable selector. Meanwhile, `tapOnElementByNodeId` computes selectors but requires a valid nodeId, which is exactly what's missing when the VH is insufficient. No tool combines coordinate simplicity with selector intelligence.

### Maestro Coupling

Trailblaze's Maestro dependency exists at three layers:
- **Matching**: `ElementMatcherUsingMaestro` uses reflection on private `Orchestra` methods, instantiating a fake `Maestro` with `ViewHierarchyOnlyDriver`. Fragile and breaks on version changes.
- **Execution**: Tools extending `MapsToMaestroCommands` produce Maestro `Command` objects.
- **Type conversion**: `TreeNodeExt.kt` and `TrailblazeElementSelectorExt.kt` exist solely to bridge the matching dependency.

Per [Decision 006](2026-01-01-maestro-integration.md), Maestro was explicitly "not a permanent coupling."

### ViewHierarchyTreeNode Loses Fidelity

The conversion pipeline `Raw Platform Tree → Maestro TreeNode → ViewHierarchyTreeNode` drops information at each step. Android loses `package`, `long-clickable`, `NAF`. iOS loses accessibility traits, `value`, `identifier`. Web/Playwright loses HTML tags, CSS classes, ARIA roles, `href`, input types, `data-*` attributes — collapsing everything into a single `resource-id` string. The correct granularity is **driver-specific** (not just platform-specific), since the same platform can have multiple drivers with fundamentally different UI projections (UiAutomator vs Espresso vs Compose on Android).

## Decision

Seven phases, ordered by dependency:

### Phase 1: View Hierarchy Resilience (Quick Wins)

**1a. Pre-LLM VH insufficiency heuristic** — After filtering interactable nodes, if count is below a threshold, inject a warning into the LLM message giving explicit permission to use `tapOnPoint`. Zero LLM cost.

**1b. Screen dimensions in LLM messages** — Include device dimensions and scale factor in every user message. Data already available, just not included.

### Phase 2: LLM-Reported VH Quality

**2a.** Add `viewHierarchyQuality` (COMPLETE/PARTIAL/INSUFFICIENT/EMPTY) as an optional field on `ScreenAnalysis`. Zero extra LLM calls — piggybacks on existing analysis.

**2b.** Outer agent uses quality signal for adaptive tool switching — dropping nodeId tools and switching to coordinate-based tools when VH is insufficient.

**2c.** `ReflectionNode` uses VH quality history to produce targeted diagnostics instead of generic "try something different."

### Phase 3: Augmented tapOnPoint

Convert `tapOnPoint` from `MapsToMaestroCommands` to `DelegatingTrailblazeTool`. It hit-tests the VH at the given coordinates, computes a recordable selector if a node is found, validates the selector resolves back to the intended coordinates (within tolerance), and falls back to raw coordinates if not. The LLM's coordinates are ground truth; the selector is an optimization for recordability.

### Phase 4: Trailblaze-Native Element Matcher

Replace `ElementMatcherUsingMaestro` with `TrailblazeElementMatcher` operating directly on `ViewHierarchyTreeNode`. Reimplement text/ID regex matching, state filtering, bounds filtering, `childOf`/`containsChild` traversal, spatial relationships, and index-based selection. Delete `ElementMatcherUsingMaestro.kt`, `ViewHierarchyOnlyDriver.kt`, and `TreeNodeExt.kt`.

### Phase 5: Driver-Specific View Hierarchies

Replace `ViewHierarchyTreeNode` with a `ViewHierarchyNode` interface and driver-specific implementations: `AndroidUiAutomatorNode`, `PlaywrightDomNode`, `IosAccessibilityNode`. Each provides a `descriptionForLlm()` in its platform's native style (UiAutomator dump format, HTML-like, XCTest output). `ViewHierarchyTreeNode` becomes a legacy adapter during migration.

### Phase 6: Driver-Specific Selectors

`TrailblazeSelector` sealed interface with `UiAutomatorSelector`, `PlaywrightSelector` (full locator API: `ByRole`, `ByTestId`, `ByCss`, etc.), and `XCTestSelector`. Each driver gets a `SelectorEngine<N, S>` implementation. Existing `TapSelectorV2` strategies are refactored under the `UiAutomatorSelectorEngine`.

### Phase 7: Execution Layer Abstraction

`DeviceCommandExecutor` interface makes Maestro a pluggable backend. Implementations: `MaestroDeviceCommandExecutor` (current behavior), `AdbDeviceCommandExecutor` (builds on existing benchmarks code), `PlaywrightDeviceCommandExecutor` (future).

## Dependency Graph

```
Phase 1 (VH Resilience)
  |
  v
Phase 2 (LLM VH Quality)          Phase 4 (Own Element Matcher)
  |                                  |
  v                                  v
Phase 3 (Augmented tapOnPoint) --> Phase 5 (Driver-Specific Hierarchies)
                                     |
                                     v
                                   Phase 6 (Driver-Specific Selectors)
                                     |
                                     v
                                   Phase 7 (Execution Abstraction)
```

Phases 1 and 4 can proceed in parallel.

## What changed

**Positive:**
- Phases 1-2: Immediately reduce wasted iterations on insufficient VH screens at zero LLM cost
- Phase 3: `tapOnPoint` becomes recordable; graceful degradation (coordinates always work, selectors are an optimization)
- Phase 4: Eliminates fragile private API reflection into Maestro
- Phases 5-6: Full platform fidelity; web gets Playwright-native selectors; new drivers can be added cleanly
- Phase 7: Maestro becomes optional; alternative backends possible

**Negative:**
- Phase 4 must exactly replicate Maestro's matching behavior (significant test effort)
- Phases 5-6 introduce multiple hierarchy/selector types to maintain; large migration surface
- Phase 7 requires maintaining parity across backends

## Related Documents

- [006: Maestro Integration](2026-01-01-maestro-integration.md) - This makes 006's "not a permanent coupling" concrete
- [032: Trail/Blaze Agent](2026-02-04-trail-blaze-agent-architecture.md) - Phase 2's adaptive tool switching applies to both trail and blaze
- [032b: Mobile-Agent-v3](2026-02-04-mobile-agent-v3-integration.md) - Decoupled; V3 benefits from but doesn't depend on this work
- [002: Trail Recording Format](2025-10-01-trail-recording-format.md) - Phases 3 and 6 improve recording quality

---
title: "iOS TrailblazeNode Support via IosMaestro"
type: devlog
date: 2026-03-17
---

# iOS TrailblazeNode Support via IosMaestro

## Summary

Added `DriverNodeDetail.IosMaestro` so iOS view hierarchies get preserved as typed data in `TrailblazeNode` trees instead of being flattened into the LCD `ViewHierarchyTreeNode` model. This populates `ScreenState.trailblazeNodeTree` for iOS (previously always `null`), enabling future selector generation that can match on `className`, separate text fields, and boolean states — things `TrailblazeElementSelector` can't do.

## What Changed

- **New variant:** `DriverNodeDetail.IosMaestro` — same shape as `AndroidMaestro` plus `visible` and `ignoreBoundsFiltering` (iOS-specific filtering flags)
- **New match type:** `DriverNodeMatch.IosMaestro` + `TrailblazeNodeSelector.iosMaestro` field
- **Two conversion paths:** `TreeNode.toTrailblazeNodeIosMaestro()` (Maestro fallback) and `ViewHierarchyTreeNode.toIosMaestroTrailblazeNode()` (downstream app-specific custom hierarchy)
- **Backward compat adapter:** `TrailblazeNode.toViewHierarchyTreeNode()` for all 5 driver types
- **Wired into drivers:** `HostMaestroDriverScreenState` and downstream app-specific iOS drivers both populate `trailblazeNodeTree`
- **39 new tests** covering conversion, serialization, resolver matching, and round-trip compat

## Key Decisions

### One iOS variant, not two

The original plan had a dedicated `DriverNodeDetail` variant for downstream app-specific custom hierarchies alongside `DriverNodeDetail.IosMaestro` (the Maestro fallback). We dropped the dedicated variant after realizing the downstream on-device service serializes to `ViewHierarchyTreeNode` — the same ~18 properties Maestro captures. There's no fidelity gain from having a separate type. If a downstream on-device service later sends richer data (accessibility traits, custom UIKit properties), we can reintroduce a dedicated variant then.

### Remove non-native iOS boolean properties from matchable set

`clickable`, `enabled`, and `checked` don't exist natively on iOS — Maestro infers or defaults them. Including them in `MATCHABLE_PROPERTIES` would let the selector generator produce selectors against values that are guesses, not ground truth. Removed all three from the matchable set and from `DriverNodeMatch.IosMaestro`. The `DriverNodeDetail.IosMaestro` data class still *carries* these values (for display/LLM context) but they're marked display-only and can't appear in recorded selectors. Only `text`, `resourceId`, `accessibilityText`, `className`, `hintText`, `focused`, and `selected` are matchable — all properties iOS actually provides.

### Keep AndroidMaestro and IosMaestro separate (don't merge into one "Maestro" type)

Even though the schemas are nearly identical (just `visible` and `ignoreBoundsFiltering` extra on iOS), keeping them separate means we can remove unreliable properties from one platform without affecting the other. iOS `checked` is Maestro's best guess from accessibility traits — if it proves too flaky for selectors, we can drop it from `IosMaestro` without touching `AndroidMaestro`.

### Platform-based inspector badges, not driver-based

Changed the UI inspector badges from `"a11y"` / `"maestro"` / `"ios-maestro"` to `"android"` / `"ios"` / `"web"` / `"compose"`. Consumers don't need to know about Maestro internals — it caused confusion about Trailblaze's relationship to Maestro.

### Selector generation stubs, not implementations

The generator returns `emptyList()` for `IosMaestro` — strategy implementations are Phase 4A work. The resolver and match types are fully functional, so once strategies land, recording and playback via `TrailblazeNodeSelectorResolver` will work end-to-end.

## Dead Ends

### Heterogeneous tree builder

Initially built `buildHeterogeneousTrailblazeNodeTree()` that walked the custom hierarchy, detected system placeholders, and replaced them with Maestro-sourced `TrailblazeNode` subtrees. Realized this duplicated the merge that `mergeHierarchies()` already performs at the `ViewHierarchyTreeNode` level. Deleted it and just call `mergedHierarchy.toIosMaestroTrailblazeNode()` on the already-merged result.

## Known Gap

`HostMaestroDriverScreenState` builds its `stableTrailblazeNodeTree` from the raw Maestro `TreeNode` (before custom hierarchy merge). When a downstream app-specific iOS driver is active, its `lastTrailblazeNodeTree` has richer merged data, but the host module can't read it due to module boundaries (trailblaze-host can't depend on the downstream app's UI-test module). Fixing this needs a callback/provider pattern or shared interface — future work.

## Future Work

Plan saved in `.agents/knowledge/ios-trailblaze-node-phase4-plan.md`. Priority order:

1. **Selector generation strategies for IosMaestro** — enables recording with `className`, separate text fields, boolean states
2. **Element-to-node selector bridge** — converts old `TrailblazeElementSelector` recordings to `TrailblazeNodeSelector` for playback via the new resolver
3. **TrailblazeNode-aware filtering** — replaces `ViewHierarchyFilter` for the TrailblazeNode path
4. **TrailblazeNode compact formatter** — replaces `ViewHierarchyCompactFormatter` for LLM context
5. **Migrate ElementMatcherUsingMaestro** — final unification on `TrailblazeNodeSelectorResolver`

## Files

| File | Action |
|------|--------|
| `DriverNodeDetail.kt` | Add `IosMaestro` variant |
| `TrailblazeNodeSelector.kt` | Add `iosMaestro` field + `DriverNodeMatch.IosMaestro` |
| `TrailblazeNodeSelectorGenerator.kt` | Stub branches + `buildStructuralMatch`/`buildTargetMatch` |
| `TrailblazeNodeSelectorResolver.kt` | `matchesIosMaestro()` |
| `InspectTrailblazeNodeComposable.kt` | Display branches + `IosMaestroProperties` + platform badges |
| `TrailblazeNodeMapperMaestro.kt` | **NEW** — `TreeNode.toTrailblazeNodeIosMaestro()` |
| `TrailblazeNodeMapperIosMaestro.kt` | **NEW** — `ViewHierarchyTreeNode.toIosMaestroTrailblazeNode()` |
| `TrailblazeNodeCompat.kt` | **NEW** — backward compat adapter |
| `HostMaestroDriverScreenState.kt` | Populate `trailblazeNodeTree` for iOS |
| Downstream app-specific iOS driver | `lastTrailblazeNodeTree` at all return paths |

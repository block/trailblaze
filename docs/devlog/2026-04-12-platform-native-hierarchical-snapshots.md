---
title: "Platform-Native Hierarchical Snapshots with Stable Element Refs"
type: decision
date: 2026-04-12
---

# Platform-Native Hierarchical Snapshots with Stable Element Refs

## Summary

Replaced the flat pipe-delimited screen summary on Android and iOS with a hierarchical, indented text representation matching Playwright's ARIA snapshot quality. Introduced stable content-hashed element refs, progressive disclosure (`--bounds`, `--offscreen`), and a `tap` tool for ref-based interaction. The text output, set-of-mark screenshot labels, and element refs are now consistent across all surfaces.

## The Problem

Before this change, `blaze snapshot` produced radically different output per platform:

- **Web (Playwright):** Rich hierarchical ARIA snapshot with `[eN]` refs, landmark grouping, state annotations, offscreen count. The gold standard.
- **Android:** `Search Settings | Google | Services & preferences | Network & internet | ...` — flat, pipe-delimited, no structure, no refs, truncated.
- **iOS:** Same flat format plus system UI noise (time, battery, signal bars, carrier info).

This made the CLI nearly useless for agents on mobile. An LLM couldn't distinguish interactive elements from labels, couldn't reference specific elements for actions, and couldn't reason about screen structure.

## Key Decisions

### 1. Native class names, not ARIA roles

We deliberately chose to use each platform's native vocabulary:
- Android: `EditText`, `Button`, `Switch`, `RecyclerView`
- iOS: `UIButton`, `UITextField`, `UITableView` (when Maestro provides them)
- Web: ARIA roles (`button`, `link`, `searchbox`)

**Why:** ARIA roles are a web abstraction. Forcing mobile into `button` and `textbox` loses information (is it a Switch or a CheckBox? Both map to "checkbox" in ARIA). Native class names preserve the full fidelity of each platform's accessibility framework. This also avoids maintaining a brittle mapping table that breaks on custom components.

**Generic class suppression:** `View`, `FrameLayout`, `LinearLayout`, `ConstraintLayout` are too generic to be useful — they're layout containers, not semantic elements. We suppress them from output. Informative classes (`Button`, `EditText`, `ImageView`, `RecyclerView`) are kept.

### 2. Stable content-hashed element refs

Element refs are 1 letter + 1-3 digits (e.g., `y778`, `a1`, `k42`), computed by hashing the element's text, class name, and center point coordinates.

**Why not sequential IDs?** Sequential IDs (`e1`, `e2`, `e3`) shift when any element appears, disappears, or loads async. A toast notification appearing would change every ID on the screen. We tried this first and it caused the `tap` tool to hit the wrong elements between `snapshot` and `tap` calls.

**Why not Playwright's caching approach?** Playwright caches refs on DOM elements (`element._ariaRef`). Mobile has no persistent objects — the accessibility tree is rebuilt from scratch on every capture.

**Why hashing works:** `hash(className + text + roundedCenter)` produces the same ref for the same element on the same screen, regardless of what other elements exist. A new toast, a loading spinner, an async element — none shift other refs. The 10px rounding on coordinates tolerates minor layout shifts.

**Collision handling:** 26,000 possible values (26 x 1000). For screens with 20-100 elements, collision probability is negligible. Rare collisions get a letter suffix: `k42`, `k42b`.

The ref is stored on `TrailblazeNode.ref` for debugging/inspection and on `AnnotationElement.refLabel` so set-of-mark screenshots draw the same IDs as the text output.

### 3. Progressive disclosure via CLI flags

```
blaze snapshot                    # compact default
blaze snapshot --bounds           # adds {x:120,y:450,w:200,h:40}
blaze snapshot --offscreen        # includes hidden elements marked (offscreen)
```

**Why:** Bounds data is useful for spatial reasoning but adds ~30 chars per element. Offscreen elements can double the output. The compact default keeps token costs low; agents request detail when they need it.

This mirrors a pattern we built for Playwright (`playwright_request_details` with BOUNDS, CSS_SELECTORS, OFFSCREEN_ELEMENTS) but is simpler — no one-shot request/clear mechanism needed since the CLI flags are explicit per call.

The `snapshotDetails` parameter on the `blaze` MCP tool enables the same for LLM agents: `blaze(tools="...", snapshotDetails="BOUNDS")`.

### 4. The `tap` tool

```
blaze tool tap ref=y778 -o "Tap Network & internet"
```

Resolves the hash ref by re-walking the tree with the same algorithm as the compact element list builder, finds the matching node, and taps its center point.

**Why not `tapOnElement element="Network & internet"`?** Text matching is ambiguous — multiple elements can have the same text, and Compose elements often have no accessibility label at all. The ref is unambiguous.

**Why not `tapOnElementByNodeId nodeId=21`?** nodeIds are internal, unstable, and differ between the `ViewHierarchyTreeNode` (used by the tool) and `TrailblazeNode` (used by the compact list). The hash ref abstracts over this mismatch.

**Resolution runs on-device and host-side:** The `ElementRef.resolveRef()` function uses the same tree walk as the renderer, ensuring consistent resolution regardless of where the tool executes. This is critical because the inner agent runs on-device while the CLI drives from the host.

### 5. iOS system UI filtering by bounds, not text

iOS has no `com.android.systemui` package to filter. Instead we filter by screen position: small non-interactive leaf elements in the top 50px of the screen are status bar chrome (time, signal, battery, carrier). Scroll indicators are caught by extreme aspect ratio detection (very tall+narrow or very wide+short).

**Why not text patterns?** We started with regex matching (`"3:20 PM"`, `"Wi-Fi bars"`, etc.) but it doesn't scale across locales, OS versions, and configurations. Bounds-based filtering works everywhere.

**Chevrons** (`"chevron"` disclosure indicators) are filtered by exact text match — they're decorative, not interactive, and present on every iOS list row.

### 6. Text representation is the source of truth for the LLM

`InnerLoopScreenAnalyzer.buildViewHierarchyDescription()` now checks `viewHierarchyTextRepresentation` first on ALL platforms (not just Compose). This means the inner agent sees the same hierarchical output as `blaze snapshot`. Previously, non-Compose drivers fell through to a legacy tree-to-text generator with different formatting.

`BridgeUiActionExecutor.describeScreen()` (outer agent path) already checked this — no change needed there.

## What We Tried That Didn't Work

- **Sequential `[nID]` refs from `TrailblazeNode.nodeId`:** IDs shifted between captures. Tapping `n60` from a previous snapshot hit the wrong element.
- **`ref:slug` format (slugified text):** `ref:sign-in`, `ref:activity`. Readable but long, and the `ref:` prefix was ambiguous with resource IDs.
- **Text-based tapping on Compose tabs:** `tapOnElement element="Activity"` didn't navigate a Compose-based app's bottom tabs. The accessibility text matched but the tap coordinates were wrong. This validated the need for bounds-based resolution.
- **Regex-based iOS system UI filtering:** Broke across locales and simulator configurations. Replaced with bounds-based approach.

## Files Changed

### New
- `ElementRefSlug.kt` — Hash computation, ref tracking, tree resolution
- `AndroidCompactElementList.kt` — Android hierarchical renderer
- `IosCompactElementList.kt` — iOS hierarchical renderer
- `SnapshotDetail.kt` — Progressive disclosure enum (BOUNDS, OFFSCREEN)
- `TapTrailblazeTool.kt` — `tap` tool with ref resolution

### Modified
- `TrailblazeNode.kt` — Added `ref: String?` field and `withRefs()` utility
- `ScreenState.kt` — Added `AnnotationElement.refLabel`
- `AccessibilityServiceScreenState.kt` — `viewHierarchyTextRepresentation` override, ref population
- `RpcScreenStateAdapter.kt` — Same for host-side Android path
- `HostMaestroDriverScreenState.kt` — Same for iOS Maestro path
- `InnerLoopScreenAnalyzer.kt` — Uses `viewHierarchyTextRepresentation` first on all platforms
- `BridgeUiActionExecutor.kt` — Progressive disclosure support
- `StepToolSet.kt` — `snapshotDetails` parameter
- `BlazeCli.kt` — `--bounds`, `--offscreen` flags, `takeSnapshot` instead of `playwright_snapshot`
- `HostCanvasSetOfMark.kt` — Draws `refLabel` on screenshots
- `TrailblazeToolSet.kt` / `TrailblazeToolSetCatalog.kt` — `tap` tool registration
- `TrailblazeNodeMapperMaestro.kt` — iOS nodeId assignment fix

## Snapshot Performance (iOS)

`blaze snapshot` always runs in fast mode — no `--fast` flag needed. Screenshots, disk logging, and the view hierarchy settling loop are skipped. The `--fast` flag on `blaze`, `verify`, and `tool` commands is separate: it controls whether the LLM agent receives screenshots or text-only analysis.

| Mode | Wall time | Notes |
|------|-----------|-------|
| Snapshot (current) | ~2.0s | Single capture, no screenshot, no settling |
| Raw curl to Maestro driver | ~0.12s | Bypasses JVM entirely |

Time breakdown (~2.0s): ~250ms JVM startup + class loading, ~400ms MCP client connection, ~400ms Maestro driver wrapping + tree parsing, ~120ms actual iOS accessibility tree fetch, ~830ms JSON serialization + compact element list building.

### The 120ms direct-curl floor

The Maestro XCTest runner exposes an HTTP API on a deterministic port (`TrailblazeDevicePort.getPortForDevice(deviceId, "maestro")`). A raw `curl -X POST localhost:<port>/viewHierarchy` returns the full iOS accessibility tree in ~120ms. Shelved because: (1) refs from a standalone formatter wouldn't match `IosCompactElementList` refs, breaking `tap ref=...` after a snapshot; (2) Square iOS apps use `SquareTrailblazeIosDriver` for a richer SwiftUI view hierarchy that the direct-curl path misses; (3) tree shape differs (nesting, keyboard merging, coordinate transforms).

If sub-second snapshot is needed: daemon-side caching of the last compact element list, a persistent CLI process (Unix socket, no JVM startup), or extracting the ref algorithm to a standalone script.

## Open Questions

- **Depth control:** Playwright CLI supports `--depth=N` to limit tree depth. We don't have this yet. Could help on very deep view hierarchies.
- **Change detection:** Returning "screen changed/unchanged" between calls would save agents from re-reading identical snapshots. Simple text diff, no LLM needed.
- **Partial snapshots:** Playwright supports `snapshot e15` to snapshot a subtree. Useful for large screens where you only care about one section.
- **iOS class names:** Maestro doesn't provide UIKit class names. `xcrun simctl` or the accessibility inspector could give us richer data.
- **Ref-based tools beyond tap:** `type ref=k42 text="hello"`, `assert ref=y778 visible`, `scroll ref=r74 direction=down`.

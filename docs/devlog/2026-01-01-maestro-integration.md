---
title: "Maestro as Current Execution Backend"
type: decision
date: 2026-01-01
---

# Maestro as Current Execution Backend

Choosing our execution backend for driving UI interactions.

## Background

Trailblaze needs to interact with mobile devices to perform UI actions (taps, swipes, text input) and query screen state. Building and maintaining these low-level device interaction implementations across multiple platforms (Android, iOS) requires significant effort and ongoing maintenance.

[Maestro](https://maestro.mobile.dev/) is an open source mobile UI testing framework that already provides robust, cross-platform device interaction capabilities with an active community.

## What we decided

**Trailblaze currently uses Maestro as its primary execution backend for device interactions, but Maestro is not an intrinsic part of the Trailblaze architecture.**

Maestro handles the majority of UI interactions, but Trailblaze also uses **ADB commands and shell commands** directly for certain device control operations. This hybrid approach gives us flexibility—Maestro for high-level UI actions, and direct device commands when lower-level control is needed.

### Why Maestro (For Now)

- **Avoids reinventing the wheel**: Maestro provides battle-tested implementations for taps, swipes, scrolls, text input, and screen queries across Android and iOS
- **Community maintenance**: We benefit from bug fixes, platform updates (new Android/iOS versions), and improvements contributed by the broader community
- **Reduced dependency surface**: Using a focused tool means we don't need to pull in larger testing framework dependencies

### Not a Permanent Coupling

Trailblaze's core value is in its LLM-driven test generation and trail recording/replay architecture—not in how device interactions are executed. We may choose to replace Maestro in the future if:

- A better-suited tool emerges
- Our requirements diverge from Maestro's direction
- We need tighter control over the execution layer

Tool implementations should remain abstracted such that swapping execution backends is feasible.

### On-Device Orchestra Fork

Maestro's standard architecture assumes a host machine driving a connected device. For Trailblaze's on-device execution mode, we maintain **a copy of Maestro's Orchestra code** in our codebase.

This is necessary because:
- Maestro's base implementation doesn't work when running directly on the device
- Pulling in the full Maestro dependency would bring unnecessary transitive dependencies
- We need a minimal, self-contained implementation for the on-device use case

**Maintenance requirement**: When upgrading Maestro versions, the Orchestra copy must be reviewed and updated to incorporate relevant changes while preserving on-device compatibility.

## What changed

**Positive:**
- Faster time-to-market by leveraging existing device interaction code
- Benefit from community improvements without maintaining low-level platform code
- Clear abstraction boundary makes future migration possible

**Negative:**
- Dependent on external project's stability and direction
- Orchestra fork requires manual sync during Maestro upgrades
- Must track Maestro releases for security patches and compatibility updates

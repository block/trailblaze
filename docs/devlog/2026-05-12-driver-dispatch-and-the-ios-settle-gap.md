---
title: "DriverDispatch and the iOS settle gap"
type: devlog
date: 2026-05-12
---

# DriverDispatch and the iOS settle gap

## What landed

A new marker interface `xyz.block.trailblaze.api.DriverDispatch` (in `trailblaze-models/commonMain`)
documents the cross-driver "dispatch a content-changing action, then wait until the driver's UI
is settled enough to safely read state" contract. Three drivers now implement it:

- `AccessibilityDeviceManager` (Android) — settle via `UiDevice.waitForIdle()`, the platform's
  own accessibility-event quiet detector. Pattern was already in place from PR #2843; this PR
  formalizes it.
- `PlaywrightPageManager` (web) — settle via HTTP request drain + navigation `load` state.
  Method renamed from `waitForCompletion` to `dispatchAndAwaitSettle` for cross-driver naming
  consistency. Pattern landed in PR #2846; this PR formalizes it.
- `ComposeTestTarget` (desktop) — settle via `waitForIdle()`, Compose's recomposition queue
  drain. The three dispatch tools (Click, Type, Scroll) now route through
  `target.dispatchAndAwaitSettle { ... }` rather than calling `target.waitForIdle()` inline.

The interface has one method:

```kotlin
suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R
```

It's `suspend` because that's the union of all drivers' natural shapes. Playwright's settle
does real coroutine I/O (request tracking, `delay`, navigation waits). Android and Compose
implement the same suspend signature but the bodies never actually suspend — they invoke the
action and call their blocking settle primitive (`UiDevice.waitForIdle()`,
`ComposeTestTarget.waitForIdle()`) inline. Drivers whose existing call sites are non-coroutine
(Android's per-gesture `fun tap()`, `fun swipe()`, ...) keep a small private blocking helper
inside the class (`dispatchAndAwaitSettleBlocking`) so blocking callers don't have to enter a
coroutine context. The helper body is identical to the suspend override — tiny duplication
in exchange for keeping the public surface natural at each call site.

## What didn't land, and why

**iOS — neither path adopts the interface yet.**

The Trailblaze iOS surface today goes through two paths:

1. **iOS Maestro** (`HostMaestroTrailblazeAgent`) — settle is implicit inside Maestro's
   `viewHierarchy()` quiesce. Trailblaze never sees the signal directly.
2. **iOS Axe** (`AxeDeviceManager`) — no settle at all. The class has an orphaned
   `waitForReady(timeoutMs)` (300ms sleep) that's never called post-dispatch.

The cross-driver consistency direction calls for making iOS look like Android and web — but
adopting the interface on the iOS Maestro path using `driver.contentDescriptor()` as the settle
primitive would deepen the Maestro dependency, the exact direction we just spent PR #2843
moving away from on Android. The Android win was reactive (subscribe to
`AccessibilityEvent.TYPE_VIEW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED`, debounce on a
quiet window) rather than the old poll-and-diff of view hierarchies. We want to repeat that
win on iOS rather than wedge Maestro deeper.

## The iOS reactive settle observer (follow-on work)

iOS has direct equivalents of the Android primitives we used:

1. **`UIAccessibility.layoutChangedNotification` / `screenChangedNotification`** — system-posted
   accessibility events fired when the AX tree changes. Direct analog of Android's
   `TYPE_VIEW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED`. Subscribe once, debounce on a
   quiet window, push "settled" to the host.
2. **`CFRunLoopObserver` on the main run loop's idle phase** — true reactive idle detection,
   not a poll-and-diff. Comparable to `UiDevice.waitForIdle()` but event-driven.
3. **XCUITest's internal snapshot-ready signal** — Apple already tracks this for
   `XCUIElement.waitForExistence`; the underlying implementation is event-driven, not poll
   based. Not directly exposed but reachable from a custom XCTest runner.

The architectural shape that ports cleanly from Android is: a small observer running inside
the on-device XCTest runner subscribes to (1) + (2), and pushes quiet-window events out over
the host↔device transport. The host's iOS `DriverDispatch` implementation calls
`bridge.waitForSettled(timeoutMs)` after dispatch.

Today there's no OSS-level on-device iOS runner component we own to extend — the XCUITest
bridges that do exist are target-scoped (one per internal app) rather than reusable across
arbitrary iOS targets. Standing one up at the OSS layer is the unblocking piece.

Once that observer lands:

- iOS Maestro gets explicit settle that doesn't go through Maestro — and we can plan the
  Maestro divorce.
- iOS Axe gets its first real settle (replaces the orphaned 300ms heuristic).
- Both iOS paths can implement `DriverDispatch` against the same primitive, mirroring the
  Android shape exactly.

That work is platform-engineering scope (build a runner component, design the wire protocol,
land changes on both sides of the device boundary). It's not a refactor PR; it's its own piece.
This devlog exists to capture the design rationale so when that work starts, the goal is clear:
**a reactive iOS settle observer, not another Maestro hook.**

## Pattern: when to add a new driver to DriverDispatch

When a new driver lands or an existing one acquires its own (non-Maestro) settle primitive:

1. Make the manager implement `DriverDispatch` and override `dispatchAndAwaitSettle`. The body
   is whatever runs the action and then waits on the driver's settle primitive — for drivers
   whose primitive is blocking, the suspend body just calls it inline (it never actually
   suspends).
2. If the driver has existing non-coroutine call sites (per-gesture `fun` methods), add a
   private blocking helper (`dispatchAndAwaitSettleBlocking` or similar) to avoid pushing those
   callers into a coroutine context. The helper body mirrors the suspend override.
3. Route every content-changing method through one of the two so adding a new gesture without
   the settle wait is impossible by construction.
4. Update the `DriverDispatch` kdoc to add the manager to the "Current implementers" list.
5. Update tools that dispatch content changes to call the suspend method rather than
   inline-settling.

Verifications, reads, and explicit "wait N seconds" tools do NOT go through `dispatchAndAwaitSettle`.
They use their own poll loops (verify) or call the underlying settle primitive directly (read).

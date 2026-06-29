---
title: Accessibility-tree completeness on Android
---

# Accessibility-tree completeness on Android

Trailblaze isn't an accessibility scanner, but the on-device Android driver operates entirely
through the Android accessibility framework — taps, swipes, and assertions all resolve against
the accessibility hierarchy the OS exposes. When the captured hierarchy looks malformed,
Trailblaze surfaces that as a diagnostic. It's a *byproduct* of how the driver works, not a
primary feature.

A run can fail in a confusing way when the screenshot shows a full screen of content but the
captured accessibility tree only contains a thin slice of it (often jammed against one edge).
Selectors then report "element not found" for plainly-visible elements. The cause is almost
always app-side — incomplete Compose semantics, a custom view that doesn't populate
`AccessibilityNodeInfo`, or another rendering path that bypasses accessibility — but it
manifests as a Trailblaze failure. The signal documented here helps you tell the two apart
faster.

## What you see

Two surfaces report the signal — pick whichever fits the consumer.

### `[capture-coverage]` log lines

The on-device gate logs through the standard `Console.log` channel (visible under
`./trailblaze run --no-daemon -v` or in the daemon log under
`~/.trailblaze/desktop-logs/`). The lines start with `[capture-coverage]` and read like:

```
[capture-coverage] settled tree looks truncated (content spans 17% of width, jammed against
  the right edge (left 82% empty) across 6 node(s)); holding for the full tree
[capture-coverage] proceeding after 1000ms with a TRUNCATED tree (...). The screen may render
  fully but only that slice is reachable via accessibility — this could be an accessibility
  bug in the app under test (e.g. incomplete Compose semantics, a custom view that doesn't
  populate AccessibilityNodeInfo); selectors may miss visible elements
```

Useful when you're already reading a session log to triage a failure. Brittle to parse out of
logcat in bulk — for that, use the JSON field below.

### `accessibility_truncation` in the report JSON

`trailblaze_test_report.json` carries a per-session `accessibility_truncation` object whenever
at least one capture during the session computed a coverage assessment. The field is null in
three cases:

- The session ran on a non-Android driver (iOS, web — those drivers don't run the gate).
- The on-device gate was disabled for the session
  (`TRAILBLAZE_DISABLE_SETTLE_TREE_STABILITY=1`).
- Every capture in the session was unmeasurable — typically a degraded device where the
  accessibility window root was unreadable or the screen dimensions came back zero. A
  `[capture-coverage] skipping coverage check — screen dimensions unreadable …` line in the
  device log is the breadcrumb for the last case.

Otherwise it looks like:

```json
{
  "session_id": "2026_06_26_…",
  "platform": "android",
  "accessibility_truncation": {
    "captures_truncated": 3,
    "captures_total": 30,
    "examples": [
      {
        "timestamp_ms": 1782739200000,
        "reason": "content spans 17% of width, jammed against the right edge (left 82% empty) across 6 node(s)",
        "horizontal_coverage": 0.17,
        "vertical_coverage": 0.92
      }
    ]
  }
}
```

Up to 5 examples are surfaced per session (the rest tend to be more of the same — the count
is the signal). Dashboards, LLMs, and CI graders can read this without grepping logs.

## Reading the signal

The detector is a heuristic. Treat it as "could be" / "often indicates" rather than a hard
verdict.

- A small, occasional ratio (1–2 truncated captures in a run) is often a transient
  mid-render snapshot the gate already recovered from — usually fine to ignore.
- A high ratio (most captures of a given screen are flagged) often indicates a real app-side
  accessibility problem on that screen. Verify before filing — see below.
- Some apps with intentionally sparse accessibility (legitimately empty screens, third-party
  surfaces the test author doesn't control) will report it persistently and harmlessly.
- The signal is most useful as a *trend* across a run or across runs, not a single-capture
  alert.

## Common app-side causes

When a screen shows up consistently truncated, the underlying cause is usually one of:

- **Incomplete Compose semantics.** A composable that draws content but doesn't expose it
  through `Modifier.semantics { ... }` (or merges into a parent whose semantics don't carry
  its text) won't surface in the accessibility tree. The screenshot still looks right because
  Compose draws it; the tree just doesn't know about it.
- **Custom `View`s without `AccessibilityNodeInfo`.** A custom view that overrides `onDraw`
  but not `onPopulateAccessibilityEvent` / `onInitializeAccessibilityNodeInfo` is invisible
  to accessibility.
- **Rendering bugs in custom views** that clip or position content off the bounds they
  report — the screenshot lands correctly but the bounds reported to accessibility are wrong.
- **Animations / transitions** that move content into place after the accessibility tree
  reports settled — usually transient and the gate recovers; persistent occurrences suggest
  the screen never re-emits the events accessibility needs to refresh.

## Verifying outside Trailblaze

Confirm the signal is real before filing it as an app bug. A few independent tools that read
the same accessibility framework:

- **Android Studio Layout Inspector** — connect to the running app, toggle "Show all" to
  include views that aren't important for accessibility, and check whether the tree matches
  what's drawn. The most direct read.
- **`adb shell uiautomator dump`** — captures the live accessibility hierarchy to an XML
  file. Lightweight and scriptable; useful for diffing against a screenshot.
- **Accessibility Scanner** (Google Play) — runs heuristic checks against the same tree
  Trailblaze sees. Will surface most of the issues above, often with a fix suggestion.
- **TalkBack** — Android's built-in screen reader. If TalkBack can't read or focus an
  element, neither can Trailblaze.

If the screen looks correct in every other tool but Trailblaze still reports truncation, the
detector's heuristic may be misfiring. The detector is deliberately conservative (only flags
one specific failure shape — see [`HierarchyCoverageAssessor`][assessor] for the rules) but
heuristics drift; please file an issue with the session log if you suspect a false positive.

[assessor]: https://github.com/block/trailblaze/blob/main/trailblaze-android/src/main/java/xyz/block/trailblaze/android/accessibility/HierarchyCoverageAssessor.kt

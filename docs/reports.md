---
title: Report Gallery
---

# Report Gallery

Every Trailblaze run produces a rich, replayable session. The reports below are **not
mockups** — they are generated automatically by Trailblaze's own CI on each push to
`main`, exported straight from the example and showcase trails in this repository and
embedded here by the docs build — one per platform (Android, iOS, web). What you see is
exactly what your agent or CI produces locally with `trailblaze report`.

Three export formats are shown for each trail:

- **Storyboard** — a single-frame grid tiling every step's screenshot, labeled with the
  tool that ran. A glance-overview of the whole flow. (`trailblaze report --storyboard`)
- **Timeline (animated WebP)** — the report's timeline autoplay, scrubbing through each
  step with its labels and annotations. The animated walkthrough.
  (`trailblaze report --webp`)
- **Interactive report** — the full self-contained HTML report: per-step screenshots,
  view-hierarchy snapshots, recorded tool calls, and (when an LLM was involved) the
  transcript. This is the same [Trace Viewer](index.md#trace-viewer) surface the desktop
  app shows.

!!! tip "These are live artifacts"
    The WebP and storyboard below link through to the full interactive HTML report —
    click either image to open it. The HTML report is a single file you can download,
    open offline, or attach to a PR; embedding it inline doesn't do it justice, so the
    animations link out to the real thing.

## Set an alarm (Android)

A recorded Android trail driving the system Clock app to set a 7:30 AM alarm, replayed on
an emulator via Trailblaze's host-RPC Android driver — no LLM at replay time. Source:
[`trails/clock/set-alarm-730am`](https://github.com/block/trailblaze/tree/main/trails/clock/set-alarm-730am).

### Storyboard

[![Set-alarm clock trail storyboard — every step tiled into a grid](/trailblaze/report-assets/clock/storyboard.webp)](/trailblaze/report-assets/clock/report.html)

### Timeline

[![Set-alarm clock trail timeline — animated walkthrough of each step](/trailblaze/report-assets/clock/timeline.webp)](/trailblaze/report-assets/clock/report.html)

[**Open the full interactive report →**](/trailblaze/report-assets/clock/report.html)

---

## Contacts (iOS)

A recorded iOS trail exercising the system Contacts app — searching for, opening, and
verifying a contact — replayed on an iOS simulator, with no LLM at replay time. Source:
[`examples/ios-contacts`](https://github.com/block/trailblaze/tree/main/examples/ios-contacts).

### Storyboard

[![iOS Contacts trail storyboard — every step tiled into a grid](/trailblaze/report-assets/ios-contacts/storyboard.webp)](/trailblaze/report-assets/ios-contacts/report.html)

### Timeline

[![iOS Contacts trail timeline — animated walkthrough of each step](/trailblaze/report-assets/ios-contacts/timeline.webp)](/trailblaze/report-assets/ios-contacts/report.html)

[**Open the full interactive report →**](/trailblaze/report-assets/ios-contacts/report.html)

---

## Wikipedia (web)

A recorded web trail driven through Playwright against live `en.wikipedia.org` — no
Android emulator or iOS simulator required, and no LLM at replay time. Source:
[`examples/wikipedia`](https://github.com/block/trailblaze/tree/main/examples/wikipedia).

### Storyboard

[![Wikipedia trail storyboard — every step tiled into a grid](/trailblaze/report-assets/wikipedia/storyboard.webp)](/trailblaze/report-assets/wikipedia/report.html)

### Timeline

[![Wikipedia trail timeline — animated walkthrough of each step](/trailblaze/report-assets/wikipedia/timeline.webp)](/trailblaze/report-assets/wikipedia/report.html)

[**Open the full interactive report →**](/trailblaze/report-assets/wikipedia/report.html)

*Want this for your own app? Every `trailblaze run` produces a session you can export
the same way — see the [CLI reference](CLI.md#trailblaze-report) for `trailblaze report`
and its `--storyboard` / `--webp` / `--gif` / `--video` flags.*

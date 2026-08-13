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

[![Set-alarm clock trail storyboard — every step tiled into a grid](report-assets/clock/storyboard.webp)](report-assets/clock/report.html)

### Timeline

[![Set-alarm clock trail timeline — animated walkthrough of each step](report-assets/clock/timeline.webp)](report-assets/clock/report.html)

[**Open the full interactive report →**](report-assets/clock/report.html)

---

## Contacts (iOS)

A recorded iOS trail driving the system Contacts app through a full create→verify→delete
lifecycle — creating a "Trailblaze Demo" contact with a phone number, confirming it landed
in the list, then deleting it — replayed on an iOS simulator with no LLM at replay time.
Source: [`trails/ios-contacts/test-create-then-delete`](https://github.com/block/trailblaze/tree/main/trails/ios-contacts/test-create-then-delete).

### Storyboard

[![iOS Contacts trail storyboard — every step tiled into a grid](report-assets/ios-contacts/storyboard.webp)](report-assets/ios-contacts/report.html)

### Timeline

[![iOS Contacts trail timeline — animated walkthrough of each step](report-assets/ios-contacts/timeline.webp)](report-assets/ios-contacts/report.html)

[**Open the full interactive report →**](report-assets/ios-contacts/report.html)

---

## Wikipedia (web)

A recorded web trail driven through Playwright against live `en.wikipedia.org` — no
Android emulator or iOS simulator required, and no LLM at replay time. Source:
[`examples/wikipedia`](https://github.com/block/trailblaze/tree/main/examples/wikipedia).

### Storyboard

[![Wikipedia trail storyboard — every step tiled into a grid](report-assets/wikipedia/storyboard.webp)](report-assets/wikipedia/report.html)

### Timeline

[![Wikipedia trail timeline — animated walkthrough of each step](report-assets/wikipedia/timeline.webp)](report-assets/wikipedia/report.html)

[**Open the full interactive report →**](report-assets/wikipedia/report.html)

---

## Open one of your own sessions in the browser

The reports above are exported files. If what you have is a **session archive** — the `.zip`
a run leaves behind, or one downloaded from CI — you don't need to export anything to read
it:

[**Open the report viewer →**](report-viewer/index.html)

**Drop the `.zip` on that page** (or use its file picker) and every log, screenshot, LLM
call, and step timeline in the archive renders as a full interactive report. This is the
path that always works: the archive is read in your browser, nothing is uploaded, and no
request leaves the page. It works offline, and on an archive you'd never put on a network.

The viewer is one self-contained file — the same stylesheet and the same renderer an
exported report carries, with no run baked into it. So it can't drift from the reports it
renders, and you can host or keep your own copy:

```
./scripts/build-viewer-shell.sh out    # writes out/index.html — serve it anywhere, or just open it
```

### Loading by URL (`?zip=`), and when it works

Appending `?zip=<archive-url>` (or pasting a URL into the viewer's field) loads an archive
over the network instead, which is what makes a report **shareable as a link**. Viewer
route params ride alongside it, so a link can open on a specific place in the report:

```
.../report-viewer/?zip=https://example.com/runs/my-session.zip&tab=lightbox
```

This path is **opt-in on the archive host's side**, because the fetch is cross-origin: it
only works if whatever serves the `.zip` sends an `Access-Control-Allow-Origin` header that
permits the viewer's page. Plenty of artifact stores don't, and that's not something the
viewer can work around — the browser blocks the read before the page sees any bytes. When it
happens, the viewer says so and you can still drop the file.

*Want the exports above for your own app? Every `trailblaze run` produces a session you can
export the same way — see the [CLI reference](CLI.md#trailblaze-report) for `trailblaze report`
and its `--storyboard` / `--webp` / `--gif` / `--video` flags.*

*Optimizing a slow trail? The same session logs also feed an Instruments-style time
profiler - see [Performance Profiling](profiling.md) for `trailblaze profile`.*

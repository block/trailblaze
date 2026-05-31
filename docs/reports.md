---
title: Report Gallery
---

# Report Gallery

Every Trailblaze run produces a rich, replayable session. The reports below are **not
mockups** — they are generated automatically by Trailblaze's own CI on each push to
`main`, exported straight from the example trails in this repository and embedded here
by the docs build. What you see is exactly what your agent or CI produces locally with
`trailblaze report`.

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

More examples — iOS Contacts and the Android sample app — land here as their trail
workflows are wired into the gallery pipeline.

*Want this for your own app? Every `trailblaze run` produces a session you can export
the same way — see the [CLI reference](CLI.md#trailblaze-report) for `trailblaze report`
and its `--storyboard` / `--webp` / `--gif` / `--video` flags.*

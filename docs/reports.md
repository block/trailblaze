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
    The WebP and storyboard below link through to the report viewer, which loads each run
    from the session archive CI published — click either image to open it. Embedding a
    report inline doesn't do it justice, so the animations link out to the real thing.
    Each run also offers its self-contained HTML report: one file you can save, open
    offline, or attach to a PR.

## Set an alarm (Android)

A recorded Android trail driving the system Clock app to set a 7:30 AM alarm, replayed on
an emulator via Trailblaze's host-RPC Android driver — no LLM at replay time. Source:
[`trails/clock/set-alarm-730am`](https://github.com/block/trailblaze/tree/main/trails/clock/set-alarm-730am).

### Storyboard

[![Set-alarm clock trail storyboard — every step tiled into a grid](report-assets/clock/storyboard.webp)](report-viewer/index.html?zip=../report-assets/clock/session.zip)

### Timeline

[![Set-alarm clock trail timeline — animated walkthrough of each step](report-assets/clock/timeline.webp)](report-viewer/index.html?zip=../report-assets/clock/session.zip)

[**Open this run in the report viewer →**](report-viewer/index.html?zip=../report-assets/clock/session.zip)
<br/>*or open the [self-contained report file](report-assets/clock/report-interactive.html) — one HTML file, no network.*

---

## Contacts (iOS)

A recorded iOS trail driving the system Contacts app through a full create→verify→delete
lifecycle — creating a "Trailblaze Demo" contact with a phone number, confirming it landed
in the list, then deleting it — replayed on an iOS simulator with no LLM at replay time.
Source: [`trails/ios-contacts/test-create-then-delete`](https://github.com/block/trailblaze/tree/main/trails/ios-contacts/test-create-then-delete).

### Storyboard

[![iOS Contacts trail storyboard — every step tiled into a grid](report-assets/ios-contacts/storyboard.webp)](report-viewer/index.html?zip=../report-assets/ios-contacts/session.zip)

### Timeline

[![iOS Contacts trail timeline — animated walkthrough of each step](report-assets/ios-contacts/timeline.webp)](report-viewer/index.html?zip=../report-assets/ios-contacts/session.zip)

[**Open this run in the report viewer →**](report-viewer/index.html?zip=../report-assets/ios-contacts/session.zip)
<br/>*or open the [self-contained report file](report-assets/ios-contacts/report-interactive.html) — one HTML file, no network.*

---

## Wikipedia (web)

A recorded web trail driven through Playwright against live `en.wikipedia.org` — no
Android emulator or iOS simulator required, and no LLM at replay time. Source:
[`examples/wikipedia`](https://github.com/block/trailblaze/tree/main/examples/wikipedia).

### Storyboard

[![Wikipedia trail storyboard — every step tiled into a grid](report-assets/wikipedia/storyboard.webp)](report-viewer/index.html?zip=../report-assets/wikipedia/session.zip)

### Timeline

[![Wikipedia trail timeline — animated walkthrough of each step](report-assets/wikipedia/timeline.webp)](report-viewer/index.html?zip=../report-assets/wikipedia/session.zip)

[**Open this run in the report viewer →**](report-viewer/index.html?zip=../report-assets/wikipedia/session.zip)
<br/>*or open the [self-contained report file](report-assets/wikipedia/report-interactive.html) — one HTML file, no network.*

---

## Open one of your own sessions in the browser

Every run above opens through that same viewer: the gallery publishes each session's `.zip`
next to its images and links `?zip=` at it, so those pages are the viewer doing exactly what
it does for your own archives. If what you have is a **session archive** of your own — the
`.zip` a run leaves behind, or one downloaded from CI — nothing needs exporting either:

[**Open the report viewer →**](report-viewer/index.html)

**Drop the `.zip` on that page** (or use its file picker) and every log, screenshot, LLM
call, and step timeline in the archive renders as a full interactive report. This is the
path that always works: the archive is read in your browser, nothing is uploaded, and no
request leaves the page. It works offline, and on an archive you'd never put on a network.

The viewer is one self-contained file — the same stylesheet and the same renderer an
exported report carries, with no run baked into it. It also carries the selector engine, so
**Inspect UI** computes the same ranked selector suggestions here as it does in an exported
report: the daemon's own generator and resolver compiled to JavaScript, not a re-implementation.
So it can't drift from the reports it renders, and you can host or keep your own copy:

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

Whether this works depends on where the archive is served from:

- **Same origin as the viewer** — no CORS involved at all, so it always works. That's how
  the gallery runs above are wired: their `?zip=` values are relative paths to archives
  published beside the viewer on this site.
- **Any other host** — the fetch is cross-origin, so it's **opt-in on the archive host's
  side**: it only works if whatever serves the `.zip` sends an `Access-Control-Allow-Origin`
  header permitting the viewer's page. Plenty of artifact stores don't, and that's not
  something the viewer can work around — the browser blocks the read before the page sees
  any bytes. When it happens, the viewer says so and you can still drop the file.

## How screenshots travel with a report

A report has to answer one question about every screenshot it shows: does the picture ride
*inside* the HTML file, or does the browser fetch it from somewhere? Trailblaze does both,
and which one you get depends on how the report was made.

**Embedded (the default).** `trailblaze report` writes every screenshot into the HTML as
base64. The result is one file with no dependencies — mail it, attach it to a PR, open it
on a plane. The cost is size: a screenshot-dense run contributes a couple of MB on its own,
so a report covering hundreds of runs is not something you want to hand a browser.

**Already hosted (automatic).** A run on a device farm doesn't ship its step screenshots
back with the logs — the farm keeps them and the session log records the URL it put them
at. Reports built from those logs reference the images and never embed them. Nothing to
configure; it is simply what those sessions carry. Measured on 37 device-farm sessions: the
report referenced 296 hosted screenshots and embedded 37 (the one screenshot per session
the farm does download locally), for 4.1 MB total.

**Linked (opt-in).** `generate-report --link-images` writes references instead of image data,
pointing at `<session-id>/<file>` next to the report. It's for reports that are *served* rather
than passed around: you host the screenshot files alongside the HTML and the browser pulls each
one only when it's actually shown. On three real local runs this took the report from 5.3 MB to
0.9 MB, and it skips the ffmpeg re-encode that embedding runs on every screenshot over 100 KB.

Three things to know before turning it on:

- **The report stops being portable.** Move it away from its images and they stop loading. That's
  why embedding remains the default, and why `trailblaze report` never links.
- **You must host the images at that exact layout**, `<report-url-dir>/<session-id>/<file>`.
  Nothing checks this for you; get it wrong and every screenshot is a 404.
- **The images have to still be on disk when the report is generated.** A screenshot the
  generator can't find is left out of the report rather than referenced — so if your pipeline
  deletes or moves the image files, do it *after* generation, not before.

A linked report also can't offer its **Export screenshots** button (it has no image bytes to put
in the export), and **Export report** produces a copy that still points at the original host
rather than a self-contained file.

The Trailblaze daemon's own `/report` page always uses the linked form, serving the images
off its `/static/` route. That page is generated fresh on every request from logs the
daemon can still see, so there is nothing to keep portable — and it's what lets one page
cover many runs without inlining all of them. When you want the portable artifact for a run
you're looking at there, `trailblaze report` writes it.

*Want the exports above for your own app? Every `trailblaze run` produces a session you can
export the same way — see the [CLI reference](CLI.md#trailblaze-report) for `trailblaze report`
and its `--storyboard` / `--webp` / `--gif` / `--video` flags.*

*Optimizing a slow trail? The same session logs also feed an Instruments-style time
profiler - see [Performance Profiling](profiling.md) for `trailblaze profile`.*

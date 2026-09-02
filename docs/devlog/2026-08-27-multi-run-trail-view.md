---
title: "The Trail View: One Trail, Every Device, One Clock"
type: devlog
date: 2026-08-27
---

# The Trail View: One Trail, Every Device, One Clock

## Summary

The report viewer can now render several runs of the *same* trail as one report and project them
side by side: **Map** (a waypoint chain, one hub per authored step fanning out to one card per
device), **Grid** (lanes aligned row-by-row), and **Replay** (every lane advancing on one shared
wall clock, playing each lane's mp4 where the run recorded one). Load it with repeated `?zip=`
params. Built into the existing viewer rather than as a new page, so there is no second renderer to
drift.

## What Changed

- Repeated `?zip=` params (or a whitespace-separated paste) load as one report. When every run is
  the same trail, the run index offers a **Trail view**; a single run reaches the same projections
  from a button in its detail header.
- `buildTrailMatrix` (`run-report-trail-model.ts`) joins each session's authored-step groups into
  shared rows. Alignment is by step **number**, not by matching text — every lane ran the same trail
  YAML, so numbered objectives correspond positionally even when a step's wording carries
  per-platform notes. A lane whose wording disagrees keeps its own text on the cell
  (`labelDiffers`) rather than being silently relabelled.
- Replay's clock is pure in `run-report-trail-replay.ts`: `buildReplayTimeline` turns the step-major
  matrix lane-major on one clock, and `laneStateAt(lane, t)` answers what that device had on screen
  at instant `t`.
- The reader can toggle lanes off to compare just the devices they care about.
- The `time` projection was deleted once Replay landed. It showed the same per-lane pacing with none
  of the playback, and an old `?mode=time` link now falls back to the map like any other unknown
  mode.

## Key Decisions

**Extract the geometry and the clock, then wire the DOM.** `run-report-trail-camera.ts` and
`run-report-trail-replay.ts` hold the framing math and the playback clock as pure functions with
their own unit tests. This is not only for tidiness: the test harness's DOM shim returns `[]` for
selectors it does not model, so anything left inside a `wireTrail*()` function exits immediately
under test and is covered by nothing. Logic that matters has to live outside the wiring.

**A lane is a position; a run is an identity.** With lane filtering, "lane 0" means the first
*shown* device, which is not session 0. Everything internal to the view — matrix columns, replay
panes, `data-rp-*` and `data-wp-frame` keys — agrees on the position. Only the exits into the rest
of the viewer (`data-trail-open`, `data-shot-run`) carry the session index, because the Lightbox and
`openSession` resolve straight off `SESSIONS`. Renumbering an exit to a position opens the wrong
run, and the failure is quiet: you get a real screenshot of a real run, just not the one you
clicked.

**Video is a per-lane ladder, not a mode.** In the five-device set that drove this work, exactly one
lane (the iPad) recorded video. So a lane that has a recording plays it and a lane that doesn't
keeps its screenshots, and the pane *says* which (a `REC` badge, or a "screenshots only" note when
no lane recorded). A global "video mode" would have been empty for four of five columns.

**Exports stay on stills.** A clip reaches the viewer as an object URL over bytes the page already
downloaded, so it is page-local by construction. `videoClip` is nulled at both serialization
boundaries — a standalone HTML cannot carry 59 MB of base64, and a `blob:` URL in an exported
document is a `REC` badge over a video that will never load.

## What We Learned

**A recorder's declared window is not its file's duration, and two places have to agree about
that.** The iPad lane declares a 182.1s capture window for a 178.0s mp4. Subtracting the start
offset drifts by that whole difference — measured landing four seconds and two screens late by the
end of the run — so `videoClipTimeAt` *scales* media time by `duration / window`. The trap: the
element's `playbackRate` was set to the bare replay speed, so while the position mapping scaled, the
playback did not. The element walked off the clock at that ratio until the 250ms drift correction
seeked it back — about once a second at 10×, and every seek discards the decode pipeline. The
symptom was stutter that looked like a browser problem and was ours. Both now read one
`videoClipScale`, and the test asserts the rate against the position mapping's own derivative so
they cannot drift apart again.

**Seeking every frame is what makes scrubbed video stutter.** During playback the element plays
*itself* and is only corrected past a tolerance. Setting `currentTime` every animation frame throws
away the decode pipeline each time.

**A step's outcome cannot come from its objective bookends alone.** A crash logs no
`ObjectiveComplete`, so every objective row stays `ok` and only the tool row that died is failed —
the index called the run failed while every Trail projection painted the lane green. The detail
timeline had always handled this with a failure *anchor*; that rule is now
`failureAnchorIndex(trace)` in `run-report-trace-model.ts`, shared by the timeline, the scrubber and
the trail matrix. Extracting it surfaced that its "first failed row inside the failed objective"
branch had no test coverage anywhere, and that a third branch was unreachable.

**Jitter was three separate things.** A `translateY(6px)` slide-in on the crossfade (the slide was
the original ask; it read as jitter and was retired), sub-percent aspect wobble between consecutive
captures of one device (`aspectHeld` holds anything under a percent; a rotation sails past), and a
step chip that re-heighted on two-line labels and shoved the picture around (now a fixed 34px).

**Two CSS/DOM traps worth knowing.** A CSS comment containing a backtick terminates the stylesheet's
template literal, and the failure presents as a `bun test` hang at 99.6% CPU with no output rather
than a parse error. And `[hidden]` loses to an author `display` — an element that must hide has to
be class-driven if any rule gives it a `display`.

**A hidden browser tab throttles `requestAnimationFrame` to nothing.** Verifying playback in an
automated pane means dispatching the key events (the step path paints synchronously); waiting on the
play loop looks exactly like a broken clock.

## Future Work

- A jump-to-step scrubber or minimap for the Map, deliberately left as a design call.
- Lazy clip reads. Deferring the read to first Replay open is a load-latency win, not a footprint
  win: reading later means retaining the archive bytes for the life of the payload, and a session
  archive is mostly its mp4. Getting the footprint win too needs the archive dropped once every
  lane's clip has been opened.

Shipped in PR #6394.

---
title: "Strings on Every Capture"
type: decision
date: 2026-09-27
---

# Strings on Every Capture

## Summary

Every capture log now carries the text on screen. The strings are read from the view tree when
the log is written, on device and on the host, so every session has them without an export step.
Each string points at its capture and its box on it. That powers the report's Strings tab, crops of
each string cut out of its screenshot, the survey SDK's `screenText`, and `strings diff`.

## What Changed

- `TrailblazeLog.withVisibleStrings()` (models `commonMain`) fills a `visibleStrings` field on
  `AgentDriverLog`, `TrailblazeSnapshotLog` and `TrailblazeLlmRequestLog`. It runs in the logging
  rule's emitter and again in `LogsRepo.saveLogToDisk` as a catch-all. It is idempotent and never
  throws: a capture with no strings is better than a capture lost to a parse error.
- Each string has `text`, `source`, `bounds` (`[left, top, right, bottom]` in device pixels) and
  `visible`. `visible` is left out on disk when true, so a reader must treat a missing value as
  true.
- `visible-strings.ndjson` is still written, now as version 2. It is derived from the logs.
  Each line names its capture with `captureId` and carries the image separately in
  `screenshot` (plus `captureUrl` on the farm).
- In the report, the Strings tab lists every string with Visible / Not visible / All filters.
  By screen mode draws each string's box on its screenshot, and each row shows the string cropped
  out of its screenshot.

## Key Decisions

**Record at capture time, not in a pass afterwards.** Before this, strings existed only when
someone ran the export over a finished session. Putting them on the log means every reader (the
report, surveys, diffs, anything downstream) gets them from the same place for every session,
including CI runs. Nearly all of the value came from this decision.

**Name the capture, and keep the image in its own field.** A string is only trustworthy if you
can see where it was: which frame, and which box on it. A capture with a screenshot is named by
that screenshot, which every reader already has. A capture without one (screenshots turned off,
or a failed capture) gets an id stamped on its log once, as it's emitted, and never rewritten.
The id is stored, not derived, so every reader sees the same one. Log file names and timestamps
were ruled out: farm runs rename log files and none of the readers see them, and several logs of
one screen carry different timestamps. `captureId` is opaque, and the image is a separate
`screenshot` field, so a video frame can later be another field without changing the key. This
also replaced a capture counter that was named `stepIndex` but was never a trail step. Each
Timeline row lists the ids of its screenshot-less captures, so the Strings tab links those to the
exact row and dispatch too.

**Judge volatile text when it's read, not when it's written.** Clocks, balances and counters (a
digit and no words except AM/PM) are recognised by `VolatileText.looksVolatile` in Kotlin and
`looksVolatile` in TypeScript. The two must stay in step. Because nothing is stored, a better
rule reaches every session already recorded.

**A crop is exactly what the device drew.** Crops are a CSS background: the whole screenshot,
scaled and shifted so only the box shows. Each screenshot is written once, in one style rule,
not once per row. Crops have square corners. They come only from a place where the string was
visible, because a box that was scrolled away cuts out blank space and a covered box cuts out
whatever covers it.

## What We Learned

**Renaming fields in an export breaks readers you don't own.** Version 2 dropped `stepIndex`,
`repeatOfStepIndex`, `logType` and the stored `volatile` flag, and changed `bounds` to corners.
That was tidier, but a comparison tool outside this repo read those fields and showed no strings
until it was updated. Adding the new fields next to the old ones would have delivered the same
value with no break. Next time a file has readers outside the repo, add fields and don't rename
or remove them.

**Pair by position only across the whole stream.** A reader that buckets captures by trail step
sees a slice of the stream. Numbering captures within the slice pairs them with the wrong
capture, so a capture's number must be its position in the whole stream.

## Future Work

- **Trail step.** Add the trail step each capture happened in as a new field. Several captures
  share a step. That answers "which step first showed this string" and lets a comparison group
  by real steps rather than inferring them from timestamps.
- **Frames from video — built in the browser.** A capture with no screenshot now shows the
  recording's frame at its time, taken by the report page and never stored, and labeled "From
  video". Capture times are moved onto the host clock the recording uses, and `bounds` scale by
  the frame's size like a screenshot's. `trailblaze run` and `trailblaze strings extract` now
  also save that frame beside the screenshots as `<captureId>.webp`, by the same rule, and name it
  in the strings export's `frame` field; a report that carries the file shows it without reading
  the recording. Still open:
  - Multi-device sessions. A capture log doesn't name its device, so when two recordings cover
    its instant no frame is saved and only the page can take one.
  - Daemon sessions. They never write the strings export, so they save no frames.
  - Linked clips. A recording linked from a host that doesn't allow reading its pixels gives no
    frames; embedded recordings always do.
  - Quality. Lossy encoding blurs small text, and a frame may catch the screen mid-animation.
- **Log the screen an action decided on (outside the Maestro path).** On the drivers that don't
  run through Maestro, a step's capture is taken before the action starts, and the polls that
  follow (an assert waiting for its text, a tap waiting for its target) are discarded. So an
  assert that passed after waiting logs a screen that may not show the text, and a tap that
  waited draws its marker on a screen taken before the one it matched. Taking the step's capture
  from the poll that matched fixes both without adding captures. The Maestro path already does
  this: it captures right before the gesture, after the element was found, and again once an
  assertion passes. Before changing the others, check every reader that assumes a step's capture
  is the "before" screen.
- **Stable pairing across runs.** Pairing screens by position breaks when a list is re-sorted.
  Recording an element id per string at capture would let two runs pair by element instead.

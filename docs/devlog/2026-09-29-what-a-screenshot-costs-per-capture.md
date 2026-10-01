---
title: "What a Screenshot Costs per Capture"
type: devlog
date: 2026-09-29
---

# What a Screenshot Costs per Capture

## Summary

Taking the screenshot is the most expensive part of a capture on Android and web, and the second
most expensive on iOS: roughly 100–175 ms of every capture. The session recording already holds the
same pixels, and a frame pulled from it after the run matches the real screenshot (mean SSIM 0.989
over 51 captures, see [Strings on Every Capture](2026-09-27-strings-on-every-capture.md)). So when a
run records video, a capture that only needs its picture for the report can skip the screenshot and
get its frame from the video after the run.

## The numbers

Time a capture spends reading the screen, with and without the screenshot:

| Platform (driver) | Screen read, no screenshot | Screenshot | Screenshot's share |
| :--- | ---: | ---: | ---: |
| Android (on-device accessibility) | 103 ms | +175 ms | ~63% |
| iOS (XCTest runner) | 161 ms | +100 ms, plus host encode | ≥38% |
| Web (Playwright) | 13 ms | +114 ms, plus host encode | ~90% |

All figures are medians.

- **Android** is an A/B of the runner's own `GetScreenStateRequest`, called directly on the device
  and alternating `includeScreenshot` false and true (40 calls each). The emulator was a private
  API 33 one at 1080×2400. A capture took 103 ms without the screenshot and 277 ms with it, which
  includes scaling, WebP encoding (about 32 KB), and the RPC reply. A second pass, taken while the
  laptop was under heavier load, gave 126 → 368 ms (+242 ms).
- **iOS** comes from `takeScreenshot` and `contentDescriptor` spans recorded in real runs over two
  weeks. p90 is 164 ms for the screenshot and 227 ms for the tree. The host decode, scale and encode
  that follows is not traced. An earlier measurement on a simulator put the decode at about 34 ms.
- **Web** comes from `screenshot` and `ariaSnapshot` spans in real runs over two weeks, with p90 of
  170 ms. That figure is the raw PNG only. Scaling and encoding come after it and are not traced.

Over a whole run: the 3-minute session used as the frame proof had 51 captures. At these rates that
is about 9 s of screenshot time on Android (around 5% of the run), and about 5–6 s on iOS or web.

Pulling the frames from the video instead costs nothing while the run is going. After the run,
`trailblaze strings extract` saved all 51 frames from that session's 3-minute recording in 8 s,
including JVM start.

## Where it applies

- **Only when the run records video.** Video is off by default (`capture-video`). Recording has its
  own cost, which this entry does not measure, so the saving is only net for runs that record
  anyway, or once recording is shown to cost less than the screenshots it replaces. That cost is
  measured in [Take Report Pictures from the Video](2026-09-30-video-instead-of-screenshots.md).
- **Only for captures whose picture nobody needs during the run.** A capture that goes to the model
  needs its screenshot right away, since the model reads the image. The candidates are log
  captures: replayed recorded steps, asserts, and the "before" picture of an action that the report
  shows.
- **Android already moves part of this off the critical path.** For action logs, the scale and
  encode are deferred until the bytes are first read. What still sits in the capture is the grab
  itself: on-device `screencap` of a raw frame took 85–134 ms, including starting the process.

## What didn't work

Fresh timings on the laptop. Other sessions pushed the load average past 400, and iOS calls
measured then took seconds (`axe describe-ui` 5–6 s, `simctl io screenshot` 5–13 s). That is why
the iOS and web figures come from traces recorded in real runs, and why the Android A/B is given
with both of its passes.

## Open questions

- What does recording cost per platform? Answered in
  [Take Report Pictures from the Video](2026-09-30-video-instead-of-screenshots.md): free during the run on
  iOS, about 100 ms per tool call on web, and on an Android emulator free on a still screen but
  costly while the screen animates.
- These are laptop and emulator numbers. Farm devices should be measured too before this is made
  the default.
- iOS on AXe has too few traced screenshots to report (n=2).

## Future work

- A switch that skips the in-run screenshot for log captures when video is recording, relying on
  the frames saved from the recording.

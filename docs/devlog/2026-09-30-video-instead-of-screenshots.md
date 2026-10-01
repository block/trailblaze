---
title: "Take Report Pictures from the Video"
type: decision
date: 2026-09-30
---

# Take Report Pictures from the Video

**In CI, take the pictures for log-only captures from the session video after the run. Keep in-run
screenshots only where the model has to see the screen.**

CI already records video on every run, so the recording is paid for. A frame pulled afterwards costs
less than a screenshot taken during the run, on every platform:

```text
                 time per capture
iOS      ████████████████████████████████████████  150 ms  screenshot during the run
         ████                                       16 ms  frame from the video, after     9× cheaper

Android  ████████████████████████                   90 ms  screenshot during the run
         ████                                       16 ms  frame from the video, after     6× cheaper

Web      ███████████████                            58 ms  screenshot during the run
         ████                                       16 ms  frame from the video, after     4× cheaper

A 51-capture iOS run:   7 s of screenshots on the run's clock  →  0.8 s of frames after it
```

| When | Do this | Why |
| :--- | :--- | :--- |
| The model has to see the screen | Screenshot during the run | The model reads the image before its next move. |
| Log-only capture, video on (every CI run) | Frame from the video, afterwards | Saves 45–150 ms per capture on the run; costs ~16 ms per frame after it. |
| Local run, video off (the default) | Screenshot during the run | Turning video on just to replace it only pays on iOS. |

| Per log-only capture | iOS | Android | Web |
| :--- | :--- | :--- | :--- |
| Screenshot during the run | ~150 ms (45 ms grab + ~100 ms host encode) | 83–100 ms | 58 ms + encode |
| Frame from the video, afterwards | ~16 ms, after the run | ~16 ms, after the run | ~16 ms, after the run |
| Frame lines up within | ±6 ms | ±20 ms | ±50 ms |

**One fix comes first:** take the frame at the moment the tree was read, not at the capture log's
timestamp, which can be up to 270 ms later. That only matters on a moving screen.

Figures are medians from quiet machines. "Log-only" captures are the ones only the report sees:
replayed recorded steps, asserts, and the "before" picture of an action. Frames come from
`CaptureVideoFrames`, which already fills every capture that has no screenshot. The rest of this entry is the evidence.

## The hypothesis

The starting hypothesis: if a run records video and its frames line up with the run's events to
within a few milliseconds, frames from the video are cheaper than screenshots taken during the run,
and they show more. For turning video on just for this, rather than in CI where it is already on:

- **Cheaper:** yes on iOS and on Android screens that are standing still. On web, recording adds
  about 100 ms to every tool call, which is roughly what the screenshot costs, so web breaks even.
  On an Android emulator the screen being redrawn while it records makes each capture about 220 ms
  slower, which is more than the screenshot saves.
- **A few milliseconds:** iOS holds it, with frames within about ±6 ms of each other around a fixed
  20 ms offset. The Android recorder is within ±20 ms around a fixed 100 ms offset. Web is only
  within about ±50 ms, because it records at 10 frames per second.
- **The screenshots are further off than the video.** On every platform the in-run screenshot shows
  the screen 130–190 ms before the time its log carries. A video frame at that log time shows a
  later screen than the screenshot did.
- **Shows more:** yes. The video has a frame every 17–100 ms. Captures give one or two per step.

This follows [What a Screenshot Costs per Capture](2026-09-29-what-a-screenshot-costs-per-capture.md),
which measured the screenshot alone.

## How it was measured

The screen shows a web page that draws the device's own clock, `Date.now()`, as a barcode on every
animation frame. Any picture of the screen (a video frame or a screenshot) can then be decoded to
the millisecond it was drawn. A trail waits one second and takes a capture (`takeSnapshot`), 12
times over. It was run three times with recording on and three times with it off, alternating.

The laptop was too loaded to measure on (load average 400–980; an iOS simulator screenshot took
5–13 s). The figures below come from quiet cloud machines:

- iOS: a simulator on a Mac (load under 2).
- Android: an API 36 x86_64 emulator on a 96-core Linux machine (load 4–11). The page ran in the
  emulator's WebView test browser, since the image has no Chrome.
- Web: Playwright's Chromium on the same Mac.

That page redraws the whole screen 60 times a second. That is the worst case for a video encoder,
so Android was also measured on the still home screen.

## What recording costs

| Platform (recorder) | Capture, off → on | Session | After the run |
| :--- | ---: | ---: | ---: |
| iOS (`simctl recordVideo`) | 280 → 286 ms | no change | +6 s (re-encode to WebM) |
| Web (CDP screencast) | +100 ms per tool call | +3 s | +1 s |
| Android (scrcpy), still screen | 380 → 385 ms | +0.3 s | none |
| Android (scrcpy), animating page | 595 → 817 ms | +7 s | none |

"Capture" is the median `takeSnapshot` span across runs. "Session" is the time from session start to session
end, over 12 captures and 13 waits. "After the run" is the added wall time once the session ends.

- **iOS** records on the host, so the run doesn't notice. The cost is ffmpeg re-encoding the
  recording after the run.
- **Web** adds about 100 ms to every tool call, not only captures (`web_wait` 1011 → 1108 ms,
  `web_snapshot` 222 → 333 ms). That time sits outside the Playwright work itself and isn't traced.
- **Android** encodes on the device. On an emulator that encoder runs in software and competes with
  the capture for CPU, but only while pixels change. Measured on the device's own capture call, 20–40
  calls per case, with the platform's `screenrecord` as the encoder:

  | Android capture call | No recording | `screenrecord` running, animating page | `screenrecord` running, still screen |
  | :--- | ---: | ---: | ---: |
  | Tree only | 241 ms | 427 ms | 74 ms (same as off) |
  | Screenshot only | 137 ms | 474 ms | 86 ms (same as off) |
  | Both | 341 ms | 865 ms | 157 ms (same as off) |

  A real app is still between actions and animates during transitions, so the cost falls between
  these two cases. Devices with a hardware encoder were not measured.

## Screenshot and tree together

| Platform | Tree | Screenshot | Both |
| :--- | ---: | ---: | ---: |
| iOS (XCTest runner) | 118 ms | 44 ms | 162 ms, one after the other or in parallel |
| Android, one call, animating page | 241 ms | 137 ms | 341 ms |
| Android, one call, still screen | 74 ms | 86 ms | 157 ms |
| Web (Playwright) | 3 ms | 55–68 ms | taken one after the other |

Asking for both at once doesn't make either one cheaper:

- **iOS:** the runner handles one request at a time. Sending the two in parallel took exactly as
  long as sending them in turn (p50 162 ms, p90 181 vs 183 ms, 40 rounds).
- **Android:** fetching both in one call saves 3–37 ms over two calls. That is the second round trip,
  not faster work.
- **iOS host work:** a whole iOS capture is about 280 ms. It reads the tree (125 ms), then takes the
  screenshot (45 ms), then spends about 100 ms on the host. That last part isn't traced. It is
  decoding, scaling and encoding the image and writing the log, and it only exists because there is
  a screenshot.

## Pulling frames after the run

- **The real extractor** (`trailblaze strings extract`) was timed on a 3-minute 1080×2340 recording
  whose 51 captures had no screenshot. It took 2.2 s with the frames to save, and 1.4 s on a rerun
  where they already existed. So the frames cost about 0.8 s, or ~16 ms each. The 1.4 s (JVM start,
  reading logs, strings) is paid with or without frames.
- **The clock videos** from this entry, with ffmpeg alone: 13 frames from each took 0.18–0.33 s,
  for 60, 35 and 10 fps recordings of about 20 s.
- **The same 51 captures as in-run screenshots** would spend 3–7 s on the run's clock.

These timings are from the laptop, at load average 20–27.

## How well the video lines up

For each video frame: the time the report assigns to it, minus the time the device drew what it
shows. For each capture: the in-run screenshot's time, minus the time on the capture's log.

| Platform | Frame spacing | Frame time − drawn time (p50, p10…p90) | Screenshot − log time (p50) |
| :--- | ---: | ---: | ---: |
| iOS | 17 ms (60 fps) | −14 to −21 ms, spread about 12 ms | −150 ms |
| Android | 28 ms (p90 41) | +99 ms, 75…115 | −135 ms (p10 −440) |
| Web | 100 ms (10 fps) | +11 ms, −20…+100 | −166 to −188 ms |

- **iOS** is the only platform that lands within a few milliseconds. Its offset is fixed, so it can
  be subtracted.
- **Android** frames are stamped about 100 ms after the screen was drawn, give or take 20 ms. The
  emulator's clock was 79 ms ahead of the host. The logs work that out on their own to within
  6 ms (−73 ms against −79 ms), so clock skew isn't the error.
- **Web** records the screencast at a fixed 10 frames per second. Any single frame can be up to
  100 ms stale, which sets the error however well the clocks agree.
- **Screenshots are the least exact.** A capture's log time is stamped after the image has been
  encoded. On iOS the log comes about 275 ms after the capture starts: the tree was read 140–270 ms
  before the log time, and the screenshot about 150 ms before it. So a video frame at the log time
  shows a later screen than both the screenshot and the tree. The median gap to the screenshot is
  150–166 ms on iOS, 140–170 ms on Android and 14–50 ms on web.

## Verdict

**Cheaper whenever the run records video anyway, which every CI run does.** Whether turning video
on just for this pays depends on the platform:
- iOS: recording costs nothing during the run. Dropping the screenshot saves about 150 ms per capture
  (45–55 ms screenshot plus about 100 ms of host work).
- Web: recording costs about 100 ms per tool call, roughly what the screenshot costs. It breaks even
  until that 100 ms is found.
- Android emulator: it depends on how much the screen moves. It's free when still. While animating,
  recording costs more than the screenshot it would replace.

**Within a few milliseconds, only on iOS today.**
- Android and web can be corrected to about ±20 ms and ±50 ms. Getting further needs:
  - removing Android's fixed 100 ms offset;
  - recording web at its real frame times rather than a fixed 10 frames per second.
- Any frame lookup must use the instant the tree was read. Today it uses the log's timestamp, which
  can be up to 270 ms later.

**More flexible, clearly.** A capture has one or two pictures per step, each 130–190 ms older than
its label. The video has a frame every 17–100 ms, so the report can show:
- the moment of a tap;
- the transition after it;
- a toast or spinner that came and went between two captures;
- the screen when an assert finally passed.

The capture model can't show any of these without taking more screenshots.

## What didn't work

The first Android and web runs were on the laptop at load 500–800. There, Android recording doubled
the session (17 → 33–40 s) and frames drifted 600–680 ms from their content. iOS couldn't start its
test runner within 10 minutes. Numbers from a machine that busy say how that machine behaves, not how
recording does. They are why everything above came from quiet machines.

## Open questions

- Android on real devices with a hardware encoder: does the animating-screen cost disappear?
- What the ~100 ms per tool call on web is.
- Where Android's fixed 100 ms comes from. The recorder carries device-side frame times, so
  scrcpy's reported time may be later than the frame it came with.

## Future work

- Record the instant the tree was read on each capture log, and take frames at that instant rather
  than at the log's timestamp.
- Record web at its real frame times rather than 10 frames per second.
- Once the first lands, skip the in-run screenshot for log-only captures whenever video is on.

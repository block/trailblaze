---
title: "Tracing: One Producer, One Timeline"
type: devlog
date: 2026-08-27
---

# Tracing: One Producer, One Timeline

## Summary

This arc started with a question — "wait, what is `trailblaze profile`?" — and the uncomfortable
answer: three profiling systems existed and none of them knew about the others. Twelve PRs later
there is one span producer (`TrailblazeTracer`), one durable timing record (the tool log's
`durationMs`), one consumer (`trailblaze profile`), and two export formats (Chrome Trace
`trace.json` always, OTLP via `trailblaze otel` on demand). Viewers are deliberately not ours.

## Where it started

Three systems, three blind spots:

1. **`TrailblazeTracer`** (`:trailblaze-tracing`) — a Chrome Trace recorder written long before any
   of this, wired into a handful of host paths, exported per session as `trace.json`. Nothing read
   it.
2. **`trailblaze profile`** (#5195–#5197) — an HTML timeline built from the session *logs*. It knew
   nothing about the tracer, so a 2.5-second tool call was one opaque block.
3. **Ad-hoc timers** — `PerformanceMetricsUtil` (print-the-elapsed-ms, zero callers),
   `AndroidTestMetrics` (a private three-phase benchmark counter in the in-process driver),
   `ReportTiming` (stdout stopwatches in report generation), and a scatter of
   `Console.log("...took ${elapsed}ms")`.

The tracer was the keeper. Everything since has been about making it the *only* producer and making
its output reach every place time is actually spent.

## The twelve PRs, as one story

**Make the profiler read the tracer.** #6273 taught `trailblaze profile` to open the session's
`trace.json`, so a tool decomposes into what ran inside it. #6281 added declared parentage
(`sid`/`psid` — our extension to the Chrome Trace format; Perfetto ignores unknown keys), replacing
timestamp-containment guessing. #6282 gave a trace an identity that survives leaving the process
(`trid`, OTel-width ids). #6284 added `SpanKind` so the HTTP producers record CLIENT.

**Break the run open.** #6300 attributed HTTP time to the tool that made the call. #6311 broke an
agent phase into the tools it ran. #6309 added `TraceLevel` (`off` / `normal` / `verbose`) with
VERBOSE-only `traceDetail` spans that compile to a field read at the default level — the license to
instrument hot paths without taxing them.

**Cross the process boundary.** #6308 added OTLP export via the OpenTelemetry SDK (`SpanData` over
already-ended spans — no tracer, no sampler). #6314 merged device and host halves into one
`trace.json` (`SessionTraceFile.merge` is now the file's only writer; `drain()` keeps the trace id
across flushes; the uploader declares its clock). #6329 carried a W3C `traceparent` on the device
RPC so a run traces as one tree instead of one per process.

**Instrument the drivers.** #6333 gave the Android accessibility driver its VERBOSE limb — screen
capture, tree building, selector resolution, per-action spans. #6385 did the same for the
in-process ANDROID_TEST driver, folding the last ad-hoc timer's phases into spans and deleting
`PerformanceMetricsUtil`.

## Is it one implementation now?

Producer-side: effectively yes. Four systems became one, and the two survivors are not duplicates:

- **Log `durationMs` stays.** It is the durable, always-on record the report renders. Tracing
  records at `normal` by default but is level-gated and can be turned off entirely; the log is the
  contract. Two layers by design — the profiler nests trace spans under log spans.
- **`AndroidTestMetricsSink` stays.** It is the numeric surface behind the published in-process
  benchmark figure. #6385's spans describe the same phase split for the timeline; the sink's
  arithmetic is untouched, and an on-device test asserts both surfaces keep reporting.

Known remainders, left deliberately:

- **`ReportTiming`** (report generation stopwatches). Report generation runs *after* the session's
  trace export, so spans recorded there can never reach a viewer — wrapping it today would be
  observability theater. It waits for a report-phase exporter.
- **`RpcRouteExt`'s per-route stopwatch** — the cheapest remaining real consolidation, same
  dependency.
- Scattered per-site `Console.log` diagnostics — noise, not a competing framework.

## Key decisions

- **Export the format, never own a viewer.** Ruled twice. `trace.json` opens in ui.perfetto.dev
  from the file picker; `trailblaze otel` feeds any OTLP viewer. No bundled viewer, no
  viewer-lifecycle code, no OTLP-to-anything converters in this repo.
- **`traceDetail` must be free when off.** It is `inline` and gates on a field read, which is what
  made instrumenting driver internals (hundreds of spans per step) acceptable.
- **Selector resolution is Trailblaze's own work, not the backend's.** The accessibility and
  in-process drivers both record `resolveSelector` nested inside the dispatch span. #6385 measured
  why it matters: one tool spent
  38.8 of its 40.9 ms dispatch resolving the selector; another spent 20 of 333. Same span, opposite
  diagnosis — and without the split, both blame the UI backend, the one place a slow selector
  cannot be fixed.
- **Span parenting is thread-local.** A raw `thread {}` records roots; coroutine dispatch works
  because `trace { }` is inline and the context element swaps frames. Every driver test asserts
  ancestry for exactly this reason.

## Driver coverage

Two layers apply to every driver because they live in shared code: the always-on tool log
(`durationMs`), and the per-tool span `BaseTrailblazeAgent` opens — every agent extends it. The
host process also traces agent phases, LLM calls, and HTTP for every driver, and `LoggingDriver`
adds a span per Maestro command for any Maestro-backed device. So **every driver profiles at tool
granularity today**. What differs is the driver-*internal* decomposition:

| Driver | Internal spans | Verbose level reachable? | Trace reaches the profile? |
|---|---|---|---|
| Playwright web + Electron | yes (browser, screen state, node mapping) | yes — host process, env var | always |
| ANDROID_TEST in-process | yes (#6385) | yes — instrumentation argument | written on device, CI pulls it |
| Android accessibility | yes (#6333) | **no — level stops at the daemon** | CI/JUnit yes; interactive runs drop the device half |
| Android instrumentation (Maestro) | command-level plus screen-capture spans | same gap as accessibility | same gap |
| iOS host (XCUITest) | command-level via `LoggingDriver`; below that is Maestro's own XCTest driver, external code | would just work — host process | host half only |
| iOS Axe (host-native) | none — `IosDeviceManager`/`AxeDeviceManager`/`SimctlCli` record nothing | would just work — host process | host half only |
| Compose | none | — | **lost: its in-app process never exports** (the JUnit rule and the host runner are the only exporters) |
| Revyl (cloud) | nothing local to trace; HTTP spans capture the API calls | yes — host | host half |

The asymmetry worth acting on: the accessibility driver is instrumented but dark until the level
rides the RPC (below), while the Axe path is coarse only because nobody has added spans — it runs
host-side through our own `IosDeviceManager`/`AxeDeviceManager`/`SimctlCli`, so `traceDetail` calls
there light up with zero plumbing. The XCUITest path is different: below the command boundary it is
Maestro's driver, not ours, so finer spans there mean instrumenting our capture code around it, not
inside it. Compose is the only driver whose spans would be *lost* rather than coarse; instrumenting
it is worthless until it has an export path.

## What still isn't reachable

The gap is no longer consolidation — it's turning the detail on end to end:

1. **The trace level stops at the daemon.** The in-process driver takes a
   `trailblaze.trace.level` instrumentation argument (#6385) because that instrumentation *is* the
   run. The accessibility driver cannot use an argument — its on-device server is reused across
   runs — so the level must ride the run request, mirroring #6329's `traceparent`. Until then,
   #6333's spans are unreachable in the documented workflow.
2. **The device never drains on the interactive RPC path.** The receiving half exists; the JUnit
   teardown that exports never fires on that path, so device spans arrive on CI lanes and are
   dropped on interactive runs. Per-session drain also bounds the recorder's growth on a long-lived
   server.
3. **An end-to-end one-trid proof** — a verbose RPC run whose merged `trace.json` shows
   device-clock spans under the host's trace id.
4. **`trailblaze profile --json`** — a ranked, diffable self-time table, so an agent can consume
   the profile without parsing HTML.

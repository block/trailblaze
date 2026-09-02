---
title: Performance Profiling
---

# Performance Profiling

`trailblaze profile` generates an Instruments-style time profiler over your trail
sessions: a zoomable multi-track timeline plus aggregate tables that show exactly where a
run's wall-clock time went. Where the [interactive run report](reports.md) answers *what
happened, step by step*, the performance-analysis report answers *why was it slow* - it
is the tool to reach for when you are optimizing a trail.

## Generating the report

```bash
trailblaze profile                 # profile the configured logs directory
trailblaze profile ./logs          # profile a specific logs directory (e.g. CI artifacts)
trailblaze profile ./logs --open   # ...and open the report in a browser
```

The command runs entirely standalone (no daemon needed): it reads the per-session log
directories from disk, profiles every session it finds, and writes one self-contained
HTML file at `<logs-dir>/trailblaze_performance_analysis.html`. It requires `bun` on
your `PATH`.

It is ad-hoc by design - `trailblaze run` and `trailblaze report` do not emit this
report; you ask for it when you want to dig into performance. See the
[CLI reference](CLI.md#trailblaze-profile) for the full flag list.

## Reading the timeline

The report opens on a session index; pick a session to see its profile. The timeline
stacks one track per source of time:

- **Steps** - the trail's steps, end to end.
- **Gaps** - idle stretches where no tool or LLM call was in flight.
- **Tools** - every tool call, nested by call depth (a flame-graph-style lane).
- **LLM** - agent LLM requests, when the session used the agent.
- **Device (skewed clock)** - device-side spans stamped on the device's own clock. These
  are drawn where the device reported them; when the device clock is far out of sync with
  the host, the lane says so instead of guessing an alignment.

Navigation matches what you'd expect from a native profiler: **wheel** zooms at the
cursor, **shift+wheel** pans, **drag** selects a time range, and **double-click** zooms
to a span. Selecting a range rescopes the tables below to just that window, so you can
zoom into one slow step and ask "what was running here?"

## The aggregate tables

Below the timeline, four tabs break the time down:

- **Bottom-Up** - the heaviest operations first: total self time, call count, and worst
  single call for each tool and LLM call name. Double-click a row to jump the timeline to
  that name's biggest contributor in the selected range.
- **Call Tree** - the same data top-down, following the nesting of the Tools lane.
- **Timeout Tax** - tools that carry a timeout budget, with how much of it they actually
  burned. A tool that routinely spends its whole budget before failing (or barely uses it
  before succeeding) is a prime tuning target. Spent and budget figures are always
  whole-invocation totals, even when a range is selected.
- **Gaps** - the idle stretches, largest first, clipped to the selected range.
  Double-click a gap to zoom the timeline to it.

## Comparing two runs

The **Compare** picker overlays a second session as run B. The timeline shows both runs,
and an **A/B Diff** tab appears with a per-operation delta table - which tools got
slower, which got faster, and by how much. This is built for before/after measurement:
record a baseline run, make your change, run again, and diff the two sessions of the
same trail. All the other tabs stay available in compare mode and keep reporting run A.

## One timeline, from every process a run touches

A session's spans are recorded in more than one place. The host records the agent, the LLM calls and
the tools it dispatches; a device records what the driver actually did - the accessibility capture,
the view-hierarchy walk - and uploads that to the host when the run ends. Both halves are merged into
the session's single `trace.json`, which is what lets the **Device (skewed clock)** lane sit
alongside the host's tools on one timeline.

They share one trace id because the dispatch carries it. Every process mints its own trace id on
first use, so left alone the two halves would arrive as two unrelated traces; instead the host sends
the trace it is recording plus the span it is dispatching from - a W3C `traceparent` on the request -
and the device records into that trace, hanging its own top-level spans under the host tool call that
asked for the work. A host that isn't recording sends nothing and the device traces its own half on
its own, which is also what an older device or host does - and because a device's RPC server serves
run after run, a dispatch that names no trace also stops the device inheriting the one the previous
run handed it.

Uploads are merged, not overwritten, and a batch that arrives twice is recorded once - so a device
that retried its upload doesn't double the timeline. Each upload says which clock stamped it, and a
device's spans are marked as such: they carry that device's wall clock, so they belong on the Device
lane, and their skew from the host's clock - whole seconds, routinely - must not be read as elapsed
time and stretch the session window around it. The uploader has to declare this rather than the
receiver assume it, because the host posts its own trace through the same route.

A run that starts while another is still going shares its recording, so both sessions' `trace.json`
hold both runs' spans. The alternative was worse: a second run clearing the recorder would delete
what the first had buffered.

## Choosing how much to record

A run records at one of three levels, set by `TRAILBLAZE_TRACE_LEVEL` (or
`-Dtrailblaze.trace.level`, which wins):

| Level | Records |
|---|---|
| `off` | Nothing. |
| `normal` (default) | Tools, agent phases, LLM calls, HTTP requests. |
| `verbose` | The above, plus the fine-grained spans underneath — driver operations, screen-capture internals, per-node selector matching. |

Those fine-grained spans are still being instrumented, layer by layer. Until a layer has them,
`verbose` records the same thing `normal` does for that layer — so if a run at `verbose` looks
identical to one at `normal`, the flag is working and the detail simply isn't there yet.

Instrumented so far: the host driver and HTTP layers, and — on Android runs driven by the
accessibility driver — the on-device capture, which records a screen capture's tree-stability wait,
window-root enumeration, node refresh, the two tree builds, and the screenshot as separate spans.
The in-process `ANDROID_TEST` driver records each tool's phases: resolving `{{memory}}`
placeholders, the native dispatch, and writing the tool log. Selector resolution is a span of its
own inside the dispatch, because a native tool resolves its own selector — so a slow selector reads
as a slow selector rather than as slow Espresso.

Anything a device records is stamped by the device's own clock, which drifts from the host's, so
those spans go on the profiler's flat **Device (skewed clock)** lane instead of into the host tree.
They keep their parent links in `trace.json`, but the lane does not yet draw them as a tree or
subtract a child's time from its parent — so read a device span's bar as its total, not its own
cost, and expect a parent and its children to each account for the same milliseconds.

```bash
TRAILBLAZE_TRACE_LEVEL=verbose trailblaze run my-trail.trail.yaml
trailblaze profile --open
```

The level travels with the run. `trailblaze run` normally hands the work to a background daemon
that outlives it, so the level is read from the environment of the shell you typed the command in
and applied to that run only - a daemon left running from an earlier `verbose` investigation does
not keep recording every later run that way.

It travels as far as the host and its daemon. An Android device runs the driver in a third process -
the instrumentation on the device - and the level does not yet reach it, so a `verbose` run records
the host's detail and the device's spans at `normal`. The on-device spans described above are
recorded and tested, but until the level is forwarded over the run's device RPC, seeing them means
configuring that instrumentation process itself rather than the run.

The in-process `ANDROID_TEST` driver is configured that way, as an instrumentation argument, and
there it is the whole story: that instrumentation *is* the run, so nothing has to be forwarded.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.trailblaze.trace.level=verbose
```

`AndroidTestLoggingRule` applies it for the test and puts the previous level back afterwards, so
the level lands in the `trace.json` the run writes to `Download/trailblaze-logs`.

`verbose` is for a specific investigation - reach for it when a tool's time is unexplained at
`normal` and you need to see inside it. It is not a default, because its spans fire hundreds of
times per step: a span that costs more than the work it measures doesn't just slow the run, it
changes the shape of what you are trying to profile. Everything at `normal` wraps work measured in
tens of milliseconds or more, so its recording cost is lost in the noise.

Turning detail off never orphans the spans that remain. A span nested inside a suppressed one
reparents to the nearest span that is still recorded, so the tree stays connected at every level.

## OpenTelemetry export

The same spans the profiler reads can be exported as OpenTelemetry, for viewing in an OTel
trace viewer or for handing to a collector:

```bash
trailblaze otel                              # convert every session in the configured logs dir
trailblaze otel ./logs/my-session            # convert one session
trailblaze otel --post                       # ...and send it to the configured (or default) endpoint
trailblaze otel --endpoint http://host:4318  # ...and send it there
```

Each session gets an `otel.json` beside its `trace.json` - one OTLP/JSON request, which is
what an OTLP-aware viewer ingests. `--post` sends the same payload to a live endpoint (`--endpoint` picks one, and implies `--post`); port
`4317` is treated as gRPC and anything else as OTLP/HTTP.

To skip the manual step, set an endpoint and a run exports itself as soon as it finishes:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
trailblaze run my-trail.trail.yaml
```

Export is opt-in and best-effort. With no endpoint set nothing is sent, and a viewer that
isn't listening is reported without failing the run - `trace.json` is written either way.
See the [configuration reference](configuration.md#opentelemetry-export) for the variables.

### What survives the conversion

The export is a translation of what was recorded, and OpenTelemetry's exporters do the
encoding, so the file and the wire carry identical bytes. A few things are worth knowing:

- **Only declared parentage.** A span nests under the parent its producer recorded. The
  timestamp-and-thread inference the profiler falls back to for older traces stays in the
  profiler: a guessed parent is indistinguishable from a real one downstream, and a viewer
  would present it as fact.
- **Spans from before span identity existed** get ids minted during the conversion, marked
  with a `trailblaze.synthetic_span_id` attribute. A trace with no recorded id joins one
  shared trace rather than becoming one trace per span.
- **Each process becomes its own resource.** A trace spanning a host run, its daemon and a
  device would otherwise read as a single process with impossible thread interleaving.
- **Recorded event args become `trailblaze.*` attributes**, and a span that recorded an
  error arrives with an error status so a viewer's error filter finds it.

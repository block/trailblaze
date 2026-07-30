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

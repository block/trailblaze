---
title: "Run Trails on GitHub Actions"
type: decision
date: 2026-10-13
---

# Run Trails on GitHub Actions

## Summary

Any GitHub repository can now run its trails in CI with one reusable workflow,
[`trail-runner.yml`](https://github.com/block/trailblaze/blob/main/.github/workflows/trail-runner.yml).
The repository commits a small request file that lists devices (`android`, `ios`, `web`), trails
and shard counts. The workflow runs every shard on its own runner and reports once: a pass/fail job
summary, one PR comment that updates on every push, and one merged HTML report.

A run can also name a prebuilt Android instrumentation test APK instead of trails, the way a device
farm runs one.

Usage is documented in [Run Trails on GitHub Actions](../github-actions.md).

## Why

Recorded trails replay with no LLM, so they are cheap enough to run on every pull request. What
was missing was the CI wiring: boot an emulator or simulator, install the app, run the trails,
split them across machines, and turn the sessions into something a reviewer reads on the PR.
Every repository would otherwise write that itself.

Replay is the default for the same reason. It needs no API key, so it works on pull requests from
forks, where GitHub withholds secrets.

## Decisions

### Its own small request format

The request file is a new format, kept deliberately small: a list of runs, each with a device, what
to run, and a shard count. It is not a general CI configuration language and it is not tied to any
other pipeline. A team reads it in one screen and writes one by copying a neighbour.

### It lives in the public repository, under `.github/`

The workflow and the composite action it calls live at `.github/workflows/trail-runner.yml` and
`.github/actions/trailblaze/` in `block/trailblaze`. That is where GitHub resolves
`uses: owner/repo/path@ref`, so a caller references it directly, and a release tag pins both the
workflow and the CLI it installs. Trailblaze's own pull request checks run through it, so the
workflow every caller gets is the one tested on every change.

### Test APKs run without Gradle

A test APK run installs a prebuilt APK and runs it with `am instrument`. Sharding uses Android's own
`numShards` / `shardIndex`, and `orchestrator: true` runs each test in its own process with the
app's data cleared between tests. The runner needs no Gradle and no source checkout of the app. The
Trailblaze Gradle plugin is one way to package trails into a test APK, not a requirement. The
sessions the tests write on the device join the same report as trail runs.

### Results stay in artifacts unless asked

`publish: none`, the default, keeps sessions in workflow artifacts. On a private repository only
people with access can open them. `publish: branch` also pushes session zips and storyboards to a
branch, so the summary shows storyboards inline and links straight into the report viewer. Anyone
can open those, so it is for public repositories only. On a public repository the artifacts are
public as well, so sessions there must be safe to share in either mode.

## How it works

1. **Plan.** One job reads the request file and expands it into a job matrix: one job per run per
   shard.
2. **Run.** Each job boots its device (an Android emulator on Linux, an iOS simulator on macOS, or
   Chromium), installs the app, and runs its share of the trails or tests.
3. **Report.** One job downloads every shard's sessions, merges them into one HTML report, writes
   the job summary, posts or updates the PR comment, and fails the build if any trail failed.

The workflow first shipped in v2026.10.13. A caller pins a release tag, such as
`@v2026.10.13`, and the tag picks both the workflow and the CLI. Pinning a commit on `main` works
too, with `version:` naming the CLI release.

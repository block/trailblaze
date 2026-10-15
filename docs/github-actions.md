---
title: GitHub Actions
---

# Run Trails on GitHub Actions

Trailblaze ships a reusable GitHub Actions workflow that runs your trails on Android emulators, iOS
simulators and Chromium, on your repository's own runners. You commit one request file that lists
the devices and trails. The workflow runs every shard in parallel and reports once:

- a pass/fail table in the job summary, with a storyboard per trail when results are
  [published to a branch](#publishing-results),
- one PR comment, updated on every push,
- one merged HTML report as an artifact.

Recorded trails replay with no LLM, so no API key is needed and pull requests from forks work too.

The workflow is
[`.github/workflows/trail-runner.yml`](https://github.com/block/trailblaze/blob/main/.github/workflows/trail-runner.yml)
and the action it calls is
[`.github/actions/trailblaze`](https://github.com/block/trailblaze/tree/main/.github/actions/trailblaze),
both in `block/trailblaze`. The
[action README](https://github.com/block/trailblaze/blob/main/.github/actions/trailblaze/README.md)
is the full reference.

## A minimal caller workflow

Build your app, upload it as an artifact, and hand that artifact to the trail runner:

```yaml
# .github/workflows/trails.yml
name: Trails
on: [pull_request, push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - run: ./gradlew :app:assembleDebug
      - uses: actions/upload-artifact@v7
        with: { name: app, path: app/build/outputs/apk/debug/app-debug.apk }

  trails:
    needs: build
    permissions: { contents: read, pull-requests: write }
    uses: block/trailblaze/.github/workflows/trail-runner.yml@v2026.10.13
    with:
      request: .github/trailblaze-runs.yaml
      app-artifact: app
```

The files in `app-artifact` land in the workspace root, so the request file refers to them by bare
name (`app-debug.apk`). An iOS run needs its simulator `.app` in the same artifact. See
[Versioning](#versioning) for which tag to pin.

## The request file

The request file is YAML (JSON also works). Each entry under `runs:` is one kind of device, running
either trails or an Android instrumentation test APK:

```yaml
# .github/trailblaze-runs.yaml
defaults:
  mode: replay                  # replay | self-heal | ai
runs:
  - name: android-phone
    device: android             # an emulator, on ubuntu-latest
    android: { api: 34, profile: pixel_6 }
    app: app-debug.apk          # from app-artifact
    trails: [trails/mobile/]
    shards: 4

  - name: iphone
    device: ios                 # a simulator, on macos-26
    ios: { simulator: iPhone 17 Pro }
    app: MyApp.app              # a simulator build, also in app-artifact
    trails: [trails/mobile/]

  - name: chrome
    device: web                 # Chromium, on ubuntu-latest
    trails: [trails/web/]

  - name: android-instrumented  # a test APK, the way a device farm runs one
    device: android
    app: app-debug.apk
    test-apk: app-debug-androidTest.apk
    test-targets: [class com.example.LoginTest, package com.example.checkout]
    orchestrator: true
    shards: 4
```

### Fields

| Field | Meaning |
|---|---|
| `name` | Lowercase letters, digits and dashes. Names the jobs and artifacts. |
| `device` | `android`, `ios` or `web`. |
| `trails` | Files, globs or directories, as `trailblaze run` takes them. |
| `test-apk` | Android only, instead of `trails`: an instrumentation test APK. Its tests' sessions join the same report. |
| `test-targets` | Which of the test APK's tests to run: `class …`, `package …`, `annotation …`, `size …`, or a `not` form such as `notClass …`. All of them when omitted. |
| `test-args` | Instrumentation arguments for the test APK. Gradle passes these at `connectedAndroidTest` time; they aren't in the APK. |
| `orchestrator` | Run the test APK under Android Test Orchestrator: each test in its own process, with the app's data cleared between tests. |
| `app` | APK or simulator `.app` to install first, relative to the workspace. |
| `shards` | Parallel jobs for this run. See [Sharding](#sharding). |
| `mode` | `replay` (default), `self-heal` or `ai`. Trails only. The last two need an LLM key, which the reusable workflow doesn't pass to its jobs; see [Using an LLM](#using-an-llm). |
| `android` | `api` (default 34) and `profile` (default `pixel_6`). Use the device the trails were recorded on. |
| `ios` | `simulator` (default `iPhone 17 Pro`). |
| `config-dir` | The workspace's `trails/config`, when it isn't found from the trails. |
| `setup` | Shell command run once the device is up, before the trails. |
| `args` | Extra `trailblaze run` flags. Trails only. |
| `runs-on` | Override the runner label. |

Anything under `defaults:` applies to every run unless the run sets it.

### Running a test APK

A run can name a prebuilt instrumentation test APK instead of trails. The runner installs it,
runs it with `am instrument`, and collects the Trailblaze sessions the tests wrote on the device
into the same report as everything else. There is no Gradle on the runner: build the APK in your
own job and pass it through `app-artifact`. The
[Trailblaze Gradle plugin](https://github.com/block/trailblaze/blob/main/trailblaze-android-gradle/README.md)
is one way to package trails into a test APK, but any instrumentation APK works.

## Sharding

`shards: N` splits one run across N parallel jobs, each with its own device.

- **Trails** are dealt out by directory, so a trail's files stay together. The number of shards is
  capped at the number of trails.
- **A test APK** uses Android's own sharding (`numShards` / `shardIndex`), which splits the tests
  by a hash of their names.

The workflow input `max-parallel` (default 20) caps how many jobs run at once across all runs.

## Publishing results

The `publish` input decides where the sessions go:

| `publish` | What happens | Use it for |
|---|---|---|
| `none` (default) | Results stay in workflow artifacts. The summary links to them instead of showing storyboards. | Private repositories, where artifacts need repository access. |
| `branch` | Also pushes session zips and storyboards to a `trailblaze-sessions` branch, so the summary shows storyboards inline and links each session straight into the [report viewer](report-viewer/index.html). | Public repositories only. Needs `contents: write`. Skipped on fork PRs. |

The PR comment needs `pull-requests: write`; set `comment: false` to turn it off.

Sessions hold screenshots, logs and any captured network traffic. On a public repository, anyone signed in
to GitHub can download the workflow artifacts, whichever mode you pick, so only run trails there whose sessions are
safe to share.

## Workflow inputs

| Input | Default | Meaning |
|---|---|---|
| `request` | required | The request file. |
| `app-artifact` | none | Artifact from your workflow holding the app, and the test APK if a run uses one. |
| `version` | the pinned tag | The Trailblaze CLI release to run. |
| `cli-artifact` | none | Artifact holding `trailblaze` and `trailblaze.jar` built earlier in the run. |
| `max-parallel` | 20 | Most jobs at once. |
| `publish` | `none` | `none` or `branch`. See [Publishing results](#publishing-results). |
| `comment` | `true` | Post the summary as one PR comment. |

## Versioning

Pin the workflow to a [Trailblaze release](https://github.com/block/trailblaze/releases) tag. The
workflow first shipped in v2026.10.13. The tag pins both the workflow and the CLI it runs, so no
`version` input is needed:

```yaml
uses: block/trailblaze/.github/workflows/trail-runner.yml@v2026.10.13
```

To move to a newer release, change the tag. Set `version` only to run a different CLI release than
the tag's.

To try an unreleased change on `main`, pin a commit instead. A commit doesn't name a CLI release,
so set `version` too:

```yaml
uses: block/trailblaze/.github/workflows/trail-runner.yml@<commit-sha>
with:
  request: .github/trailblaze-runs.yaml
  version: "2026.10.13"
```

## Using an LLM

The reusable workflow replays recordings and takes no secrets, so `mode: self-heal` and `mode: ai`
have no key to use through it. To run with an LLM, use the action below in your own job, put the
key in that job's environment, and pass the mode as `trailblaze run` flags, such as
`args: --use-recorded-steps --self-heal`. Fork PRs don't get secrets, so keep those jobs off fork
PRs.

## One device in a job you already have

To run one device's trails as a step inside an existing job, use the action directly:

```yaml
- uses: block/trailblaze/.github/actions/trailblaze@v2026.10.13
  with:
    device: android
    app: app/build/outputs/apk/debug/app-debug.apk
    trails: trails/
```

Its inputs are listed in
[`action.yml`](https://github.com/block/trailblaze/blob/main/.github/actions/trailblaze/action.yml).

## Real examples

- [ShoppingApp](https://github.com/handstandsam/ShoppingApp/blob/main/.github/workflows/trailblaze.yml)
  pins v2026.10.13, replays its Android trails on two shards and publishes to a branch, so its PR
  comments link straight into the report viewer
  ([request file](https://github.com/handstandsam/ShoppingApp/blob/main/.github/trailblaze-runs.yaml)).
- Trailblaze's own pull request checks run Android, iOS, web and an on-device test APK through
  the workflow: see the `trail-runner` job in
  [`pr-checks.yml`](https://github.com/block/trailblaze/blob/main/.github/workflows/pr-checks.yml)
  and its [request file](https://github.com/block/trailblaze/blob/main/.github/trailblaze-runs.yaml).

## Limits

- A request makes at most 256 jobs across all its runs and shards, GitHub's matrix limit.
- GitHub's free plan runs 20 jobs at once, of which 5 can be macOS.
- macOS minutes are free on public repositories and cost ten times Linux minutes on private ones.

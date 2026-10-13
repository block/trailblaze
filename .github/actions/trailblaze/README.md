# Run Trailblaze trails on GitHub Actions

List the devices and trails in one request file, and GitHub runs every trail on every device in
parallel on your repo's own runners, then reports once: a pass/fail table and storyboards in the job
summary, one PR comment updated on every push, and the HTML report as an artifact. Recorded trails
replay with no LLM and no API key, so fork PRs work too.

## Quick start

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
    uses: block/trailblaze/.github/workflows/trail-runner.yml@<release-tag>
    with:
      request: .github/trailblaze-runs.yaml
      app-artifact: app
```

Replace `<release-tag>` with a Trailblaze release, such as `v2026.10.20`: the tag pins both the workflow and the CLI it runs.

## The request file

YAML, or JSON, which is also valid YAML. Each run is one device; `shards` splits its trails across
that many parallel jobs, keeping each trail directory together.

```yaml
defaults:
  mode: replay                  # replay | self-heal | ai
runs:
  - name: android-phone
    device: android             # an emulator, on ubuntu-latest
    android: { api: 34, profile: pixel_6 }
    app: app-debug.apk          # from app-artifact
    trails: [trails/]
    shards: 4
  - name: iphone
    device: ios                 # a simulator, on macos-26
    ios: { simulator: iPhone 17 Pro }
    app: MyApp.app
    trails: [trails/]
  - name: chrome
    device: web                 # Chromium, on ubuntu-latest
    trails: [trails/web/]
```

| Field | Meaning |
|---|---|
| `name` | Lowercase letters, digits and dashes. Names the jobs and artifacts. |
| `device` | `android`, `ios` or `web`. |
| `trails` | Files, globs or directories, as `trailblaze run` takes them. |
| `app` | APK or simulator `.app` to install first, relative to the workspace. |
| `shards` | Parallel jobs for this run. Capped at the number of trails. |
| `mode` | `replay` (default), `self-heal` or `ai`. The last two need an LLM key. |
| `android` | `api` (default 34) and `profile` (default `pixel_6`). Use the device the trails were recorded on. |
| `ios` | `simulator` (default `iPhone 17 Pro`). |
| `config-dir` | The workspace's `trails/config`, when it isn't found from the trails. |
| `setup` | Shell command run once the device is up, before the trails. |
| `args` | Extra `trailblaze run` flags. |
| `runs-on` | Override the runner label. |

## Workflow inputs

| Input | Default | Meaning |
|---|---|---|
| `request` | required | The request file. |
| `app-artifact` | none | Artifact from your workflow holding the app. Its files land in the workspace root. |
| `version` | the pinned tag | A different CLI release. |
| `cli-artifact` | none | Artifact holding `trailblaze` and `trailblaze.jar` built earlier in the run. |
| `max-parallel` | 20 | Most jobs at once. |
| `publish` | `none` | `none` keeps results in artifacts, which need repo access to open: right for private repos. `branch` also pushes session zips and storyboards to a `trailblaze-sessions` branch, so the summary gets one-click [report viewer](https://block.github.io/trailblaze/report-viewer/) links and inline storyboards anyone can open. Public repos only; needs `contents: write`; fork PRs skip it. |
| `comment` | `true` | Post the summary as one PR comment. Needs `pull-requests: write`. |

## One device in your own job

The action under this directory runs one device's trails in a job you already have:

```yaml
- uses: block/trailblaze/.github/actions/trailblaze@<release-tag>
  with:
    device: android
    app: app/build/outputs/apk/debug/app-debug.apk
    trails: trails/
```

Its inputs are listed in [`action.yml`](action.yml).

## Limits

- A run makes at most 256 jobs, GitHub's matrix limit.
- GitHub's free plan runs 20 jobs at once, of which 5 can be macOS.
- macOS minutes are free on public repos and cost ten times Linux minutes on private ones.

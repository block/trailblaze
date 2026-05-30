# Rung 2 — Save and replay

Once you've driven a device (see [`drive-device.md`](drive-device.md)),
Trailblaze has been recording each step into a session. You can save the
session as a `.trail.yaml` file for later deterministic replay, inspect
it in the Trace Viewer, or commit it to the user's repo as a CI test.
Same artifact, three uses — and **no LLM at replay time by default**.
The two exceptions are opt-in: `--self-heal` puts the LLM back in the
loop to patch a failing step against the live screen, and a trail step
that ships with only a natural-language description (no `recording:`
block) falls back to LLM execution. Default deterministic; opt-in
agentic.

Load this reference when the task is to save a trail, replay one, run
a suite, inspect a session (HTML report or desktop trace viewer), or
look up the results of a past CI run.

## Contents

- [Sessions are auto-created on first device action](#sessions-are-auto-created-on-first-device-action)
- [Saving a session as a `.trail.yaml`](#saving-a-session-as-a-trailyaml)
- [Replaying a trail](#replaying-a-trail)
- [Inspecting a session — generate an HTML report](#inspecting-a-session--generate-an-html-report)
- [Inspecting a session in the desktop Trace Viewer](#inspecting-a-session-in-the-desktop-trace-viewer)
- [Looking up past results](#looking-up-past-results)

## Sessions are auto-created on first device action

You don't need to start a session explicitly. The first `snapshot` or
`tool` call against a given `(device, target)` pair creates a
session; subsequent calls on that pair reuse it. The "Target app
changed (X → Y) — creating new session" first-call message is exactly
this auto-creation happening.

If you want explicit control (e.g., to force a clean session start):

```bash
trailblaze session start -d <device>   # explicit start with video + logs capture
trailblaze session stop -d <device>    # finalize captures, release the device
```

The full `session` surface:

```bash
trailblaze session list                       # recent sessions
trailblaze session info --id <session-id>     # detail on one session
trailblaze session artifacts --id <id>        # what got captured (screenshots, video, logs)
trailblaze session recording --id <id>        # the recording YAML for a session
trailblaze session delete --id <id>           # delete logs + artifacts
```

(`info`, `artifacts`, and `recording` default to the current session
when `--id` is omitted. `delete` always requires `--id`. All four
accept an ID prefix.)

## Saving a session as a `.trail.yaml`

When the session has progressed to a point worth keeping (a flow ran
successfully, an interesting state is captured), save it:

```bash
trailblaze session save                       # save the current session
trailblaze session save --id <session-id>     # save a specific session (id prefix OK)
trailblaze session save -t "<title>"          # save with a title
```

`session save` operates on the **current session by default** — there
is no `--device` flag on this subcommand. It writes the recorded
steps to a `.trail.yaml` file. **The session stays active** — you
can keep driving the device and `session save` again later to capture
the extended flow. Use `session stop` when you're actually done with
the device. A one-shot alternative is `trailblaze session stop --save -d <device>`
which saves and ends in one step.

The trail YAML has two halves per step:
- **Natural-language step** — the `-s "<step>"` you passed at
  record time. The prose source of truth.
- **Recording** — the resolved selector, the captured screen state,
  the tool that ran and its args. Frozen at record time.

The natural-language steps describe *what* the test does; the
recordings describe *how* it runs on this specific platform.

## Replaying a trail

```bash
trailblaze run <trail.yaml> -d <device>
```

`run` replays the trail deterministically. Frozen selectors mean no
LLM at replay time — refs that were recorded resolve back to the
same elements on the same screen. Fast, predictable, zero token
cost.

`run` accepts files, shell globs, or directories. Directories recurse
to one trail per containing directory (recording preferred over NL
when both are present):

```bash
trailblaze run trails/login.trail.yaml -d <device>
trailblaze run trails/ -d <device>                            # every trail under trails/
trailblaze run 'trails/**/login.trail.yaml' -d <device>       # glob pattern
```

Useful `run` flags:

- **`--self-heal`** — when a recorded step doesn't match the live
  screen anymore (UI drift), Trailblaze's built-in agent patches the
  failing step against the live screen and updates the recording on
  success. Default is fail-loud (no patching), so flakes don't get
  silently masked.
- **`--memory KEY=VAL`** — seed runtime values into the trail's
  memory (e.g., usernames, search terms).
- **`--secret KEY=VAL`** — seed sensitive values (e.g., passwords)
  that won't be logged.
- **`--tags <tag>[,<tag>,…]`** — only run trails matching the given
  tag filter.

Trails declare their own metadata too: a `tags:` field at the trail
level lets you partition the suite, and a `skip:` field (with a
reason string) reports the trail as skipped at runtime and exits 0
for that file's slot. Blank `skip:` is ignored. To unskip a trail,
remove its `skip:` line.

**Memory/secret resolution contract.** When a trail step references
a `{ memory: KEY }` or `{ secret: KEY }` value that was never supplied
on the command line, `run` fails the step with a structured error
naming the missing key — don't paper over it, fix the invocation.
Later `--memory K=V` overrides earlier occurrences of the same key
(last-write-wins); extra keys not referenced by any step are silently
ignored.

**When `run` fails before reaching a step.** File-not-found / no-glob-match
exits non-zero with the resolved path. Malformed YAML reports parse
errors with the offending line. If the bound device disconnects or
becomes unavailable mid-run, `run` halts at the failing step and the
partial session is inspectable via `trailblaze session info --id <id>`
and `trailblaze report --id <id>` — the recording up to the failure
point is intact.

## Inspecting a session — generate an HTML report

The most reliable way to inspect a session in detail is the
self-contained HTML report:

```bash
trailblaze report --id <session-id>     # render a specific session's report
trailblaze report --current             # render the current session
```

`report` produces an HTML file (the primary artifact, gates the exit
code), plus optionally MP4 / GIF / WebP exports for video playback.
The HTML is self-contained and openable in any browser — useful for
attaching to bug reports, sharing with teammates, or surfacing inline
in CI builds.

The report shows step-by-step playback, the view hierarchy at each
step, screenshots, video, recorded tool calls, and the LLM transcript
(when an LLM was in the loop).

Use this when:
- A `run` failed and you want to see the recorded vs live state
- The user asks "why did that test break?"
- You want to share what happened with a teammate

## Inspecting a session in the desktop Trace Viewer

For interactive inspection (multiple sessions side-by-side, frame
scrubbing, view-hierarchy editing), launch the Trailblaze desktop
application:

```bash
trailblaze app                  # launch the desktop app (boots the daemon if needed)
trailblaze app --headless       # daemon-only, no GUI
trailblaze app --status         # is the daemon running?
trailblaze app --stop           # stop the running daemon
```

The default invocation (`trailblaze app`, no flags) opens the desktop
GUI. The daemon comes along for the ride — it's the background
service that drives devices and serves the GUI's session data, and
every CLI call boots it on demand anyway. Use `--headless` only when
running on a machine with no display (CI agent, remote shell) and you
want the daemon up so subsequent CLI calls reuse it.

The HTML report is still the right artifact for sharing or for
attaching to a bug report — it's self-contained and portable. The
desktop app is the right surface for live local inspection.

If a daemon is already running on the bound port, `trailblaze app`
reuses it (no second daemon spawns). If startup fails — port already
held by an unrelated process, no display available on the host, missing
JVM dependency — the error surfaces via the standard structured envelope
(`reason:` + `hint:`). Use `trailblaze app --status` to verify the daemon
state and `--stop` to free the port before retrying.

## Looking up past results

```bash
trailblaze results show C<case-id> --device <profile>
trailblaze results show C<case-id> --all-devices    # across the device matrix
```

Queries the persisted test-result index. **Case IDs need the `C`
prefix** (e.g. `C12345`), and `--device <profile>` or `--all-devices`
is required. Useful when investigating "when was the last successful
run of test X, and where are its artifacts?"

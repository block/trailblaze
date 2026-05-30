# Rung 1 — Driving a device

This is the base interaction loop. Everything Trailblaze can do is built
on top of these primitives. Load this reference when the task is to
explore an app, take a UI action, see what's on screen, or discover the
verbs available on a connected device.

## Contents

- [The `--device` flag is required on every device-driving call](#the---device-flag-is-required-on-every-device-driving-call)
- [Read the screen with `snapshot`](#read-the-screen-with-snapshot)
- [Act on elements with `tool`](#act-on-elements-with-tool)
- [Discover what tools are available with `toolbox`](#discover-what-tools-are-available-with-toolbox)
- [The basic loop](#the-basic-loop)
- [When an action fails](#when-an-action-fails)
- [Things that are normal but might look surprising](#things-that-are-normal-but-might-look-surprising)
- [Things that are real failures the skill can recover from](#things-that-are-real-failures-the-skill-can-recover-from)
- [Full CLI surface](#full-cli-surface)

The base [`SKILL.md`](../SKILL.md) covers the two routing keys (**device**
and **target**) that every command in this reference uses — read that
section first if you haven't.

## The `--device` flag is required on every device-driving call

Trailblaze is multi-tenant — the daemon supports many devices at once,
with no implicit "current device" on the CLI side. Every command that
acts on a device takes `-d <device>` (or `--device <device>`). Pass it
every time.

```bash
trailblaze device list                    # what's connected
trailblaze snapshot -d android/emulator-5554
```

If `trailblaze device list` returns multiple devices and the user
hasn't told you which one to use, ask. Don't guess.

(Sessions DO persist per-device on the daemon side once you start
acting — the daemon reuses an existing session for the same `(device,
target)` pair across CLI calls until the target changes or the
session is explicitly ended. From the CLI caller's perspective, you
still pass `-d` every call.)

## Read the screen with `snapshot`

```bash
trailblaze snapshot -d <device>
```

Returns the current view hierarchy as a structured text tree. The
output starts with platform-level context, then lists elements —
each with a `ref` (a short id), a role / element-type, and the
visible text or label. Indentation reflects parent-child nesting.

**On web**, the output includes a `### Page` header (URL + title)
plus a `### Screen` header (browser + viewport). Example for
example.com:

```
### Page
- Page URL: https://example.com/
- Page title: Example Domain
### Screen
Browser: Chromium (desktop)
Viewport: 1280x800
  [e1] heading "Example Domain"
  [e2] paragraph "This domain is for use in documentation examples..."
    [e3] link "Learn more"
```

**On mobile (Android / iOS)**, there's no `### Page` header — the
output starts directly with `### Screen` followed by `App: <package
or bundle id>` and then the indented element tree.

To click the link in the web example, you'd reference it as
`ref=e3`. If the device's current screen has nothing interactive on
it (e.g. immediately after connecting, before navigating), you may
see `(no interactive elements found)` — that's fine, drive the device
to a screen with content first.

Refs are short *content-hashed* slugs computed from the element's
class, label, and rough position — NOT positional indices, NOT
driver-dependent identifiers. The letter prefix comes from
`hash % 26`, so different elements naturally land on different
letters (`y778`, `p386`, `k42`) within the same screen. This is
deliberate: refs survive small UI changes (scroll offsets, animation
frames, layout shifts) and stay stable across captures of the same
element, but you can't predict them — always pull from a real
`snapshot`.

Examples seen in practice:
- **Web** (Playwright): `e<n>` — `e1`, `e2`, `e3`
- **Android / iOS**: content-hashed slugs as above — `y778`, `p386`, `k42`

The hierarchy reflects the platform's native semantic surface — the
accessibility tree on Android, native UI semantics on iOS, the DOM on
web.

**Refs are stable across snapshots of the same screen.** Take fresh
snapshots after actions that change the screen — refs won't carry
over to a new screen. Within a single screen, you can re-snapshot and
expect the same refs.

## Act on elements with `tool`

```bash
trailblaze tool -d <device> <tool-name> <arg-pairs> -s "<step>"
```

> `-o` / `--objective` are deprecated aliases — accepted by the CLI without warning, but new code should write `-s`.

This is the canonical action form. `<tool-name>` is one of the
primitives or custom tools available on the connected device's driver.
**Primitive names are platform-namespaced on web** (e.g. `web_click`,
`web_type`, `web_scroll`, `web_verify_*`) and unprefixed on mobile
(e.g. `tap`, `inputText`). Use `trailblaze toolbox` (below) to see
the real names available for your device.

`<arg-pairs>` are `key=value` pairs that vary by tool — `ref=e3` for
ref-based targeting, `text="..."` for typing, `url=...` for
navigation, etc. **To see a specific tool's args, pass `--help`**:

```bash
trailblaze tool web_click --help
# → web_click
#     Click on a web element identified by...
#     ref (STRING, optional): Element ID...
#     reasoning (STRING, optional): ...
```

`trailblaze toolbox --name web_click` produces the same output if
you prefer the explicit catalog form.

**`-s "<step>"` is required on every action.** It captures the
natural-language intent — what this step is *trying to do* — and gets
recorded into the trail YAML alongside the resolved selector. Without
it the trail is unreadable, undiagnosable, and self-heal won't work.
(The CLI doesn't enforce this by default — set
`trailblaze config require-steps true` to make a missing `-s` a hard
error in your environment.) Write step text the way you'd write a
test step description for a human reviewer:

```bash
trailblaze tool -d android/emulator-5554 tap ref=y778 -s "Tap the Sign in button"
trailblaze tool -d web/playwright-native web_click ref=e3 -s "Click the Sign in button"
```

Not `-s "tap"`. Not `-s ""`. Not no flag at all.

## Discover what tools are available with `toolbox`

```bash
trailblaze toolbox --device <platform> --target <target>
```

`toolbox` takes a `--device` (either `<platform>` like `web`, or
`<platform>/<id>` like `web/playwright-native`) plus a `--target
<target>` (e.g. `default`, or an app-specific target like `<your-app-target>`
if a trailmap for that app is installed). The target is the
discovery surface for app-specific custom tools — pass the target
matching the app you're driving to see its tools alongside the
built-in primitives. Use `--target default` for generic device
control without an app-specific vocabulary.

The result is every tool that platform-target combination supports —
built-in primitives plus any custom typed tools the user's team has
shipped via a trailmap. **Run this when you need a verb you don't
already know.**

The output is markdown-shaped for downstream LLM consumers. The first
line is a banner naming the resolved `(target, platform)` so an agent
reading the output knows the scope of what follows
(`# Trailblaze toolbox — <target> (<platform>)`; appends
`— tool: <name>` in `--name` mode). If the target's trailmap declares
a `system_prompt_file:` (curated UI conventions, gotchas, recommended
starting points), its contents are inlined under a `## System prompt`
section above the catalog — the same operational context Trailblaze's
in-session agent reads at session start, so a CLI-side agent gets
parity. The system prompt section is silently omitted when the target
has none configured and is skipped for single-tool drill-downs
(`--name <tool>`).

Two useful narrowing flags:

```bash
trailblaze toolbox --device web --target default --search tap   # find tools by keyword
trailblaze toolbox --device web --target default --name web_click  # detail on one tool
```

Tool names and signatures evolve; the `toolbox` output is the
canonical current surface.

## The basic loop

For any device-driven task:

1. `device list` — figure out which device, if not already specified
2. `snapshot -d <platform>/<id>` — see what's on screen, get refs
3. `toolbox --device <platform> --target <target>` — check available
   tools if you need something beyond `tap` / `web_click` / typing
4. `tool <name> -d <platform>/<id> <args> -s "<step>"` — act
5. `snapshot -d <platform>/<id>` again — see what changed
6. Repeat 3-5 until the goal is reached

Every tool call you make is recorded into a session. You don't need to
do anything explicit to start the session — invoking any device action
creates one. Trailblaze captures the screen state, the resolved
selector, and the step text alongside each tool call.

To save the session as a replayable `.trail.yaml`, see
[`save-and-replay.md`](save-and-replay.md).

## When an action fails

If a `tool` call returns a non-zero exit code or surfaces an error:

- The structured error message tells you what went wrong (`reason:`
  line) and often what to try next (`hint:` line). Read both before
  acting.
- The most common failure mode is a stale ref — the screen changed
  between your snapshot and your tool call. Re-snapshot and pick the
  ref off the fresh hierarchy.
- The second most common is a wrong tool name. Check `toolbox` for the
  real verbs available on this device's driver.

## Things that are normal but might look surprising

A few first-call messages aren't errors — don't treat them as such:

- **`Target app changed (X → Y) — creating new session.`** appears on
  first device interaction (and any time the target switches). It's
  Trailblaze bootstrapping a session for the current `(device,
  target)` pair. Not a problem.
- **`Installing Playwright (this may take up to 2 minutes)`** appears
  on the first web action of a fresh install. One-time setup; later
  web calls don't repeat it.

## Things that are real failures the skill can recover from

- **`Error: Device <id> is busy. Held by: TrailblazeCLI ...`** — a
  previous session is still holding the device. Recover with
  `trailblaze session stop -d <device>`, then retry your command. If
  you used the short form `-d ios` and it collided, try the long
  form `-d ios/<id>` from `device list` to be unambiguous.
- **Driver initialization timeouts** on first iOS connection — the
  driver subprocess takes longer than the default to come up. Wait
  ~30s and retry; if it persists, the user may need to set
  `MAESTRO_DRIVER_STARTUP_TIMEOUT` to a higher value. The variable is
  in **milliseconds** (Trailblaze's CI sets `300000` for the 5-minute
  ceiling); don't set it in seconds by mistake.

## Full CLI surface

```bash
trailblaze --help
```

This is the canonical source for the current command surface. Hidden
commands (like the built-in agent loop) are intentionally not in the
default `--help` listing — don't reach for them.

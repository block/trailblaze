# iOS Contacts Example — a canonical Trailblaze target trailmap for mobile

A worked end-to-end example of using Trailblaze to test a real mobile
application. Drives **Apple's built-in iOS Contacts app**
(`com.apple.MobileAddressBook`) through the host driver. The trailmap ships:

- **9 scripted tools** (in TypeScript) covering app launch, search, contact
  open/create/delete, multi-step edit-mode forms, contact-shape assertions,
  plus a composition example.
- **18 trails** under `trails/ios-contacts/` exercising those tools
  + the built-in iOS interaction toolsets, with `tags` for selective runs
  and one `skip:` example for opting out gracefully.
- **A target-scoped system prompt** that teaches the LLM when to reach for
  the scripted tools instead of inline-expanding to raw `tap` /
  `inputText` primitives.
- **CLI-first runtime** — every trail runs via `./trailblaze run …`, the
  same path an end user takes. There is no XCUITest / JUnit harness.

If you're building a target trailmap for your own mobile app, this is the
reference shape to copy.

## Why use Trailblaze for iOS instead of raw XCUITest?

You can drive iOS apps with raw XCUITest or Maestro YAML. Trailblaze adds
two things on top:

1. **Natural-language trails.** A test like "search Contacts for Albert
   Einstein and verify the contact has a phone number" stays readable to a
   PM, QA, or support engineer who doesn't write XCUITest. The LLM
   resolves it against the live view hierarchy each run.
2. **Per-target scripted tools.** When you DO want determinism for hot
   paths, you write a TypeScript tool once, advertise its task pattern to
   the LLM via the system prompt, and let trails compose it by name. The
   accessibility-label knowledge stays in one place; the trail just
   describes intent.

In practice you'll write **both**: NL trails for breadth, scripted tools
for the 5-10 workflows you run every build.

---

## Quick start (first green test in 60 seconds)

> Paths below are relative to the repo root (this README's canonical home).
> If your checkout nests the tree under a sub-directory, prefix every path
> with that directory name.

Prerequisites: an iOS Simulator running iOS 17+ with the system Contacts
app available. Boot one via Xcode or `xcrun simctl boot "iPhone 15"` and
make sure `xcrun simctl list devices booted` shows it before continuing.

```bash
# 1. From the repo root, point the daemon at this trailmap's config dir.
#    This is what registers the contacts_ios_* tools when the daemon boots.
export TRAILBLAZE_CONFIG_DIR=$PWD/examples/ios-contacts/trails/config

# 2. Stop any existing daemon (so it re-reads the config dir) and start
#    fresh. Source builds rebuild here automatically.
./trailblaze app --stop
./trailblaze app --headless & disown

# 3. Confirm the scripted tools registered. You should see 9 entries:
./trailblaze toolbox --device ios --target contacts --search contacts_

# 4. Run a single trail end-to-end.
./trailblaze run trails/ios-contacts/test-app-launches \
  --device ios/ios-host
```

The first run takes ~10s (simulator app launch + 1-2 LLM rounds). Expect
`Results: 1 passed, 0 failed`. If you got that, you're set up. Skip to
**Anatomy of a tool** to see how the wiring works.

---

## Anatomy of a scripted tool

Every scripted tool is a `(yaml, ts)` pair under
`trails/config/trailmaps/contacts/tools/`. Here's `contacts_ios_openContact`
broken into the parts that matter.

### The YAML manifest tells the LLM what the tool does

```yaml
# contacts_ios_openContact.yaml
script: ./contacts_ios_openContact.ts                  # ← TS file with the body
name: contacts_ios_openContact                         # ← the dispatchable name
description: |                                         # ← LLM-readable; KEEP DESCRIPTIVE
  Open a specific contact by name from the iOS
  Contacts list. Use this whenever the task is to
  open a contact, view a contact, navigate to
  someone's contact card, or look up a particular
  person …
_meta:
  trailblaze/supportedPlatforms: [ios]                 # ← driver-scope guardrail
  trailblaze/requiresContext: true                     # ← needs a live session
inputSchema:
  name:
    type: string
    description: Visible name of the contact to open.
    required: false                                    # ← honored at runtime; defaults from the TS body take over when omitted
```

### The TS body defines the behavior

```ts
// contacts_ios_openContact.ts
import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import { nonEmptyString, requireSessionContext } from "./contacts_ios_shared";

export interface OpenContactArgs {
  name?: string;
  expectedHeading?: string;
}

export async function contacts_ios_openContact(
  args: OpenContactArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,                  // ← typesafe; .tools.X(args) is the surface
): Promise<string> {
  requireSessionContext(ctx);                // ← shared helper, throws if no session
  const name = nonEmptyString(args?.name, "John Appleseed");
  const expectedHeading = nonEmptyString(args?.expectedHeading, name);

  await client.tools.contacts_ios_searchContacts({   // ← scripted-tool composition
    query: name,
    rowText: name,
    openFirstResult: true,
  });
  await client.tools.assertVisibleWithAccessibilityText({
    accessibilityText: expectedHeading,
  });

  return `Opened contact "${name}" and verified heading "${expectedHeading}".`;
}
```

Key conventions to copy:

1. **Accessibility labels live in `contacts_ios_shared.ts`** as a frozen
   `LABELS` constant — never inlined in tool bodies. If Apple renames
   "Done" to "Save" in iOS 18, you change one constant, not 9 files.
2. **Helpers are shared too** — `requireSessionContext`, `nonEmptyString`,
   `tryOrFalse`, `textIsVisible`, `ensureContactsRoot`. Every tool imports
   from `./contacts_ios_shared`.
3. **All dispatch goes through `client.tools.X(args)`** — typesafe;
   `tsc` flags typos and bad arg shapes at compile time. The exported
   `TrailblazeClient` type deliberately omits a generic `callTool`, so
   unknown tool names surface as compile errors, not runtime "tool not
   registered" failures.
4. **Return a short human-readable string.** The LLM sees this as the
   tool result; keep it informative ("Opened X" beats "ok").
5. **Throw on failure.** Don't swallow — the framework treats a thrown
   `Error` as the tool failing, which is what you want for the agent to
   see and react to.

---

## Recipe: add your own scripted tool

Three files, then restart the daemon. Concrete steps:

```bash
TOOL=contacts_ios_myNewTool
TRAILMAP=examples/ios-contacts/trails/config/trailmaps/contacts

# 1. Manifest — what the LLM reads.
cat > $TRAILMAP/tools/${TOOL}.yaml <<'YAML'
script: ./contacts_ios_myNewTool.ts
name: contacts_ios_myNewTool
description: |
  <one paragraph in plain English, including the task patterns the LLM
  should match against. NO "USE THIS TOOL" — describe what it DOES.>
_meta:
  trailblaze/supportedPlatforms: [ios]
  trailblaze/requiresContext: true
inputSchema:
  someArg:
    type: string
    description: <describe>
    required: false
YAML

# 2. Body — what it actually does.
cat > $TRAILMAP/tools/${TOOL}.ts <<'TS'
import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import { requireSessionContext } from "./contacts_ios_shared";

export interface MyNewToolArgs { someArg?: string; }

export async function contacts_ios_myNewTool(
  args: MyNewToolArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  // ... do work via client.tools.X(args) ...
  return "Did the thing.";
}
TS

# 3. Register in trailmap.yaml — list it under target.tools by bare name.
$EDITOR $TRAILMAP/trailmap.yaml

# 4. Restart the daemon so the new tool is discovered + bindings regenerate.
./trailblaze app --stop && ./trailblaze app --headless & disown

# 5. Confirm registration + open the .ts file in your IDE; `client.tools.`
#    autocomplete should now include your new tool.
./trailblaze toolbox --device ios --target contacts --search myNewTool
```

That's the whole loop. If autocomplete doesn't pick up the new tool, run
`./trailblaze check --workspace examples/ios-contacts`
manually — that's the per-trailmap `trailblaze-client.d.ts` regeneration step the daemon
fires on restart.

### Tool-description discipline (load-bearing)

The `description:` field in the YAML manifest is read by the LLM at
session start as the **only** way it learns what your tool does. Two
non-obvious rules:

- **Don't say "USE THIS TOOL FOR X".** The LLM picks tools by matching its
  understanding of the task against the description. Telling it to "use"
  the tool reduces the description to a single keyword and loses the
  surrounding context. Describe what the tool *does* and include the task
  patterns it matches — "Open a contact / view a contact card / navigate
  to someone's contact" — not "USE THIS WHEN OPENING A CONTACT".
- **Match real user phrasing.** If a trail says "open Albert Einstein's
  contact" but the tool description only mentions "navigate to a contact's
  detail screen", the LLM may not connect them. Include the synonyms
  (open, view, navigate to, look up) that real prompts will use.

You can sanity-check picking by running a trail in `--verbose` mode and
watching which tool the agent chose for each step.

---

## Recording vs natural-language: when to use which

Each trail directory has a `blaze.yaml` (the source of truth). If a
`ios.trail.yaml` is also present, the CLI replays it deterministically
instead of going through the LLM. **Use the decision tree:**

| Property of the trail                                       | NL (`blaze.yaml` only) | Recorded (`+ ios.trail.yaml`) |
|-------------------------------------------------------------|------------------------|-------------------------------|
| Should pass on any reasonable contacts-db state             | ✅ preferred           | risky — recording fixes one view tree |
| You want zero LLM cost on every run                         | ❌ ~$0.02-0.05/run     | ✅ free after recording      |
| The view tree changes per simulator (e.g. presence-of-cards)| ✅ stays robust        | ❌ will drift                |
| You need stable CI signal across hundreds of runs           | depends on flakiness   | ✅ if the selectors are stable|
| The trail composes scripted (`contacts_ios_*`) tools        | ✅ works fine          | ⚠ see "known issues" below   |

In this repo, **no trails carry recordings** today — recordings must come
from real device runs (per `feedback_no_hand_authored_recordings`), and
the example workflow is intentionally LLM-driven so an adopter can run
each trail from scratch.

**To record a trail**: run a passing `blaze.yaml` against a connected
simulator; the CLI auto-saves a fresh `<device>.trail.yaml` next to the
source on success.

---

## Filtering trails with `tags`

Every trail's `config:` block carries a `tags:` list. `--tags` filters by them.

```bash
# Smoke pass (fast, deterministic, must-pass on every build):
./trailblaze run trails/ios-contacts --device ios --tags smoke

# Anything in the search or crud suites:
./trailblaze run trails/ios-contacts --device ios --tags search,crud

# Long-tail nightly:
./trailblaze run trails/ios-contacts --device ios --tags slow
```

Conventions used in this example:

| Tag           | Meaning |
|---------------|---------|
| `smoke`       | Fast, deterministic, must-pass on every run. |
| `search`      | Drives the pull-down search field. |
| `contact`     | Asserts shape of a single contact's detail screen. |
| `crud`        | Create / edit / delete flows that mutate the contacts db. |
| `nav`         | In-app navigation primitives (back-stack, tab switching). |
| `slow`        | Multi-step compositions; skip during local iteration. |
| `flaky`       | Known to be timing-sensitive; usually paired with a `skip:` reason. |

### Skipping a trail with a written reason

A trail's `config:` block can carry `skip: "reason..."` to opt out of every
run until the reason is removed. Better than commenting trails out or
maintaining a separate exclusion list — the reason is committed alongside
the trail and shows up in `--verbose` output.

```yaml
- config:
    title: "Contacts (iOS): Autocomplete suggestions visible while typing"
    tags: [search, flaky]
    skip: "Suggestion popup races simulator boot — remove `skip:` once the inline-suggestions probe lands."
- prompts:
    - step: …
```

To run a skipped trail anyway, delete its `skip:` line (or set it to an
empty string). The CLI treats blank values as "not skipped".

---

## Pattern catalog — when to look at which trail

A few trails are specifically chosen as teaching examples. Read these
when you're authoring new tools or trails for your own target:

| Pattern | Trail | What it shows |
|---------|-------|---------------|
| Simple NL → built-in primitives | `test-app-launches` | Smallest possible trail; agent uses `launchApp` + `assertVisible` directly. |
| NL → scripted tool (LLM picks it) | `test-search-by-first-name` | System prompt nudges the agent toward `searchContacts` without naming it. |
| Direct scripted-tool dispatch | `test-custom-open-contact` | Trail prompts name the tool explicitly + pass typed args. Maximum determinism short of a recording. |
| Composition (tool calls tool) | `test-search-multi-contact` | Exercises `contacts_ios_searchAndVerify`, which internally calls two other scripted tools. |
| Data-driven shape | `test-search-multi-contact` | Same workflow across multiple contacts — copy-paste the prompt block + change the data. |
| Conditional UI (keyboard up?) | `test-dismiss-keyboard` | Exercises `dismissKeyboardIfPresent`, which no-ops cleanly when no keyboard is shown. |
| Branch coverage on a feature flag | `test-verify-contact-fields` + `test-verify-contact-no-extras` | Pair covers both branches of `verifyContactStructure`'s `requireFields` arg. |
| Multi-step CRUD | `test-create-then-delete` | End-to-end create + verify + delete as a single trail. |
| Graceful skip | `test-search-autocomplete` | Shows the `skip: "reason..."` config field. |

---

## IDE autocomplete on `args` / `ctx` / `client`

Trailmap tools are TypeScript only — the bundler rejects `.js` / `.mjs` / `.cjs`
sources so authors can't fall back to a subprocess runtime that would lose
the typesafe contract.

```bash
./trailblaze check --workspace examples/ios-contacts
```

That writes two artifacts (both gitignored as derived output):

- **Workspace SDK** at `trails/.trailblaze/sdk/` — the `@trailblaze/scripting`
  source the per-trailmap `tsconfig.json` resolves through path mapping. Ships
  curated runtime globals (`URL`, `fetch`, `AbortController`, `console`).
- **Per-trailmap `trailblaze-client.d.ts`** at `<trailmap>/tools/trailblaze-client.d.ts` —
  exhaustive bindings for every tool the runtime registry knows about
  (framework built-ins + trailmap-local scripted tools + Kotlin tools surfaced
  through `tool_sets:` declared in `trailmap.yaml`).

Open any `.ts` file in `tools/` and hover `ctx` / `client` / `args` to
confirm the types resolve. Mistype a tool name or pass the wrong arg shape
and `tsc` flags it at compile time.

To run the bundled `tsc` against your trailmap:

```bash
./trailblaze check contacts
```

---

## Runtime: QuickJS (in-process)

`.ts` files route through the daemon's QuickJS in-process runtime. No
subprocess fork, no Node APIs available beyond the curated globals the SDK
ships. Sub-millisecond dispatch; every tool call goes through
`client.tools.X(args)`.

---

## Testing your tools

Per-trailmap unit tests are part of `./trailblaze check` — its third phase runs `bun test`
across every trailmap's `.test.ts` files after materialize + tsc. From inside this example
workspace (`examples/ios-contacts/`), run `./trailblaze check contacts` to validate the
trailmap end-to-end; the test step shells out to `bun test` against the mock client + mock
context from `@trailblaze/scripting/testing`, so tests run without a daemon or a
simulator. Requires `bun` on PATH (install from https://bun.sh). All paths below are
relative to this README's directory. See the existing samples for patterns:

- `trails/config/trailmaps/contacts/tools/contacts_ios_searchContacts.test.ts` — single-tool
  sequence; asserts the order of `client.tools.*` dispatches + the args of each call,
  exercises the "No Results" pre-flight conditional branch with the default mock, and
  demonstrates `client.stub(name, { errorMessage })` as a fault-injection lever for a
  chosen dispatch.
- `trails/config/trailmaps/contacts/tools/contacts_ios_searchAndVerify.test.ts` — composition
  pattern; asserts the tool delegates to `contacts_ios_searchContacts` then
  `contacts_ios_verifyContactStructure` with the right args, without unrolling the
  sub-tools themselves.
- `trails/config/trailmaps/contacts/tools/contacts_ios_verifyContactStructure.test.ts` —
  exercises the per-field probe loop on the happy path and documents `client.stub`'s
  tool-wide fault-injection scope (the stubbed assertion fires on the up-front name
  probe, short-circuiting the `requireFields` loop — sequence-aware stubbing would be
  needed to drive the per-field branch).

These unit tests cover tool logic without a simulator; pair them with the
natural-language trails in `trails/ios-contacts/` for real end-to-end coverage. Both
feed into the same CI pipeline.

---

## Troubleshooting

**Daemon won't start** — check `./trailblaze app --status`, then look at
`~/.trailblaze/daemon.log` for the last few hundred lines. The
`daemon-<pid>.pid` file under `~/.trailblaze/` is the canonical location;
stale entries from previous crashes are safe to delete.

**Tool not registering / "tool not found at dispatch"** —

1. Confirm the file is named exactly `<toolName>.ts` + `<toolName>.yaml`
   under `tools/`. The bundler discovers by filename, not by manifest.
2. Confirm the bare tool name is listed under `target.tools:` in
   `trailmap.yaml`. Names omitted from that list are loaded by the bundler but
   not advertised to the agent for this target.
3. Restart the daemon after editing anything under
   `trails/config/trailmaps/<trailmap>/`. Trailmaps are discovered at boot.

**IDE shows red squiggles on `client.tools.X(args)`** — run
`./trailblaze check --workspace <path-to-your-config>` to regenerate the per-trailmap
`trailblaze-client.d.ts`. If that fails, check `./trailblaze app --status` — the
daemon writes the SDK + `trailblaze-client.d.ts` on every restart.

**"Daemon unreachable after 30 consecutive poll failures"** — known CLI
flake on long agent rounds. See **Known issues** below.

**Tool calls that worked live now fail on replay** — `contacts_ios_*`
scripted tools currently don't dispatch from an `ios.trail.yaml` recording.
See **Known issues** below.

---

## Known issues

These are tracked framework gaps. The example works around them today; the
canonical shape will improve when these land.

- **Scripted-tool replay-dispatch gap.** When an `ios.trail.yaml` recording
  captures a call to `contacts_ios_*`, the iOS agent rejects it at replay
  as `OtherTrailblazeTool` (the dispatcher only handles the core
  interaction primitives). Until fixed, only trails that exercise pure
  built-in tools get useful recordings. That's why all trails here stay
  NL-only.
- **CLI poll-timeout on long LLM rounds.** `./trailblaze run …` can
  return `FAILED: Daemon unreachable after 30 consecutive poll failures`
  while the daemon is still healthy and the session is making progress.
  The session artifacts under `logs/<session>/` are still written; you
  can replay or inspect them after. Workaround: re-run; not deterministic.
---

## Layout reference

```
examples/ios-contacts/
├── README.md                                 ← you are here
├── build.gradle.kts                          ← only runs `trailblaze.bundle`
└── trails/config/
    ├── trailblaze.yaml                       ← workspace anchor
    └── trailmaps/contacts/
        ├── trailmap.yaml                         ← target manifest
        ├── contacts-ios-system-prompt.md     ← LLM tool-selection guidance
        └── tools/                            ← scripted tool sources
            ├── tsconfig.json                 ← extends workspace base
            ├── contacts_ios_shared.ts        ← helpers reused by every tool
            └── contacts_ios_*.{ts,yaml}      ← one (ts,yaml) pair per tool

trails/ios-contacts/
├── test-app-launches/                        ← cold-start smoke
├── test-search-*/                            ← search flows (NL + scripted)
├── test-open-*/                              ← open-by-name trailheads
├── test-create-*/                            ← contact creation flows
├── test-verify-*/                            ← contact-shape assertions
├── test-edit-*/                              ← edit-mode multi-step flows
├── test-back-navigation/                     ← in-app nav
├── test-dismiss-keyboard/                    ← conditional UI handling
└── test-custom-*/                            ← typed scripted-tool composition
```

## CI

CI runs the same commands a developer does — there's no separate harness.
The benchmark pipeline scripts set up the daemon, fan out via
`./trailblaze run`, and summarize results. Look at those if you're
wiring a similar trailmap into your own CI.

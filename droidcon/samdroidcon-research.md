# Trailblaze repo research digests (for the Droidcon talk)

Condensed from research agents reading github.com/block/trailblaze (cloned 2026-07-11).
Source of truth = repo code; devlogs are historical. Companion to `samdroidcon-notes.md`.

## Scripted tools / TypeScript story

**Terminology to use on stage:** *scripted tool* (TS-authored custom tool), *trailmap*
(directory grouping a target + tools/prompt/trails, distributed as an npm package), *target*
(app under test), *trailhead / waypoint / shortcut / trail*, *daemon* (long-lived local
process holding the device session), `@trailblaze/scripting` (the one TS SDK).

**Authoring today:** `.ts`-only, no sidecar YAML. One file = one tool:
`export const my_tool = trailblaze.tool<Args>(spec, async (input, ctx) => {...})`.
The framework derives everything from source: export name → tool id, TSDoc → LLM-facing
description, `<I,O>` type params → input/output JSON Schema (per-field TSDoc → field
descriptions). `ctx.tools.<name>(args)` is a typed proxy over framework primitives + sibling
tools — wrong name/args = compile error, and there's deliberately no generic `callTool` to
bypass type-checking. Real example: `examples/wikipedia/.../tools/wikipedia_web_openArticle.ts`.

**Runtime:** two modes.
- Default: **in-process QuickJS** (quickjs-kt binding, QuickJS-NG) embedded in the daemon —
  sub-ms dispatch, curated globals only (fetch/URL/console, no fs). Same engine code compiles
  for host JVM **and** Android ART, so on-device runs the identical code.
- Opt-in: **Bun subprocess** (host-only) for tools needing Node APIs; speaks MCP over stdio.
  Bun is the *sole* supported JS runtime (Node dropped 2026-05; startup speed + Anthropic
  acquired Bun Dec 2025). MCP is no longer author-facing — just the wire protocol at that boundary.
- Scripted tools are **orchestrators**: TS composes; taps/launches execute in Kotlin drivers.

**Codegen philosophy: "Kotlin canonical, TypeScript derived."** Every typed surface crossing
the boundary is generated from Kotlin. Per-trailmap `trailblaze-client.d.ts` gives each
trailmap autocompletion for exactly its reachable tools (generated, gitignored — coupled to
installed binary version). Playwright tools are codegen'd shims from the Java JAR (one-line
allowlist add). A Kotlin/Wasm `.d.ts` spike was tried and rejected.

**Validation:** `trailblaze check` = binding regen + tsc + `bun test`. Sibling `.test.ts`
unit tests with `createMockClient()`. (This is the "YAML → TS → compile" safeguard Sam
described; the analyzer extracts JSON Schema from TS via a bun subprocess.)

**Distribution / registration inversion:**
- `install.sh` ships a JAR + launcher into `~/.trailblaze/bin` (auto-installs Bun).
- Trailmaps are **npm packages** — no custom registry; CLI walks `node_modules/` for
  `trailmap.yaml` at daemon init. Deps can be `file:`, `github:`, or semver.
- Old world: all tools Kotlin, registered at compile time (fork + rebuild to extend).
  New world: trailmap loader walks `tools/` on the filesystem at load time. No Kotlin
  compiler in the loop — the #1 motivator was binary-release consumers who can't contribute Kotlin.
- On-device: TS tools pre-compiled to QuickJS bundles as test-APK assets via a published
  Gradle plugin; slim SDK profile dropped Zod/Ajv/MCP for a >10× bundle-size reduction
  (~97% of the full bundle was validation/MCP machinery).

**Why TS over JS/Kotlin (recorded rationale):** Kotlin stays the framework language
(ART-native on device); tools needed no-rebuild iteration accessible to test engineers,
agents, and external binary consumers. Plain JS rejected — the types ARE the schema and
the docs; bundler hard-errors on non-`.ts` files. TS chosen as type-safe + the language
LLMs edit best.

**Fun implementation details (possible talk color):**
- QuickJS-NG isn't thread-safe; cross-thread re-entry segfaults the JVM — one engine, one
  owning thread, reentrancy tripwires.
- quickjs-kt async-function JNI bug → all host bindings are sync + runBlocking
  (public tracker: block/trailblaze#194).

## Unified trail format (current state in code)

**Naming:** it's called the **"unified"** format (not "v3"); file on disk is `trail.yaml`;
models in `trailblaze-models/.../yaml/unified/`. Migration status matters for honesty on
stage: the format is real and shipping, but most checked-in trails are still legacy v1
(one `*.trail.yaml` per platform + sibling `blaze.yaml`); the committed unified examples are
single-platform so far — the canonical multi-platform shape lives in the spec + code.

**Shape:** top-level `config` / `trailhead` ("step 0") / `trail` (list of steps). Each step is
`- step:` (direction) or `- verify:` (verification) — **natural language is required** ("NL is
forced"). Per-device recordings nest under `recording:`, keyed by **classifier**
(`android`, `android-phone`, `ios-iphone`, `web`, …) with **closest-wins resolution** via
**classifier lineage** (`android-phone` → `android`). Extras: `recordable: false` (always
LLM-handled), `maxRetries`, explicit empty `classifier: []` = intentional no-op.

Canonical multi-platform example (from spec devlog 2026-05-22, revisions at bottom):

```yaml
trailhead:
  step: Sign in as the QE sender
  recording:
    android-phone: { myapp_signInViaUI: { email: "{{memory.account_email}}" } }
    ios-iphone:    { myapp_ios_signInViaUI: { email: "{{memory.account_email}}" } }
    web:           { myapp_web_signIn: { email: "{{memory.account_email}}" } }
trail:
  - step: Add a latte to the cart and open checkout
    recording:
      android:        # broad family
        - tapOn: { text: Latte }
      android-phone:  # most-specific wins at run time
        - tapOn: { id: menu_latte }
  - step: Confirm the order summary shows exactly one item   # NL-only (agent solves live)
  - verify: The cart shows 2 items
```

Real committed files: `trails/clock/set-alarm-730am/trail.yaml` (config + trailhead +
recordings + assertions), `trails/ios-contacts/test-back-navigation/trail.yaml`.
Trailhead recording = one tool per classifier (map); step recording = ordered list.

**Trailmap manifest (`trailmap.yaml`):** `id`, `dependencies: [trailblaze]`, `target:`
(display_name, platforms.{android,ios,web,compose} with app_ids / tool_sets / tools /
excluded_tools / drivers). Target trailmap (runnable app) vs **library trailmap**
(cross-target tooling, no waypoints/trailheads). Tools/waypoints/shortcuts/trailheads are
discovered by directory walk with filename-suffix conventions (`*.waypoint.yaml`,
`*.shortcut.yaml`, `*.trailhead.yaml`, `*.tool.yaml`). `trailblaze check` = "javac for
trailmaps": validates all references, emits materialized target configs into `dist/targets/`.

**Strict validation — two gates:**
1. *Parse gate* — strict kaml `strictMode`: unknown/stale YAML keys throw instead of
   silently dropping (guard test proves it).
2. *Type gate* — `TrailTscValidator`: every recorded tool call becomes one line of generated
   TypeScript (`client.tools.tapOnElementWithText({...})`) compiled with `tsc --noEmit`
   against the trailmap's generated `trailblaze-client.d.ts`; diagnostics are remapped back
   to `trail.yaml · step N [classifier]`. Runs on every `trailblaze check`; default-fail with
   per-target `trail_validation.exempt: "<reason>"` escape hatch. Validates **every**
   classifier slot, not just closest-wins.

**Targets/drivers model:** platforms ANDROID/IOS/WEB/DESKTOP; classifiers = platform[-category]
(`android-phone`, `ios-ipad`, arbitrary depth allowed); drivers enum incl.
`ANDROID_ONDEVICE_ACCESSIBILITY` (Android default), `ANDROID_ONDEVICE_INSTRUMENTATION`,
`IOS_HOST` (iOS default), `IOS_AXE`, `PLAYWRIGHT_NATIVE/ELECTRON`, `REVYL_ANDROID/IOS` (cloud
devices), `COMPOSE`. Per-trail driver pin via `config.devices: {android: ...}`.

## Maestro → accessibility driver (~3x claim: verified, actually 4-5x at driver level)

**Headline (from `AccessibilityTrailRunner` kdoc):** per-action overhead dropped from
**~500–700ms (Maestro's "screenshot sandwich") to ~100–150ms** (event-based settle +
single-pass tree capture) = **~4-5x trail-playback speedup**. Sam's "nearly 3x" is a
conservative end-to-end number — safe on stage.

**Why Maestro hurt:** reflection on private Orchestra methods for element matching (broke on
upgrades); a maintained Orchestra fork for on-device; lossy tree normalization (Raw →
Maestro TreeNode → ViewHierarchyTreeNode dropped ~30 Android accessibility props → brittle
index-based selectors); implicit settle Trailblaze couldn't observe.

**New architecture:** on-device Android **AccessibilityService** (`trailblaze-accessibility-app`
shell app): gestures via `dispatchGesture`, tree read natively in a single pass from
`AccessibilityNodeInfo` (merges dialogs/popups), **event-based settle** (debounced quiet
window on accessibility events) instead of screenshot poll-and-diff. Host ↔ device over
Ktor HTTP RPC through `adb forward` (port 52527). Async logging off the hot path.

**Supporting wins:** `TrailblazeNode` model — driver-specific detail (no lowest-common-
denominator), matchable-vs-display-only props, 11 cascading selector strategies;
Playwright-quality hierarchical text snapshots with native class names; stable content-hashed
element refs replacing DFS node-ids (killed a fragile host↔device re-resolution hack);
WebP screenshots = measured ~4x smaller payloads.

**Honest status table (architecture.md):** Android accessibility = current/preferred, Android
Maestro = deprecated-legacy (with `migrate-trail` migration tooling); **iOS still on Maestro**
(reactive settle observer designed, blocked on an OSS on-device iOS runner); web was always
Playwright (its ARIA snapshot was the explicit "gold standard" Android/iOS rebuilt toward).

**Caution:** the 135x–13954x numbers in `docs/benchmarks/playwright-native-benchmarks.md` are
AI-mode vs recorded-replay (LLM vs deterministic) — NOT Maestro-vs-accessibility. Don't mix.

## Waypoints — current status (⚠️ current deck overstates this)

**Bottom line:** waypoints are an **active prototype**, README says so verbatim ("not yet
stable"). What's real: assertable named locations + authoring/visualization tooling.
What's NOT built: runtime **BFS pathfinding / `goTo` / planner-executor** — that's still
vision. The current deck's slides claiming "deterministic pathfinding (BFS), no LLM" and a
`matchWaypoint` primitive are **inaccurate** — `matchWaypoint` never shipped under that name
and `.nav.yaml` was never built.

**Current terminology:** *waypoint* (node, `*.waypoint.yaml`), **shortcut** (edge — renamed
from "edge", `*.shortcut.yaml`), *trailhead* (bootstrap: any state → known waypoint, now a
`trailhead:` field on a TS launch tool), *navigation graph* (derived at read-time by
`WaypointGraphBuilder`, never a stored file).

**Waypoint schema v2 today:** classifier-keyed (`android:` → `required:`/`forbidden:` lists,
each with `description` + full `selector: { androidAccessibility: {...} }`), URL-style ids
like `calendar/android/day_view`. Real corpus: calendar trailmap has 100 waypoints /
64 shortcuts, contacts 100/81 — but almost entirely single-platform Android, and they're
Google fixture apps, not Block apps.

**Shipped:** `WaypointMatcher` (structural match against live/recorded state),
`assertWaypoint` tool (replaced the `postcondition:` field), full CLI
(`trailblaze waypoint {list, locate, validate, propose, tune, segment, shortcut, graph, ...}`
— `segment` mines waypoint transitions from session logs = the retroactive-discovery idea),
graph viewer (React Flow HTML export + native Compose "subway" view), single-shortcut
empirical verify.

**Not shipped:** `goTo`/pathfinding, express routes, multi-platform shortcut bodies,
semantic recording, workspace-owned waypoint resolution for assertWaypoint.

**Talk framing:** "the app map is real — author, assert, visualize it today; autonomous
navigation over that map is the near-term vision." This is exactly Sam's 'philosophy of
where we're going' angle — waypoints belong in the *future/vision* act, not as shipped fact.

## Sam's previous talks (read from SpeakerDeck transcripts)

**2016 · Droidcon NYC — "Espresso: A Screenshot is Worth 1,000 Words"** (102 slides, Capital One era).
Arc: Espresso in 2 min → Screenshots (Why/How/When/Cost) → Maintainable Test Architecture
(robot pattern) → tips. Verbatim gold:
- Robot slides literally labeled **"What"** and **"How"**: "Test = What & How" (problem) →
  "Robot = How, Test = What" (fix); "View Changed" X's every test → with robot, one X.
- The one-liner: `new LoginRobot().assertLoginDisabled().username("sam").login().assertHomeScreenShown()`
  — every robot method takes a screenshot ("Screenshots for Free").
- Failure-diagnosis pair: "**Oops, Your Test Failed**" (wall of NoMatchingViewException
  hierarchy text) → "**Ahh, I See Why**" (same + screenshot).
- "NO ONE sees the value of your tests… yet" / "**To them it's just console output**" /
  "Let EVERYONE see what's being tested."
- **Mona Lisa exercise**: describe the Mona Lisa to someone blind — UIs are visual.
- "ALWAYS on FAILURE"; "NEVER Thread.sleep()"; closing slide was just "**1,000 Words**".

**2025 · Droidcon NYC — "AI Driven Mobile Testing: Blazing the Trail at Block"**
(63 slides, **with Brian Gardner** — not Plummer). Arc: origin timeline (DragonCrawl Apr
2024 → ai-test-agent Nov 2024 → 🧭 Trailblaze Mar 2025) → agent loop (do/while, GPT-4o-mini,
Tap/InputText/Erase/Scroll + Halt) → productionizing (on-device Maestro driver for the Test
Farm) → challenges (no tool calls → ToolChoice required; X,Y doesn't scale for recordings;
Set-of-Mark) → architecture layers → future. **Future-work slide = 2026 checklist:**
"iOS and Web support?", "**Reuse the same prompts across platforms**" (→ unified format!),
"Custom App Interactions: Draw Signature, Connect Virtual Card Reader" (→ custom tools/TS),
"MCP authoring improvements", "Show us your AI tools!". Screen state was already
"View Hierarchy • Screenshot". Close: "Incremental adoption is key."

**Callback hooks for 2026:**
1. What-vs-how side-by-side: the 2016 LoginRobot one-liner next to a natural-language trail.
2. "A screenshot was worth 1,000 words → now it's worth 1,000 **tokens**" — the LLM is the
   new audience for test reports (Oops/Ahh pair reborn as agent context).
3. "To them it's just console output" → standalone CDN reports readable by everyone AND agents.
4. 2025's future-work slide → tick items off live ("we said we'd reuse prompts across
   platforms — that became the unified trail format").
5. Timeline device: extend the 2025 dated-milestones opener back to 2016 and forward to waypoints.

## Devlog timeline — the story beats (condensed from 87 devlogs)

**Phases:** founding thesis (2025-10) → Kotlin/Maestro foundation (2026-01) → agent
architecture + Maestro decoupling (02–03) → refs/snapshots/config (03–04) → scripted-tools
saga (04–06) → robot pattern & trailmaps (04–05) → type discipline + philosophy (05) →
consolidation & hardening (06–07).

**The 8 biggest story beats (each with devlog receipts):**

1. **"LLM as compiler" — day one, never abandoned** (2025-10-01). NL = source, trail YAML =
   bytecode, device = runtime, replay needs no LLM. Bookend with beat 2.
2. **"Agent-authored, human-readable"** (2026-05-23) — the philosophy statement: agents are
   the primary *authoring* layer, humans the *comprehension/audit* layer. One-line proof:
   `assertWaypoint` became a *tool* replacing a YAML *field* because "the agent drives by
   selecting tools — capabilities must be tools, not fields" (2026-06-30).
3. **Platform-specific recordings → unified format, forced by real drift** (2026-01-29 →
   2026-05-22): per-platform NL drifted in production (credentials diverged, steps added to
   one file not others). Honest bonus: **trail YAML v2 (2026-03-06) was fully designed and
   never shipped** — reality overrode a finished design doc.
4. **Trailblaze = the robot pattern, generalized** (2026-04-26): tools=robot methods,
   waypoints=screens, shortcuts=transitions, trailmaps=the published container. "One shortcut
   body fixes thousands of recordings" = the robot-pattern maintenance economics, plus
   assertable state + cross-platform identity + derivable-from-observation.
5. **Maestro decoupling** (2026-01-01 "not a permanent coupling" → 2026-02-09 seven-phase
   plan → TrailblazeNode → stable refs). Killed private-API reflection, LCD hierarchies,
   DFS node-ids.
6. **The scripted-tools pendulum** — richest honest-lessons thread: Kotlin-only →
   TS/QuickJS vision (02-20) → bun-subprocess-MCP flip (04-20; inline `script:` deprecated
   the same day it merged) → back to QuickJS-default + subprocess-opt-in (06-17); two SDKs
   collapsed to one; on-device MCP bundle subsystem built then deleted. Landing insight:
   "a TrailblazeTool is just an RPC request" (04-22).
7. **Strict validation via a surprising trick** (07-01): type-check YAML by transpiling
   each recorded call to TS and running tsc — reuse the one type authority, no parallel
   schema. "Kotlin canonical, TypeScript derived" (05-22), CI fails on drift.
8. **Waypoints/nav graphs** (03-11 vision, 05-08 goTo/pathfinding vision) — see waypoint
   section above: map is real, navigation is the vision.

**Other reversal beats (honest-lessons pool):** flat tool naming (2026-01-14) fully unwound
into trailmap-scoped `X_localName` because `:` is illegal on the OpenAI/MCP wire (05-27);
"every app runs its own MCP server" (02-03) abandoned; Kotlin/Wasm .d.ts spike rejected
(06-17); force-directed graph viewer → subway view.

**Positioning gold** (2026-05-23-pack-ecosystem-vision.md): "developer-first AI testing."
The Maestro lesson — Maestro's founders tried pure-NL testing and retreated (QA wanted real
assertions); SaaS platforms force JS into a worse web IDE; Trailblaze is the deliberate
middle: agent authors NL → compiled human-editable YAML in *your repo*, real TS surface,
portable NL layer = exit strategy, trailmaps = "cross-platform Page Object Models for agents."

**Production bug color:** clipboard round-trip trail failed under per-tool replay because
setClipboard/pasteClipboard hit different executor instances → batched execution scope
(07-03). Vivid CI-bug story if needed.

## Agent loop, self-heal, memory, reports, desktop, MCP, CLI (final sweep)

**trail<> vs blaze<>:** "blaze once, trail forever" — blaze explores an objective and
generates a trail; trail replays it. Caveat: the shipped day-to-day verb is now **`step`**
(CLI + MCP), not `blaze`. Two agent impls: `TRAILBLAZE_RUNNER` (battle-tested loop, **the
default**) and `MULTI_AGENT_V3` (Mobile-Agent-v3-inspired: frontier model for vision + mini
model for planning, no fine-tuned models; Planning/Decision/Execution/Exception/Reflection/
Working-Memory nodes) — **opt-in via `--agent`, not the default**. AndroidWorld "70%+" is a
*target*, not a published result.

**A\* cost model (real, in `TrailGoalPlanner.kt`):** `RECORDING_COST = 1.0`,
`SELF_HEAL_COST = 5.0` — recordings always preferred. Four execution modes: DETERMINISTIC
(recordings only, 0 LLM calls), RECORDING_WITH_FALLBACK, HYBRID, AI_ONLY.

**Self-heal: OFF by default** (`SELF_HEAL_DEFAULT = false` — "keeps failures actionable;
recordings validated unless explicitly opted in"). When a recorded call fails, LLM
re-interprets *only that step* against current screen. Statuses `SucceededWithSelfHeal` /
`FailedWithSelfHeal`; reports classify runs into RECORDING_ONLY / SELF_HEAL / AI_ONLY etc. —
teams track **"% passed via self-heal" as the recording-staleness health signal**.

**Memory/parameterization:** `config.memory:` seeds `{{var}}` values; CLI `--memory K=V` and
`--secret K=V` (redacted, only key recorded). Recording **reverse-substitution**: literals in
recordings are swapped back to `{{key}}` using a per-tool-log memory snapshot (min 8 chars,
exact match) so recordings don't hardcode stale credentials.

**Reports:** interactive HTML report (per-step screenshots + hierarchy + LLM transcript),
storyboard grid (WebP via headless Playwright), GIF/WebP/MP4 exports, public CI-generated
report gallery. Session log server at `localhost:52525`. Chrome Trace Event format tracing
(opens in Perfetto). Video/logcat/network capture via ffmpeg (`--capture-*` flags).

**Desktop app:** Compose Multiplatform, macOS menu-bar app (replaced an old IntelliJ
plugin) — trail browse/edit/run/debug, device mirroring, embedded MCP server, dashboards.
One Homebrew package (`brew install block/tap/trailblaze`) = CLI + GUI, same version.

**MCP today:** singleton daemon on `localhost:52525`; CLI = short-lived HTTP; MCP clients =
STDIO→HTTP proxy (`trailblaze mcp`, Streamable HTTP). Consolidated **verb-first tools
mirroring the CLI 1:1**: `step`, `ask`, `trail`, `trailEdit`, `session`, `device`, `config`,
`toolbox`, plus observation/debugging tools. Device-claim registry with yield-unless-busy.
Known gap (honest): device state is daemon-global, not per-session — multi-terminal needs
`--device` each call.

**CLI day-to-day:** `run` (globs, `--tags`, `--all-devices`, `--use-recorded-steps`,
`--self-heal`), `step` (`--save` records a trail), `ask`, `verify` (CI assert, exit 0/1),
`snapshot`, `tool`, `toolbox`, `session`, `report`, `check` (trailmap compiler + tsc +
bun test), `mcp`, `app`, `device`, `show`, `waypoint {list,graph,propose,tune,shortcut,...}`.
No `record` verb — recording is `step --save` / `session save`.

**Extras:** trailheads = tools with `trailhead:{to}` blocks — the "known starting state"
primitive that makes replay reliable (framework asserts arrival). Showcase trails replayed
in CI per platform (`docs/showcase-trails.yml`): clock set-alarm-730am (Android),
ios-contacts create-then-delete, wikipedia shakespeare (web) — ready-made "real recorded
test, zero LLM, in CI" demo material. Revyl = cloud-device integration (the likely public
face of running Square hardware targets).

**⚠️ AndroidWorld benchmarks: Sam says the module has been DELETED from the repo (post-clone).
Do not reference it in the talk.** Original motivation (building a custom agent) faded — they
kept the simple agent and lean on Claude Code / Codex instead.

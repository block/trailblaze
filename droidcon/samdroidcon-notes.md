# Trailblaze talk — brainstorming notes

Working notes for the Droidcon 2026 talk (Jul 17, 40 min, W222 B).
Deck lives in `samdroidcon.md` — this file is scratch space so ideas don't taint the slides.

## ⏰ Overnight review — 2026-07-11 → 07-12 (skim this first)

Light, voice-preserving tighten/expand pass done while you slept. Deck is **55 slides**
(was 52). Everything below is revertable; each item says what changed and why. I kept your
structure, your headlines, and your speaker-note style — no rewrites of working slides.

### Changed (4 edits)
1. **`info:` metadata** — refrain aligned to "Blaze once, trail forever." (was the older
   "Explore once, replay forever"). Off-slide only; consistency nit.
2. **"The missing link"** (slide 4) — last two bullets tightened so "lose the link" pays off
   in the next line: *"The objective is the **what**. Most tools throw it away."* Was the
   cryptic "The test case is the what."
3. **"One login flow. Seven targets."** → split into a **two-slide build** (5 + 6):
   *"One login flow. / You write it once."* then *"Seven targets."* + the 7-grid +
   hundreds→thousands. Same content; the one→many multiplication now happens *physically*
   when you advance. No new imagery needed (grid unchanged). Your signature big-beat split.
   (Round 9: slide 5 is now **"One user journey."** — login demoted to spoken example;
   "projecting thousands" cut from slide 6, spoken-optional.)

### Added (2 slides)
4. **"The natural language drifted"** (slide 17, marked `ACT 2 · optional`) — two tiny YAML
   snippets showing the *same* step drift ("QE sender" vs "test sender") with nothing catching
   it. Makes "drift" concrete before the unified format fixes it. **To cut:** delete the slide
   block (--- to ---); the story survives without it. Renders verified.
5. **"Backup · How reliable is it?"** — a Q&A safety card *behind the backup divider* (never
   shown unless you navigate to it), holding your banked wording: "still getting to 100%,
   good and improving, parity across Android and iOS" — **no invented number**. Zero impact
   on the main flow; there so you stay calm if the reliability question comes.

### Production items CLOSED overnight (were on your plate — now done)
6. **QR code** — real, on the CTA slide: white-on-transparent SVG encoding
   github.com/block/trailblaze (`droidcon/public/trailblaze-qr.svg`, generated locally with
   slidev's bundled `uqr`). Render verified. **Scan it once from your phone tomorrow** —
   10-second sanity check.
7. **Square hardware imagery** — the 7-targets grid now shows REAL product shots for
   Terminal / Handheld / Register (`droidcon/public/square-*.png`, pulled from
   squareup.com/us/en/hardware — Block's own marketing assets, transparent PNGs).
   Consumer devices stay emoji on purpose: everyone knows an iPhone; nobody knows a
   Square Register. Render verified.
8. **Plan B for all three 📼 assets** — see the "🎬 Plan B" section below: the talk is
   **presentable tomorrow night with zero video production**. Videos are now optional
   polish, not a dependency.

### Deliberately NOT changed
- The spine, refrain bookends, unified-format YAML, "driving the device *is* constructing the
  trail," the recipe, CTA — all load-bearing, left alone.
- No new numbers anywhere (reliability has none; wall-clocks still pending from the chip).
- No agenda slide forced in — see the ready-to-paste option below.

### 🔪 Cut-priority list — if a dry run runs long, drop in THIS order
Most expendable first. Everything here is safe to cut with no dangling references unless noted.
1. **"The natural language drifted"** (17, optional) — built to be first out.
2. **"Tools return data, not just text"** (Act 4) — nice refinement, not load-bearing.
3. **"Not every tool is for the LLM"** (Act 4) — API-hygiene aside; fold one line into the prior slide.
4. **"Parameterize, don't hardcode"** (Act 2) — 60s beat; Q&A will cover credentials anyway.
5. ASSET C **second-map flash** (Calendar) — keep the primary contacts map, drop the "pattern, not a one-off" beat.
6. **"Reports with no strings attached"** (Act 3) — compress to one spoken line if truly desperate.
> Do NOT panic-cut: the thesis slide, both refrain slides, the unified YAML, the "driving the
> device" peak, the recipe, or the CTA. That's the skeleton — cutting it breaks the arc.

### 🎬 Plan B — the talk is DONE with zero videos (deadline-proof)
**STATUS 2026-07-12: SUPERSEDED — both asset chips delivered; all four 📼 slides now
embed REAL media (videos on 14, report walk on 28, terminal on 37, graph build on 44).
Keep this section as the emergency fallback if playback/rendering misbehaves on the
podium machine. Regeneration: asset-regeneration-playbook.sh + asset-scripts/ +
asset-recipe-regenerate.txt, all in/next to droidcon/public/.**
Each 📼 slide already works as a spoken beat; these fallbacks make each one *visual*
without any video production. If a quiet hour opens Jul 13–16, videos replace them 1:1.
Every command/number below is real and verified in-repo — nothing invented.

**ASSET A pt 1 (zero-LLM replay)** — paste-ready static slide:

````md
<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# Blazed in 6m41s. Replays with zero LLM calls.

```yaml
# blaze.yaml — the journey we wrote
- step: Enter first name "Casey" and last name "Trailblaze", then save the contact

# android.trail.yaml — the recording the agent EARNED by driving the device
#   (paste 3–4 real lines from trails/contacts/create-contact/android.trail.yaml)
```

<div class="pt-4 opacity-70">same folder, two files: the intent · its deterministic materialization</div>

<!-- 6m41s = the REAL photo-trail blaze (richest journey). Speak replay honestly:
"replay is the same steps with the LLM out of the loop." Add the parity trail's
exact replay wall-clock when the chip session reports it — until then, no number. -->
````

**ASSET A pt 2 (the report)** — no slide needed: launch the Trailblaze desktop app
(`trailblaze`), open tonight's session `create_contact_b66e486e`, and walk the real
report — per-step screenshots, hierarchy, logs. Local disk, zero network. Rehearse the
click-path once.

**ASSET B (agent blazing via CLI)** — paste-ready terminal-transcript slide, real
lines from the night the recordings were earned (2026-07-11, paths tidied):

````md
<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 2</div>

# The night these recordings were earned

```bash
$ trailblaze run --device android trails/contacts/create-contact/blaze.yaml
  # agent drives every step on the device …
  ✓ completed successfully                # session: create_contact_b66e486e

$ trailblaze session save --title "Contacts: create a contact"
  Trail saved → trails/contacts/create-contact/android.trail.yaml
```

<div class="pt-4 opacity-70">the recording lands NEXT TO the natural language that earned it</div>
````

**ASSET C / LIVE TRAIL MAP BROWSER** — the viewer is served by the local daemon, zero
network. **VERIFIED LIVE 2026-07-13 from this worktree.** Launch (dedicated port so it
does NOT clobber the shared :52525 daemon other concurrent sessions use):

```
TRAILBLAZE_PORT=52620 TRAILBLAZE_CONFIG_DIR="$PWD/trails/config" trailblaze app --headless
# then open  http://localhost:52620/waypoints/graph   (stop later: trailblaze --stop)
```

THE MONEY SHOT (drive it live, ~30s): Target → **contacts** (header snaps to **100
SCREENS · 81 SHORTCUTS** — the deck's numbers, live) → **SUBWAY** tab → FOCAL =
`contacts/ios/list` → Fit View. You get the whole contacts app map radiating from the
list screen, real screenshots as nodes. The panel says it out loud: "every waypoint
reachable in ≤ 3 forward hops — the SAME adjacency we hand the agent in its prompt."
MAP tab = filmstrip of every screen; SCREENS = grid; REPORT = sessions.
HEADS-UP on stage: the workspace also shows **calendar (100)** and **clock (3)** target
chips — filter to contacts BEFORE presenting, or be ready to call calendar a
Google-Calendar-like fixture app (see the ASSET-C production note below). Route + port
verified in `WaypointGraphEndpoint.kt` / `TrailblazePortManager.kt`.
Fallback if the daemon won't start live: the captured asset-c-graph-*.png stills, or
speak it — "100 named places, 81 shortcuts, committed in the repo today."

**ANDROID CONTACTS MAP — 2026-07-14, VERIFIED LIVE, FULL COVERAGE.** Contacts is no
longer iOS-only: **37 Android waypoints + 37 mined example screenshots + 38 shortcuts**
sit beside the 100/81 iOS set (contacts chip reads **137**). 37 IS full coverage —
AOSP Contacts is a much smaller app than iOS Contacts; the count includes every
settings screen, editor state, dialog, search state, plus the five system-integration
states (share sheet, link picker, ringtone picker, photo dialog, multi-select).
Android money shot: Target **contacts** → Platform **android** (header: **37 SCREENS ·
38 SHORTCUTS**) → SUBWAY → FOCAL = `contacts/android/list-populated` → Fit View.

**SELF-MAPPING MECHANICS (backs the new "The product mapped itself" slide).** The
whole map is exhaust from Trailblaze's own tools — no screen was hand-screenshotted,
no tree hand-transcribed:
1. `trailblaze snapshot` reads the live UI tree; waypoint YAML is authored from the
   anchors the tree itself exposes (~3 anchored regex selectors: title, resource-id,
   one landmark; full-string match semantics — `^Sharing` fails, `^Sharing \d+ files?$`
   matches).
2. A recorded micro-trail per state replays deterministically to that screen
   (launchMode: RESUME so app data survives).
3. `trailblaze verify "<state description>"` on the reached screen writes a session
   log carrying the REAL node tree + REAL screenshot in one shot.
4. `trailblaze waypoint capture-example --id <waypoint> --session <session>` mines
   that log into the committed `.example.json` + `.webp` pair and self-validates it
   against the selectors (37/37 MATCH).
Stage line: the map isn't documentation OF the tool, it's EXHAUST FROM the tool —
the same primitives the agent navigates with (snapshot / verify / assertWaypoint)
are what built the map.
Regen/extend on ANY machine: `droidcon/android-map-sources/` — README with the full
pipeline + gotchas, seed-contacts.sh (the 8-contact device DB every screenshot
assumes), and all 34 replayable micro-trails.
TRAILHEADS (added 2026-07-14): 3 entry points render as dashed edges from a virtual
origin — `contacts_launch` (CROSS-PLATFORM: one trailhead, `toByPlatform` fans it to
android list-populated AND ios list), ACTION_INSERT→editor, ACTION_VIEW→contact card.
Stage line: shortcuts move you WITHIN the map; trailheads get you INTO it from
anywhere — launch intents are edges too. Bonus beat: ONE trailhead spans BOTH
platforms — same entry tool, per-platform landing.
DEEP LINKS (added 2026-07-14): the URL hash now encodes target/platform/view (+
subway focal/depth/dest) — bookmarkable demo states, no live clicking to set up:
`#target=contacts&platform=android` (just contacts) and
`#target=contacts&view=subway&focal=contacts%2Fandroid%2Flist-populated&depth=3`.
DEMO REQUIREMENT: `toByPlatform` + deep links exist only on this branch — run the
viewer FROM SOURCE: `TRAILBLAZE_PORT=<port> TRAILBLAZE_CONFIG_DIR=<repo>/trails/config
./gradlew :trailblaze-desktop:run --args="app --foreground --headless"`.
`--foreground` is load-bearing: without it the CLI execs the INSTALLED ~/.trailblaze
jar as a detached daemon (old code, old data) and exits. The graph's default root
now prefers live workspace resolution (TRAILBLAZE_CONFIG_DIR env, then cwd walk-up)
over the persisted trails-dir setting — so with that env var set, PLAIN URLs serve
full data with screenshots; no `?root=` needed (it remains a per-request override).
Blank screenshots on every node = the root didn't resolve (classpath-bundled YAMLs
carry no example images). Demo URL shape:
`/waypoints/graph#target=contacts&platform=android`.
Old binaries skip the toByPlatform sidecar with a warning
(kaml strictMode=false + per-file error isolation) — degradation, not breakage.
Gotcha if asked why they're YAML sidecars AND TS blocks: the viewer only reads
`trailheads/*.trailhead.yaml`; TS-inline `trailhead:` reaches the runner manifest but
not the map render (upstream #202 gap — candidate report, don't file unprompted).
🎬 VIEWER DEMO SCRIPT — 90 seconds, consolidated (2026-07-15; beats verified live):
1. Open `/waypoints/graph#target=contacts&platform=android` (bookmark it). Land on
   MAP: 37 waypoints, every card a real screenshot, dark Trail Runner look.
2. Point at the floating 🥾 TRAILHEADS panel: "every run starts at one of these —
   no preconditions, any device state."
3. Click the contacts_launch → list-populated chip: map dims, entry trail lights
   green and FLOWS, camera fits, and a 🥾 walks the route. Line: "the map doesn't
   just show the way in — watch the boot make the trip." (Panel steps back to 25%
   while selected; hover brings it back. Deselect = click empty canvas.)
3½. NEW — click "Oss Licenses Trail" in the 🗺️ FEATURED TRAILS legend (same panel,
   below trailheads): the orange route lights END-TO-END, camera fits, and the 🥾
   runs the WHOLE trail — list → drawer → settings → about → oss-licenses. Line:
   "trails have names now — watch the boot run one, trailhead to summit." Colored
   dashes at rest = the 8 marquee trails, park-map style. Re-click the row (or
   click empty canvas) to deselect. Deep link if needed:
   `#target=contacts&platform=android&trail=discard-customizations-dialog-trail`.
4. Click a DEEPER card (e.g. delete-contact-dialog): multi-leg walk — trailhead →
   contact-detail → overflow → dialog. "That's the agent's actual route."
5. Switch to the subway money shot: `#target=contacts&view=subway&
   focal=contacts%2Fandroid%2Flist-populated&depth=3` — "focal at center, ≤3
   forward hops: this is the adjacency we hand the agent."
6. Detail panel → AGENT tab (subway mode only; disabled elsewhere by design):
   the literal prompt text, hops + descriptions + shortcuts. Hit Copy. "You could
   paste this into any LLM."
7. REPORT: "contacts trail guide" — green Trailheads section up top, 108 trails
   below. "Auto-derived, refreshed every run."
Escape hatches: 🔗 button copies the exact current view; theme toggle if the room
lighting fights the dark theme (light theme verified end-to-end).
ATLAS NOD + MAP RESTYLE (2026-07-14, Sam's call): the screenshot-node flow-map
approach was inspired by Revyl's Atlas (revyl.com/atlas — Sam talks with the
founder; waypoint collection arrived independently, the visualization direction
came from them). On-slide footnote credit added to the ASSET C slide + spoken-nod
line in its presenter notes; template header comment credits them too. To make the
map view OURS without a redesign: (1) topo-contour canvas texture replaces the dot
grid (MAP only — subway keeps dots), (2) painted trail-blaze marks on every card —
one per platform, android green/ios blue, DOUBLE blaze = cross-platform (real-trail
signal), (3) transition edges are round-cap dotted "footpath" lines, (4) trailhead
glyph is now 🥾 (was ✈) per the iconography rule, (5) header stat renamed
screens→WAYPOINTS (our vocabulary vs Atlas's "Screens/Transitions/User paths").
Subway view untouched — already distinctly ours. Deck stills on the ASSET C slide
predate the restyle; live demo shows the new look.
TRAIL RUNNER RETHEME (2026-07-15, Sam's call, commit 8d8708d8): second visual pass —
the whole viewer now wears the Trail Runner design language (trailrunner.css tokens):
neutral #0f0f0f/#181818/#232323 surfaces + white-alpha hairlines (was blue-dark),
sky-blue #3399FF accent, trailheads own the ONE high-emphasis green (#00E013 —
TR's "zero to one green moment per screen" rule), Hanken Grotesk + JetBrains Mono,
pill toggles/edge-chips, 16px card radius, subway lines rebuilt from the TR accent
family. Semantics on stage: GREEN = start here (trailheads, reachable-hint),
BLUE = selection/interaction. Deck stills predate this pass too.
TRAILHEADS SECTION (698fa69a, Sam 2026-07-15 "that's our thing"): map view has a
floating 🥾 Trailheads panel (top-left) — one row per trailhead, per-destination
chips (platform-labelled when cross-platform); CLICK A CHIP live on stage: it
selects the destination and the map dims + zooms the entry path (free via the
existing focus behavior). Report view = "trail guide" now and LEADS with a green
Trailheads section. Header stats start with TRAILHEADS. Vocabulary is fully ours:
mode button Screens→Waypoints (hash key 'screens' unchanged — old #view= links
fine), sidebar "Trails / N trails blazed", report cards "Trail"/"waypoints",
loading state "🥾 Blazing the trail…". DEMO BEAT: open map → point at the panel →
"three ways in, one click shows the way" → click contacts_launch android chip.
- The AVD (tb-map-34) carries 8 trail-themed contacts (Amber Alpine → Sage Canyon,
  Casey Trailblaze 555-0134 is the star) so list/search screens look real.
- GOTCHA the viewer binds TRAILBLAZE_PORT **and PORT+1** (HTTPS). If a run daemon sits
  on 52621, the 52620 viewer dies with "Address already in use" — `TRAILBLAZE_PORT=52621
  trailblaze --stop` first.
- GOTCHA example pairs must be classifier-less (`*.example.json`/`.webp`) for graph
  nodes to show screenshots; `capture-example` writes `*.example.android-phone.json`
  when the source log records a classifier (run sessions do; `verify` CLI sessions
  don't) — rename both files AND the embedded `screenshotFile` field.
- `capture-example` won't overwrite existing pairs without `--force`.
- GOTCHA long-press: `tapOnPoint {longPress: true}` and ref-based `longPress` both
  degrade to a plain TAP on the on-device accessibility driver — a zero-distance
  `dragTo` (same ref/point, `durationMs: 1500`) is the reliable press-and-hold
  (that's how the multi-select shortcut works).
LIVING TRAILS + WALKER (2026-07-15, commits e26b84a1 / 4e1c4c3a / 6aecb4ed):
- e26b84a1 polish: focused trails FLOW (dash animation toward the selection),
  trailhead nodes pulse a soft green beacon, 🥾 favicon + live tab title, TR-styled
  minimap, 🔗 copy-link button (clipboard + ✓ flash), Trailheads panel collapses.
- 6aecb4ed walker: selecting a waypoint sends a 🥾 WALKING the shortest entry route
  (trailhead → selection) along the exact rendered curves. DEMO BEAT upgrade: click
  the contacts_launch android chip → "the map doesn't just show the way in — watch
  the boot make the trip." Respects prefers-reduced-motion; skips routes >14 legs.
- 4e1c4c3a demo-day insurance: esm.sh imports pinned EXACT (@xyflow/react@12.8.4,
  style.css to match, dagre@1.1.8) — the unpinned `@12` URL drifted to 12.11.2
  mid-session, so venue-day behavior is now deterministic. Plus MeasureKick: React
  Flow withholds EVERY edge until nodes measure, and measurement rides on
  ResizeObserver, which only delivers when the page paints — an occluded/embedded
  tab can stay edge-less forever. A tiny store-level watchdog force-measures
  stragglers on a timer (no-op in a normal tab). Verified all four views, both
  themes, both README deep links, 820px + 1600px, console clean.
- GOTCHA (verify workflow, not the demo): the Claude preview pane never paints
  unless screenshotted — rAF and ResizeObserver freeze, and trusted clicks hit-test
  a 0×0 viewport. Verify via preview_eval + synthetic .click(); each screenshot
  ticks exactly one frame (walker/camera animations advance per shot). On a real
  projector none of this applies.
NAMED TRAILS + MAP OVERLAY (2026-07-15, commit db377cb6, Sam's ask: "overlay trails
on the map like an actual trail map — trailheads around different areas, walk all
the waypoints when you run a trail"):
- Every derived journey is now a NAMED TRAIL: destination words + park-map length
  suffix — Spur (≤1 hop) / Path (≤3) / Trail (≤5) / Ridge (≤7) / Traverse (8+).
  Deterministic from the graph, no stored state. android scope names that land:
  "Discard Customizations Dialog Trail" (6 wp), "Oss Licenses Trail", "Add Blocked
  Number Dialog Trail", "Ringtone Picker Path".
- FEATURED TRAILS (≤8): longest trail per trailhead entry (every trailhead area
  contributes — that's the "trailheads around the map" beat), then longest fills
  to destinations not already visited by a featured route. Colors from the subway
  palette SKIPPING GREEN (green stays trailheads-only). At rest they overlay the
  map as colored dashes ('4 7') over the faint footpath dots — named-route marking,
  exactly like a park map. Shared segments go to the LONGEST trail so marquee
  routes read continuously and shorter ones branch off.
- Panel gains a 🗺️ FEATURED TRAILS legend under Trailheads: color dot + name +
  wp count, footer "42 trails blazed in scope — see all in Report →" (mode jump).
- CLICK A TRAIL = RUN IT: exactly that route focuses in the trail's color (not
  the all-entries union a waypoint click shows), camera fits, Detail opens on the
  destination, and the 🥾 walks EVERY waypoint trailhead→destination — walker speed
  scales (max(420, totalLen/8) px/s) so even the longest trail lands in ~8s; leg
  cap 24 for trail runs (vs 14 for entry walks). Waypoint clicks / pane clicks /
  filter changes all clear trail-run mode cleanly.
- DEEP LINK: #trail=<slug> (slug = kebab name), e.g.
  `#target=contacts&platform=android&trail=discard-customizations-dialog-trail`
  — applied once at load, survives target/platform/view rewrites (APP_HASH_KEYS).
- DATA PROVENANCE (Sam asked): trails are BFS-derived from authored trailheads +
  shortcuts (same 42/108 as Report) — NOT replayed sessions. The full view
  hierarchy lives one level down: waypoint classifiers match the live hierarchy
  at runtime ("you are here"), grounded by capture-example snapshots taken FROM
  real session logs. Trailheads need no hierarchy — declared entry tools + a
  promised landing waypoint. Seam already commented in deriveJourneys to swap in
  real recorded-run data later without touching rendering.
- GOTCHA (verify workflow): occluded pane ALSO throttles setTimeout hard (chained
  timers → 1s+, long chains → 1/min) — walker spawn/fitBounds fire late in the
  pane, and multi-await eval scripts blow the 30s eval budget. Probe with single
  synchronous evals + Bash sleeps between; screenshots still tick frames.
- DEMO SCRIPT UPDATE: insert between beats 3 and 4 — click "Oss Licenses Trail"
  in the panel: "these are named trails now — watch the boot run the whole thing,
  trailhead to summit." Orange route end-to-end + full walk is the wow.

### 🙋 Ready-to-paste: opening audience poll (no slide needed — just say it)
Right on the opener (round 10: "AI is supposed to do everything"): *"Quick hands — who's
tried getting an LLM to drive your real app? … Keep your hand up if you'd trust it to run
in CI every night."* The hands drop, and that IS your "slow / expensive / non-deterministic"
slide. Works even better now — the poll lands between the dream beat and the falls-short turn.

### 📋 Ready-to-paste: agenda / roadmap slide (only if a dry run feels disorienting)
I did **not** insert this — your cold open on the problem is strong and an agenda can blunt it.
But your style likes an agenda up front, so here it is, ready. Best placement: right after
"Ten years on this problem," as the Act 0→1 bridge. Paste between two `---` fences:

```md
<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# The trail we'll walk

- The **problem** — why good coverage is still hard
- The **thesis** — blaze once, trail forever
- **One file, every platform** — the unified trail
- **What the LLM sees** — reports as context
- **Who writes the tools** — and how agents reach them
- **Owning the speed** — the driver
- **The map** — and what's next

<!-- OPTIONAL roadmap. Cut if you'd rather stay cold on the problem. -->
```

## 🔎 Editorial read-through — per-act readout (for your dry run)

**Overall:** the narrative is strong and well-signposted; the ACT markers do a lot of quiet
work. Your main risk is **time, not content** — there's more than 40 min of good material
here, so the skill tomorrow is *pace and cuts*, not additions. Below is only what's worth
acting on; acts I don't flag are already solid.

**Timing readout**
- Marked content runs to ~36:30, Q&A ~3.5 min. My two added slides nudge Act 0 (+~15s) and
  Act 2 (+~30s, and that one's optional). Not worth re-numbering the markers.
- **Highest over-run risk: Act 4** (three beats + ASSET B, the densest stretch). If you're
  past ~27:30 leaving Act 4, pull from the cut list immediately.
- **Two code slides** (unified YAML, TS tool) are the classic time sinks — budget ~60–75s
  each and resist explaining every line. The types/TSDoc slide especially reads itself.

**Per-act flags**
- **Act 0** — Open the poll (above). The split now lands scale physically; advance *briskly*
  through the 7-grid, don't read all seven labels aloud — the picture does it.
- **Act 1** — The compiler table and the lifecycle mermaid are dense-adjacent. The mermaid is
  a "walk it once, don't dwell" slide (~45s). Refrain lands here — say it, don't explain it.
- **Act 2** — Your strongest conceptual act. Slow down on the **unified YAML payoff**, not on
  the setup. Drift slide is optional cinema before it. "Most flaky tests die right here" is a
  keeper line.
- **Act 3** — *"A screenshot was worth 1,000 words → now 1,000 tokens"* is a highlight; land
  it and pause. Reports slide is a quick one-breath beat.
- **Act 4** — Densest act, biggest over-run risk. **Protect** "Could the LLM construct it?" +
  "Recordings are earned, not written" — that's the emotional beat. If you must trim, cut
  "Tools return data" and "Not every tool is for the LLM" *first* (they're refinements).
- **Act 5** — Let the driver-table numbers sit; don't narrate every row. The iOS/web honesty
  line matters — it buys credibility, keep it.
- **Act 6** — Map payoff; you've earned it, so keep it brisk. *"You never need a trail"* is a
  mic-drop — let it breathe for a beat.
- **Act 7** — recipe → refrain → CTA → "now the robots write the tests." Clean close. Don't
  add anything; resist the urge to summarize twice.

**Tightenings I did NOT make (your call — flagged, not churned)**
- *"Not just test authoring"* (Act 6) headline reads like a topic label; a statement version
  lands harder — e.g. **"Bigger than tests."** Left as-is; flip it if you agree.
- *"The CLI pivot"* — the 4th bullet ("do we lose the natural language?") is a great
  cliffhanger into the "driving the device" peak. Lean into it vocally; no edit needed.

**One backup slide worth adding (optional):** a hidden **reliability** card holding your
banked wording — *"still getting to 100%; reliability is good and improving; parity across
Android and iOS"* — no number, just the phrasing, so if asked you stay calm. Q&A answers are
in the gap-analysis section further down; skim them before you walk on.

## Portable session files (take these to any machine)

- `samdroidcon.md` — the Slidev deck (render with `bunx slidev samdroidcon.md`; theme seriph)
- `samdroidcon-notes.md` — this file: Sam's brainstorm capture + decisions + open questions
- `samdroidcon-research.md` — topic digests from deep-reading github.com/block/trailblaze
- `samdroidcon-repo-map.md` — where everything lives in the trailblaze repo (navigation aid)
- Setup for live preview: `.claude/launch.json` (runs local slidev binary, port 3030); a
  local untracked `.npmrc` holds any machine-specific registry config.
  Fresh machine: `bun add @slidev/cli @slidev/theme-seriph`, then `bunx slidev samdroidcon.md`.

## Talk reframe (2026-07-11 brainstorm)

The auto-generated deck reads like an intro/pitch. Sam's actual goal: **how Block is using
Trailblaze in production (Square + Cash, 1.5+ years) and the philosophy of where it's going.**

Continuity with past talks:
- **Droidcon NYC 2025** — Sam + Brian covered the original technical details: the agent loop
  (natural language + screen state → tool selection → drive the UI). This talk should NOT
  re-cover that; it's the sequel.
- **Droidcon NYC 2016** — exactly 10 years ago, Sam presented the **robot pattern** for UI
  testing (what-vs-how separation). Still used in Square's Espresso tests today. Perfect
  callback: Trailblaze custom tools are the robot pattern reborn for the LLM era.

## Core points from Sam (raw capture, organized)

### 1. The scale problem: the platform matrix
- Square targets **Android, iOS, and proprietary devices**, across multiple form factors and
  OS versions each.
- A single scenario — even simple login — needs coverage on **7 targets** today:
  `android-phone`, `android-tablet`, `ios-phone`, `ios-ipad`,
  `square-t2`, `square-t3`, `square-x2`.

### 2. Old format: platform-specific trail files (and why it hurt)
- One trail per platform (Android-phone trail, iPhone trail, ...). It worked, but:
  - **File explosion.**
  - **Natural-language steps drifted out of sync** between platform versions.
- Original value prop of Trailblaze: represent a *business case* (steps a user goes through),
  materialize it per platform, run via LLM, **self-heal via LLM** when the app changes.
  Platform-specific files undermined the "single business case" part.

### 3. New unified trail format (the breakthrough)
- **One file = the user journey** (single natural-language source of truth) **with all
  platform-specific recordings embedded within it.**
- Runs exactly the same way as before — just one file now.
- "Obvious in retrospect" — but not while the YAML spec and CI pipelines were still evolving.
  (Honest-lessons material.)

### 4. Why it gets unwieldy anyway
- 5+ platform variants per test; differences can be small but real:
  tablet vs phone navigation, scrolling behavior, generally different experiences.

### 5. Robot pattern → custom tools (what vs how)
- Hundreds of tests → massive duplication. The 2016 answer was the robot pattern:
  **separate WHAT you test from HOW you test it.**
- This separation is *incredibly important when communicating with an LLM*:
  - The LLM wants to decide **what** to do; forcing it to also figure out **how**
    (via skills / "just figure it out") explodes the context window on decisions
    that should have been one decision.
- **Custom tool = robot method** (`login`, `addItemToCart`). What, not how.
- When the implementation changes under the hood, you adapt the tool programmatically —
  **trails on disk don't change.** At thousands of trails, this is what makes
  maintenance survivable.

### 6. Maintenance safeguards at scale
- One parameter change on a tool can ripple across **hundreds of YAML files**; these tests
  are slow and don't run on PR checks, so a bad check-in surfaces late and expensively in CI.
- Safeguard: **strict YAML parsing** — no fields that don't exist in the schema of your
  defined tools.
- Mechanism: YAML file → **generated TypeScript version** → run through a **compilation
  step** as validation.

### 7. The TypeScript decision (tools-as-code story)
- Tools were originally **Kotlin**. Fine when it was Android-on-device, then iOS via
  source-based compilation.
- Problem: agent harnesses (Codex, Claude) need to **iterate fast**; Kotlin tooling requires
  forking Trailblaze and full compilation. Arduous.
- Long-standing idea: scripted tools. First thought: **JavaScript** via a QuickJS↔Kotlin
  binding, run in-process (same approach as Cash App's **Redwood** library).
- But: wanted **type safety** + a language **LLMs are good at editing** → **TypeScript**.
- Wins: type-safe errors; tooling that **generates bindings** for your custom tools so you
  *and your agents* can code up custom logic for your use cases.

### 8. Distribution + the tool-registration inversion
- Running from source (forking Trailblaze) was a barrier to adoption and slow.
  → Now shipping a **binary via Homebrew**.
- But a binary made contributing your own tools impossible.
  → **Inverted tool registration**: tools live **on the filesystem** in a **"trail map"** —
  TypeScript-defined tools that use framework tools under the hood.
- Trail map + per-target configuration (e.g. Cash App) = fast iteration on trails with
  your coding agent (or by hand).

### 9. Maestro → accessibility driver: ~3x faster
- Migrated off Maestro to Trailblaze's own accessibility-based driver.
- **Nearly 3x speed improvement.** (Pull exact architecture + numbers from the repo.)

### (more to come — Sam stopped here, has more points)

## Core points round 2 (2026-07-11, after outline v1)

### 10. The REAL problem statement (talk should open with this)
- Not just the device matrix — the bigger question: **how do we get good test coverage now
  that AI is prevalent and at our disposal?**
- AI + mobile still has **no canonical way of interacting that can be expressed in natural
  language.** Many AI solutions exist, but they're platform-specific, or they **lose the link
  between the natural-language objective and the actions that actually got performed.**
- That link is critical: **the test case is WHAT should happen.** The "how" really only
  matters for assertions, where you may want to be mechanical/handwritten.
- **YAML as the surface**: the LLM can construct it AND the human can come in and edit it.
  **Git** gives the historical perspective on how a test evolves.
- Custom tools complete it: **your app has its own tools.**
- Differentiator summary (Sam's words, lightly compressed): custom tooling + composable
  tools + cross-platform & cross-form-factor representation + maintained natural language =
  what sets Trailblaze apart for offering AND maintaining automation.

### 11. Trailheads — deterministic setup (missing from outline v1)
- A **trailhead is just a tool, designated as a trailhead** — effectively the test-setup
  method: "I start in this certain place."
- Why it matters: tests commonly fail because setup isn't deterministic — clearing app data,
  closing apps, launching with correct arguments. You need that for **deterministic replay**,
  which is the whole point.
- Lives inside the trailmap concept / new unified trail format story.

### 12. The CLI pivot (early 2026) — huge unlock, must be in the talk
- Trailblaze became a **CLI** because **MCP setup was a big friction point**: users had to
  configure the MCP then start a NEW session; and while *developing* the MCP, any code change
  disconnected it → more problems.
- With a CLI: someone with a skill (or just awareness of the binary) can install and start
  using it **within the same session**.
- The fear: how do we expose all the tools and still keep natural language? Answer emerged
  from targets: **each target is basically a trailmap**, so the CLI exposes a **toolbox** of
  available tools for that target; the agent calls tools via the CLI and **provides the
  natural-language step alongside each tool call** — so as it drives the device it's
  simultaneously constructing the trail with its NL steps. Exactly what we wanted to save.

### 13. Reporting approach — standalone, CDN-servable, no SaaS
- From the start: a **standalone report** servable from a CDN — full-fidelity capture
  viewable in CI, downloadable as a **zip for analysis by you or by an agent**.
- "Incredibly powerful" — and **no software-as-a-service backend needed**.

### Corrections
- **AndroidWorld benchmarks: DELETED from the repo — do not mention in the talk.** Not
  invalid, just never leaned into; the original motivation (building a great custom agent)
  faded because the simple agent + leaning on Claude Code / Codex won.

## Core points round 3 (2026-07-11)

### 14. The acceptance gate — AI authors, human approves, git blesses
- AI can author trails and heal them **without human interaction** — but the current
  process has a **human accept what was generated** before it's committed.
- Once committed: "we now have a deterministic way of saying — YES, this is how this
  natural-language case materializes on this platform."
- The commit IS the acceptance gate; git history = the record of blessed materializations.
  (Pairs with the repo's "agent-authored, human-readable" philosophy devlog.)

### 15. CI: the on-demand pipeline
- On-demand pipeline triggers **any number of trails**, filtered by **device type**.
- Defaults to the **latest build**; can pin an **exact build** (e.g. for a regression).
- This is public/shareable — it answers the "how do 7 targets run in CI" Q&A preempt.

## Core points round 4 (2026-07-11, reacting to compiler slide)

- **Self-heal = recompilation.** Because the NL source exists, we can *recompile* a step
  against the app as it works RIGHT NOW: the LLM navigates the app to meet the laid-out
  steps/objective — and appropriately reports if it cannot. (Extends the compiler metaphor;
  self-heal slide should sit adjacent to the compiler slide.)
- **Deep execution context is a key differentiator.** Per trail run Trailblaze captures
  network calls, logcat, analytics events, screenshots, hierarchy, etc. — "deep detailed
  context of the execution." That context is what lets the LLM recover well during
  self-heal, not just try again blind.
- **Source-code trace-back.** If the LLM also has your source, it can trace a failure back,
  put in a fix or a new version, and continue. Block mostly tests **binary builds**, but
  still traces issues back to source and finds the commits that caused them.

## Core points round 5 (2026-07-11)

- **Not a test — a user journey / job-to-be-done.** The NL is "the thing that should be
  possible that we need to ensure is possible." It *materializes* as a test. Today each
  platform buries its own implementation deep in its codebase — you can never connect
  "this iOS test IS this Android test" without some mapping. A trail + per-platform
  recordings = the materialization of the NL succeeding → **unified validation of a user
  journey**. Focus shifts to what the user should experience.
- **Recordings are disposable; the journey is not.** Sourced in git, you can drop the
  recordings for one platform (or all) and re-materialize them — the LLM has the past
  recording + all its context to do so.
- **Archived runs = "the last run down that trail."** (Terminology: always "run," NEVER
  "hike" — Trailblaze never uses that word.) A trail alone loses the deep
  execution context (hierarchies, logs, network, analytics). In CI every run's zip is
  archived — at any point, pull a test's **latest successful run** for diagnostics: all the
  pictures and logs from the last time somebody went down that trail. The trail = the steps
  to follow; along the way you might find an overflowing creek and route around it
  (self-heal).
- **Sam asked for a test-lifecycle diagram** in the deck.

## Core points round 6 (2026-07-11)

- **Conditionals are a real problem.** Examples: "if you're on a tablet, dismiss this
  window"; "if there's a new notification, dismiss it"; "if the price is above $20 do X,
  under $20 expect Y." Current answer: write a TypeScript custom tool that does the
  computation + conditional. A teammate added a **`runIf` tool** — people use it; Sam has a
  knee-jerk reaction against ANY logic living in a trail. His acceptance criterion:
  **"as long as the LLM could construct it, I'm okay with it."** (Ties to day-one devlog
  rule: recordings are strictly linear; conditionals belong in tools.)
- **Typed tools return structured JSON, not just text.** Via the type-safe TS bindings
  (`trailblaze.tool<In, Out>`): e.g. list installed applications on a device, grab a user's
  profile information — results the next step can consume. Super helpful in practice.
- **"Could the LLM construct it?" — confirmed important, with a sharp nuance.** It means
  the LLM *selecting a tool out of the target's toolbox* while actually driving — NOT
  handwriting the YAML. Sam actively discourages LLMs handwriting trail YAML: validation
  would catch malformed YAML, but you'd still be **locking in a recording that never
  actually ran**. The hope: recordings are generated by the agent actually driving and
  successfully completing the run. (Claude's compression: "recordings are earned, not
  written" — Sam-approved phrasing pending.)
- **Tools have properties — LLM visibility among them.** Underlying utility tools exist
  purely to be called by other tools; a tool decides whether it's exposed to the LLM.
  "An LLM needs to pick what's going on, but that doesn't mean monoliths — we still break
  things down into smaller, reusable pieces." Only the right surface is advertised.

## Core points round 7 (2026-07-11)

- **Trailblaze is not only for test authoring.** It gives agents-with-an-objective the
  ability to interact with devices. A trailmap for an app on that device = the agent
  navigates more effectively — fewer decisions, tools doing the work under the hood.
- **"You never, ever, ever need a trail to use Trailblaze."** Immediate value = driving
  UIs with an agent, period. No prerequisite. Deliberately designed so that all the data
  is already being captured — so the moment you decide something's worth doing repeatedly,
  you save it as a trail. Device control first; trails when you're ready.
  (2026 version of 2025's closing message "incremental adoption is key.")

## Answers locked in (2026-07-11)

- **Talk ethos:** developer conferences are about sharing technical thought process and
  discussion, NOT sales pitches. Main goal = the thinking; getting people to investigate
  Trailblaze is the natural side effect.
- **Waypoints positioning (Sam's words):** something he heavily wanted to invest in and
  work HAS been done, but it's "an idea that hasn't been fully proven out yet." He's
  bullish and wants to talk about where it could take us — but clearly framed as **the
  future step, not right now**. (Matches repo reality: README calls it an active prototype;
  matcher/assertWaypoint/CLI/viewer shipped, goTo/pathfinding not built.)

- **Targets (7):** android-phone, android-tablet, ios-phone, ios-ipad, square-t2, square-t3, square-x2.
- **Audience assumption:** most have heard of Trailblaze (or will get it fast); include a
  *quick refresher* in the first couple of minutes, but don't re-teach the 2025 agent-loop talk.
- **The takeaway sentence:** the audience walks away with a snapshot of Block's *current
  positioning* on representing user journeys/trails to effectively test the app — via the
  unified trail format and everything inside Trailblaze — as a **general recipe and vision**
  they can adopt (use Trailblaze, or apply the patterns in their own systems).
- **Trailmap** is a real spec in the repo (github.com/block/trailblaze) — cloned to scratchpad,
  research agents digesting devlogs + code.

## Clarifying answers round 2 (2026-07-11) — TALK IS IN 5 DAYS (Jul 17 → prep by Jul 16)

1. **Demos: pre-recorded only.** Bad live-demo experience last year; heavy travel/work before
   the talk — everything must be done ahead of time. Videos and/or generated reports.
2. **Scale numbers OK to share:** trails are "in the hundreds" today, projecting to
   thousands. Framing: keep the automatable automated, so manual testing focuses on new
   features + hardware-specific things automation can't reach yet.
3. **Robot-pattern callback = a beat, not the spine.** Mention 10+ years in this space; 2016
   talk covered (a) what-vs-how separation and (b) the importance of screenshots. Both
   concepts survived and shaped Trailblaze: what-vs-how → custom tools & LLM context
   discipline; screenshots → reporting that captures screenshot + view hierarchy + logs.
   Punchline: "a screenshot was worth a thousand words — now the LLM has ALL the context it
   needs (screen, hierarchy, logs) to solve failures and iterate."
4. **Title interpretation locked:** "Map Your App for AI" = building your app's trailhead
   tools + custom tools — the composable shortcuts you expose to the LLM to reach states and
   do things the fastest way. Tools + a large trail corpus = the map of all your trails.
   Waypoints = the NEXT step (visualizing how it all interconnects). Future-framed.
5. **Square hardware — use product names + public imagery** from squareup.com/us/en/hardware:
   T2 = **Square Terminal**, T3 = **Square Handheld**, X2 = **Square Register**. Show
   alongside iPhone/iPad/Android phone/Android tablet for the 7-target matrix slide.
6. **Openness:** everything in the repo/devlogs is 100% public, deliberately. Wants honesty
   about challenges BUT don't confuse people with every wrong turn — frame as "we evolved and
   why." (Claude: no red flags found in the public repo; watch: don't present AndroidWorld
   70% as a result — it's a target; don't conflate the 135x web benchmark with the driver 3x.)
7. **Call to action:** `brew install` → grab the Trailblaze skill (repo `skills/trailblaze/`)
   → tell Claude/Codex to use it on your own app.

## Emerging themes (Claude's read, for discussion)

- The through-line candidate: **"what vs how" is the load-bearing idea** — it explains the
  unified format (what = journey, how = embedded recordings), custom tools (robot pattern),
  and LLM context discipline. The 10-years-later robot-pattern callback could be the spine.
- The talk is really a **scale + production-lessons talk**: 8 targets × hundreds of tests ×
  thousands of trails. Each architecture decision (unified format, TS tools, homebrew +
  trail map) is an answer to a scale pressure.

## Open questions

- **Demo**: which app, which platform? Backup video still to record.
- **Honest limits**: what goes in the "where it doesn't fit" bullet?
- **YAML examples** on slides 6 & 10 are stale vs. the new unified format — sync before the talk.
- QR code for the repo on the closing slide.
- How much of the auto-generated deck's waypoints/edges/nav-graph content survives the reframe?
- "Trail map" (tool registry on disk) vs. the talk title "Map Your App for AI" — same map,
  different map, or intentional double meaning?

## Sam's slide style (extracted from the 2016 + 2025 decks — apply to this deck)

Content density & type:
- **Many slides, each light** — 2016 was 102 slides, 2025 was 63 for ~40 min. Sam advances
  fast; one idea per slide beats one slide with five bullets.
- **Build sequences across consecutive slides**, not v-clicks on a dense slide: the 2016
  What/How robot diagram evolved over ~10 slides; the 2025 architecture diagram added one
  layer per slide. Repetition-with-addition is his reinforcement mechanic.
- **Cumulative lists**: "1. See what's tested" → next slide adds "2. Diagnose failures" →
  next adds "3. Share your tests."
- **Real, verbatim code in small doses**: the do/while agent loop, a single data class, one
  robot one-liner. Code is shown to be *read aloud*, not studied.
- **Numbered evolution storytelling**: "1st Attempt → 2nd Attempt → 3rd Impl → Final" —
  wrong turns narrated as a sequence, not a confession.
- **Dated milestone timeline slides** (2025 origin: DragonCrawl Apr 2024 → ai-test-agent
  Nov 2024 → Trailblaze Mar 2025).
- **Headlines are statements or questions** ("What does 'AI Driven' mean?", "Oops, Your
  Test Failed" → "Ahh, I See Why"), not topic labels.
- **Punchline slides with 2–3 words** ("1,000 Words") for the big beats.
- Agenda slide up front; explicit **Call To Action** close; occasional audience poll opener.

Deck implication: prefer SPLITTING a bulleted slide into 2–3 light slides / a build
sequence over cramming one slide. The 38-slide skeleton will likely grow to 55–65 slides
at Sam's natural density — expected, not scope creep.

## Proposed outline v3 (2026-07-11 — after step-back + subagent review; v2 superseded)

**The spine (state at end of Act 1, reprise on recipe slide):** two braided threads —
**protect the natural language** (the WHAT) and **make everything beneath it deterministic**
(the HOW). Every architecture decision in the talk defends one of the two. Self-heal is the
hinge: when determinism breaks, the NL rescues it.
**The refrain:** **"Blaze once, trail forever."** Introduce in Act 1, echo at close.
**The map assembles progressively** — trails (1–2), trailheads (2), your tools (3–4), the
trailmap container (4) — so Act 6 *names* what the audience watched get built. Seed it twice:
Act 0 ("by the end you'll see the map — spoiler: you're already building it") and Act 4
("note the name trailmap; we'll come back to why it's a *map*").

Changes from v2 (review-driven): Act 4 split into two labeled beats with internals cut to
one-liners (protect the CLI climax); "blaze once, trail forever" refrain added; A* numbers →
backup slide; assets 1+2 merged into one video; memory/`--secret` beat added to Act 2;
title seeded; Act 5 compressed; real Q&A time recovered (~3.5 min); closing grace note added.

### Act 0 — The problem: coverage in the AI era · 0:00–4:00
(RETHREADED round 10 — Sam: "this is a messaging request." The arc:)
- Open on the HYPE: **AI is supposed to do everything.** Then the dream, concrete:
  say *"create a contact for Casey"* and an agent **drives a real phone** — it just
  does it. Then the turn: on mobile it **falls short** at **device control** and
  **testing what matters** — the TWO hard problems this talk answers.
- Falls-short #1 (economics): an LLM on every run — slow · expensive ·
  non-deterministic; great for exploring and for agents driving devices, brutal for CI.
- Falls-short #2 (the missing link): it did it — but what exactly did it do, and how
  do you run *that* again deterministically? The **objective ↔ actions link is lost**;
  the objective is the *what* and most tools throw it away. ("Per-platform" is now
  SPOKEN here as the bridge to Seven Targets.) The "how" matters mainly for assertions
  you deliberately want mechanical (verify: thread returns in Act 2 — don't leave it
  dangling).
- User journeys = the **unit of quality** — what a user must always be able to do;
  you write it once.
- Our scale makes it concrete: the 7-target matrix with product imagery — Android
  phone/tablet, iPhone, iPad, **Square Terminal, Square Handheld, Square Register**.
  One user journey, seven targets, hundreds of trails (thousands = spoken-optional,
  round 9). Say SEVEN.
- Who I am: 10+ years in UI testing; Droidcon NYC 2016 (robot pattern + screenshots);
  Droidcon NYC 2025 with Brian (agent loop internals).
- Title seed: "by the end you'll see what the map is — spoiler: you're already building it."
- Hand-off (round 10): two hard problems on the table — device control, and protecting
  user journeys. "Our answer to BOTH is a thesis about natural language and determinism."

### Act 1 — The thesis: blaze once, trail forever · 4:00–9:00
- Refresher in one slide: agent drives the real app from NL → saved as a **trail** (YAML) →
  **deterministic replay, zero LLM calls** in CI. **"Blaze once, trail forever."**
- "LLM as compiler": NL = source, trail = bytecode, device = runtime.
- **YAML is the shared surface**: LLM constructs it, humans edit it, **git gives the
  historical perspective** on how a test evolves.
- **The acceptance gate:** AI authors (and heals) without human interaction — but a human
  **accepts** what was generated, and the **commit blesses it**: "yes, this is how this
  natural-language case materializes on this platform." Agent-authored, human-approved.
- Self-heal = the hinge between the threads: recording fails → LLM re-solves *just that
  step* from the NL. Off by default (failures stay actionable); **% passed via self-heal =
  your recording-staleness health signal**. (A* cost numbers 1.0/5.0 → backup slide.)
- State the spine: "natural language on top, determinism underneath — everything that
  follows defends one of those two."
- 📼 ASSET A (first half): zero-LLM replay clip.
- Hand-off: "That's the thesis. Production scale is what stress-tested it."

### Act 2 — One journey, one file: unified format + trailheads · 9:00–15:30
- v1 world: one trail file per platform. Worked, then broke: file explosion + **NL drift**
  between platform copies — the "one business case" value prop quietly broke.
- The fix: **unified trail format** — ONE file = the user journey; NL exists once;
  per-device recordings under `recording:` keyed by classifier; **closest-wins** lineage
  (`android-phone` → `android`). Show the real YAML, incl. a `verify:` step (assertions
  callback). Honesty hedge ready for Q&A: multi-platform example is the spec + Block
  production shape; public repo's committed unified trails are single-platform so far.
- **Memory/parameterization (60s, closes the drift anecdote):** `{{memory.var}}` seeding,
  `--secret` redaction, and **reverse-substitution** so recordings never hardcode stale
  credentials. (Guaranteed Q&A topic otherwise: test accounts/secrets in CI.)
- **Trailheads**: a tool designated as the trail's starting point — the test-setup method.
  Deterministic setup (clear app data, right launch args, land in a known state) is the
  precondition for deterministic replay; most flaky tests die right here.
- Honest beat (concrete > generic): **trail YAML v2 was fully designed and never shipped**
  — reality overrode a finished design doc on the way to unified.
- Hand-off: "Unified files fixed drift. Duplication was next — and I'd seen that problem
  ten years ago."

### Act 3 — What vs How: the principle that survived 10 years · 15:30–21:00
- 2016 callback: robot pattern — separate WHAT you test from HOW. Still running in Square's
  Espresso suites today.
- The LLM twist: an LLM wants to decide **what** to do next; making it figure out **how**
  explodes its context on decisions that should be one decision.
- **Custom tools = robot methods**: `login`, `addItemToCart`. **Your app has its own
  tools.** Implementation changes → fix the tool once; trails on disk never change.
- The screenshots thread from 2016, upgraded: per-step **screenshot + view hierarchy +
  logs**. "A screenshot was worth a thousand words — now the LLM has ALL the context it
  needs to solve failures and iterate."
- Reporting architecture (kept tight): **standalone report, servable from any CDN**, zip
  downloadable for you *or an agent*. **No SaaS backend.**
- 📼 ASSET A (second half): the replay's own report walked through — screenshots,
  hierarchy, logs, LLM transcript. One video serves Acts 1+3 and shows the CDN/zip point.
- Hand-off: "Tools are the unit of reuse. Two questions decided everything: who gets to
  write them, and how do agents reach them?"

### Act 4 — Two beats: tools as code, then the CLI pivot · 21:00–27:30
- **Beat 1 — WHO writes tools (21:00–24:00):** Kotlin, compile-time registration → fork +
  rebuild = a wall for external teams, too slow for agents. Ship a binary (Homebrew) →
  nobody can compile tools in → **inversion: tools live on the filesystem in a trailmap**
  (npm packages). TypeScript because **types ARE the schema, TSDoc IS the description** —
  and it's the language LLMs edit best. (Engine/runtime internals = one line: "runs in an
  embedded JS engine, identical on host and device." QuickJS/Bun/codegen → backup slides.)
  Title seed #2: "note the name — *trailmap*. We'll come back to why it's a map."
- **Beat 2 — HOW agents reach tools (24:00–26:30):** the CLI pivot (early 2026). MCP setup
  friction: configure → restart session; dev-loop change → disconnect. As a CLI: install
  and use in the SAME session. The fear — losing NL while exposing tools — resolved:
  each target is a trailmap, the CLI exposes its **toolbox**, and the agent **attaches the
  NL step to every tool call**. Land it unhurried (60–90s): **"driving the device IS
  constructing the trail"** — this resolves Act 0's problem statement. (One line so it
  doesn't read as trashing MCP: `trailblaze mcp` still exists as a proxy; the CLI is the
  primary surface.)
- **Guardrails (26:30–27:30):** one param change ripples across hundreds of YAMLs that
  don't run on PR checks. Strict YAML parsing + the **tsc trick** — recorded calls
  transpiled to `client.tools.x({...})` and compiled. (Keep the trick; remapping mechanics
  live on the slide, not in the narration.)
- Hand-off: "Authoring got fast. The other half of slow was the driver."

### Act 5 — Going ~3x faster: owning the driver · 27:30–30:00 (compressed)
- Maestro first (right call; "not a permanent coupling" from day one) → the walls:
  ~500–700ms/action "screenshot sandwich", lossy tree normalization, private-API reflection.
- Our Android **AccessibilityService** driver: single-pass native tree, event-based settle,
  stable refs → **~100–150ms/action, ~3x end-to-end**. Numbers live on the slide.
- Honest beat: iOS still on Maestro (replacement designed, not landed); web always Playwright.
- Hand-off: "We'd protected the language; now the determinism underneath it is fast.
  So — the map."

### Act 6 — The map, and where it goes next · 30:00–34:30
- Title payoff = a *naming*, not a reveal: "you've watched this map assemble for half an
  hour" — trails (well-worn paths), trailheads (entry points), custom tools (your app's
  verbs), the **trailmap** container. Composable, and it's YOUR surface exposed to the LLM.
- Future step: **waypoints** — named assertable places, shortcuts between them, the
  interconnection graph. Scaffolding shipped (schema, matcher, assertWaypoint, CLI, graph
  viewer); honestly framed: an idea not fully proven yet. "I'm bullish; here's where it
  could take us": `goTo(waypoint)` via pathfinding, semantic recordings, one nav fix
  repairing every trail that used that edge.
- 📼 ASSET: waypoint graph viewer flythrough — **label the calendar trailmap as a Google
  fixture app, not a Block app** (protects the honest framing).
- Hand-off: "The whole recipe, one slide."

### Act 7 — The recipe + CTA · 34:30–36:30
- Recipe slide reprises the spine: NL on top (trails in git, what-not-how tools) ·
  determinism underneath (trailheads, typed + validated recordings, native driver) ·
  self-heal as the hinge and health signal → keep the automatable automated; spend humans
  on new features and hardware. Refrain: **"Blaze once, trail forever."**
- Walk away: `brew install block/tap/trailblaze` → grab the Trailblaze skill → tell
  Claude/Codex to use it on YOUR app. QR to repo.
- Closing grace note (15s): the robot pattern outlived every framework it was written in —
  **now the robots write the tests.**
- Real Q&A: ~36:30–40:00.

### Pre-recorded assets to produce (by Jul 16) — now 3 (was 5)
1. **ASSET A (two-part):** zero-LLM trail replay → then walk its generated report
   (screenshots/hierarchy/logs/CDN/zip). Serves Acts 1 + 3.
2. **ASSET B:** agent driving via CLI toolbox with NL steps attached (`step --save` flow),
   ending with the saved trail file on screen. Serves Act 4.
3. **ASSET C:** waypoint graph viewer flythrough (calendar fixture app). Serves Act 6.
   (Nice-to-have if time: self-heal run showing SucceededWithSelfHeal.)

### Q&A preempts (backup slides)
- LLM token cost of authoring/blazing a run; replay = $0.
- "Why does replay still take 2 minutes?" (presenter sim round 8 — verbal, no backup
  slide): the minutes are the app + emulator (real UI, animations, full app-state
  reset) plus ~35s one-time CLI/daemon startup; the framework tax is ~100–150ms/action
  (Act 5). End-to-end user journey on a real device — price it against the blaze
  ($0.55 → $0.00), not a JVM unit test. Sam's verdict on seeing the number: "REALLY
  bad" — now tracked publicly as github.com/block/trailblaze/issues/210 (itemize
  invocation phases: startup/app-launch/act/settle/capture; 15 recorded actions ≈ 2s
  of the 84s). If asked on stage, the strongest answer is agreement + the issue number.
- "We already have Espresso/Compose tests — why this?" (positioning: complements; robot
  pattern lineage; NL layer = exit strategy).
- "Did you abandon MCP?" (no — proxy remains; CLI is primary).
- How do 7 targets actually run in CI? ANSWERED: on-demand pipeline, any set of trails
  filtered by device type; latest build by default, exact build pinnable for regressions.
  (Whether to name the device-farm vendor still Sam's call.)
- Secrets/test accounts (covered in Act 2, backup detail: --secret, sensitiveMemoryKeys).
- A* cost model + execution modes detail. QuickJS/Bun runtime detail. Codegen pipeline.

## Demo app decision (slide 12, ASSET A — side-by-side CLI blaze vs. recording replay)

Sam's constraints: no toy demos (wikipedia/calculator/contacts), no other company's app
(affiliation/bias), must be committable to block/trailblaze as a real example trail —
"not smoke and mirrors, literally something sitting out there." Gmail-compose is a great
personal-automation STORY but Google Auth + affiliation make it a bad committed demo —
keep as a spoken anecdote.

Options (original ranking; statuses from Sam's 2026-07-12 answers):

**A. The droidcon conference app itself — ❌ REJECTED by Sam** ("we should use a real
app"). Was: Touchlab's DroidconKotlin (github.com/touchlab/DroidconKotlin), trail =
"find this talk and bookmark it."

**B. Company-product b-roll — ❌ REJECTED by Sam** (representing the company/product;
can't clear it — and not committable as an example anyway). Direction instead: general
user/developer experiences for developer education.

**C. Mastodon (official app, OSS).** Matches Sam's personal use case — content that lives
only in a mobile app. Browse feed / post / verify. Messy real-world UI (infinite scroll,
pull-to-refresh) shows off scrolling + assertions. Committable. Needs a test account.

**D. Thunderbird for Android (OSS, ex-K-9).** The email-compose use case WITHOUT Google
Auth: compose → send → verify in sent folder against a test IMAP account. Committable.

**E. Goose desktop (Block's own OSS agent).** Trails already exist in the repo;
"Trailblaze testing an AI agent" is memorable — but desktop, not mobile, for a Droidcon room.

**Round 2 (Sam's direction, 2026-07-12): a REAL app on a stock Android device.**
- Gmail — ❌ privacy (people would see his email; Google Auth pain).
- Google Calendar — "great because we already have it."
- **Contacts (Sam's new idea)** — "a great use case of being able to define a unified
  trail with one goal and have it implemented in both places [Android + iOS]. It's also
  not completely trivial, so it could be the one we end up using."
- Sam: "We have the power to do both. So maybe I could get some agents working on both."
- usa.droidcon.com — Sam's concern: **ephemeral** — the site changes next year, so the
  committed example rots; "only useful at this one moment, but it still shows off
  everything and I kind of like that."

**Round-2 repo audit (2026-07-12, verified on main):**

*Calendar trailmap* (`trails/config/trailmaps/calendar/`, target Google Calendar,
`com.google.android.calendar`):
- **Exactly 100 waypoints + 64 shortcuts committed** — the "100/64" ASSET C numbers ARE
  this corpus. Quality is high: waypoints carry `required` + `forbidden` selectors with
  prose descriptions (day_view forbids the multi-day "Open Day View" cell — real
  discriminators, not toy matchers); ~70 have example.json + example.webp screenshots.
- Coverage includes the notoriously painful corners: recurring-event edit/delete dialogs,
  custom recurrence, timezone/repeat/notification pickers, Google sign-in flow, permission
  dialogs (notification/location/photos), notification-fired + snooze, share intents,
  widget config, quick-create in ~10 state variants.
- Gaps: **zero committed calendar trails** (it's a map with no journeys), tools = 2 thin
  launch tools only, Android-only (ios/web platform stubs empty).
- Verdict: complex enough to be impressive **as the map** (ASSET C ready essentially
  today); ASSET A's journey must be authored (which is fine — authoring is the demo).

*Contacts* — richer than assumed, and asymmetric in a USEFUL way:
- `trails/config/trailmaps/contacts/`: ONE trailmap already targeting BOTH platforms —
  Google Contacts (android) + Apple Contacts (`com.apple.MobileAddressBook`, ios).
  ~103 iOS waypoints + 81 iOS shortcuts committed; Android side has none.
- `examples/ios-contacts/` = the repo's self-described "canonical mobile reference":
  10 typed TS tools WITH unit tests (createContact, deleteContact, openContact,
  searchContacts, searchAndVerify, verifyContactStructure, addPhoneNumber,
  dismissKeyboardIfPresent, openApp, shared.ts utilities) + a per-target system prompt.
- `trails/ios-contacts/`: 18 committed iOS trails; `trails/contacts/`: 3 baby Android
  trails. **All in the OLD per-platform format** (`ios-iphone.trail.yaml`,
  `android.trail.yaml`) with `blaze.yaml` (NL-only source) sitting next to each —
  source + bytecode as a literal directory listing.
- The unified-contacts demo therefore re-enacts Act 2 with the repo's real files: take
  the journey that already exists on iOS ("create contact 'Trailblaze Demo', verify in
  list" — recording = contacts_ios_openApp + contacts_ios_createContact), materialize
  the SAME file with an android-phone recording → one unified trail, committable, and
  it upgrades the repo's own examples off the v1 shape. Slide 17's fictional
  `myapp_signInViaUI` YAML can later be swapped for this real file.

*Proposed asset division (both apps, per Sam's "power to do both"):*
- ASSET A (side-by-side + report walk): **Calendar** — "Add 'Trailblaze: Map Your App
  for AI' — Jul 17, W222 B — with a reminder"; verify = event on the day grid.
- ASSET B (CLI authoring): **Contacts** — blaze the Android half into the unified file
  that already carries the iOS recording; end-card = one file, two `recording:` blocks.
- ASSET C (graph flythrough): **Calendar's 100/64 map**; optional 5-second flash of
  contacts-iOS (103/81) as a second map — proves pattern, not one-off.

*Agent workstreams for Sam to kick off:*
1. **Calendar journey** — author + record the add-event trail on a stock emulator with a
   fresh test Google account; pin the Calendar app version; archive the run (ASSET A
   needs the replay + its report). Acceptance: trail replays zero-LLM; report zip opens.
2. **Contacts unification** — write the unified trail (ios-iphone recording ported from
   test-create-contact-basic; android-phone recording blazed fresh via CLI, captured on
   video for ASSET B). Acceptance: one file, both classifiers, both replay green.
   Stretch: backfill a starter set of Android contacts waypoints so the map story isn't
   iOS-only.

*usa.droidcon.com framing (Claude's take):* the ephemerality is an ARGUMENT, not just a
flaw — "this site will be different next year" is literally the staleness thesis
(recordings rot; the NL journey survives; re-blaze re-materializes). Use as a
moment-in-time flourish (b-roll, or committed as a dated `examples/droidcon-2026/` with
a README saying it's a snapshot that will rot by design). Not the anchor — Sam's own
read: web is less impressive for this room.

## Cross-app demo scenarios (2026-07-12, Sam + Claude design session)

> **STATUS 2026-07-11 (round 3): calendar half PARKED.** Sam: "Let's just do
> contacts then. Let's just ship an example. Time's running out." Scenario B and
> the Etar work below stay as reference/backup narrative; the shipping demo is
> the contacts-with-photo trail — see "Round 3: contacts-only pivot" below.

**Decision: focus = Calendar + Contacts.** Sam wants COMPLEX, CROSS-APP use cases
("that usually works out pretty well") showing multiple layers. His sketch: start from
a message → create contact → add picture → save number → back to the message → send to
the new contact. His observation to honor: with `target: contacts`, only the contacts
custom tools load — the other apps run on CORE tools (tapOn, inputText, launchApp,
assertVisibleWithText, assertWithAi…). That texture change at the app boundary is a
TEACHING moment, not a bug. Docs also list **cross-app memory** as a built-in agent
feature (docs/index.md, Mobile-Agent-v3 lineage) — grounding for the narration.

### Scenario A — "Unknown number → teammate" (flagship wow b-roll; ASSET B candidate)
```yaml
- config:
    title: "Cross-app: turn an unknown number into a contact, then reply by name"
    target: contacts
- prompts:
    - step: Open Messages and find the conversation from the unknown number ending in 0134
    - step: Start creating a contact for that number from the conversation
    - step: Name the contact "Casey Trailblaze" and save the number as mobile
    - step: Add a photo to the contact using the camera
    - verify: The contact card shows the name, mobile number, and photo
    - step: Return to the conversation in Messages
    - verify: The conversation now shows "Casey Trailblaze" instead of the raw number
    - step: Reply "Welcome to the team!" and send it
    - verify: The sent message appears in the thread
```
Layers: 3 apps (Messages → Contacts → Camera → Messages) · custom tools ONLY inside
contacts (createContact/addPhoneNumber/verifyContactStructure) · core-tool fallback
outside · the thread-renames-itself assertion = observable cross-app side effect ·
same NL on iOS/Android with genuinely divergent recordings (Google Messages "Add
contact" chip vs iOS thread-header → Info → Create New Contact) · iOS photo leg lands
on committed waypoints (camera_capture, visual_identity_photo, photo crop).
**Feasibility traps:** Android emulator — inbound SMS seedable (`adb emu sms send
<num> <text>`; candidate trailhead tool via host-side exec), outbound shows in-thread
w/o delivery = verify against the thread, camera = emulated scene. **iOS Simulator has
NO SMS path (no simctl sms verb)** → record the iOS variant on a REAL iPhone, or demote
iOS to the contacts-only core (create + photo + verify). NOT CI-safe as committed
example because of the SMS dependency — treat as recorded b-roll.

### Scenario B — "Invite the new teammate" (Calendar × Contacts; committable + ASSET A candidate)
**UNIFIED — same NL on Android AND iOS (Sam confirmed wanting the iOS stock version).
Steps name the JOB, not the platform's UI vocabulary** (Android says "Add people,"
iOS says "Invitees" — the recordings diverge, the NL never does):
```yaml
- config:
    title: "Cross-app: create a contact, then invite them to the talk"
    target: contacts   # or a composed demo trailmap — open question below
- prompts:
    - step: Create a contact "Casey Trailblaze" with email casey.trailblaze.demo@example.com
    - verify: The contact shows the name and email
    - step: Open the calendar app and go to July 17
    - step: Create an event "Trailblaze: Map Your App for AI" at 1:00 PM in room "W222 B"
    - step: Invite Casey Trailblaze to the event
    - step: Set a 30-minute reminder
    - step: Save the event
    - verify: July 17 shows the event with Casey invited and a reminder set
```
**Android — two tracks (post clean-CI probe, see subsection below):**
- *Recorded b-roll track (Sam's own Pixel, Google Calendar):* every calendar beat lands
  on a COMMITTED waypoint: quick_create_expanded, add_location, add_people +
  add_people_typing (guest autocomplete pulling from Contacts = the cross-app payoff),
  notification_picker, event_detail_with_attendees. ASSET C can light up BOTH graphs
  and trace this trail across them. Stretch beat (timing-fiddly): let the reminder fire
  and snooze from the shade — event_notification_fired + snooze_options waypoints exist.
- *Committed CI track (clean AOSP image, per Sam's constraint):* the committed calendar
  trailmap targets `com.google.android.calendar` resource-ids — NOT present on the clean
  image, and the stock AOSP Calendar is view-only (stripped editor). CI runs on sideloaded
  **Etar** with a trailhead-seeded local calendar (full recipe below). Etar waypoints
  would be NEW (`ws.xsoh.etar` ids) — which is a feature, not a bug: blazing them fresh
  is exactly what ASSET B shows.

**iOS (stock Apple Contacts + Apple Calendar):** beat-for-beat mirror; Invitees
autocomplete searches Contacts. **GATE — now governing under the clean-CI constraint:
the Invitees row only appears on an invite-capable calendar (iCloud/Google/Exchange);
a bare simulator's local "On My iPhone" calendar hides it, and Sam ruled out signing
in.** So the accountless iOS variant REPLACES the invite beat. Candidates (agent spike
to pick): (a) give Casey a **birthday** during contact creation → the stock Birthdays
calendar shows it — still a genuine Contacts→Calendar cross-app payoff, zero accounts
(verify Birthdays calendar appears accountless on a fresh simulator); (b) event with
location + 30-min reminder only, verify: on the event detail. Contacts half is iOS's
STRONG side (typed tools + 103 waypoints) and already runs accountless in
ios-contacts-trails.yml (macos-26 / iPhone 17 Pro).

**Symmetry to narrate:** Android = calendar mapped / contacts thin; iOS = contacts
mapped / calendar unmapped. Same trail runs on both — closest-wins recordings +
core-tool fallback absorb it. Stage line: "maps mature independently; the journey
doesn't care."

**Trailhead detail (feeds slide 19's narration):** Calendar's first Contacts-autocomplete
triggers a one-time permission dialog → replay non-determinism. Pre-grant in the
trailhead: `xcrun simctl privacy booted grant contacts com.apple.mobilecal` /
`adb shell pm grant`. A concrete trailhead story instead of generic "clear app data."

**Caveat, resolved by the clean-CI design:** on a LOCAL (sync-adapter-less) calendar
with no account on the device, saving never emails anyone — the invite is a provider
row, not an outbound message. Belt-and-suspenders: the contact uses **@example.com**
(reserved domain, no real mailbox). The old "designated test address" worry only
applies to the Google-Calendar b-roll track on Sam's own device.

### Clean-CI probe results (2026-07-11, live on disposable AVD `tb-probe-clean-34`)

**Sam's governing constraint (verbatim intent):** run from a **clean stock emulator or
simulator**, **no sign-in at all**, so the demo can run in the open-source CI.

Probed empirically on `system-images;android-34;default;x86_64` (the same family
clock-trails.yml uses — reactivecircus/android-emulator-runner, api-level 34):

1. **What the clean image ships:** com.android.calendar / contacts / dialer / messaging
   all present. CalendarProvider has **ZERO calendars** out of the box.
2. **Stock AOSP Calendar is view-only.** Its editor is stripped from the APK —
   INSERT intent crashes internally (`ClassNotFoundException:
   com.android.calendar.event.EditEventActivity`) and bounces to week view. Dead end
   for any create-event demo. (Google Calendar is not on `default` images.)
3. **Stock AOSP Contacts editor fully works accountless.** Contact "Casey Trailblaze"
   + email created via UI, lands in ContactsProvider (raw_contacts _id=1) on the
   device-local account.
4. **Fix: sideload Etar** (`ws.xsoh.etar`, F-Droid, OSS fork of the *unstripped* AOSP
   Calendar, ~8.4 MB, apk cached fine for CI). Full accountless editor: title, date
   (Jul 17 2026 reachable), time, **Guests**, location, reminder, recurrence.
5. **Cross-app payoff VERIFIED end-to-end:** typing "Casey" in Etar's Guests field
   pops ContactsProvider autocomplete → "Casey Trailblaze
   casey.trailblaze.demo@example.com" → tap → chip → DONE saves → provider shows
   `attendees: event_id=3, attendeeName=Casey Trailblaze,
   attendeeEmail=casey.trailblaze.demo@example.com`. That `content query` on
   `content://com.android.calendar/attendees` is a deterministic `verify:` hook.

**Trailhead recipe (all adb, all CI-safe, each line has a why):**
```bash
# 1. Seed a LOCAL calendar — provider ships empty; editor needs a writable calendar.
#    URI must be single-quoted through adb shell (the & otherwise splits the command).
adb shell 'content insert --uri "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=local&account_type=LOCAL" --bind account_name:s:local --bind account_type:s:LOCAL --bind name:s:Local --bind calendar_displayName:s:Local --bind calendar_color:i:-16776961 --bind calendar_access_level:i:700 --bind ownerAccount:s:local --bind visible:i:1 --bind sync_events:i:1'
# 2. Install Etar (full calendar editor).
adb install etar.apk
# 3. Disable the stripped stock Calendar → Etar becomes the ONLY calendar app:
#    no app-chooser dialog ever appears, and "open the calendar app" is unambiguous.
adb shell pm disable-user --user 0 com.android.calendar
# 4. Pre-grant runtime permissions — kills two replay-nondeterminism landmines:
#    without READ_CONTACTS the guest autocomplete SILENTLY shows nothing;
#    without exact-alarm access Etar's first editor open bounces to Settings.
adb shell pm grant ws.xsoh.etar android.permission.READ_CONTACTS
adb shell appops set ws.xsoh.etar SCHEDULE_EXACT_ALARM allow
```

**Etar facts for the waypoint/tool authors:**
- `EditEventActivity` is **not exported** → launch via implicit INSERT intent only
  (`am start -a android.intent.action.INSERT -t "vnd.android.cursor.item/event"`),
  never by explicit component. With stock Calendar disabled there's no chooser.
- Attendees row sits below the fold → dismiss keyboard + scroll before it's visible.
  Resource-ids: `ws.xsoh.etar:id/attendees`, `add_attendees_row`, `attendees_icon`.
- The autocomplete suggestion popup renders in a separate window that a plain
  uiautomator main-window dump misses (screenshot sees it) — nice concrete beat for
  the driver/a11y-tree discussion if it comes up in Q&A.
- Force-stop before re-firing INSERT: a foregrounded Etar swallows the intent into the
  existing task ("intent delivered to top-most instance") without opening the editor.

**NL stays unified across all three calendars.** "Open the calendar app / create an
event / invite Casey" runs against Etar (CI), Google Calendar (Sam's Pixel b-roll),
and Apple Calendar (iOS) — recordings and waypoints diverge per app/platform, the
journey doesn't. (Don't over-claim on stage: recordings are keyed by platform
classifier, so an Etar recording doesn't replay on Google Calendar — it's the NL
source that's shared.)
Share the finished contact card from Contacts into Messages (share sheet = OS
connective tissue; Android intents vs iOS share sheet, same NL).

### Open technical question for the agent workstreams (answer FIRST)
Sam's wrinkle — "we'd only get contacts tools" — may have a better answer than "that's
fine": trailmaps compose via `dependencies:` (docs/trailmaps.md: transitive, field-level
closest-wins; waypoints wired through loadResolvedRuntime). **Empirically check whether a
tiny `trailmaps/droidcon-demo/` with `dependencies: [trailblaze, contacts, calendar]`
surfaces BOTH apps' tools + waypoints in one run.** If yes: the demo trailmap IS the
composition story on stage ("maps compose"). If no: run Scenario B with target:
contacts and narrate the boundary honestly (calendar side on core tools — still lands
on calendar waypoints? verify; waypoints may load only for the active trailmap).

### Layer inventory the pair covers (for slide narration)
custom tools ↔ core tools boundary · waypoint assertions inside the map · cross-app
side-effect verification (thread rename, guest autocomplete) · camera/permission
interruptions · unified NL + divergent per-platform recordings · trailmap composition
(if dependencies pan out) · zero-LLM replay of a 3-app journey.

Format note: the side-by-side IS the thesis — left (blaze, LLM thinking, slow) still
working while right (replay, zero LLM) finishes. Let the speed gap be the punchline.
Could show a wall-clock/step counter on each side.

## Round 3: contacts-only pivot (2026-07-11) — THE SHIPPING DEMO

**Sam's call:** drop the calendar half, contacts only, ship an example now, and
make it richer than create-basic — "maybe one that takes a photo of the contact."

**Shipped trail: `trails/contacts/create-contact-with-photo/blaze.yaml`** — one
unified NL source, both platforms, no `driver:`/`platform:` pinned (CLI `-d`
decides; config driver is optional — TrailblazeDesktopApp.kt resolves request
driver first). Target `contacts` already declares BOTH `com.android.contacts`
(clean AOSP image) and `com.google.android.contacts`, plus iOS — no trailmap
changes needed. The photo step is the one-line cross-platform philosophy:

```yaml
- step: Add a profile photo to the contact — take a new photo if there's a
        camera, otherwise pick the first photo in the library
```

Android emulators have a camera; iOS Simulators don't (library-pick wins there,
after `xcrun simctl addmedia booted <photo>.jpg` seeds it). Same NL, honestly
divergent recordings.

### Android photo flow — probed end-to-end on clean AOSP api-34 (`default`)

**FOUR app surfaces, zero accounts, provider-verifiable** — richer cross-app
story than the calendar version:
1. Contacts editor ("Saving to **Device**" — accountless local save); camera icon
   on the avatar → "Change photo" dialog: **Take photo / Choose photo / CANCEL**
2. Take photo → `com.android.camera2` CaptureActivity, live **virtualscene**
   feed (the low-poly-cat living room) → shutter → review with
   `com.android.camera2:id/{done_button,retake_button,cancel_button}`
3. done → `com.android.gallery3d` filtershow **CropActivity** → SAVE
   (`com.android.gallery3d:id/filtershow_done`)
4. back in the editor with the photo as header → SAVE →
   `content query --uri content://com.android.contacts/contacts --projection
   display_name:photo_uri` → `photo_uri=content://com.android.contacts/display_photo/1`
   = the deterministic verify hook.

**Device/CI facts:**
- AVD camera: `hw.camera.back=virtualscene` (pretty) or `emulated` (avdmanager
  default, test-pattern) — BOTH capture fine. CLI flag `-camera-back virtualscene`
  works too. reactivecircus runner: supplying `emulator-options` REPLACES its
  defaults (`-no-window -gpu swiftshader_indirect -no-snapshot -noaudio
  -no-boot-anim`) — repeat them and append `-camera-back virtualscene`.
- "Choose photo" on a clean image = EMPTY gallery → the NL conditional
  naturally resolves to camera-on-Android, library-on-iOS.
- No permission dialogs anywhere in the Android flow (system apps).
- No trailhead seeding needed at all for contacts (unlike calendar).

### Android recording — EARNED (2026-07-11, blaze on clean AVD, 6m 41s, Succeeded)

`trails/contacts/create-contact-with-photo/android.trail.yaml`, driver
`ANDROID_ONDEVICE_ACCESSIBILITY`. Every beat pinned to deterministic selectors —
zero per-run LLM: `contacts_android_launchApp` (custom tool) → notification-
permission ALLOW recovery → FAB → name/phone/email inputs → photo hop
(`photo_touch_intercept_overlay` → "Take photo" → `camera2:id/shutter_button` →
`done_button` → `gallery3d:id/filtershow_done`) → save → 3-assert verify (incl.
`contentDescriptionRegex: Call Mobile 555-0134` and the photo overlay).
The agent saved early on the email step, then RE-ENTERED edit mode for the photo
— recorded honestly, replays consistently. "Recordings are earned, not written"
now has a live exhibit. `session save` gotchas: requires `--title`, and it
slugifies the title (colon included) into the output dir — move the file next
to blaze.yaml after. Shared-machine gotcha: the user-level daemon
(~/.trailblaze) is a singleton — a concurrent trailblaze session (a separate
nightly-validation session was live on this machine) can stop/replace it
mid-run: replay attempt 1 died "Daemon unreachable", attempt 3 delegated to THE
OTHER session's daemon (wrong config dir → `contacts_android_launchApp` "not
registered in this session's tool repo"). **Escape hatch: `trailblaze run
--no-daemon`** — in-process, immune to daemon contention; use it for local
replays whenever another session might be active.

### iOS probe results (2026-07-11, agent, 3 fresh accountless sims) — PHOTO BEAT BLOCKED ON iOS 26.x

- Contacts launches accountless fine; fresh sims are NOT empty: stock sample
  contacts (John Appleseed et al.) AND 6 stock photos IMG_0001–0006 — so "pick
  the FIRST photo in the library" is ambiguous there; seeded photo lands as
  IMG_0007.
- `simctl addmedia` seeding works (Photos.sqlite confirmed).
- **BLOCKER: tapping "Photos" in the Add-Photo avatar sheet CRASHES Contacts**
  — SIGABRT `AG::precondition_failure` (SwiftUI AttributeGraph/ViewGraph), 4/4
  reproducible, on iOS 26.5 AND 26.4, via BOTH maestro XCTest taps and axe HID
  taps → OS bug in the simulator runtime, pre-picker, not driver-related.
  Crash reports: `~/Library/Logs/DiagnosticReports/Contacts-2026-07-11-*.ips`.
  (These popped Sam's macOS "Contacts quit unexpectedly" dialogs — ReportCrash
  surfaces simulator crashes like native ones.)
- iOS 26.x avatar sheet has NO Camera option (Photos/Monogram/Memoji/Emoji) —
  as predicted, sims have no camera. iOS 18.6 uses a different poster-based UI
  (Camera tile present but greyed); Photos tap there didn't crash but rendered
  black/empty — unverified further.
- A11y vocabulary harvested for future waypoints: sheet `Choose an Avatar`, ids
  `IdentityTypePicker`, `NewPhotoButton` (`photo.on.rectangle.angled`),
  `NewMonogramButton`, `NewMemojiButton`, `NewEmojiButton`,
  `OnboardingCancelButton`, `SuggestionListItemButton` (labels `,Serif`
  `,Sans-serif` `,Rounded` `,Condensed`), containers
  `VisualIdentityEditorNavigationStack` / `VisualIdentityView`; contacts-list
  add button = a11y text `add`.

**iOS options for the unified trail (Sam to pick):**
1. Earn the iOS recording for everything EXCEPT the photo beat and pin the photo
   step to the explicit per-platform no-op (`ios-iphone: []`) with a comment
   linking the OS bug — the unified format's escape hatch demonstrated for real.
   (Do NOT blaze the photo step on iOS — the agent would tap Photos and crash
   Contacts again.)
2. On iOS let the profile picture resolve to a MONOGRAM (native, no picker, no
   crash — untested past sheet render) — requires rewording the NL to "give the
   contact a profile picture" instead of photo-specific.
3. Talk-level fallback: Android carries the photo journey; iOS story rides the
   existing `trails/ios-contacts/` corpus already green in CI (create-then-delete
   showcase). Zero new iOS work before Jul 16.

### Ship checklist
- [x] blaze.yaml committed (NL source)
- [x] Android recording EARNED (blaze Succeeded, 6m 41s) + saved next to source
- [x] Recording maintenance from replay attempts: pruned ONE dead recorded
      assert (strict two-line email text the blaze itself had immediately
      superseded with a looser one) + widened the launch wait 3s→8s (cold-start
      after `pm clear` on a loaded host)
- [~] Zero-LLM replay: steps 1–3 PROVEN replaying (attempt 4); full-pass
      validation BLOCKED by host contention, not by the trail — the emulator
      ANR'd ("System UI isn't responding") while a separate nightly-validation
      session ran Gradle tests + an eval emulator + 2 iOS sims concurrently.
      Re-run on a quiet machine or let CI be the clean room:
      `adb shell pm clear com.android.contacts && adb shell pm clear
      com.android.providers.contacts`, then
      `TRAILBLAZE_CONFIG_DIR=$(pwd)/trails/config trailblaze run --no-daemon
      --device android/<serial> trails/contacts/create-contact-with-photo/android.trail.yaml`
      (AVD `tb-probe-clean-34` kept; camera already `virtualscene`)
- [x] iOS decision (round 4, Sam): "It's a bug, so let's simplify and do
      something that has parity" → see Round 4 below
- [ ] CI wiring: clone clock-trails.yml shape → contacts trail; only flip
      `docs/showcase-trails.yml` android slug AFTER recording is committed
      (slugs are load-bearing: report-assets bucket + artifact names)

## Round 4: the parity suite (2026-07-11) — CRITICAL JOBS × 2 PLATFORMS

**Sam's framing:** "The goal is to say 'here are X amount of tests that are
critical jobs to do' and we have trails for iOS and Android effectively."
The photo trail STAYS committed as the Android depth-extra (platforms can go
deeper where the OS allows); the HEADLINE becomes the suite.

**The suite — 4 unified NL sources in `trails/contacts/`, zero platform
vocabulary, every beat parity-safe (no photo picker, no accounts):**
1. `create-contact/` — name + phone + email → verify card (photo trail minus photo)
2. `find-contact/` — create, back to list, search "Casey" → verify result
   (fresh iOS sims ship stock sample contacts — unique name keeps it unambiguous)
3. `add-phone-to-contact/` — create name-only, edit, add number → verify
4. `delete-contact/` — create, delete w/ confirmation → verify GONE
   (mirrors the already-CI-green iOS create-then-delete flow)

Self-containment rule: every trail creates what it needs — any subset runs on
a fresh CI device in any order. Slide math: **4 jobs × 2 platforms = 8 green
runs from 4 files.**

### Recording-earning status
- [x] Android × 4 — blazed 2026-07-11 evening (AVD `tb-probe-clean-34`,
      `--no-daemon`, pm-clear reset + POST_NOTIFICATIONS pre-grant per trail),
      replay-verified zero-LLM same night, committed + pushed.
- [x] iOS × 4 — blazed 2026-07-12 morning on fresh accountless iPhone 17 Pro /
      iOS 26.5 sim (erased between trails), replay-verified zero-LLM,
      committed. Avatar sheet never touched.
- [x] Replay-verify both platforms locally (quiet host) — all 8 green.
- [x] CI: `contacts-trails-android.yml` (workflow_dispatch + PR paths-filter on
      trails/contacts/**) replays the 4 Android recordings sequentially on the
      api-34 default image. showcase-trails.yml untouched. Not yet exercised —
      first run happens on a manual dispatch or the PR.

### Earning results — THE SLIDE NUMBERS (blaze = agent+LLM, replay = zero-LLM)
| trail | Android blaze | Android replay | iOS blaze | iOS replay |
|---|---|---|---|---|
| create-contact | 3m15s | **84s** | 2m43s* | **167s** |
| find-contact | 2m02s | **78s** | 5m59s | **78s** |
| add-phone-to-contact | 12m11s | **91s** | 2m40s* | **100s** |
| delete-contact | 1m56s | **90s** | 3m01s | **93s** |

Replay times include ~30s cold-start (relaunch after full state reset) + CLI
startup; the on-screen action portion is far shorter. Android add-phone's 12m
blaze = the agent flailing on phone entry before recovering — an honest
"blaze once" exhibit (its replay is still 91s).

*iOS phone-entry gotcha (NEW, slide-worthy): the iOS editor shows raw digits
(5550134); dash formatting appears only on the saved card. Both agents
(TRAILBLAZE_RUNNER ×2, MULTI_AGENT_V3 ×1) looped asserting '555-0134' in the
editor until the 50-call per-objective cap. Fix: blaze-time hint in a SCRATCH
copy of the source ("type with text input; editor shows raw digits — don't
assert dash format"), then the recording's step text restored to the canonical
NL. Committed blaze.yaml never touched. Good acceptance-gate beat: the agent
earned every tool; the human supplied one sentence of platform truth.

Recording maintenance from replay verification (same class as the photo trail):
- android create-contact: added launchApp RESUME after the custom launch tool
  (custom tool returns at `am start`; blaze think-time masked the cold start);
  moved the SAVE tap into the otherwise-unrecorded 'Save the contact' step (an
  unrecorded step = LLM on every replay = fails in CI's sentinel-key env).
- ios create-contact: pruned four dead recorded asserts — one pinned the email
  text to the app-root element (aggregated child text at blaze time only),
  two used strict multi-line expectedText ('mobile\n555-0134'), one expected
  'home,'-prefixed email text. All were superseded during the blaze itself by
  the looser single-line asserts that survive; the row's aggregated a11y text
  differs between blaze and replay, so only exact single-element text re-matches.
- `trailblaze run` auto-saves a classifier-named recording (android-phone /
  ios-iphone) next to the source after every SUCCESSFUL run — including
  replays. Use `--no-save-recording` on replays or keep deleting closest-wins
  dups. Session-save daemon can also re-emit OTHER sessions' variants.

## Cohesion pass #2 (2026-07-12, Claude solo + independent cold reader, merged)

Same method as pass #1: my full-flow pass + an independent cold read, merged. 15
cold-read findings; 12 applied, 3 rejected with reasons. LARGE changes:

1. **Act 2 now rhymes end-to-end (the cold read's "one change").** Slide 19 redesigned:
   headline promoted to "One file = the user journey"; the trailhead's two recordings
   are THE SAME two recordings from the flaw slide (android-tablet `tapOn: id`,
   ios-iphone `tapOn: "Sign In"` + `inputText: {{account_email}}`), custom tools
   (myapp_*) moved OUT of the example (they arrive with the robot pattern, Act 3/4).
   16 → 17 → 19 is now one continuous artifact: same journey, same two classifiers.
2. **Slide 20's v1-echo objection preempted.** New on-slide line: "not the old flaw:
   blaze.yaml is the only source — recordings are earned artifacts · drop one,
   re-materialize." Note scripts the full answer (v1 files were each the SOURCE;
   recordings are lockfile-like outputs; inline vs sibling shapes = same model).
3. **Asset slides retitled audience-facing** (own half): "Zero-LLM trail replay" /
   "Walking the report that replay generated" / "An agent blazing a trail" /
   "Your app, as a map" — ASSET ids moved to corner markers.
4. **Maestro introduced properly at the driver slide** (was a parenthetical first
   mention): "we started on Maestro's driver stack — Android is now our own · iOS:
   still Maestro's XCTest runner · web: Playwright, now full-fidelity."
5. **"Target" removed from the emotional-peak slide** (device-target vs app-target
   collision): now "Point the CLI at your app's trailmap → it exposes the toolbox."
6. Backup CI slide cites the real public workflow (contacts-trails-android.yml).

Note-level fixes: trailmap re-tease on the binary slide → callback; Act-2→3 hand-off
no longer reuses "duplication"; self-heal off-by-default vs %-signal closer; "human
edits" scoped vs "earned, not written" (maintenance vs authorship — launchMode: RESUME
in the committed recording is the real example); Act-0 assertions note says "pinned,"
not "handwritten"; trailhead section-vs-tool shape check; close's "tests" wordplay
marked as a deliberate trade.

REJECTED (with reasons): retitling "The missing link" (the link metaphor pays off at
"The link? Never broken."); cutting the trail-runner-UI mention (Sam asked for it,
round 5); reordering trailheads before the unified slide (ordering frozen; YAML gloss
comment + shape-check note instead).

## Round 5: Sam's slide-by-slide pass (2026-07-12, applied same night)

All 13 slide notes applied to the deck; slide numbers = Slidev numbering at time of review.
The load-bearing POSITIONS Sam gave (these outlive the edits):

- **User journeys (sl.5, retitled round 9 — was "one login flow"):** don't dwell on
  login; it's the spoken example only. "User journey" = the canonical UX term (industry:
  critical user journey / CUJ) for what a user must always be able to complete. The
  round-5 honesty still applies: MFA, first-time account setup, etc. are their own
  journeys, each written once. What you get is a **trailhead**, and within it you skip
  the normal noise (onboarding screens, select-a-default). Slide stays clean (wink line
  tried, Sam cut it round 5.1); speaker note carries the honesty.
- **Iconography rule (sl.9, global):** 🥾 boot is the working icon; **question every
  compass 🧭** and prefer the boot — EXCEPT trail-map contexts, which get 🗺️. Deck's
  only compass (trailmap slide) → 🗺️. Applies to all future edits.
- **Parameterization (sl.19/21):** Sam is NOT comfortable selling trail-level
  parameterization — it works through memory seeding today and "the implementation is
  pretty gross." Tool parameterization (typed, well-defined args) is the strong story;
  lead with it. `memory.` prefix REMOVED from the unified example. Next step (if asked):
  first-class trail params — "exactly where this is heading."
- **Parity visualization (sl.20):** boots-in-columns table read as confusing → replaced
  with the trails/contacts/ FILE TREE (one folder per job: blaze.yaml + per-platform
  recordings). Same honesty rule: tree must match the repo on talk day.
- **Web driver history (sl.39):** web used Playwright from day one but early on it was
  Playwright shoved into Maestro's model — Sam was always hesitant about web support.
  Turning point = full fidelity for the browser target (expressing things the way
  Playwright/the browser does). Second proof of the fidelity lesson, not an aside.
- **Same artifacts everywhere (sl.26):** every platform generates the same session
  artifact structure (zippable) + single reporting output — now a bullet on the slide.
- **Toolset composition (sl.29 note):** app targets compose exact include/exclude
  toolsets. Square example: default swipe hit the bottom nav → removed from the toolset,
  replaced with a square-specific mid-screen swipe.
- **Type safety where people live (sl.30):** VS Code & other IDEs + the newer trail
  runner UI — added as a line.
- **QA↔codebase gap (sl.41 note, future problem):** pre-Trailblaze: QA's NL-managed
  tests vs developers' in-repo tests. Journeys now visible across apps, but NO metadata
  links a journey to where it's unit-tested in the codebase. Out of scope; "a problem we
  want to have fixed."
- **Assets (sl.14/28/37/44):** production DELEGATED to chips — task_e23beb14 (blaze +
  replay videos + CLI terminal capture, own emulator on port 5596) and task_23ea1e62
  (waypoint graph subway+normal PNGs, report walk PNGs). Deliverables land in
  droidcon/public/; talk session embeds + commits. Slide 44 shows BOTH graph views
  (subway first). ASSET A/B/C slide notes track the chip ids.

## Round 6: naming, Maestro nod, target terminology (2026-07-12)

**1. Unified naming (Sam's fact, applied to sl.19+20 notes):** previously
`contacts/android.trail.yaml` (folder + siblings); in unified, a plain "contacts" test
is ONE file `contacts.trail.yaml` — recordings inline. Today's repo sibling layout =
the migration in motion; the unified single file = the destination.

**2. Maestro treatment (Sam's spec, applied to sl.39 note + backup 53):** nod warmly —
**Maestro blazed the path**: readable YAML, deterministic steps; we've seen how well
that works. We built on it: custom toolsets, LLM-first, natural language attached to
every step. Credit then difference, one sentence, not a huge deal, never dunk.

**3. "Target inside a trailmap" — terminology rationalization (Sam asked; banked for
Q&A + his own thinking; verified against repo code):**
- **One-liner: the trailmap is the map; the target is the territory it covers.** The
  `target:` block is the map's title plate — a map physically contains the declaration
  of what ground it covers ("Yosemite Valley" on the cover). So target-inside-trailmap
  isn't a nesting accident; it's how maps work.
- Implementation enforces the metaphor (TrailblazeTrailmapManifest.kt): `target:`
  presence is the DISCRIMINATOR. Trailmap WITH target = a map of somewhere, runnable
  (contacts, clock). Trailmap WITHOUT target = a **library trailmap** — shared
  toolsets/tools, forbidden to declare waypoints. A map with no territory is a legend.
- The target block contains exactly territory coordinates: `app_ids` per platform,
  `base_url` for web, `min_build_version` — plus which toolsets apply there. Everything
  journey-shaped (waypoints, trailheads, shortcuts, trails) lives OUTSIDE the block.
- Target id defaults to the trailmap id (1:1, id-aliased) — the map is named after its
  territory, like every real map. That's why `--target contacts` and "the contacts
  trailmap" feel interchangeable: same name, two layers.
- Why "target" and not a hiking word: it must generalize across an Android app id, an
  iOS bundle, a URL, a Compose composition (TrailConfig kdoc: alias | package ID | URL)
  — "app" breaks for web, "park" is whimsy in a CLI flag. And devs already parse it:
  Xcode targets, Gradle targets, deployment targets. It's the one deliberately
  engineering-flavored word, placed exactly where the metaphor touches the real world.
- Axis check (clean in code): **target = what app is under test; device = where it
  runs** — never conflated; `ResolvedTarget` pairs them ("what app is under test on
  what device"). Talk caveat: slide 6's "Seven targets" uses the Square build-target
  sense (device families) — Trailblaze's `target:` key appears nowhere on-deck, so the
  two senses never collide on a slide; just don't say "target" for both in the same
  breath out loud.

## Gap analysis (2026-07-12, Claude + independent reviewer, merged & ranked)

_Round-1 statuses applied 2026-07-12 after Sam's answers. Deck is now **51 slides**
(economics slide, ASSET B slide, positioning backup added). ✅ done · 🔶 open · ❌ dropped._

### Sam's decisions
1. 🔶 **Demo app — STILL OPEN.** DroidconKotlin REJECTED ("we should use a real app").
   Direction: a real app on a stock Android device. Candidates per Sam: Gmail (✗ privacy —
   people would see his email), **Google Calendar** (maybe), usa.droidcon.com (events site —
   "pretty awesome" as a WEB scenario, less impressive on native mobile).
   Fact in Calendar's favor: the public repo already ships a committed Calendar fixture
   trailmap — `trails/config/trailmaps/calendar/` targeting `com.google.android.calendar`,
   waypoint JSONs + screenshots — so ASSET C's map data mostly exists. Privacy fix for any
   Google app: fresh test account, never Sam's.
2. ❌ **Production evidence — OFF THE TABLE** (Sam: it represents the company/product and
   he can't clear it). Show general user/developer experiences for developer education
   only. "1.5 years in production" stays as a spoken statement; no internal artifacts,
   screenshots, or b-roll. Don't re-raise.
3. ✅ **Reliability — verbal answer only, no number exists.** Banked Q&A wording:
   "We're still getting to our 100% success rate — reliability is good and improving,
   and we're seeing **parity across Android and iOS**." Never invent a number.
4. ✅ **"Recordings are earned, not written"** — approved verbatim (Sam: "love it").

### Content gaps → round-1 status
5. ✅ Economic hook — new slide 3 ("An LLM on every run? Slow. Expensive. Non-deterministic.").
   Sam's positioning framing in its note: replay alone is table stakes (Maestro has it);
   the leg up = NL-first + custom tools in TypeScript + full per-platform fidelity.
   ASSET A pt 1 (slide 13) re-specced: side-by-side, agent blaze vs. replay, wall clocks.
6. ✅ verify:-vs-zero-LLM — speaker-note answer on slide 17, verified in UnifiedTrailStep.kt:
   verify steps are assertion-scoped, auto-terminate, NEVER self-healed; recordable like
   any step; NL-only = deliberate per-run LLM assertion, not a leak.
7. ✅ ASSET B slide added (now slide 34, right after the emotional peak).
8. 🔶 Map-as-artifact — REDIRECTED by Sam: show the **waypoint map of the demo app**
   (ASSET C, slide 41, subtitle updated). Sam gets the data; if demo app = Calendar it
   mostly exists in-repo.
9. ❌ Agenda slide — Sam: not needed.
10. ✅ Act 5 cold open — setup line added to slide 36 + **DRIVER TERMINOLOGY CORRECTION
    (Sam):** the before-column is **UiAutomator**, not "Maestro" — Maestro drives Android
    WITH UiAutomator (gRPC → instrumentation APK). iOS = Maestro's on-device **XCTest
    runner** (verified: HostIosDriverFactory.kt imports xcuitest.*; devlog 2026-05-12
    plans the "Maestro divorce" via our own XCTest runner + event-based settle).
    **Guard: name the underlying tech (UiAutomator / XCTest), not the wrapper.**
11. ✅ 39→40 seam — hand-off line in slide 39's note.
12. ✅ Whose-agent — "Driven by your agent: Claude Code · Codex · Goose" on slide 32.
    🔶 Authoring-cost ballpark for backup 46 still needs a number from Sam (or drop).
13. ✅ CLI verbs — real commands on the ASSET B slide (`trailblaze step --save`, `run`).
14. ✅ Slide-22 granularity — verified against CaptureOptions.kt: per-STEP = screenshot /
    hierarchy / LLM transcript; per-RUN = video + device logs (logcat / scoped iOS log
    stream), ON by default with `--no-capture-*` opt-outs. Network + analytics are NOT in
    OSS capture — moved to speaker note as "Block layers more onto the same reports."
15. ✅ Positioning backup added (slide 49) with Sam's framing + lossy-tree ammo.

### Production checklist (by Jul 16)
- ✅ ALL FOUR ASSETS DONE + EMBEDDED (2026-07-12, chips): A pt1 = blaze/replay videos
  w/ real wall clocks 6m26s vs 2m20s (sl.14) · A pt2 = 3-click report walk ending on
  the $0.55/91%-cached LLM bill (sl.28) · B = real terminal transcript PNG (sl.37) ·
  C = subway→map graph build, 100 waypoints/81 shortcuts (sl.44).
- Square hardware imagery for slide 5 (currently emoji) — squareup.com/us/en/hardware.
- QR code for slide 43.
- Timing pinch points: Act 1 (8 slides + video in 5 min), Act 4 (now 10 slides + peak +
  ASSET B in 6.5 min — recheck after asset durations land). Act 5 has the only slack.
- Density pass (skeleton → Sam's 55–65-slide style) — timebox to ~1 day AFTER ordering freeze.
- ✅ SDK snippet verified against sdks/typescript: `trailblaze.tool<I,O>(spec, handler)`
  overload, `supportedPlatforms` registration-gate field, `ctx.tools.*` all real.

### Verified clean (no action)
All 7 core-points rounds represented · accuracy spot-checks pass (3x conservative, waypoint
counts labeled as fixture, self-heal defaults, A* costs, product names, brew tap, tsc trick)
· waypoints correctly future-framed · "hike" absent · 7 targets never drifts to 8.

## Ideas

_(brainstorm below — promote the keepers into the deck)_

## Parked / rejected

_(keep the trail of what we decided against and why)_

## Round 7: whole-deck cohesion pass #3 (2026-07-12, post-assets)

Full re-read + independent cold-read agent after all four assets embedded. 14 findings,
all applied to the deck (note-level unless marked). What changed:

**Accuracy (the two that could bite on stage):**
- Slide 28 RETITLED "Walking a real run's report" (was "…that replay generated") —
  the walked report is a BLAZE's (session create_contact_b66e486e has the 20-call/$0.55
  LLM bill; a replay report would bill $0.00). Note now pins the session lineage:
  NOT the same session as the slide-14 videos (create_contact_3958). Don't tie them.
- Slidev presenter mode only shows a slide's LAST comment block — three slides had
  spoken content in an invisible FIRST block (The LLM twist; Tools wanted out of the
  binary, incl. the Square swipe composition story; Next: waypoints). Merged. Deck-wide
  check: no slide has split blocks anymore.

**Staleness purged:** cover note (assets are embedded, v6), slide 19/20 notes
(iOS recordings committed, tree VERIFIED vs repo 2026-07-12 — extras in the folder
are expected: with-photo, 3 segment trails, regen/), slide 37 backup-PNG marker.

**Number defenses added to notes:** 39-vs-22-vs-20 (report actions vs NL steps vs
LLM calls, different sessions); `tap` vs `tapOn` both real (coordinate primitive vs
selector — transcript verbatim); ~3x headline vs ~5x table (end-to-end vs per-action);
Calendar map count NEEDS VERIFY before quoting (both maps at exactly 100 waypoints
smells like a display cap — Contacts 100/81 confirmed from screenshots).

**Clarity/flow:** slide 12 tease clause for "custom tools" (undefined until Act 4);
19→20 seam line now ALWAYS-SPOKEN (inline shape = spec + Block production, siblings =
migration in motion); "update = add-phone" caption mapping; `--secret` = "a CLI flag";
waypoints→map bridge ("scaffolding draws it; navigating over it is what's unproven");
slide 34 marked `optional` on-slide (matches cut-priority #3) — Act 4 is the pacing
pinch: 10 slides / 6.5 min incl. the peak + ASSET B; watch it in the dry run.

**New content:** BYOK bullet on the cost backup slide — verified from docs:
`trailblaze config llm`, openai/anthropic/google/ollama/openrouter, enterprise
endpoints + self-hosted via YAML config, keys via env vars.

**Backup CI slide:** now says "Android side of the parity suite" + honesty note:
the workflow is committed but has never been TRIGGERED; iOS has its own pre-existing
workflow on main.

**Cold reader's do-not-touch list (it's right):** Act 0 seven-targets build, Act 1
spine, Act 2 rhyme, Act 3 arc, the 35–37 peak, the close, the backup bank.

## Round 8: presenter-simulation pass (2026-07-12, Sam asked "present it to me")

Full listener walk of all 55 slides in their post-pass-3 state. The arc holds: every
hand-off chains, no undefined term survives (the two forward teases are armed), and
the act timings sum to ~36:30 + Q&A. Four NEW gaps found and filled (all note-level,
nothing projected changed):

1. **Sl.14 replay-speed objection** — an Espresso mind may fixate on "2m20s is slow."
   Ammo added to the slide note + Q&A preempts: minutes = app + emulator + one-time
   ~35s startup; framework tax = 100–150ms/action; price vs the blaze, not a unit test.
2. **Sl.38 missing setup** — a cold listener doesn't know WHO rewrote hundreds of YAML
   files. Spoken setup clause added (tool param change → mechanical rewrite of every
   recording that calls it; suites don't run on PR checks → used to surface late in CI).
3. **Sl.46 CTA "grab the skill" grounded** — the skill is REAL: `skills/trailblaze/
   SKILL.md` in the repo. OPEN QUESTION FOR SAM: the repo README never mentions it, so
   QR scanners won't find "the skill" by skimming — add a README pointer on main before
   the talk, or leave for the post-talk docs task?
4. **Sl.39 count guard** — web debuts in the main deck here; the Act 0 grid was seven
   DEVICE targets. Phrase: "seven device targets, plus web via Playwright" — never
   "eight targets."

Deliberately NOT flagged (checked and fine): "Most flaky tests die right here" (sl.22 —
ambiguous on paper, lands when spoken); Act 5 = one slide / 2.5 min (correct breather
after the peak, note carries the content); videos autoplay-loop on sl.14 (verified).

## Round 9: synced to main upstream #209 (2026-07-12 evening)

Sam: "keep on top of block/trailblaze main." Fetched — one new commit, `ce44af30`
"Upstream 2026.07.12 (#209)" (+5,508/−1,016 across 80 files). What matters for the talk:

- **Unified format LANDED in public docs.** docs/project_layout.md now defines the
  canonical shape: one journey = one `<journey>/trail.yaml` (NL steps + per-device
  `recording:` slots keyed by classifier; device with a slot replays deterministically,
  device without runs the prose through the agent; optional `trailhead:`). A standalone
  `<name>.trail.yaml` works zero-config. **blaze.yaml and classifier-named
  `*.trail.yaml` files are officially labeled LEGACY** (still replayable). Sample-app
  examples renamed to the unified shape. → Slides 19/20 notes updated: the seam line is
  now "shipped to main's docs and examples this week."
- **Healed-run save-back:** new `HealedRunUnifiedSaveBackTest` (+186) — healed runs
  save back into the unified file. Strengthens the self-heal story if asked how heals
  persist.
- **README fully rewritten** (~272 lines) — new framing: "Natural-language device
  control for your coding agent — across iOS, Android, and web"; leads with pointing
  Claude Code/Cursor/Codex/Goose at the CLI; "twenty selector strategies per element
  only works because an agent is driving." Very aligned with the talk's Act 4/5 framing.
  Still NO mention of skills/trailblaze/SKILL.md → block/trailblaze#191's acceptance criterion
  stands.
- **MCP server expanded** (+753 in TrailblazeMcpServer, descriptor/target-scoped tool
  tests). `trailblaze mcp` proxy still exists (STDIO default, HTTP option) — the CLI-pivot
  slide's disarming line still true.
- **Parameterization naming settled (Sam, verbal):** `args` = trail run args ·
  `params` = tool call params · `memory` = session-based memory. NOT in #209 — lands
  in a FUTURE upstream. Parameterize-slide note carries the check-main-before-stage rule.
- Deck/branch mechanics: talk branch stays where it is (based off #208's main) — do NOT
  rebase before the talk; the parity corpus + committed assets are the stage truth.
  Post-talk, a rebase picks up the unified docs cleanly.

## Round 10: Act 0 rethread — messaging, not nitpicks (2026-07-13)

Sam (practicing): "This isn't a bunch of small nitpicks… more of a messaging request."
The new Act 0 arc, inside the SAME seven slots (no renumbering anywhere):

**HYPE → DREAM → GAP(×2) → QUALITY UNIT → SCALE → CREDENTIALS**

1. Cover — 🥾 on "Trailblaze", 🗺️ on "Map Your App for AI" (Sam: icons on lines 1+2).
2. WAS "AI can drive your app / why still hard" → NOW "AI is supposed to do
   *everything*" + two clicks: the dream made concrete (*"create a contact for
   Casey"* → agent drives a real phone → it just… does it), then the turn (falls
   short on mobile: controlling devices · testing what matters). NL now enters HERE
   (Sam: "start with that around 2 — say 'do xyz' and it just does it"), so slide 4
   no longer jumps. The example string = THE demo journey (slide 14 videos, Act 2).
3. UNCHANGED slide, new role: falls-short #1 — the naive fix ("agent on every run")
   fails CI economics.
4. "The missing link" REWRITTEN as the catch: it did it — but what exactly did it
   do? · run *that* again, deterministically? · objective ↔ actions link lost · the
   what, thrown away. "Per-platform" moved to SPOKEN (bridge to Seven Targets).
5. "One user journey." + new sub-line: *what a user must always be able to do — the
   unit of quality* (Sam: journeys are what we care about from a testing/quality
   perspective). Note: this names hard-problem #2's unit; Trailblaze-helps-BOTH
   lands at the Act 1 hand-off.
6. Unchanged (round 9 already: "Hundreds of trails today.").
7. "Ten years" + REAL title-slide images: talk-2016.jpg (actual 2016 title slide,
   from Sam's own Speaker Deck — files.speakerdeck.com slide_0) and talk-2025.jpg
   (droidcon NYC 2025 branded card w/ Sam + Brian, from the session video thumbnail).
   Hand-off upgraded: answer to BOTH problems.

Also: Act 1 thesis note (slide 8) now frames "Blaze once, trail forever" as the
one-move answer to both problems; audience-poll paste re-anchored to the new opener;
Act 0 editorial summary rewritten to the new arc.
Provenance for the images (public sources, Sam's own talks):
- 2016: speakerdeck.com/handstandsam/espresso-a-screenshot-is-worth-1-000-words
- 2025: youtube.com/watch?v=DReniYeZe5o (hqdefault thumbnail)

## Round 11: cover fix, real-world ask, trailmap reveal, the BALANCE (2026-07-13)

- **Cover:** trailing emoji on the title lines "sucked" (Sam; confirmed on screenshot —
  broke optical centering, boot clashed with the wordmark). Now: clean title, small
  spaced 🥾🗺️ motif line between subtitle and byline. If it still reads wrong, KILL the
  icons entirely — Sam already authorized that.
- **Slide 2 example is real-world now:** "Validate the new real-time cart updates — and
  make it a test." (Sam: "create a contact" too simple for an audience building
  world-class apps; register = dev-to-dev, problem→how-we-tried-to-solve.) Demo corpus
  stays contacts (public reproducibility) — hedge unchanged on Four Jobs.
- **Slide 4 tees the reports:** "what exactly did it do?" is a planted promise, paid
  twice — recordings (what to replay, Acts 1–2) and session reports (what happened +
  full context for humans AND the LLM, Act 3). Act 3 capture slide now says the
  callback explicitly.
- **Slide 7:** title cards inline on their own rows (2016 next to its line, 2025 next
  to its), both h-40, 2025's YouTube letterbox cropped to true 16:9 (sips -c 270 480).
- **Slide 9 crushes the tee-up:** click reveals the REAL contacts trailmap.yaml from
  the repo (id / dependencies / target / platforms / android app_ids + launch tool;
  ios/web/compose + 100 waypoint lines elided as honest comments). Waypoints comment
  plants Act 6.
- **THE BALANCE (load-bearing, Sam verbatim-ish):** test accounts & setup are REAL —
  AI can't do without that; trailheads exist to hand AI that reality. You can't have
  EVERYTHING-AI — people who've done NL-only want to code things (so do we). Trailblaze
  strikes the balance by letting AI call YOUR code via tools + trailheads you
  contribute. Landed: trailheads slide (new bullet + note), Act 4 opener note, Q&A
  shield for "why not pure prompts?".
- **ASSET A regen is GO (Sam: "embarrassingly slow"):** old videos show pre-#212 code.
  Video session tasked with re-earning blaze+replay on the rebased branch (fast code),
  producing new mp4s + wallclock, keeping BEFORE/AFTER — delta gets commented on
  block/trailblaze#210. Deck numbers sweep on delivery (captions, both spoken-ammo
  notes, parity table, Q&A answer, Act 5 per-action figure).

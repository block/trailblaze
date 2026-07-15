# Android contacts map — sources & regeneration

Provenance and regen recipe for the Android side of the contacts trail map
(**37 waypoints · 38 shortcuts** under `trails/config/trailmaps/contacts/`,
committed 2026-07-14). Everything here is what another machine needs to
reproduce, verify, or extend that map. The deck slide "The product mapped
itself" and the ASSET C section of `../samdroidcon-notes.md` describe the
same pipeline from the stage side.

## Environment

- AVD: profile **Nexus 6** (1440x2560 — coordinates in the multi-select
  shortcut assume this), image `system-images;android-34;default;arm64-v8a`,
  stock AOSP Contacts (`com.android.contacts`).
- Seed the contact DB first: `./seed-contacts.sh [serial]` — the example
  screenshots, list/search waypoints, and the `a286` ref in the multi-select
  shortcut all assume these 8 contacts (Casey Trailblaze 555-0134 is the one
  every detail-screen trail opens).
- ALWAYS export `TRAILBLAZE_PORT` before any `trailblaze` command. A bare
  invocation — even `--help` — autostarts a daemon on the default port and
  will collide with any other daemon convention on the machine.
- The graph viewer (`trailblaze app --headless`) binds `TRAILBLAZE_PORT`
  **and PORT+1** (HTTPS). A run daemon on PORT+1 kills the viewer with
  "Address already in use" — stop it first.

## micro-trails/

One replayable trail per mapped state (00–33), each ending ON the state it
names; a few capture two states in passing (e.g. 00 also passes the
notification-permission prompt and empty list). Replay:

```sh
TRAILBLAZE_PORT=<port> trailblaze run micro-trails/<n>-<state>.trail.yaml \
  --device android/<serial>
```

Force-stop `com.android.contacts` between trails — they launch with
`launchMode: RESUME` so app data (the seeded contacts) survives.

## The mining pipeline (waypoint → example pair)

1. **Explore**: `trailblaze snapshot --device android/<serial>` prints the
   live UI tree — waypoint selectors are authored from the anchors the tree
   itself exposes.
2. **Author**: a waypoint is ~3 `androidAccessibility` selectors (title,
   resource-id, one landmark). Regexes are **FULL-match**: `^Sharing` fails
   against "Sharing 1 file"; `^Sharing \d+ files?$` matches. Waypoint YAMLs
   auto-scan from the waypoints directory — no trailmap.yaml registration.
3. **Walk**: replay the state's micro-trail.
4. **Log**: `trailblaze verify "<one-line state description>" --device ...`
   — a verify CLI session writes ONE log carrying a real node tree AND a
   real screenshot, which is exactly what the miner wants. (`trailblaze
   config target contacts` once per workspace; `verify` has no `--target`.)
5. **Mine**: `trailblaze waypoint capture-example --id contacts/android/<x>
   --target contacts --session <verify-session-dir-name>` → writes the
   sibling `.example.json` + `.example.webp` pair and self-validates.

Debug loop when a selector won't match: `trailblaze waypoint locate
--target contacts --file <session>/<n>_TrailblazeLlmRequestLog.json` — the
near-miss output names the exact failing selector.

Example pairs must be **classifier-less** (`<base>.example.json` + `.webp`)
for graph nodes to render screenshots. Verify-session mines come that way
for free; mines from run sessions get classifier-suffixed names — rename
both files AND the embedded `screenshotFile` field. `capture-example`
needs `--force` to overwrite an existing pair.

## Trailheads (entry points)

Three trailheads bootstrap the agent into the contacts map from ANY device
state (the graph draws them as dashed entry edges from a virtual origin —
they're what anchors the layout):

- `contacts_launch` → **cross-platform**, one destination per platform via
  `trailhead.toByPlatform` (`android: contacts/android/list-populated`,
  `ios: contacts/ios/list`). Wraps `contacts_launchApp`, the TS front door
  that delegates to the iOS/Android impls by `ctx.device.platform`
  (bun-tested).
- `contacts_android_createContact` → `new-contact-editor` (system
  `ACTION_INSERT` intent, package-pinned with `-p`)
- `contacts_android_viewContact` → `contact-detail` (`ACTION_VIEW` with the
  contact id resolved on-device in a single `sh -c` pipeline)

`toByPlatform` is additive on this branch: the model, graph builder, and
viewer expand one trailhead into one entry edge per platform destination
(deduped to a single pill in all-platforms views). Old binaries that
predate the field skip the sidecar with a load warning and lose only that
entry — scalar-`to:` sidecars load everywhere. Platform entry tools live
in `tools/` with an inline `trailhead: { to }` block, PLUS a thin
`trailheads/*.trailhead.yaml` sidecar aliasing it under a distinct id.
Both layers are required today: the runner manifest reads the TS-inline
block, but the graph viewer discovers trailheads only from
`trailheads/*.trailhead.yaml` (upstream #202 removed the sidecars, which
silently emptied the viewer's trailhead layer — candidate upstream
report). All three Android intents were verified live against the mapped
waypoints before authoring.

## Known issues worked around (candidate upstream reports)

- **Run-session screenshot persistence writes 0-byte files** (observed
  2026-07-14 on the homebrew binary): run sessions keep real node trees in
  their LLM-step logs but the sibling PNGs land empty, and the session-final
  SnapshotLog silently vanishes (the "📸 Final screenshot captured" console
  line prints on non-throw, not on success). CLI sessions (`snapshot`,
  `verify`) persist real bytes — hence step 4 above.
- **Long-press degrades to a plain tap** on the on-device accessibility
  driver — both `tapOnPoint {longPress: true}` and ref-based `longPress`
  fire ACTION_CLICK behavior. Reliable press-and-hold: zero-distance
  `dragTo` (same-point `toX`/`toY`, `durationMs: 1500`) — that is how
  `contacts_android_listLongPressToMultiSelect.shortcut.yaml` works.
- `takeSnapshot` (screenName:) silently no-ops in delegated replay.
- Daemon-autostart ignores `TRAILBLAZE_DISABLE_DAEMON_AUTOSTART` /
  `TRAILBLAZE_IPC` on the homebrew binary.

## Verifying the result

The demo viewer must run **from source on this branch** — installed
binaries predate `toByPlatform` and the deep-link params:

```
TRAILBLAZE_PORT=<port> ./gradlew :trailblaze-desktop:run --args="app --foreground --headless"
```

(`--foreground` matters: without it the CLI spawns the *installed*
`~/.trailblaze/install/trailblaze.jar` as a detached daemon and exits, so
source changes are never served.)

Setting `TRAILBLAZE_CONFIG_DIR=<repo>/trails/config` matters under
gradle: the run task's working directory is not the repo root. On this
branch the graph endpoint's default root prefers live workspace
resolution (that env var, then a cwd walk-up to
`trails/config/trailblaze.yaml`) over the persisted trails-directory
setting, so plain graph URLs serve full waypoint data — screenshots
included — from wherever the process was launched. A per-request
`?root=<dir>` query param still overrides everything (useful for
comparing branches). Symptom of a wrong root: the graph renders all
nodes but every screenshot is blank (classpath-bundled YAMLs carry no
example images).

Open `/waypoints/graph`: Target **contacts** → Platform **android**
should read **37 WAYPOINTS · 38 SHORTCUTS** with a screenshot in every
node (the header says "Trailmap", the stat label "waypoints" — renamed
2026-07-14 in the map-view restyle); SUBWAY view with focal
`contacts/android/list-populated` + Fit View is the money shot.

The URL hash deep-links every control (keys: `target`, `platform`, `view`,
plus subway's `focal`/`depth`/`dest`), so you can bookmark exact demo
states:

- `/waypoints/graph#target=contacts&platform=android` — just the Android
  contacts map
- `/waypoints/graph#target=contacts&view=subway&focal=contacts%2Fandroid%2Flist-populated&depth=3`
  — the subway money shot

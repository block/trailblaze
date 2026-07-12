# Contacts parity suite — regeneration playbook

The four trails under `trails/contacts/{create-contact,find-contact,add-phone-to-contact,delete-contact}`
are ONE natural-language source each (`blaze.yaml`) plus two earned zero-LLM
recordings (`android.trail.yaml`, `ios.trail.yaml`). Recordings are earned, not
written — this directory holds the scripts and the operational knowledge needed
to re-verify or re-earn them from scratch at any time.

(`create-contact-with-photo` is the separate showcase journey and is not
covered by these scripts.)

## Prerequisites

- Trailblaze CLI on PATH.
- `bun install --frozen-lockfile` in `sdks/typescript` — without it the custom
  scripted tools fail with "Unsupported tool type for RPC execution".
- All scripts set `TRAILBLAZE_CONFIG_DIR=<repo>/trails/config` themselves.
- Earning (blazing) needs a real LLM key in the environment; replaying does not
  (recordings run zero-LLM — that's the point, and it's what CI relies on).

Devices:

- **Android**: any API-34 Google-APIs emulator (CI uses api-level 34, Nexus 6,
  x86_64). Scripts pick the first `adb devices` entry or honor
  `TB_ANDROID_SERIAL`.
- **iOS**: a FRESH, accountless iPhone 17 Pro / iOS 26.5 simulator. Create a
  throwaway probe, export its UDID, delete it when done:

  ```sh
  UDID=$(xcrun simctl create tb-contacts-parity-probe \
    com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro \
    com.apple.CoreSimulator.SimRuntime.iOS-26-5)
  export TB_IOS_UDID="$UDID"
  # ... earn / replay ...
  xcrun simctl shutdown "$UDID"; xcrun simctl delete "$UDID"
  ```

  A fresh simulator ships stock sample contacts (John Appleseed et al.) — the
  trails are written for that; the unique name "Casey Trailblaze" keeps every
  search and assertion unambiguous.

## ⚠️ iOS 26.x crash bug

NEVER touch the Add-Photo avatar sheet in the iOS Contacts app — tapping
"Photos" in it crashes Contacts (OS bug, reproduces on 26.0–26.5). None of the
four flows go near it; keep it that way when writing hints or new steps.

## Replay-verify (the routine health check)

```sh
./trails/contacts/regen/replay-android.sh create-contact find-contact add-phone-to-contact delete-contact
./trails/contacts/regen/replay-ios.sh     create-contact find-contact add-phone-to-contact delete-contact
```

Each trail gets a full state reset first (Android: `pm clear` both contacts
packages + re-grant POST_NOTIFICATIONS; iOS: `simctl erase` back to the
pristine image). Success is judged from the log (`Results: 1 passed`), never
from the exit code — see hazards below.

## Re-earning a recording from scratch

```sh
./trails/contacts/regen/earn-android.sh <trail-name> [...]
./trails/contacts/regen/earn-ios.sh     <trail-name> [...]
```

Each earn blazes the `blaze.yaml`, session-saves with the canonical title
("Contacts: <job>" on Android, "Contacts iOS: <job>" on iOS), and moves the
recording into place. Then ALWAYS do the post-earn checklist below and
replay-verify before committing.

### The phone-entry gotcha (iOS) — hinted scratch blaze

"Add the mobile number 555-0134" fails unhinted on iOS, reproducibly, with
both agents: the editor shows raw digits (`5550134`) while dash formatting
appears only on the saved card, so the agent loops asserting `555-0134` in the
editor until the per-objective LLM cap (`--max-llm-calls` does not lift it).

Fix: copy the `blaze.yaml` somewhere OUTSIDE the trail directory, reword only
the phone step with a blaze-time hint (e.g. "…type the raw digits 5550134
using the text-input tool; the editor shows unformatted digits — dash
formatting only appears on the saved card, so don't assert formatting in the
editor"), then:

```sh
./trails/contacts/regen/earn-ios-scratch.sh <trail-name> /path/to/scratch/blaze.yaml "Contacts iOS: <job>"
```

Afterwards RESTORE the canonical step text inside the earned recording (the
`step:`/`verify:` strings must match the committed `blaze.yaml` verbatim) and
never commit the scratch copy. Precedent: `create-contact` and
`add-phone-to-contact` iOS recordings, earned exactly this way (see the
maintenance comments inside them).

### Post-earn checklist (all learned the hard way)

1. **Every step must have a `recording:` block.** A step without one calls the
   LLM on every replay — which fails in CI where the key is a sentinel. If the
   blaze folded an action into a neighboring step (e.g. the SAVE tap recorded
   at the tail of the email step), move the tool into the step it belongs to.
2. **Android launch needs `launchApp` with `launchMode: RESUME`** right after
   the custom launch tool. The custom tool returns at `am start`; blaze
   think-time masks the cold start but a zero-LLM replay taps too early. The
   `wait` tool does NOT help — it is settle-based, returning in <1s on a
   stable screen, not a timed sleep.
3. **Prune dead recorded asserts.** Blazes record every assert attempt that
   passed situationally: text pinned to an app-root element that aggregated
   child text only at blaze time, strict multi-line `expectedText`
   (`mobile\n555-0134` vs the element's `mobile, 555-0134`), `home,`-prefixed
   aggregations. They fail on replay. Keep the loosest single-element assert
   that proves the objective; leave a maintenance comment for each prune.
4. **Delete classifier-named duplicates** (`android-phone.trail.yaml`,
   `ios-iphone.trail.yaml`). Closest-wins resolution means a duplicate SHADOWS
   the maintained recording. `trailblaze run` auto-saves one after every
   SUCCESSFUL run — including replays — hence `--no-save-recording` in the
   replay scripts. Session-save daemons can also re-emit other sessions'
   variants; check `git status` before committing.
5. **Replay-verify the final file zero-LLM** before committing, on the same
   fresh state CI will have.

## Runner hazards the scripts already handle

- `trailblaze run` can linger indefinitely after a failed run (trace-post
  retry). All scripts background the run, poll the log for `^Results:`, allow
  a grace period, then kill — and judge success from the log text.
- A killed run leaves its report-server port (52525) closing and a stale
  `~/.trailblaze/daemon-52525.pid` behind; the next run refuses to start over
  either. The scripts wait for the port to free and remove the pidfile.

## Expected timings (measured 2026-07-11, M-series host)

| trail | Android blaze | Android replay | iOS blaze | iOS replay |
|---|---|---|---|---|
| create-contact | 3m15s | 84s | 2m43s* | 167s |
| find-contact | 2m02s | 78s | 5m59s | 78s |
| add-phone-to-contact | 12m11s | 91s | 2m40s* | 100s |
| delete-contact | 1m56s | 90s | 3m01s | 93s |

\* hinted scratch blaze. Replay times include ~30s cold-start + CLI startup.
A replay drifting far past these numbers usually means an assert is polling
toward timeout — check the log named in the script output.

## CI

`.github/workflows/contacts-trails-android.yml` replays the four Android
recordings sequentially via `.github/pr_run_contacts_trails_android.sh`
(state reset per trail, `--no-daemon --no-save-recording`, sentinel LLM key).
Triggers: manual dispatch, or PRs touching `trails/contacts/**`.

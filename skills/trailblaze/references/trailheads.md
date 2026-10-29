# Trailheads — start every trail from a known state

Load this when a trail needs a starting point: authoring a new trail, making a recording
re-runnable, or fixing a trail that passes locally but fails in CI.

## What a trailhead is

A **trailhead** is the trail's deterministic step 0. It is one tool that takes the device from any
state to a known one: the right data cleared, the right permissions granted, the app open and on
screen, signed in if the test needs it. No LLM runs it.

Without one, a trail starts wherever the device happens to be. That works on your laptop and fails
in CI.

**Exploring vs testing:**

- **Exploring**: the agent calls `openApp` to open or bring the app to the front. It never clears
  data, restarts the app or changes permissions, so exploring cannot wreck the state you were
  looking at.
- **Testing**: the trail opens with your target's trailhead, which spells out every state change
  it makes.

`launchApp` is deprecated. Its default mode silently cleared app data, and on iOS it granted every
permission. Recorded trails that use it keep working; write new trailheads from the building blocks
below instead.

## Find one

```bash
trailblaze toolbox trailheads --device android --target <your-app-target>
trailblaze toolbox --name <tool-id>     # full description and params
```

If nothing fits, write one (below). Don't hand-write a multi-step launch sequence inside a trail;
that is the problem a trailhead solves.

## Use it in a trail

```yaml
config:
  id: myapp/checkout-smoke
  target: myapp
trailhead:
  step: Open the app signed in, on the home screen
  recording:
    all:
      myapp_launchApp: {}
trail:
- step: Add an item to the cart
  ...
```

- `recording: all:` records the trailhead **once, for every platform**. `all` is the fallback
  classifier every device resolves to.
- A trailhead classifier maps to **one tool call** (a map), not a list like a `trail:` step. Put
  multiple actions inside the tool, not in the trail.
- Pass accounts and passwords with `--secret` or your team's account store. Never hard-code them in
  the trail.

## Recommended shape: one entry point, one tool per platform

Give your target **one** trailhead that every trail uses, and have it hand off to one internal tool
per platform:

```text
myapp_launchApp               ← the trailhead; recorded under `all:` in every trail
 ├─ myapp_android_launchApp   ← hidden; the Android steps
 └─ myapp_ios_launchApp       ← hidden; the iOS steps
```

Trails stay platform-free, and each platform's steps live in their own tool, so you can change one
platform without touching the other. Put each tool in its own file under the trailmap's `tools/`
directory. Nothing to list in `trailmap.yaml`: every scripted tool in `tools/` is on the target,
and each tool's `supportedPlatforms` / `supportedDrivers` scope it to its platform and drivers.

For a small app, a single tool with an `if` on `ctx.device.platform` works too. Split it once the
branches grow.

```typescript
// tools/myapp_launchApp.ts
import { trailblaze } from "@trailblaze/scripting";

/** Opens MyApp signed in on the home screen, from any state, on Android or iOS. */
export const myapp_launchApp = trailblaze.trailhead(
  {
    supportedPlatforms: ["android", "ios"],
  },
  async (_input, ctx) => {
    if (ctx.device.platform === "ios") return ctx.tools.myapp_ios_launchApp({});
    return ctx.tools.myapp_android_launchApp({});
  },
);
```

```typescript
// tools/myapp_android_launchApp.ts
import { trailblaze } from "@trailblaze/scripting";

/** Android half of myapp_launchApp: fresh data, notifications allowed, app on screen. */
export const myapp_android_launchApp = trailblaze.tool(
  { supportedPlatforms: ["android"], surfaceToLlm: false },
  async (_input, ctx) => {
    const appId = "com.example.myapp";
    await ctx.tools.mobile_clearAppData({ appId });
    await ctx.tools.android_grantPermissions({
      appId,
      permissions: ["android.permission.POST_NOTIFICATIONS"],
    });
    await ctx.tools.openApp({ appId });
    // ...then sign in with your own tools.
    return `Opened ${appId} from cleared data.`;
  },
);
```

```typescript
// tools/myapp_ios_launchApp.ts
import { trailblaze } from "@trailblaze/scripting";

/** iOS half of myapp_launchApp: fresh data, location allowed, app on screen. */
export const myapp_ios_launchApp = trailblaze.tool(
  { supportedPlatforms: ["ios"], surfaceToLlm: false },
  async (_input, ctx) => {
    const appId = "com.example.myapp";
    await ctx.tools.ios_terminate({ appId }); // clearing data does not stop a running iOS app
    await ctx.tools.mobile_clearAppData({ appId });
    await ctx.tools.ios_setPermissions({ appId, grant: ["location"] });
    await ctx.tools.openApp({ appId });
    return `Opened ${appId} from cleared data.`;
  },
);
```

## Building blocks

Each one does one thing, so a trailhead reads as a list of exactly what it changes. They are hidden
from the agent: trailheads call them, the agent doesn't.

| Step | Android | iOS (simulator) |
|---|---|---|
| Wipe the app's data | `mobile_clearAppData` | `mobile_clearAppData` |
| Stop the app (keeps data) | `android_forceStop` | `ios_terminate` |
| Set runtime permissions | `android_grantPermissions` (clearing app data revokes them) | `ios_setPermissions` (`reset`, `revoke`, `grant` by simctl service name; `reset: ["all"]` drops grants an earlier trail left) |
| Allow or deny notifications | `android_grantPermissions` (`POST_NOTIFICATIONS`) | `ios_setPermissions` (`notifications`: the driver answers the alert when it appears) |
| Sign out (clear the keychain) | — | `ios_resetKeychain` |
| Grant special-access permissions | `android_grantAppOpsPermission` | — |
| Anything else | `android_adbShell` | — |
| **Open the app and wait until it is on screen** | `openApp` | `openApp` |
| Open with launch arguments (cold start) | — | `ios_openApp` |

Rules of thumb:

- **End with `openApp`.** It returns once the app is up: first frame drawn and in front on
  Android, then a few seconds at most for it to go idle (Android) or settle (iOS). An app that is
  still busy after that is reported, not failed; the next step's wait for its element takes over.
- **On iOS, `ios_terminate` before `mobile_clearAppData`.** Android's clear kills the app; iOS's
  only deletes files, so a running app keeps its in-memory state and `openApp` brings it back.
- **Grant iOS permissions before `openApp`.** simctl kills a running app when a grant changes.
- **Want a cold start without losing sign-in?** Stop the app, then `openApp`. Clearing data signs
  the user out.
- **iOS launch arguments** (a locale, a seeded session, a debug flag) go on `ios_openApp`, which is
  `openApp` for iOS plus `arguments`: `ios_openApp({ appId, arguments: { AppleLanguages: "(es-US)" } })`.
  Values are strings, numbers or booleans, passed as Maestro's `launchApp.arguments` passed them,
  and string values are masked in logs, so a recording needs a memory reference (`{{token}}`)
  rather than a literal. In trail YAML, `true`/`false` is always a boolean, quoted or not. They
  only reach a cold start, so `ios_terminate` first; `ios_openApp` fails if the app is still
  running.
- **Only grant what the test needs.** A trailhead that grants everything hides which permissions
  the app depends on.

## Declaring a tool as a trailhead

- **TypeScript**: `trailblaze.trailhead(spec, handler)` instead of `trailblaze.tool`. No sidecar
  file and no waypoint needed. The optional `to: "<waypoint>"` only draws the trailhead on the
  waypoint graph; nothing checks it, so assert the landing screen in the handler if it matters.
- **Kotlin**: `@TrailblazeToolClass(trailheadTo = "<waypoint>")`.

`toolbox trailheads` lists both. To author the waypoints a trailhead lands on, see
[`compose-agent-surface.md`](compose-agent-surface.md).

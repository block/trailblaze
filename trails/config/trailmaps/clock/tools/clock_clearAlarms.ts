import { trailblaze } from "@trailblaze/scripting";

/**
 * Wipes all alarms, timers, and stopwatch state from the AOSP Desk Clock app via
 * `pm clear` followed by `am force-stop`, so the next launch starts from a fresh
 * data directory. Useful as a setup primitive for trails that need a known-clean
 * Clock state without the side effect of leaving the app open.
 *
 * No arguments — the framework resolves the app id from the `clock` trailmap manifest's
 * `app_ids:` list against installed apps on the connected device. Composes only the
 * dual-mode `android_adbShell` primitive, so this tool works on both host- and
 * on-device-dispatched sessions.
 */
// Implementation notes — a simpler companion to `clock_android_launchApp.ts`: no launch,
// no relaunch, just a single `pm clear` followed by a force-stop.
//
// App-id resolution: `ctx.target?.resolveAppId({ defaultAppId })` — framework-provided
// method, see clock_android_launchApp.ts for the full priority order. `requiresContext:
// true` guarantees `ctx`; we still optional-chain `ctx.target` for target-less sessions.
//
// Why force-stop AFTER pm clear? `pm clear <pkg>` resets the app's user data (preferences,
// databases, files — everything alarms are stored in) but doesn't necessarily kill an
// already-running process. A running clock instance with cached in-memory alarm state can
// re-write its state files on its next lifecycle event. Following with `am force-stop`
// makes sure the next launch comes up against the cleared state.
export const clock_clearAlarms = trailblaze.tool(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    const appId = ctx.target?.resolveAppId({ defaultAppId: "com.android.deskclock" });
    if (!appId) {
      throw new Error("clock_clearAlarms could not resolve an Android app id from ctx.target.");
    }

    // `pm clear` returns "Success" on stdout for a successful wipe and a non-zero exit
    // for a failure (e.g. unknown package). Thanks to `android_adbShell`'s exit-code sentinel
    // detection, a `pm clear` failure surfaces as an Error envelope and our `await`
    // throws — there's no silent "everything looks fine" path on a real failure.
    await ctx.tools.android_adbShell({
      command: ["pm", "clear", appId],
    });
    await ctx.tools.android_adbShell({
      command: ["am", "force-stop", appId],
    });

    return `Cleared alarms/timers/stopwatch state for ${appId} (pm clear + force-stop).`;
  },
);

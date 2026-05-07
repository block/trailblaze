// Custom scripted tool: clear ALL existing alarms, timers, and stopwatch state from
// the Google Clock app — a useful test-setup primitive for trails that need a clean
// initial state. Demonstrates a simpler shape than `clock_android_launchApp.ts`:
// no launch, no relaunch, just a single `pm clear` followed by a force-stop so the
// next launch starts from a fresh state.
//
// App-id resolution: `ctx.target.resolveAppId({ defaultAppId })` — framework-provided
// method, see clock_android_launchApp.ts kdoc for the full priority order.
//
// Why force-stop AFTER pm clear?
//
// `pm clear <pkg>` resets the app's user data (preferences, databases, files —
// everything alarms are stored in) but doesn't necessarily kill an already-running
// process. A running clock instance with cached in-memory alarm state can re-write
// its state files on its next lifecycle event. Following with `am force-stop` makes
// sure the next launch comes up against the cleared state.
//
// Composition over `adbShell` (dual-mode primitive) means this tool works both when
// the daemon dispatches it on host (today) and on-device (when cell 3 of the matrix
// lights up). No `requiresHost: true` needed on the descriptor.

/**
 * Registered as `clock_clearAlarms` by the workspace `clock` pack.
 *
 * @param {Record<string, never>} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function clock_clearAlarms(args, ctx, client) {
  if (!ctx) {
    throw new Error("clock_clearAlarms requires a live Trailblaze session context.");
  }
  const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.deskclock" });
  if (!appId) {
    throw new Error("clock_clearAlarms could not resolve an Android app id from ctx.target.");
  }

  // `pm clear` returns "Success" on stdout for a successful wipe and a non-zero exit
  // for a failure (e.g. unknown package). Thanks to `adbShell`'s exit-code sentinel
  // detection, a `pm clear` failure surfaces as an Error envelope and our `await`
  // throws — there's no silent "everything looks fine" path on a real failure.
  await client.callTool("adbShell", {
    command: `pm clear ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });

  return `Cleared alarms/timers/stopwatch state for ${appId} (pm clear + force-stop).`;
}

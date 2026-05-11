// Custom scripted tool: launch Google Calendar via Android shell commands —
// uses the dual-mode `adbShell` primitive so the same body works on host-
// dispatched and (future) on-device QuickJS scripted-tool execution.
//
// App-id resolution: `ctx.target.resolveAppId({ defaultAppId })` consults
// `ctx.target.resolvedAppId` (framework-resolved at session start, picking
// whichever of the manifest's `app_ids` is actually installed), falls back
// to `ctx.target.appIds[0]`, then to the caller's `defaultAppId` if neither
// is reachable. Returns `undefined` if all three layers miss — hence the
// explicit no-resolve check below.

/**
 * Registered as `calendar_android_launchApp` by the workspace `calendar` pack.
 *
 * @param {Record<string, never>} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function calendar_android_launchApp(args, ctx, client) {
  if (!ctx) {
    throw new Error("calendar_android_launchApp requires a live Trailblaze session context.");
  }
  const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.calendar" });
  if (!appId) {
    throw new Error("calendar_android_launchApp could not resolve an Android app id from ctx.target.");
  }

  // `adbShell` (dual-mode, `requiresHost: false`) over `runCommand` with a
  // raw `adb shell` host invocation: avoids the host `adb` PATH dependency
  // and works on dadb / remote-server wiring too. Matches the same launch
  // pattern used by clock / contacts after the adbShell migration in #2777.
  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${appId}`,
  });

  return `Launched ${appId} (force-stop + am start MAIN/LAUNCHER).`;
}

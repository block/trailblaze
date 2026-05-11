// Custom scripted tool: launch the Google Contacts app via Android shell commands.
// See the sibling clock_android_launchApp.ts for the full rationale (force-stop + am
// start covers stop-and-relaunch).
//
// App-id resolution: `ctx.target.resolveAppId({ defaultAppId })` — framework-provided
// method, see clock_android_launchApp.ts kdoc for the full priority order.

/**
 * Registered as `contacts_android_launchApp` by the workspace `contacts` pack.
 *
 * @param {Record<string, never>} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function contacts_android_launchApp(args, ctx, client) {
  if (!ctx) {
    throw new Error("contacts_android_launchApp requires a live Trailblaze session context.");
  }
  const appId = ctx.target?.resolveAppId({ defaultAppId: "com.google.android.contacts" });
  if (!appId) {
    throw new Error("contacts_android_launchApp could not resolve an Android app id from ctx.target.");
  }

  // See sibling clock_android_launchApp.ts for the rationale on `adbShell` over the
  // Maestro-shaped `launchApp` and on `am start` over `monkey`. `adbShell` is dual-mode
  // (`requiresHost: false`), so this tool composes cleanly whether the daemon dispatches
  // it on host or, in the future, on-device.
  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${appId}`,
  });

  return `Launched ${appId} (force-stop + am start MAIN/LAUNCHER).`;
}

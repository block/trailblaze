// Custom scripted tool: launch the Wikipedia (en) app via Android shell commands.
// See sibling clock_android_launchApp.ts for the full rationale on the choice of
// `adbShell` over the Maestro-shaped `launchApp`, and `am start` over `monkey`.
//
// App-id resolution: `ctx.target.resolveAppId({ defaultAppId })` — a framework-
// provided method that consults `ctx.target.resolvedAppId` (framework-resolved
// at session start), falls back to `ctx.target.appIds[0]` (first declared
// candidate), then to the caller's `defaultAppId` if neither is reachable.
// Returns `undefined` when nothing's reachable and no default is supplied.

/**
 * Registered as `wikipedia_android_launchApp` by the workspace `wikipedia` pack.
 *
 * @param {Record<string, never>} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function wikipedia_android_launchApp(args, ctx, client) {
  if (!ctx) {
    throw new Error("wikipedia_android_launchApp requires a live Trailblaze session context.");
  }
  const appId = ctx.target?.resolveAppId({ defaultAppId: "org.wikipedia" });
  if (!appId) {
    throw new Error("wikipedia_android_launchApp could not resolve an Android app id from ctx.target.");
  }

  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${appId}`,
  });

  return `Launched ${appId} (force-stop + am start MAIN/LAUNCHER).`;
}
